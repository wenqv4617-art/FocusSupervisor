package com.focussupervisor.app.ui.theme

import androidx.compose.ui.graphics.Color
import com.focussupervisor.app.core.style.StyleInjector
import com.focussupervisor.app.domain.model.ChatAppearance
import com.focussupervisor.app.domain.model.ChatPresetIds
import com.focussupervisor.app.domain.model.StyleOverrides

/**
 * 预置聊天主题。
 *
 * ===========================================================================
 * 为什么颜色写在这里，而不是进领域模型
 * ===========================================================================
 * 「iMessage 的蓝是 #0B84FF」是纯粹的展示决策。把它写进领域层的数据结构里，
 * 将来想把蓝色调深一档，就得做一次数据迁移 —— 而这件事本来改一行常量就够了。
 *
 * 所以领域层只记「用户选了 imessage」，具体长什么样由这里决定。
 *
 * ===========================================================================
 * 三套主题分别对应对什么
 * ===========================================================================
 * | 主题 | 底色 | 我的气泡 | 结构 |
 * |------|------|----------|------|
 * | 日间微信 | 中性灰 | 微信绿 + 深字 | 大圆角 + 头像 + 姓名 |
 * | 仿 iMessage | 纯白 | 蓝底白字 | 全圆角、无头像无姓名、时间戳居中 |
 * | 捡手机文学 | 蓝灰 | 黄底深字 | 小圆角、有头像无姓名 |
 *
 * 注意「仿 iMessage」那一行里的**无头像、无姓名、时间戳居中**：这三条跟颜色一样是
 * 主题的一部分。只换颜色的话，用户看到的仍然是「一个染了蓝的微信」，
 * 而不是 iMessage。
 */

/** 一套预置主题。 */
data class ChatThemePreset(
    val id: String,
    val name: String,
    /** 一句话说明这套主题的特征，显示在选择器下方。 */
    val description: String,
    val skin: ChatSkin,
)

/** 三个预置的固定日间皮肤。 */
private fun wechatSkin(): ChatSkin = ChatSkin(
    bubbleUser = Color(0xFF95EC69),
    bubbleUserText = Color(0xFF191919),
    bubbleAi = Color(0xFFFFFFFF),
    bubbleAiText = Color(0xFF191919),
    chatBackground = Color(0xFFEDEDED),
    chrome = Color(0xFFF7F7F7),
    hairline = Color(0xFFDCDCDC),
    systemPill = Color(0xFFD6D6D6),
    systemPillText = Color(0xFF6E6E6E),
    textPrimary = Color(0xFF191919),
    textSecondary = Color(0xFF9A9A9A),
    inputField = Color(0xFFFFFFFF),
    accent = Color(0xFF07C160),
    bubbleRadius = 14,
    bubbleTailRadius = 4,
    showAvatar = true,
    showSenderName = true,
    roundAvatar = true,
    bubbleMaxWidthFraction = 0.68f,
    centerTimestamp = false,
)

private fun imessageSkin(): ChatSkin = ChatSkin(
    // iMessage 的蓝。比微信绿饱和得多，但它是这套主题最核心的一眼识别特征。
    bubbleUser = Color(0xFF0B84FF),
    bubbleUserText = Color(0xFFFFFFFF),
    bubbleAi = Color(0xFFE9E9EB),
    bubbleAiText = Color(0xFF1C1C1E),
    chatBackground = Color(0xFFFFFFFF),
    chrome = Color(0xFFF7F7F9),
    hairline = Color(0xFFD8D8DC),
    systemPill = Color(0xFFE9E9EB),
    systemPillText = Color(0xFF7C7C80),
    textPrimary = Color(0xFF000000),
    textSecondary = Color(0xFF8A8A8E),
    inputField = Color(0xFFF2F2F7),
    accent = Color(0xFF0B84FF),
    // 全圆角：iMessage 没有「指向发送方的小角」这个概念。
    bubbleRadius = 18,
    bubbleTailRadius = 18,
    showAvatar = false,
    showSenderName = false,
    roundAvatar = true,
    bubbleMaxWidthFraction = 0.72f,
    // iMessage 的时间戳是居中的分隔标签，不是贴气泡的小字。
    centerTimestamp = true,
)

private fun pickupSkin(): ChatSkin = ChatSkin(
    // 「捡手机文学」是一类截图体裁：蓝灰底、黄气泡、圆头像、不显示姓名。
    // 色值取自用户给的那张参考图。
    bubbleUser = Color(0xFFFFD75E),
    bubbleUserText = Color(0xFF3D3320),
    bubbleAi = Color(0xFFFFFFFF),
    bubbleAiText = Color(0xFF1F1F1F),
    chatBackground = Color(0xFFB4C0CE),
    chrome = Color(0xFFC6CFDB),
    hairline = Color(0x33000000),
    systemPill = Color(0x66FFFFFF),
    systemPillText = Color(0xFF4A4A4A),
    textPrimary = Color(0xFF1F1F1F),
    textSecondary = Color(0xFF6B6B6B),
    inputField = Color(0xFFFFFFFF),
    accent = Color(0xFFF0A500),
    bubbleRadius = 10,
    bubbleTailRadius = 4,
    showAvatar = true,
    showSenderName = false,
    roundAvatar = true,
    bubbleMaxWidthFraction = 0.66f,
    centerTimestamp = false,
)

/**
 * 可供选择的预置主题。
 *
 * 注意这里**不含 [ChatPresetIds.FOLLOW_APP]**：那一项不是一套皮肤，而是
 * 「不覆盖，跟着应用明暗走」。把它混进这个列表会让「选中的是预设」这个判断
 * 变得含糊，所以它由 [resolveSkin] 单独处理。
 */
val chatThemePresets: List<ChatThemePreset> = listOf(
    ChatThemePreset(
        id = ChatPresetIds.WECHAT,
        name = "默认日间微信",
        description = "中性灰底 + 微信绿气泡，带头像与姓名。和真正的微信最接近。",
        skin = wechatSkin(),
    ),
    ChatThemePreset(
        id = ChatPresetIds.IMESSAGE,
        name = "仿 iMessage",
        description = "纯白底 + 蓝色气泡，收起来头像与姓名、时间戳居中。",
        skin = imessageSkin(),
    ),
    ChatThemePreset(
        id = ChatPresetIds.PICKUP,
        name = "捡手机文学",
        description = "蓝灰底 + 黄色气泡，小圆角、圆头像、不显示姓名。",
        skin = pickupSkin(),
    ),
)

/** 按 id 取预置，取不到返回 null。 */
fun chatThemePresetOf(id: String): ChatThemePreset? =
    chatThemePresets.firstOrNull { it.id == id }

/**
 * 把「用户的选择」解析成一份真正能渲染的皮肤。
 *
 * 顺序是**固定的三步**，顺序本身是设计的一部分：
 * 1. 先取基础皮肤（跟随应用明暗，或某个预置主题）；
 * 2. 再套用户在样式框里注入的覆盖项 —— 注入永远排在预置之后，
 *    否则「我明明写了红气泡，它还是绿的」；
 * 3. 背景图/纯色不在这里处理，它由聊天页单独画在消息列表下面。
 *
 * @param appearance 用户的外观选择。
 * @param appColors 当前应用色板，只用于「跟随应用明暗」那一项。
 */
fun resolveChatSkin(appearance: ChatAppearance, appColors: FocusColors): ChatSkin {
    val base = chatThemePresetOf(appearance.presetId)?.skin ?: defaultChatSkin(appColors)
    return applyOverrides(base, overridesOf(appearance))
}

/**
 * 把解析好的覆盖项套到皮肤上。
 *
 * 单独抽出来是因为它同时被 [resolveChatSkin] 和 @Preview 使用 ——
 * 预览想直接看「某套皮肤 + 某段样式」的效果，不必造一个完整的 ChatAppearance。
 */
fun applyOverrides(base: ChatSkin, overrides: StyleOverrides): ChatSkin {
    if (overrides.isEmpty) return base

    val withColors = base.withColors(
        bubbleUser = overrides.bubbleUser?.let { Color(it) },
        bubbleUserText = overrides.bubbleUserText?.let { Color(it) },
        bubbleAi = overrides.bubbleAi?.let { Color(it) },
        bubbleAiText = overrides.bubbleAiText?.let { Color(it) },
        chatBackground = overrides.chatBackground?.let { Color(it) },
        chrome = overrides.chrome?.let { Color(it) },
        hairline = overrides.hairline?.let { Color(it) },
        systemPill = overrides.systemPill?.let { Color(it) },
        systemPillText = overrides.systemPillText?.let { Color(it) },
        textPrimary = overrides.textPrimary?.let { Color(it) },
        textSecondary = overrides.textSecondary?.let { Color(it) },
        inputField = overrides.inputField?.let { Color(it) },
        accent = overrides.accent?.let { Color(it) },
    )

    return withColors.copy(
        bubbleRadius = overrides.bubbleRadius ?: withColors.bubbleRadius,
        bubbleTailRadius = overrides.bubbleTailRadius ?: withColors.bubbleTailRadius,
        bubbleMaxWidthFraction = overrides.bubbleMaxWidthPercent
            ?.let { it / 100f }
            ?: withColors.bubbleMaxWidthFraction,
        showAvatar = overrides.showAvatar ?: withColors.showAvatar,
        showSenderName = overrides.showSenderName ?: withColors.showSenderName,
        centerTimestamp = overrides.centerTimestamp ?: withColors.centerTimestamp,
        roundAvatar = overrides.roundAvatar ?: withColors.roundAvatar,
    )
}

/**
 * 由 [ChatAppearance] 现算覆盖项。
 *
 * 解析本身是纯函数且很便宜（几十行字符串扫描），但聊天页每次重组都跑一遍没必要，
 * 所以调用方应当 `remember`。这里刻意**不**缓存到仓库里：样式文本是唯一事实来源，
 * 缓存派生结果只会多出一份需要同步的状态。
 */
fun overridesOf(appearance: ChatAppearance): StyleOverrides =
    StyleInjector.parse(appearance.styleSheet).overrides
