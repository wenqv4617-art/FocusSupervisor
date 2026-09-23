package com.focussupervisor.app.ui.util

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import com.focussupervisor.app.core.time.TimeNarrator

/**
 * 时间格式化。
 *
 * 只有两条规则，够用且不啰嗦：
 *  - 消息气泡下方：`HH:mm`
 *  - 待办的计划完成时间：`MM-dd HH:mm`（待办可能跨天，只给时分会产生歧义）
 *
 * 刻意不做「昨天 / 星期三 / 2026年9月23日」这类智能分组 —— 一天的监督会话通常很短，
 * 加上日期分组只会让界面变吵，收益为零。
 *
 * 实现上使用 java.time：minSdk 26 起它是平台原生 API，不需要 desugaring，
 * 也不要用 SimpleDateFormat（可变、非线程安全、且不必要）。
 */
private val DATE_TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")

/**
 * 把消息时间戳格式化成**带日期感**的短标签。
 *
 * ```
 *   今天 09:41   →  `09:41`
 *   昨天 21:30   →  `昨天 21:30`
 *   更早         →  `09-12 21:30`
 * ```
 *
 * 为什么不一律只给 `HH:mm`：对话是持久化的，翻上去看到「21:30」时根本分不清那是
 * 今天晚上还是三天前的晚上 —— 而监督场景里「这句话是什么时候说的」正是关键。
 * 也不一律带日期：当天占绝大多数，全都带上日期只会让每条消息都变长一截。
 *
 * 基准时间在函数内部取。这里和其它地方（时间线面板）的取舍不同，是因为这里的
 * 标签是**绝对时间**，逐行独立成立；而相对时间（「3 分钟前」）必须共用同一个基准，
 * 那种情况才会把基准放进状态。
 *
 * @param timestampMillis Unix 时间戳（毫秒）
 */
internal fun formatMessageTime(
    timestampMillis: Long,
    nowMillis: Long = System.currentTimeMillis(),
): String = when (TimeNarrator.dayOffset(timestampMillis, nowMillis)) {
    0L -> TimeNarrator.clock(timestampMillis)
    1L -> "昨天 ${TimeNarrator.clock(timestampMillis)}"
    else -> TimeNarrator.stamp(timestampMillis)
}

/**
 * 把 Unix 毫秒时间戳格式化成设备本地时区的 `MM-dd HH:mm`。
 *
 * @param timestampMillis Unix 时间戳（毫秒）
 * @return 形如 `09-23 21:30` 的字符串
 */
internal fun formatPlannedTime(timestampMillis: Long): String =
    Instant.ofEpochMilli(timestampMillis)
        .atZone(ZoneId.systemDefault())
        .format(DATE_TIME_FORMATTER)