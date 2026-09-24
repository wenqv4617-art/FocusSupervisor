package com.focussupervisor.app.core.ai

import android.util.Log
import com.focussupervisor.app.core.ai.prompt.ChatTemplate
import com.focussupervisor.app.core.ai.prompt.PromptProfile
import com.focussupervisor.app.core.ai.prompt.PromptProfiles
import com.focussupervisor.app.core.network.ChatTurn
import com.focussupervisor.app.core.network.OpenAiCompatibleClient
import com.focussupervisor.app.data.repository.AiConfigRepository
import com.focussupervisor.app.data.repository.AppPolicyRepository
import com.focussupervisor.app.data.repository.ConversationRepository
import com.focussupervisor.app.data.repository.GazeRepository
import com.focussupervisor.app.data.repository.LocalLlmRepository
import com.focussupervisor.app.data.repository.LocalModelRepository
import com.focussupervisor.app.data.repository.MemoryRepository
import com.focussupervisor.app.data.repository.PersonaRepository
import com.focussupervisor.app.data.repository.PromptCacheRepository
import com.focussupervisor.app.data.repository.TextEmbedder
import com.focussupervisor.app.data.repository.TimelineRepository
import com.focussupervisor.app.domain.model.AiCommand
import com.focussupervisor.app.domain.model.AiConfig
import com.focussupervisor.app.domain.model.AiCommandLimits
import com.focussupervisor.app.domain.model.ChatMessage
import com.focussupervisor.app.domain.model.EmbeddingConfig
import com.focussupervisor.app.domain.model.MemorySource
import com.focussupervisor.app.domain.model.MessageSender
import com.focussupervisor.app.domain.model.TodoItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import java.util.UUID

/** 一次发送的结果。 */
sealed interface SendOutcome {

    /** 模型回复已经上屏。 */
    data object Sent : SendOutcome

    /** 还没有可用的端点配置。 */
    data object NotConfigured : SendOutcome

    /**
     * 发送失败。
     *
     * [message] 是已经翻译好的中文，调用方可以直接拿去显示。引擎同时也会往会话里
     * 插一条系统胶囊 —— 这样用户能在聊天记录里看到「哪一次失败了、为什么」，
     * 而不是只看到一个转瞬即逝的提示。
     */
    data class Failed(val message: String) : SendOutcome
}

/**
 * 对话编排层。
 *
 * ===========================================================================
 * 它负责什么
 * ===========================================================================
 * ```
 *  用户输入
 *    → 落库（对话仓库）
 *    → 召回记忆（向量 or 关键词）
 *    → 组装 system 提示词（前置提示 / 人设 / 记忆三层 / 待办 / 白名单 / 指令协议）
 *    → 调模型
 *    → 解析回复：拆出「给人看的正文」与「给系统执行的指令」
 *    → 正文落库并上屏
 *    → 执行指令，每条产出一条系统胶囊
 *    → 把新出现的对话与记忆补进向量索引
 * ```
 *
 * ===========================================================================
 * 为什么这一层值得单独存在
 * ===========================================================================
 * 上面那条链路有九个步骤、跨了五个仓库。如果写在 ViewModel 里，那个类会被业务
 * 逻辑淹没，而且**换一个前端（比如将来做桌面版）就得把它抄一遍**。
 * 放在这里之后，ViewModel 只剩三件事：收集状态、转发用户意图、显示结果。
 *
 * 另一个好处是**出错处理只有一个地方**：所有异常在这里被翻译成一句中文 + 一条
 * 系统胶囊。ViewModel 不需要认识 `AiClientException`，界面更不需要。
 */
class ConversationEngine(
    private val client: OpenAiCompatibleClient,
    private val policy: AppPolicyRepository,
    private val memory: MemoryRepository,
    private val conversation: ConversationRepository,
    private val personas: PersonaRepository,
    private val aiConfig: AiConfigRepository,
    private val timeline: TimelineRepository,
    private val cache: PromptCacheRepository,
    private val gaze: GazeRepository,
    private val localModel: LocalModelRepository,
    private val localLlm: LocalLlmRepository,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /**
     * 端侧生成。
     *
     * ===========================================================================
     * 提示词在这里才被渲染成字符串
     * ===========================================================================
     * 云端交出去的是 `messages` 数组（角色由服务端按自己的模板渲染）；
     * 端侧交出去的是**一个纯字符串**，ChatML 或 Llama 3 的标记要我们自己拼，
     * 末尾那个 assistant 引导头更是少不得（见 [ChatTemplate]）。
     *
     * @param onPartial 每个分片回调一次，界面据此做打字机效果。
     */
    private suspend fun generateLocally(
        profile: PromptProfile,
        turns: List<ChatTurn>,
        onPartial: (suspend (String) -> Unit)?,
    ): Result<String> {
        val engine = localLlm.ensureReady().getOrElse { return Result.failure(it) }
        val prompt = ChatTemplate.render(profile.format, turns)

        val builder = StringBuilder()
        return try {
            engine.generate(prompt).collect { delta ->
                builder.append(delta)
                // 分片先上屏，再等下一个。界面那边只做字符串拼接，不会有重活。
                onPartial?.invoke(delta)
            }
            if (builder.isEmpty()) {
                Result.failure(IllegalStateException("端侧模型没有输出任何内容"))
            } else {
                Result.success(builder.toString())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (throwable: Throwable) {
            Log.e(TAG, "端侧生成失败", throwable)
            Result.failure(throwable)
        }
    }

    /**
     * 端点是不是跑在**这台手机**上。
     *
     * ===========================================================================
     * 为什么这件事决定了整个提示词预算
     * ===========================================================================
     * 云端与端侧的成本结构完全不同：
     *
     *  - 云端：长提示词只是多花点钱，缓存命中还能打折。10k token 无所谓。
     *  - 端侧：长提示词**直接就是等待时间**。SoC 的预填充速度是每秒几百 token，
     *    10k token 意味着用户按下发送之后要盯着屏幕半分钟才看到第一个字。
     *
     * 所以预算按运行位置分轨，而运行位置只有两个判据：
     *  1. 用户在配置里明确选了端侧模型（[AiConfig.useLocalModel]）；
     *  2. 地址指向本机环回（本机 Ollama 就是这种情况）。
     *
     * 不认 `10.0.2.2`：那是 Android 模拟器里访问**开发机**的地址，
     * 算力在电脑上而不在手机上，按端侧给它压预算没有意义。
     */
    /**
     * 按档位给出实际用于请求的配置。
     *
     * 唯一会覆盖用户设置的是**采样温度**，而且只在端侧生效。
     * 理由是具体的：0.5B 级模型对温度极其敏感 —— 0.7 会让它开始自由发挥、
     * 车轱辘话来回说，而这些小模型恰恰是「照着格式输出」比「有创意」重要得多。
     * 云端不动用户的值：那是他手调的，中大型模型也完全承受得住。
     */
    private fun effectiveConfig(config: AiConfig, profile: PromptProfile): AiConfig {
        val recommended = profile.profileTemperature ?: return config
        return if (isLocalEndpoint(config)) config.copy(temperature = recommended) else config
    }

    private fun isLocalEndpoint(config: AiConfig): Boolean {
        val url = config.baseUrl.lowercase()
        return url.contains("127.0.0.1") ||
            url.contains("localhost") ||
            url.contains("0.0.0.0") ||
            url.contains("[::1]")
    }

    /**
     * 把文本转向量的适配器。
     *
     * 记忆仓库不知道网络的存在，这里把两者接起来。没配向量模型时它返回失败，
     * 记忆仓库会安静地退回关键词召回 —— 不需要在这里做任何判断。
     */
    private val embedder = TextEmbedder { texts ->
        embedTexts(aiConfig.embeddingConfigNow(), texts)
    }

    /**
     * 向量计算的唯一出口。
     *
     * 在线与本地是**互斥的两条路**，不做自动回退：用户明确选了本地模型，
     * 就该只走本地 —— 一旦偷偷回退到在线端点，「这次为什么变慢了 / 这次为什么
     * 花了钱」就变成一个没法解释的现象。模型没下好时直接返回失败并带上原因，
     * 记忆仓库会安静地退回关键词召回，功能不会整个坏掉。
     *
     * 向量走**独立的**配置，不复用对话端点 —— 现实中这两件事经常不在一家
     * （DeepSeek 没有公开的 embeddings 接口；中转站往往只代理对话模型）。
     */
    private suspend fun embedTexts(
        config: EmbeddingConfig,
        texts: List<String>,
    ): Result<List<List<Float>>> {
        if (!config.useLocalModel) return client.embed(config, texts)

        val engine = localModel.ensureEngineReady().getOrElse { throwable ->
            return Result.failure(throwable)
        }
        return engine.embed(texts)
    }

    /**
     * 发送一条用户消息并处理完整的回复链路。
     *
     * **不会抛异常**（除协程取消外）。任何失败都会变成本方法返回的 [SendOutcome.Failed]，
     * 同时往会话里插一条系统胶囊。
     */
    /**
     * @param onPartial 流式分片回调。端侧模型逐字产出时会调用它（打字机效果）；
     *        云端路径目前是一次性返回，不会调用。为 null 表示不需要流式。
     */
    suspend fun send(
        userText: String,
        onPartial: (suspend (String) -> Unit)? = null,
    ): SendOutcome {
        val text = userText.trim()
        if (text.isEmpty()) return SendOutcome.Sent

        // 阶段标记。整条链路跨了五个仓库、九个步骤，一旦出事，光看异常类型
        // 根本不知道该往哪查。把它带上，界面上那条胶囊就直接指出了出问题的环节。
        var phase = "准备"

        try {
            // ---- 1. 用户消息先落库。哪怕后面网络失败，这句话也已经记下来了。----
            phase = "保存消息"

            // 上一次对话的时间必须在**插入这条消息之前**取，否则取到的就是刚写进去的这条。
            // 它是提示词里「距上一次对话 X」的来源 —— 断了两小时和断了三天，
            // 监督者该说的话完全不同。
            val previousUserMessageMillis = conversation.messages.value
                .lastOrNull { it.sender == MessageSender.USER }
                ?.timestampMillis

            // 本轮输入先做成一个独立的值对象，再落库。
            //
            // 顺序很重要：**先有 CurrentTurn，再 append**。因为 append 之后去读
            // `conversation.messages.value` 拿到的可能是**还没刷新**的旧快照
            // （DataStore 落盘 → Flow 发射 → 仓库内存镜像更新，中间有真实窗口）。
            // 组装器只认手里这个值对象，不再依赖那个快照 —— 这就是「AI 总是回答
            // 上一轮」那个缺陷的根治点，细节见 PromptAssembler 的类注释。
            val currentTurn = CurrentTurn(
                id = newId(MessageSender.USER),
                text = text,
                timestampMillis = clock(),
            )

            conversation.append(
                ChatMessage(
                    id = currentTurn.id,
                    sender = MessageSender.USER,
                    text = currentTurn.text,
                    timestampMillis = currentTurn.timestampMillis,
                ),
            )

            // ---- 2. 端点配置 ----
            phase = "读取端点配置"
            val config = aiConfig.activeConfigNow()
            if (!config.isUsable) {
                return fail("还没有配置 AI 端点。打开「+」→「AI 配置」填好地址与模型再试。")
            }

            // ---- 3. 召回记忆 ----
            phase = "读取向量配置"
            // 档案要先算出来：召回条数跟着它的预算走。
            val runningLocal = config.useLocalModel
            val profile = if (runningLocal) {
                PromptProfiles.resolveStyle(
                    localLlm.selected.value.promptModelName,
                    isLocal = true,
                )
            } else {
                PromptProfiles.resolve(config.model, isLocalEndpoint(config))
            }
            val embeddingConfig = aiConfig.embeddingConfigNow()
            val queryEmbedding = if (embeddingConfig.isUsable) {
                embedTexts(embeddingConfig, listOf(text)).getOrNull()?.firstOrNull()
            } else {
                null
            }
            phase = "召回记忆"
            // 召回条数跟着预算走：端侧只取 1~2 条，云端可以多给。
            val recalled = memory.recall(
                queryText = text,
                queryEmbedding = queryEmbedding,
                limit = profile.budget.recallLimit,
            )

            // ---- 4. 组装提示词 ----
            //
            // 稳定段（进 system）与本轮状态（拼到本轮用户消息末尾）**必须分开**，
            // 混在一起就等于每轮都让上下文缓存失效。理由见 PromptAssembler 的类注释。
            phase = "组装提示词"
            val now = clock()
            val history = conversation.messages.value

            val systemPrompt = PromptAssembler.buildSystemPrompt(
                profile = profile,
                prePrompt = config.prePrompt,
                personas = personas.personas.value,
                coreMemories = memory.coreMemories(),
                shortTermMemories = memory.recentShortTerm(),
            )

            val volatileContext = PromptAssembler.buildVolatileContext(
                profile = profile,
                recalledMemories = recalled.memories.map { it.entry },
                recalledConversation = recalled.conversationSnippets,
                todos = policy.todos.value,
                whitelist = policy.whitelist.value,
                timeline = timeline.events.value,
                vision = gaze.status.value,
                history = history,
                lastInteractionMillis = previousUserMessageMillis,
                nowMillis = now,
            )

            val turns = PromptAssembler.buildTurns(
                profile = profile,
                systemPrompt = systemPrompt,
                history = history,
                volatileContext = volatileContext,
                // 本轮输入显式传进去。它是唯一的真实输入，与 history 快照是否
                // 已经包含它无关 —— 组装器会在末尾重新追加一次。
                currentTurn = currentTurn,
            )

            // ---- 5. 调模型 ----
            //
            // 两条路在这里分开，而且**互斥**：端侧模式绝不偷偷回退到云端。
            // 理由不是技术洁癖 —— 对一个自律监督应用来说，「我明明关了网它怎么还能
            // 回话」是最不该出现的疑问，而那正是自动回退会造成的现象。
            phase = "调用模型"
            val reply = if (runningLocal) {
                generateLocally(profile, turns, onPartial).getOrElse { throwable ->
                    return fail(
                        throwable.message?.takeIf { it.isNotBlank() } ?: "端侧模型生成失败",
                    )
                }
            } else {
                val completion = client.chat(effectiveConfig(config, profile), turns)
                    .getOrElse { throwable ->
                        return fail(
                            throwable.message?.takeIf { it.isNotBlank() } ?: "请求模型失败",
                        )
                    }

                // usage 里有缓存命中数就记下来，给「AI 配置」面板显示。
                // 端点没上报时 cacheStats 为 null —— 那是「看不到」，不是「没命中」，
                // 两者必须区分，否则会去优化一个本来就正常的东西。
                completion.cacheStats?.let(cache::record)
                completion.content
            }

            // ---- 6. 解析指令 ----
            phase = "解析回复"
            val parsed = AiCommandParser.parse(reply)

            val visible = parsed.visibleText.ifBlank {
                // 模型只输出了指令、没说话。给一句兜底文案，
                // 否则聊天记录里会出现一条空白的 AI 气泡。
                "（已执行你的请求）"
            }

            conversation.append(
                ChatMessage(
                    id = newId(MessageSender.AI),
                    sender = MessageSender.AI,
                    text = visible,
                    timestampMillis = clock(),
                ),
            )

            // ---- 7. 执行指令 ----
            phase = "执行指令"
            parsed.commands.forEach { command -> execute(command) }

            // ---- 8. 索引。不涉及网络，随手做掉；向量留给后台补。----
            phase = "更新记忆索引"
            memory.syncIndex()

            return SendOutcome.Sent
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            // 走到这里说明有个我们没预料到的异常。它绝不能冒到界面上变成崩溃。
            Log.e(TAG, "对话链路异常（阶段：$phase）", t)
            return fail("[$phase] ${describeUnexpected(t)}")
        }
    }

    /**
     * 主动盘问：由监督层触发，让 AI 先开口。
     *
     * ===========================================================================
     * 和 [send] 的区别只有一处：没有「他刚发来的消息」
     * ===========================================================================
     * 其余完全相同 —— 同样的稳定段、同样的易变状态块、同样的指令解析与执行。
     * 唯一多出来的是 [PromptAssembler.buildProactiveTrigger]，它把「为什么现在让你
     * 开口」写进上下文；以及一条**不落盘**的临时用户消息，用来承载状态块
     * （理由见 `PromptAssembler.buildTurns`：不能改动历史中段的字节）。
     *
     * **调用方已经确认过「他此刻正在注视屏幕」**，这里不再判断这件事 ——
     * 判断只应该有一个地方，两处判断迟早会不一致。
     */
    suspend fun askProactively(reason: String): SendOutcome {
        var phase = "主动盘问"

        try {
            phase = "读取端点配置"
            val config = aiConfig.activeConfigNow()
            if (!config.isUsable) return SendOutcome.NotConfigured

            // 用触发原因本身去召回记忆：「他刚连着开了三次小红书」这种查询，
            // 正好能把「他上次说要戒短视频」这类往事捞出来。
            phase = "召回记忆"
            // 与 send 一致：端侧模式下档案看的是选中的那个 .task 模型。
            val runningLocal = config.useLocalModel
            val profile = if (runningLocal) {
                PromptProfiles.resolveStyle(
                    localLlm.selected.value.promptModelName,
                    isLocal = true,
                )
            } else {
                PromptProfiles.resolve(config.model, isLocalEndpoint(config))
            }
            val recalled = memory.recall(
                queryText = reason,
                queryEmbedding = null,
                limit = profile.budget.recallLimit,
            )

            phase = "组装提示词"
            val now = clock()
            val history = conversation.messages.value

            val systemPrompt = PromptAssembler.buildSystemPrompt(
                profile = profile,
                prePrompt = config.prePrompt,
                personas = personas.personas.value,
                coreMemories = memory.coreMemories(),
                shortTermMemories = memory.recentShortTerm(),
            )

            val volatileContext = buildString {
                append(
                    PromptAssembler.buildVolatileContext(
                        profile = profile,
                        recalledMemories = recalled.memories.map { it.entry },
                        recalledConversation = recalled.conversationSnippets,
                        todos = policy.todos.value,
                        whitelist = policy.whitelist.value,
                        timeline = timeline.events.value,
                        vision = gaze.status.value,
                        history = history,
                        lastInteractionMillis = history
                            .lastOrNull { it.sender == MessageSender.USER }
                            ?.timestampMillis,
                        nowMillis = now,
                    ),
                )
                appendLine()
                appendLine()
                append(PromptAssembler.buildProactiveTrigger(reason))
            }

            val turns = PromptAssembler.buildTurns(
                profile = profile,
                systemPrompt = systemPrompt,
                history = history,
                volatileContext = volatileContext,
                // 主动盘问没有「他刚说的话」，走临时触发消息那条路。
                // 它同样保证末尾一定是这一轮的输入（临时触发说明），而不是历史里的旧消息。
                trailingUserText = PromptAssembler.PROACTIVE_TRIGGER_TEXT,
            )

            phase = "调用模型"
            // 主动盘问也走同一条分流。它没有流式回调 —— 盘问是一条主动弹出的
            // 短消息，打字机效果在这里只会让它显得犹豫。
            val replyText = if (runningLocal) {
                generateLocally(profile, turns, onPartial = null).getOrElse { throwable ->
                    return fail(
                        throwable.message?.takeIf { it.isNotBlank() } ?: "端侧模型生成失败",
                    )
                }
            } else {
                val completion = client.chat(effectiveConfig(config, profile), turns)
                    .getOrElse { throwable ->
                        return fail(
                            throwable.message?.takeIf { it.isNotBlank() } ?: "请求模型失败",
                        )
                    }
                completion.cacheStats?.let(cache::record)
                completion.content
            }

            phase = "解析回复"
            val parsed = AiCommandParser.parse(replyText)
            val visible = parsed.visibleText.ifBlank {
                return SendOutcome.Failed("模型没有输出任何内容")
            }

            conversation.append(
                ChatMessage(
                    id = newId(MessageSender.AI),
                    sender = MessageSender.AI,
                    text = visible,
                    timestampMillis = clock(),
                ),
            )

            phase = "执行指令"
            parsed.commands.forEach { command -> execute(command) }

            phase = "更新记忆索引"
            memory.syncIndex()

            return SendOutcome.Sent
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.e(TAG, "主动盘问异常（阶段：$phase）", t)
            return fail("[$phase] ${describeUnexpected(t)}")
        }
    }

    /**
     * 后台补向量。     *
     * 由 `AppContainer` 定期调用，也可以在一次发送之后顺手调一次。
     * 失败不报错 —— 没有向量模型时它会一直失败，那是正常状态，不是错误。
     */
    suspend fun embedPendingMemories(): Int = try {
        memory.embedPending(embedder)
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        Log.w(TAG, "补向量失败", t)
        0
    }

    /**
     * 把一次交互中「值得记的东西」补进索引。
     *
     * 用户手动写记忆、或 AI 写记忆之后调用。
     */
    suspend fun refreshIndex() {
        runCatching { memory.syncIndex() }
    }

    // -----------------------------------------------------------------------
    // 指令执行
    // -----------------------------------------------------------------------

    /**
     * 执行一条 AI 指令。
     *
     * 每一条都会产出一条**居中的系统胶囊**，格式是「[系统] AI 已…」，
     * 让用户一眼分清「这是系统真的做了的事」和「这是模型说的话」。
     * 这也正是需求里说的「以系统消息形式上屏，不要把特殊标签爆在消息里」。
     */
    private suspend fun execute(command: AiCommand) {
        when (command) {
            is AiCommand.GrantWhitelist -> {
                val label = policy.resolveAppLabel(command.packageName)
                // 关键应用（桌面 / 输入法 / 电话）在这里被仓库静默拒绝。
                // 先问一次，好在播报里说实话，而不是让用户以为放行了却没生效。
                if (policy.isDeviceCritical(command.packageName)) {
                    policy.publishNotice(
                        "AI 申请放行 $label，但它属于设备关键应用，本来就永远放行，无需豁免",
                    )
                    return
                }

                policy.grantTemporaryWhitelist(
                    packageName = command.packageName,
                    durationMinutes = command.minutes,
                    reason = command.reason,
                    appName = label,
                )
                policy.publishNotice(
                    "AI 已放行 $label · ${command.minutes} 分钟 · 理由：${command.reason}",
                )
            }

            is AiCommand.RevokeWhitelist -> {
                val label = policy.resolveAppLabel(command.packageName)
                policy.revokeTemporaryWhitelist(command.packageName)
                policy.publishNotice("AI 已撤销 $label 的临时豁免")
            }

            is AiCommand.AddTodo -> {
                val due = command.dueAtMillis ?: (clock() + AiCommandLimits.DEFAULT_TODO_DELAY_MILLIS)
                policy.addTodo(title = command.title, plannedAtMillis = due)
                policy.publishNotice("AI 记下待办：${command.title}")
            }

            is AiCommand.CompleteTodo -> {
                val target = resolveTodo(command.target)
                if (target == null) {
                    policy.publishNotice("AI 想完成待办「${command.target}」，但没有找到匹配项")
                } else {
                    policy.setTodoDone(target.id, true)
                    policy.publishNotice("AI 已把待办标记为完成：${target.title}")
                }
            }

            is AiCommand.WriteMemory -> {
                val entry = memory.remember(
                    content = command.content,
                    tier = command.tier,
                    source = MemorySource.AI,
                    pinned = command.pinned,
                )
                if (entry == null) {
                    policy.publishNotice("AI 想写入记忆，但保存失败")
                } else {
                    policy.publishNotice("AI 写入${command.tier.label}：${command.content}")
                }
            }

            is AiCommand.Unparsable -> {
                // 不静默丢弃：否则用户会看到「AI 说它已经放行了」但什么都没发生。
                policy.publishNotice("AI 输出的指令格式无法解析，已忽略：${command.raw.take(60)}")
            }
        }
    }

    /**
     * 按 id 或标题片段找待办。
     *
     * 模型多半记不住 id，只会给标题，所以两种都接受；标题用「包含」而不是「相等」，
     * 因为模型复述标题时经常会漏掉标点或多两个字。
     */
    private fun resolveTodo(target: String): TodoItem? {
        val todos = policy.todos.value
        todos.firstOrNull { it.id == target }?.let { return it }

        val needle = target.trim()
        if (needle.isEmpty()) return null

        return todos.firstOrNull { !it.isDone && it.title.contains(needle) }
            ?: todos.firstOrNull { !it.isDone && needle.contains(it.title) }
    }

    // -----------------------------------------------------------------------
    // 工具
    // -----------------------------------------------------------------------

    /**
     * 失败收尾：往会话里插一条系统胶囊，并返回一个带同样文案的结果。
     *
     * 两个地方都要有，是因为它们服务不同的场景：胶囊留在聊天记录里供回看，
     * 返回值供界面做即时反馈（比如把「正在输入」的指示停掉）。
     */
    private fun fail(message: String): SendOutcome.Failed {
        policy.publishNotice("对话失败：$message")
        return SendOutcome.Failed(message)
    }

    /**
     * 把没预料到的异常翻译成一条**能直接定位问题**的说明。
     *
     * 普通异常只要 message 就够了。但像 `ExceptionInInitializerError` 这种
     * 「类初始化失败」，它自己的 message 是 null —— 真正的信息全在 cause 和栈里。
     * 如果只显示类名，用户看到的是一句毫无线索的话，而我们拿不到任何可查的东西。
     * 所以这里额外带上 cause 与最上面两帧调用点。
     */
    private fun describeUnexpected(t: Throwable): String {
        val head = buildString {
            append(t.javaClass.simpleName)
            t.message?.takeIf { it.isNotBlank() }?.let { append("：").append(it) }
            t.cause?.let { cause ->
                append(" ← ").append(cause.javaClass.simpleName)
                cause.message?.takeIf { it.isNotBlank() }?.let { append("：").append(it.take(160)) }
            }
        }

        val frames = t.stackTrace
            .filter { it.className.startsWith("com.focussupervisor") }
            .take(2)
            .joinToString(" | ") { "${it.fileName}:${it.lineNumber} ${it.methodName}" }

        return if (frames.isBlank()) head else "$head\n$frames"
    }

    private fun newId(sender: MessageSender) = "${sender.name.lowercase()}-${UUID.randomUUID()}"

    private companion object {
        const val TAG = "ConversationEngine"
    }
}
