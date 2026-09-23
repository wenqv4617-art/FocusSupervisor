package com.focussupervisor.app.core.ai

import android.util.Log
import com.focussupervisor.app.core.network.OpenAiCompatibleClient
import com.focussupervisor.app.data.repository.AiConfigRepository
import com.focussupervisor.app.data.repository.AppPolicyRepository
import com.focussupervisor.app.data.repository.ConversationRepository
import com.focussupervisor.app.data.repository.MemoryRepository
import com.focussupervisor.app.data.repository.PersonaRepository
import com.focussupervisor.app.data.repository.TextEmbedder
import com.focussupervisor.app.domain.model.AiCommand
import com.focussupervisor.app.domain.model.AiCommandLimits
import com.focussupervisor.app.domain.model.ChatMessage
import com.focussupervisor.app.domain.model.MemorySource
import com.focussupervisor.app.domain.model.MessageSender
import com.focussupervisor.app.domain.model.TodoItem
import kotlinx.coroutines.CancellationException
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
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /**
     * 把文本转向量的适配器。
     *
     * 记忆仓库不知道网络的存在，这里把两者接起来。没配向量模型时它返回失败，
     * 记忆仓库会安静地退回关键词召回 —— 不需要在这里做任何判断。
     */
    private val embedder = TextEmbedder { texts ->
        client.embed(aiConfig.activeConfigNow(), texts)
    }

    /**
     * 发送一条用户消息并处理完整的回复链路。
     *
     * **不会抛异常**（除协程取消外）。任何失败都会变成本方法返回的 [SendOutcome.Failed]，
     * 同时往会话里插一条系统胶囊。
     */
    suspend fun send(userText: String): SendOutcome {
        val text = userText.trim()
        if (text.isEmpty()) return SendOutcome.Sent

        try {
            // ---- 1. 用户消息先落库。哪怕后面网络失败，这句话也已经记下来了。----
            conversation.append(
                ChatMessage(
                    id = newId(MessageSender.USER),
                    sender = MessageSender.USER,
                    text = text,
                    timestampMillis = clock(),
                ),
            )

            // ---- 2. 端点配置 ----
            val config = aiConfig.activeConfigNow()
            if (!config.isUsable) {
                return fail("还没有配置 AI 端点。打开「+」→「AI 配置」填好地址与模型再试。")
            }

            // ---- 3. 召回记忆 ----
            val queryEmbedding = if (config.embeddingModel.isNotBlank()) {
                client.embed(config, listOf(text)).getOrNull()?.firstOrNull()
            } else {
                null
            }
            val recalled = memory.recall(queryText = text, queryEmbedding = queryEmbedding)

            // ---- 4. 组装提示词 ----
            val now = clock()
            val systemPrompt = PromptAssembler.buildSystemPrompt(
                prePrompt = config.prePrompt,
                personas = personas.personas.value,
                coreMemories = memory.coreMemories(),
                recalledMemories = recalled.memories.map { it.entry },
                shortTermMemories = memory.recentShortTerm(),
                recalledConversation = recalled.conversationSnippets,
                todos = policy.todos.value,
                whitelist = policy.whitelist.value,
                nowMillis = now,
            )

            val turns = PromptAssembler.buildTurns(
                systemPrompt = systemPrompt,
                history = conversation.messages.value,
            )

            // ---- 5. 调模型 ----
            val reply = client.chat(config, turns).getOrElse { throwable ->
                return fail(
                    throwable.message?.takeIf { it.isNotBlank() } ?: "请求模型失败",
                )
            }

            // ---- 6. 解析指令 ----
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
            parsed.commands.forEach { command -> execute(command) }

            // ---- 8. 索引。不涉及网络，随手做掉；向量留给后台补。----
            memory.syncIndex()

            return SendOutcome.Sent
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            // 走到这里说明有个我们没预料到的异常。它绝不能冒到界面上变成崩溃。
            Log.e(TAG, "对话链路异常", t)
            return fail("处理消息时出错：${t.message ?: t.javaClass.simpleName}")
        }
    }

    /**
     * 后台补向量。
     *
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

    private fun newId(sender: MessageSender) = "${sender.name.lowercase()}-${UUID.randomUUID()}"

    private companion object {
        const val TAG = "ConversationEngine"
    }
}
