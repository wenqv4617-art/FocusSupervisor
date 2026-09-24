package com.focussupervisor.app.core.style

import com.focussupervisor.app.domain.model.StyleOverrides
import com.focussupervisor.app.domain.model.StyleParseResult

/**
 * 样式注入：把一小段「类 CSS」文本翻译成一组可覆盖的皮肤属性。
 *
 * ===========================================================================
 * 先把话说清楚：这不是 CSS，Compose 也没有 CSS
 * ===========================================================================
 * Android 的 Jetpack Compose 是一棵**强类型**的节点树，没有浏览器那种
 * 「一份样式表 + 一棵文档树 + 选择器匹配引擎」的架构。想在 Compose 上做真正的
 * CSS 注入，等于要在应用里重新实现一个 CSS 引擎 —— 那不是这个功能该背的成本。
 *
 * 所以这里实现的是一个**明确划定的子集**：
 *
 * ```
 * .bubble-user {
 *   background: #0B84FF;
 *   color: #FFFFFF;
 * }
 * ```
 *
 * 认得的选择器只有下面这些（每一项直接对应皮肤里的一个槽位，不做层级匹配、
 * 不支持后代选择器、不支持伪类）：
 *
 * | 选择器 | 作用 |
 * |--------|------|
 * | `.chat` | 聊天区背景 |
 * | `.bubble` | 两侧气泡（写在 `.bubble-user` 之前时会被后者覆盖） |
 * | `.bubble-user` / `.bubble-user-text` | 我发的气泡 / 其中的文字 |
 * | `.bubble-ai` / `.bubble-ai-text` | 对方气泡 / 其中的文字 |
 * | `.system-pill` / `.system-pill-text` | 系统胶囊 / 其中的文字 |
 * | `.text-primary` / `.text-secondary` | 正文色 / 次级文字色 |
 * | `.chrome` | 顶栏与输入栏底色 |
 * | `.hairline` | 分割线颜色 |
 * | `.input` | 输入框底色 |
 * | `.accent` | 强调色（发送按钮、光标） |
 * | `.avatar` | 头像（只能调 `display` 与 `border-radius`） |
 * | `.timestamp` | 时间戳（只能调 `display`） |
 *
 * 认得的属性：`background` / `background-color` / `color` / `border-radius` /
 * `max-width` / `opacity` / `display`。
 *
 * ===========================================================================
 * 为什么全部手写扫描，一个正则都不用
 * ===========================================================================
 * 本项目已经在正则上栽过一次：Android 的正则引擎是 ICU，它把 `\[\[cmd:(\{.*?})]]`
 * 里那个**没有量词开头的 `}`** 判成语法错误，而同样的写法在 JVM 上完全合法 ——
 * 结果是「本机编译通过、真机一启动就崩」。CSS 的解析天然满是花括号，
 * 继续用正则就是再赌一次同一种运气。
 *
 * `indexOf('{')` / `indexOf('}')` 是纯字符串查找，两个平台行为完全一致。
 */
object StyleInjector {

    /** 认得的图片/纯色选择器到覆盖槽位的映射。 */
    private val COLOR_SELECTORS = mapOf(
        ".chat" to "chatBackground",
        ".bubble-user" to "bubbleUser",
        ".bubble-user-text" to "bubbleUserText",
        ".bubble-ai" to "bubbleAi",
        ".bubble-ai-text" to "bubbleAiText",
        ".system-pill" to "systemPill",
        ".system-pill-text" to "systemPillText",
        ".text-primary" to "textPrimary",
        ".text-secondary" to "textSecondary",
        ".chrome" to "chrome",
        ".hairline" to "hairline",
        ".input" to "inputField",
        ".accent" to "accent",
    )

    /** 一次注入最多解析多少条规则。防止有人粘进来一整份框架的 CSS 把主线程占住。 */
    private const val MAX_RULES = 60

    /**
     * 解析入口。
     *
     * **永不抛异常**。用户输入的文本不该有能力弄崩界面，认不出来的一律进
     * [StyleParseResult.ignored] 让界面显示出来。
     */
    fun parse(text: String): StyleParseResult {
        if (text.isBlank()) return StyleParseResult()

        val applied = mutableListOf<String>()
        val ignored = mutableListOf<String>()
        var overrides = StyleOverrides()

        // 先剥掉注释，免得 `/* .bubble-user { } */` 被当成真规则。
        val source = stripComments(text)

        var cursor = 0
        var ruleCount = 0
        while (cursor < source.length && ruleCount < MAX_RULES) {
            val open = source.indexOf('{', cursor)
            if (open < 0) break
            val close = source.indexOf('}', open + 1)
            if (close < 0) {
                ignored += "第 ${ruleCount + 1} 段缺少右花括号 }，已忽略"
                break
            }

            val selector = source.substring(cursor, open).trim()
            val body = source.substring(open + 1, close)
            cursor = close + 1

            // 选择器可以写成 `.a, .b`，逗号分隔逐个处理。
            // 一段规则里只要有一个选择器认出来了就算「生效」，其余进忽略列表。
            val selectors = selector.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            if (selectors.isEmpty()) continue
            ruleCount++

            for (one in selectors) {
                val outcome = applyRule(one, body, overrides)
                overrides = outcome.overrides
                applied += outcome.applied
                ignored += outcome.ignored
            }
        }

        if (ruleCount >= MAX_RULES) {
            ignored += "规则数超过 $MAX_RULES 条，后面的已忽略"
        }

        return StyleParseResult(
            overrides = overrides,
            applied = applied,
            ignored = ignored,
        )
    }

    /** 一段规则的作用结果。 */
    private class RuleOutcome(
        val overrides: StyleOverrides,
        val applied: List<String>,
        val ignored: List<String>,
    )

    /**
     * 把一段规则套到当前覆盖项上。
     *
     * `overrides` 用**参数传入再返回**，而不是用 var 捕获：解析是从上往下顺序执行的，
     * 后面的规则要覆盖前面的（CSS 的就近原则），必须显式传递当前状态。
     */
    private fun applyRule(
        selector: String,
        body: String,
        current: StyleOverrides,
    ): RuleOutcome {
        val applied = mutableListOf<String>()
        val ignored = mutableListOf<String>()
        var result = current

        for (declaration in body.split(';')) {
            val colon = declaration.indexOf(':')
            if (colon <= 0) continue
            val property = declaration.substring(0, colon).trim().lowercase()
            val rawValue = declaration.substring(colon + 1).trim()
            if (property.isEmpty() || rawValue.isEmpty()) continue

            val outcome = applyDeclaration(selector, property, rawValue, result)
            result = outcome.first
            if (outcome.second) {
                applied += "$selector 的 $property"
            } else {
                ignored += "$selector 的 $property: $rawValue —— ${outcome.third}"
            }
        }

        return RuleOutcome(result, applied, ignored)
    }

    /**
     * 套用一条声明。
     *
     * @return 三元组：新的覆盖项、是否生效、没生效的原因。
     */
    private fun applyDeclaration(
        selector: String,
        property: String,
        rawValue: String,
        current: StyleOverrides,
    ): Triple<StyleOverrides, Boolean, String> {
        val fail = { reason: String -> Triple(current, false, reason) }

        // ---- display：只对三类「可以没有」的元素有意义 ------------------------
        if (property == "display") {
            val visible = when (rawValue.lowercase()) {
                "none" -> false
                "block", "flex", "inline", "inline-block" -> true
                else -> return fail("display 只认 none 或 block")
            }
            return when (selector) {
                ".avatar" -> Triple(current.copy(showAvatar = visible), true, "")
                ".sender-name" -> Triple(current.copy(showSenderName = visible), true, "")
                ".timestamp" -> Triple(current.copy(centerTimestamp = visible), true, "")
                else -> fail("只有 .avatar / .sender-name / .timestamp 能调 display")
            }
        }

        // ---- border-radius ---------------------------------------------------
        if (property == "border-radius") {
            val dp = parseNumber(rawValue) ?: return fail("圆角要写成数字，例如 14 或 14px")
            val rounded = dp.toInt().coerceIn(0, 40)
            return when (selector) {
                // `.bubble` 是两侧一起设：常规角与「指向发送方的那个角」一起改，
                // 否则会出现「大圆角配一个 4dp 的尖角」这种很怪的组合。
                ".bubble" -> Triple(
                    current.copy(bubbleRadius = rounded, bubbleTailRadius = rounded),
                    true,
                    "",
                )

                ".bubble-user", ".bubble-ai" ->
                    Triple(current.copy(bubbleRadius = rounded), true, "")

                ".avatar" -> Triple(current.copy(roundAvatar = rounded >= 12), true, "")
                else -> fail("只有气泡和头像有圆角")
            }
        }

        // ---- max-width -------------------------------------------------------
        if (property == "max-width") {
            if (selector != ".bubble" && selector != ".bubble-user" && selector != ".bubble-ai") {
                return fail("只有气泡能调 max-width")
            }
            val percent = parsePercent(rawValue) ?: return fail("max-width 要写成百分比，例如 72%")
            return Triple(current.copy(bubbleMaxWidthPercent = percent.coerceIn(30, 95)), true, "")
        }

        // ---- opacity ---------------------------------------------------------
        //
        // 背景图透明度**故意不在这里支持**。它是外观状态 `ChatAppearance` 的一部分，
        // 由界面上那个滑块管；同一件事有两个入口（滑块 + CSS）必然打架，
        // 而且用户从 CSS 里改了它，滑块的显示就骗人了。
        // 与其偷偷忽略，不如明确告诉他去哪儿调。
        if (property == "opacity") {
            return fail("背景图透明度请用上方的滑块调整，样式里不用写 opacity")
        }

        // ---- color / background ---------------------------------------------
        val isBackground = property == "background" || property == "background-color"
        val isForeground = property == "color"
        if (!isBackground && !isForeground) {
            return fail("不认识的属性（只认 background / color / border-radius / max-width / display）")
        }

        val color = parseColor(rawValue) ?: return fail("颜色要写成 #RRGGBB 或 #AARRGGBB")

        // `.bubble` 是「两侧一起设」的简写。
        if (selector == ".bubble") {
            return if (isBackground) {
                Triple(current.copy(bubbleUser = color, bubbleAi = color), true, "")
            } else {
                Triple(current.copy(bubbleUserText = color, bubbleAiText = color), true, "")
            }
        }

        val slot = COLOR_SELECTORS[selector] ?: return fail("不认识的选择器")

        // 每个槽位能接受哪种写法是固定的：气泡底是背景、气泡文字是前景。
        // 用一个集合判定而不是靠后缀猜名字 —— 名字早晚会被改，集合是显式的契约。
        val acceptsBackground = slot in BACKGROUND_SLOTS
        val acceptsForeground = slot in FOREGROUND_SLOTS
        if (isBackground && !acceptsBackground) {
            return fail("这一项是文字色，请用 color 而不是 background")
        }
        if (isForeground && !acceptsForeground) {
            return fail("这一项是底色，请用 background 而不是 color")
        }

        return Triple(setColor(current, slot, color), true, "")
    }

    /** 只能（且必须）用 `background` 设置的槽位。 */
    private val BACKGROUND_SLOTS = setOf(
        "bubbleUser",
        "bubbleAi",
        "chatBackground",
        "systemPill",
        "chrome",
        "hairline",
        "inputField",
    )

    /**
     * 只能（且必须）用 `color` 设置的槽位。
     *
     * `accent` 不在任何一边：它既可以当底色（选中态小圆点）也可以当色值
     * （发送图标、光标），两边都收。
     */
    private val FOREGROUND_SLOTS = setOf(
        "bubbleUserText",
        "bubbleAiText",
        "systemPillText",
        "textPrimary",
        "textSecondary",
    )

    /**
     * 往具体槽位写色。
     *
     * 写成 when 而不是反射：字段是编译期已知的，反射在 Android 上有额外的
     * 隐藏 API 限制与性能开销，为十几行代码不值得。
     */
    private fun setColor(current: StyleOverrides, slot: String, color: Long): StyleOverrides =
        when (slot) {
            "chatBackground" -> current.copy(chatBackground = color)
            "bubbleUser" -> current.copy(bubbleUser = color)
            "bubbleUserText" -> current.copy(bubbleUserText = color)
            "bubbleAi" -> current.copy(bubbleAi = color)
            "bubbleAiText" -> current.copy(bubbleAiText = color)
            "systemPill" -> current.copy(systemPill = color)
            "systemPillText" -> current.copy(systemPillText = color)
            "textPrimary" -> current.copy(textPrimary = color)
            "textSecondary" -> current.copy(textSecondary = color)
            "chrome" -> current.copy(chrome = color)
            "hairline" -> current.copy(hairline = color)
            "inputField" -> current.copy(inputField = color)
            "accent" -> current.copy(accent = color)
            else -> current
        }

    /** 剥掉 `/* … *\/` 注释。手写扫描，理由见类注释。 */
    private fun stripComments(text: String): String {
        if (!text.contains("/*")) return text
        val builder = StringBuilder(text.length)
        var index = 0
        while (index < text.length) {
            val start = text.indexOf("/*", index)
            if (start < 0) {
                builder.append(text, index, text.length)
                break
            }
            builder.append(text, index, start)
            val end = text.indexOf("*/", start + 2)
            if (end < 0) break
            index = end + 2
        }
        return builder.toString()
    }

    /**
     * 解析颜色。
     *
     * 支持 `#RGB` / `#RRGGBB` / `#AARRGGBB` 与 `transparent`。
     * 刻意**不支持** `rgb()` / `hsl()` / 具名颜色：写得越长，认错的方式就越多，
     * 而真正会手写这段样式的用户，十六进制是他最熟的写法。
     */
    fun parseColor(raw: String): Long? {
        val value = raw.trim().lowercase()
        if (value == "transparent") return 0x00000000L
        if (!value.startsWith("#")) return null

        val hex = value.substring(1)
        return when (hex.length) {
            3 -> {
                val r = hex[0].digit() ?: return null
                val g = hex[1].digit() ?: return null
                val b = hex[2].digit() ?: return null
                // #ABC -> #AABBCC
                val rr = r * 16 + r
                val gg = g * 16 + g
                val bb = b * 16 + b
                0xFF000000L or (rr.toLong() shl 16) or (gg.toLong() shl 8) or bb.toLong()
            }

            6 -> hex.toLongOrNull(16)?.let { 0xFF000000L or it }
            8 -> hex.toLongOrNull(16)
            else -> null
        }
    }

    /** 单个十六进制字符转数值。手写而不是 `Character.digit`，省一次装箱。 */
    private fun Char.digit(): Int? = when (this) {
        in '0'..'9' -> this - '0'
        in 'a'..'f' -> this - 'a' + 10
        in 'A'..'F' -> this - 'A' + 10
        else -> null
    }

    /** 解析 `14` / `14px` / `14dp` 这类数字。 */
    private fun parseNumber(raw: String): Float? {
        val cleaned = raw.trim()
            .removeSuffix("px")
            .removeSuffix("dp")
            .removeSuffix("sp")
            .trim()
        return cleaned.toFloatOrNull()
    }

    /** 解析 `72%`。没写百分号时按「已经是百分数」处理。 */
    private fun parsePercent(raw: String): Int? {
        val cleaned = raw.trim()
        return if (cleaned.endsWith("%")) {
            cleaned.dropLast(1).trim().toFloatOrNull()?.toInt()
        } else {
            cleaned.toFloatOrNull()?.toInt()
        }
    }

    /**
     * 界面上「一键复制」用的类名清单。
     *
     * 直接给一份**能跑的模板**，而不是一串干巴巴的类名：用户点一下复制、粘进去、
     * 改几个色值就能用，比让他自己对着一列类名猜语法友好得多。
     */
    val TEMPLATE: String = """
/* 聊天页样式注入 · 认得的选择器见下方清单
   改完点「保存样式」，不生效的规则会在下面逐条告诉你原因 */

.chat            { background: #EDEDED; }   /* 聊天区背景 */
.bubble-user     { background: #95EC69; color: #191919; }
.bubble-ai       { background: #FFFFFF; color: #191919; }
.system-pill     { background: #D6D6D6; color: #6E6E6E; }
.text-primary    { color: #191919; }
.text-secondary  { color: #9A9A9A; }
.chrome          { background: #F7F7F7; }
.hairline        { background: #DCDCDC; }
.input           { background: #FFFFFF; }
.accent          { color: #07C160; }

/* 形状与结构 */
.bubble          { border-radius: 14px; max-width: 72%; }
.avatar          { display: none; }        /* 藏掉头像 */
.sender-name     { display: none; }        /* 藏掉姓名 */
.timestamp       { display: none; }        /* 藏掉时间戳 */
""".trimIndent()

    /**
     * 可复制粘贴的类名全集（纯清单，给想自己从零写的人用）。
     */
    const val SELECTOR_LIST: String =
        ".chat .bubble .bubble-user .bubble-user-text .bubble-ai .bubble-ai-text " +
            ".system-pill .system-pill-text .text-primary .text-secondary " +
            ".chrome .hairline .input .accent .avatar .sender-name .timestamp"
}
