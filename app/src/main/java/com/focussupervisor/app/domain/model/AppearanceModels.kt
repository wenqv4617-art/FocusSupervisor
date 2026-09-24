package com.focussupervisor.app.domain.model

/**
 * 领域层：聊天页外观。
 *
 * ===========================================================================
 * 这里存什么、不存什么
 * ===========================================================================
 * 存的是**用户的选择**：选了哪套主题、背景图长什么样、注入了一段什么样式文本。
 * 不存**具体的颜色值** —— 那些是表现层的事，写进领域层等于把「iMessage 的蓝是
 * #0B84FF」这种纯设计决策钉死在数据里，将来想调深浅就得做数据迁移。
 *
 * 所以真正把 presetId 变成一堆颜色的工作在 `ui/theme/ChatPresets.kt`。
 */

/** 主题预设的稳定 id。 */
object ChatPresetIds {
    /**
     * 跟随应用明暗。
     *
     * 这是**初始状态**，也是唯一一个会随系统深色模式变化的选项。
     * 另外三个是固定色（日间），理由是：主题是用户显式挑的，挑的就是那个观感，
     * 跟着系统变成深色反而破坏了他选它的理由 —— 跟换壁纸是同一回事。
     */
    const val FOLLOW_APP = "follow_app"

    /** 默认日间微信。 */
    const val WECHAT = "wechat"

    /** 仿 iMessage。 */
    const val IMESSAGE = "imessage"

    /** 捡手机文学（黄气泡 + 蓝灰底）。 */
    const val PICKUP = "pickup"

    /** 全部可选值，供界面遍历与数据校验。 */
    val ALL = listOf(FOLLOW_APP, WECHAT, IMESSAGE, PICKUP)

    /** 兜底：数据里出现未知 id 时退回到这个。 */
    const val FALLBACK = FOLLOW_APP

    /** 未知 id 一律收敛到 [FALLBACK]，避免一份手改过的备份把界面搞成白板。 */
    fun sanitize(id: String): String = if (id in ALL) id else FALLBACK
}

/**
 * 聊天区背景的来源。
 *
 * 三选一而不是「图片路径为空就用纯色」：后者无法区分「我没设图」和
 * 「我设了图又把它删了」，也就没法在界面上正确回显用户的选择。
 */
enum class BackgroundMode {
    /** 用主题自带的底色。 */
    PRESET,

    /** 用一张自定义图片。 */
    IMAGE,

    /** 用用户手选的一个纯色。 */
    SOLID,
    ;

    companion object {
        fun sanitize(name: String): BackgroundMode =
            entries.firstOrNull { it.name == name } ?: PRESET
    }
}

/**
 * 聊天页外观的完整状态。
 *
 * @param presetId 主题预设 id，见 [ChatPresetIds]。
 * @param backgroundMode 背景来源。
 * @param backgroundImagePath 自定义背景图的**绝对路径**。存在应用私有目录里
 *        （`filesDir/chat_background.jpg`），不写进相册 —— 用户从相册选一张图来做
 *        聊天背景，不代表他希望这张图再被复制一份到相册。
 * @param backgroundImageOpacity 背景图不透明度，0.05~1。**下限不是 0**：
 *        完全透明等于「我明明设了图却什么都看不到」，那是个只会让人以为坏了的状态。
 * @param backgroundSolidColor 纯色背景的 ARGB，仅在 [BackgroundMode.SOLID] 下有意义。
 * @param styleSheet 用户注入的样式文本。语法见 `core/style/StyleInjector`。
 */
data class ChatAppearance(
    val presetId: String = ChatPresetIds.FALLBACK,
    val backgroundMode: BackgroundMode = BackgroundMode.PRESET,
    val backgroundImagePath: String? = null,
    val backgroundImageOpacity: Float = 1f,
    val backgroundSolidColor: Long? = null,
    val styleSheet: String = "",
) {
    /** 是否处于「用户动过手」的状态。用于界面上显示「已自定义」标记。 */
    val isCustomized: Boolean
        get() = presetId != ChatPresetIds.FOLLOW_APP ||
            backgroundMode != BackgroundMode.PRESET ||
            styleSheet.isNotBlank()

    companion object {
        /** 背景图透明度下限。 */
        const val MIN_IMAGE_OPACITY = 0.05f

        /** 注入样式的长度上限。 */
        const val MAX_STYLE_SHEET_LENGTH = 4000

        /** 背景图落盘的文件名（相对 `filesDir`）。 */
        const val BACKGROUND_FILE_NAME = "chat_background.jpg"

        /** 背景图落盘后的最长边。1440 够 1080p 屏用，也不会让备份文件虚胖。 */
        const val BACKGROUND_MAX_EDGE = 1440

        /**
         * 把一份可能是手改过的外观收敛到合法范围。
         *
         * 备份文件是用户可以直接编辑的，恢复时不设防就等于把崩溃入口交出去：
         * 一个 500% 的透明度、一个不存在的 presetId 都足以让界面渲染得很奇怪。
         */
        fun sanitize(appearance: ChatAppearance): ChatAppearance = appearance.copy(
            presetId = ChatPresetIds.sanitize(appearance.presetId),
            backgroundImagePath = appearance.backgroundImagePath?.takeIf { it.isNotBlank() },
            backgroundImageOpacity = appearance.backgroundImageOpacity
                .coerceIn(MIN_IMAGE_OPACITY, 1f),
            styleSheet = appearance.styleSheet.take(MAX_STYLE_SHEET_LENGTH),
        )
    }
}

/**
 * 样式注入解析出来的结果。
 *
 * ===========================================================================
 * 为什么解析结果是一堆可空字段，而不是直接一个 ChatSkin
 * ===========================================================================
 * 因为「用户没写这一条」和「用户写了、值等于默认值」必须能区分开。
 * 前者要保留主题原有的颜色，后者要覆盖成用户给的值 —— 如果解析结果用非空字段
 * 加默认值，用户只写了一句 `.bubble-user { background: #FF0000 }`，其余十几项
 * 都会被默认值悄悄覆盖掉，整套主题就毁了。
 *
 * 每一格都是 `null` 表示「这一项没被注入」。
 */
data class StyleOverrides(
    val bubbleUser: Long? = null,
    val bubbleUserText: Long? = null,
    val bubbleAi: Long? = null,
    val bubbleAiText: Long? = null,
    val chatBackground: Long? = null,
    val chrome: Long? = null,
    val hairline: Long? = null,
    val systemPill: Long? = null,
    val systemPillText: Long? = null,
    val textPrimary: Long? = null,
    val textSecondary: Long? = null,
    val inputField: Long? = null,
    val accent: Long? = null,
    val bubbleRadius: Int? = null,
    val bubbleTailRadius: Int? = null,
    val bubbleMaxWidthPercent: Int? = null,
    val showAvatar: Boolean? = null,
    val showSenderName: Boolean? = null,
    val centerTimestamp: Boolean? = null,
    val roundAvatar: Boolean? = null,
) {
    /** 一个字都没解析出来。界面据此提示「你写的样式一条都没生效」。 */
    val isEmpty: Boolean get() = this == StyleOverrides()
}

/**
 * 一次解析的完整结果。
 *
 * @param overrides 成功解析出来的覆盖项。
 * @param applied 生效了的规则描述，例如 `".bubble-user 的 background"`。
 *        界面把它列出来 —— 用户写 CSS 最需要的是「我写的哪句被认了」，
 *        而不是一句笼统的「已应用」。
 * @param ignored 被忽略的规则描述，每条都带原因（选择器不认识 / 属性不支持 / 值不合法）。
 */
data class StyleParseResult(
    val overrides: StyleOverrides = StyleOverrides(),
    val applied: List<String> = emptyList(),
    val ignored: List<String> = emptyList(),
) {
    /** 是否一条都没生效。 */
    val hasNothingApplied: Boolean get() = applied.isEmpty() && ignored.isNotEmpty()
}
