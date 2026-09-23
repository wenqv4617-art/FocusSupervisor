package com.focussupervisor.app.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import com.focussupervisor.app.data.datastore.AppPreferencesDataSource
import com.focussupervisor.app.domain.model.AiPersona
import com.focussupervisor.app.domain.model.PersonaPair
import com.focussupervisor.app.domain.model.UserPersona
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** 头像属于哪一方。 */
enum class AvatarTarget {
    AI,
    USER,
}

/**
 * 数据层：人设仓库
 *
 * 与其它仓库一样遵循「内存快照 + Flow 回环」：界面读 `StateFlow`，写入走 DataStore。
 * 人设不像白名单那样有「主线程同步判定」的需求，本来可以直接挂起读；但保持一致的
 * 形态能让 `AppContainer` 里的五个仓库看起来是同一套东西，少一层认知负担。
 */
interface PersonaRepository {

    /** 会话双方的人设。 */
    val personas: StateFlow<PersonaPair>

    suspend fun updateAi(persona: AiPersona): Boolean

    suspend fun updateUser(persona: UserPersona): Boolean

    /**
     * 把用户选中的图片存成头像。
     *
     * 之所以要「另存一份」而不是直接记住 content:// URI：那个 URI 的读取授权是
     * **临时的**，进程重启（甚至只是 Activity 重建）之后就失效了，界面会变成一个
     * 加载不出来的破图。复制进应用私有目录才是可靠的。
     *
     * @return 保存后的本地绝对路径；失败返回 null
     */
    suspend fun saveAvatar(source: Uri, target: AvatarTarget): String?
}

/**
 * [PersonaRepository] 的 DataStore 实现。
 */
class DataStorePersonaRepository(
    context: Context,
    private val preferences: AppPreferencesDataSource,
    scope: CoroutineScope,
) : PersonaRepository {

    private val appContext: Context = context.applicationContext

    private val _personas = MutableStateFlow(PersonaPair())
    override val personas: StateFlow<PersonaPair> = _personas.asStateFlow()

    init {
        scope.launch {
            preferences.preferences.collect { prefs ->
                _personas.value = PersonaPair(ai = prefs.aiPersona, user = prefs.userPersona)
            }
        }
    }

    override suspend fun updateAi(persona: AiPersona): Boolean = write("保存 AI 人设") {
        preferences.updateAiPersona(persona)
    }

    override suspend fun updateUser(persona: UserPersona): Boolean = write("保存用户人设") {
        preferences.updateUserPersona(persona)
    }

    override suspend fun saveAvatar(source: Uri, target: AvatarTarget): String? =
        withContext(Dispatchers.IO) {
            try {
                val bitmap = appContext.contentResolver.openInputStream(source)?.use { stream ->
                    BitmapFactory.decodeStream(stream)
                }
                if (bitmap == null) {
                    Log.w(TAG, "无法解码选中的图片：$source")
                    return@withContext null
                }

                val scaled = scaleToSquare(bitmap)
                if (scaled !== bitmap) bitmap.recycle()

                val directory = File(appContext.filesDir, AVATAR_DIRECTORY).apply { mkdirs() }
                val file = File(directory, "${target.name.lowercase()}-${UUID.randomUUID()}.png")

                file.outputStream().use { output ->
                    scaled.compress(Bitmap.CompressFormat.PNG, AVATAR_QUALITY, output)
                }
                scaled.recycle()

                // 写成功之后才删旧头像：反过来的话，一旦写失败用户就没有头像了。
                removeOldAvatars(directory, target, keep = file)

                file.absolutePath
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                // 选了一张已损坏的图、或者权限被撤销 —— 都不该让应用崩掉。
                Log.e(TAG, "保存头像失败", t)
                null
            }
        }

    /**
     * 把任意尺寸的图片裁成正方形并缩放到头像是用的边长。
     *
     * 居中裁剪而不是拉伸：头像位置是固定尺寸的圆/圆角，拉伸会把脸压扁。
     */
    private fun scaleToSquare(source: Bitmap): Bitmap {
        val side = minOf(source.width, source.height)
        if (side <= 0) return source

        val square = Bitmap.createBitmap(
            source,
            (source.width - side) / 2,
            (source.height - side) / 2,
            side,
            side,
        )

        if (side == AVATAR_SIZE_PX) return square

        val scaled = Bitmap.createScaledBitmap(square, AVATAR_SIZE_PX, AVATAR_SIZE_PX, true)
        if (scaled !== square) square.recycle()
        return scaled
    }

    private fun removeOldAvatars(directory: File, target: AvatarTarget, keep: File) {
        runCatching {
            val prefix = "${target.name.lowercase()}-"
            directory.listFiles()?.forEach { file ->
                if (file.name.startsWith(prefix) && file.absolutePath != keep.absolutePath) {
                    file.delete()
                }
            }
        }.onFailure { Log.w(TAG, "清理旧头像失败", it) }
    }

    private suspend fun write(action: String, block: suspend () -> Unit): Boolean = try {
        block()
        true
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        Log.e(TAG, "$action 失败", t)
        false
    }

    private companion object {
        const val TAG = "PersonaRepository"

        /** 头像文件目录名（位于 filesDir 下）。 */
        const val AVATAR_DIRECTORY = "avatars"

        /**
         * 头像边长。
         *
         * 256px 在 3x 屏上是 85dp，而界面里头像只有 40dp —— 两倍余量，
         * 存成 PNG 也就几十 KB。
         */
        const val AVATAR_SIZE_PX = 256

        const val AVATAR_QUALITY = 100
    }
}
