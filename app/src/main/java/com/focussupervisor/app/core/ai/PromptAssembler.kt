package com.focussupervisor.app.core.ai

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
 *   [历史]    每一轮都是上一次请求里原样出现过的内容，窗口按块滑动（见 HISTORY_TRIM_CHUNK）
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
object PromptAssembler {

    /**
     * 带进上下文的最近对话条数（下限）。
     *
     * 实际保留条数在 [RECENT_TURN_LIMIT] 到 [RECENT_TURN_LIMIT] + [HISTORY_TRIM_CHUNK] - 1
     * 之间浮动，理由见 [HISTORY_TRIM_CHUNK]。
     */
    const val RECENT_TURN_LIMIT = 24

    /**
     * 历史窗口的**滑动步长**。
     *
     * 这是为缓存命中率服务的一个刻意设计。如果严格「只留最近 24 条」，那么每来一条
     * 新消息，窗口的第一条就会变，公共前缀立刻断在历史的开头 —— 缓存只覆盖到那条
     * system，等于白设。
     *
     * 改成按 12 条为一块滑动之后，窗口的第一条**每 12 轮才变一次**：这期间新消息只是
     * 往末尾追加，前面逐字节不变，整段历史都在命中范围内。代价是最多多带 11 条消息
     * （一两百 token），换来 12 轮里稳定的长前缀 —— 这个交换在任何价格模型下都划算。
     */
    const val HISTORY_TRIM_CHUNK = 12

    private val TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    /**
     * 构造**稳定段** system 提示词。
     *
     * 这个方法里**不许出现任何随时间变化的输入**（当前时间、剩余分钟数、召回结果…）。
     * 加参数之前先问一句：这个值在两次相邻请求之间会不会变？会变就别加进来。
     * 这是缓存命中率的全部秘密，也是这个文件最容易被后人无意破坏的地方。
     */
    fun buildSystemPrompt(
        prePrompt: String,
        personas: PersonaPair,
        coreMemories: List<MemoryEntry>,
        shortTermMemories: List<MemoryEntry>,
    ): String = buildString {
        // ---- 1. 前置提示（位置最靠前，逐字原样，不做任何加工）----
        if (prePrompt.isNotBlank()) {
            appendLine(prePrompt.trim())
            appendLine()
            appendLine(SEPARATOR)
            appendLine()
        }

        // ---- 2 / 3. 人设 ----
        appendLine("# 你的身份")
        appendLine(describeAi(personas.ai))
        appendLine()
        appendLine("# 你在和谁说话")
        appendLine(describeUser(personas.user))
        appendLine()

        // ---- 4 ~ 5. 他这个人 ----
        //
        // 召回出来的记忆**不在这里**：那部分每轮都不同，放进来就等于每轮都让缓存失效。
        // 它们由 buildVolatileContext 放到末尾。
        appendMemorySection("核心记忆", coreMemories, "（暂无）")
        appendMemorySection("短期记忆", shortTermMemories, "（暂无）")

        // ---- 6. 指令协议 ----
        appendLine(COMMAND_PROTOCOL)

        // ---- 7. 实时状态说明 ----
        appendLine(CONTEXT_PROTOCOL)

        // ---- 8. 时间的使用方式 ----
        appendLine(TIME_PROTOCOL)
    }

    /**
     * 构造**本轮状态**块：每轮都会变的东西全部在这里。
     *
     * 调用方要把它拼到本轮用户消息的末尾（见 [buildTurns]）。
     * 返回的串不带尾部空行，方便直接拼接。
     */
    fun buildVolatileContext(
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
        appendLine(STATUS_HEADER)

        // ---- 此刻想起的事 ----
        if (recalledMemories.isNotEmpty()) {
            appendLine("# 此刻想起的事")
            appendLine("这是他以前说过、和当前话题有关的（按相关度排序）：")
            recalledMemories.forEach { entry ->
                val pin = if (entry.pinned) "（已置顶）" else ""
                appendLine("- [${entry.tier.label}] ${entry.content}$pin")
            }
            appendLine()
        }

        // ---- 相关往事 ----
        if (recalledConversation.isNotEmpty()) {
            appendLine("# 相关往事")
            appendLine("从历史对话里检索到的原文片段：")
            recalledConversation.forEach { appendLine("- $it") }
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
            whitelist.forEach { entry -> appendLine("- ${describeWhitelist(entry, nowMillis)}") }
        }
        appendLine()

        // ---- 最近发生的事 ----
        appendSituationSection(timeline, nowMillis)

        // ---- 摄像头此刻看到的 ----
        appendVisionSection(vision)

        // ---- 现在 ----
        appendTimeSection(history, timeline, lastInteractionMillis, nowMillis)
    }.trim()

    /**
     * 把最近的对话映射成模型能吃的消息序列，并把 [volatileContext] 拼到本轮用户消息末尾。
     *
     * 系统胶囊（[MessageSender.SYSTEM]）会被过滤掉：那些是界面上给**人**看的
     * 状态播报（「已执行压制」），把它们塞进上下文只会让模型以为自己在跟系统对话。
     * 它们的内容并不丢 —— 该记的早就记进时间线了，而时间线在 [volatileContext] 里。
     *
     * @param history 按时间正序的完整会话
     */
    fun buildTurns(
        systemPrompt: String,
        history: List<ChatMessage>,
        volatileContext: String,
    ): List<ChatTurn> {
        val turns = windowedHistory(history)

        // 状态挂在本轮用户消息上。找不到用户消息（理论上不会发生：只有用户发消息
        // 才会走到这里）就退化成不挂 —— 总比把状态拼到 AI 的话后面强。
        val lastUserIndex = turns.indexOfLast { it.sender == MessageSender.USER }

        val result = mutableListOf<ChatTurn>()
        result += ChatTurn.system(systemPrompt)

        turns.forEachIndexed { index, message ->
            val stamped = stamp(message)
            val content = if (index == lastUserIndex && volatileContext.isNotBlank()) {
                "$stamped\n\n$volatileContext"
            } else {
                stamped
            }

            when (message.sender) {
                MessageSender.USER -> result += ChatTurn.user(content)
                MessageSender.AI -> result += ChatTurn.assistant(content)
                MessageSender.SYSTEM -> Unit
            }
        }

        return result
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
     * 按块滑动的历史窗口，见 [HISTORY_TRIM_CHUNK]。
     *
     * 返回条数在 `[RECENT_TURN_LIMIT, RECENT_TURN_LIMIT + HISTORY_TRIM_CHUNK)` 之间。
     */
    private fun windowedHistory(history: List<ChatMessage>): List<ChatMessage> {
        val turns = history.filter { it.sender != MessageSender.SYSTEM }

        val excess = turns.size - RECENT_TURN_LIMIT
        if (excess <= 0) return turns

        // 把「超出的条数」向下取整到块边界再丢：窗口起点于是在 12 轮之内保持不变。
        val drop = (excess / HISTORY_TRIM_CHUNK) * HISTORY_TRIM_CHUNK
        return if (drop <= 0) turns else turns.drop(drop)
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
    ) {
        val horizonMillis = TimelineDefaults.PROMPT_HORIZON_HOURS * 3_600_000L
        val recent = timeline
            .filter { it.atMillis >= nowMillis - horizonMillis }
            .sortedByDescending { it.atMillis }
            .take(TimelineDefaults.PROMPT_EVENT_LIMIT)

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
