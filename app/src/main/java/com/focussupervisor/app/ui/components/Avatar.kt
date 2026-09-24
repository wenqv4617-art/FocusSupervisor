package com.focussupervisor.app.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.focussupervisor.app.ui.theme.ChatTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 消息气泡旁边的头像尺寸。微信同尺寸。 */
val MessageAvatarSize: Dp = 40.dp

/**
 * 头像。
 *
 * 三种情况按顺序退化：
 *  1. 有本地图片文件 → 显示它；
 *  2. 没有图片 → 用名字的**首字**做文字头像，底色由名字稳定推导；
 *  3. 连名字都没有 → 一个空白色块。
 *
 * 为什么要有第 2 种：绝大多数用户不会一上来就设头像。留一个灰方块看起来像
 * 「加载失败」，而一个带首字的色块看起来像「还没设置」—— 后者才是事实。
 *
 * 图片解码放在 [Dispatchers.IO]：虽然是 256px 的小图（解码通常几毫秒），
 * 但消息列表一次会重组十几条气泡，叠加起来就足以在滚动时掉帧。
 */
@Composable
fun Avatar(
    path: String?,
    name: String,
    modifier: Modifier = Modifier,
    size: Dp = MessageAvatarSize,
    round: Boolean = false,
) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, path) {
        value = withContext(Dispatchers.IO) {
            val file = path?.takeIf { it.isNotBlank() } ?: return@withContext null
            runCatching { BitmapFactory.decodeFile(file)?.asImageBitmap() }.getOrNull()
        }
    }

    // 微信的头像是「方中带圆」的小圆角方块，不是正圆，所以默认沿用方块。
    // 「捡手机文学」那类主题用的是正圆头像，由皮肤把 round 打开 ——
    // 头像形状和气泡圆角一样，属于「这套主题长什么样」的一部分。
    val shape = if (round) CircleShape else RoundedCornerShape(size * 0.12f)

    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(fallbackColorFor(name)),
        contentAlignment = Alignment.Center,
    ) {
        val image = bitmap
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                text = name.trim().take(1).ifEmpty { "·" },
                style = MaterialTheme.typography.bodyMedium,
                color = ChatTheme.skin.textPrimary,
            )
        }
    }
}

/**
 * 由名字稳定推导一个文字头像的底色。
 *
 * 用名字的哈希在几个低饱和色之间取一个 —— 同一个人每次进来颜色都一样，
 * 不会闪。刻意只用低饱和色：这个应用的整体基调是冷淡，
 * 高饱和的彩色头像块会立刻把界面拉成另一副样子。
 */
private fun fallbackColorFor(name: String): androidx.compose.ui.graphics.Color {
    val palette = FALLBACK_PALETTE
    if (name.isBlank()) return palette.last()
    val index = (name.hashCode().toLong() and 0x7FFFFFFF) % palette.size
    return palette[index.toInt()]
}

private val FALLBACK_PALETTE = listOf(
    androidx.compose.ui.graphics.Color(0xFFD8F3EA),
    androidx.compose.ui.graphics.Color(0xFFDCEAFB),
    androidx.compose.ui.graphics.Color(0xFFE8E2F9),
    androidx.compose.ui.graphics.Color(0xFFFBE1E7),
    androidx.compose.ui.graphics.Color(0xFFE6ECEF),
    androidx.compose.ui.graphics.Color(0xFFEDEDED),
)
