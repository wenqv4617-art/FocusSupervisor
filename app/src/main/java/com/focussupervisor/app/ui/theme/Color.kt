package com.focussupervisor.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * 全局色板。
 *
 * 命名规则：`<用途><角色>`。所有颜色都必须在这里有名字，禁止在 Composable 里写
 * 十六进制字面量 —— 一是换肤时只改一处，二是「这个灰色到底是哪种灰」在评审时
 * 能直接对照。
 *
 * 整体方向：仿微信的中性灰底 + 单一绿色强调色。没有渐变、没有彩色阴影、没有
 * 第三种强调色。监督类应用要的是「安静」，颜色越少越好。
 */

// ---------------------------------------------------------------------------
// 浅色（主用）
// ---------------------------------------------------------------------------

/** 聊天页背景：微信同款中性灰，不用纯白，长时间看不刺眼。 */
val WeChatBackground = Color(0xFFEDEDED)

/** 顶栏 / 底栏背景，比聊天区略亮一点，靠一条发丝线区分层次。 */
val ChromeBackground = Color(0xFFF7F7F7)

/** 发丝分割线。 */
val Hairline = Color(0xFFDCDCDC)

/** 用户气泡：微信绿。整个界面唯一的高饱和色块。 */
val BubbleUser = Color(0xFF95EC69)

/** AI 气泡：纯白。 */
val BubbleAi = Color(0xFFFFFFFF)

/** 输入框底色。 */
val InputField = Color(0xFFFFFFFF)

/** 系统消息胶囊底色：中性灰，不带任何倾向性。 */
val SystemPillBackground = Color(0xFFD6D6D6)

/** 系统消息文字：中灰，刻意弱于正文，让状态播报「存在但不抢戏」。 */
val SystemPillText = Color(0xFF6E6E6E)

/** 正文主色。不用纯黑，纯黑在灰底上过于生硬。 */
val TextPrimary = Color(0xFF191919)

/** 次级文字：时间戳、副标题、面板图标下的文字。 */
val TextSecondary = Color(0xFF9A9A9A)

/** 图标色（未激活）。 */
val IconTint = Color(0xFF5A5A5A)

/** 强调色：发送按钮、选中态。 */
val Accent = Color(0xFF07C160)

/** 强调色不可用态（草稿为空时的发送按钮）。 */
val AccentDisabled = Color(0xFFC6C6C6)

/**
 * 提醒色。
 *
 * 界面上**唯一**允许出现的「需要你处理」信号，只有两个用途：面板入口右上角的小红点、
 * 以及逾期待办的标记。这个应用没有第二种「警告色」—— 一旦出现第二种，用户就得开始
 * 分辨哪个红更重要，而那正是要避免的噪音。
 */
val Attention = Color(0xFFFA5151)

// ---------------------------------------------------------------------------
// 全屏遮罩（LockOverlayController）
//
// 这几个颜色不走 Compose 主题 —— 遮罩是 WindowManager 直接持有的纯 View，
// 但色值仍然定义在这里，保证「遮罩的黑」和「界面的黑」是同一个黑。
// ---------------------------------------------------------------------------

/** 遮罩底色。比纯黑略亮一点点，避免在 OLED 上与息屏混淆。 */
val OverlayBackground = Color(0xFF0A0A0A)

/** 遮罩主提示语。 */
val OverlayHeadline = Color(0xFFEDEDED)

/** 遮罩上的包名等次要信息。 */
val OverlayMeta = Color(0xFF6B6B6B)

/** 遮罩底部出路说明。刻意比包名更暗，让它退到最后。 */
val OverlayHint = Color(0xFF4F4F4F)

// ---------------------------------------------------------------------------
// 深色
// ---------------------------------------------------------------------------

val DarkBackground = Color(0xFF111111)
val DarkChrome = Color(0xFF1B1B1B)
val DarkHairline = Color(0xFF2C2C2C)
val DarkBubbleUser = Color(0xFF3E6B34)
val DarkBubbleAi = Color(0xFF2A2A2A)
val DarkInputField = Color(0xFF2A2A2A)
val DarkSystemPill = Color(0xFF2E2E2E)
val DarkSystemPillText = Color(0xFF9E9E9E)
val DarkTextPrimary = Color(0xFFEDEDED)
val DarkTextSecondary = Color(0xFF8A8A8A)
val DarkIconTint = Color(0xFFB0B0B0)
val DarkAttention = Color(0xFFE0736F)
