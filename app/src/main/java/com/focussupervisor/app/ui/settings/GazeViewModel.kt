package com.focussupervisor.app.ui.settings

import android.app.Application
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.focussupervisor.app.appContainer
import com.focussupervisor.app.core.network.AiClientException
import com.focussupervisor.app.domain.model.AiConfig
import com.focussupervisor.app.domain.model.GazeConfig
import com.focussupervisor.app.domain.model.GazeDefaults
import com.focussupervisor.app.domain.model.VisionConfig
import com.focussupervisor.app.domain.model.VisionEngineKind
import com.focussupervisor.app.domain.model.VisionSource
import com.focussupervisor.app.domain.model.VisionStatus
import com.focussupervisor.app.service.FocusMonitorService
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 注视监控面板的状态。
 *
 * @param config 已保存的配置
 * @param draft 视觉模型的草稿（编辑中，未保存）
 * @param savedVision 已保存的视觉配置，用来判断草稿有没有改动
 * @param hasCameraPermission 摄像头运行时权限是否已授予。**这是本功能的硬前提**：
 *        没有它，前台服务连前台态都进不去（Android 14 起 camera 类型必须有权限）。
 */
data class GazeUiState(
    val config: GazeConfig = GazeConfig(),
    val status: VisionStatus = VisionStatus(),
    val lastDescription: String? = null,
    val lastError: String? = null,
    val hasCameraPermission: Boolean = false,
    val isConfigLoaded: Boolean = false,
    val draft: VisionConfig = VisionConfig(),
    val savedVision: VisionConfig = VisionConfig(),
    val isApiKeyVisible: Boolean = false,
    val isSaving: Boolean = false,
    val isTesting: Boolean = false,
    val isFetchingModels: Boolean = false,
    val fetchedModels: List<String> = emptyList(),
    val message: String? = null,
    val isMessageError: Boolean = true,
    val canCaptureScreen: Boolean = false,
) {
    val isBusy: Boolean get() = isSaving || isTesting

    /** 草稿与已保存内容是否有差异。 */
    val isVisionDirty: Boolean
        get() = draft.baseUrl != savedVision.baseUrl ||
            draft.apiKey != savedVision.apiKey ||
            draft.model != savedVision.model ||
            draft.prompt != savedVision.prompt ||
            // 来源与本地引擎也算改动：用户从「在线」切到「本地」之后如果这一项不算脏，
            // 界面上的「未保存」提示就不会出现，他会以为已经生效了。
            draft.source != savedVision.source ||
            draft.localEngine != savedVision.localEngine

    val canSaveVision: Boolean get() = !isBusy

    /** 只有地址与模型都填了才允许测试 —— 少一个就没法发请求。 */
    val canTestVision: Boolean get() = draft.isUsable && !isBusy
}

/**
 * 注视监控面板的状态持有者。
 *
 * ===========================================================================
 * 开关的动作不只是写配置
 * ===========================================================================
 * 打开时要 `startForegroundService`，关闭时让服务自己退出。顺序不能反：
 * **先写配置，再启动服务**。服务一启动就会去读配置，如果配置还没落盘，它会读到
 * 「未开启」然后立刻自杀 —— 表现出来就是「打开开关后服务一闪就没了」。
 */
class GazeViewModel(application: Application) : AndroidViewModel(application) {

    private val container = application.appContainer
    private val gaze = container.gaze
    private val aiConfig = container.aiConfig
    private val client = container.openAiClient
    private val policy = container.policy
    private val localVision = container.localVision
    private val permissions = container.permissions

    private val _uiState = MutableStateFlow(GazeUiState())
    val uiState: StateFlow<GazeUiState> = _uiState.asStateFlow()

    private var testJob: Job? = null
    private var fetchJob: Job? = null

    init {
        observeGaze()
        observeVisionConfig()
        refreshPermission()
    }

    // -----------------------------------------------------------------------
    // 观测
    // -----------------------------------------------------------------------

    private fun observeGaze() {
        viewModelScope.launch {
            gaze.config.collect { config ->
                if (config != null) {
                    _uiState.update { it.copy(config = config, isConfigLoaded = true) }
                }
            }
        }
        viewModelScope.launch {
            gaze.status.collect { status -> _uiState.update { it.copy(status = status) } }
        }
        viewModelScope.launch {
            gaze.lastDescription.collect { text ->
                _uiState.update { it.copy(lastDescription = text) }
            }
        }
        viewModelScope.launch {
            gaze.lastError.collect { message ->
                _uiState.update { it.copy(lastError = message) }
            }
        }
    }

    private fun observeVisionConfig() {
        viewModelScope.launch {
            aiConfig.visionConfig.collect { config ->
                _uiState.update { state ->
                    // 只有在用户没有未保存改动时才覆盖草稿，否则他正在输入的字会被
                    // 后台的一次 Flow 回环抹掉。
                    if (state.isVisionDirty) {
                        state.copy(savedVision = config)
                    } else {
                        state.copy(savedVision = config, draft = config)
                    }
                }
            }
        }
    }

    /** 重新读一次摄像头权限状态。界面每次重新进入组合都会调。 */
    fun refreshPermission() {
        _uiState.update {
            it.copy(
                hasCameraPermission = permissions.hasCameraPermission(),
                canCaptureScreen = com.focussupervisor.app.core.vision.ScreenCaptureProvider.isAvailable,
            )
        }
    }

    // -----------------------------------------------------------------------
    // 开关
    // -----------------------------------------------------------------------

    /**
     * 打开/关闭注视监控。
     *
     * 打开前会再确认一次摄像头权限：没有权限时前台服务进不了前台态，
     * 与其让它启动后立刻死掉（用户只会看到开关弹回去），不如当场说清原因。
     */
    fun setEnabled(enabled: Boolean) {
        if (enabled && !permissions.hasCameraPermission()) {
            _uiState.update {
                it.copy(message = "请先授予摄像头权限，否则摄像头无法在后台工作", isMessageError = true)
            }
            return
        }

        viewModelScope.launch {
            val next = _uiState.value.config.copy(enabled = enabled)
            gaze.updateConfig(next)
            _uiState.update { it.copy(config = next, message = null) }

            if (enabled) {
                startMonitorService()
            }
            // 关闭时不需要在这里 stopService：服务自己在收集到配置变化后会退出。
            // 这样即使配置是从别处改的（将来可能有别的入口），行为也一致。
        }
    }

    private fun startMonitorService() {
        val context = getApplication<Application>()
        val intent = Intent(context, FocusMonitorService::class.java)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(context, intent)
            } else {
                context.startService(intent)
            }
        }.onFailure { throwable ->
            _uiState.update {
                it.copy(
                    message = "启动注视监控失败：${throwable.javaClass.simpleName}",
                    isMessageError = true,
                )
            }
        }
    }

    fun setUploadScreenshots(enabled: Boolean) {
        writeConfig(_uiState.value.config.copy(uploadScreenshots = enabled))
    }

    fun setAnalyzeInterval(millis: Long) {
        writeConfig(
            _uiState.value.config.copy(
                analyzeIntervalMillis = millis.coerceAtLeast(GazeDefaults.MIN_ANALYZE_INTERVAL_MILLIS),
            ),
        )
    }

    fun setTolerance(degrees: Float) {
        writeConfig(
            _uiState.value.config.copy(
                yawToleranceDegrees = degrees,
                pitchToleranceDegrees = degrees,
            ),
        )
    }

    private fun writeConfig(config: GazeConfig) {
        viewModelScope.launch {
            gaze.updateConfig(config)
            _uiState.update { it.copy(config = config) }
        }
    }

    // -----------------------------------------------------------------------
    // 视觉模型
    // -----------------------------------------------------------------------

    fun onVisionBaseUrlChange(value: String) =
        _uiState.update { it.copy(draft = it.draft.copy(baseUrl = value.trim())) }

    fun onVisionApiKeyChange(value: String) =
        _uiState.update { it.copy(draft = it.draft.copy(apiKey = value.trim())) }

    fun onVisionModelChange(value: String) =
        _uiState.update { it.copy(draft = it.draft.copy(model = value.trim())) }

    fun onVisionPromptChange(value: String) =
        _uiState.update {
            it.copy(draft = it.draft.copy(prompt = value.take(VisionConfig.MAX_PROMPT_LENGTH)))
        }

    fun toggleApiKeyVisibility() =
        _uiState.update { it.copy(isApiKeyVisible = !it.isApiKeyVisible) }

    /** 切换识图来源：在线多模态 / 本地端侧。 */
    fun onVisionSourceChange(source: VisionSource) {
        _uiState.update { it.copy(draft = it.draft.copy(source = source), message = null) }
    }

    /** 选择本地识图的引擎。 */
    fun onVisionEngineChange(engine: VisionEngineKind) {
        _uiState.update {
            it.copy(draft = it.draft.copy(localEngine = engine), message = null)
        }
    }

    fun saveVisionConfig() {
        val draft = _uiState.value.draft
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, message = null) }
            val ok = aiConfig.saveVisionConfig(draft)
            _uiState.update {
                it.copy(
                    isSaving = false,
                    savedVision = if (ok) draft else it.savedVision,
                    message = if (ok) "视觉模型配置已保存" else "保存失败",
                    isMessageError = !ok,
                )
            }
        }
    }

    /**
     * 测试视觉端点。
     *
     * 发的是**一次纯文本请求**（沿用对话端点的握手逻辑），而不是伪造一张图：
     * 假图片能让某些端点报出与真实使用无关的错误，反而误导。所以这次测试回答的是
     * 「地址、Key、模型名对不对」，真实截图能不能识别要等第一次注视时才知道 ——
     * 这一点也写在界面文案里。
     */
    fun testVisionConnection() {
        val draft = _uiState.value.draft

        // 本地识图走一条完全不同的测试：真的对一张合成位图跑一次提取。
        // 它回答的问题不一样 —— 云端测的是「地址 Key 模型名对不对」，
        // 本地测的是「这套端侧链路在你这台机器上能不能跑通」。
        if (draft.source == VisionSource.LOCAL) {
            testLocalVisionEngine(draft)
            return
        }

        if (!draft.isUsable) {
            _uiState.update { it.copy(message = "先填地址和模型名", isMessageError = true) }
            return
        }

        testJob?.cancel()
        testJob = viewModelScope.launch {
            _uiState.update { it.copy(isTesting = true, message = null) }
            val probe = AiConfig(
                baseUrl = draft.baseUrl,
                apiKey = draft.apiKey,
                model = draft.model,
                temperature = AiConfig.DEFAULT_TEMPERATURE,
            )
            val result = client.testConnection(probe)
            _uiState.update {
                it.copy(
                    isTesting = false,
                    message = result.getOrElse { throwable ->
                        (throwable as? AiClientException)?.message ?: "测试失败"
                    },
                    isMessageError = result.isFailure,
                )
            }
        }
    }

    fun onMessageShown() = _uiState.update { it.copy(message = null) }

    // -----------------------------------------------------------------------
    // 拉取模型列表
    // -----------------------------------------------------------------------

    /**
     * 让端点自己报出可用的模型名。
     *
     * 和对话配置里的「拉取」是同一套逻辑（打 `/models`）。它比手填重要得多：
     * 各家多模态模型的命名差异很大（qwen-vl-plus / glm-4v-flash / gpt-4o-mini…），
     * 手填一个不存在的名字，报错往往是「模型不存在」这种不带候选的提示，
     * 用户只能靠猜。
     */
    fun fetchVisionModels() {
        val draft = _uiState.value.draft
        if (draft.baseUrl.isBlank()) {
            _uiState.update { it.copy(message = "先填 Base URL", isMessageError = true) }
            return
        }

        fetchJob?.cancel()
        fetchJob = viewModelScope.launch {
            _uiState.update { it.copy(isFetchingModels = true, message = null) }
            val result = client.fetchModels(baseUrl = draft.baseUrl, apiKey = draft.apiKey)
            _uiState.update { state ->
                result.fold(
                    onSuccess = { models ->
                        state.copy(
                            isFetchingModels = false,
                            fetchedModels = models,
                            message = if (models.isEmpty()) "端点没有返回任何模型" else "拉到 ${models.size} 个模型",
                            isMessageError = models.isEmpty(),
                        )
                    },
                    onFailure = { throwable ->
                        state.copy(
                            isFetchingModels = false,
                            message = (throwable as? AiClientException)?.message ?: "拉取失败",
                            isMessageError = true,
                        )
                    },
                )
            }
        }
    }

    /** 从拉取结果里选一个填进模型名。 */
    fun pickVisionModel(model: String) {
        _uiState.update { it.copy(draft = it.draft.copy(model = model)) }
    }

    private companion object {
        /**
         * 本地识图测试的摘要长度上限。
         *
         * 用固定 40 字，而不是当前提示词档位的预算：测试的语义是「这套链路通不通」，
         * 不是「这次注视的摘要会多长」。用一个随档位变化的数，会让同一次测试在不同
         * 配置下给出不同长度的输出，反而看不清。
         */
        const val LOCAL_VISION_TEST_MAX_CHARS = 40
    }
}
