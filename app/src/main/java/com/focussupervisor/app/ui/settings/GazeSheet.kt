package com.focussupervisor.app.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.focussupervisor.app.domain.model.GazeState
import com.focussupervisor.app.domain.model.VisionConfig
import com.focussupervisor.app.domain.model.VisionStatus
import com.focussupervisor.app.ui.theme.FocusTheme

/**
 * 注视监控面板。
 *
 * ===========================================================================
 * 这个面板要回答的三个问题
 * ===========================================================================
 * ```
 *   ┌ 现在在不在看 ┐   它此刻判定成什么？有没有出错？摄像头权限有没有？
 *   ┌ 要不要上传   ┐   截屏发给外部端点，这是一个用户必须自己做的决定
 *   ┌ 判定参数     ┐   抽帧多快、头偏多少算没在看
 *   ┌ 视觉模型     ┐   用哪一家（和对话端点不是同一家）
 * ```
 *
 * 「要不要上传」单独成段并给出明确后果，是因为这是整个应用里唯一会把**屏幕内容**
 * 送出设备的功能。默认关闭，打开时不需要二次确认弹窗 —— 但文案必须把话说全，
 * 让用户在同一个屏幕上就能理解自己在同意什么。
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
        // 用户点了「允许」就顺手把开关打开 —— 他刚才的意图已经表达得很清楚了，
        // 再让他去拨一次开关是多余的。
        if (granted) viewModel.setEnabled(true)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
        sheetState = sheetState,
        containerColor = FocusTheme.colors.chromeBackground,
        contentColor = FocusTheme.colors.textPrimary,
    ) {
        GazeContent(
            uiState = uiState,
            onToggleEnabled = { enabled ->
                if (enabled && !uiState.hasCameraPermission) {
                    permissionLauncher.launch(android.Manifest.permission.CAMERA)
                } else {
                    viewModel.setEnabled(enabled)
                }
            },
            onToggleUpload = viewModel::setUploadScreenshots,
            onAnalyzeIntervalChange = viewModel::setAnalyzeInterval,
            onToleranceChange = viewModel::setTolerance,
            onRequestCamera = { permissionLauncher.launch(android.Manifest.permission.CAMERA) },
            onBaseUrlChange = viewModel::onVisionBaseUrlChange,
            onApiKeyChange = viewModel::onVisionApiKeyChange,
            onModelChange = viewModel::onVisionModelChange,
            onPromptChange = viewModel::onVisionPromptChange,
            onToggleApiKey = viewModel::toggleApiKeyVisibility,
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
    onBaseUrlChange: (String) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onModelChange: (String) -> Unit,
    onPromptChange: (String) -> Unit,
    onToggleApiKey: () -> Unit,
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

        // ---------------- 运行状态 ----------------
        SheetSection(
            title = "运行状态",
            subtitle = uiState.lastError,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = uiState.status.state.label,
                        style = MaterialTheme.typography.bodyLarge,
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

            if (!uiState.hasCameraPermission) {
                Text(
                    text = "缺少摄像头权限。没有它，摄像头无法在后台工作 —— " +
                        "Android 要求 camera 类型的前台服务必须先拿到这个权限。",
                    style = MaterialTheme.typography.bodySmall,
                    color = FocusTheme.colors.textSecondary,
                )
                SheetSecondaryButton(
                    text = "授予摄像头权限",
                    enabled = true,
                    onClick = onRequestCamera,
                )
            }

            if (!uiState.canCaptureScreen) {
                Text(
                    text = "没有截屏能力（无障碍服务未开启，或系统低于 Android 11）。" +
                        "注视仍然会被记录，只是不会产生视觉描述。",
                    style = MaterialTheme.typography.bodySmall,
                    color = FocusTheme.colors.textSecondary,
                )
            }
        }

        // ---------------- 截图上传 ----------------
        SheetSection(
            title = "截图上传",
            subtitle = "判定为「正在注视」时，截取当前屏幕并发给视觉模型，让 AI 知道他在做什么。",
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (uiState.config.uploadScreenshots) "已开启" else "已关闭",
                        style = MaterialTheme.typography.bodyLarge,
                        color = FocusTheme.colors.textPrimary,
                    )
                    Text(
                        text = if (uiState.config.uploadScreenshots) {
                            "截图会离开这台设备。抖音、微信、相册里的内容都可能被一起发出去 —— " +
                                "只在你能接受这一点时保持开启。"
                        } else {
                            "只在本机记录「在看 / 没在看」，不出设备。"
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
        }

        // ---------------- 判定参数 ----------------
        SheetSection(
            title = "判定参数",
            subtitle = "默认值偏保守：宁可少记几次，也不要在你只是路过屏幕时刷出一堆记录。",
        ) {
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
                text = "抽帧越慢越省电，但「拿起手机」这种短动作可能被漏掉；" +
                    "容差越大越容易判成「在看」，小于 10° 时正常坐着看手机都可能判不出来。",
                style = MaterialTheme.typography.bodySmall,
                color = FocusTheme.colors.textSecondary,
            )
        }

        // ---------------- 视觉模型 ----------------
        SheetSection(
            title = "视觉模型",
            subtitle = "和对话端点是两份独立配置。DeepSeek 不接受图片，所以这里要另配一家多模态服务。",
        ) {
            SheetTextField(
                label = "Base URL",
                value = uiState.draft.baseUrl,
                onValueChange = onBaseUrlChange,
                placeholder = "https://dashscope.aliyuncs.com/compatible-mode/v1",
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
            )

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
        parts += "本次运行累计 ${status.todayFocusMillis / 60_000L} 分钟"
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
