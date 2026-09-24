package com.focussupervisor.app.data.repository

import android.content.Context
import android.util.Log
import com.focussupervisor.app.core.backup.BackupChild
import com.focussupervisor.app.core.backup.BackupExport
import com.focussupervisor.app.core.backup.BackupManager
import com.focussupervisor.app.core.backup.RestoreCandidate
import com.focussupervisor.app.data.datastore.AppPreferences
import com.focussupervisor.app.data.datastore.AppPreferencesDataSource
import com.focussupervisor.app.domain.model.BackgroundMode
import com.focussupervisor.app.domain.model.ChatAppearance
import com.focussupervisor.app.domain.model.LocalEmbeddingModels
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 一次数据体量的快照。
 *
 * 界面上「数据管理」那一页的顶部就是这串数字。它存在的意义不是好看：
 * 用户点「导出备份」之前最该知道的是「我到底有多少东西」——
 * 一个只有十几条对话的人和一个有八百条记忆的人，对「备份要多久、会不会分片」
 * 的预期完全不同。
 */
data class DataStats(
    val messageCount: Int = 0,
    val memoryCount: Int = 0,
    val indexedCount: Int = 0,
    val embeddedCount: Int = 0,
    val timelineCount: Int = 0,
    val whitelistCount: Int = 0,
    val todoCount: Int = 0,
    val presetCount: Int = 0,
    /** 自定义聊天背景占用的字节。没设过就是 0。 */
    val backgroundImageBytes: Long = 0,
    /** 本地向量模型占用的字节。没下载就是 0。 */
    val modelBytes: Long = 0,
) {
    /**
     * 会被写进备份正文的部分（不含模型）。
     *
     * 模型**不进备份**：它是可以从界面重新下回来的东西，把它算进来会让每一份
     * 备份都白白多 22MB。界面上把这笔账分开显示，用户才不会以为「备份怎么这么大」。
     */
    val backupEstimateBytes: Long get() = backgroundImageBytes

    /** 索引里已算出向量的比例，0f~1f。 */
    val embeddingProgress: Float
        get() = if (indexedCount <= 0) 0f else embeddedCount.toFloat() / indexedCount
}

/**
 * 数据层：数据管理（备份 / 恢复 / 体量统计）。
 *
 * ===========================================================================
 * 为什么它是仓库，而不是把逻辑写在 ViewModel 里
 * ===========================================================================
 * 「恢复备份」这件事要动两个地方：DataStore 里的全部配置，以及磁盘上的背景图文件。
 * 这两步必须一起成功 —— 只写回配置而没写回图，聊天页背景会变成一块空白；
 * 只写回图而没写回配置，图就变成一个占着空间却没人引用的孤儿文件。
 *
 * 把这种「多存储的原子性」放在 ViewModel 里，等于让每个界面自己保证它，
 * 而那正是迟早会漏掉一个分支的地方。
 */
interface DataMaintenanceRepository {

    /** 读一次当前体量。 */
    suspend fun readStats(): DataStats

    /** 导出一次备份。 */
    suspend fun export(): Result<BackupExport>

    /** 读一个目录里的备份（只读，等用户确认）。 */
    suspend fun readBackup(children: List<BackupChild>): Result<RestoreCandidate>

    /** 用一份候选备份覆盖当前全部数据。 */
    suspend fun restore(candidate: RestoreCandidate): Boolean
}

/** [DataMaintenanceRepository] 的实现。 */
class DefaultDataMaintenanceRepository(
    context: Context,
    private val preferences: AppPreferencesDataSource,
    private val backupManager: BackupManager,
) : DataMaintenanceRepository {

    private val appContext: Context = context.applicationContext

    private val backgroundFile: File
        get() = File(appContext.filesDir, ChatAppearance.BACKGROUND_FILE_NAME)

    private val modelFile: File
        get() = File(
            File(appContext.filesDir, LocalEmbeddingModels.MODEL_DIR),
            LocalEmbeddingModels.DEFAULT.fileName,
        )

    override suspend fun readStats(): DataStats {
        val prefs = preferences.readOnce()
        return withContext(Dispatchers.IO) {
            DataStats(
                messageCount = prefs.messages.size,
                memoryCount = prefs.memories.size,
                indexedCount = prefs.memoryIndex.size,
                embeddedCount = prefs.memoryIndex.count { it.embedding.isNotEmpty() },
                timelineCount = prefs.timeline.size,
                whitelistCount = prefs.whitelist.size,
                todoCount = prefs.todos.size,
                presetCount = prefs.presets.size,
                backgroundImageBytes = backgroundFile.takeIf { it.isFile }?.length() ?: 0L,
                modelBytes = modelFile.takeIf { it.isFile }?.length() ?: 0L,
            )
        }
    }

    override suspend fun export(): Result<BackupExport> {
        val prefs = preferences.readOnce()
        return backupManager.export(
            preferences = prefs,
            backgroundFile = backgroundFile.takeIf { it.isFile },
        )
    }

    override suspend fun readBackup(children: List<BackupChild>): Result<RestoreCandidate> =
        backupManager.readBackup(children)

    override suspend fun restore(candidate: RestoreCandidate): Boolean = try {
        val snapshot = candidate.preferences

        // 先把背景图落盘，再写配置。
        //
        // 顺序不能反：先写配置的话，配置里那个路径会在文件还不存在的那一刻短暂生效，
        // 聊天页正好在那一刻重组就会拿到一个空背景。反过来先落文件则没有这个窗口 ——
        // 最坏的情况是磁盘上多了一个暂时没人引用的文件。
        val backgroundApplied = withContext(Dispatchers.IO) {
            writeBackground(candidate)
        }

        val appearance = resolveAppearance(snapshot.appearance, backgroundApplied)
        preferences.restoreAll(snapshot.copy(appearance = appearance))
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        Log.e(TAG, "恢复备份失败", t)
        false
    }

    /**
     * 写回背景图。
     *
     * @return 恢复之后背景图是否可用。
     */
    private fun writeBackground(candidate: RestoreCandidate): Boolean {
        val bytes = candidate.backgroundImage
        if (bytes == null || bytes.isEmpty()) {
            // 备份里没有图：把当前那张清掉。否则会出现「恢复了旧备份，
            // 但背景还是恢复前那一张」这种明显不一致的状态。
            runCatching { backgroundFile.delete() }
            return false
        }

        return runCatching {
            val temp = File(appContext.filesDir, "${ChatAppearance.BACKGROUND_FILE_NAME}.restore")
            temp.outputStream().use { output -> output.write(bytes) }
            if (backgroundFile.exists() && !backgroundFile.delete()) {
                error("旧背景图无法删除")
            }
            if (!temp.renameTo(backgroundFile)) {
                error("背景图写入失败")
            }
            true
        }.getOrElse { throwable ->
            Log.e(TAG, "写回背景图失败", throwable)
            false
        }
    }

    /**
     * 修正恢复出来的外观。
     *
     * 备份里的 `backgroundImagePath` 是**上一次安装时**的绝对路径。同一个包名的
     * 私有目录路径在绝大多数情况下是一致的，但跨用户、跨设备恢复时不保证。
     * 与其相信那个字符串，不如按「文件到底有没有写成功」重新决定 ——
     * 这也顺手处理了「备份里有图但写盘失败」的情况：那时把背景退回主题自带，
     * 而不是留一个指向空气的路径。
     */
    private fun resolveAppearance(appearance: ChatAppearance, backgroundApplied: Boolean): ChatAppearance =
        if (backgroundApplied) {
            ChatAppearance.sanitize(
                appearance.copy(
                    backgroundMode = BackgroundMode.IMAGE,
                    backgroundImagePath = backgroundFile.absolutePath,
                ),
            )
        } else {
            ChatAppearance.sanitize(
                appearance.copy(
                    backgroundMode = if (appearance.backgroundMode == BackgroundMode.IMAGE) {
                        BackgroundMode.PRESET
                    } else {
                        appearance.backgroundMode
                    },
                    backgroundImagePath = null,
                ),
            )
        }

    private companion object {
        const val TAG = "DataMaintenance"
    }
}
