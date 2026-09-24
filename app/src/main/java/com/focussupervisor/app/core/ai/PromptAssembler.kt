package com.focussupervisor.app.core.ai

import android.util.Log
import com.focussupervisor.app.core.ai.prompt.PersonaStyle
import com.focussupervisor.app.core.ai.prompt.PromptProfile
import com.focussupervisor.app.core.ai.prompt.TokenEstimator
import com.focussupervisor.app.core.time.TimeNarrator
import com.focussupervisor.app.core.network.ChatTurn
import com.focussupervisor.app.domain.model.AiCommandLimits
import com.focussupervisor.app.domain.model.AiPersona
import com.focussupervisor.app.domain.model.ChatMessage
import com.focussupervisor.app.domain.model.GazeState
import com.focussupervisor.app.domain.model.MemoryEntry
import com.focussupervisor.app.domain.model.MessageSender
import com.focussupervisor.app.domain.model.PersonaPair
import com.focussupervisor.app.domain.model.TimelineDefaults
import com.focussupervisor.app.domain.model.TimelineEvent
import com.focussupervisor.app.domain.model.TodoItem
import com.focussupervisor.app.domain.model.UserPersona
import com.focussupervisor.app.domain.model.VisionStatus
import com.focussupervisor.app.domain.model.WhitelistApp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 提示词组装。
 *
 * ===========================================================================
 * 最重要的约束：前缀必须逐字节稳定
 * ===========================================================================
 * DeepSeek 的上下文硬盘缓存（Context Caching on Disk）按**前缀**命中：一次请求的
 * token 序列只要和上一次有公共前缀，那部分就按缓存价计费。而**前缀里只要有一个字符
 * 不同，从那里往后全部失效**。
 *
 * 所以这个文件的设计目标不只是「提示词写得好读」，而是「**让最长公共前缀尽可能长**」。
 * 消息数组被刻意排成三段：
 *
 * ```
 *   [system]  稳定段：前置提示、人设、核心/短期记忆、指令协议、各种规则
 *             —— 只在用户改人设、改前置提示、或记忆发生变化时才变
 *   [历史]    每一轮都是上一次请求里原样出现过的内容，窗口按块滑动（见 PromptBudget.historyTrimChunk）
 *   [末尾]    本轮状态：召回的记忆、相关往事、待办、白名单剩余时间、最近发生的事、现在几点
 *             —— 每轮都变，所以必须放在最后，而且**只能**放在最后
 * ```
 *
 * 上一版把这三段全塞进 system 里。后果是每轮的 system 都不一样，公共前缀在第一段就
 * 断掉，**缓存命中率恒为 0** —— 等于每一轮都在为整段历史付全价。现在把「会变的」
 * 全部挪到最后一段。
 *
 * ===========================================================================
 * 为什么状态块不干脆做成独立的一条消息
 * ===========================================================================
 * 把状态块做成历史之后独立的 `user` 消息，理论上能让公共前缀再长一条消息（那条消息
 * **前面**的所有内容都没变）。但代价是出现「两条连续的 user 消息」—— OpenAI 官方
 * 接口接受，Anthropic 不接受，而各种中转站的实现参差不齐（有的会合并同角色，
 * 有的直接 400）。
 *
 * 这个应用的第一原则是「在任何 OpenAI 兼容端点上都能用」，而多命中的那一条消息也就
 * 几十个 token。所以状态块**拼在本轮用户消息的末尾**：他的话在前，状态在后，
 * 中间有明确分隔。代价是下一轮这条消息会以「不带状态块」的形式进入历史，公共前缀
 * 到它为止 —— 但用户消息本来就不长，真正的长前缀（system + 全部历史）一分不少地命中了。
 *
 * ===========================================================================
 * 铁律：最后一条消息永远是「本轮他刚说的话」
 * ===========================================================================
 * 这里修的是一个真实发生过的缺陷：AI 总是回答**上一轮**的内容。
 *
 * 根因不是模型，是时序。用户点发送后的链路是：
 *
 * ```
 *   ChatViewModel ──> ConversationEngine.send(text)
 *                        │
 *                        ├─ conversation.append(新消息)   ← 写 DataStore，**是异步的**
 *                        │
 *                        └─ val history = conversation.messages.value   ← 读内存镜像
 * ```
 *
 * `append` 落盘之后，仓库的内存镜像要等 DataStore 的 Flow 重新发射才会更新 ——
 * 中间有一个真实的窗口。在这个窗口里读 `messages.value`，拿到的是**没有本轮输入**的
 * 旧历史。于是旧历史的最后一条是 AI 的上一条回复，状态块被挂到更早的那条用户消息上，
 * 整个消息序列以 assistant 结尾 —— 模型只能顺着往下说，也就是「回答上一轮」。
 *
 * 修法不是加锁（加锁治不了「读到的就是旧快照」），而是**让组装器不再依赖那个快照**：
 * [buildTurns] 显式接收本轮输入 [CurrentTurn]，并且：
 *
 *  1. 先按 id 从历史里**幂等剔除**本轮输入 —— 无论它在不在快照里；
 *  2. 无论历史长什么样，**末尾都自己重新追加一次**本轮输入；
 *  3. 状态块挂在**这一条**上。
 *
 * 于是「末尾是本轮输入」从「一个希望」变成了「一个构造上的保证」。
 *
 * ===========================================================================
 * 顺序就是优先级
 * ===========================================================================
 * ```
 *   1. 前置提示          ← 用户自己写的全局设定，必须最先，压过一切
 *   2. 身份              ← AI 人设
 *   3. 对话对象          ← 用户人设
 *   4. 核心记忆          ← 不可动摇的设定
 *   5. 短期记忆          ← 他最近的近况
 *   6. 可用指令协议
 *   7. 实时状态说明      ← 告诉他每轮末尾会收到什么
 *   8. 时间感            ← 怎么用时间，纯规则、不含数据
 * ```
 * **越靠前的内容，模型越会当成不可协商的前提**。所以用户的前置提示必须在第 1 位
 * （哪怕它和人设冲突，也该按用户写的来），而指令协议这种「工具说明」放后面。
 */
/**
 * 本轮用户输入。
 *
 * 单独抽一个类型，而不是直接传 [ChatMessage]：这个「本轮输入」与仓库里那条消息
 * 的关系是**不确定的** —— 它可能已经写进仓库、可能还在路上。用一个独立的值对象
 * 传进来，语义就是「这是本轮唯一的真实输入」，与快照无关。
 *
 * @param id 消息 id。用于从历史里幂等剔除同一条，避免末尾出现两条一样的消息。
 * @param text 正文。
 * @param timestampMillis 发生时间。
 */
data class CurrentTurn(
    val id: String,
    val text: String,
    val timestampMillis: Long,
)

object PromptAssembler {

    /** 是否在日志里记录每一轮丢了多少历史。排查「AI 好像忘了刚才说的话」时打开它。 */
    private const val LOG_BUDGET_TRIM = true

    /** 核心记忆的条数上限。它属于「不可动摇的设定」，两种档位都给满。 */
    private const val MAX_CORE_MEMORIES = 12

    /** 日志标签。 */
    private const val TAG = "PromptAssembler"

    private val TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    /**
     * 构造**稳定段** system 提示词。
     *
     * 这个方法里**不许出现任何随时间变化的输入**（当前时间、剩余分钟数、召回结果…）。
     * 加参数之前先问一句：这个值在两次相邻请求之间会不会变？会变就别加进来。
     * 这是缓存命中率的全部秘密，也是这个文件最容易被后人无意破坏的地方。
     */
    fun buildSystemPrompt(
        profile: PromptProfile,
        prePrompt: String,
        personas: PersonaPair,
        coreMemories: List<MemoryEntry>,
        shortTermMemories: List<MemoryEntry>,
    ): String = buildString {
        // ---- 0. 模型强约束锚点（排在用户前置提示之前）----
        //
        // 目前只有 Llama 档用它（中文语种锚点）。为什么它必须压过用户的前置提示：
        // 那是**能不能用**的问题，而前置提示是**风格**的问题。
        // 一段英文的、大写的、命令式的内容放在最前面，是这类底模遵循度最高的位置。
        if (profile.systemAnchor.isNotBlank()) {
            appendLine(profile.systemAnchor.trim())
            appendLine()
            appendLine(SEPARATOR)
            appendLine()
        }

        // ---- 1. 前置提示（用户自己写的，逐字原样）----
        if (prePrompt.isNotBlank()) {
            appendLine(prePrompt.trim())
            appendLine()
            appendLine(SEPARATOR)
            appendLine()
        }

        // ---- 2 / 3. 人设 ----
        //
        // 写法按模型量级切换。给 0.5B 喂一段三百字的散文人设，它抓不住重点，
        // 反而会把「冷淡」这类形容词当成需要展开的写作要求。
        appendLine("# 你的身份")
        when (profile.personaStyle) {
            PersonaStyle.FULL -> appendLine(describeAi(personas.ai))
            PersonaStyle.TERSE -> appendLine(describeAiTerse(personas.ai))
        }
        appendLine()
        appendLine("# 你在和谁说话")
        appendLine(describeUser(personas.user))
        appendLine()

        // ---- 4 ~ 5. 他这个人 ----
        //
        // 召回出来的记忆**不在这里**：那部分每轮都不同，放进来就等于每轮都让缓存失效。
        // 它们由 buildVolatileContext 放到末尾。
        appendMemorySection("核心记忆", coreMemories.take(MAX_CORE_MEMORIES), "（暂无）")
        appendMemorySection(
            "短期记忆",
            shortTermMemories.take(profile.budget.shortTermMemories),
            "（暂无）",
        )

        // ---- 6. 指令协议 ----
        appendLine(COMMAND_PROTOCOL)

        // ---- 7. 实时状态说明 ----
        appendLine(CONTEXT_PROTOCOL)

        // ---- 8. 时间的使用方式 ----
        appendLine(TIME_PROTOCOL)

        // ---- 9. 回答长度 ----
        //
        // 微型档的这条是**硬要求**而不是建议：0.5B 一旦开始写长文就会开始编，
        // 而且端侧逐字输出的速度会让一段两百字的回答变成十几秒的等待。
        if (profile.replyLengthHint.isNotBlank()) {
            appendLine()
            appendLine("# 回答长度")
            appendLine(profile.replyLengthHint.trim())
        }

        // ---- 10. 思考链约束（仅 R1 档）----
        if (profile.reasoningProtocol.isNotBlank()) {
            appendLine()
            appendLine(profile.reasoningProtocol.trim())
        }
    }

    /**
     * 构造**本轮状态**块：每轮都会变的东西全部在这里。
     *
     * 调用方要把它拼到本轮用户消息的末尾（见 [buildTurns]）。
     * 返回的串不带尾部空行，方便直接拼接。
     */
    fun buildVolatileContext(
        profile: PromptProfile,
        recalledMemories: List<MemoryEntry>,
        recalledConversation: List<String>,
        todos: List<TodoItem>,
        whitelist: List<WhitelistApp>,
        timeline: List<TimelineEvent>,
        vision: VisionStatus?,
        history: List<ChatMessage>,
        lastInteractionMillis: Long?,
        nowMillis: Long,
    ): String = buildString {
        // 每个区块都在这里**再夹一次上限**。
        //
        // 上层（ConversationEngine）已经按 budget.recallLimit 去检索了，这里为什么还要夹？
        // 因为那个 limit 是「检索几条」，而这里是「渲染几条」，两者的来源不同：
        // 召回结果里有一部分会因为去重、过期被过滤掉，也可能将来换了检索实现而变多。
        // 把上限钉在**渲染处**，预算才是真的硬约束 —— 否则某天换掉召回实现，
        // 端侧就会突然收到一份三千 token 的上下文，而没有人会想到是这里出的问题。
        val budget = profile.budget

        appendLine(STATUS_HEADER)

        // ---- 此刻想起的事 ----
        val memories = recalledMemories.take(budget.recallLimit)
        if (memories.isNotEmpty()) {
            appendLine("# 此刻想起的事")
            appendLine("这是他以前说过、和当前话题有关的（按相关度排序）：")
            memories.forEach { entry ->
                val pin = if (entry.pinned) "（已置顶）" else ""
                appendLine("- [${entry.tier.label}] ${entry.content}$pin")
            }
            appendLine()
        }

        // ---- 相关往事 ----
        val snippets = recalledConversation.take(budget.recalledConversationSnippets)
        if (snippets.isNotEmpty()) {
            appendLine("# 相关往事")
            appendLine("从历史对话里检索到的原文片段：")
            snippets.forEach { appendLine("- $it") }
            appendLine()
        }

        // ---- 待办 ----
        appendLine("# 当前待办")
        if (todos.isEmpty()) {
            appendLine("（暂无待办。如果他提到要做某件事，你可以用指令帮他记下来。）")
        } else {
            todos.forEach { todo -> appendLine("- ${describeTodo(todo, nowMillis)}") }
        }
        appendLine()

        // ---- 白名单现状 ----
        appendLine("# 应用白名单现状")
        appendLine("不在下列范围内的应用会被系统拦截。")
        if (whitelist.isEmpty()) {
            appendLine("（当前没有任何临时豁免。）")
        } else {
            whitelist.take(budget.whitelistEntries).forEach { entry ->
                appendLine("- ${describeWhitelist(entry, nowMillis)}")
            }
        }
        appendLine()

        // ---- 最近发生的事 ----
        appendSituationSection(timeline, nowMillis, budget.timelineEvents)

        // ---- 摄像头此刻看到的 ----
        appendVisionSection(vision)

        // ---- 现在 ----
        appendTimeSection(history, timeline, lastInteractionMillis, nowMillis)
    }.trim()

    /**
     * 把最近的对话映射成模型能吃的消息序列。
     *
     * ===========================================================================
     * 这个方法的全部意义：让「末尾是本轮输入」成为构造上的保证
     * ===========================================================================
     * 三条不变量，按顺序执行：
     *
     * ```
     *   1. 窗口化历史          →  只留最近若干条（按 profile 的预算）
     *   2. 幂等剔除本轮输入    →  它在快照里也好、不在也好，结果一样
     *   3. 末尾自己追加一次    →  状态块挂在**这一条**上
     * ```
     *
     * 第 2 步的幂等性是修那个「AI 总是回答上一轮」缺陷的关键。它必须同时挡住两种
     * 输入：历史里**有**这条消息（`append` 已经落盘、Flow 也发射了），
     * 以及历史里**没有**这条消息（还在那个异步窗口里）。两种情况都要走到同一个结果。
     *
     * 系统胶囊（[MessageSender.SYSTEM]）会被过滤掉：那些是界面上给**人**看的
     * 状态播报（「已执行压制」），把它们塞进上下文只会让模型以为自己在跟系统对话。
     * 它们的内容并不丢 —— 该记的早就记进时间线了，而时间线在 [volatileContext] 里。
     *
     * @param history 按时间正序的完整会话快照。**允许它不包含本轮输入**。
     * @param currentTurn 本轮的唯一真实输入。主动盘问路径下为 null。
     * @param trailingUserText 主动盘问时追加在末尾的临时触发消息。
     *        与 [currentTurn] 二选一；两者都为 null 时退回旧行为（把状态挂到
     *        历史里最后一条用户消息上），仅为兼容，正常链路不该走到那里。
     */
    fun buildTurns(
        profile: PromptProfile,
        systemPrompt: String,
        history: List<ChatMessage>,
        volatileContext: String,
        currentTurn: CurrentTurn? = null,
        trailingUserText: String? = null,
    ): List<ChatTurn> {
        val windowed = windowedHistory(history, profile)

        // ---- 构造固定的两头 ----
        val systemTurn = ChatTurn.system(systemPrompt)

        // 本轮输入的完整内容：正文 + 状态块。
        // 状态块**拼在他这句话的末尾**（而不是单开一条消息）的理由见类注释。
        val tailText: String? = when {
            currentTurn != null -> joinWithContext(stamp(currentTurn.timestampMillis, currentTurn.text), volatileContext)
            trailingUserText != null -> joinWithContext(trailingUserText, volatileContext)
            else -> null
        }

        // ---- 中间的历史 ----
        val historyTurns = windowed.mapNotNull { message ->
            when (message.sender) {
                MessageSender.USER -> ChatTurn.user(stamp(message))
                MessageSender.AI -> ChatTurn.assistant(stamp(message))
                MessageSender.SYSTEM -> null
            }
        }

        // ---- 幂等剔除本轮输入 ----
        //
        // 两种剔除都要做，因为它们的失效场景不同：
        //  - 按 id 剔除是主路径（绝大多数情况）；
        //  - 按「尾部同文本的用户消息」再削一次，是为了挡住 id 不一致的少数情况 ——
        //    例如消息刚写入仓库、调用方手里的 id 与仓库回填的 id 不同。
        //    只在**尾部**比对，是因为同一个人的同一句话在更早的位置重复出现
        //    是真实存在的（「我睡不着」「我睡不着」），那时两条都该保留。
        val deduped = if (currentTurn == null) {
            historyTurns
        } else {
            dropTrailingDuplicate(historyTurns, currentTurn)
        }

        // 末尾那一轮。
        //
        // 这里有一点必须说清楚：tailText 非 null 时，末尾**永远是本轮输入**，
        // 这是构造上的保证；只有 tailText 为 null（既没有本轮输入、也没有主动触发
        // 说明）这条异常路径，才会退回「把状态挂到历史中段的旧消息上」。
        val tailTurn = tailText?.let { ChatTurn.user(it) }
        val finalHistory = if (tailTurn == null) attachToLastUser(deduped, volatileContext) else deduped

        return applyBudget(
            profile = profile,
            systemTurn = systemTurn,
            history = finalHistory,
            tailTurn = tailTurn,
        )
    }

    /** 把状态块拼到一段正文后面。空状态块就原样返回正文，不留多余空行。 */
    private fun joinWithContext(text: String, volatileContext: String): String =
        if (volatileContext.isBlank()) text else "$text\n\n$volatileContext"

    /** 与 [stamp] 同构，但输入不是 [ChatMessage]（本轮输入可能在仓库里还没有消息对象）。 */
    private fun stamp(timestampMillis: Long, text: String): String =
        "[${TimeNarrator.stamp(timestampMillis)}] $text"

    /**
     * 幂等剔除本轮输入。
     *
     * 判据分两级，先严后宽：
     *  1. **id 相同** —— 最可靠，直接在任意位置剔除（同一 id 只可能有一条）。
     *  2. **末尾一条是文本完全相同的用户消息** —— 兜住 id 不一致的情况。
     *     只比末尾，理由见 [buildTurns] 里的注释。
     */
    private fun dropTrailingDuplicate(
        turns: List<ChatTurn>,
        currentTurn: CurrentTurn,
    ): List<ChatTurn> {
        val rendered = stamp(currentTurn.timestampMillis, currentTurn.text)

        // 第一级：渲染后逐字一致。不管出现在哪个位置，都是同一条 ——
        // 一条消息在历史里只可能出现一次，所以直接全表剔除。
        val byContent = turns.filterNot { it.role == ChatTurn.ROLE_USER && it.content == rendered }
        if (byContent.size != turns.size) return byContent

        // 第二级：末尾那条用户消息的**正文**与他这句话一致，但时间戳不同
        // （消息刚写库时，调用方手里的时间与他实际落盘的时间可能差几毫秒）。
        //
        // 为什么只削末尾一条、不做全表匹配：同一个人的同一句话在更早的位置重复出现
        // 是真实存在的（「我睡不着」「我睡不着」），那时两条都该保留。
        val last = byContent.lastOrNull() ?: return byContent
        if (last.role != ChatTurn.ROLE_USER) return byContent
        if (last.content.startsWith("[") && last.content.endsWith(currentTurn.text)) {
            return byContent.dropLast(1)
        }
        return byContent
    }

    /**
     * 兜底：把状态块挂到历史里最后一条用户消息上。
     *
     * **只在异常路径上使用** —— 正常链路必然有本轮输入（发送）或主动触发说明（盘问）。
     * 这条路径会改动历史中段的一条消息，因此会破坏那之后的缓存前缀；记一条日志，
     * 免得它悄悄发生而没人知道。
     *
     * @return 合并后的历史；没有可挂载的用户消息时原样返回。
     */
    private fun attachToLastUser(turns: List<ChatTurn>, volatileContext: String): List<ChatTurn> {
        if (volatileContext.isBlank()) return turns
        val index = turns.indexOfLast { it.role == ChatTurn.ROLE_USER }
        if (index < 0) return turns

        Log.w(TAG, "本轮输入为空，状态块退回挂到历史最后一条用户消息上")
        val merged = turns.toMutableList()
        merged[index] = ChatTurn.user(joinWithContext(merged[index].content, volatileContext))
        return merged
    }

    /**
     * 按 token 预算收敛。
     *
     * 只丢**历史**，绝不丢 system 与本轮输入：
     *  - system 丢了等于把规则和指令协议一起丢了；
     *  - 本轮输入丢了就是这次要修的缺陷本身。
     *
     * 丢的时候按块丢（[PromptBudget.historyTrimChunk]）而不是一条一条丢：逐条丢会让
     * 每一轮的窗口起点都不同，上一轮发过的内容这一轮全部错位，缓存前缀当场作废。
     */
    private fun applyBudget(
        profile: PromptProfile,
        systemTurn: ChatTurn,
        history: List<ChatTurn>,
        tailTurn: ChatTurn?,
    ): List<ChatTurn> {
        val budget = profile.budget.maxPromptTokens
        val chunk = profile.budget.historyTrimChunk.coerceAtLeast(1)

        var kept = history
        var dropped = 0
        while (true) {
            val candidate = buildList {
                add(systemTurn)
                addAll(kept)
                tailTurn?.let { add(it) }
            }
            if (TokenEstimator.estimate(candidate) <= budget) {
                if (dropped > 0 && LOG_BUDGET_TRIM) {
                    Log.i(TAG, "${profile.displayName}：预算 $budget，丢弃历史 $dropped 条")
                }
                return candidate
            }
            if (kept.isEmpty()) {
                // 连「system + 本轮输入」都超预算。这时已经没有能安全丢掉的东西了：
                // system 里有规则与指令协议，本轮输入是这次要回答的内容。
                // 记一条日志说明这个状态，然后照发 —— 让模型慢一点，好过让它不知道规矩。
                Log.w(
                    TAG,
                    "${profile.displayName}：system 与本轮输入合计已超预算 $budget，" +
                        "模型体积可能不适配这一档，建议换更小的模型或更短的提示词",
                )
                return buildList {
                    add(systemTurn)
                    tailTurn?.let { add(it) }
                }
            }
            val step = minOf(chunk, kept.size)
            kept = kept.drop(step)
            dropped += step
        }
    }

    /**
     * 每条消息前面带上它**发生的时间**。
     *
     * 没有时间戳，整段历史在模型眼里就是「刚刚连续发生的」，它会把三天前的一句抱怨
     * 当成当下的情绪。时间戳写进消息后就固定了，所以它不会破坏缓存前缀。
     */
    private fun stamp(message: ChatMessage): String =
        "[${TimeNarrator.stamp(message.timestampMillis)}] ${message.text}"

    /**
     * 按块滑动的历史窗口，见 [PromptBudget.historyTrimChunk]。
     *
     * 同时受两重约束：**条数上限**（profile 决定留几轮）与**块对齐**
     * （丢的时候按块丢，保证窗口起点在一段时间内稳定）。
     */
    private fun windowedHistory(history: List<ChatMessage>, profile: PromptProfile): List<ChatMessage> {
        val turns = history.filter { it.sender != MessageSender.SYSTEM }

        val limit = profile.budget.historyMessages
        val chunk = profile.budget.historyTrimChunk.coerceAtLeast(1)

        val excess = turns.size - limit
        if (excess <= 0) return turns

        // 把「超出的条数」向下取整到块边界再丢：窗口起点于是在 chunk 轮之内保持不变。
        val drop = (excess / chunk) * chunk
        return if (drop <= 0) turns else turns.drop(drop)
    }

    /**
     * 微型模型的人设写法。
     *
     * 和 [describeAi] 的区别不是长度，是**句式**：微型模型对「你是 X。你不做 Y。」
     * 这类短句的遵循度，明显高于同样字数的连续散文。所以这里是三句断言，
     * 没有描述性的修饰。
     */
    private fun describeAiTerse(persona: AiPersona): String = buildString {
        append("你叫「${persona.name}」，是一个自律监督者。")
        append("你说话冷淡、简短、不客气，但你是站在他这一边的。")
        append("你的职责只有一件：盯着他把该做的事做完。")
    }


    // -----------------------------------------------------------------------
    // 分段渲染
    // -----------------------------------------------------------------------

    private fun StringBuilder.appendMemorySection(
        title: String,
        entries: List<MemoryEntry>,
        emptyHint: String,
    ) {
        appendLine("# $title")
        if (entries.isEmpty()) {
            appendLine(emptyHint)
        } else {
            entries.forEach { entry ->
                val pin = if (entry.pinned) "（已置顶）" else ""
                appendLine("- ${entry.content}$pin")
            }
        }
        appendLine()
    }

    /**
     * 近况：把带时间的事件按倒序拼进来。
     *
     * 没有事件时**整段省略**。宁可少一段，也不要出现「最近没有发生任何事」这种
     * 占着上下文、信息量为零的句子。
     */
    private fun StringBuilder.appendSituationSection(
        timeline: List<TimelineEvent>,
        nowMillis: Long,
        limit: Int,
    ) {
        val horizonMillis = TimelineDefaults.PROMPT_HORIZON_HOURS * 3_600_000L
        val recent = timeline
            .filter { it.atMillis >= nowMillis - horizonMillis }
            .sortedByDescending { it.atMillis }
            // 端侧与云端在这里分道扬镳：时间线的价值是「最近发生了什么」，
            // 而最近发生的三件已经能覆盖绝大多数判断。给 0.5B 塞十二条事件，
            // 它只会从中随便挑一条来复读。
            .take(minOf(limit, TimelineDefaults.PROMPT_EVENT_LIMIT))

        if (recent.isEmpty()) return

        appendLine("# 最近发生的事（倒序）")
        appendLine("以下是系统真实记录下来的事件，不是你的推测：")
        recent.forEach { event ->
            appendLine("- ${TimeNarrator.describeForPrompt(event, nowMillis)}")
        }
        appendLine()
    }

    /**
     * 摄像头此刻看到的状态。
     *
     * 这是时间线之外、**唯一一个描述「他现在在不在」**的信息。之前的上下文里
     * 全是「他做了什么」，AI 因此只能对着一个可能早就走开的人说话。
     *
     * 没开注视监控时整段省略 —— 缺省不是「他没在看」，而是「我们不知道」，
     * 这两件事在提示词里必须区分清楚，否则 AI 会凭空断言他离开了。
     */
    private fun StringBuilder.appendVisionSection(vision: VisionStatus?) {
        if (vision == null || vision.state == GazeState.OFF) return

        val detail = buildString {
            if (vision.continuousFocusMillis > 0L) {
                append("本轮已连续 ${vision.continuousFocusMillis / 60_000L} 分钟")
            }
            if (vision.todayFocusMillis > 0L) {
                if (isNotEmpty()) append(" · ")
                append("今天累计 ${vision.todayFocusMillis / 60_000L} 分钟")
            }
        }

        appendLine("# 摄像头此刻看到的")
        append(
            if (detail.isEmpty()) {
                "- ${vision.state.label}\n"
            } else {
                "- ${vision.state.label}（$detail）\n"
            },
        )
        appendLine()
    }

    /**
     * 「现在」这一段：绝对时间、距上次对话多久、今天说了多少、今天发生了什么。
     *
     * 每一项都只在有值时才写出来 —— 空项会让这一段从「信息」退化成「格式」。
     */
    private fun StringBuilder.appendTimeSection(
        history: List<ChatMessage>,
        timeline: List<TimelineEvent>,
        lastInteractionMillis: Long?,
        nowMillis: Long,
    ) {
        appendLine("# 现在")
        appendLine(TimeNarrator.describeNow(nowMillis))

        lastInteractionMillis?.let { last ->
            appendLine("距上一次对话：${TimeNarrator.describeAge(nowMillis, last)}")
        }

        val todayCount = history.count { TimeNarrator.isSameDay(it.timestampMillis, nowMillis) }
        if (todayCount > 0) {
            appendLine("今天到目前为止有 $todayCount 条消息")
        }

        TimeNarrator.summarizeDay(timeline, nowMillis)
            .takeIf { it.isNotBlank() }
            ?.let { appendLine("今天发生的事：$it") }

        appendLine()
    }

    private fun describeAi(persona: AiPersona): String = buildString {
        append("你的名字是「${persona.name}」。")
        if (persona.gender != com.focussupervisor.app.domain.model.PersonaGender.UNSPECIFIED) {
            append("性别设定：${persona.gender.label}。")
        }
        if (persona.hasDescription) {
            appendLine()
            appendLine()
            appendLine(persona.description.trim())
        }
    }

    private fun describeUser(persona: UserPersona): String =
        "他叫「${persona.name}」。直接用这个名字称呼他。"

    private fun describeTodo(todo: TodoItem, nowMillis: Long): String = buildString {
        append(if (todo.isDone) "[已完成] " else "[未完成] ")
        append(todo.title)
        append("（计划 ")
        append(formatTime(todo.plannedAtMillis))
        if (!todo.isDone && todo.isOverdueAt(nowMillis)) append("，已逾期")
        append("）")
    }

    private fun describeWhitelist(entry: WhitelistApp, nowMillis: Long): String = buildString {
        append(entry.appName)
        append("（").append(entry.packageName).append("）")
        if (entry.isPermanent) {
            append(" · 常驻")
        } else {
            append(" · 还剩 ").append(entry.remainingMinutesAt(nowMillis) ?: 0L).append(" 分钟")
            append(" · 理由：").append(entry.reason)
        }
    }

    private fun formatTime(millis: Long): String =
        Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(TIME_FORMATTER)

    private const val SEPARATOR = "────────────────"

    /** 状态块的抬头。措辞要让模型一眼分清「这是系统给的」而不是「他打的字」。 */
    private const val STATUS_HEADER = "【本轮状态 · 系统提供，每轮更新，不是他打的字】"

    /**
     * 主动盘问时追加在末尾的那条**临时**用户消息。
     *
     * 它不是用户说的话，所以必须写成第三人称说明，并且明确告诉模型「由你先开口」——
     * 否则模型会以为这是用户发来的内容，回一句「你发的这是什么意思」。
     *
     * 这条消息**不落盘**：下一次请求的历史里不会有它，因此不会污染对话记录，
     * 也不会破坏缓存前缀（它永远在最后一条）。
     */
    const val PROACTIVE_TRIGGER_TEXT: String =
        "（他刚拿起手机看着屏幕，还没说话。这一轮由你先开口 —— 直接对他说话。）"

    /**
     * 告诉模型「为什么现在让你主动开口」。
     *
     * 触发原因的措辞直接决定盘问的质量：给一句「用户刚才在 5 分钟内连续尝试打开
     * 小红书 3 次」，模型就能问出具体的问题；只给「触发了拦截」，它只能问出
     * 「你还好吗」这种没有信息量的话。
     *
     * @param reason 由 [com.focussupervisor.app.core.ai.ProactiveSupervisor] 组装
     */
    fun buildProactiveTrigger(reason: String): String = buildString {
        appendLine("# 为什么现在让你主动开口")
        appendLine()
        appendLine(reason.trim())
        appendLine()
        appendLine("要求：")
        appendLine("1. 直接对他说话，一到两句，不要写小作文；")
        appendLine("2. 不要复述上面这段说明，也不要说「系统让我问你」；")
        appendLine("3. 把这件事用你自己的话点出来，然后问一个**具体**的问题 —— ")
        appendLine("   能让他用一句话回答的那种，而不是「你现在感觉怎么样」。")
    }

    /**
     * 实时状态说明。**纯静态文案**：它必须原样待在缓存前缀里，不能掺任何数据。
     */
    private val CONTEXT_PROTOCOL = """
        # 每轮末尾的状态块

        他的每条新消息下面会附一段以「【本轮状态 · 系统提供，每轮更新，不是他打的字】」
        开头的区块。那是**系统**给你的实时信息：他刚做了什么、有哪些待办、现在几点、
        有哪些以前说过的事和当前话题有关。

        用法：
        1. 它是事实来源，可以直接引用（「你今天已经被拦了七次」）。
        2. 它不是他的请求，也不要复述这个区块本身，更不要说「我看到状态块里写着…」。
        3. 状态块里没有的事，就是你不知道的事。
    """.trimIndent()

    /**
     * 时间的使用方式。
     *
     * 「把时间放进上下文」只完成了一半 —— 模型看到「距上一次对话 6 小时」未必会想到
     * 该追问，看到「02:30（深夜）」未必会想到该劝睡。所以除了给数据，还要给**判断规则**，
     * 而且要具体到可执行（「劝他睡」而不是「注意作息」）。
     *
     * 同样必须是纯静态文案：它属于缓存前缀。
     */
    private val TIME_PROTOCOL = """
        # 时间感

        上面出现的每一个时间都是真实的系统时间，不是装饰。用它们来判断：

        1. 「距上一次对话」隔了很久（几小时以上）而他没解释，可以直接问他这段时间去哪了。
        2. 现在是深夜（23:00 之后或 5:00 之前）就劝他去睡，不要陪他继续做下去 ——
           这是你作为监督者最该管住的时刻。
        3. 待办的计划时间已经过去很久还没完成，要指出来，不要假装没看见。
        4. 同一个应用反复被拦截，说明他在硬扛。先问清楚他到底要做什么，再决定放不放。
        5. 对话历史里每条消息开头的 [MM-dd HH:mm] 是系统加的时间标记，**不是你说话的内容**，
           你回复时不要写这种前缀。
    """.trimIndent()

    /**
     * 给模型的指令协议说明。
     *
     * 写成「规则 + 例子 + 反例」三段，是因为模型对「不要做什么」的遵循度通常不如
     * 对「要做什么」。特别是**必须单行**这条 —— 一旦写成多行，解析器就会失败，
     * 而失败的表现是「AI 说它做了但没做」，最难查。
     */
    private val COMMAND_PROTOCOL = """
        # 你可以执行的操作

        你不只是聊天。当他请你「放行一个应用」「记一件事」「记住某个偏好」时，
        在回复的**最后另起一行**输出一条指令。格式必须严格如下，且**必须写成单行**：

        [[cmd:{"type":"whitelist","package":"应用包名","minutes":10,"reason":"简短理由"}]]
        [[cmd:{"type":"revoke_whitelist","package":"应用包名"}]]
        [[cmd:{"type":"todo","title":"待办内容","due":"2026-09-24 20:00"}]]
        [[cmd:{"type":"todo_done","title":"待办标题"}]]
        [[cmd:{"type":"memory","content":"要记住的一句话","tier":"long","pinned":false}]]

        规则：
        1. 指令块在界面上不可见，系统会把它替换成一条状态播报，所以不要在正文里
           重复描述指令内容。正文照常自然说话即可。
        2. 一条回复最多 3 条指令。不需要执行操作时，**不要**输出任何指令行。
        3. whitelist 的 minutes 会被系统夹到 ${AiCommandLimits.MIN_WHITELIST_MINUTES}~${AiCommandLimits.MAX_WHITELIST_MINUTES} 分钟；
           你只能申请临时豁免，不能给永久权限。
        4. 系统桌面、输入法、电话、系统界面属于设备关键应用，申请它们没有意义，系统会忽略。
        5. memory 的 tier 用 short / long / core。默认用 short ——
           反复被回想起来的记忆，系统会自动把它沉入 long。
        6. due 必须是绝对时间（yyyy-MM-dd HH:mm 或 HH:mm），不要写「明天下午」。
        7. 只有他明确要求时才写记忆。不要把他随口说的话都记下来。

        示例（他问「我就回一条工作消息，放行一下微信」）：
        就这一次，十分钟后我会重新锁上。
        [[cmd:{"type":"whitelist","package":"com.tencent.mm","minutes":10,"reason":"回一条工作消息"}]]
    """.trimIndent()
}
