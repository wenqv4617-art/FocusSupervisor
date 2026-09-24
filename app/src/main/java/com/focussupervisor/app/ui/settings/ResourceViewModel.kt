package com.focussupervisor.app.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.focussupervisor.app.appContainer
import com.focussupervisor.app.data.repository.BenchmarkResult
import com.focussupervisor.app.data.repository.LocalModelState
import com.focussupervisor.app.domain.model.LocalEmbeddingModel
import com.focussupervisor.app.domain.model.LocalEmbeddingModels
import com.focussupervisor.app.domain.model.LocalLlmModel
import com.focussupervisor.app.domain.model.LocalLlmModels
import com.focussupervisor.app.domain.model.VisionEngineKind
import com.focussupervisor.app.domain.model.VisionSource
import com.focussupervisor.app.domain.model.formatSize
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 一类模型资源。 */
enum class ResourceKind {
    /** 向量模型：把文本变成向量，供记忆检索用。 */
    EMBEDDING,

    /** 对话模型：端侧跑的自律管家本体。 */
    LLM,

    /** 识图引擎：把屏幕截图变成一句事实。 */
    VISION,
}

/**
 * 资源列表里的一项。
 *
 * 把三类模型的卡片压成同一个形状，是为了让「资源管理」这一页**真的能横向比较**：
 * 用户真正要回答的问题是「我这台手机该装哪几个、一共要占多少空间」，
 * 而三个长得不一样的列表回答不了这个问题。
 */
data class ResourceItem(
    val id: String,
    val kind: ResourceKind,
    val title: String,
    val tier: String,
    val description: String,
    val sizeBytes: Long,
    val recommendedRamGb: Int,
    val state: LocalModelState,
    /** 当前是否正在被使用（决定了「启用」按钮是常亮还是可点）。 */
    val isActive: Boolean,
    /** 非 null 表示这一项现在根本不可用，界面显示原因并禁用。 */
    val unavailableReason: String? = null,
    /** 只有对话模型会跑分。 */
    val benchmark: BenchmarkResult? = null,
) {
    val sizeLabel: String get() = if (sizeBytes <= 0L) "内置" else formatSize(sizeBytes)

    /** 是否已下载完成。 */
    val isInstalled: Boolean get() = state is LocalModelState.Ready

    /** 是否可以点「下载 / 继续」。 */
    val canDownload: Boolean get() = unavailableReason == null && state.canDownload

    /** 是否支持跑分。目前只有对话模型有这套指标。 */
    val canBenchmark: Boolean get() = unavailableReason == null && isInstalled
}

/** 资源管理页的状态。 */
data class ResourceUiState(
    val items: List<ResourceItem> = emptyList(),
    val benchmarkTargetId: String? = null,
    val message: String? = null,
    val isMessageError: Boolean = false,
) {
    /** 已占用的总空间。 */
    val usedBytes: Long get() = items.filter { it.isInstalled }.sumOf { it.sizeBytes }

    /** 全部下载完会占多少。用来给用户一个「全下要 7.4GB」的心理预期。 */
    val totalBytes: Long get() = items.filter { it.unavailableReason == null }.sumOf { it.sizeBytes }

    val llmItems: List<ResourceItem> get() = items.filter { it.kind == ResourceKind.LLM }
    val embeddingItems: List<ResourceItem> get() = items.filter { it.kind == ResourceKind.EMBEDDING }
    val visionItems: List<ResourceItem> get() = items.filter { it.kind == ResourceKind.VISION }
}

/**
 * 资源管理页的状态持有者。
 *
 * ===========================================================================
 * 为什么要把三类模型收在一页里
 * ===========================================================================
 * 在这之前，向量模型在「向量模型」面板、对话模型在「AI 配置」、识图在「注视监控」——
 * 三个入口各自管自己那一摊。单看每一处都合理，合起来就有一个说不通的问题：
 * **没有任何一页能回答「这些东西一共占了我多少空间」**。
 *
 * 而端侧模型恰恰是这个应用里唯一会吃掉几个 GB 的功能。用户在下第三个模型之前，
 * 最需要知道的就是前面两个已经占了多少 —— 这一页存在的全部理由就是这个。
 *
 * ===========================================================================
 * 它不持有下载状态，只是三个仓库的投影
 * ===========================================================================
 * 进度、状态机、引擎生命周期的作者分别是 `LocalModelRepository` 与
 * `LocalLlmRepository`。这里只把它们**读**出来拼成卡片，转发用户的操作。
 * 两处各记一份进度，迟早会出现「界面显示还在下、实际早就下完了」。
 */
class ResourceViewModel(application: Application) : AndroidViewModel(application) {

    private val container = application.appContainer
    private val embeddingRepo = container.localModel
    private val llmRepo = container.localLlm

    private val _uiState = MutableStateFlow(ResourceUiState())
    val uiState: StateFlow<ResourceUiState> = _uiState.asStateFlow()

    init {
        // 三个来源各自的 Flow 都要订阅，任意一个变化就重算整张表。
        // 用 viewModelScope 拉起三条收集，而不是 combine：卡片是「投影」，
        // 重算是纯函数，没必要为它维护一个三路合并的运算符链。
        viewModelScope.launch {
            embeddingRepo.state.collect { rebuild() }
        }
        viewModelScope.launch {
            llmRepo.state.collect { rebuild() }
        }
        viewModelScope.launch {
            llmRepo.selected.collect { rebuild() }
        }
        viewModelScope.launch {
            llmRepo.benchmark.collect { rebuild() }
        }
    }

    fun onSheetOpened() {
        embeddingRepo.refreshState()
        llmRepo.refreshState()
        rebuild()
    }

    // -----------------------------------------------------------------------
    // 投影
    // -----------------------------------------------------------------------

    private fun rebuild() {
        val items = buildList {
            addAll(embeddingItems())
            addAll(llmItems())
            addAll(visionItems())
        }
        // 分类顺序固定：向量 → 对话 → 识图。它对应「记忆 / 说话 / 看」这个
        // 从底层到表层的顺序，比按体积排更容易让人建立心理模型。
        _uiState.update { it.copy(items = items) }
    }

    private fun embeddingItems(): List<ResourceItem> {
        val model: LocalEmbeddingModel = LocalEmbeddingModels.DEFAULT
        val state = embeddingRepo.state.value
        return listOf(
            ResourceItem(
                id = model.id,
                kind = ResourceKind.EMBEDDING,
                title = model.name,
                tier = "384 维",
                description = model.description,
                sizeBytes = model.sizeBytes,
                // 向量模型的内存占用很小（几十 MB 常驻），不设运存门槛。
                recommendedRamGb = 0,
                state = state,
                isActive = state is LocalModelState.Ready &&
                    container.aiConfig.embeddingConfigNow().useLocalModel,
            ),
        )
    }

    private fun llmItems(): List<ResourceItem> {
        val selectedId = llmRepo.selected.value.modelId
        val benchmark = llmRepo.benchmark.value
        return LocalLlmModels.ALL.map { model: LocalLlmModel ->
            ResourceItem(
                id = model.modelId,
                kind = ResourceKind.LLM,
                title = model.displayName,
                tier = "${model.tierLabel} · ${model.parameterTier}",
                description = model.description,
                sizeBytes = model.fileSizeBytes,
                recommendedRamGb = model.recommendedRamGb,
                // 只有**当前选中**的那个模型的状态是实时的；其余一律显示未下载，
                // 因为仓库只跟踪选中的那一个。这是刻意的取舍：同时跟踪四个模型的
                // 下载状态要四份状态机，而用户一次只会下其中一个。
                state = if (model.modelId == selectedId) {
                    llmRepo.state.value
                } else {
                    LocalModelState.NotInstalled
                },
                isActive = model.modelId == selectedId &&
                    container.aiConfig.activeConfigNow().useLocalModel,
                benchmark = benchmark?.takeIf { it.modelId == model.modelId },
            )
        }
    }

    private fun visionItems(): List<ResourceItem> =
        VisionEngineKind.entries.map { engine ->
            ResourceItem(
                id = engine.name,
                kind = ResourceKind.VISION,
                title = engine.label,
                tier = engine.tagline,
                description = engine.detail,
                // OCR 是内置组件，标 0 表示「不需要额外下载」。
                sizeBytes = 0L,
                recommendedRamGb = 0,
                state = if (engine.isAvailable) {
                    LocalModelState.Ready(0L, 0)
                } else {
                    LocalModelState.NotInstalled
                },
                isActive = engine.isAvailable &&
                    container.aiConfig.visionConfigNow().let {
                        it.source == VisionSource.LOCAL && it.localEngine == engine
                    },
                unavailableReason = engine.unavailableReason,
            )
        }

    // -----------------------------------------------------------------------
    // 操作
    // -----------------------------------------------------------------------

    /** 下载或继续下载。 */
    fun download(item: ResourceItem) {
        when (item.kind) {
            ResourceKind.EMBEDDING -> embeddingRepo.startDownload()

            ResourceKind.LLM -> {
                // 下载之前先把它设为选中：仓库只跟踪选中模型的状态，
                // 不先选中的话进度条会挂在一个没人看的模型上。
                llmRepo.select(item.id)
                llmRepo.startDownload()
            }

            // 识图的两项都不可下载（OCR 内置、VLM 无运行时）。
            ResourceKind.VISION -> _uiState.update {
                it.copy(message = item.unavailableReason ?: "这一项不需要下载", isMessageError = true)
            }
        }
    }

    fun cancel(item: ResourceItem) {
        when (item.kind) {
            ResourceKind.EMBEDDING -> embeddingRepo.cancelDownload()
            ResourceKind.LLM -> llmRepo.cancelDownload()
            ResourceKind.VISION -> Unit
        }
    }

    fun delete(item: ResourceItem) {
        when (item.kind) {
            ResourceKind.EMBEDDING -> {
                embeddingRepo.deleteModel()
                _uiState.update { it.copy(message = "已删除向量模型", isMessageError = false) }
            }

            ResourceKind.LLM -> {
                llmRepo.deleteModel()
                _uiState.update { it.copy(message = "已删除 ${item.title}", isMessageError = false) }
            }

            ResourceKind.VISION -> _uiState.update {
                it.copy(message = "这一项是内置组件，不能删除", isMessageError = true)
            }
        }
    }

    /** 把某个对话模型设为当前使用的那一个。 */
    fun activateLlm(item: ResourceItem) {
        llmRepo.select(item.id)
        viewModelScope.launch {
            // 用 updateActiveConfig 而不是「取出配置 → copy → 存回去」：
            // 后者要先取一次快照，而用户在跑分、下载这些耗时操作期间完全可能
            // 改了别的东西（前置提示、温度），用旧快照写回去会静默覆盖掉那处改动。
            // 这个方法是就地改，只动我们指定的两个字段。
            val saved = container.aiConfig.updateActiveConfig { current ->
                current.copy(useLocalModel = true, localModelId = item.id)
            }
            _uiState.update {
                if (saved) {
                    it.copy(message = "已切换到 ${item.title}", isMessageError = false)
                } else {
                    it.copy(message = "保存失败，请重试", isMessageError = true)
                }
            }
            rebuild()
        }
    }

    /** 跑一次基准测试。 */
    fun benchmark(item: ResourceItem) {
        if (item.kind != ResourceKind.LLM) return
        if (_uiState.value.benchmarkTargetId != null) return

        llmRepo.select(item.id)
        _uiState.update { it.copy(benchmarkTargetId = item.id, message = "正在跑分，请稍候…", isMessageError = false) }

        viewModelScope.launch {
            val result = llmRepo.runBenchmark()
            _uiState.update { state ->
                result.fold(
                    onSuccess = {
                        state.copy(
                            benchmarkTargetId = null,
                            message = null,
                        )
                    },
                    onFailure = { throwable ->
                        state.copy(
                            benchmarkTargetId = null,
                            message = throwable.message?.takeIf { it.isNotBlank() }
                                ?: "跑分失败",
                            isMessageError = true,
                        )
                    },
                )
            }
            rebuild()
        }
    }

    fun dismissMessage() {
        _uiState.update { it.copy(message = null) }
    }
}
