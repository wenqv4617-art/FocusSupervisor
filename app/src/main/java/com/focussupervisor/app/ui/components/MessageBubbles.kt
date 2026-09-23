package com.focussupervisor.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.focussupervisor.app.domain.model.ChatMessage
import com.focussupervisor.app.domain.model.MessageSender
import com.focussupervisor.app.ui.theme.FocusSupervisorTheme
import com.focussupervisor.app.ui.theme.FocusTheme
import com.focussupervisor.app.ui.util.formatMessageTime

/**
 * 消息气泡组件集。
 *
 * 三类消息在视觉上必须一眼可分，且各自只有一种样式：
 *
 * | 类型    | 位置 | 底色        | 用途                       |
 * |---------|------|-------------|----------------------------|
 * | USER    | 靠右 | 微信绿      | 用户输入                   |
 * | AI      | 靠左 | 纯白        | AI 监管者的回复            |
 * | SYSTEM  | 居中 | 中性灰胶囊  | 系统级状态事件（非对话）   |
 *
 * 这里刻意不做「气泡尾巴」（那个小三角）。微信自己在较新版本里也把尾巴做得极浅，
 * 用圆角差异（发送侧上方那只角更小）来表达方向，信息量足够而视觉更干净。
 */

/** 气泡最大宽度占屏宽比例。留出 22% 的空档，让左右两侧的对话一眼能分开。 */
private const val BUBBLE_MAX_WIDTH_FRACTION = 0.78f

/** 系统胶囊最大宽度占屏宽比例。比气泡窄，进一步弱化它的存在感。 */
private const val PILL_MAX_WIDTH_FRACTION = 0.84f

/** 气泡内边距。 */
private val BubbleHorizontalPadding = 12.dp
private val BubbleVerticalPadding = 9.dp

/** 气泡圆角：常规角 14dp，指向发送方的那只角压到 4dp。 */
private val UserBubbleShape = RoundedCornerShape(
    topStart = 14.dp,
    topEnd = 4.dp,
    bottomEnd = 14.dp,
    bottomStart = 14.dp,
)

private val AiBubbleShape = RoundedCornerShape(
    topStart = 4.dp,
    topEnd = 14.dp,
    bottomEnd = 14.dp,
    bottomStart = 14.dp,
)

/**
 * 消息分发入口：按 [MessageSender] 选择具体的渲染方式。
 *
 * 调用方（LazyColumn）只需要认识这一个函数，新增消息类型时改动被限制在本文件内。
 */
@Composable
fun MessageBubble(
    message: ChatMessage,
    modifier: Modifier = Modifier,
) {
    when (message.sender) {
        MessageSender.USER -> UserMessageBubble(
            text = message.text,
            timestampMillis = message.timestampMillis,
            modifier = modifier,
        )

        MessageSender.AI -> AiMessageBubble(
            text = message.text,
            timestampMillis = message.timestampMillis,
            modifier = modifier,
        )

        MessageSender.SYSTEM -> SystemMessagePill(
            text = message.text,
            modifier = modifier,
        )
    }
}

/**
 * 用户气泡：靠右、微信绿、深色正文。
 *
 * 实现在 [ChatBubble] 里，这一层只负责给出「靠右 + 绿色 + 右上角小圆角」这组参数。
 */
@Composable
fun UserMessageBubble(
    text: String,
    timestampMillis: Long,
    modifier: Modifier = Modifier,
) {
    ChatBubble(
        text = text,
        timestampMillis = timestampMillis,
        isFromUser = true,
        containerColor = FocusTheme.colors.bubbleUser,
        shape = UserBubbleShape,
        modifier = modifier,
    )
}

/**
 * AI 气泡：靠左、纯白、深色正文。
 */
@Composable
fun AiMessageBubble(
    text: String,
    timestampMillis: Long,
    modifier: Modifier = Modifier,
) {
    ChatBubble(
        text = text,
        timestampMillis = timestampMillis,
        isFromUser = false,
        containerColor = FocusTheme.colors.bubbleAi,
        shape = AiBubbleShape,
        modifier = modifier,
    )
}

/**
 * 气泡的公共实现。
 *
 * 宽度控制是这里唯一的技巧：外层 Row 撑满，内层 Column 先被 [fillMaxWidth] 撑到
 * 78% 宽，再用 [wrapContentWidth] 把内容重新按自身尺寸对齐到该区域的一侧。
 * 这样短消息不会被迫拉成一条长条，长消息又不会顶满整屏 —— 不需要
 * BoxWithConstraints 那套子组合开销。
 */
@Composable
private fun ChatBubble(
    text: String,
    timestampMillis: Long,
    isFromUser: Boolean,
    containerColor: Color,
    shape: Shape,
    modifier: Modifier = Modifier,
) {
    val horizontalAlignment = if (isFromUser) Alignment.End else Alignment.Start

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        horizontalAlignment = horizontalAlignment,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(BUBBLE_MAX_WIDTH_FRACTION)
                .wrapContentWidth(horizontalAlignment),
            horizontalAlignment = horizontalAlignment,
        ) {
            Surface(
                color = containerColor,
                shape = shape,
                // 刻意不用阴影：灰色背景上的一点阴影会显脏，气泡靠底色区分已经足够。
                shadowElevation = 0.dp,
                tonalElevation = 0.dp,
            ) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = FocusTheme.colors.textPrimary,
                    modifier = Modifier.padding(
                        horizontal = BubbleHorizontalPadding,
                        vertical = BubbleVerticalPadding,
                    ),
                )
            }

            // 时间戳：极小、极浅，只在气泡下方贴边显示。
            Text(
                text = formatMessageTime(timestampMillis),
                style = MaterialTheme.typography.bodySmall,
                color = FocusTheme.colors.textSecondary,
                modifier = Modifier.padding(top = 3.dp, start = 2.dp, end = 2.dp),
            )
        }
    }
}

/**
 * 系统消息胶囊（System Message Pill）。
 *
 * 用于「[系统] 监测到进入：小红书」「[系统] 已开启屏幕视线感知」这类不是对话、
 * 但用户必须知道的状态变动。
 *
 * 设计要点：
 * - 居中，不参与左右对话的视线流动，天然被读成「旁白」；
 * - 中性灰底 + 中灰字，对比度刻意低于正文，做到存在但不抢戏；
 * - 全圆角（50% → 胶囊形），与方中带圆的对话气泡形成形状语言的区分；
 * - 不显示时间戳：系统事件的价值在内容，加上时间只会让它更像一条消息。
 */
@Composable
fun SystemMessagePill(
    text: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            color = FocusTheme.colors.systemPill,
            shape = RoundedCornerShape(percent = 50),
            shadowElevation = 0.dp,
            tonalElevation = 0.dp,
            modifier = Modifier
                // 先撑到 84% 定出「最宽能到哪」，再用 wrapContentWidth 让胶囊按
                // 自身文字宽度贴合、并在这 84% 里居中。少了后半句，短句也会被
                // 拉成一条几乎满屏的横条。
                .fillMaxWidth(PILL_MAX_WIDTH_FRACTION)
                .wrapContentWidth(Alignment.CenterHorizontally),
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = FocusTheme.colors.systemPillText,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 预览：只用于开发期在 Android Studio 里核对观感，不进正式包。
// ---------------------------------------------------------------------------

@Preview(name = "三类消息", showBackground = true, backgroundColor = 0xFFEDEDED)
@Composable
private fun MessageBubblesPreview() {
    FocusSupervisorTheme {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SystemMessagePill(text = "[系统] 已开启屏幕视线感知")
            AiMessageBubble(
                text = "接下来我会盯着你。想清楚再打开那些 App。",
                timestampMillis = 1_758_600_000_000,
            )
            UserMessageBubble(
                text = "知道了，先写完这一节。",
                timestampMillis = 1_758_600_060_000,
            )
        }
    }
}
