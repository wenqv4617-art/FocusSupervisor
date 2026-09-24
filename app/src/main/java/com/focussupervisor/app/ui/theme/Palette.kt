package com.focussupervisor.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * 面板层的淡彩色板。
 *
 * ===========================================================================
 * 为什么聊天页和面板用两套颜色
 * ===========================================================================
 * 这两块地方的职责完全不同，硬用一套颜色只会两边都别扭：
 *
 *  - **聊天页**是「别人的东西」——它模仿的是微信/iMessage，颜色要服从被模仿对象的
 *    真实观感，用户才信那是聊天界面；
 *  - **面板**是「我们自己的东西」——它是一堆配置表单，用户在这里需要快速分辨
 *    「这是一组什么设置」「哪一块是可以点的」「哪里是危险操作」。它需要的是
 *    **结构感**，不是模仿。
 *
 * 所以面板层单独有一套：淡彩底色分层 + 卡片 + 一色一义的语义浅底。
 *
 * ===========================================================================
 * 取舍：为什么是青/天青/藕荷/藕粉这四支
 * ===========================================================================
 * 需求给了三条排除：不要莫兰迪（灰调）、不要黄系、不要深蓝紫的科技感。
 * 剩下能用的就是**高明度、低饱和、但色相明确**的淡彩：
 *
 * | 用途 | 色相 | 为什么 |
 * |------|------|--------|
 * | 薄荷 | 青绿 | 承接主强调色，是「正常 / 已完成」 |
 * | 天青 | 淡蓝 | 中性信息，是「说明 / 提示」 |
 * | 藕荷 | 淡紫 | 需要区分但不刺眼，是「可选 / 自定义」 |
 * | 藕粉 | 淡粉 | 唯一带情绪的一支，只给「危险 / 删除」 |
 *
 * 每支都配一个 `Ink` 深色版：淡彩做底、深色做字，对比度才够 —— 淡彩底上写
 * 中灰字是这类设计最常见的翻车点（好看但看不清）。
 */

// ---------------------------------------------------------------------------
// 淡彩底 + 对应墨色
// ---------------------------------------------------------------------------

/** 薄荷：正常、已完成、本地可用。 */
val PastelMint = Color(0xFFD8F3EA)
val PastelMintInk = Color(0xFF1F7A63)

/** 天青：说明、提示、中性信息。 */
val PastelSky = Color(0xFFDCEAFB)
val PastelSkyInk = Color(0xFF2C5E93)

/** 藕荷：可选、自定义、需要跟别的东西区分开。 */
val PastelLilac = Color(0xFFE8E2F9)
val PastelLilacInk = Color(0xFF5B4B96)

/** 藕粉：危险、删除、不可逆。全应用只有这一支带情绪的颜色。 */
val PastelBlush = Color(0xFFFBE1E7)
val PastelBlushInk = Color(0xFF9C4257)

// ---------------------------------------------------------------------------
// 面板与卡片
// ---------------------------------------------------------------------------

/**
 * 面板底：极淡的冷白。
 *
 * 不用纯白。纯白面板 + 纯白卡片 = 卡片边界只能靠描边，整个面板会读成一张
 * 摊平的纸；面板底压暗一档，卡片浮上去，层次不用画线就出来了。
 */
val SheetSurface = Color(0xFFF3F6F9)

/** 卡片底：纯白。面板里所有「一组设置」都装在这里面。 */
val CardSurface = Color(0xFFFFFFFF)

/** 卡片内的次级块。比卡片底略暗，用于卡片内部再分层（例如统计行、预览区）。 */
val CardSurfaceMuted = Color(0xFFF1F5F8)

/** 卡片描边。极浅，只负责在卡片压到深色底时兜住边界。 */
val CardBorder = Color(0xFFE2E9EF)

/**
 * 磨砂层色。
 *
 * 带 alpha 的白：叠在内容之上做「毛玻璃」。真正的模糊由
 * [com.focussupervisor.app.ui.components.frosted] 在 API 31+ 上用 RenderEffect
 * 提供，低版本退化成这一层半透明 —— 两种情况下这层底色都是必需的，
 * 不然低版本会变成「内容直接透出来」，比不磨砂还难看。
 */
val FrostTint = Color(0xE6F7FAFC)

/** 磨砂层在低版本（无 RenderEffect）时用的加深版。 */
val FrostTintFallback = Color(0xF2F3F6F9)

// ---------------------------------------------------------------------------
// 深色
// ---------------------------------------------------------------------------

val DarkSheetSurface = Color(0xFF15181B)
val DarkCardSurface = Color(0xFF1E2226)
val DarkCardSurfaceMuted = Color(0xFF252A2F)
val DarkCardBorder = Color(0xFF2E353B)
val DarkFrostTint = Color(0xE61A1E23)
val DarkPastelMint = Color(0xFF1B3830)
val DarkPastelMintInk = Color(0xFF7FD9BF)
val DarkPastelSky = Color(0xFF1B2A3B)
val DarkPastelSkyInk = Color(0xFF8FBEEB)
val DarkPastelLilac = Color(0xFF272140)
val DarkPastelLilacInk = Color(0xFFB7A8EA)
val DarkPastelBlush = Color(0xFF3A2229)
val DarkPastelBlushInk = Color(0xFFEBA3B4)
