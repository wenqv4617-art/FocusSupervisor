package com.focussupervisor.app.core.time

import com.focussupervisor.app.domain.model.TimelineEvent
import com.focussupervisor.app.domain.model.TimelineKind
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * 时间叙述器。
 *
 * ===========================================================================
 * 它为什么存在
 * ===========================================================================
 * 「现在是 2026-09-23 21:14」这句话本身信息量很低，模型拿到它也只能照抄。
 * 真正构成**时间感**的是下面这几种说法：
 *
 *  - 「距上次对话 12 分钟」—— 他刚走开又回来，还是隔了三天才回来；
 *  - 「昨天 23:40」—— 不是「1440 分钟前」，人脑子里不存在这个数；
 *  - 「现在 02:30（深夜）」—— 同一句话在下午说是关心，在凌晨说是该拦；
 *  - 「今天拦截 7 次」—— 比七条「21:12 拦截 小红书」更能说明问题。
 *
 * 所以这里把「绝对时间戳」翻译成这些说法，供提示词和界面共用。
 *
 * ===========================================================================
 * 为什么放在 core 而不是 ui 或 ai
 * ===========================================================================
 * 界面要显示「3 分钟前」，提示词要写「3 分钟前」，两处必须是**同一套规则** ——
 * 否则会出现「界面上说 3 分钟前，AI 以为隔了一小时」这种对不上的情况，而这类
 * 不一致极难察觉。共用一份实现是唯一的办法。它也不依赖 Android 框架，
 * 所以放在 core 里两类使用方都能访问。
 */
object TimeNarrator {

    private val CLOCK_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    private val STAMP_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")
    private val FULL_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    private val DAY_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd")
    private val DAY_WITH_WEEK_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd EEE")

    private val WEEKDAY_LABELS = arrayOf(
        "周一", "周二", "周三", "周四", "周五", "周六", "周日",
    )

    /** `21:14`。 */
    fun clock(millis: Long): String = zoned(millis).format(CLOCK_FORMATTER)

    /** `09-23 21:14`。带日期，用于跨天的场景（对话轮次、事件列表）。 */
    fun stamp(millis: Long): String = zoned(millis).format(STAMP_FORMATTER)

    /**
     * 一天里的时段，例如「深夜」「晚上」。
     *
     * 分界点是按**大多数人**的作息划的，不是按等分 24 小时：23 点和 4 点必须同类
     * （都是「该睡了」），而 6 点和 9 点必须分开（一个是起床，一个是上午）。
     */
    fun timeOfDayLabel(millis: Long): String = when (zoned(millis).hour) {
        in 0..4 -> "深夜"
        in 5..8 -> "早晨"
        in 9..11 -> "上午"
        in 12..13 -> "中午"
        in 14..17 -> "下午"
        in 18..22 -> "晚上"
        else -> "深夜"
    }

    /**
     * 当前时刻的完整说法，例如 `2026-09-23 周三 21:14（晚上）`。
     */
    fun describeNow(nowMillis: Long): String {
        val at = zoned(nowMillis)
        return buildString {
            append(at.format(FULL_FORMATTER))
            append(' ')
            append(WEEKDAY_LABELS.getOrElse(at.dayOfWeek.value - 1) { "" })
            append('（')
            append(timeOfDayLabel(nowMillis))
            append('）')
        }
    }

    /**
     * 相对天数标签：`今天` / `昨天` / `前天` / `09-12 周三`。
     *
     * 用**日历天**判断，不是用 24 小时：昨晚 23:50 和今天 00:10 只差 20 分钟，
     * 但必须说成「昨天」和「今天」，否则「昨天做了什么」这类问题会答错。
     */
    fun dayLabel(millis: Long, nowMillis: Long): String {
        val at = zoned(millis)
        return when (daysBetween(millis, nowMillis)) {
            0L -> "今天"
            1L -> "昨天"
            2L -> "前天"
            else -> at.format(DAY_WITH_WEEK_FORMATTER).let { raw ->
                // EEE 在中文 locale 下会输出「周三」，但 locale 不是中文时会是 "Wed"。
                // 统一换成自己的标签，保证输出稳定。
                val weekday = WEEKDAY_LABELS.getOrElse(at.dayOfWeek.value - 1) { "" }
                raw.substringBefore(' ') + " " + weekday
            }
        }
    }

    /**
     * 时间距离的说法：`刚刚` / `12 分钟前` / `3 小时前` / `昨天 21:10` / `2 天前` / `09-12`。
     *
     * 分段刻意做得不均匀 —— 越近的时间说得越细。人对「刚刚」和「12 分钟前」的区分
     * 很敏感，但对「8 天前」和「9 天前」毫无感觉。
     */
    fun describeAge(nowMillis: Long, thenMillis: Long): String {
        val delta = nowMillis - thenMillis
        // 时钟回拨、或事件时间在未来（不该发生，但不能让界面显示「-3 分钟前」）。
        if (delta <= 0L) return "刚刚"

        val minutes = delta / 60_000L
        if (minutes < 1L) return "刚刚"
        if (minutes < 60L) return "$minutes 分钟前"

        return when (val days = daysBetween(thenMillis, nowMillis)) {
            0L -> "${delta / 3_600_000L} 小时前"
            1L -> "昨天 ${clock(thenMillis)}"
            in 2L..6L -> "$days 天前"
            else -> zoned(thenMillis).format(DAY_FORMATTER)
        }
    }

    /**
     * 今天的总体情况，例如 `拦截 7 次 · 放行 2 次 · 新记待办 1 项 · 完成 1 项`。
     *
     * 只统计**今天**（按设备本地日历天）。没有任何事件时返回空串，调用方据此整段省略，
     * 避免拼出一句「今天：什么都没有」这种看似有信息、实则零信息的废话。
     */
    /** 两个时间戳是否落在同一个**日历天**（本地时区）。 */
    fun isSameDay(aMillis: Long, bMillis: Long): Boolean =
        zoned(aMillis).toLocalDate() == zoned(bMillis).toLocalDate()

    /**
     * [millis] 比 [nowMillis] 早几个日历天。同一天为 0，昨天为 1。
     *
     * 用它来判断「要不要给这个时间戳补上日期」比比较 [dayLabel] 的字符串稳：
     * 那个返回的是给人看的文案，文案一改，调用方的判断就会悄悄失效。
     */
    fun dayOffset(millis: Long, nowMillis: Long): Long = daysBetween(millis, nowMillis)

    fun summarizeDay(events: List<TimelineEvent>, nowMillis: Long): String {
        val today = zoned(nowMillis).toLocalDate()
        val todays = events.filter { zoned(it.atMillis).toLocalDate() == today }
        if (todays.isEmpty()) return ""

        fun count(kind: TimelineKind) = todays.count { it.kind == kind }

        val parts = mutableListOf<String>()
        count(TimelineKind.APP_BLOCKED).let { if (it > 0) parts += "拦截 $it 次" }
        count(TimelineKind.WHITELIST_GRANTED).let { if (it > 0) parts += "放行 $it 次" }
        count(TimelineKind.TODO_ADDED).let { if (it > 0) parts += "新记待办 $it 项" }
        count(TimelineKind.TODO_COMPLETED).let { if (it > 0) parts += "完成待办 $it 项" }
        count(TimelineKind.MEMORY_WRITTEN).let { if (it > 0) parts += "写入记忆 $it 条" }
        return parts.joinToString(" · ")
    }

    /**
     * 一条事件的完整说法，供提示词逐条列出，例如
     * `昨天 21:12 拦截未豁免应用：小红书`。
     */
    fun describeForPrompt(event: TimelineEvent, nowMillis: Long): String = buildString {
        append(dayLabel(event.atMillis, nowMillis))
        append(' ')
        append(clock(event.atMillis))
        append(' ')
        append(event.kind.label)
        if (event.title.isNotBlank()) {
            append('：')
            append(event.title)
        }
        if (event.detail.isNotBlank()) {
            append('（')
            append(event.detail)
            append('）')
        }
    }

    /** 两个时间戳之间隔了几个**日历天**（同一自然日内为 0）。 */
    private fun daysBetween(thenMillis: Long, nowMillis: Long): Long =
        ChronoUnit.DAYS.between(zoned(thenMillis).toLocalDate(), zoned(nowMillis).toLocalDate())

    private fun zoned(millis: Long): ZonedDateTime =
        Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
}
