package com.focussupervisor.app.ui.util

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

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
private val TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val DATE_TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")

/**
 * 把 Unix 毫秒时间戳格式化成设备本地时区的 `HH:mm`。
 *
 * @param timestampMillis Unix 时间戳（毫秒）
 * @return 形如 `09:41` 的字符串
 */
internal fun formatMessageTime(timestampMillis: Long): String =
    Instant.ofEpochMilli(timestampMillis)
        .atZone(ZoneId.systemDefault())
        .format(TIME_FORMATTER)

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
