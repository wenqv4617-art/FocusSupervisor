package com.focussupervisor.app.ui.components

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Rect
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.focussupervisor.app.ui.theme.FocusTheme
import com.focussupervisor.app.ui.theme.OverlayBackground
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 背景图裁剪。
 *
 * ===========================================================================
 * 一、为什么是自己画而不是用 uCrop 之类的库
 * ===========================================================================
 * 这里的需求窄得不能再窄：**一张图、一个固定比例的框、拖动与缩放、出一张位图**。
 * 引一个通用裁剪库会带来它的主题、它的权限处理、它的多图/多比例逻辑，
 * 而我们要做的只是把用户选的那块区域抠出来。一个 300 行的自绘实现，
 * 换掉的是几千行不归我们管的代码。
 *
 * ===========================================================================
 * 二、坐标系：只有两个变量
 * ===========================================================================
 * ```
 *   scale   源图的一个像素，在裁剪框里占多少像素（含用户缩放）
 *   offset  源图左上角在裁剪框坐标系里的位置（x、y，单位是框内像素）
 * ```
 *
 * 画面里的一切都由这两个量推出来，手势也只是在改这两个量 ——
 * 没有矩阵求逆、没有「视图坐标系」和「图片坐标系」的来回换算。
 * 输出时再用一次反变换就得到源图上的矩形：
 *
 * ```
 *   源图 x = -offsetX / scale
 * ```
 *
 * 之所以能这么简单，是因为**裁剪框是固定的、动的是图**。反过来（框能拖、
 * 图不动）才需要引入矩阵。
 *
 * ===========================================================================
 * 三、最小缩放是「铺满」
 * ===========================================================================
 * [minScale] 取 `max(框宽/图宽, 框高/图高)`，也就是 cover。不允许缩得比这更小，
 * 否则框里会出现空白 —— 而背景图是铺满整个聊天区的，留白没有任何意义。
 * 上限是 cover 的 4 倍，再多就是在放大马赛克了。
 *
 * @param source 已降采样的源图。
 * @param aspectRatio 裁剪框的宽高比（宽/高）。
 * @param onCancel 取消。
 * @param onConfirm 确认，回调里给出裁剪结果。结果位图由本组件创建，调用方负责
 *        在不用之后回收。
 */
@Composable
fun ImageCropScreen(
    source: Bitmap,
    aspectRatio: Float,
    onCancel: () -> Unit,
    onConfirm: (Bitmap) -> Unit,
    modifier: Modifier = Modifier,
) {
    val image = remember(source) { source.asImageBitmap() }

    // 裁剪框的实际像素尺寸。第一次布局完成后才知道。
    var frameSize by remember { mutableStateOf(IntSize.Zero) }
    var scale by remember { mutableFloatStateOf(0f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    // cover 缩放：让图刚好铺满框。
    val minScale = remember(frameSize, source) {
        if (frameSize.width == 0 || frameSize.height == 0) {
            0f
        } else {
            max(
                frameSize.width.toFloat() / source.width.toFloat(),
                frameSize.height.toFloat() / source.height.toFloat(),
            )
        }
    }
    val maxScale = minScale * MAX_ZOOM

    // 框尺寸已知（或发生变化）时重新摆正：按 cover 缩放并居中。
    LaunchedEffect(frameSize, source) {
        if (minScale <= 0f) return@LaunchedEffect
        scale = minScale
        offset = Offset(
            x = (frameSize.width - source.width * minScale) / 2f,
            y = (frameSize.height - source.height * minScale) / 2f,
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(OverlayBackground.copy(alpha = 0.97f))
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "调整背景图",
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
        )
        Text(
            text = "拖动可以移动，双指捏合可以缩放。框里看到的就是聊天页上会看到的部分。",
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.7f),
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(aspectRatio)
                    .clip(RoundedCornerShape(14.dp))
                    .onSizeChanged { frameSize = it }
                    .pointerInput(minScale, maxScale) {
                        detectTransformGestures { centroid, pan, zoom, _ ->
                            if (minScale <= 0f) return@detectTransformGestures

                            val previous = scale
                            val next = (previous * zoom).coerceIn(minScale, maxScale)
                            val ratio = if (previous > 0f) next / previous else 1f

                            // 让捏合的中心点在图上保持不动。
                            // 没有这一步，缩放会永远以图片左上角为锚点，
                            // 手感是「越缩越跑到角上去」。
                            val anchored = Offset(
                                x = centroid.x + pan.x - (centroid.x - offset.x) * ratio,
                                y = centroid.y + pan.y - (centroid.y - offset.y) * ratio,
                            )

                            scale = next
                            offset = clampOffset(
                                candidate = anchored,
                                scale = next,
                                sourceWidth = source.width,
                                sourceHeight = source.height,
                                frameWidth = frameSize.width,
                                frameHeight = frameSize.height,
                            )
                        }
                    },
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    if (scale <= 0f) return@Canvas
                    drawImage(
                        image = image,
                        srcOffset = IntOffset.Zero,
                        srcSize = IntSize(source.width, source.height),
                        dstOffset = IntOffset(offset.x.roundToInt(), offset.y.roundToInt()),
                        dstSize = IntSize(
                            width = (source.width * scale).roundToInt(),
                            height = (source.height * scale).roundToInt(),
                        ),
                        // 缩小时用双线性，放大时也不会糊成块状。
                        filterQuality = FilterQuality.Low,
                    )
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CropActionButton(
                text = "取消",
                filled = false,
                onClick = onCancel,
                modifier = Modifier.weight(1f),
            )
            CropActionButton(
                text = "就用这块",
                filled = true,
                onClick = {
                    if (scale <= 0f || frameSize.width == 0) {
                        onCancel()
                    } else {
                        onConfirm(
                            cropToBitmap(
                                source = source,
                                scale = scale,
                                offset = offset,
                                frameWidth = frameSize.width,
                                frameHeight = frameSize.height,
                            ),
                        )
                    }
                },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** 底部的一枚操作按钮。裁剪页是深色浮层，Material 按钮的默认配色在这里都不合适，自绘。 */
@Composable
private fun CropActionButton(
    text: String,
    filled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (filled) FocusTheme.colors.accent else Color.White.copy(alpha = 0.12f),
            )
            .clickable(onClick = onClick)
            .padding(vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White,
        )
    }
}

/**
 * 把图限制在框内，不允许露出一丝空白。
 *
 * 当图在某一个方向上比框小（只可能发生在浮点误差下），就把它居中，
 * 免得出现「一边贴死、一边空出半个像素」的抖动。
 */
private fun clampOffset(
    candidate: Offset,
    scale: Float,
    sourceWidth: Int,
    sourceHeight: Int,
    frameWidth: Int,
    frameHeight: Int,
): Offset {
    val drawnWidth = sourceWidth * scale
    val drawnHeight = sourceHeight * scale

    val minX = min(0f, frameWidth - drawnWidth)
    val maxX = max(0f, frameWidth - drawnWidth)
    val minY = min(0f, frameHeight - drawnHeight)
    val maxY = max(0f, frameHeight - drawnHeight)

    return Offset(
        x = if (drawnWidth <= frameWidth) (frameWidth - drawnWidth) / 2f else candidate.x.coerceIn(minX, maxX),
        y = if (drawnHeight <= frameHeight) (frameHeight - drawnHeight) / 2f else candidate.y.coerceIn(minY, maxY),
    )
}

/**
 * 按当前的缩放与偏移，从源图上抠出框里那块。
 *
 * 用 `android.graphics.Canvas` 而不是 Compose 的绘制 API：这里要的是一张真的
 * [Bitmap]，而 Compose 的画布最终也要落到一张 Android Canvas 上。直接画少一层。
 */
private fun cropToBitmap(
    source: Bitmap,
    scale: Float,
    offset: Offset,
    frameWidth: Int,
    frameHeight: Int,
): Bitmap {
    val output = Bitmap.createBitmap(frameWidth, frameHeight, Bitmap.Config.ARGB_8888)

    // 反变换：框内的 (0,0) 对应源图的 (-offset/scale)。
    val left = (-offset.x / scale)
    val top = (-offset.y / scale)
    val right = left + frameWidth / scale
    val bottom = top + frameHeight / scale

    val sourceRect = Rect(
        left.roundToInt().coerceIn(0, source.width),
        top.roundToInt().coerceIn(0, source.height),
        right.roundToInt().coerceIn(0, source.width),
        bottom.roundToInt().coerceIn(0, source.height),
    )
    // 浮点误差可能把矩形压成零面积，那样 drawBitmap 会抛异常。
    if (sourceRect.width() <= 0 || sourceRect.height() <= 0) {
        return output
    }

    val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    android.graphics.Canvas(output).drawBitmap(
        source,
        sourceRect,
        Rect(0, 0, frameWidth, frameHeight),
        paint,
    )
    return output
}

/** 最多放大到 cover 的多少倍。 */
private const val MAX_ZOOM = 4f
