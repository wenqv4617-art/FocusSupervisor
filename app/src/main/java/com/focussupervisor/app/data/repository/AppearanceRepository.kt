package com.focussupervisor.app.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import com.focussupervisor.app.data.datastore.AppPreferencesDataSource
import com.focussupervisor.app.domain.model.BackgroundMode
import com.focussupervisor.app.domain.model.ChatAppearance
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * 数据层：聊天页外观仓库
 *
 * ===========================================================================
 * 为什么背景图由仓库接管，而不是让界面自己存
 * ===========================================================================
 * 背景图有两个落点，而且必须同时正确：
 *
 *  1. **文件**：裁剪结果写到 `filesDir/chat_background.jpg`；
 *  2. **引用**：`ChatAppearance.backgroundImagePath` 落盘指向它。
 *
 * 这两步一旦分家就会出现两种很难查的状态：文件在、引用不在（用户的图白裁了），
 * 或者引用在、文件不在（聊天页背景是一块空白）。把它们收在仓库里，
 * 界面只调 [applyBackgroundImage]，就不可能只成功一半。
 *
 * 另外，用户换主题或删背景图时要顺手把旧文件删掉 —— 那是几 MB 的东西，
 * 留在私有目录里既占空间，也会被一起打进备份。
 */
interface AppearanceRepository {

    /**
     * 当前外观。
     *
     * 与 [com.focussupervisor.app.data.repository.GazeRepository.config] 不同，
     * 这里**不是 null**：外观有一份完全合理的默认值（跟随应用明暗、无背景图、
     * 无注入样式），「还没读到磁盘」和「就是默认值」在观感上没有差别，
     * 区分它们只会让每个调用方都多写一个分支。
     */
    val appearance: StateFlow<ChatAppearance>

    /** 保存外观。@return 是否写入成功。 */
    suspend fun update(appearance: ChatAppearance): Boolean

    /**
     * 把一张已经裁剪好的图写成聊天背景并落盘引用。
     *
     * @return 成功时返回新的外观，失败返回 null。界面据此决定要不要提示。
     */
    suspend fun applyBackgroundImage(bitmap: Bitmap, opacity: Float): ChatAppearance?

    /** 清除自定义背景图（连同文件）。 */
    suspend fun clearBackgroundImage(): ChatAppearance?

    /** 背景图文件。返回 null 表示没设过或文件已经丢了。 */
    fun backgroundFile(): File?

    /** 背景图是否真的在磁盘上。引用存在但文件没了时用来兜底。 */
    fun hasBackgroundImage(): Boolean
}

/** [AppearanceRepository] 的实现：外观落 DataStore，图片落私有目录。 */
class DataStoreAppearanceRepository(
    private val context: Context,
    private val preferences: AppPreferencesDataSource,
    scope: CoroutineScope,
) : AppearanceRepository {

    private val appContext: Context = context.applicationContext

    private val _appearance = MutableStateFlow(ChatAppearance())
    override val appearance: StateFlow<ChatAppearance> = _appearance.asStateFlow()

    init {
        scope.launch {
            preferences.preferences.collect { prefs ->
                _appearance.value = prefs.appearance
            }
        }
    }

    override suspend fun update(appearance: ChatAppearance): Boolean = try {
        preferences.updateAppearance(appearance)
        true
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        Log.e(TAG, "保存聊天页外观失败", t)
        false
    }

    override suspend fun applyBackgroundImage(
        bitmap: Bitmap,
        opacity: Float,
    ): ChatAppearance? = withContext(Dispatchers.IO) {
        val target = File(appContext.filesDir, ChatAppearance.BACKGROUND_FILE_NAME)
        val written = runCatching {
            // 先写到临时文件再改名。直接往目标文件写，一旦中途失败（磁盘满、
            // 进程被杀），用户原来的背景图就没了 —— 而他还什么都没确认。
            val temp = File(appContext.filesDir, "${ChatAppearance.BACKGROUND_FILE_NAME}.tmp")
            FileOutputStream(temp).use { output ->
                // JPEG 没有 alpha 通道，但裁剪出来的一定是 ARGB_8888；
                // compress 会自动丢掉 alpha，不必先转成 RGB_565。
                bitmap.compress(Bitmap.CompressFormat.JPEG, BACKGROUND_JPEG_QUALITY, output)
                output.flush()
            }
            if (target.exists() && !target.delete()) {
                error("旧背景图无法删除")
            }
            if (!temp.renameTo(target)) {
                error("背景图落盘失败")
            }
            true
        }.getOrElse { throwable ->
            Log.e(TAG, "写入聊天背景图失败", throwable)
            false
        }

        if (!written) return@withContext null

        val next = _appearance.value.copy(
            backgroundMode = BackgroundMode.IMAGE,
            backgroundImagePath = target.absolutePath,
            backgroundImageOpacity = opacity.coerceIn(
                ChatAppearance.MIN_IMAGE_OPACITY,
                1f,
            ),
        )
        if (update(next)) next else null
    }

    override suspend fun clearBackgroundImage(): ChatAppearance? {
        val next = _appearance.value.copy(
            backgroundMode = BackgroundMode.PRESET,
            backgroundImagePath = null,
        )
        if (!update(next)) return null
        // 先落盘、后删文件：顺序反过来的话，删完文件但写盘失败，就会留下一个
        // 「引用无效」的状态，聊天页背景会变成一块空白。
        withContext(Dispatchers.IO) {
            runCatching { backgroundFile()?.delete() }
                .onFailure { Log.e(TAG, "删除旧背景图失败", it) }
        }
        return next
    }

    override fun backgroundFile(): File? {
        val path = _appearance.value.backgroundImagePath ?: return null
        val file = File(path)
        return file.takeIf { it.isFile }
    }

    override fun hasBackgroundImage(): Boolean = backgroundFile() != null

    private companion object {
        const val TAG = "AppearanceRepository"

        /**
         * 背景图 JPEG 质量。
         *
         * 88 是这类「大面积渐变 + 少量文字」的图看不出压损、体积又明显小一档的位置。
         * 再往上收益很小，再往下灰底会出现可见的块状噪点，而聊天页背景恰恰是大片灰底。
         */
        const val BACKGROUND_JPEG_QUALITY = 88
    }
}

/**
 * 从内容 Uri 读一张图。
 *
 * 放在这里而不是界面层，是因为它有两条硬规则必须成对出现，散在界面里迟早漏掉一条：
 *
 *  1. **必须降采样**。用户从相册选的很可能是一张 4000×3000、十几 MB 的原图，
 *     整张读进内存去裁剪，在低端机上就是一次 OOM。
 *  2. **必须限定最长边**。聊天背景最终会被铺满屏幕，超过屏幕分辨率的像素
 *     只是白白占内存和磁盘。
 */
internal object BackgroundImageLoader {

    /** 读图时的采样上限。先按这个算 inSampleSize，再精确缩到 [maxEdge]。 */
    private const val DECODE_HINT_EDGE = 2048

    /** 读一张图并缩到最长边不超过 [maxEdge]。失败返回 null。 */
    fun load(context: Context, uri: Uri, maxEdge: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, bounds)
        } ?: return null

        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = calculateSampleSize(bounds.outWidth, bounds.outHeight, DECODE_HINT_EDGE)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }

        val decoded = context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, decodeOptions)
        } ?: return null

        scaleToMaxEdge(decoded, maxEdge)
    }.getOrElse { throwable ->
        Log.e("BackgroundImageLoader", "读取所选图片失败", throwable)
        null
    }

    /** 算 2 的幂次采样率。 */
    private fun calculateSampleSize(width: Int, height: Int, target: Int): Int {
        var sample = 1
        var longest = maxOf(width, height)
        while (longest / 2 >= target) {
            longest /= 2
            sample *= 2
        }
        return sample
    }

    /** 精确缩放到最长边 [maxEdge]。已经够小就原样返回。 */
    fun scaleToMaxEdge(bitmap: Bitmap, maxEdge: Int): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= maxEdge) return bitmap

        val ratio = maxEdge.toFloat() / longest
        val width = (bitmap.width * ratio).toInt().coerceAtLeast(1)
        val height = (bitmap.height * ratio).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)
        // 缩放产生了新对象时，把旧的那张回收掉 —— 一张 2048 边的 ARGB 位图是 16MB，
        // 在裁剪这种连续操作的场景里，攒两张就足够把低端机顶到 GC 抖动。
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }
}
