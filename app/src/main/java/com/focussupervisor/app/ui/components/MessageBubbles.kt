package com.focussupervisor.app.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import com.focussupervisor.app.domain.model.AiPersona
import com.focussupervisor.app.domain.model.ChatMessage
import com.focussupervisor.app.domain.model.MessageSender
import com.focussupervisor.app.domain.model.PersonaPair
import com.focussupervisor.app.domain.model.UserPersona
import com.focussupervisor.app.ui.theme.FocusSupervisorTheme
import com.focussupervisor.app.ui.theme.FocusTheme
import com.focussupervisor.app.ui.util.formatMessageTime

/**
 * 消息气泡组件集。
 *
 * 三类消息在视觉上必须一眼可分，且各自只有一种样式：
 *
 * | 类型    | 位置 | 底色        | 头像 | 姓名 |
 * |---------|------|-------------|------|------|
 * | USER    | 靠右 | 微信绿      | 右侧 | 气泡上方右侧 |
 * | AI      | 靠左 | 纯白        | 左侧 | 气泡上方左侧 |
 * | SYSTEM  | 居中 | 中性灰胶囊  | 无   | 无 |
 *
 * 仿微信的完整版：**头像 + 姓名 + 气泡**。系统胶囊保持无头像无姓名 ——
 * 它不是某个人说的话，而是「系统发生了什么」，加了头像反而会让人以为是第三方。
 *
 * 这里刻意不做「气泡尾巴」（那个小三角）。微信自己在较新版本里也把尾巴做得极浅，
 * 用圆角差异（发送侧上方那只角更小）来表达方向，信息量足够而视觉更干净。
 */

/** 气泡最大宽度占屏宽比例。留出空档，让左右两侧的对话一眼能分开。 */
private const val BUBBLE_MAX_WIDTH_FRACTION = 0.68f

/** 系统胶囊最大宽度占屏宽比例。比气泡窄，进一步弱化它的存在感。 */
private const val PILL_MAX_WIDTH_FRACTION = 0.84f

/** 气泡内边距。 */
private val BubbleHorizontalPadding = 12.dp
private val BubbleVerticalPadding = 9.dp

/** 头像与气泡之间的间距。 */
private val AvatarGap = 10.dp

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
 * @param personas 会话双方的人设。每条消息都要用它取头像与姓名，
 *        所以打包成一个值往下传，而不是拆成两个参数。
 */
@Composable
fun MessageBubble(
    message: ChatMessage,
    personas: PersonaPair,
    onLongPress: (ChatMessage) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (message.sender) {
        MessageSender.USER -> ChatBubble(
            text = message.text,
            timestampMillis = message.timestampMillis,
            onLongPress = { onLongPress(message)},
            senderName = personas.user.name,
            avatarPath = personas.user.avatarPath,
            isFromUser = true,
            containerColor = FocusTheme.colors.bubbleUser,
            shape = UserBubbleShape,
            modifier = modifier,
        )

        MessageSender.AI -> ChatBubble(
            text = message.text,
            timestampMillis = message.timestampMillis,
            onLongPress = { onLongPress(message)},
            senderName = personas.ai.name,
            avatarPath = personas.ai.avatarPath,
            isFromUser = false,
            containerColor = FocusTheme.colors.bubbleAi,
            shape = AiBubbleShape,
            modifier = modifier,
        )

        MessageSender.SYSTEM -> SystemMessagePill(
            text = message.text,
            onLongPress = { onLongPress(message) },
            modifier = modifier,
        )
    }
}

/**
 * 一条带头像与姓名的对话气泡。
 *
 * 布局是「头像 + 一列（姓名 / 气泡 / 时间 / 系统指令说明）」，
 * 靠 [RowScope] 的方向控制左右镜像 —— 用同一套代码渲染两边，
 * 保证左右气泡的内边距、圆角、字号永远一致（分成两个函数迟早会漂移）。
 */
@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun ChatBubble(
    text: String,
    timestampMillis: Long,
    onLongPress: () -> Unit,
    senderName: String,
    avatarPath: String?,
    isFromUser: Boolean,
    containerColor: Color,
    shape: Shape,
    modifier: Modifier = Modifier,
) {
    val horizontalAlignment = if (isFromUser) Alignment.End else Alignment.Start

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        horizontalArrangement = if (isFromUser) Arrangement.End else Arrangement.Start,
    ) {
        // 靠左时头像在前 —— 用两个 if 而不是让 Row 反排，是为了让「头像始终贴着屏幕
        // 外侧」这条规则在代码里就是字面意思，不需要读者在脑子里做一次镜像。
        if (!isFromUser) {
            Avatar(path = avatarPath, name = senderName)
            Box(modifier = Modifier.width(AvatarGap))
        }

        Column(
            modifier = Modifier
                .fillMaxWidth(BUBBLE_MAX_WIDTH_FRACTION)
                .wrapContentWidth(horizontalAlignment),
            horizontalAlignment = horizontalAlignment,
        ) {
            // 姓名。微信在单聊里不显示，但这里必须显示 —— 名字是用户自己设的，
            // 而且他随时可能改，让它在每条消息上方可见才能立刻确认改对了没有。
            Text(
                text = senderName,
                style = MaterialTheme.typography.labelSmall,
                color = FocusTheme.colors.textSecondary,
                maxLines = 1,
                modifier = Modifier.padding(start = 2.dp, end = 2.dp, bottom = 4.dp),
            )

            Surface(
                color = containerColor,
                shape = shape,
                // 刻意不用阴影：灰色背景上的一点阴影会显脏，气泡靠底色区分已经足够。
                shadowElevation = 0.dp,
                tonalElevation = 0.dp,
                // 长按气泡弹出操作面板（编辑 / 删除）。
                // 用 combinedClickable 而不是普通 clickable：聊天消息点一下不该有反应，
                // 只有长按才是「我要对这条消息做点什么」。
                modifier = Modifier.combinedClickable(
                    onClick = {},
                    onLongClick = onLongPress,
                ),
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

        if (isFromUser) {
            Box(modifier = Modifier.width(AvatarGap))
            Avatar(path = avatarPath, name = senderName)
        }
    }
}

/**
 * 系统消息胶囊（System Message Pill）。
 *
 * 用于「[系统] 监测到进入：小红书」「[系统] AI 已放行 微信 · 10 分钟」这类不是对话、
 * 但用户必须知道的状态变动。
 *
 * 设计要点：
 * - 居中，不参与左右对话的视线流动，天然被读成「旁白」；
 * - 中性灰底 + 中灰字，对比度刻意低于正文，做到存在但不抢戏；
 * - 全圆角（50% → 胶囊形），与方中带圆的对话气泡形成形状语言的区分；
 * - 无头像、无姓名、无时间戳 —— 它不代表任何人说话。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SystemMessagePill(
    text: String,
    onLongPress: () -> Unit = {},
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
                .wrapContentWidth(Alignment.CenterHorizontally)
                .combinedClickable(onClick = {}, onLongClick = onLongPress),
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
// 预览
// ---------------------------------------------------------------------------

@Preview(name = "三类消息", showBackground = true, backgroundColor = 0xFFEDEDED)
@Composable
private fun MessageBubblesPreview() {
    val personas = PersonaPair(
        ai = AiPersona(name = "守夜人", description = ""),
        user = UserPersona(name = "阿澈"),
    )

    FocusSupervisorTheme {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SystemMessagePill(text = "[系统] 已开启屏幕视线感知")
            MessageBubble(
                message = ChatMessage(
                    id = "a",
                    sender = MessageSender.AI,
                    text = "接下来我会盯着你。想清楚再打开那些 App。",
                    timestampMillis = 1_758_600_000_000,
                ),
                personas = personas,
                onLongPress = {},
            )
            MessageBubble(
                message = ChatMessage(
                    id = "u",
                    sender = MessageSender.USER,
                    text = "知道了，先写完这一节。",
                    timestampMillis = 1_758_600_060_000,
                ),
                personas = personas,
                onLongPress = {},
            )
        }
    }
}
