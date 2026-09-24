package com.focussupervisor.app.core.model

import android.util.Log
import com.focussupervisor.app.domain.model.LocalEmbeddingModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * 一次下载需要的全部信息。
 *
 * 抽成通用契约，是因为现在要下的东西不止一类：向量模型、对话模型、将来还有
 * 识图模型。它们的下载逻辑**完全一样**（断点续传、进度上报、SHA-256 校验、
 * 临时文件改名），只有「下什么」不同。为每一类复制一份下载器，
 * 等于把上面那四件事各写三遍 —— 而其中任何一处写漏了都不会报错，
 * 只会表现为「某个模型的下载偶尔是坏的」。
 *
 * @param fileName 落盘文件名。带扩展名，因为它同时是「临时文件叫什么」的依据。
 * @param sha256 期望摘要。**留空表示跳过校验**，用于用户自定义模型 ——
 *        算摘要对普通用户门槛太高。跳过的情形界面必须一直显示「未校验」。
 */
data class DownloadSpec(
    val fileName: String,
    val url: String,
    val sizeBytes: Long,
    val sha256: String,
)

/** 下载过程中的一次进度上报。 */
data class DownloadProgress(
    /** 已经落盘的字节数（含断点续传时已有的部分）。 */
    val downloadedBytes: Long,
    /** 文件总大小。服务端没给 Content-Length 时为 0。 */
    val totalBytes: Long,
    /** 瞬时速度，字节/秒。刚开始的几秒内可能为 0，界面要能容忍。 */
    val bytesPerSecond: Long,
    /** 预计剩余毫秒数。算不出来时为 -1。 */
    val remainingMillis: Long,
) {
    /**
     * 完成比例，0f~1f。
     *
     * 总大小未知时返回 0 —— 界面据此显示不确定进度条，而不是一个永远停在 0%
     * 的确定进度条（后者看起来像卡死了）。
     */
    val fraction: Float
        get() = if (totalBytes > 0L) {
            (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }

    /** 总大小是否已知。 */
    val isTotalKnown: Boolean get() = totalBytes > 0L
}

/**
 * 本地模型下载器。
 *
 * ===========================================================================
 * 一、为什么不用 OkHttp 的默认超时
 * ===========================================================================
 * 22MB 的模型在移动网络上可能要走几分钟。OkHttp 默认的读超时是 10 秒，
 * 那是「连上了但服务端十秒没吐数据」的判定 —— 对一个大文件下载来说这个阈值
 * 经常误伤。所以这里单独建了一个客户端：读超时给到 60 秒，
 * 连接超时保持 15 秒（连不上就该早点失败，让用户看到原因）。
 *
 * ===========================================================================
 * 二、断点续传
 * ===========================================================================
 * 这件事在这个功能上是**必需**而不是加分项：22MB 在国内网络下断一次很常见，
 * 每次都从零开始会让用户连试三次都下不完。
 *
 * 做法是下载到一个 `.part` 临时文件，每次开始前看它有多大，用
 * `Range: bytes=<已有>-` 续。三种回应都要处理：
 *
 * | 响应 | 含义 | 动作 |
 * |------|------|------|
 * | 206 | 服务端支持续传 | 追加写入 |
 * | 200 | 服务端忽略了 Range（或文件被改过） | 清空重下 |
 * | 416 | 已有的部分已经不小于文件了 | 直接进入校验 |
 *
 * 全部下完之后**必须校验 SHA-256 再改名**。一个被截断的 ONNX 文件不会在
 * 写入时报错，它会在运行时抛出一句和「下载」毫无关系的解析错误，
 * 那种问题极难归因。校验放在改名之前，损坏的文件就永远不会出现在正式路径上。
 */
class LocalModelDownloader(private val client: OkHttpClient = defaultClient()) {

    /**
     * 下载一个模型。
     *
     * 冷流：只有被收集时才真正开始下载；收集方取消（用户退出界面）时，
     * 协程取消会把 `copyTo` 中断，临时文件保留下来供下次续传。
     *
     * @param targetDirectory 模型目录。不存在会自动创建。
     * @return 最终落在磁盘上的模型文件。
     */
    fun download(
        model: LocalEmbeddingModel,
        targetDirectory: File,
    ): Flow<DownloadProgress> = download(
        spec = DownloadSpec(
            fileName = model.fileName,
            url = model.downloadUrl,
            sizeBytes = model.sizeBytes,
            sha256 = model.sha256,
        ),
        targetDirectory = targetDirectory,
    )

    /** 通用下载入口。所有模型资源都走这一条。 */
    fun download(
        spec: DownloadSpec,
        targetDirectory: File,
    ): Flow<DownloadProgress> = flow {
        if (!targetDirectory.exists() && !targetDirectory.mkdirs()) {
            throw IOException("无法创建模型目录：${targetDirectory.absolutePath}")
        }

        val target = File(targetDirectory, spec.fileName)
        if (target.isFile && target.length() == spec.sizeBytes) {
            // 已经下过了。直接进入校验，不重新下 —— 用户点了「下载」但文件其实
            // 早就在，这种情况（比如上次下完没刷新界面）应该瞬间完成而不是再等一分钟。
            emit(DownloadProgress(spec.sizeBytes, spec.sizeBytes, 0, 0))
            verifyOrThrow(target, spec)
            return@flow
        }

        val partial = File(targetDirectory, "${spec.fileName}.part")
        var existing = partial.length()
        if (existing > spec.sizeBytes) {
            // 临时文件比目标还大，说明之前写坏了。丢掉重来。
            partial.delete()
            existing = 0
        }

        val request = Request.Builder()
            .url(spec.url)
            .apply { if (existing > 0) header("Range", "bytes=$existing-") }
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful && response.code != HTTP_RANGE_NOT_SATISFIABLE) {
                throw IOException("下载失败：HTTP ${response.code} ${response.message}")
            }

            val resumed = response.code == HTTP_PARTIAL_CONTENT
            val complete = response.code == HTTP_RANGE_NOT_SATISFIABLE ||
                (resumed && existing >= spec.sizeBytes)

            if (!complete) {
                // 服务端不支持续传（200）时，临时文件里的内容就作废了，
                // 必须从零写起，否则会拼出一个前面重复、后面错位的文件。
                if (!resumed && existing > 0) {
                    partial.delete()
                    existing = 0
                }

                val body = response.body ?: throw IOException("下载失败：响应体为空")
                // 服务端给的 Content-Length 在续传时是**剩余**大小，要加上已有的；
                // 拿不到就退回模型声明的总大小。
                val total = when {
                    body.contentLength() > 0 -> existing + body.contentLength()
                    else -> spec.sizeBytes
                }

                FileOutputStream(partial, resumed && existing > 0).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var downloaded = existing
                    var lastReportAt = System.currentTimeMillis()
                    var lastReportBytes = downloaded
                    var speed = 0L

                    body.byteStream().use { input ->
                        while (true) {
                            // 每轮都检查一次取消。OkHttp 的阻塞读不会响应协程取消，
                            // 不显式检查的话用户划走界面后下载还会继续跑到底。
                            currentCoroutineContext().ensureActive()

                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            downloaded += read

                            val now = System.currentTimeMillis()
                            val elapsed = now - lastReportAt
                            if (elapsed >= PROGRESS_INTERVAL_MILLIS) {
                                val delta = downloaded - lastReportBytes
                                // 瞬时速度用「这段时间内的平均」，直接取单次块大小
                                // 会抖得没法看。再做一次指数平滑，数字才稳。
                                val instant = delta * 1000L / elapsed.coerceAtLeast(1L)
                                speed = if (speed <= 0L) instant else (speed * 3 + instant) / 4

                                emit(
                                    DownloadProgress(
                                        downloadedBytes = downloaded,
                                        totalBytes = total,
                                        bytesPerSecond = speed,
                                        remainingMillis = remaining(total, downloaded, speed),
                                    ),
                                )
                                lastReportAt = now
                                lastReportBytes = downloaded
                            }
                        }
                    }
                    output.flush()
                    emit(
                        DownloadProgress(
                            downloadedBytes = downloaded,
                            totalBytes = total,
                            bytesPerSecond = speed,
                            remainingMillis = 0,
                        ),
                    )
                }
            }
        }

        verifyOrThrow(partial, spec)

        if (target.exists() && !target.delete()) {
            throw IOException("无法覆盖旧的模型文件")
        }
        if (!partial.renameTo(target)) {
            throw IOException("模型文件重命名失败")
        }
    }.flowOn(Dispatchers.IO)

    /**
     * 校验文件完整性，不合格直接抛错并删掉它。
     *
     * 摘要对不上时**删掉**而不是留着：留着一个坏文件，下次续传会从一个错误的
     * 位置接着写，永远也下不完。
     */
    private fun verifyOrThrow(file: File, spec: DownloadSpec) {
        if (!file.isFile) throw IOException("模型文件不存在")

        if (spec.sizeBytes > 0 && file.length() != spec.sizeBytes) {
            file.delete()
            throw IOException("模型文件不完整（已下载 ${file.length()} 字节，应为 ${spec.sizeBytes}）")
        }

        if (spec.sha256.isNotBlank()) {
            val actual = sha256Of(file)
            if (!actual.equals(spec.sha256, ignoreCase = true)) {
                file.delete()
                throw IOException("模型文件校验失败，请重试下载")
            }
        }
    }

    /** 流式算摘要。22MB 一次读完大约几百毫秒，不必再拆线程。 */
    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString(separator = "") { "%02x".format(it) }
    }

    /** 剩余时间。速度还没测出来时返回 -1，界面显示「计算中」。 */
    private fun remaining(total: Long, downloaded: Long, speed: Long): Long {
        if (speed <= 0L || total <= downloaded) return -1L
        return (total - downloaded) * 1000L / speed
    }

    /** 已下载但还没校验的临时文件。界面用来显示「上次下到一半」。 */
    fun partialFile(fileName: String, directory: File): File =
        File(directory, "$fileName.part")

    /** 已下载完成的模型文件。 */
    fun installedFile(fileName: String, directory: File): File =
        File(directory, fileName)

    /** 向量模型专用的两个便捷重载。 */
    fun partialFile(model: LocalEmbeddingModel, directory: File): File =
        partialFile(model.fileName, directory)

    fun installedFile(model: LocalEmbeddingModel, directory: File): File =
        installedFile(model.fileName, directory)

    companion object {
        private const val TAG = "LocalModelDownloader"

        /** 读取缓冲区。256KB 在移动网络与闪存上都属于「一次系统调用拿够」的量级。 */
        private const val BUFFER_SIZE = 256 * 1024

        /** 进度上报间隔。太快会把界面刷爆，太慢进度条看着像卡住。 */
        private const val PROGRESS_INTERVAL_MILLIS = 250L

        private const val HTTP_PARTIAL_CONTENT = 206
        private const val HTTP_RANGE_NOT_SATISFIABLE = 416

        /**
         * 下载专用客户端。
         *
         * 与 `OpenAiCompatibleClient` 分开：那一个的超时是为「一次对话请求」
         * 调的（几十秒就该有结果），拿来下 22MB 的文件会频繁误判超时。
         */
        fun defaultClient(): OkHttpClient {
            Log.d(TAG, "创建下载专用 OkHttp 客户端")
            return OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
        }
    }
}
