package com.focussupervisor.app.data.repository

import android.content.Context
import android.util.Log
import com.focussupervisor.app.core.ai.LocalEmbeddingEngine
import com.focussupervisor.app.core.model.DownloadProgress
import com.focussupervisor.app.core.model.LocalModelDownloader
import com.focussupervisor.app.domain.model.LocalEmbeddingModel
import com.focussupervisor.app.domain.model.LocalEmbeddingModels
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * 本地模型的运行状态。
 *
 * 用一个密封接口而不是「一个布尔 + 一个进度 + 一个错误串」：后者允许出现
 * 「正在下载 60% 同时错误信息还在」这种自相矛盾的组合，界面就得写一堆
 * 优先级判断来决定显示什么。密封接口让「此刻到底是哪种情况」由类型保证。
 */
sealed interface LocalModelState {

    /** 没下载过。 */
    data object NotInstalled : LocalModelState

    /** 上次下到一半（有 `.part` 文件），可以续传。 */
    data class Partial(val downloadedBytes: Long) : LocalModelState

    /** 正在下载。 */
    data class Downloading(val progress: DownloadProgress) : LocalModelState

    /** 正在校验摘要 / 加载模型。 */
    data object Verifying : LocalModelState

    /** 已就绪，可以直接用。 */
    data class Ready(val sizeBytes: Long, val dimension: Int) : LocalModelState

    /** 失败了，[message] 是可以直接显示给用户的中文原因。 */
    data class Failed(val message: String) : LocalModelState

    /** 是否处于「已经在跑」的状态，界面据此禁用按钮。 */
    val isBusy: Boolean
        get() = this is Downloading || this is Verifying

    /** 是否可以开始（或继续）下载。 */
    val canDownload: Boolean
        get() = this is NotInstalled || this is Partial || this is Failed
}

/**
 * 数据层：本地向量模型仓库。
 *
 * ===========================================================================
 * 它管三件事，而且这三件事必须由一个类来协调
 * ===========================================================================
 *  1. **文件**：模型在不在、下载到哪了、`.part` 有多大；
 *  2. **状态**：上面那个 [LocalModelState]，界面直接订阅它画进度条；
 *  3. **引擎**：下载完成后把 [LocalEmbeddingEngine] 加载起来。
 *
 * 拆开会立刻出现一个跨对象的时序问题：「文件到位了但引擎还没加载」这段窗口里，
 * 用户以为自己已经开了本地模型，实际每次检索都在失败。放在一起，
 * 状态机只有一个作者，那种窗口就不存在。
 *
 * 内存状态**刻意不落盘**：它是「此刻的下载情况」，重启后由磁盘上文件的实际
 * 大小重新推导（见 [refreshState]），不需要也不应该持久化 —— 否则会出现
 * 「磁盘上文件已经被清了，状态还写着 Ready」。
 */
interface LocalModelRepository {

    /** 当前状态。 */
    val state: StateFlow<LocalModelState>

    /** 当前选定的模型。 */
    val model: LocalEmbeddingModel

    /** 从磁盘实际情况刷新一次状态。进入界面前调一次。 */
    fun refreshState()

    /** 开始（或继续）下载。重复调用会被忽略。 */
    fun startDownload()

    /** 取消正在进行的下载。已下载的部分保留，供下次续传。 */
    fun cancelDownload()

    /** 删除已下载的模型，释放空间。 */
    fun deleteModel()

    /**
     * 确保引擎可用。
     *
     * @return 成功，或一句中文错误原因。走本地模型算向量之前必须先调它 ——
     *         引擎是懒加载的，这一步就是那个「懒」的触发点。
     */
    suspend fun ensureEngineReady(): Result<LocalEmbeddingEngine>
}

/** [LocalModelRepository] 的实现。 */
class DefaultLocalModelRepository(
    context: Context,
    private val scope: CoroutineScope,
    private val engine: LocalEmbeddingEngine,
    private val downloader: LocalModelDownloader = LocalModelDownloader(),
) : LocalModelRepository {

    private val appContext: Context = context.applicationContext

    override val model: LocalEmbeddingModel = LocalEmbeddingModels.DEFAULT

    private val directory: File = File(appContext.filesDir, LocalEmbeddingModels.MODEL_DIR)

    private val _state = MutableStateFlow<LocalModelState>(LocalModelState.NotInstalled)
    override val state: StateFlow<LocalModelState> = _state.asStateFlow()

    private var downloadJob: Job? = null

    init {
        refreshState()
    }

    override fun refreshState() {
        // 正在下载时不要用磁盘状态覆盖内存状态：那一刻磁盘上的 `.part` 必然比
        // 内存里的进度小一点，覆盖回去会让进度条倒着走。
        if (_state.value.isBusy) return

        val installed = downloader.installedFile(model, directory)
        _state.value = when {
            installed.isFile && installed.length() == model.sizeBytes ->
                LocalModelState.Ready(installed.length(), model.dimension)

            // 文件在但大小不对：多半是上次写到一半被杀。当成「没装」处理，
            // 并把它清掉腾出空间 —— 一个残缺的 22MB 文件没有任何用处。
            installed.isFile -> {
                Log.w(TAG, "模型文件大小异常，已清理：${installed.length()}")
                installed.delete()
                LocalModelState.NotInstalled
            }

            else -> {
                val partial = downloader.partialFile(model, directory)
                if (partial.isFile && partial.length() > 0) {
                    LocalModelState.Partial(partial.length())
                } else {
                    LocalModelState.NotInstalled
                }
            }
        }
    }

    override fun startDownload() {
        if (_state.value.isBusy) return

        downloadJob = scope.launch {
            _state.value = LocalModelState.Downloading(
                DownloadProgress(
                    downloadedBytes = downloader.partialFile(model, directory).length(),
                    totalBytes = model.sizeBytes,
                    bytesPerSecond = 0,
                    remainingMillis = -1,
                ),
            )

            // 这里刻意用 try/catch 而不是 Flow 的 catch 操作符：
            // `catch` 会把异常吞掉并让流「正常结束」，于是下面的加载逻辑照样会执行 ——
            // 结果就是下载失败之后还去加载一个不存在的模型，报出一句跟真实原因
            // （网络）完全无关的错误。用 try/catch 才能把两条路径彻底分开。
            try {
                downloader.download(model, directory).collect { progress ->
                    _state.value = LocalModelState.Downloading(progress)
                }
            } catch (cancelled: CancellationException) {
                // 取消不是错误。保留 `.part`，下次点下载就是续传。
                refreshAfterCancel()
                throw cancelled
            } catch (throwable: Throwable) {
                Log.e(TAG, "下载本地模型失败", throwable)
                _state.value = LocalModelState.Failed(
                    throwable.message?.takeIf { it.isNotBlank() } ?: "下载失败，请重试",
                )
                return@launch
            }

            // 走到这里说明流正常结束 = 文件已经下载并校验通过。
            _state.value = LocalModelState.Verifying
            val loaded = engine.load(downloader.installedFile(model, directory))
            _state.value = loaded.fold(
                onSuccess = {
                    LocalModelState.Ready(
                        sizeBytes = downloader.installedFile(model, directory).length(),
                        dimension = model.dimension,
                    )
                },
                onFailure = { throwable ->
                    LocalModelState.Failed(
                        throwable.message?.takeIf { it.isNotBlank() } ?: "模型加载失败",
                    )
                },
            )
        }
    }

    /** 取消之后把状态落回「下了多少」。 */
    private fun refreshAfterCancel() {
        val partial = downloader.partialFile(model, directory)
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
        engine.close()
        val installed = downloader.installedFile(model, directory)
        val partial = downloader.partialFile(model, directory)
        val removed = listOf(installed, partial).map { file ->
            !file.exists() || file.delete()
        }.all { it }

        if (!removed) {
            Log.w(TAG, "部分模型文件删除失败")
        }
        _state.value = LocalModelState.NotInstalled
    }

    override suspend fun ensureEngineReady(): Result<LocalEmbeddingEngine> {
        engine.takeIf { it.isReady }?.let { return Result.success(it) }

        val installed = downloader.installedFile(model, directory)
        if (!installed.isFile) {
            return Result.failure(IllegalStateException("本地模型还没下载，请到「向量模型」里先下载"))
        }

        return engine.load(installed).map { engine }
            .onFailure { throwable ->
                Log.e(TAG, "加载本地模型失败", throwable)
            }
    }

    private companion object {
        const val TAG = "LocalModelRepository"
    }
}
