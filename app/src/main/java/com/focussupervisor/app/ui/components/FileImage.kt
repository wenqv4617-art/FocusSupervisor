package com.focussupervisor.app.ui.components

import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 按文件路径异步加载一张图，并在路径不变时复用。
 *
 * 抽成一个共用函数，是因为有两处需要它（聊天页的背景图、美化面板里的缩略图），
 * 而两处都必须遵守同一条规则：**解码不能发生在主线程**。
 *
 * 一张 1440 边的 ARGB 位图解码出来是 8MB，解码本身在低端机上要几十毫秒 ——
 * 放在组合里同步做，就是每次重组掉一帧。而这个应用有两处会频繁重组（聊天页
 * 每来一条消息、面板里每拖一次滑块），同步解码的下场是可感知的卡顿。
 *
 * 返回 null 表示「还没有」或「读不出来」。两种情况在界面上通常是同一个处理
 * （先留白、读到了再显示），所以不区分。
 */
@Composable
fun rememberFileImage(path: String?): ImageBitmap? {
    val image by produceState<ImageBitmap?>(initialValue = null, path) {
        value = withContext(Dispatchers.IO) {
            val file = path?.takeIf { it.isNotBlank() } ?: return@withContext null
            runCatching { BitmapFactory.decodeFile(file)?.asImageBitmap() }.getOrNull()
        }
    }
    return image
}
