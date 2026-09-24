package com.focussupervisor.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * 主题层。
 *
 * 为什么要在 MaterialTheme 之外再挂一层自定义色板：
 * Material 的 ColorScheme 是一套「组件语义色」（primary/onSurface/...），
 * 而聊天气泡、系统胶囊这类颜色并不属于任何 Material 组件的语义，硬塞进
 * ColorScheme 的某个槽位只会让后人看不懂。因此这里用 CompositionLocal 扩展出
 * 一组本项目专属的语义色 [FocusColors]，与 MaterialTheme 并存、互不干扰。
 *
 * 用法：
 * ```
 * Text(color = FocusTheme.colors.textSecondary)
 * ```
 */

/**
 * 本应用专属语义色。
 *
 * 标 [Immutable] 是给 Compose 编译器的承诺：这个类的实例不会被就地修改，
 * 于是所有读取它的 Composable 都能被正确跳过（skippable），不会因为「可能变了」
 * 而每次重组都重画。
 */
@Immutable
data class FocusColors(
    /** 聊天区背景。 */
    val chatBackground: Color,
    /** 顶栏 / 输入栏 / 面板背景。 */
    val chromeBackground: Color,
    /** 发丝分割线。 */
    val hairline: Color,
    /** 用户气泡底色。 */
    val bubbleUser: Color,
    /** AI 气泡底色。 */
    val bubbleAi: Color,
    /** 输入框底色。 */
    val inputField: Color,
    /** 系统消息胶囊底色。 */
    val systemPill: Color,
    /** 系统消息胶囊文字色。 */
    val systemPillText: Color,
    /** 正文主色。 */
    val textPrimary: Color,
    /** 次级文字色。 */
    val textSecondary: Color,
    /** 图标默认色。 */
    val iconTint: Color,
    /** 强调色（可用的发送按钮）。 */
    val accent: Color,
    /** 强调色禁用态。 */
    val accentDisabled: Color,
    /** 提醒色：面板红点、逾期待办。 */
    val attention: Color,

    // -----------------------------------------------------------------------
    // 面板层
    //
    // 见 Palette.kt 的类注释：面板不模仿任何现成应用，它要的是结构感，
    // 所以单独有一组淡彩 + 卡片语义色。
    //
    // 这几个字段都带默认值：新增字段时不必回头改每一处构造点，默认值就是浅色版。
    // 但 Light/Dark 两个实例仍然显式写了值 —— 默认值只是编译期的兜底，
    // 不承担实际配色。
    // -----------------------------------------------------------------------

    /** 面板底。 */
    val surfaceSheet: Color = SheetSurface,
    /** 卡片底。 */
    val surfaceCard: Color = CardSurface,
    /** 卡片内的次级块。 */
    val surfaceCardMuted: Color = CardSurfaceMuted,
    /** 卡片描边。 */
    val cardBorder: Color = CardBorder,
    /** 磨砂层。 */
    val frostTint: Color = FrostTint,
    /** 薄荷：正常 / 已完成 / 本地可用。 */
    val pastelMint: Color = PastelMint,
    val pastelMintInk: Color = PastelMintInk,
    /** 天青：说明 / 中性信息。 */
    val pastelSky: Color = PastelSky,
    val pastelSkyInk: Color = PastelSkyInk,
    /** 藕荷：可选 / 自定义。 */
    val pastelLilac: Color = PastelLilac,
    val pastelLilacInk: Color = PastelLilacInk,
    /** 藕粉：危险 / 删除。 */
    val pastelBlush: Color = PastelBlush,
    val pastelBlushInk: Color = PastelBlushInk,
)

/**
 * 淡彩语义色的成套取用入口。
 *
 * 面板里到处是「一块淡彩底 + 同色系深字」的小标签，写成两个字段容易配错对。
 * 打包成一个枚举取，配色就只有一处定义。
 */
enum class PastelTone {
    /** 薄荷：正常、已完成。 */
    MINT,

    /** 天青：说明、提示。 */
    SKY,

    /** 藕荷：可选、自定义。 */
    LILAC,

    /** 藕粉：危险、删除。 */
    BLUSH,
    ;

    /** 取这一支的淡彩底。 */
    fun surface(colors: FocusColors): Color = when (this) {
        MINT -> colors.pastelMint
        SKY -> colors.pastelSky
        LILAC -> colors.pastelLilac
        BLUSH -> colors.pastelBlush
    }

    /** 取这一支的墨色（写在淡彩底上的文字色）。 */
    fun ink(colors: FocusColors): Color = when (this) {
        MINT -> colors.pastelMintInk
        SKY -> colors.pastelSkyInk
        LILAC -> colors.pastelLilacInk
        BLUSH -> colors.pastelBlushInk
    }
}

/**
 * 浅色实例（默认值，同时也是 Preview 的兜底值）。
 *
 * `internal` 而不是 `private`：`ChatSkin.kt` 里的 `LocalChatSkin` 需要一个默认值，
 * 而那行代码在文件之外。默认值必须有东西可指，让它在模块内可见是最直接的做法。
 */
internal val LightFocusColors = FocusColors(
    chatBackground = WeChatBackground,
    chromeBackground = ChromeBackground,
    hairline = Hairline,
    bubbleUser = BubbleUser,
    bubbleAi = BubbleAi,
    inputField = InputField,
    systemPill = SystemPillBackground,
    systemPillText = SystemPillText,
    textPrimary = TextPrimary,
    textSecondary = TextSecondary,
    iconTint = IconTint,
    accent = Accent,
    accentDisabled = AccentDisabled,
    attention = Attention,
    surfaceSheet = SheetSurface,
    surfaceCard = CardSurface,
    surfaceCardMuted = CardSurfaceMuted,
    cardBorder = CardBorder,
    frostTint = FrostTint,
    pastelMint = PastelMint,
    pastelMintInk = PastelMintInk,
    pastelSky = PastelSky,
    pastelSkyInk = PastelSkyInk,
    pastelLilac = PastelLilac,
    pastelLilacInk = PastelLilacInk,
    pastelBlush = PastelBlush,
    pastelBlushInk = PastelBlushInk,
)

/** 深色实例。 */
private val DarkFocusColors = FocusColors(
    chatBackground = DarkBackground,
    chromeBackground = DarkChrome,
    hairline = DarkHairline,
    bubbleUser = DarkBubbleUser,
    bubbleAi = DarkBubbleAi,
    inputField = DarkInputField,
    systemPill = DarkSystemPill,
    systemPillText = DarkSystemPillText,
    textPrimary = DarkTextPrimary,
    textSecondary = DarkTextSecondary,
    iconTint = DarkIconTint,
    accent = Accent,
    accentDisabled = AccentDisabled,
    attention = DarkAttention,
    surfaceSheet = DarkSheetSurface,
    surfaceCard = DarkCardSurface,
    surfaceCardMuted = DarkCardSurfaceMuted,
    cardBorder = DarkCardBorder,
    frostTint = DarkFrostTint,
    pastelMint = DarkPastelMint,
    pastelMintInk = DarkPastelMintInk,
    pastelSky = DarkPastelSky,
    pastelSkyInk = DarkPastelSkyInk,
    pastelLilac = DarkPastelLilac,
    pastelLilacInk = DarkPastelLilacInk,
    pastelBlush = DarkPastelBlush,
    pastelBlushInk = DarkPastelBlushInk,
)

/**
 * 自定义色板的 CompositionLocal。
 *
 * 用 staticCompositionLocalOf 而不是 compositionLocalOf：色板整体替换（明暗切换）
 * 是低频事件，用 static 版本可以让读取方在值不变时被完全跳过，性价比更高。
 * 代价是切换主题会重组整棵子树 —— 但这正是我们想要的。
 */
val LocalFocusColors = staticCompositionLocalOf { LightFocusColors }

/**
 * 取本应用语义色的入口。
 *
 * 用 `@ReadOnlyComposable` 标记：它只读不写，编译器可以省掉一层快照订阅开销。
 */
object FocusTheme {
    val colors: FocusColors
        @Composable
        @ReadOnlyComposable
        get() = LocalFocusColors.current
}

/** 与自定义色板配套的 Material 3 ColorScheme。 */
private val LightMaterialScheme = lightColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    background = WeChatBackground,
    onBackground = TextPrimary,
    surface = ChromeBackground,
    onSurface = TextPrimary,
    surfaceVariant = InputField,
    onSurfaceVariant = TextSecondary,
    outline = Hairline,
)

private val DarkMaterialScheme = darkColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    background = DarkBackground,
    onBackground = DarkTextPrimary,
    surface = DarkChrome,
    onSurface = DarkTextPrimary,
    surfaceVariant = DarkInputField,
    onSurfaceVariant = DarkTextSecondary,
    outline = DarkHairline,
)

/**
 * 应用主题根节点。
 *
 * 刻意不启用 Material You 动态取色：本应用的绿色是有含义的（监督 / 克制），
 * 跟随用户壁纸变色会让「同一款应用在不同手机上长得不一样」，也会破坏
 * 气泡与系统胶囊之间那点微妙的明度关系。
 *
 * @param darkTheme 是否使用深色。默认跟随系统。
 */
@Composable
fun FocusSupervisorTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val focusColors = if (darkTheme) DarkFocusColors else LightFocusColors
    val materialScheme = if (darkTheme) DarkMaterialScheme else LightMaterialScheme

    CompositionLocalProvider(LocalFocusColors provides focusColors) {
        MaterialTheme(
            colorScheme = materialScheme,
            typography = FocusTypography,
            content = content,
        )
    }
}
