package com.focussupervisor.app.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.focussupervisor.app.appContainer
import com.focussupervisor.app.core.network.AiClientException
import com.focussupervisor.app.core.network.PromptCacheStats
import com.focussupervisor.app.domain.model.AiConfig
import com.focussupervisor.app.domain.model.AiPreset
import com.focussupervisor.app.domain.model.AiPresetDefaults
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 「AI 配置中心」面板的状态。
 *
 * 编辑态（[draft]）与已保存态（[presets]）是**分开**的两份数据：用户改了 Base URL
 * 但还没点「保存当前」时，输入框里显示的是草稿，预设列表里还是磁盘上那一份。
 *
 * @param isSaving 正在写盘
 * @param isTesting 正在测试连接
 * @param message 提示信息；null 表示没有
 * @param isMessageError true 表示 [message] 是错误（显示为提醒色）
 * @param isDirty 草稿与已保存内容是否存在差异
 */
data class AiConfigUiState(
    val presets: List<AiPreset> = emptyList(),
    val selectedPresetId: String = "",
    val draftName: String = "",
    val draft: AiConfig = AiConfig(
        baseUrl = "",
        apiKey = "",
        model = "",
        temperature = AiConfig.DEFAULT_TEMPERATURE,
    ),
    val isApiKeyVisible: Boolean = false,
    val isFetchingModels: Boolean = false,
    val isTesting: Boolean = false,
    val isSaving: Boolean = false,
    val fetchedModels: List<String> = emptyList(),
    val isModelPickerVisible: Boolean = false,
    val message: String? = null,
    val isMessageError: Boolean = true,
    val isDirty: Boolean = false,
    /**
     * 最近一次请求的上下文缓存命中情况。[com.focussupervisor.app.core.network.PromptCacheStats]
     * 为 null 表示**端点没上报**，不是「没命中」。
     */
    val cacheStats: PromptCacheStats? = null,
) {
    val selectedPreset: AiPreset? get() = presets.firstOrNull { it.id == selectedPresetId }

    val isSelectedBuiltIn: Boolean
        get() = selectedPresetId in AiPresetDefaults.BUILT_IN_IDS

    /** 能不能发起测试或保存：地址与模型都填了，且没有正在进行的请求。 */
    val isBusy: Boolean get() = isTesting || isFetchingModels || isSaving

    val canTest: Boolean get() = draft.isUsable && !isBusy

    val canSave: Boolean get() = selectedPresetId.isNotBlank() && !isBusy
}

/**
 * AI 配置面板的状态持有者。
 *
 * ===========================================================================
 * 「测试连接」与「保存」是两件独立的事
 * ===========================================================================
 * 上一版把测试连接做成了「测试成功就自动保存、自动关面板」。那是错的，原因有两个：
 *
 *  1. **用户在点按钮之前无法预期它会写盘**。他可能只是想试试某个地址通不通，
 *     结果当前预设被悄悄改掉了。
 *  2. **面板一关，失败信息就没有地方显示了**。用户在配置页里连试三次都失败，
 *     每次都看不到原因，只能靠聊天记录里那条系统胶囊 —— 而归因必须发生在他
 *     改配置的那个界面上。
 *
 * 现在：保存只保存，测试只测试。测试成功显示一行灰色的「握手成功」，失败显示
 * 低饱和红字，**面板始终留在原地**。
 */
class AiConfigViewModel(application: Application) : AndroidViewModel(application) {

    private val container = application.appContainer
    private val repository = container.aiConfig
    private val client = container.openAiClient
    private val policy = container.policy

    private val _uiState = MutableStateFlow(AiConfigUiState())
    val uiState: StateFlow<AiConfigUiState> = _uiState.asStateFlow()

    private var fetchJob: Job? = null

    init {
        observeRepository()
        observePromptCache()
    }

    /**
     * 观测最近一次请求的缓存命中情况。
     *
     * 放在这个面板里，是因为它衡量的是**提示词排布**的代价，而人设与前置提示正是
     * 在这个面板里配置的 —— 「我改了这几个字，命中率掉没掉」应该能在同一个界面里看到。
     */
    private fun observePromptCache() {
        viewModelScope.launch {
            container.promptCache.last.collect { stats ->
                _uiState.update { it.copy(cacheStats = stats) }
            }
        }
    }

    private fun observeRepository() {
        viewModelScope.launch {
            repository.presets.collect { presets ->
                _uiState.update { state ->
                    val stillSelected = presets.any { it.id == state.selectedPresetId }
                    if (!stillSelected && presets.isNotEmpty()) {
                        val fallback = presets.first()
                        state.copy(
                            presets = presets,
                            selectedPresetId = fallback.id,
                            draftName = fallback.name,
                            draft = fallback.config,
                            isDirty = false,
                        )
                    } else {
                        state.copy(presets = presets).withRecalculatedDirty()
                    }
                }
            }
        }

        viewModelScope.launch {
            repository.selectedPresetId.collect { selectedId ->
                _uiState.update { state ->
                    if (selectedId == state.selectedPresetId) return@update state
                    val preset = state.presets.firstOrNull { it.id == selectedId }
                    if (preset == null) {
                        state.copy(selectedPresetId = selectedId)
                    } else {
                        state.copy(
                            selectedPresetId = preset.id,
                            draftName = preset.name,
                            draft = preset.config,
                            message = null,
                            fetchedModels = emptyList(),
                            isModelPickerVisible = false,
                            isDirty = false,
                        )
                    }
                }
            }
        }
    }

    /**
     * 面板每次打开时把草稿重置成磁盘上的那一份。
     *
     * ViewModel 的寿命比 Sheet 长，用户上次没保存就关掉面板，下次打开看到的会是
     * 那份被丢弃的草稿。重置之后语义就清楚了：打开面板 = 看到真实生效的配置。
     */
    fun onSheetOpened() {
        val presets = repository.presets.value
        val current = presets.firstOrNull { it.id == repository.selectedPresetId.value }
            ?: presets.firstOrNull()

        _uiState.update { state ->
            if (current == null) {
                state.copy(message = null)
            } else {
                state.copy(
                    selectedPresetId = current.id,
                    draftName = current.name,
                    draft = current.config,
                    message = null,
                    isMessageError = true,
                    fetchedModels = emptyList(),
                    isModelPickerVisible = false,
                    isTesting = false,
                    isFetchingModels = false,
                    isSaving = false,
                    isDirty = false,
                )
            }
        }
    }

    // -----------------------------------------------------------------------
    // 输入
    // -----------------------------------------------------------------------

    fun onPresetSelected(presetId: String) {
        viewModelScope.launch { repository.selectPreset(presetId) }
    }

    fun onNameChange(value: String) = editDraft(name = value)

    fun onBaseUrlChange(value: String) = editDraft { it.copy(baseUrl = value.trim()) }

    fun onApiKeyChange(value: String) = editDraft { it.copy(apiKey = value.trim()) }

    fun onModelChange(value: String) = editDraft { it.copy(model = value.trim()) }

    /**
     * 前置提示。
     *
     * 刻意**不 trim**：用户可能故意用空行开头做视觉分隔，而那几行会原样进入提示词。
     * 只做长度截断，防止有人粘进来一整篇文档把上下文预算挤爆。
     */
    fun onPrePromptChange(value: String) =
        editDraft { it.copy(prePrompt = value.take(MAX_PRE_PROMPT_LENGTH)) }

    /**
     * 温度滑块。
     *
     * 手动吸附到 0.1 的整数倍，而不是只依赖 `Slider` 的 `steps`：
     * `steps` 只保证「手指停在离散点上」，浮点累积误差仍然会让实际值变成
     * 0.7000001 这种数；显示出来就是「0.7000001」，写进配置也脏。
     */
    fun onTemperatureChange(value: Float) {
        val snapped = (value.coerceIn(AiConfig.MIN_TEMPERATURE, AiConfig.MAX_TEMPERATURE) * 10)
            .roundToInt() / 10f
        editDraft { it.copy(temperature = snapped) }
    }

    fun toggleApiKeyVisibility() {
        _uiState.update { it.copy(isApiKeyVisible = !it.isApiKeyVisible) }
    }

    fun onModelPickerDismiss() {
        _uiState.update { it.copy(isModelPickerVisible = false) }
    }

    fun onModelPicked(model: String) {
        editDraft { it.copy(model = model) }
        _uiState.update { it.copy(isModelPickerVisible = false, message = null) }
    }

    fun onMessageDismissed() {
        _uiState.update { it.copy(message = null) }
    }

    // -----------------------------------------------------------------------
    // 拉取模型
    // -----------------------------------------------------------------------

    /**
     * 拉取端点支持的模型列表。
     *
     * 失败时把错误留在面板里，**不关闭面板** —— 用户正在这里改配置，
     * 把他弹出去等于让他从头再来一遍。
     */
    fun fetchModels() {
        val draft = _uiState.value.draft
        if (draft.baseUrl.isBlank()) {
            showMessage("请先填写 Base URL", isError = true)
            return
        }

        fetchJob?.cancel()
        _uiState.update {
            it.copy(isFetchingModels = true, message = null, isModelPickerVisible = false)
        }

        fetchJob = viewModelScope.launch {
            val result = client.fetchModels(draft.baseUrl, draft.apiKey)
            _uiState.update { state ->
                result.fold(
                    onSuccess = { models ->
                        when {
                            models.isEmpty() -> state.copy(
                                isFetchingModels = false,
                                fetchedModels = emptyList(),
                                isModelPickerVisible = false,
                                message = "端点没有返回任何模型，请确认地址是否正确",
                                isMessageError = true,
                            )

                            // 只有一个模型时不用弹选择器，直接填上更省一步。
                            models.size == 1 -> state.copy(
                                isFetchingModels = false,
                                fetchedModels = models,
                                isModelPickerVisible = false,
                                draft = state.draft.copy(model = models.first()),
                                message = "已填入唯一可用模型",
                                isMessageError = false,
                            ).withRecalculatedDirty()

                            else -> state.copy(
                                isFetchingModels = false,
                                fetchedModels = models,
                                isModelPickerVisible = true,
                                message = "拉到 ${models.size} 个模型，点一个填进去",
                                isMessageError = false,
                            )
                        }
                    },
                    onFailure = { throwable ->
                        state.copy(
                            isFetchingModels = false,
                            fetchedModels = emptyList(),
                            isModelPickerVisible = false,
                            message = friendlyMessage(throwable),
                            isMessageError = true,
                        )
                    },
                )
            }
        }
    }

    // -----------------------------------------------------------------------
    // 保存
    // -----------------------------------------------------------------------

    /** 把当前草稿写回选中的预设。**只写盘，不做任何别的动作。** */
    fun saveCurrentPreset() {
        val state = _uiState.value
        if (state.selectedPresetId.isBlank()) {
            showMessage("没有可保存的预设，请先新建一个", isError = true)
            return
        }
        if (state.isSaving) return

        val preset = AiPreset(
            id = state.selectedPresetId,
            name = state.draftName.ifBlank { state.selectedPreset?.name.orEmpty() },
            config = state.draft,
        )

        _uiState.update { it.copy(isSaving = true, message = null) }
        viewModelScope.launch {
            val ok = repository.savePreset(preset)
            _uiState.update {
                it.copy(
                    isSaving = false,
                    isDirty = if (ok) false else it.isDirty,
                    message = if (ok) "已保存到本地" else "保存失败，请稍后重试",
                    isMessageError = !ok,
                )
            }
        }
    }

    /**
     * 新建一个预设。
     *
     * 行为是**克隆当前草稿**而不是给一张白纸：绝大多数情况下用户是想「在同一个端点
     * 上换一个模型」，克隆之后只要改模型名就行，比重新粘一遍 URL 和 Key 省事得多。
     */
    fun createPreset() {
        viewModelScope.launch {
            val created = repository.createPreset(
                name = AiPresetDefaults.CUSTOM_NAME,
                config = _uiState.value.draft,
            )
            if (created != null) {
                _uiState.update {
                    it.copy(
                        selectedPresetId = created.id,
                        draftName = created.name,
                        draft = created.config,
                        isDirty = false,
                        isModelPickerVisible = false,
                        fetchedModels = emptyList(),
                        message = "已新建预设",
                        isMessageError = false,
                    )
                }
            } else {
                showMessage("新建失败，请稍后重试", isError = true)
            }
        }
    }

    /** 删除当前预设。内置预置会被仓库拒绝。 */
    fun deleteCurrentPreset() {
        val presetId = _uiState.value.selectedPresetId
        if (presetId in AiPresetDefaults.BUILT_IN_IDS) {
            showMessage("内置预设不可删除", isError = true)
            return
        }

        viewModelScope.launch {
            if (repository.deletePreset(presetId)) {
                showMessage("已删除预设", isError = false)
            } else {
                showMessage("删除失败，请稍后重试", isError = true)
            }
        }
    }

    // -----------------------------------------------------------------------
    // 测试连接
    // -----------------------------------------------------------------------

    /**
     * 测试连接。**只测，不保存，不关面板。**
     *
     * 往会话流里插一条系统胶囊（让聊天记录里留下「哪一刻握手成功过」），
     * 同时在面板内显示结果 —— 两个地方都要有，因为它们服务不同的场景：
     * 胶囊供回看，面板内的文字供当场判断下一步改什么。
     */
    fun testConnection() {
        val state = _uiState.value
        if (!state.draft.isUsable) {
            showMessage("Base URL 与模型名都不能为空", isError = true)
            return
        }
        if (state.isTesting) return

        _uiState.update { it.copy(isTesting = true, message = null, isModelPickerVisible = false) }

        viewModelScope.launch {
            val result = client.testConnection(state.draft)
            result.fold(
                onSuccess = { reply ->
                    policy.publishNotice("AI 终端握手成功，当前模型：${state.draft.model}")
                    _uiState.update {
                        it.copy(
                            isTesting = false,
                            message = buildString {
                                append("握手成功")
                                if (reply.isNotBlank()) append(" · 模型回了：").append(reply.take(40))
                            },
                            isMessageError = false,
                        )
                    }
                },
                onFailure = { throwable ->
                    _uiState.update {
                        it.copy(
                            isTesting = false,
                            message = friendlyMessage(throwable),
                            isMessageError = true,
                        )
                    }
                },
            )
        }
    }

    // -----------------------------------------------------------------------
    // 内部工具
    // -----------------------------------------------------------------------

    private fun editDraft(name: String) {
        _uiState.update { it.copy(draftName = name, message = null).withRecalculatedDirty() }
    }

    private fun editDraft(transform: (AiConfig) -> AiConfig) {
        _uiState.update { state ->
            state.copy(
                draft = transform(state.draft),
                message = null,
                isModelPickerVisible = false,
            ).withRecalculatedDirty()
        }
    }

    private fun showMessage(text: String, isError: Boolean) {
        _uiState.update { it.copy(message = text, isMessageError = isError) }
    }

    /** 重算「草稿是否与已保存内容不同」，界面据此决定要不要把保存按钮点亮。 */
    private fun AiConfigUiState.withRecalculatedDirty(): AiConfigUiState {
        val saved = presets.firstOrNull { it.id == selectedPresetId } ?: return copy(isDirty = false)
        return copy(isDirty = saved.name != draftName || saved.config != draft)
    }

    /**
     * 把异常翻译成一句可以直接显示的中文。
     *
     * 网络层已经做过一次翻译（[AiClientException.message]），这里只负责兜住
     * 「万一漏了一个没被包装的异常」的情况 —— 界面上永远不该出现类名。
     */
    private fun friendlyMessage(throwable: Throwable): String = when (throwable) {
        is AiClientException -> throwable.message ?: "请求失败"
        else -> throwable.message?.takeIf { it.isNotBlank() }
            ?: "请求失败：${throwable.javaClass.simpleName}"
    }

    private companion object {
        const val MAX_PRE_PROMPT_LENGTH = 8000
    }
}
