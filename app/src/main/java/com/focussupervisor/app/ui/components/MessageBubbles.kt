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
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.focussupervisor.app.domain.model.AiPersona
import com.focussupervisor.app.domain.model.ChatMessage
import com.focussupervisor.app.domain.model.MessageSender
import com.focussupervisor.app.domain.model.PersonaPair
import com.focussupervisor.app.domain.model.UserPersona
import com.focussupervisor.app.ui.theme.ChatSkin
import com.focussupervisor.app.ui.theme.ChatTheme
import com.focussupervisor.app.ui.theme.FocusSupervisorTheme
import com.focussupervisor.app.ui.util.formatMessageTime

/**
 * 消息气泡组件集。
 *
 * 三类消息在视觉上必须一眼可分，且各自只有一种样式：
 *
 * | 类型    | 位置 | 底色         | 头像 | 姓名 |
 * |---------|------|--------------|------|------|
 * | USER    | 靠右 | 我发的气泡色 | 右侧 | 气泡上方右侧 |
 * | AI      | 靠左 | 对方气泡色   | 左侧 | 气泡上方左侧 |
 * | SYSTEM  | 居中 | 中性胶囊     | 无   | 无   |
 *
 * ===========================================================================
 * 颜色从 ChatTheme 取，不从 FocusTheme 取
 * ===========================================================================
 * 这个区别很关键。`FocusTheme` 是**整个应用**的色板（面板、对话框都用它），
 * 而聊天气泡的颜色属于**主题皮肤** —— 用户可以把气泡换成 iMessage 的蓝，
 * 那时配置面板不应该跟着变蓝。
 *
 * 所以这里全部读 `ChatTheme.skin`。而且皮肤里不止是颜色：圆角大小、要不要显示
 * 头像与姓名、气泡最大宽度，都是「这套主题长什么样」的一部分。iMessage 没有
 * 头像也没有姓名，只换颜色是做不出那个观感的。
 *
 * 这里刻意不做「气泡尾巴」（那个小三角）。微信自己在较新版本里也把尾巴做得极浅，
 * 用圆角差异（指向发送方的那只角更小）来表达方向，信息量足够而视觉更干净。
 * 例外是 iMessage 那类主题 —— 它把两个角设成一样大，于是「没有方向感」
 * 本身就成了那套主题的特征。
 */

/** 系统胶囊最大宽度占屏宽比例。比气泡窄，进一步弱化它的存在感。 */
private const val PILL_MAX_WIDTH_FRACTION = 0.84f

/** 气泡内边距。 */
private val BubbleHorizontalPadding = 12.dp
private val BubbleVerticalPadding = 9.dp

/** 头像与气泡之间的间距。 */
private val AvatarGap = 10.dp

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
            onLongPress = { onLongPress(message) },
            senderName = personas.user.name,
            avatarPath = personas.user.avatarPath,
            isFromUser = true,
            modifier = modifier,
        )

        MessageSender.AI -> ChatBubble(
            text = message.text,
            timestampMillis = message.timestampMillis,
            onLongPress = { onLongPress(message) },
            senderName = personas.ai.name,
            avatarPath = personas.ai.avatarPath,
            isFromUser = false,
            modifier = modifier,
        )

        MessageSender.SYSTEM -> SystemMessagePill(
            text = message.text,
            timestampMillis = message.timestampMillis,
            onLongPress = { onLongPress(message) },
            modifier = modifier,
        )
    }
}

/**
 * 按皮肤算出某一侧气泡的形状。
 *
 * 用一个函数算两边而不是各写一遍：左右气泡的圆角必须永远互为镜像，
 * 分两处写迟早会出现「左边 4dp、右边 6dp」这种只有把截图放大才看得出来、
 * 但在长对话里会明显感觉歪掉的问题。
 */
private fun bubbleShape(skin: ChatSkin, isFromUser: Boolean): Shape = RoundedCornerShape(
    topStart = if (isFromUser) skin.bubbleRadius.dp else skin.bubbleTailRadius.dp,
    topEnd = if (isFromUser) skin.bubbleTailRadius.dp else skin.bubbleRadius.dp,
    bottomStart = skin.bubbleRadius.dp,
    bottomEnd = skin.bubbleRadius.dp,
)

/**
 * 一条带头像与姓名的对话气泡。
 *
 * 布局是「头像 + 一列（姓名 / 气泡 / 时间）」，靠 `horizontalArrangement` 控制
 * 左右镜像 —— 用同一套代码渲染两边，保证左右气泡的内边距、圆角、字号永远一致。
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
    modifier: Modifier = Modifier,
) {
    val skin = ChatTheme.skin
    val horizontalAlignment = if (isFromUser) Alignment.End else Alignment.Start
    val containerColor = if (isFromUser) skin.bubbleUser else skin.bubbleAi
    val contentColor = if (isFromUser) skin.bubbleUserText else skin.bubbleAiText
    val shape = bubbleShape(skin, isFromUser)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        horizontalArrangement = if (isFromUser) Arrangement.End else Arrangement.Start,
    ) {
        // 靠左时头像在前 —— 用两个 if 而不是让 Row 反排，是为了让「头像始终贴着屏幕
        // 外侧」这条规则在代码里就是字面意思，不需要读者在脑子里做一次镜像。
        if (!isFromUser && skin.showAvatar) {
            Avatar(path = avatarPath, name = senderName, round = skin.roundAvatar)
            Box(modifier = Modifier.width(AvatarGap))
        }

        Column(
            modifier = Modifier
                .fillMaxWidth(skin.bubbleMaxWidthFraction)
                .wrapContentWidth(horizontalAlignment),
            horizontalAlignment = horizontalAlignment,
        ) {
            // 姓名。微信在单聊里不显示，但这里必须显示 —— 名字是用户自己设的，
            // 而且他随时可能改，让它在每条消息上方可见才能立刻确认改对了没有。
            // iMessage 那类主题会把它关掉，所以它受皮肤控制。
            if (skin.showSenderName) {
                Text(
                    text = senderName,
                    style = MaterialTheme.typography.labelSmall,
                    color = skin.textSecondary,
                    maxLines = 1,
                    modifier = Modifier.padding(start = 2.dp, end = 2.dp, bottom = 4.dp),
                )
            }

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
                    color = contentColor,
                    modifier = Modifier.padding(
                        horizontal = BubbleHorizontalPadding,
                        vertical = BubbleVerticalPadding,
                    ),
                )
            }

            // 时间戳：极小、极浅，通常贴在气泡下方贴边显示。
            // 「居中」那一档是给 iMessage 类主题用的 —— 它的时间戳是分隔标签，
            // 不是气泡的附属物，跟着气泡走反而不对。
            Text(
                text = formatMessageTime(timestampMillis),
                style = MaterialTheme.typography.bodySmall,
                color = skin.textSecondary,
                textAlign = if (skin.centerTimestamp) TextAlign.Center else TextAlign.Start,
                modifier = Modifier
                    .then(
                        if (skin.centerTimestamp) Modifier.fillMaxWidth() else Modifier,
                    )
                    .padding(top = 3.dp, start = 2.dp, end = 2.dp),
            )
        }

        if (isFromUser && skin.showAvatar) {
            Box(modifier = Modifier.width(AvatarGap))
            Avatar(path = avatarPath, name = senderName, round = skin.roundAvatar)
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
 * - 中性底 + 中灰字，对比度刻意低于正文，做到存在但不抢戏；
 * - 全圆角（50% → 胶囊形），与方中带圆的对话气泡形成形状语言的区分；
 * - 无头像、无姓名 —— 它不代表任何人说话。
 *
 * 时间戳是**有**的，和气泡一样贴在下方。系统播报全部是「什么时候发生了什么」，
 * 去掉时间就等于把最关键的那半句信息丢了；监督场景里「这是十分钟前拦的，
 * 还是昨天拦的」决定了用户接下来该做什么。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SystemMessagePill(
    text: String,
    timestampMillis: Long,
    onLongPress: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val skin = ChatTheme.skin

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            color = skin.systemPill,
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
                color = skin.systemPillText,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }

        Text(
            text = formatMessageTime(timestampMillis),
            style = MaterialTheme.typography.bodySmall,
            color = skin.textSecondary,
            modifier = Modifier.padding(top = 3.dp),
        )
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
            SystemMessagePill(text = "[系统] 已开启屏幕视线感知", timestampMillis = 1_756_000_000_000L)
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
