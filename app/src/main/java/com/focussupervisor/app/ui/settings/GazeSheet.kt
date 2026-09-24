package com.focussupervisor.app.ui.settings

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.focussupervisor.app.domain.model.GazeState
import com.focussupervisor.app.domain.model.VisionConfig
import com.focussupervisor.app.domain.model.VisionEngineKind
import com.focussupervisor.app.domain.model.VisionSource
import com.focussupervisor.app.domain.model.VisionStatus
import com.focussupervisor.app.ui.theme.FocusTheme
import com.focussupervisor.app.ui.theme.PastelTone

/**
 * 注视监控面板。
 *
 * ===========================================================================
 * 这个面板的顺序就是它的逻辑
 * ===========================================================================
 * 上一版把「状态、上传、参数、视觉模型」四块平铺在一列里，看起来都对，但用户
 * 看完不知道**它到底会不会截图、什么时候截图** —— 那正是这个功能唯一需要用户
 * 想清楚的事。所以现在按因果重排：
 *
 * ```
 *   ① 现在会发生什么   ← 一句话结论：它此刻会做什么、不会做什么
 *   ② 感知             ← 摄像头与判定参数（决定「知不知道你在看」）
 *   ③ 上报             ← 截图与视觉模型（决定「要不要把屏幕发出去」）
 * ```
 * ② 是 ③ 的前提：不打开感知，上报那一整块根本不会触发。面板上也按这个顺序排，
 * 并且在 ③ 里直接写清「没配视觉模型时截图不会产生描述」这种失败态 ——
 * 让用户在同一个屏幕上就能把因果关系看完。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GazeSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: GazeViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // 每次重新进入组合都重查一次权限：用户可能刚从系统设置页回来。
    LaunchedEffect(Unit) { viewModel.refreshPermission() }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        viewModel.refreshPermission()
        // 用户点了「允许」就顺手把开关打开 —— 他刚才的意图已经表达得很清楚了。
        if (granted) viewModel.setEnabled(true)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
        sheetState = sheetState,
        containerColor = FocusTheme.colors.surfaceSheet,
        contentColor = FocusTheme.colors.textPrimary,
    ) {
        GazeContent(
            uiState = uiState,
            onToggleEnabled = { enabled ->
                if (enabled && !uiState.hasCameraPermission) {
                    permissionLauncher.launch(Manifest.permission.CAMERA)
                } else {
                    viewModel.setEnabled(enabled)
                }
            },
            onToggleUpload = viewModel::setUploadScreenshots,
            onAnalyzeIntervalChange = viewModel::setAnalyzeInterval,
            onToleranceChange = viewModel::setTolerance,
            onRequestCamera = { permissionLauncher.launch(Manifest.permission.CAMERA) },
            onVisionSourceChange = viewModel::onVisionSourceChange,
            onVisionEngineChange = viewModel::onVisionEngineChange,
            onBaseUrlChange = viewModel::onVisionBaseUrlChange,
            onApiKeyChange = viewModel::onVisionApiKeyChange,
            onModelChange = viewModel::onVisionModelChange,
            onPromptChange = viewModel::onVisionPromptChange,
            onToggleApiKey = viewModel::toggleApiKeyVisibility,
            onFetchModels = viewModel::fetchVisionModels,
            onPickModel = viewModel::pickVisionModel,
            onSaveVision = viewModel::saveVisionConfig,
            onTestVision = viewModel::testVisionConnection,
            onClose = onDismiss,
        )
    }
}

@Composable
private fun GazeContent(
    uiState: GazeUiState,
    onToggleEnabled: (Boolean) -> Unit,
    onToggleUpload: (Boolean) -> Unit,
    onAnalyzeIntervalChange: (Long) -> Unit,
    onToleranceChange: (Float) -> Unit,
    onRequestCamera: () -> Unit,
    onVisionSourceChange: (VisionSource) -> Unit,
    onVisionEngineChange: (VisionEngineKind) -> Unit,
    onBaseUrlChange: (String) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onModelChange: (String) -> Unit,
    onPromptChange: (String) -> Unit,
    onToggleApiKey: () -> Unit,
    onFetchModels: () -> Unit,
    onPickModel: (String) -> Unit,
    onSaveVision: () -> Unit,
    onTestVision: () -> Unit,
    onClose: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = SheetHorizontalPadding)
            .padding(bottom = SheetBottomPadding),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SheetTitle(text = "注视监控", modifier = Modifier.weight(1f))
            TextButton(onClick = onClose) {
                Text(
                    text = "关闭",
                    style = MaterialTheme.typography.bodySmall,
                    color = FocusTheme.colors.textSecondary,
                )
            }
        }

        // ---------------- ① 现在会发生什么 ----------------
        SheetSection(
            title = "现在会发生什么",
            subtitle = uiState.lastError,
        ) {
            Text(
                text = conclusion(uiState),
                style = MaterialTheme.typography.bodyMedium,
                color = FocusTheme.colors.textPrimary,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "摄像头判定：${uiState.status.state.label}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = FocusTheme.colors.textPrimary,
                    )
                    Text(
                        text = statusDetail(uiState.status),
                        style = MaterialTheme.typography.bodySmall,
                        color = FocusTheme.colors.textSecondary,
                    )
                }
                Switch(
                    checked = uiState.config.enabled,
                    onCheckedChange = onToggleEnabled,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = FocusTheme.colors.accent,
                        checkedTrackColor = FocusTheme.colors.accent.copy(alpha = 0.4f),
                    ),
                )
            }

            uiState.lastDescription?.let { description ->
                Text(
                    text = "最近一次视觉描述：$description",
                    style = MaterialTheme.typography.bodySmall,
                    color = FocusTheme.colors.textPrimary,
                )
            }
        }

        // ---------------- ② 感知 ----------------
        SheetSection(
            title = "感知",
            subtitle = "决定「它知不知道你在看屏幕」。画面只在设备本地处理，用完即弃，不落盘。",
        ) {
            if (!uiState.hasCameraPermission) {
                Text(
                    text = "缺少摄像头权限。Android 要求 camera 类型的前台服务必须先拿到它，" +
                        "否则连服务都起不来 —— 这是硬前提，不是可选项。",
                    style = MaterialTheme.typography.bodySmall,
                    color = FocusTheme.colors.textSecondary,
                )
                SheetSecondaryButton(
                    text = "授予摄像头权限",
                    enabled = true,
                    onClick = onRequestCamera,
                )
            }

            Text(
                text = "抽帧间隔",
                style = MaterialTheme.typography.bodyMedium,
                color = FocusTheme.colors.textPrimary,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ANALYZE_INTERVALS.forEach { (label, millis) ->
                    SheetChip(
                        text = label,
                        selected = uiState.config.analyzeIntervalMillis == millis,
                        onClick = { onAnalyzeIntervalChange(millis) },
                    )
                }
            }

            Text(
                text = "头部偏转容差",
                style = MaterialTheme.typography.bodyMedium,
                color = FocusTheme.colors.textPrimary,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TOLERANCES.forEach { degrees ->
                    SheetChip(
                        text = "${degrees.toInt()}°",
                        selected = uiState.config.yawToleranceDegrees == degrees,
                        onClick = { onToleranceChange(degrees) },
                    )
                }
            }

            Text(
                text = "抽帧越慢越省电，但「刚拿起手机」这种短动作可能被漏掉；" +
                    "容差越大越容易判成「在看」。判定本身还有 1.5 秒的去抖，" +
                    "所以眨个眼、偏一下头都不会让状态来回跳。",
                style = MaterialTheme.typography.bodySmall,
                color = FocusTheme.colors.textSecondary,
            )
        }

        // ---------------- ③ 上报 ----------------
        SheetSection(
            title = "上报",
            subtitle = "决定「要不要把屏幕内容发出去」。只有判定为「正在注视」的那一刻才会截图，" +
                "而且每次之间至少隔 ${uiState.config.captureCooldownMillis / 60_000L} 分钟。",
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (uiState.config.uploadScreenshots) "截图上传已开启" else "截图上传已关闭",
                        style = MaterialTheme.typography.bodyMedium,
                        color = FocusTheme.colors.textPrimary,
                    )
                    Text(
                        text = if (uiState.config.uploadScreenshots) {
                            "屏幕内容会离开这台设备。抖音、微信、相册里的东西都可能被一起发出去 —— " +
                                "只在你能接受这一点时保持开启。"
                        } else {
                            "只在本机记录「在看 / 没在看」，一个字节都不出设备。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = FocusTheme.colors.textSecondary,
                    )
                }
                Switch(
                    checked = uiState.config.uploadScreenshots,
                    onCheckedChange = onToggleUpload,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = FocusTheme.colors.accent,
                        checkedTrackColor = FocusTheme.colors.accent.copy(alpha = 0.4f),
                    ),
                )
            }

            if (uiState.config.uploadScreenshots) {
                if (!uiState.canCaptureScreen) {
                    Text(
                        text = "但当前拿不到截图：无障碍服务没开，或者系统低于 Android 11" +
                            "（无障碍的截屏 API 是 Android 11 才有的）。注视照样会被记录，" +
                            "只是不会产生描述。",
                        style = MaterialTheme.typography.bodySmall,
                        color = FocusTheme.colors.textSecondary,
                    )
                }
                if (!uiState.draft.isUsable) {
                    Text(
                        text = "而且视觉模型还没配好（下面要填地址与模型名），" +
                            "现在既不会截图也不会产生描述。",
                        style = MaterialTheme.typography.bodySmall,
                        color = FocusTheme.colors.textSecondary,
                    )
                }
            }
        }

        SheetSection(
            title = "识图方式",
            subtitle = "两条路互斥，不会自动回退 —— 选了本地就真的完全不出这台手机。",
            tone = PastelTone.SKY,
        ) {
            SheetSegmented(
                options = listOf("在线多模态 API", "本地端侧识图"),
                selectedIndex = if (uiState.draft.source == VisionSource.LOCAL) 1 else 0,
                onSelect = { index ->
                    onVisionSourceChange(
                        if (index == 1) VisionSource.LOCAL else VisionSource.ONLINE_API,
                    )
                },
            )

            if (uiState.draft.source == VisionSource.LOCAL) {
                VisionEngineKind.entries.forEach { engine ->
                    LocalEngineCard(
                        engine = engine,
                        selected = uiState.draft.localEngine == engine,
                        onClick = { onVisionEngineChange(engine) },
                    )
                }
                SheetNote(
                    text = "本地识图把「看图」这一步留在了手机上：截图在本机被解析成一句事实，" +
                        "再写进时间线。于是对话端无论是云端 DeepSeek 还是端侧纯文本模型，" +
                        "都能知道屏幕上发生了什么 —— 不需要一个能看图的对话模型。",
                    tone = PastelTone.MINT,
                )
            }
        }

        // 在线多模态的配置只在选它的时候出现。
        //
        // 隐藏而不是置灰：置灰会让用户以为「填了就能用」，而这两条路是互斥的 ——
        // 选了本地之后，那三个输入框填什么都不影响任何行为，留着只是噪音。
        if (uiState.draft.source == VisionSource.ONLINE_API) {
            SheetSection(
                title = "多模态端点",
                subtitle = "和对话端点是两份独立配置。DeepSeek 不接受图片，所以这里必须另配一家多模态服务。",
            ) {
            SheetTextField(
                label = "Base URL",
                value = uiState.draft.baseUrl,
                onValueChange = onBaseUrlChange,
                placeholder = "https://dashscope.aliyuncs.com/compatible-mode/v1",
                monospace = true,
            )

            SheetTextField(
                label = "API Key",
                value = uiState.draft.apiKey,
                onValueChange = onApiKeyChange,
                placeholder = "本地端点或免鉴权中转站可留空",
                monospace = true,
                visualTransformation = if (uiState.isApiKeyVisible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailing = {
                    TextButton(onClick = onToggleApiKey) {
                        Text(
                            text = if (uiState.isApiKeyVisible) "隐藏" else "显示",
                            style = MaterialTheme.typography.bodySmall,
                            color = FocusTheme.colors.textSecondary,
                        )
                    }
                },
            )

            SheetTextField(
                label = "模型",
                value = uiState.draft.model,
                onValueChange = onModelChange,
                placeholder = "qwen-vl-plus / glm-4v-flash / gpt-4o-mini",
                monospace = true,
                trailing = {
                    TextButton(onClick = onFetchModels, enabled = !uiState.isFetchingModels) {
                        Text(
                            text = if (uiState.isFetchingModels) "拉取中…" else "拉取",
                            style = MaterialTheme.typography.bodySmall,
                            color = FocusTheme.colors.accent,
                        )
                    }
                },
            )

            // 拉取结果就地可选。命名差异太大（同名模型各家后缀不同），
            // 让端点自己报出候选是最省事的做法。
            if (uiState.fetchedModels.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 200.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    uiState.fetchedModels.forEach { model ->
                        SheetChip(
                            text = model,
                            selected = model == uiState.draft.model,
                            onClick = { onPickModel(model) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            SheetTextField(
                label = "看图时问它什么",
                value = uiState.draft.prompt,
                onValueChange = onPromptChange,
                placeholder = VisionConfig.DEFAULT_PROMPT,
                singleLine = false,
                minHeight = 90,
                footer = "回答会被写进时间线，再进入 AI 的上下文。所以要求它一句话说清事实，" +
                    "而不是写一篇分析。",
            )
            }
        } else {
            SheetSection(
                title = "看图时问它什么",
                subtitle = "本地识图时，这段话决定从屏幕文字里挑什么 —— 它不会改变引擎本身的能力。",
                tone = PastelTone.LILAC,
            ) {
                SheetTextField(
                    label = "提炼要求",
                    value = uiState.draft.prompt,
                    onValueChange = onPromptChange,
                    placeholder = VisionConfig.DEFAULT_PROMPT,
                    singleLine = false,
                    minHeight = 90,
                    footer = "端侧摘要会写进时间线，再进入 AI 的上下文。要求它一句话说清事实。",
                )
            }
        }

        SheetSection(title = "保存", tone = null) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SheetPrimaryButton(
                    text = if (uiState.isSaving) "保存中…" else "保存",
                    enabled = uiState.canSaveVision,
                    onClick = onSaveVision,
                    modifier = Modifier.weight(1f),
                )
                SheetSecondaryButton(
                    text = if (uiState.isTesting) "测试中…" else "测试连接",
                    enabled = uiState.canTestVision,
                    onClick = onTestVision,
                    modifier = Modifier.weight(1f),
                )
            }

            SheetMessageLine(message = uiState.message, isError = uiState.isMessageError)

            Text(
                text = "测试连接发的是一次纯文本请求，只验地址、Key 与模型名对不对；" +
                    "真实截图能否识别，要等第一次注视时才知道。",
                style = MaterialTheme.typography.bodySmall,
                color = FocusTheme.colors.textSecondary,
            )
        }
    }
}

/**
 * 一张本地识图引擎的选项卡。
 *
 * 不可用的那一张**不画任何按钮**，只把原因写在卡片上。
 * 画一个禁用的按钮会让人以为「条件满足了就能点」，而这一项缺的不是条件，是运行时 ——
 * 那两件事必须让人一眼分清。
 */
@Composable
private fun LocalEngineCard(
    engine: VisionEngineKind,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = FocusTheme.colors
    val shape = RoundedCornerShape(14.dp)
    val tone = if (engine.isAvailable) PastelTone.MINT else PastelTone.BLUSH

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surfaceCardMuted)
            .clickable(enabled = engine.isAvailable, onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // 选中标记。不可用时画成一个空心圈，表示「这个选项存在但现在选不了」。
        Box(
            modifier = Modifier
                .padding(top = 3.dp)
                .size(16.dp)
                .clip(CircleShape)
                .background(
                    if (selected && engine.isAvailable) colors.pastelMintInk
                    else colors.surfaceCard,
                ),
        )

        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = engine.label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (engine.isAvailable) colors.textPrimary else colors.textSecondary,
                )
                SheetPill(
                    text = if (engine.isAvailable) "可用" else "暂不可用",
                    tone = tone,
                )
            }
            Text(
                text = engine.tagline,
                style = MaterialTheme.typography.labelSmall,
                color = colors.textSecondary,
            )
            Text(
                text = engine.detail,
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
                modifier = Modifier.padding(top = 4.dp),
            )
            engine.unavailableReason?.let { reason ->
                SheetNote(text = reason, tone = PastelTone.BLUSH, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}

/**
 * 一句话说清「它此刻会做什么」。
 *
 * 这是整个面板最重要的一行：用户不需要理解抽帧、去抖、冷却，只需要知道
 * **它会不会把我的屏幕发出去**，以及**为什么现在还不会**。
 */
private fun conclusion(state: GazeUiState): String = when {
    !state.config.enabled ->
        "未开启。打开后会用前置摄像头判断你是否在看屏幕；画面不出设备，状态栏会亮一个绿点。"

    !state.hasCameraPermission ->
        "已开启，但缺少摄像头权限，实际上并没有在工作。"

    !state.config.uploadScreenshots ->
        "只在本地记录「在看 / 没在看」，不截图、不上传。"

    !state.draft.isUsable ->
        "已开启截图上传，但视觉模型还没配好 —— 截图不会产生任何描述。"

    !state.canCaptureScreen ->
        "已开启截图上传，但无障碍服务没开（或系统低于 Android 11），拿不到截图。"

    else ->
        "判定为「正在注视」时会截一张屏幕发给「${state.draft.model}」，" +
            "每次之间至少隔 ${state.config.captureCooldownMillis / 60_000L} 分钟。"
}

/** 状态下面那行小字：把累计时长说清楚。 */
private fun statusDetail(status: VisionStatus): String {
    if (status.state == GazeState.OFF || status.state == GazeState.ERROR) {
        return "打开后，摄像头会以低功耗模式判断你是否在看屏幕"
    }

    val parts = mutableListOf<String>()
    if (status.continuousFocusMillis > 0L) {
        parts += "本轮已连续注视 ${status.continuousFocusMillis / 60_000L} 分钟"
    }
    if (status.todayFocusMillis > 0L) {
        parts += "今天累计 ${status.todayFocusMillis / 60_000L} 分钟"
    }
    if (parts.isEmpty()) parts += "画面只在设备本地处理，不落盘"
    return parts.joinToString(" · ")
}

/** 抽帧间隔的可选值。第一项是默认值。 */
private val ANALYZE_INTERVALS = listOf(
    "0.7 秒" to 700L,
    "1 秒" to 1_000L,
    "2 秒" to 2_000L,
)

/** 头部偏转容差的可选值（度）。 */
private val TOLERANCES = listOf(10f, 15f, 20f)
