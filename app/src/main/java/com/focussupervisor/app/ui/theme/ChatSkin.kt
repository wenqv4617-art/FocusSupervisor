package com.focussupervisor.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * 聊天页皮肤。
 *
 * ===========================================================================
 * 为什么皮肤不直接改 FocusColors
 * ===========================================================================
 * 最省事的做法是「换肤 = 换一套 FocusColors」。但那会有一个立刻能看见的副作用：
 * **面板也被一起换掉**。用户只是想把聊天气泡换成 iMessage 的蓝，结果「向量模型」
 * 配置页的输入框、卡片、按钮全跟着变了色，这不是他要的。
 *
 * 所以聊天皮肤是**独立的一层**：它只管气泡、聊天区背景、系统胶囊、输入栏这几个
 * 「属于聊天页面」的槽位，由 `LocalChatSkin` 提供，只挂在聊天页那棵子树上。
 * 面板继续读 `FocusTheme.colors`，两者互不干扰。
 *
 * ===========================================================================
 * 兜底
 * ===========================================================================
 * [LocalChatSkin] 的默认值由当前 [FocusColors] 推出来（见 [defaultChatSkin]），
 * 也就是「没选任何主题 = 现在的微信样子」。这样 @Preview 和任何忘记套皮肤的地方
 * 都不会渲染出一片空白。
 */
@Immutable
data class ChatSkin(
    /** 我发的气泡底色。 */
    val bubbleUser: Color,
    /** 我发的气泡文字色（iMessage 的蓝底白字要求它能独立于正文色）。 */
    val bubbleUserText: Color,
    /** 对方气泡底色。 */
    val bubbleAi: Color,
    /** 对方气泡文字色。 */
    val bubbleAiText: Color,
    /** 聊天区背景（不含背景图）。 */
    val chatBackground: Color,
    /** 顶栏 / 输入栏底色。 */
    val chrome: Color,
    /** 发丝线。 */
    val hairline: Color,
    /** 系统胶囊底 / 字。 */
    val systemPill: Color,
    val systemPillText: Color,
    /** 正文与次级文字。 */
    val textPrimary: Color,
    val textSecondary: Color,
    /** 输入框底。 */
    val inputField: Color,
    /** 强调色（发送按钮、光标）。 */
    val accent: Color,

    // ---- 形状与结构 ---------------------------------------------------------
    //
    // 主题的差别不只在线色。iMessage 是「全圆角、无头像、无姓名」，
    // 微信是「大圆角 + 头像 + 姓名」。只换颜色会看起来像「微信换了皮」，
    // 而不是另一个应用 —— 所以这几个结构开关也必须跟色板一起走。

    /** 气泡常规圆角（dp）。 */
    val bubbleRadius: Int,
    /** 指向发送方的那个角的圆角（dp）。 */
    val bubbleTailRadius: Int,
    /** 是否显示头像。 */
    val showAvatar: Boolean,
    /** 是否显示气泡上方的姓名。 */
    val showSenderName: Boolean,
    /** 头像形状：true 为圆，false 为圆角方块。 */
    val roundAvatar: Boolean,
    /** 气泡最大宽度占屏宽比例。 */
    val bubbleMaxWidthFraction: Float,
    /** 是否把时间戳居中显示（捡手机文学那类截图风格是居中的）。 */
    val centerTimestamp: Boolean,
) {
    /**
     * 把整份皮肤里的颜色替换掉一部分。
     *
     * 样式注入只认得它能表达的那几项，其余必须保持主题原样 —— 用 data class 的
     * copy 逐个判断「有没有被注入」会写出十几行 if，这里统一收成一个函数。
     */
    fun withColors(
        bubbleUser: Color? = null,
        bubbleUserText: Color? = null,
        bubbleAi: Color? = null,
        bubbleAiText: Color? = null,
        chatBackground: Color? = null,
        chrome: Color? = null,
        hairline: Color? = null,
        systemPill: Color? = null,
        systemPillText: Color? = null,
        textPrimary: Color? = null,
        textSecondary: Color? = null,
        inputField: Color? = null,
        accent: Color? = null,
    ): ChatSkin = copy(
        bubbleUser = bubbleUser ?: this.bubbleUser,
        bubbleUserText = bubbleUserText ?: this.bubbleUserText,
        bubbleAi = bubbleAi ?: this.bubbleAi,
        bubbleAiText = bubbleAiText ?: this.bubbleAiText,
        chatBackground = chatBackground ?: this.chatBackground,
        chrome = chrome ?: this.chrome,
        hairline = hairline ?: this.hairline,
        systemPill = systemPill ?: this.systemPill,
        systemPillText = systemPillText ?: this.systemPillText,
        textPrimary = textPrimary ?: this.textPrimary,
        textSecondary = textSecondary ?: this.textSecondary,
        inputField = inputField ?: this.inputField,
        accent = accent ?: this.accent,
    )
}

/**
 * 由当前面板色板推出的「默认皮肤」。
 *
 * 刻意写成函数而不是常量：默认皮肤必须跟着明暗模式走，写成常量会把浅色值
 * 钉死在深色模式里。
 */
fun defaultChatSkin(colors: FocusColors): ChatSkin = ChatSkin(
    bubbleUser = colors.bubbleUser,
    // 微信绿上写深色字，这是微信自己的做法，对比度实测足够。
    bubbleUserText = colors.textPrimary,
    bubbleAi = colors.bubbleAi,
    bubbleAiText = colors.textPrimary,
    chatBackground = colors.chatBackground,
    chrome = colors.chromeBackground,
    hairline = colors.hairline,
    systemPill = colors.systemPill,
    systemPillText = colors.systemPillText,
    textPrimary = colors.textPrimary,
    textSecondary = colors.textSecondary,
    inputField = colors.inputField,
    accent = colors.accent,
    bubbleRadius = 14,
    bubbleTailRadius = 4,
    showAvatar = true,
    showSenderName = true,
    roundAvatar = true,
    bubbleMaxWidthFraction = 0.68f,
    centerTimestamp = false,
)

/**
 * 当前聊天皮肤的 CompositionLocal。
 *
 * `staticCompositionLocalOf`：换肤是低频事件（用户手动点一下），用 static 版本
 * 可以让读取方在值不变时被整体跳过；代价是换肤时整棵子树重组，而那正是我们要的。
 *
 * 默认值不能直接写 `defaultChatSkin(LightFocusColors)` 之外的表达式 ——
 * CompositionLocal 的默认值在组合之外求值，拿不到当前主题。所以默认值用浅色，
 * 真正的兜底由 [LocalChatSkinProvider] 在每个作用域显式提供。
 */
val LocalChatSkin = staticCompositionLocalOf { defaultChatSkin(LightFocusColors) }

/** 取当前聊天皮肤。 */
object ChatTheme {
    val skin: ChatSkin
        @Composable
        @ReadOnlyComposable
        get() = LocalChatSkin.current
}
