package com.focussupervisor.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 字体规格。
 *
 * 只用系统默认字体（[FontFamily.Default] = 设备的中文/英文默认字族），不打包
 * 自定义字体：一来避免 APK 里塞进几 MB 的中文字体，二来中文字体在 Android 上
 * 各厂商差异很大，跟随系统反而观感最稳。
 *
 * 字号刻度刻意比 Material 默认更紧：聊天界面是信息密集场景，正文 16sp、
 * 次级信息 12sp 是最舒服的一档。
 */
val FocusTypography = Typography(
    // 顶栏主标题（AI 监管者名称）
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 17.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.sp,
    ),
    // 顶栏副标题（连接状态）
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.2.sp,
    ),
    // 聊天气泡正文
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 23.sp,
        letterSpacing = 0.sp,
    ),
    // 系统胶囊文字 / 时间戳
    bodySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.sp,
    ),
    // 输入框文字与提示
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp,
    ),
    // 「+」面板图标下方的功能名
    labelMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.sp,
    ),
)
