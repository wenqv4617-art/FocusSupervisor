package com.focussupervisor.app.ui.settings

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.focussupervisor.app.core.style.StyleInjector
import com.focussupervisor.app.domain.model.BackgroundMode
import com.focussupervisor.app.domain.model.ChatAppearance
import com.focussupervisor.app.domain.model.ChatPresetIds
import com.focussupervisor.app.ui.components.ImageCropScreen
import com.focussupervisor.app.ui.theme.ChatSkin
import com.focussupervisor.app.ui.theme.FocusTheme
import com.focussupervisor.app.ui.theme.PastelTone
import com.focussupervisor.app.ui.theme.chatThemePresets
import com.focussupervisor.app.ui.theme.resolveChatSkin

/**
 * 聊天页美化面板。
 *
 * ===========================================================================
 * 这一页的三块，顺序是有讲究的
 * ===========================================================================
 * ```
 *   预览       ← 先让用户看到「现在长什么样」，后面所有操作的效果都在这块里
 *   主题       ← 影响最大的一步（换掉整套配色与结构），所以排第一
 *   背景       ← 在主题之上再加一张图，属于「再叠一层」
 *   样式注入   ← 最自由也最容易写错的一步，放最后
 * ```
 *
 * 预览放在最上面而不是底部：底部面板是可滚动的，如果预览在下面，
 * 用户改完主题之后得往下滚才能看到效果，那就等于没有预览。
 *
 * ===========================================================================
 * 关于「CSS 注入」这件事，界面上说清楚了
 * ===========================================================================
 * Compose 没有 CSS，所以这里实现的是一个**明确划定的子集**（选择器与属性清单
 * 见 `StyleInjector`）。界面上不假装它是 CSS：文案里写的是「样式注入」，
 * 下面会把「哪句生效了、哪句没认出来、为什么」逐条列出来 ——
 * 用户写错的时候不需要去猜。这是能给出的最好形态：
 * 不假装做不到的事做到了，但把做得到的那部分做到真的有用。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AppearanceViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val clipboard = LocalClipboardManager.current

    LaunchedEffect(Unit) { viewModel.onSheetOpened() }

    // 系统相册选图。用 GetContent 而不是 PickVisualMedia：后者在部分国产 ROM 上
    // 会被换成自己那套相册，返回的 Uri 权限模型不一致；GetContent 是所有版本上
    // 行为最统一的那条路，而且它天然不需要任何存储权限。
    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri != null) viewModel.onBackgroundPicked(uri)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
        sheetState = sheetState,
        containerColor = FocusTheme.colors.surfaceSheet,
        contentColor = FocusTheme.colors.textPrimary,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = SheetHorizontalPadding)
                .padding(bottom = SheetBottomPadding),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SheetTitle(text = "聊天页美化", modifier = Modifier.weight(1f))
                if (uiState.isCustomized) {
                    SheetPill(text = "已自定义", tone = PastelTone.LILAC)
                    Box(modifier = Modifier.width(8.dp))
                }
                TextButton(onClick = onDismiss) {
                    Text(
                        text = "关闭",
                        style = MaterialTheme.typography.bodySmall,
                        color = FocusTheme.colors.textSecondary,
                    )
                }
            }

            // ---- 预览 ----------------------------------------------------------
            ChatPreviewCard(appearance = uiState.draft)

            // ---- 主题 ----------------------------------------------------------
            SheetSection(
                title = "主题",
                subtitle = "主题决定的不只是颜色，还有圆角、头像与时间戳的位置。",
            ) {
                SheetChip(
                    text = "跟随应用明暗",
                    selected = uiState.draft.presetId == ChatPresetIds.FOLLOW_APP,
                    onClick = { viewModel.selectPreset(ChatPresetIds.FOLLOW_APP) },
                )
                chatThemePresets.forEach { preset ->
                    PresetRow(
                        name = preset.name,
                        description = preset.description,
                        skin = preset.skin,
                        selected = uiState.draft.presetId == preset.id,
                        onClick = { viewModel.selectPreset(preset.id) },
                    )
                }
            }

            // ---- 背景 ----------------------------------------------------------
            SheetSection(
                title = "聊天背景",
                subtitle = "主题自带底色之外，可以再铺一张自己的图或一个纯色。",
                tone = PastelTone.SKY,
            ) {
                SheetSegmented(
                    options = listOf("主题自带", "自定义图", "纯色"),
                    selectedIndex = when (uiState.draft.backgroundMode) {
                        BackgroundMode.PRESET -> 0
                        BackgroundMode.IMAGE -> 1
                        BackgroundMode.SOLID -> 2
                    },
                    onSelect = { index ->
                        viewModel.selectBackgroundMode(
                            when (index) {
                                0 -> BackgroundMode.PRESET
                                1 -> BackgroundMode.IMAGE
                                else -> BackgroundMode.SOLID
                            },
                        )
                    },
                )

                if (uiState.draft.backgroundMode == BackgroundMode.SOLID) {
                    SolidColorRow(onPick = viewModel::selectSolidColor)
                }

                if (uiState.hasBackgroundImage) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        BackgroundThumbnail(path = uiState.draft.backgroundImagePath)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "自定义背景图",
                                style = MaterialTheme.typography.bodySmall,
                                color = FocusTheme.colors.textPrimary,
                            )
                            Text(
                                text = "约 ${formatSize(uiState.backgroundSizeBytes)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = FocusTheme.colors.textSecondary,
                            )
                        }
                        TextButton(onClick = viewModel::clearBackground) {
                            Text(
                                text = "移除",
                                style = MaterialTheme.typography.bodySmall,
                                color = FocusTheme.colors.pastelBlushInk,
                            )
                        }
                    }

                    if (uiState.draft.backgroundMode == BackgroundMode.IMAGE) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = "透明度",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = FocusTheme.colors.textSecondary,
                                )
                                Text(
                                    text = "${(uiState.draft.backgroundImageOpacity * 100).toInt()}%",
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                    ),
                                    color = FocusTheme.colors.textSecondary,
                                )
                            }
                            Slider(
                                value = uiState.draft.backgroundImageOpacity,
                                onValueChange = viewModel::onOpacityDraftChange,
                                // 拖动中不落盘，松手才写一次。理由见 ViewModel 的类注释。
                                onValueChangeFinished = viewModel::onOpacityCommit,
                                valueRange = ChatAppearance.MIN_IMAGE_OPACITY..1f,
                                colors = SliderDefaults.colors(
                                    thumbColor = FocusTheme.colors.pastelSkyInk,
                                    activeTrackColor = FocusTheme.colors.pastelSkyInk,
                                ),
                            )
                        }
                    }
                }

                SheetSecondaryButton(
                    text = if (uiState.hasBackgroundImage) "换一张图" else "选一张图做背景",
                    enabled = !uiState.isBusy,
                    onClick = { imagePicker.launch("image/*") },
                )
            }

            // ---- 样式注入 ------------------------------------------------------
            SheetSection(
                title = "样式注入",
                subtitle = "一段类 CSS 的文本，可以精确覆盖上面那两块定不到的地方。",
                tone = PastelTone.LILAC,
            ) {
                SheetNote(
                    title = "先说清楚它不是浏览器里的 CSS",
                    text = "Compose 的界面是一棵强类型节点树，没有选择器匹配引擎，" +
                        "所以这里只认一份固定的选择器与属性清单。下面会把每条规则" +
                        "是否生效、以及没生效的原因逐条列出来。",
                    tone = PastelTone.SKY,
                )

                SheetTextField(
                    label = "样式文本",
                    value = uiState.draft.styleSheet,
                    onValueChange = viewModel::onStyleSheetChange,
                    placeholder = ".bubble-user { background: #0B84FF; }",
                    monospace = true,
                    singleLine = false,
                    minHeight = 150,
                    footer = "点下面的「复制模板」得到一份能直接改的完整示例。",
                )

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SheetSecondaryButton(
                        text = "复制模板",
                        enabled = true,
                        onClick = { clipboard.setText(AnnotatedString(StyleInjector.TEMPLATE)) },
                        modifier = Modifier.weight(1f),
                    )
                    SheetSecondaryButton(
                        text = "复制类名",
                        enabled = true,
                        onClick = { clipboard.setText(AnnotatedString(StyleInjector.SELECTOR_LIST)) },
                        modifier = Modifier.weight(1f),
                    )
                }

                SheetPrimaryButton(
                    text = "保存样式",
                    enabled = true,
                    onClick = viewModel::saveStyleSheet,
                )

                if (uiState.parse.applied.isNotEmpty()) {
                    ParseOutcomeList(
                        title = "已生效 ${uiState.parse.applied.size} 条",
                        items = uiState.parse.applied,
                        tone = PastelTone.MINT,
                    )
                }
                if (uiState.parse.ignored.isNotEmpty()) {
                    ParseOutcomeList(
                        title = "已忽略 ${uiState.parse.ignored.size} 条",
                        items = uiState.parse.ignored,
                        tone = PastelTone.BLUSH,
                    )
                }
            }

            SheetMessageLine(message = uiState.message, isError = uiState.isMessageError)

            SheetSecondaryButton(
                text = "恢复默认外观",
                enabled = true,
                onClick = viewModel::resetAll,
            )
        }
    }

    // ---- 全屏裁剪 ----------------------------------------------------------
    //
    // 用 Dialog 而不是把这个界面塞进 BottomSheet 里：裁剪需要尽可能大的可视面积，
    // 而 BottomSheet 的高度上限是屏幕的一部分。用 usePlatformDefaultWidth = false
    // 让它真正铺满整屏。
    val cropSource = uiState.cropSource
    if (cropSource != null) {
        Dialog(
            onDismissRequest = viewModel::onCropCancelled,
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            ImageCropScreen(
                source = cropSource,
                aspectRatio = CROP_ASPECT_RATIO,
                onCancel = viewModel::onCropCancelled,
                onConfirm = viewModel::onCropConfirmed,
            )
        }
    }
}

/**
 * 一张实时预览。
 *
 * 它渲染的是**真实的气泡组件所用的同一份皮肤**：直接读 `resolveChatSkin` 的结果，
 * 而不是在这里再写一套「预览用的颜色」。两套颜色的下场一定是漂移 ——
 * 用户看到的预览和真实聊天页不一样，那预览就是骗人的。
 */
@Composable
private fun ChatPreviewCard(appearance: ChatAppearance) {
    val colors = FocusTheme.colors
    val skin = remember(appearance, colors) { resolveChatSkin(appearance, colors) }

    val backgroundImage = remember(appearance.backgroundImagePath) {
        appearance.backgroundImagePath
            ?.let { BitmapFactory.decodeFile(it) }
            ?.asImageBitmap()
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(176.dp)
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, colors.cardBorder, RoundedCornerShape(16.dp))
            .background(skin.chatBackground),
    ) {
        when {
            appearance.backgroundMode == BackgroundMode.IMAGE && backgroundImage != null -> {
                Image(
                    bitmap = backgroundImage,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .alpha(appearance.backgroundImageOpacity),
                )
            }

            appearance.backgroundMode == BackgroundMode.SOLID -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(appearance.backgroundSolidColor ?: 0xFFEDEDEDL)),
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            PreviewBubble(
                text = "还有 12 分钟就到你定的时间了。",
                skin = skin,
                isFromUser = false,
            )
            PreviewBubble(
                text = "知道了，我把这个视频看完就停。",
                skin = skin,
                isFromUser = true,
            )
            Text(
                text = "预览 · 与真实聊天页用的是同一份皮肤",
                style = MaterialTheme.typography.labelSmall,
                color = skin.textSecondary,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
    }
}

/** 预览里的一条气泡。刻意不复用真实的气泡组件：那个带着长按回调和人设，过重。 */
@Composable
private fun PreviewBubble(text: String, skin: ChatSkin, isFromUser: Boolean) {
    val shape = RoundedCornerShape(
        topStart = if (isFromUser) skin.bubbleRadius.dp else skin.bubbleTailRadius.dp,
        topEnd = if (isFromUser) skin.bubbleTailRadius.dp else skin.bubbleRadius.dp,
        bottomStart = skin.bubbleRadius.dp,
        bottomEnd = skin.bubbleRadius.dp,
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isFromUser) Arrangement.End else Arrangement.Start,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(skin.bubbleMaxWidthFraction)
                .clip(shape)
                .background(if (isFromUser) skin.bubbleUser else skin.bubbleAi)
                .padding(horizontal = 11.dp, vertical = 8.dp),
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = if (isFromUser) skin.bubbleUserText else skin.bubbleAiText,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 一行主题：左边一小块配色缩略，右边名字与说明。 */
@Composable
private fun PresetRow(
    name: String,
    description: String,
    skin: ChatSkin,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = FocusTheme.colors
    val shape = RoundedCornerShape(14.dp)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) colors.pastelMint else colors.surfaceCardMuted)
            .clickable(onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 缩略图：底色 + 两个小块，一眼看出这套主题的配色走向。
        Box(
            modifier = Modifier
                .size(width = 46.dp, height = 34.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(skin.chatBackground)
                .border(1.dp, colors.cardBorder, RoundedCornerShape(9.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                Box(
                    modifier = Modifier
                        .size(width = 9.dp, height = 9.dp)
                        .clip(CircleShape)
                        .background(skin.bubbleAi),
                )
                Box(
                    modifier = Modifier
                        .size(width = 9.dp, height = 9.dp)
                        .clip(CircleShape)
                        .background(skin.bubbleUser),
                )
            }
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected) colors.pastelMintInk else colors.textPrimary,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.labelSmall,
                color = if (selected) {
                    colors.pastelMintInk.copy(alpha = 0.8f)
                } else {
                    colors.textSecondary
                },
            )
        }
    }
}

/** 背景图的小缩略图。 */
@Composable
private fun BackgroundThumbnail(path: String?) {
    val bitmap = remember(path) {
        path?.let { BitmapFactory.decodeFile(it) }?.asImageBitmap()
    }

    Box(
        modifier = Modifier
            .size(width = 52.dp, height = 40.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(FocusTheme.colors.surfaceCardMuted)
            .border(1.dp, FocusTheme.colors.cardBorder, RoundedCornerShape(10.dp)),
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** 纯色背景的候选色。四支淡彩 + 一个中性灰，正好都是面板里已经有的色。 */
@Composable
private fun SolidColorRow(onPick: (Long) -> Unit) {
    val colors = FocusTheme.colors
    val options = remember(colors) {
        listOf(
            0xFFEDEDEDL to colors.chatBackground,
            0xFFD8F3EAL to colors.pastelMint,
            0xFFDCEAFBL to colors.pastelSky,
            0xFFE8E2F9L to colors.pastelLilac,
            0xFFFBE1E7L to colors.pastelBlush,
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        options.forEach { (value, display) ->
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(display)
                    .border(1.dp, colors.cardBorder, RoundedCornerShape(10.dp))
                    .clickable { onPick(value) },
            )
        }
    }
}

/** 解析结果的清单。生效的走薄荷、被忽略的走藕粉并带上原因。 */
@Composable
private fun ParseOutcomeList(
    title: String,
    items: List<String>,
    tone: PastelTone,
) {
    val colors = FocusTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(tone.surface(colors))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = tone.ink(colors),
        )
        items.forEach { item ->
            Text(
                text = "· $item",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                ),
                color = tone.ink(colors).copy(alpha = 0.85f),
            )
        }
    }
}

/** 字节数转成人话。 */
private fun formatSize(bytes: Long): String = when {
    bytes <= 0L -> "未知"
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
}

/**
 * 裁剪框的宽高比。
 *
 * 取 0.62（约 9:14.5），比多数手机的屏幕略窄一点。宁愿比屏幕窄也不要宽：
 * 窄的图铺在聊天页上只会被裁掉左右两边，而宽的图会被裁掉上下 ——
 * 用户精心选的主体通常在画面中间偏上的位置，裁上下更容易毁掉构图。
 */
private const val CROP_ASPECT_RATIO = 0.62f
