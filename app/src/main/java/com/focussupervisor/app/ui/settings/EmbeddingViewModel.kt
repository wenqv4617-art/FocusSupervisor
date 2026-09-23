package com.focussupervisor.app.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.focussupervisor.app.appContainer
import com.focussupervisor.app.core.network.AiClientException
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
) {
    val isBusy: Boolean get() = isSaving || isTesting

    /** 能测：地址与模型都填了，且没在忙。 */
    val canTest: Boolean get() = draft.isUsable && !isBusy
}

/**
 * 向量模型配置页的状态持有者。
 *
 * 与对话配置页一样：保存与测试是两件独立的事，测试不会写盘、也不会关面板。
 *
 * 「测试连接」的做法是**真的发一次向量化请求**（只发一个词）。为什么不只测
 * `/models`：那个接口在多数端点不需要鉴权，能列出来不代表 Key 有效、
 * 更不代表这个模型真的能转向量。发一次真请求才能把三件事一起验掉。
 */
class EmbeddingViewModel(application: Application) : AndroidViewModel(application) {

    private val container = application.appContainer
    private val repository = container.aiConfig
    private val client = container.openAiClient

    private val _uiState = MutableStateFlow(EmbeddingUiState())
    val uiState: StateFlow<EmbeddingUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.embeddingConfig.collect { config ->
                // 只在草稿还没被改过时同步，避免磁盘回环把用户正在输入的内容冲掉。
                // 这里用一个简单的判据：草稿为空就认为还没编辑过。
                if (_uiState.value.draft == EmbeddingConfig()) {
                    _uiState.update { it.copy(draft = config) }
                }
            }
        }
    }

    /** 每次打开面板都重新对齐磁盘内容。 */
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
    }

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

    fun save() {
        val draft = _uiState.value.draft
        if (_uiState.value.isSaving) return

        _uiState.update { it.copy(isSaving = true, message = null) }
        viewModelScope.launch {
            val ok = repository.saveEmbeddingConfig(draft)
            _uiState.update {
                it.copy(
                    isSaving = false,
                    message = if (ok) "已保存" else "保存失败，请稍后重试",
                    isMessageError = !ok,
                )
            }
        }
    }

    fun testConnection() {
        val draft = _uiState.value.draft
        if (!draft.isUsable) {
            _uiState.update {
                it.copy(message = "Base URL 与向量模型名都不能为空", isMessageError = true)
            }
            return
        }
        if (_uiState.value.isTesting) return

        _uiState.update { it.copy(isTesting = true, message = null) }
        viewModelScope.launch {
            val result = client.embed(draft, listOf(TEST_TEXT))
            _uiState.update { state ->
                result.fold(
                    onSuccess = { vectors ->
                        val dimension = vectors.firstOrNull()?.size ?: 0
                        state.copy(
                            isTesting = false,
                            message = if (dimension > 0) {
                                "向量化成功，维度 $dimension"
                            } else {
                                "请求成功但没拿到向量，检查模型名是否正确"
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
