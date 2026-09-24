package com.focussupervisor.app.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.focussupervisor.app.appContainer
import com.focussupervisor.app.data.repository.LocalModelState
import com.focussupervisor.app.domain.model.LocalEmbeddingModel
import com.focussupervisor.app.domain.model.LocalEmbeddingModels
import com.focussupervisor.app.domain.model.VisionEngineKind
import com.focussupervisor.app.domain.model.VisionSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale

/** 一类模型资源。 */
enum class ResourceKind {
    /** 向量模型：把文本变成向量，供记忆检索用。 */
    EMBEDDING,

    /** 识图引擎：把屏幕截图变成一句事实。 */
    VISION,
}

/**
 * 资源列表里的一项。
 *
 * 把两类模型的卡片压成同一个形状，是为了让「资源管理」这一页**真的能横向比较**：
 * 用户真正要回答的问题是「我这台手机该装哪几个、一共要占多少空间」，
 * 而几个长得不一样的列表回答不了这个问题。
 *
 * 这里曾经还有第三类：端侧对话模型（0.5B~3.8B 的 `.task`）。整类已经移除 ——
 * 一个几 GB 的模型在手机上给出的回答，配不上它占的空间和等待时间。
 * 向量与识图留在这一页，因为它们各自都真的在链路上干活：
 * 一个决定记忆检索的准确度，一个决定监督者看不看得见屏幕。
 */
data class ResourceItem(
    val id: String,
    val kind: ResourceKind,
    val title: String,
    val tier: String,
    val description: String,
    val sizeBytes: Long,
    val state: LocalModelState,
    /** 当前是否正在被使用（决定了「启用」那一行显示什么）。 */
    val isActive: Boolean,
    /** 非 null 表示这一项现在根本不可用，界面显示原因并禁用。 */
    val unavailableReason: String? = null,
) {
    val sizeLabel: String get() = if (sizeBytes <= 0L) "内置" else formatSize(sizeBytes)

    /** 是否已下载完成。 */
    val isInstalled: Boolean get() = state is LocalModelState.Ready

    /** 是否可以点「下载 / 继续」。 */
    val canDownload: Boolean get() = unavailableReason == null && state.canDownload
}

/** 资源管理页的状态。 */
data class ResourceUiState(
    val items: List<ResourceItem> = emptyList(),
    val message: String? = null,
    val isMessageError: Boolean = false,
) {
    /** 已占用的总空间。 */
    val usedBytes: Long get() = items.filter { it.isInstalled }.sumOf { it.sizeBytes }

    /** 全部下载完会占多少。用来给用户一个「全下要多少」的心理预期。 */
    val totalBytes: Long get() = items.filter { it.unavailableReason == null }.sumOf { it.sizeBytes }

    val embeddingItems: List<ResourceItem> get() = items.filter { it.kind == ResourceKind.EMBEDDING }
    val visionItems: List<ResourceItem> get() = items.filter { it.kind == ResourceKind.VISION }
}

/**
 * 资源管理页的状态持有者。
 *
 * ===========================================================================
 * 为什么把这几项收在一页里
 * ===========================================================================
 * 在这之前，向量模型在「向量模型」面板、识图在「注视监控」—— 两个入口各自管
 * 自己那一摊。单看每一处都合理，合起来就有一个说不通的问题：
 * **没有任何一页能回答「这些东西一共占了我多少空间」**。
 *
 * ===========================================================================
 * 它不持有下载状态，只是仓库的投影
 * ===========================================================================
 * 进度、状态机、引擎生命周期的作者是 `LocalModelRepository`。这里只把它**读**出来
 * 拼成卡片，转发用户的操作。两处各记一份进度，迟早会出现「界面显示还在下、
 * 实际早就下完了」。
 */
class ResourceViewModel(application: Application) : AndroidViewModel(application) {

    private val container = application.appContainer
    private val embeddingRepo = container.localModel

    private val _uiState = MutableStateFlow(ResourceUiState())
    val uiState: StateFlow<ResourceUiState> = _uiState.asStateFlow()

    init {
        // 来源的 Flow 一变就重算整张表。用 viewModelScope 拉起一条收集，
        // 而不是 combine：卡片是「投影」，重算是纯函数，没必要为它维护运算符链。
        viewModelScope.launch {
            embeddingRepo.state.collect { rebuild() }
        }
    }

    fun onSheetOpened() {
        embeddingRepo.refreshState()
        rebuild()
    }

    // -----------------------------------------------------------------------
    // 投影
    // -----------------------------------------------------------------------

    private fun rebuild() {
        val items = buildList {
            addAll(embeddingItems())
            addAll(visionItems())
        }
        // 分类顺序固定：向量 → 识图。它对应「记 / 看」这个从底层到表层的顺序，
        // 比按体积排更容易让人建立心理模型。
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
                state = state,
                isActive = state is LocalModelState.Ready &&
                    container.aiConfig.embeddingConfigNow().useLocalModel,
            ),
        )
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

            // 识图的两项都不可下载（OCR 内置、VLM 无运行时）。
            ResourceKind.VISION -> _uiState.update {
                it.copy(message = item.unavailableReason ?: "这一项不需要下载", isMessageError = true)
            }
        }
    }

    fun cancel(item: ResourceItem) {
        when (item.kind) {
            ResourceKind.EMBEDDING -> embeddingRepo.cancelDownload()
            ResourceKind.VISION -> Unit
        }
    }

    fun delete(item: ResourceItem) {
        when (item.kind) {
            ResourceKind.EMBEDDING -> {
                embeddingRepo.deleteModel()
                _uiState.update { it.copy(message = "已删除向量模型", isMessageError = false) }
            }

            ResourceKind.VISION -> _uiState.update {
                it.copy(message = "这一项是内置组件，不能删除", isMessageError = true)
            }
        }
    }

    fun dismissMessage() {
        _uiState.update { it.copy(message = null) }
    }
}

/**
 * 字节数转人话。
 *
 * 与 `ResourceSheet` 里那个同名的私有函数是两处独立的实现，这里没有合并：
 * 一个服务于 `sizeLabel`（数据类的属性，不能依赖界面层），
 * 一个服务于界面上的临时数字。为省十几行把界面层的函数提上来，
 * 会让领域模型反过来依赖界面 —— 那个方向是错的。
 */
private fun formatSize(bytes: Long): String = when {
    bytes <= 0L -> "0 B"
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    bytes < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.0f MB", bytes / 1024.0 / 1024.0)
    else -> String.format(Locale.US, "%.1f GB", bytes / 1024.0 / 1024.0 / 1024.0)
}
