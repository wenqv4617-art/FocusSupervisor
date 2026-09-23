package com.focussupervisor.app.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.focussupervisor.app.appContainer
import com.focussupervisor.app.core.network.AiClientException
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
 * 编辑态（[draft]）与已保存态（[presets]）是**分开**的两份数据，这是刻意的：
 * 用户改了 Base URL 但还没点「保存当前」时，输入框里显示的是草稿，而预设列表里
 * 显示的还是磁盘上那一份。把两者合成一份的话，用户每敲一个字符都会触发一次落盘。
 *
 * @param presets 已保存的全部预设
 * @param selectedPresetId 当前选中的预设
 * @param draftName 草稿的预设名
 * @param draft 草稿的端点配置
 * @param isApiKeyVisible Key 是否明文显示
 * @param isFetchingModels 正在拉取模型列表
 * @param isTesting 正在测试连接
 * @param fetchedModels 上一次「拉取」拿到的模型 id
 * @param isModelPickerVisible 是否展示模型选择列表
 * @param message 提示信息；null 表示没有
 * @param isMessageError true 表示 [message] 是错误（显示为提醒色），false 是普通提示
 * @param dismissRequested 握手成功后置位，Sheet 据此播完收起动画再关闭
 * @param isDirty 草稿与已保存内容是否存在差异
 * @param pickerTarget 「拉取」成功后，模型列表要填进哪个字段
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
    val fetchedModels: List<String> = emptyList(),
    val isModelPickerVisible: Boolean = false,
    val message: String? = null,
    val isMessageError: Boolean = true,
    val dismissRequested: Boolean = false,
    val isDirty: Boolean = false,
    val pickerTarget: ModelPickerTarget = ModelPickerTarget.CONVERSATION_MODEL,
) {
    /** 当前选中的预设，找不到时为 null。 */
    val selectedPreset: AiPreset? get() = presets.firstOrNull { it.id == selectedPresetId }

    /** 当前预设是不是内置的（内置的不能删）。 */
    val isSelectedBuiltIn: Boolean
        get() = selectedPresetId in AiPresetDefaults.BUILT_IN_IDS

    /** 能不能发起测试：地址与模型都填了，且没有正在进行的请求。 */
    val canTest: Boolean
        get() = draft.isUsable && !isTesting && !isFetchingModels

    /**
     * 模型选择器里高亮哪一项。
     *
     * 取决于这次「拉取」是为哪个字段发起的 —— 否则两个字段会同时高亮同一个模型名，
     * 用户根本分不清点下去会填到哪里。
     */
    fun currentPickerValue(): String = when (pickerTarget) {
        ModelPickerTarget.CONVERSATION_MODEL -> draft.model
        ModelPickerTarget.EMBEDDING_MODEL -> draft.embeddingModel
    }
}

/** 「拉取」按钮是为哪个模型字段服务的。 */
enum class ModelPickerTarget {
    CONVERSATION_MODEL,
    EMBEDDING_MODEL,
}

/**
 * 「AI 配置中心」的状态持有者。
 *
 * 分层的落地方式，正好在这里体现得最清楚：
 * ```
 *   AiConfigSheet（纯展示 + 收集输入）
 *        ↓ 调用
 *   AiConfigViewModel（编辑态、请求编排、消息提示）
 *        ↓ 调用
 *   AiConfigRepository（持久化）   OpenAiCompatibleClient（网络）
 * ```
 * Sheet 里没有任何一处直接碰 DataStore 或 OkHttp；网络错误也在这里被翻译成一句
 * 可以直接显示的中文，UI 只负责把它画成低饱和的红色文字。
 */
class AiConfigViewModel(application: Application) : AndroidViewModel(application) {

    private val container = application.appContainer
    private val repository = container.aiConfig
    private val client = container.openAiClient
    private val policy = container.policy

    private val _uiState = MutableStateFlow(AiConfigUiState())
    val uiState: StateFlow<AiConfigUiState> = _uiState.asStateFlow()

    /** 正在进行的模型拉取任务。重复点击时先取消上一个。 */
    private var fetchJob: Job? = null

    init {
        observeRepository()
    }

    /**
     * 订阅仓库。只跟随「选中项变了」这一件事刷新草稿，其余情况只更新预设列表。
     */
    private fun observeRepository() {
        viewModelScope.launch {
            repository.presets.collect { presets ->
                _uiState.update { state ->
                    val stillSelected = presets.any { it.id == state.selectedPresetId }
                    if (!stillSelected && presets.isNotEmpty()) {
                        // 选中的预设被删掉了（或首次加载），被动回落到第一个。
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
                            // 切换预设时清掉上一条提示与拉取结果 —— 它们属于上一个端点。
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
     * 面板每次打开时调用：把草稿重置成磁盘上的那一份。
     *
     * 为什么需要：ViewModel 的寿命比 Sheet 长（它挂在 Activity 的 ViewModelStore 上），
     * 用户上次没保存就关掉面板，下次打开看到的会是那份被丢弃的草稿。重置之后语义
     * 就清楚了 —— 打开面板 = 看到真实生效的配置。
     */
    fun onSheetOpened() {
        val current = repository.presets.value.firstOrNull {
            it.id == repository.selectedPresetId.value
        } ?: repository.presets.value.firstOrNull()

        _uiState.update { state ->
            if (current == null) {
                state.copy(dismissRequested = false, message = null)
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
                    dismissRequested = false,
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

    fun onEmbeddingModelChange(value: String) =
        editDraft { it.copy(embeddingModel = value.trim()) }

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
     * 0.7000001 这种数；显示出来就是「0.7000001」，很难看，写进配置也脏。
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
        _uiState.update {
            it.applyPickedModel(model)
                .copy(isModelPickerVisible = false, message = null)
                .withRecalculatedDirty()
        }
    }

    fun onMessageDismissed() {
        _uiState.update { it.copy(message = null) }
    }

    fun onDismissRequestHandled() {
        _uiState.update { it.copy(dismissRequested = false) }
    }

    // -----------------------------------------------------------------------
    // 拉取模型
    // -----------------------------------------------------------------------

    /**
     * 拉取端点支持的模型列表。
     *
     * 失败时把错误留在面板里（低饱和红字），**不关闭面板** —— 用户正在这里改配置，
     * 把他弹出去等于让他从头再来一遍。
     */
    fun fetchModels() = fetchModelsFor(ModelPickerTarget.CONVERSATION_MODEL)

    fun fetchEmbeddingModels() = fetchModelsFor(ModelPickerTarget.EMBEDDING_MODEL)

    /**
     * 拉取端点支持的模型列表。
     *
     * 失败时把错误留在面板里（低饱和红字），**不关闭面板** —— 用户正在这里改配置，
     * 把他弹出去等于让他从头再来一遍。
     *
     * 两个模型字段共用这一条链路，靠 [ModelPickerTarget] 记住结果该填到哪里：
     * 绝大多数端点把对话模型和向量模型放在同一个 `/models` 列表里，
     * 分两条代码路径只会让它们慢慢长歪。
     */
    private fun fetchModelsFor(target: ModelPickerTarget) {
        val draft = _uiState.value.draft
        if (draft.baseUrl.isBlank()) {
            showMessage("请先填写 Base URL", isError = true)
            return
        }

        fetchJob?.cancel()
        _uiState.update {
            it.copy(
                isFetchingModels = true,
                message = null,
                isModelPickerVisible = false,
                pickerTarget = target,
            )
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
                            models.size == 1 -> state
                                .applyPickedModel(models.first())
                                .copy(
                                    isFetchingModels = false,
                                    fetchedModels = models,
                                    isModelPickerVisible = false,
                                    message = "已填入唯一可用模型",
                                    isMessageError = false,
                                )
                                .withRecalculatedDirty()

                            else -> state.copy(
                                isFetchingModels = false,
                                fetchedModels = models,
                                isModelPickerVisible = true,
                                message = "拉到 ${models.size} 个模型",
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
    // 保存与新建
    // -----------------------------------------------------------------------

    /** 把当前草稿写回选中的预设。 */
    fun saveCurrentPreset() {
        val state = _uiState.value
        if (state.selectedPresetId.isBlank()) {
            showMessage("没有可保存的预设，请先新建一个", isError = true)
            return
        }

        val preset = AiPreset(
            id = state.selectedPresetId,
            name = state.draftName.ifBlank { state.selectedPreset?.name.orEmpty() },
            config = state.draft,
        )

        viewModelScope.launch {
            if (repository.savePreset(preset)) {
                _uiState.update { it.copy(isDirty = false) }
                showMessage("已保存到本地", isError = false)
            } else {
                showMessage("保存失败，请稍后重试", isError = true)
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
     * 测试连接。
     *
     * 成功时做三件事，顺序不能反：
     *  1. **先把配置存下来** —— 用户刚刚验证通过了一套配置，丢掉它是最伤人的；
     *  2. 往会话流里插一条系统胶囊（走仓库的播报通道，不直接碰聊天状态）；
     *  3. 置 dismissRequested，让面板播完收起动画再关闭。
     *
     * 失败时只更新面板内的错误文字，面板保持打开。
     */
    fun testConnection() {
        val state = _uiState.value
        if (!state.draft.isUsable) {
            showMessage("Base URL 与模型名都不能为空", isError = true)
            return
        }

        _uiState.update { it.copy(isTesting = true, message = null, isModelPickerVisible = false) }

        viewModelScope.launch {
            val result = client.testConnection(state.draft)
            result.fold(
                onSuccess = { reply ->
                    // 1. 落盘
                    val preset = AiPreset(
                        id = state.selectedPresetId,
                        name = state.draftName.ifBlank { state.selectedPreset?.name.orEmpty() },
                        config = state.draft,
                    )
                    repository.savePreset(preset)

                    // 2. 会话播报。前缀由仓库补，这里只写正事。
                    policy.publishNotice("AI 终端握手成功，当前模型：${state.draft.model}")

                    // 3. 关闭面板
                    _uiState.update {
                        it.copy(
                            isTesting = false,
                            isDirty = false,
                            message = reply.takeIf { text -> text.isNotBlank() },
                            isMessageError = false,
                            dismissRequested = true,
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

    /** 改草稿的名字。 */
    private fun editDraft(name: String) {
        _uiState.update { it.copy(draftName = name, message = null).withRecalculatedDirty() }
    }

    /** 改草稿的配置。任何一次编辑都顺带清掉上一条提示（它已经过期了）。 */
    private fun editDraft(transform: (AiConfig) -> AiConfig) {
        _uiState.update { state ->
            state.copy(
                draft = transform(state.draft),
                message = null,
                isModelPickerVisible = false,
            ).withRecalculatedDirty()
        }
    }

    /** 把模型名填进当前 picker 指向的字段。纯函数，不产生副作用。 */
    private fun AiConfigUiState.applyPickedModel(model: String): AiConfigUiState = when (pickerTarget) {
        ModelPickerTarget.CONVERSATION_MODEL -> copy(draft = draft.copy(model = model))
        ModelPickerTarget.EMBEDDING_MODEL -> copy(draft = draft.copy(embeddingModel = model))
    }

    private fun showMessage(text: String, isError: Boolean) {
        _uiState.update { it.copy(message = text, isMessageError = isError) }
    }

    /**
     * 重算「草稿是否与已保存内容不同」。
     *
     * 界面上用它来决定要不要把「保存当前」显示成强调态 —— 没有改动时不给视觉噪音。
     */
    private fun AiConfigUiState.withRecalculatedDirty(): AiConfigUiState {
        val saved = presets.firstOrNull { it.id == selectedPresetId } ?: return copy(isDirty = false)
        val dirty = saved.name != draftName || saved.config != draft
        return copy(isDirty = dirty)
    }

    /**
     * 把异常翻译成一句可以直接显示的中文。
     *
     * 网络层已经做过一次翻译（[AiClientException.message]），这里只负责兜住
     * 「万一漏了一个没被包装的异常」的情况 —— 界面上永远不该出现类名。
     */
    private fun friendlyMessage(throwable: Throwable): String = when (throwable) {
        is AiClientException -> throwable.message ?: "请求失败"
        else -> throwable.message?.takeIf { it.isNotBlank() } ?: "请求失败：${throwable.javaClass.simpleName}"
    }
}
