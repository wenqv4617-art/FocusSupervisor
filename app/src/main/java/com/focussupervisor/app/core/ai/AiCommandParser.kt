package com.focussupervisor.app.core.ai

import com.focussupervisor.app.domain.model.AiCommand
import com.focussupervisor.app.domain.model.AiCommandLimits
import com.focussupervisor.app.domain.model.MemoryTier
import com.focussupervisor.app.domain.model.ParsedAiReply
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 把模型的回复拆成「给人看的正文」和「给系统执行的指令」。
 *
 * ===========================================================================
 * 协议
 * ===========================================================================
 * 模型在回复末尾另起一行输出：
 * ```
 * [[cmd:{"type":"whitelist","package":"com.tencent.mm","minutes":10,"reason":"回消息"}]]
 * ```
 *
 * 只认**单行**的指令块。多行 JSON 会让解析变复杂（要处理括号配对），而模型
 * 完全有能力把一条指令写成一行 —— 协议里也会明确要求。
 *
 * ===========================================================================
 * 为什么解析失败不能静默丢弃
 * ===========================================================================
 * 模型偶尔会写出格式不对的 JSON。如果直接丢掉，用户看到的是「AI 说它已经放行了」
 * 但什么都没发生 —— 这是最难排查的一类问题，因为界面上没有任何异常痕迹。
 * 所以解析失败会产出一个 [AiCommand.Unparsable]，由上层显示一条系统提示。
 * **宁可让用户看到「AI 想执行一个操作但格式不对」，也不要让他以为成功了。**
 */
object AiCommandParser {

    /** 指令块的两端标记。 */
    private const val COMMAND_OPEN = "[[cmd:"
    private const val COMMAND_CLOSE = "]]"

    /**
     * =======================================================================
     * 为什么这里一个正则都没有
     * =======================================================================
     * 这里原本是 `Regex("""\[\[cmd:(\{.*?})]]""")`。它在 JVM 上完全正常，
     * 在 Android 上却把整个类炸掉了：
     *
     *     ExceptionInInitializerError ← PatternSyntaxException:
     *     Syntax error in regexp pattern near index 15
     *
     * 原因是 Android 的 java.util.regex 底层是 ICU，而 ICU 和 JVM 在这一点上不同：
     * **一个没有量词与之配对的 `}` 是语法错误**，JVM 却把它当普通字符。
     * 上面那条模式里 `{` 已经正确转义成字面量了，于是后面那个裸 `}` 就没有量词可闭合。
     *
     * 这不是推测，是拿 ICU 74 直接调 `uregex_open` 复现出来的，偏移量和手机上分毫不差：
     *
     *     \[\[cmd:(\{.*?})]]    → 语法错误 0x10301，解析偏移 15   ← 手机上就是这个
     *     \[\[cmd:({.*?})]]     → 语法错误 0x10301，解析偏移 10
     *     \[\[cmd:(\{.*?\})]]   → 通过
     *
     * 真正致命的是它写在 object 的属性初始化里：异常发生在**类初始化**阶段，于是升级成
     * ExceptionInInitializerError，把整个 AiCommandParser 一起带走，
     * 连 `parseDue` 里那几个 DateTimeFormatter 都没机会被求值。
     *
     * 两条教训都落在下面的实现里：
     *  1. 解析这种固定协议，手写扫描比正则更可控 —— 一共两个分隔符，而且正则
     *     本来也处理不了「值里带 `}`」的 JSON，那条非贪婪匹配会被提前截断；
     *  2. 静态初始化不该做任何可能失败的事。所以下面连 `Regex` 都不出现了。
     */

    /**
     * 时间格式器。
     *
     * 写成 `by lazy` 而不是直接放在属性初始化里：这三个格式串是常量、不会失败，
     * 但**属性初始化失败等于类初始化失败**，而类初始化失败是最难定位的一类失败。
     * 代价为零的地方，就不留这个口子。
     */
    private val DATE_TIME_FORMATTER: DateTimeFormatter by lazy {
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    }

    private val DATE_FORMATTER: DateTimeFormatter by lazy {
        DateTimeFormatter.ofPattern("yyyy-MM-dd")
    }

    private val TIME_FORMATTER: DateTimeFormatter by lazy {
        DateTimeFormatter.ofPattern("HH:mm")
    }

    /**
     * 解析一条回复。
     *
     * @param reply 模型的原始输出
     * @return 干净正文 + 指令列表
     */
    fun parse(reply: String): ParsedAiReply {
        if (reply.isBlank()) return ParsedAiReply("", emptyList())

        val blocks = findCommandBlocks(reply)

        // 超出的部分不执行，但仍然从正文里删掉 —— 留在聊天记录里只会让用户困惑。
        val commands = blocks
            .take(AiCommandLimits.MAX_COMMANDS_PER_REPLY)
            .map { parseSingle(it.json) }

        val visible = stripBlocks(reply, blocks)
            .lines()
            .joinToString("\n") { it.trimEnd() }
            .let(::collapseBlankLines)
            .trim()

        return ParsedAiReply(visibleText = visible, commands = commands)
    }

    // -----------------------------------------------------------------------
    // 扫描
    // -----------------------------------------------------------------------

    /** 一条完整指令块。[start, endExclusive) 是要从正文里删掉的范围。 */
    private data class CommandBlock(val start: Int, val endExclusive: Int, val json: String)

    /**
     * 按出现顺序找出所有**完整**的指令块。
     *
     * 「完整」= `[[cmd:` + 花括号配平的 JSON 对象 + `]]`。任何一步不成立就跳过这一处
     * 继续往后找，不完整的块留在正文里让用户看见 —— 悄悄吃掉半句话比看见更糟。
     *
     * 多行的 JSON 会被正常识别（JSON 本身就允许换行），比原来的单行正则宽容。
     */
    private fun findCommandBlocks(reply: String): List<CommandBlock> {
        val blocks = mutableListOf<CommandBlock>()
        var cursor = 0

        while (cursor < reply.length) {
            val open = reply.indexOf(COMMAND_OPEN, cursor)
            if (open < 0) break

            val bodyStart = skipSpaces(reply, open + COMMAND_OPEN.length)
            if (bodyStart >= reply.length || reply[bodyStart] != '{') {
                cursor = open + COMMAND_OPEN.length
                continue
            }

            val objectEnd = matchObjectEnd(reply, bodyStart)
            if (objectEnd < 0) {
                cursor = open + COMMAND_OPEN.length
                continue
            }

            val closeStart = objectEnd + 1
            if (!reply.startsWith(COMMAND_CLOSE, closeStart)) {
                cursor = open + COMMAND_OPEN.length
                continue
            }

            blocks += CommandBlock(
                start = open,
                endExclusive = closeStart + COMMAND_CLOSE.length,
                json = reply.substring(bodyStart, objectEnd + 1),
            )
            cursor = closeStart + COMMAND_CLOSE.length
        }

        return blocks
    }

    private fun skipSpaces(text: String, from: Int): Int {
        var index = from
        while (index < text.length && text[index] == ' ') index++
        return index
    }

    /**
     * 找出 [start] 处那个 `{` 对应的 `}`，返回它的下标；不配平返回 -1。
     *
     * 会跳过字符串字面量和其中的转义，所以 `{"reason":"回复 } 一下"}` 不会被截断 ——
     * 这正是原来那条非贪婪正则做不到的事。
     */
    private fun matchObjectEnd(text: String, start: Int): Int {
        var depth = 0
        var inString = false
        var escaped = false
        var index = start

        while (index < text.length) {
            val char = text[index]
            if (inString) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> inString = false
                }
            } else {
                when (char) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return index
                    }
                }
            }
            index++
        }

        return -1
    }

    /** 按 [blocks] 把指令块从正文里挖掉。 */
    private fun stripBlocks(reply: String, blocks: List<CommandBlock>): String {
        if (blocks.isEmpty()) return reply

        return buildString(reply.length) {
            var cursor = 0
            for (block in blocks) {
                append(reply, cursor, block.start)
                cursor = block.endExclusive
            }
            append(reply, cursor, reply.length)
        }
    }

    /** 连续三个以上换行压成两个，避免删掉指令行之后留下大片空白。 */
    private fun collapseBlankLines(text: String): String = buildString(text.length) {
        var run = 0
        for (char in text) {
            if (char == '\n') {
                run++
                if (run <= 2) append(char)
            } else {
                run = 0
                append(char)
            }
        }
    }

    // -----------------------------------------------------------------------
    // 单条指令
    // -----------------------------------------------------------------------

    private fun parseSingle(json: String): AiCommand {
        val obj = runCatching { JSONObject(json) }.getOrNull()
            ?: return AiCommand.Unparsable(json)

        return when (obj.optString("type").trim().lowercase()) {
            "whitelist", "grant_whitelist" -> parseGrant(obj, json)
            "revoke_whitelist", "revoke" -> parseRevoke(obj, json)
            "todo", "add_todo" -> parseAddTodo(obj, json)
            "todo_done", "complete_todo" -> parseCompleteTodo(obj, json)
            "memory", "write_memory" -> parseMemory(obj, json)
            else -> AiCommand.Unparsable(json)
        }
    }

    private fun parseGrant(obj: JSONObject, raw: String): AiCommand {
        val packageName = obj.optString("package").trim()
        if (packageName.isEmpty()) return AiCommand.Unparsable(raw)

        // 时长在**这里**就被夹到合法区间，而不是留给下游。模型请求 600 分钟时，
        // 最终生效的必须是应用定的上限，这一点不能依赖调用方记得做校验。
        val requested = obj.optInt("minutes", AiCommandLimits.MIN_WHITELIST_MINUTES)
            .coerceIn(AiCommandLimits.MIN_WHITELIST_MINUTES, AiCommandLimits.MAX_WHITELIST_MINUTES)

        return AiCommand.GrantWhitelist(
            packageName = packageName,
            minutes = requested,
            reason = obj.optString("reason").trim().take(120).ifBlank { "AI 申请豁免" },
        )
    }

    private fun parseRevoke(obj: JSONObject, raw: String): AiCommand {
        val packageName = obj.optString("package").trim()
        return if (packageName.isEmpty()) {
            AiCommand.Unparsable(raw)
        } else {
            AiCommand.RevokeWhitelist(packageName)
        }
    }

    private fun parseAddTodo(obj: JSONObject, raw: String): AiCommand {
        val title = obj.optString("title").trim()
            .take(AiCommandLimits.MAX_TODO_TITLE_LENGTH)
        return if (title.isEmpty()) {
            AiCommand.Unparsable(raw)
        } else {
            AiCommand.AddTodo(title = title, dueAtMillis = parseDue(obj.optString("due")))
        }
    }

    private fun parseCompleteTodo(obj: JSONObject, raw: String): AiCommand {
        // id 与 title 都接受：模型多半记不住 id，只会给标题。
        val target = obj.optString("id").trim().ifEmpty { obj.optString("title").trim() }
        return if (target.isEmpty()) AiCommand.Unparsable(raw) else AiCommand.CompleteTodo(target)
    }

    private fun parseMemory(obj: JSONObject, raw: String): AiCommand {
        val content = obj.optString("content").trim()
            .take(AiCommandLimits.MAX_MEMORY_LENGTH)
        if (content.isEmpty()) return AiCommand.Unparsable(raw)

        val tier = when (obj.optString("tier").trim().lowercase()) {
            "core" -> MemoryTier.CORE
            "long", "long_term", "longterm" -> MemoryTier.LONG_TERM
            // 默认短期：模型说「记住这个」时，绝大多数情况是当下的一件事，
            // 该由召回次数来决定它值不值得沉下去，而不是由模型一次性拍板。
            else -> MemoryTier.SHORT_TERM
        }

        return AiCommand.WriteMemory(
            content = content,
            tier = tier,
            pinned = obj.optBoolean("pinned", false),
        )
    }

    // -----------------------------------------------------------------------
    // 时间解析
    // -----------------------------------------------------------------------

    /**
     * 解析 `due` 字段。
     *
     * 依次尝试四种写法，全部失败返回 null（由仓库补默认值）：
     *  1. 纯数字：大于 10^12 当作毫秒时间戳，否则当作「从现在起多少分钟」；
     *  2. `yyyy-MM-dd HH:mm`
     *  3. `yyyy-MM-dd`（默认当天 20:00）
     *  4. `HH:mm`（默认今天该时刻，已过则顺延到明天）
     *
     * 不解析自然语言（「明天下午」）—— 那需要一个日期解析库，而且中文相对时间的
     * 歧义太多（明天下午是几点？）。提示词里会明确要求模型给绝对时间。
     */
    internal fun parseDue(raw: String?, nowMillis: Long = System.currentTimeMillis()): Long? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null

        // 1. 纯数字
        text.toLongOrNull()?.let { value ->
            return if (value > 1_000_000_000_000L) value else nowMillis + value * 60_000L
        }

        val zone = ZoneId.systemDefault()
        val today = java.time.Instant.ofEpochMilli(nowMillis).atZone(zone)

        // 2. yyyy-MM-dd HH:mm
        runCatching { LocalDateTime.parse(text, DATE_TIME_FORMATTER) }.getOrNull()?.let {
            return it.atZone(zone).toInstant().toEpochMilli()
        }

        // 3. yyyy-MM-dd
        runCatching { LocalDate.parse(text, DATE_FORMATTER) }.getOrNull()?.let {
            return it.atTime(DEFAULT_HOUR, 0).atZone(zone).toInstant().toEpochMilli()
        }

        // 4. HH:mm
        runCatching { LocalTime.parse(text, TIME_FORMATTER) }.getOrNull()?.let { time ->
            val candidate = today.toLocalDate().atTime(time).atZone(zone).toInstant().toEpochMilli()
            return if (candidate > nowMillis) {
                candidate
            } else {
                // 今天这个点已经过了，理解成「明天这个点」更符合直觉。
                today.toLocalDate().plusDays(1).atTime(time).atZone(zone).toInstant().toEpochMilli()
            }
        }

        return null
    }

    /** `yyyy-MM-dd` 只给到日期时，默认落在当天 20:00。 */
    private const val DEFAULT_HOUR = 20
}
