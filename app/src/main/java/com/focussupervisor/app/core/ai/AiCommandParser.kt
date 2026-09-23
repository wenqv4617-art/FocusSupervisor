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

    /**
     * 指令块的正则。
     *
     * `.` 默认不匹配换行 —— 这正是我们要的：单行协议，多行的不认。
     * 非贪婪的 `.*?}` 对「值里不含 `}`」的平坦 JSON 足够；含嵌套对象或字符串里带
     * 花括号的会被截断，进而解析失败并产出 Unparsable，不会静默错误执行。
     */
    private val COMMAND_REGEX = Regex("""\[\[cmd:(\{.*?})]]""")

    private val DATE_TIME_FORMATTER: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    private val DATE_FORMATTER: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd")

    private val TIME_FORMATTER: DateTimeFormatter =
        DateTimeFormatter.ofPattern("HH:mm")

    /** 连续三个以上换行压成两个，避免删掉指令行之后留下大片空白。 */
    private val EXTRA_BLANK_LINES = Regex("\n{3,}")

    /**
     * 解析一条回复。
     *
     * @param reply 模型的原始输出
     * @return 干净正文 + 指令列表
     */
    fun parse(reply: String): ParsedAiReply {
        if (reply.isBlank()) return ParsedAiReply("", emptyList())

        val commands = mutableListOf<AiCommand>()

        for (match in COMMAND_REGEX.findAll(reply)) {
            if (commands.size >= AiCommandLimits.MAX_COMMANDS_PER_REPLY) break
            commands += parseSingle(match.groupValues[1])
        }

        // 把指令块整段删掉，再收拾一下因此产生的空行。
        val visible = COMMAND_REGEX.replace(reply, "")
            .lines()
            .joinToString("\n") { it.trimEnd() }
            .replace(EXTRA_BLANK_LINES, "\n\n")
            .trim()

        return ParsedAiReply(visibleText = visible, commands = commands)
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
