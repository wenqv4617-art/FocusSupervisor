package com.focussupervisor.app.data.repository

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import com.focussupervisor.app.core.ai.LocalLlmClient
import com.focussupervisor.app.core.ai.prompt.ChatTemplate
import com.focussupervisor.app.core.ai.prompt.PromptProfiles
import com.focussupervisor.app.core.model.DownloadProgress
import com.focussupervisor.app.core.model.DownloadSpec
import com.focussupervisor.app.core.model.LocalModelDownloader
import com.focussupervisor.app.core.network.ChatTurn
import com.focussupervisor.app.domain.model.LocalLlmModel
import com.focussupervisor.app.domain.model.LocalLlmModels
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * 一次跑分的结果。
 *
 * ===========================================================================
 * 为什么是这三个数，而不是一个笼统的「得分」
 * ===========================================================================
 * 端侧模型有三个**互相独立**的体验维度，合成一个分数会把关键信息抹掉：
 *
 * ```
 *   首字延迟 (TTFT)   按下发送之后多久看到第一个字 —— 决定「卡不卡」
 *   生成吞吐 (tok/s)  之后每个字出来的速度      —— 决定「读完要多久」
 *   内存占用 (PSS)    这台手机要腾出多少内存    —— 决定「还能不能开别的应用」
 * ```
 *
 * 一个 0.5B 与一个 3.8B 的对比很可能长这样：前者首字 300ms / 18 tok/s / 900MB，
 * 后者首字 900ms / 9 tok/s / 3.6GB。它们没有优劣之分，是两种取舍 ——
 * 给一个「综合得分 82」等于把选择权从用户手里拿走。
 *
 * @param ttftMillis 首字延迟（毫秒）。
 * @param tokensPerSecond 生成吞吐（token/秒）。
 * @param outputTokens 本次生成的 token 数。样本太小的话吞吐数会抖，界面上要显示出来。
 * @param memoryPssMb 进程的 PSS（含 native），这是**真实占用**。
 * @param memoryHeapMb Java 堆占用。它通常远小于 PSS，因为模型权重在 native 堆上 ——
 *        两个数都给出来，用户才不会以为「才用了 80MB 啊」。
 */
data class BenchmarkResult(
    val modelId: String,
    val ttftMillis: Long,
    val tokensPerSecond: Float,
    val outputTokens: Int,
    val generatedChars: Int,
    val memoryPssMb: Int,
    val memoryHeapMb: Int,
    val measuredAtMillis: Long,
) {
    /** 首字延迟的人话描述。 */
    val ttftLabel: String get() = when {
        ttftMillis <= 0L -> "未测到"
        ttftMillis < 1000 -> "${ttftMillis} ms"
        else -> String.format(java.util.Locale.US, "%.1f s", ttftMillis / 1000.0)
    }
}

/**
 * 数据层：端侧对话模型仓库。
 *
 * ===========================================================================
 * 它同时管四件事，而且这四件事必须由同一个类协调
 * ===========================================================================
 * ```
 *   文件   在不在、下到哪了、占多少空间
 *   状态   界面直接订阅它画进度条
 *   引擎   加载、释放、生成
 *   跑分   测一次并把结果留在内存里
 * ```
 *
 * 拆开会产生一个具体的时序问题：「文件到位了但引擎还没加载」这段窗口里，
 * 用户以为自己开了端侧模型，实际每次对话都在失败。状态机只有一个作者，
 * 那种窗口才不存在。
 *
 * ===========================================================================
 * 与向量模型那个仓库的区别：这里会**吃内存**
 * ===========================================================================
 * 向量模型是 22MB，加载了就一直留着无所谓。对话模型是 0.5~4GB ——
 * 它占的是「这台手机还能不能正常用」的量级。所以这个类多了一条别处没有的责任：
 * **在该释放的时候主动释放**（切换模型、切回云端、用户删模型）。
 */
interface LocalLlmRepository {

    /** 当前选中的模型。 */
    val selected: StateFlow<LocalLlmModel>

    /** 当前选中模型的下载 / 就绪状态。 */
    val state: StateFlow<LocalModelState>

    /** 引擎是否已经加载好（可以立刻生成）。 */
    val isEngineReady: StateFlow<Boolean>

    /** 最近一次跑分结果。纯内存，重启即失。 */
    val benchmark: StateFlow<BenchmarkResult?>

    /** 切换选中的模型。会释放已加载的引擎并重新判断磁盘状态。 */
    fun select(modelId: String)

    /** 从磁盘实际情况刷新一次状态。 */
    fun refreshState()

    /** 开始（或继续）下载当前选中的模型。 */
    fun startDownload()

    /** 取消下载，保留已下载部分供续传。 */
    fun cancelDownload()

    /** 删除当前模型的全部文件并释放引擎。 */
    fun deleteModel()

    /** 确保引擎可用。 */
    suspend fun ensureReady(): Result<LocalLlmClient>

    /** 跑一次基准测试。 */
    suspend fun runBenchmark(): Result<BenchmarkResult>

    /** 释放引擎（切回云端、退出端侧模式时调用）。 */
    fun release()

    /** 已下载的模型文件。没有就是 null。 */
    fun installedFile(): File?
}

/** [LocalLlmRepository] 的实现。 */
class DefaultLocalLlmRepository(
    context: Context,
    private val scope: CoroutineScope,
    private val engine: LocalLlmClient,
    private val downloader: LocalModelDownloader = LocalModelDownloader(),
) : LocalLlmRepository {

    private val appContext: Context = context.applicationContext

    private val directory: File = File(appContext.filesDir, LocalLlmModels.MODEL_DIR)

    private val _selected = MutableStateFlow(LocalLlmModels.RECOMMENDED)
    override val selected: StateFlow<LocalLlmModel> = _selected.asStateFlow()

    private val _state = MutableStateFlow<LocalModelState>(LocalModelState.NotInstalled)
    override val state: StateFlow<LocalModelState> = _state.asStateFlow()

    private val _isEngineReady = MutableStateFlow(false)
    override val isEngineReady: StateFlow<Boolean> = _isEngineReady.asStateFlow()

    private val _benchmark = MutableStateFlow<BenchmarkResult?>(null)
    override val benchmark: StateFlow<BenchmarkResult?> = _benchmark.asStateFlow()

    private var downloadJob: Job? = null

    init {
        refreshState()
    }

    override fun select(modelId: String) {
        val model = LocalLlmModels.find(modelId) ?: LocalLlmModels.RECOMMENDED
        if (model.modelId == _selected.value.modelId) return

        // 换模型必须先释放旧的：两块权重同时在内存里就是一次 OOM，
        // 而 1.5B + 3.8B 加起来接近 5.5GB，几乎没有手机扛得住。
        cancelDownload()
        engine.release()
        _isEngineReady.value = false
        _benchmark.value = null
        _selected.value = model
        refreshState()
    }

    override fun refreshState() {
        if (_state.value.isBusy) return

        val model = _selected.value
        val installed = downloader.installedFile(model.fileName, directory)
        _state.value = when {
            installed.isFile && installed.length() == model.fileSizeBytes ->
                LocalModelState.Ready(installed.length(), 0)

            // 文件在但大小不对：多半是上次写到一半被杀。清掉腾空间 ——
            // 一个残缺的 1.5GB 文件没有任何用处，却会占着用户最缺的那点空间。
            installed.isFile -> {
                Log.w(TAG, "模型文件大小异常，已清理：${installed.length()}")
                installed.delete()
                LocalModelState.NotInstalled
            }

            else -> {
                val partial = downloader.partialFile(model.fileName, directory)
                if (partial.isFile && partial.length() > 0) {
                    LocalModelState.Partial(partial.length())
                } else {
                    LocalModelState.NotInstalled
                }
            }
        }
        // 引擎在切模型 / 删模型时已经被释放，这里的判断要跟着磁盘走。
        _isEngineReady.value = engine.isReady
    }

    override fun startDownload() {
        if (_state.value.isBusy) return
        val model = _selected.value

        downloadJob = scope.launch {
            _state.value = LocalModelState.Downloading(
                DownloadProgress(
                    downloadedBytes = downloader.partialFile(model.fileName, directory).length(),
                    totalBytes = model.fileSizeBytes,
                    bytesPerSecond = 0,
                    remainingMillis = -1,
                ),
            )

            try {
                downloader.download(
                    spec = DownloadSpec(
                        fileName = model.fileName,
                        url = model.downloadUrl,
                        sizeBytes = model.fileSizeBytes,
                        sha256 = model.sha256,
                    ),
                    targetDirectory = directory,
                ).collect { progress ->
                    _state.value = LocalModelState.Downloading(progress)
                }
            } catch (cancelled: CancellationException) {
                refreshAfterCancel()
                throw cancelled
            } catch (throwable: Throwable) {
                Log.e(TAG, "下载端侧模型失败", throwable)
                _state.value = LocalModelState.Failed(
                    throwable.message?.takeIf { it.isNotBlank() } ?: "下载失败，请重试",
                )
                return@launch
            }

            // 流正常结束 = 下载并通过校验。**不在下载完成后自动加载** ——
            // 加载一个 1.5GB 的模型要占用大量内存，那必须是用户的显式动作
            // （打开开关、或者点「测速跑分」），不能是下载完的副作用。
            _state.value = LocalModelState.Ready(model.fileSizeBytes, 0)
        }
    }

    private fun refreshAfterCancel() {
        val partial = downloader.partialFile(_selected.value.fileName, directory)
        _state.value = if (partial.isFile && partial.length() > 0) {
            LocalModelState.Partial(partial.length())
        } else {
            LocalModelState.NotInstalled
        }
    }

    override fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
    }

    override fun deleteModel() {
        cancelDownload()
        engine.release()
        _isEngineReady.value = false
        _benchmark.value = null

        val model = _selected.value
        val installed = downloader.installedFile(model.fileName, directory)
        val partial = downloader.partialFile(model.fileName, directory)
        listOf(installed, partial).forEach { file ->
            if (file.exists() && !file.delete()) Log.w(TAG, "删除失败：${file.name}")
        }
        _state.value = LocalModelState.NotInstalled
    }

    override suspend fun ensureReady(): Result<LocalLlmClient> {
        engine.takeIf { it.isReady && it.currentModelPath == installedFile()?.absolutePath }
            ?.let { return Result.success(it) }

        val file = installedFile()
            ?: return Result.failure(IllegalStateException("端侧模型还没下载，请先在模型列表里下载一个"))

        val model = _selected.value
        val result = withContext(Dispatchers.Default) {
            engine.load(
                modelFile = file,
                backend = LocalLlmClient.Backend.CPU,
                maxOutputTokens = maxOutputTokensFor(model),
            )
        }
        _isEngineReady.value = result.isSuccess
        return result.map { engine }
    }

    override suspend fun runBenchmark(): Result<BenchmarkResult> = withContext(Dispatchers.Default) {
        val model = _selected.value
        val client = ensureReady().getOrElse { return@withContext Result.failure(it) }

        val profile = PromptProfiles.resolveStyle(model.promptModelName, isLocal = true)
        val prompt = ChatTemplate.render(
            format = profile.format,
            turns = listOf(
                ChatTurn.system("你是一个自律监督者，说话简短。"),
                ChatTurn.user(BENCHMARK_PROMPT),
            ),
        )

        val builder = StringBuilder()
        var firstTokenAt = 0L
        val started = SystemClock.elapsedRealtime()

        // 给跑分一个硬超时。
        //
        // 没有它的话，一个跑不动的模型（例如 3.8B 在 6GB 机器上）会让这个协程
        // 永远挂着，而界面上是一个转不完的圈 —— 用户唯一的出路是杀进程。
        // 超时值取得比较宽（3 分钟），因为大模型在慢设备上确实可能跑到两分钟。
        val completed = withTimeoutOrNull(BENCHMARK_TIMEOUT_MILLIS) {
            try {
                client.generate(prompt).collect { delta ->
                    if (firstTokenAt == 0L) firstTokenAt = SystemClock.elapsedRealtime()
                    builder.append(delta)
                }
                true
            } catch (throwable: Throwable) {
                Log.e(TAG, "跑分失败", throwable)
                false
            }
        }

        val finished = SystemClock.elapsedRealtime()

        if (completed == null) {
            return@withContext Result.failure(
                IllegalStateException(
                    "跑分超时（超过 ${BENCHMARK_TIMEOUT_MILLIS / 60_000} 分钟）。" +
                        "这个模型可能超出了这台设备的能力，换一个更小的试试。",
                ),
            )
        }
        if (completed != true || builder.isEmpty()) {
            return@withContext Result.failure(
                IllegalStateException("模型没有产出任何内容，可能不适配这台设备"),
            )
        }

        val outputTokens = client.countTokens(builder.toString()).coerceAtLeast(1)
        val generationMillis = (finished - (if (firstTokenAt > 0) firstTokenAt else started))
            .coerceAtLeast(1L)

        val result = BenchmarkResult(
            modelId = model.modelId,
            ttftMillis = if (firstTokenAt > 0) firstTokenAt - started else -1L,
            tokensPerSecond = outputTokens * 1000f / generationMillis,
            outputTokens = outputTokens,
            generatedChars = builder.length,
            memoryPssMb = MemoryProbe.currentPssMb(appContext),
            memoryHeapMb = MemoryProbe.currentHeapMb(),
            measuredAtMillis = System.currentTimeMillis(),
        )
        _benchmark.value = result
        Result.success(result)
    }

    override fun release() {
        if (engine.release()) {
            _isEngineReady.value = false
        }
    }

    override fun installedFile(): File? =
        downloader.installedFile(_selected.value.fileName, directory).takeIf { it.isFile }

    /**
     * 单次生成的 token 上限。
     *
     * 微型档必须压得更狠：0.5B 每秒只吐十几个 token，而它一旦开始写长文就会开始编。
     * 160 token 约两百字，已经超过它该说的量了。
     */
    private fun maxOutputTokensFor(model: LocalLlmModel): Int =
        if (model.parameterTier.startsWith("0.")) {
            LocalLlmClient.TINY_MAX_OUTPUT_TOKENS
        } else {
            LocalLlmClient.DEFAULT_MAX_OUTPUT_TOKENS
        }

    private companion object {
        const val TAG = "LocalLlmRepository"

        /** 跑分用的一句固定提示。20 字左右，短到不会因为生成长度差异影响对比。 */
        const val BENCHMARK_PROMPT = "用一句话说明：自律为什么很难。"

        /** 跑分超时。3 分钟。 */
        const val BENCHMARK_TIMEOUT_MILLIS = 180_000L
    }
}

/**
 * 进程内存探针。
 *
 * ===========================================================================
 * 为什么要专门写这个，而不是用 Runtime.totalMemory()
 * ===========================================================================
 * `Runtime.getRuntime()` 看的是 **Java 堆**。而端侧模型的权重全部在 **native 堆**上
 * （MediaPipe 的 C++ 侧），Java 侧只有一个几百字节的句柄。
 *
 * 于是会出现一个非常误导人的现象：加载了 1.5GB 的模型，而
 * `totalMemory - freeMemory` 只涨了 3MB。用户看到「才 3MB」，以为不占内存。
 *
 * [currentPssMb] 走的是 `ActivityManager.getProcessMemoryInfo`，它统计的是**整个进程**
 * 的物理内存占用（含 native、含图形缓冲），那才是这台手机真正付出的代价。
 * 查自己的进程不需要任何权限。
 */
internal object MemoryProbe {

    /** 当前进程的 PSS（MB）。拿不到时返回 0。 */
    fun currentPssMb(context: Context): Int = runCatching {
        val activityManager =
            context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val pid = android.os.Process.myPid()
        val info: Debug.MemoryInfo = activityManager.getProcessMemoryInfo(intArrayOf(pid))
            .firstOrNull() ?: return 0
        info.totalPss / 1024
    }.getOrDefault(0)

    /** 当前 Java 堆占用（MB）。 */
    fun currentHeapMb(): Int {
        val runtime = Runtime.getRuntime()
        return ((runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024).toInt()
    }
}
