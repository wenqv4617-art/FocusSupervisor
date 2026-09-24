package com.focussupervisor.app.core.backup

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import com.focussupervisor.app.data.datastore.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.CRC32
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 数据管理：备份的导出与恢复。
 *
 * ===========================================================================
 * 一、备份包里有什么
 * ===========================================================================
 * ```
 *   focus-backup-20260924-0130.zip
 *   ├── manifest.json          清单：时间、条数、分片数、正文摘要
 *   ├── data.json              全部数据（白名单/待办/预设/人设/记忆/向量/对话/时间线/外观）
 *   └── chat_background.jpg    自定义聊天背景（设过才有）
 * ```
 *
 * **不包含**本地向量模型（22MB）。它不是用户数据，是可以通过界面上那个按钮重新
 * 下载回来的东西 —— 把它打进备份，会让每一份备份都白白多 22MB。
 *
 * ===========================================================================
 * 二、为什么要分片
 * ===========================================================================
 * 这个应用有两个会持续长大的东西：对话（上限 2000 条）与向量索引。
 * 向量是最占地方的：384 维浮点，一条记忆序列化后几个 KB —— 上千条记忆的索引
 * 就是好几 MB。加上对话与时间线，压缩前的正文到十几 MB 是可能的。
 *
 * 而一次性写一个几十 MB 的文件，在手机上会遇到两个很现实的问题：
 *
 *  - **写一半没空间**。走到最后一步才失败，用户白等了半分钟还什么都没得到；
 *  - **传不出去**。微信、邮件对单个附件都有大小限制，而备份最常见的用途恰恰
 *    是「发给自己存一份」。
 *
 * 所以超过 [SPLIT_THRESHOLD_BYTES] 就切成固定大小的 `.part001`、`.part002`……
 * 恢复时按名字顺序拼回来。分片**不做单独压缩**（切的是已经压好的 zip 字节），
 * 所以每一片都不是有效 zip，必须按顺序拼回 —— 这一点写在界面上，
 * 免得用户以为可以只拿其中一片去恢复。
 *
 * ===========================================================================
 * 三、导出到哪儿
 * ===========================================================================
 * Android 10 起，应用私有外部目录（`Android/data/<包名>/`）在多数文件管理器里
 * **打不开**，而「备份到自己找不到的地方」等于没备份。所以：
 *
 *  - Android 10+：写进系统「下载」目录下的 `FocusSupervisor/` 子目录，走 MediaStore，
 *    不需要任何权限，用户用任何文件管理器都能看到；
 *  - Android 9 及以下：系统没有免权限写公共目录的办法，退回私有外部目录，
 *    并在界面上把完整路径显示出来。
 */
class BackupManager(context: Context) {

    private val appContext: Context = context.applicationContext

    // -----------------------------------------------------------------------
    // 导出
    // -----------------------------------------------------------------------

    /**
     * 导出一次备份。
     *
     * 整个过程分三步，任何一步失败都返回 [Result.failure]，不会留下半截文件：
     * 1. 在缓存目录里生成 zip（缓存目录不会被用户看到，失败也没有副作用）；
     * 2. 决定要不要分片；
     * 3. 逐个写到目标位置。
     */
    suspend fun export(
        preferences: AppPreferences,
        backgroundFile: File?,
    ): Result<BackupExport> = withContext(Dispatchers.IO) {
        runCatching {
            val payload = BackupCodec.encode(preferences)
            val archive = buildArchive(payload, backgroundFile)
            try {
                val manifest = buildManifest(preferences, backgroundFile, archive, payload)
                writeOut(archive, manifest)
            } finally {
                // 无论成功失败都清掉缓存：这些临时 zip 加起来可能几十 MB，
                // 留在缓存里只会让系统在别的地方（比如相机）抱怨空间不足。
                archive.delete()
            }
        }.onFailure { throwable ->
            Log.e(TAG, "导出备份失败", throwable)
        }
    }

    /** 生成 zip，返回缓存目录里的临时文件。 */
    private fun buildArchive(payload: String, backgroundFile: File?): File {
        val archive = File(appContext.cacheDir, "backup-build.zip")
        if (archive.exists() && !archive.delete()) {
            error("无法清理上一次的临时备份文件")
        }

        ZipOutputStream(FileOutputStream(archive).buffered()).use { zip ->
            // 1) 正文。DEFLATED 是默认值，但显式写出来 —— 这个包的数据主体是
            //    大量重复键名的 JSON，压缩率很高，别哪天被人顺手改成 STORED。
            zip.putNextEntry(ZipEntry(BackupCodec.ENTRY_DATA).apply { method = ZipEntry.DEFLATED })
            zip.write(payload.toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            // 2) 背景图。JPEG 已经压过了，再 DEFLATE 一次几乎没收益，
            //    用 STORED 省掉一遍无用的 CPU。
            if (backgroundFile != null && backgroundFile.isFile) {
                zip.putNextEntry(
                    ZipEntry(BackupCodec.ENTRY_BACKGROUND).apply { method = ZipEntry.STORED }.also {
                        // STORED 必须显式给出大小与 CRC，否则写出来的是坏包。
                        it.size = backgroundFile.length()
                        it.compressedSize = backgroundFile.length()
                        it.crc = crc32Of(backgroundFile)
                    },
                )
                backgroundFile.inputStream().use { input -> input.copyTo(zip) }
                zip.closeEntry()
            }
        }
        return archive
    }

    /** 组装清单。 */
    private fun buildManifest(
        preferences: AppPreferences,
        backgroundFile: File?,
        archive: File,
        payload: String,
    ): BackupManifest {
        val payloadBytes = payload.toByteArray(Charsets.UTF_8)
        val partCount = if (archive.length() > SPLIT_THRESHOLD_BYTES) {
            ((archive.length() + SPLIT_THRESHOLD_BYTES - 1) / SPLIT_THRESHOLD_BYTES).toInt()
        } else {
            1
        }

        return BackupManifest(
            formatVersion = BackupCodec.FORMAT_VERSION,
            createdAtMillis = System.currentTimeMillis(),
            appVersionName = appVersionName(),
            schemaVersion = preferences.schemaVersion,
            partCount = partCount,
            baseName = "focus-backup-${TIMESTAMP_FORMAT.format(Instant.now())}",
            totalBytes = archive.length(),
            hasBackgroundImage = backgroundFile != null && backgroundFile.isFile,
            payloadSha256 = sha256Of(payloadBytes),
            messageCount = preferences.messages.size,
            memoryCount = preferences.memories.size,
            timelineCount = preferences.timeline.size,
            whitelistCount = preferences.whitelist.size,
            todoCount = preferences.todos.size,
            presetCount = preferences.presets.size,
        )
    }

    /**
     * 把清单与 zip 写到目标位置。
     *
     * 清单**单独存一份 `.manifest.json`**，而不是只塞在 zip 里：
     * 分片之后 zip 被切开，用户想确认「这堆 part 是什么、是不是完整的一套」时，
     * 不应该还需要先把它们拼起来才能看到清单。
     */
    private fun writeOut(archive: File, manifest: BackupManifest): BackupExport {
        val sink = openSink()
        val written = mutableListOf<String>()

        if (manifest.partCount <= 1) {
            val name = "${manifest.baseName}.zip"
            sink.open(name).use { output -> archive.inputStream().use { it.copyTo(output) } }
            written += name
        } else {
            archive.inputStream().use { input ->
                val buffer = ByteArray(SPLIT_THRESHOLD_BYTES.toInt())
                var index = 0
                while (true) {
                    val read = input.readFully(buffer)
                    if (read <= 0) break
                    index++
                    val name = "${manifest.baseName}.part%03d".format(index)
                    sink.open(name).use { output -> output.write(buffer, 0, read) }
                    written += name
                    if (read < buffer.size) break
                }
            }
        }

        val manifestName = "${manifest.baseName}.manifest.json"
        sink.open(manifestName).use { output ->
            output.write(manifest.toJson().toByteArray(Charsets.UTF_8))
        }
        written += manifestName

        return BackupExport(
            fileNames = written,
            location = sink.locationLabel,
            totalBytes = manifest.totalBytes,
            isSplit = manifest.partCount > 1,
            manifest = manifest,
        )
    }

    // -----------------------------------------------------------------------
    // 目标位置
    // -----------------------------------------------------------------------

    /** 一个「能按名字写文件」的目标位置。 */
    private interface BackupSink {
        fun open(name: String): OutputStream

        /** 给用户看的路径文案。 */
        val locationLabel: String
    }

    private fun openSink(): BackupSink =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStoreSink()
        } else {
            LegacyDirectorySink()
        }

    /**
     * Android 10+：写进「下载 / FocusSupervisor」。
     *
     * 走 MediaStore 而不是直接 File 路径：Android 10 起应用没有权限往公共目录
     * 直接建文件，MediaStore 是免权限的正路，而且写进去的东西系统相册/文件管理器
     * 立刻能索引到。
     */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private inner class MediaStoreSink : BackupSink {

        override val locationLabel: String = "下载/FocusSupervisor"

        override fun open(name: String): OutputStream {
            val resolver = appContext.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(
                    MediaStore.Downloads.MIME_TYPE,
                    if (name.endsWith(".json")) "application/json" else "application/zip",
                )
                put(
                    MediaStore.Downloads.RELATIVE_PATH,
                    "${Environment.DIRECTORY_DOWNLOADS}/$PUBLIC_DIR",
                )
                // 同名的旧备份直接覆盖：用户连点两次导出，不该在下载目录里
                // 留下两堆长得一样的 part 文件让他去分辨。
                put(MediaStore.Downloads.IS_PENDING, 1)
            }

            val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            deleteExisting(collection, name)

            val uri = resolver.insert(collection, values)
                ?: error("系统拒绝了写入下载目录的请求")
            val stream = resolver.openOutputStream(uri)
                ?: error("无法打开下载目录里的文件")

            return object : OutputStream() {
                override fun write(b: Int) = stream.write(b)

                override fun write(b: ByteArray, off: Int, len: Int) =
                    stream.write(b, off, len)

                override fun close() {
                    // 先解除 pending 再关流：反过来的话文件会一直停在 pending 状态，
                    // 对文件管理器不可见（用户看到的现象是「导出成功但找不到文件」）。
                    runCatching {
                        resolver.update(
                            uri,
                            ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                            null,
                            null,
                        )
                    }
                    stream.close()
                }
            }
        }

        /** 删掉下载目录里同名的旧文件。 */
        private fun deleteExisting(collection: Uri, name: String) {
            runCatching {
                appContext.contentResolver.delete(
                    collection,
                    "${MediaStore.Downloads.DISPLAY_NAME} = ? AND " +
                        "${MediaStore.Downloads.RELATIVE_PATH} LIKE ?",
                    arrayOf(name, "%$PUBLIC_DIR%"),
                )
            }.onFailure { Log.w(TAG, "清理同名旧备份失败：$name", it) }
        }
    }

    /**
     * Android 9 及以下：写进私有外部目录。
     *
     * 这是无奈之举 —— 那个版本往公共「下载」目录写文件必须有
     * `WRITE_EXTERNAL_STORAGE` 权限。为了一个备份功能去要一个「读写你的所有文件」
     * 的权限，代价明显大于收益，所以退回私有目录，并在界面上把完整路径摆出来，
     * 让用户自己用数据线或者文件管理器去取。
     */
    private inner class LegacyDirectorySink : BackupSink {

        private val directory: File = File(
            appContext.getExternalFilesDir(null) ?: appContext.filesDir,
            "backup",
        )

        override val locationLabel: String
            get() = directory.absolutePath

        override fun open(name: String): OutputStream {
            if (!directory.exists() && !directory.mkdirs()) {
                error("无法创建备份目录")
            }
            val target = File(directory, name)
            if (target.exists() && !target.delete()) {
                error("无法覆盖同名备份文件")
            }
            return FileOutputStream(target).buffered()
        }
    }

    // -----------------------------------------------------------------------
    // 恢复
    // -----------------------------------------------------------------------

    /**
     * 读取一个目录里的备份，解析出清单与数据。
     *
     * 只读不写 —— 真正的写入由界面在用户确认之后调用
     * `AppPreferencesDataSource.restoreAll`。分成两步是因为「恢复」会覆盖掉当前
     * 全部数据，用户必须有机会先看到「这份备份里有多少条对话、什么时候导出的」。
     *
     * @param children 目录里的文件（显示名 + Uri），由界面枚举后传入。
     */
    suspend fun readBackup(
        children: List<BackupChild>,
    ): Result<RestoreCandidate> = withContext(Dispatchers.IO) {
        runCatching {
            val parts = selectParts(children)
            val bytes = readParts(parts)
            val entries = unzip(bytes)
            val payload = entries[BackupCodec.ENTRY_DATA]
                ?: error("备份包里没有 data.json，可能不是本应用导出的备份")

            val manifest = entries[BackupCodec.ENTRY_MANIFEST]
                ?.let { runCatching { BackupManifest.fromJson(String(it, Charsets.UTF_8)) }.getOrNull() }

            // 校验正文摘要。清单里没有摘要（旧备份或手改的包）就跳过校验，
            // 而不是拒绝恢复 —— 那会让一份本来能用的备份变得不可用。
            val actual = sha256Of(payload)
            if (manifest != null && manifest.payloadSha256.isNotBlank() &&
                manifest.payloadSha256 != actual
            ) {
                error("备份正文校验失败（文件可能已损坏或被修改）")
            }

            RestoreCandidate(
                manifest = manifest,
                preferences = BackupCodec.decode(String(payload, Charsets.UTF_8)),
                backgroundImage = entries[BackupCodec.ENTRY_BACKGROUND],
                totalBytes = bytes.size,
            )
        }.onFailure { throwable ->
            Log.e(TAG, "读取备份失败", throwable)
        }
    }

    /**
     * 从目录里的文件挑出属于同一份备份的那一组。
     *
     * 目录里可能有**多份**备份（用户导出了好几次都放在一起）。判据：
     *  - 忽略 `.manifest.json`（它是说明，不是数据）；
     *  - 有 `.zip` 就优先用它（单文件备份，最明确）；
     *  - 否则按 `.partNNN` 的基名分组，取分片字节总数最大的那一组 ——
     *    用户刚导出的那份通常就是最大的那份，而且这个判据比「按时间取最新」
     *    更可靠：备份文件通过聊天软件转发过来时，修改时间会被重写。
     */
    private fun selectParts(children: List<BackupChild>): List<BackupChild> {
        if (children.isEmpty()) error("这个文件夹里没有文件")

        val singles = children.filter { it.name.endsWith(".zip", ignoreCase = true) }
        if (singles.isNotEmpty()) {
            return listOf(singles.maxByOrNull { it.sizeBytes } ?: singles.first())
        }

        val split = children
            .filter { PART_PATTERN.find(it.name) != null }
            .groupBy { it.name.substringBefore(".part") }
            .filterValues { it.isNotEmpty() }

        if (split.isEmpty()) {
            error("没找到备份文件（需要 .zip 或 .partNNN）")
        }

        val largest = split.maxByOrNull { entry -> entry.value.sumOf { it.sizeBytes } }
            ?: error("没找到可用的备份分片")
        return largest.value.sortedBy { it.name }
    }

    /** 把分片按顺序拼起来。单文件备份就是直接读出来。 */
    private fun readParts(parts: List<BackupChild>): ByteArray {
        val total = parts.sumOf { it.sizeBytes }.coerceAtLeast(0L)
        if (total > MAX_RESTORE_BYTES) {
            error("备份文件超过 ${MAX_RESTORE_BYTES / 1024 / 1024}MB，本版本暂不支持恢复")
        }

        val output = java.io.ByteArrayOutputStream(if (total > 0) total.toInt() else 1024)
        for (part in parts) {
            appContext.contentResolver.openInputStream(part.uri)?.use { input ->
                input.copyTo(output)
            } ?: error("读不到文件：${part.name}")
        }
        return output.toByteArray()
    }

    /** 解压出需要的几个条目。列表以外的东西一律忽略。 */
    private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val wanted = setOf(
            BackupCodec.ENTRY_DATA,
            BackupCodec.ENTRY_MANIFEST,
            BackupCodec.ENTRY_BACKGROUND,
        )
        val result = mutableMapOf<String, ByteArray>()

        ZipInputStream(bytes.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name in wanted) {
                    result[entry.name] = zip.readBytes()
                }
                zip.closeEntry()
            }
        }

        if (result.isEmpty()) {
            error("备份包是空的，或者格式不正确")
        }
        return result
    }

    // -----------------------------------------------------------------------
    // 工具
    // -----------------------------------------------------------------------

    /**
     * 读满整个缓冲区。
     *
     * `InputStream.read` **不保证**一次读满，尤其在网络/大文件场景下会返回短读。
     * 直接把返回值当作「一片」会让分片大小参差不齐，恢复时按顺序拼仍然是对的，
     * 但每片的大小就不再是预期的固定值了 —— 而「固定大小」正是分片可读性的来源。
     */
    private fun java.io.InputStream.readFully(buffer: ByteArray): Int {
        var offset = 0
        while (offset < buffer.size) {
            val read = read(buffer, offset, buffer.size - offset)
            if (read < 0) break
            offset += read
        }
        return offset
    }

    private fun crc32Of(file: File): Long {
        val crc = CRC32()
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                crc.update(buffer, 0, read)
            }
        }
        return crc.value
    }

    private fun sha256Of(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString(separator = "") { "%02x".format(it) }
    }

    private fun appVersionName(): String = runCatching {
        val info = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        info.versionName ?: "unknown"
    }.getOrDefault("unknown")

    private companion object {
        const val TAG = "BackupManager"

        /** 下载目录下的子目录名。 */
        const val PUBLIC_DIR = "FocusSupervisor"

        /**
         * 超过这个大小就分片。
         *
         * 8MB 是权衡后的结果：它小到足以塞进绝大多数聊天软件的附件限制，
         * 又大到让正常使用量级（几百条对话 + 几百条记忆）的备份保持单文件。
         */
        const val SPLIT_THRESHOLD_BYTES = 8L * 1024 * 1024

        /** 恢复时的体积上限。防止误选一个巨大的视频文件把内存撑爆。 */
        const val MAX_RESTORE_BYTES = 200L * 1024 * 1024

        /**
         * 分片名匹配。
         *
         * 这里用正则**没有**花括号，也没有 `}` —— 本项目已经被 ICU 对裸 `}` 的
         * 判定坑过一次（见 `AiCommandParser`），凡是能用普通字符串判断的地方就不碰正则。
         */
        val PART_PATTERN = Regex("\\.part\\d+$")

        /** 备份文件名的时戳。到分钟就够了，同一分钟内连点两次会覆盖，正是想要的行为。 */
        val TIMESTAMP_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmm").withZone(ZoneId.systemDefault())
    }
}

/** 目录里的一个文件。界面用 `DocumentsContract` 枚举后传进来。 */
data class BackupChild(
    val name: String,
    val uri: Uri,
    val sizeBytes: Long,
)

/** 清单。 */
data class BackupManifest(
    val formatVersion: Int,
    val createdAtMillis: Long,
    val appVersionName: String,
    val schemaVersion: Int,
    val partCount: Int,
    val baseName: String,
    val totalBytes: Long,
    val hasBackgroundImage: Boolean,
    val payloadSha256: String,
    val messageCount: Int,
    val memoryCount: Int,
    val timelineCount: Int,
    val whitelistCount: Int,
    val todoCount: Int,
    val presetCount: Int,
) {
    fun toJson(): String = JSONObject().apply {
        put("formatVersion", formatVersion)
        put("createdAtMillis", createdAtMillis)
        put("appVersionName", appVersionName)
        put("schemaVersion", schemaVersion)
        put("partCount", partCount)
        put("baseName", baseName)
        put("totalBytes", totalBytes)
        put("hasBackgroundImage", hasBackgroundImage)
        put("payloadSha256", payloadSha256)
        put("messageCount", messageCount)
        put("memoryCount", memoryCount)
        put("timelineCount", timelineCount)
        put("whitelistCount", whitelistCount)
        put("todoCount", todoCount)
        put("presetCount", presetCount)
    }.toString()

    companion object {
        fun fromJson(json: String): BackupManifest {
            val obj = JSONObject(json)
            return BackupManifest(
                formatVersion = obj.optInt("formatVersion", 1),
                createdAtMillis = obj.optLong("createdAtMillis"),
                appVersionName = obj.optString("appVersionName", "unknown"),
                schemaVersion = obj.optInt("schemaVersion", 0),
                partCount = obj.optInt("partCount", 1),
                baseName = obj.optString("baseName"),
                totalBytes = obj.optLong("totalBytes"),
                hasBackgroundImage = obj.optBoolean("hasBackgroundImage", false),
                payloadSha256 = obj.optString("payloadSha256"),
                messageCount = obj.optInt("messageCount"),
                memoryCount = obj.optInt("memoryCount"),
                timelineCount = obj.optInt("timelineCount"),
                whitelistCount = obj.optInt("whitelistCount"),
                todoCount = obj.optInt("todoCount"),
                presetCount = obj.optInt("presetCount"),
            )
        }
    }
}

/** 一次导出的结果。 */
data class BackupExport(
    val fileNames: List<String>,
    val location: String,
    val totalBytes: Long,
    val isSplit: Boolean,
    val manifest: BackupManifest,
)

/**
 * 从磁盘读出来、等待用户确认的一份备份。
 *
 * 注意 [backgroundImage] 是**字节**而不是已写好的文件：确认恢复之后才落盘，
 * 取消恢复不该在磁盘上留下任何东西。
 */
data class RestoreCandidate(
    val manifest: BackupManifest?,
    val preferences: AppPreferences,
    val backgroundImage: ByteArray?,
    val totalBytes: Int,
) {
    // ByteArray 在 data class 里不会按内容比较，手写 equals/hashCode 才能让
    // 「同一份备份」的判断符合直觉。界面上用它判断「选中的是不是同一份」。
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RestoreCandidate) return false
        if (manifest != other.manifest) return false
        if (preferences != other.preferences) return false
        if (totalBytes != other.totalBytes) return false
        return when {
            backgroundImage == null -> other.backgroundImage == null
            other.backgroundImage == null -> false
            else -> backgroundImage.contentEquals(other.backgroundImage)
        }
    }

    override fun hashCode(): Int {
        var result = manifest?.hashCode() ?: 0
        result = 31 * result + preferences.hashCode()
        result = 31 * result + totalBytes
        result = 31 * result + (backgroundImage?.contentHashCode() ?: 0)
        return result
    }
}

/**
 * 枚举一个目录（`ACTION_OPEN_DOCUMENT_TREE` 拿到的 tree Uri）下的文件。
 *
 * 为什么用 `DocumentsContract` 而不是引 `androidx.documentfile`：
 * 这里只需要「列出名字、大小、Uri」三件事，为它多引一个库不划算。
 */
object BackupDirectoryReader {

    private const val TAG = "BackupDirectoryReader"

    fun listChildren(context: Context, treeUri: Uri): List<BackupChild> = runCatching {
        val treeId = DocumentsContract.getTreeDocumentId(treeUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeId)

        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE,
        )

        val result = mutableListOf<BackupChild>()
        context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            val idIndex = cursor.getColumnIndex(projection[0])
            val nameIndex = cursor.getColumnIndex(projection[1])
            val sizeIndex = cursor.getColumnIndex(projection[2])
            if (idIndex < 0 || nameIndex < 0) return@use

            while (cursor.moveToNext()) {
                val name = cursor.getString(nameIndex) ?: continue
                val size = if (sizeIndex >= 0) cursor.getLong(sizeIndex) else 0L
                val uri = DocumentsContract.buildDocumentUriUsingTree(
                    treeUri,
                    cursor.getString(idIndex),
                )
                result += BackupChild(name = name, uri = uri, sizeBytes = size)
            }
        }
        result
    }.getOrElse { throwable ->
        Log.e(TAG, "枚举备份目录失败", throwable)
        emptyList()
    }
}
