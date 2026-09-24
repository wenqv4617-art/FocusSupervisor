package com.focussupervisor.app.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.focussupervisor.app.appContainer
import com.focussupervisor.app.core.network.AiClientException
import com.focussupervisor.app.data.repository.LocalModelState
import com.focussupervisor.app.domain.model.EmbeddingConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 向量模型配置页的状态。 */
data class EmbeddingUiState(
    val draft: EmbeddingConfig = EmbeddingConfig(),
    val isApiKeyVisible: Boolean = false,
    val isSaving: Boolean = false,
    val isTesting: Boolean = false,
    val message: String? = null,
    val isMessageError: Boolean = true,
    /** 本地模型的下载 / 就绪状态。 */
    val modelState: LocalModelState = LocalModelState.NotInstalled,
) {
    val isBusy: Boolean get() = isSaving || isTesting

    /** 走本地模型。 */
    val isLocal: Boolean get() = draft.useLocalModel

    /** 能测：配置齐了，且没在忙。 */
    val canTest: Boolean get() = draft.isUsable && !isBusy

    /** 本地模型是否已经下好。 */
    val isModelReady: Boolean get() = modelState is LocalModelState.Ready
}

/**
 * 向量模型配置页的状态持有者。
 *
 * ===========================================================================
 * 这一页现在有两个分支，而且它们不是「主备」关系
 * ===========================================================================
 * ```
 *   在线端点   → 填 URL / Key / 模型名，测试连接会真的发一次请求
 *   本地模型   → 下载一个 22MB 的 ONNX，之后完全离线；测试是真的在本机跑一次推理
 * ```
 *
 * 两者的取舍不同：在线的好处是「不用下东西、模型更强」，本地的好处是
 * 「离线、不花钱、数据不出手机」。所以界面上是一个平级的分段选择器，
 * 而不是把本地模型做成「在线配置下面的一个高级选项」——
 * 那会暗示它次要，而对这个应用来说恰恰相反。
 *
 * ===========================================================================
 * 下载状态的作者不是这里
 * ===========================================================================
 * [LocalModelState] 真正的作者是 `LocalModelRepository`：下载可能在用户关掉这个
 * 面板之后继续进行（他会切出去干别的），状态必须活得比 ViewModel 久。
 * 这里只**订阅**它，不做任何自己的进度记账 —— 两处各记一份，迟早会出现
 * 「界面显示还在下、实际早就下完了」。
 */
class EmbeddingViewModel(application: Application) : AndroidViewModel(application) {

    private val container = application.appContainer
    private val repository = container.aiConfig
    private val client = container.openAiClient
    private val localModel = container.localModel

    private val _uiState = MutableStateFlow(EmbeddingUiState())
    val uiState: StateFlow<EmbeddingUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.embeddingConfig.collect { config ->
                // 只在草稿还没被改过时同步，避免磁盘回环把用户正在输入的内容冲掉。
                if (_uiState.value.draft == EmbeddingConfig()) {
                    _uiState.update { it.copy(draft = config) }
                }
            }
        }

        viewModelScope.launch {
            localModel.state.collect { state ->
                _uiState.update { it.copy(modelState = state) }
            }
        }
    }

    /** 每次打开面板都重新对齐磁盘内容与磁盘上的模型文件。 */
    fun onSheetOpened() {
        _uiState.update {
            it.copy(
                draft = repository.embeddingConfigNow(),
                message = null,
                isMessageError = true,
                isSaving = false,
                isTesting = false,
            )
        }
        // 用磁盘实际情况校正状态：用户可能从文件管理器里删掉了模型文件，
        // 也可能上次下载完就退出了应用，那时内存里的状态还停在「没下载」。
        localModel.refreshState()
    }

    // -----------------------------------------------------------------------
    // 在线端点
    // -----------------------------------------------------------------------

    fun onBaseUrlChange(value: String) = edit { it.copy(baseUrl = value.trim()) }

    fun onApiKeyChange(value: String) = edit { it.copy(apiKey = value.trim()) }

    fun onModelChange(value: String) = edit { it.copy(model = value.trim()) }

    fun toggleApiKeyVisibility() {
        _uiState.update { it.copy(isApiKeyVisible = !it.isApiKeyVisible) }
    }

    /** 填入本地 Ollama 的建议值。 */
    fun applyOllamaPreset() {
        edit {
            it.copy(
                baseUrl = EmbeddingConfig.OLLAMA_BASE_URL,
                model = EmbeddingConfig.OLLAMA_DEFAULT_MODEL,
            )
        }
    }

    // -----------------------------------------------------------------------
    // 本地模型
    // -----------------------------------------------------------------------

    /** 切换「在线 / 本地」。只改草稿，用户还要点保存才真正生效。 */
    fun selectSource(useLocal: Boolean) {
        edit { it.copy(useLocalModel = useLocal) }
    }

    fun startModelDownload() {
        if (_uiState.value.modelState is LocalModelState.Ready) {
            _uiState.update { it.copy(message = "模型已经下载好了", isMessageError = false) }
            return
        }
        localModel.startDownload()
    }

    fun cancelModelDownload() {
        localModel.cancelDownload()
    }

    fun deleteModel() {
        localModel.deleteModel()
        _uiState.update {
            it.copy(message = "模型已删除，本地向量暂时不可用", isMessageError = false)
        }
    }

    // -----------------------------------------------------------------------
    // 保存与测试
    // -----------------------------------------------------------------------

    fun save() {
        val draft = _uiState.value.draft
        if (_uiState.value.isSaving) return

        // 选了本地但模型还没下好：**允许保存**（用户可能想先定好配置、稍后再下），
        // 但要明确说出此刻的后果，免得他以为已经生效了。
        val warning = if (draft.useLocalModel && !_uiState.value.isModelReady) {
            "已保存。本地模型还没就绪，下好之前记忆会退回关键词匹配。"
        } else {
            null
        }

        _uiState.update { it.copy(isSaving = true, message = null) }
        viewModelScope.launch {
            val ok = repository.saveEmbeddingConfig(draft)
            _uiState.update {
                it.copy(
                    isSaving = false,
                    message = when {
                        !ok -> "保存失败，请稍后重试"
                        warning != null -> warning
                        else -> "已保存"
                    },
                    isMessageError = !ok,
                )
            }
        }
    }

    fun testConnection() {
        val draft = _uiState.value.draft
        if (!draft.isUsable) {
            _uiState.update {
                it.copy(
                    message = "在线端点需要 Base URL 与模型名；本地模型需要先下载好",
                    isMessageError = true,
                )
            }
            return
        }
        if (_uiState.value.isTesting) return

        _uiState.update { it.copy(isTesting = true, message = null) }
        viewModelScope.launch {
            // 走本地时「测试连接」这个名字不太准确，但它做的事是一样的：
            // 真的算一次向量，确认这条路通不通。界面上的按钮文案会跟着变。
            val result = if (draft.useLocalModel) {
                localModel.ensureEngineReady().mapCatching { engine ->
                    engine.embed(listOf(TEST_TEXT)).getOrThrow()
                }
            } else {
                client.embed(draft, listOf(TEST_TEXT))
            }

            _uiState.update { state ->
                result.fold(
                    onSuccess = { vectors ->
                        val dimension = vectors.firstOrNull()?.size ?: 0
                        state.copy(
                            isTesting = false,
                            message = when {
                                dimension <= 0 -> "请求成功但没拿到向量，检查模型名是否正确"
                                draft.useLocalModel -> "本地推理正常，维度 $dimension"
                                else -> "向量化成功，维度 $dimension"
                            },
                            isMessageError = dimension <= 0,
                        )
                    },
                    onFailure = { throwable ->
                        state.copy(
                            isTesting = false,
                            message = friendlyMessage(throwable),
                            isMessageError = true,
                        )
                    },
                )
            }
        }
    }

    private fun edit(transform: (EmbeddingConfig) -> EmbeddingConfig) {
        _uiState.update { it.copy(draft = transform(it.draft), message = null) }
    }

    private fun friendlyMessage(throwable: Throwable): String = when (throwable) {
        is AiClientException -> throwable.message ?: "请求失败"
        else -> throwable.message?.takeIf { it.isNotBlank() }
            ?: "请求失败：${throwable.javaClass.simpleName}"
    }

    private companion object {
        /** 测试用的一个词。够短，不会消耗额度。 */
        const val TEST_TEXT = "ping"
    }
}
