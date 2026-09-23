package com.focussupervisor.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.focussupervisor.app.core.time.TimeNarrator
import com.focussupervisor.app.domain.model.TimelineEvent
import com.focussupervisor.app.ui.theme.FocusTheme

/**
 * 时间线面板。
 *
 * ===========================================================================
 * 它给用户看什么
 * ===========================================================================
 * 「监督」这个功能之前只有一个不可观测的内部状态：AI 知道你被拦了几次、多久没说话，
 * 但用户看不到 AI 到底知道什么。一个不透明的监督者是不可信的 —— 用户没法判断
 * 它是真的在盯着，还是在随口说话。
 *
 * 这个面板把那份证据原样摊开：什么时间、发生了什么。它同时也是**唯一**能验证
 * 「用户的操作有没有被记录」的地方。
 *
 * ===========================================================================
 * 为什么按天分组
 * ===========================================================================
 * 时间线里最多 300 条，全是 `HH:mm` 的话，跨天之后就分不清「21:12 那条是今天的还是
 * 昨天的」—— 而这恰恰是判断「他今天到底怎么样」的关键。所以先按日历天切开，
 * 天内的记录由新到旧排列。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimelineSheet(
    events: List<TimelineEvent>,
    nowMillis: Long,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
        sheetState = sheetState,
        containerColor = FocusTheme.colors.chromeBackground,
        contentColor = FocusTheme.colors.textPrimary,
    ) {
        TimelineContent(
            events = events,
            nowMillis = nowMillis,
            onClear = onClear,
            onClose = onDismiss,
        )
    }
}

@Composable
private fun TimelineContent(
    events: List<TimelineEvent>,
    nowMillis: Long,
    onClear: () -> Unit,
    onClose: () -> Unit,
) {
    val groups = groupByDay(events, nowMillis)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = SheetHorizontalPadding)
            .padding(bottom = SheetBottomPadding),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SheetTitle(text = "时间线", modifier = Modifier.weight(1f))
            TextButton(onClick = onClose) {
                Text(
                    text = "关闭",
                    style = MaterialTheme.typography.bodySmall,
                    color = FocusTheme.colors.textSecondary,
                )
            }
        }

        if (groups.isEmpty()) {
            SheetSection(
                title = "还没有记录",
                subtitle = "拦截、放行、待办、消息改动都会记在这里，并且带着时间一起进入 AI 的上下文。",
            ) {
                Text(
                    text = "等它拦下第一个应用，或者记下第一项待办，这里就会有内容。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = FocusTheme.colors.textSecondary,
                )
            }
        } else {
            groups.forEach { group ->
                SheetSection(
                    title = group.label,
                    // 只有「今天」需要一句总结：拦截几次、放行几次，一眼看清今天的状态。
                    // 昨天和更早的日子给数字意义不大，那些天已经过去了。
                    subtitle = if (group.label == TODAY_LABEL) {
                        TimeNarrator.summarizeDay(events, nowMillis).takeIf { it.isNotBlank() }
                    } else {
                        null
                    },
                ) {
                    group.events.forEach { event ->
                        TimelineRow(
                            event = event,
                            nowMillis = nowMillis,
                            showAge = group.label == TODAY_LABEL,
                        )
                    }
                }
            }

            SheetSecondaryButton(
                text = "清空时间线",
                enabled = true,
                onClick = onClear,
            )
        }
    }
}

/**
 * 一条记录。
 *
 * 时间占固定宽度（48dp）而不是自适应：自适应的话每条记录的正文起点会随
 * 「21:12」和「9:12」的宽度差左右跳动，十几条堆在一起就是一列锯齿。
 */
@Composable
private fun TimelineRow(
    event: TimelineEvent,
    nowMillis: Long,
    showAge: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = TimeNarrator.clock(event.atMillis),
            style = MaterialTheme.typography.bodySmall,
            color = FocusTheme.colors.textSecondary,
            modifier = Modifier.width(48.dp),
        )

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = buildString {
                    append(event.kind.label)
                    if (event.title.isNotBlank()) {
                        append('：')
                        append(event.title)
                    }
                },
                style = MaterialTheme.typography.bodyMedium,
                color = FocusTheme.colors.textPrimary,
            )
            if (event.detail.isNotBlank()) {
                Text(
                    text = event.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = FocusTheme.colors.textSecondary,
                )
            }
        }

        if (showAge) {
            Text(
                text = TimeNarrator.describeAge(nowMillis, event.atMillis),
                style = MaterialTheme.typography.bodySmall,
                color = FocusTheme.colors.textSecondary,
            )
        }
    }
}

/** 一天的分组。[events] 已经是由新到旧排好序的。 */
private data class DayGroup(val label: String, val events: List<TimelineEvent>)

/**
 * 按日历天分组，天与天之间由新到旧，天内保持传入顺序（调用方已经倒过序）。
 *
 * `groupBy` 返回的是 LinkedHashMap，因此**插入顺序就是天的顺序** ——
 * 先遇到「今天」，所以「今天」在最前面，正好是想要的次序。
 */
private fun groupByDay(events: List<TimelineEvent>, nowMillis: Long): List<DayGroup> =
    events.asReversed()
        .groupBy { TimeNarrator.dayLabel(it.atMillis, nowMillis) }
        .map { (label, dayEvents) -> DayGroup(label = label, events = dayEvents) }

private const val TODAY_LABEL = "今天"
