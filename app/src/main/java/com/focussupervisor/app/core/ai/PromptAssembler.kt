package com.focussupervisor.app.core.ai

import com.focussupervisor.app.domain.model.AiCommandLimits
import com.focussupervisor.app.domain.model.AiPersona
import com.focussupervisor.app.domain.model.ChatMessage
import com.focussupervisor.app.domain.model.MemoryEntry
import com.focussupervisor.app.domain.model.MemoryTier
import com.focussupervisor.app.domain.model.MessageSender
import com.focussupervisor.app.domain.model.PersonaPair
import com.focussupervisor.app.domain.model.TodoItem
import com.focussupervisor.app.domain.model.UserPersona
import com.focussupervisor.app.domain.model.WhitelistApp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 把「人设 + 前置提示 + 记忆 + 待办 + 白名单现状 + 指令协议」拼成一段系统提示词。
 *
 * ===========================================================================
 * 顺序就是优先级
 * ===========================================================================
 * ```
 *   1. 前置提示          ← 用户自己写的全局设定，必须最先，压过一切
 *   2. 身份              ← AI 人设
 *   3. 对话对象          ← 用户人设
 *   4. 核心记忆          ← 不可动摇的设定
 *   5. 长久记忆          ← 沉淀下来的事实
 *   6. 短期记忆          ← 最近发生的事
 *   7. 相关往事          ← 向量召回出来的对话原文
 *   8. 当前待办
 *   9. 白名单现状
 *  10. 可用指令协议
 *  11. 当前时间
 * ```
 * 这不是随意排的：**越靠前的内容，模型越会当成不可协商的前提**。所以用户的
 * 前置提示必须在第 1 位（哪怕它和人设冲突，也该按用户写的来），而指令协议这种
 * 「工具说明」放最后，因为它需要被读到，但不需要被当成身份认同。
 *
 * ===========================================================================
 * 为什么拼成一段大 system 而不是拆成多条
 * ===========================================================================
 * 不少端点对 `system` 消息的位置与条数有各自的脾气（有的只认第一条，有的会把
 * 中间插的 system 按 user 处理）。拼成**一条** system 是最兼容的做法 ——
 * 这也正是「中转站」场景下最需要的性质。
 */
object PromptAssembler {

    /** 带进上下文的最近对话条数。 */
    const val RECENT_TURN_LIMIT = 24

    private val TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    private val WEEKDAY_LABELS = arrayOf(
        "周一", "周二", "周三", "周四", "周五", "周六", "周日",
    )

    /**
     * 构造 system 提示词。
     *
     * @param prePrompt 用户的**前置提示**，逐字放在最前面。空串则整段省略。
     * @param recalledMemories 向量/关键词召回出的记忆（不含核心与短期，那两层单独传）
     * @param recalledConversation 召回出的对话原文片段
     */
    fun buildSystemPrompt(
        prePrompt: String,
        personas: PersonaPair,
        coreMemories: List<MemoryEntry>,
        recalledMemories: List<MemoryEntry>,
        shortTermMemories: List<MemoryEntry>,
        recalledConversation: List<String>,
        todos: List<TodoItem>,
        whitelist: List<WhitelistApp>,
        nowMillis: Long,
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

        // ---- 4~6. 记忆三层 ----
        appendMemorySection("核心记忆", coreMemories, "（暂无）")
        appendMemorySection("长久记忆", recalledMemories, "（暂无）")
        appendMemorySection("短期记忆", shortTermMemories, "（暂无）")

        // ---- 7. 相关往事 ----
        if (recalledConversation.isNotEmpty()) {
            appendLine("# 相关往事")
            appendLine("以下是记忆中检索到的、与当前话题可能相关的过往对话原文：")
            recalledConversation.forEach { appendLine("- $it") }
            appendLine()
        }

        // ---- 8. 待办 ----
        appendLine("# 当前待办")
        if (todos.isEmpty()) {
            appendLine("（暂无待办。如果他提到要做某件事，你可以用指令帮他记下来。）")
        } else {
            todos.forEach { todo -> appendLine("- ${describeTodo(todo, nowMillis)}") }
        }
        appendLine()

        // ---- 9. 白名单现状 ----
        appendLine("# 应用白名单现状")
        appendLine("不在下列范围内的应用会被系统拦截。")
        if (whitelist.isEmpty()) {
            appendLine("（当前没有任何临时豁免。）")
        } else {
            whitelist.forEach { entry -> appendLine("- ${describeWhitelist(entry, nowMillis)}") }
        }
        appendLine()

        // ---- 10. 指令协议 ----
        appendLine(COMMAND_PROTOCOL)

        // ---- 11. 当前时间 ----
        appendLine("# 现在")
        appendLine(formatNow(nowMillis))
    }

    /**
     * 把最近的对话映射成模型能吃的消息序列。
     *
     * 系统胶囊（[MessageSender.SYSTEM]）会被过滤掉：那些是界面上给**人**看的
     * 状态播报（「已执行压制」），把它们塞进上下文只会让模型以为自己在跟系统对话。
     *
     * @param history 按时间正序的完整会话
     * @param aiName 用于把历史里的 AI 发言标注清楚
     */
    fun buildTurns(
        systemPrompt: String,
        history: List<ChatMessage>,
    ): List<ChatTurn> {
        val turns = mutableListOf<ChatTurn>()
        turns += ChatTurn.system(systemPrompt)

        history.asSequence()
            .filter { it.sender != MessageSender.SYSTEM }
            .toList()
            .takeLast(RECENT_TURN_LIMIT)
            .forEach { message ->
                turns += when (message.sender) {
                    MessageSender.USER -> ChatTurn.user(message.text)
                    MessageSender.AI -> ChatTurn.assistant(message.text)
                    MessageSender.SYSTEM -> return@forEach
                }
            }

        return turns
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

    private fun formatNow(nowMillis: Long): String {
        val zoned = Instant.ofEpochMilli(nowMillis).atZone(ZoneId.systemDefault())
        val weekday = WEEKDAY_LABELS.getOrElse(zoned.dayOfWeek.value - 1) { "" }
        return "${zoned.format(TIME_FORMATTER)} $weekday"
    }

    private fun formatTime(millis: Long): String =
        Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(TIME_FORMATTER)

    private const val SEPARATOR = "────────────────"

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
