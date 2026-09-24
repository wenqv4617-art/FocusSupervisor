package com.focussupervisor.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.focussupervisor.app.core.network.PromptCacheStats
import com.focussupervisor.app.domain.model.AiConfig
import com.focussupervisor.app.ui.theme.FocusTheme
import kotlin.math.roundToInt

/**
 * AI 配置中心。
 *
 * 从底部「+」面板里的「AI 配置」自底向上滑出，是整个应用**唯一**需要用户坐下来
 * 填东西的地方，因此也是唯一允许出现输入框的界面。
 *
 * 结构自上而下，正好对应几个递进的问题：
 * ```
 *   预设栏     我在配哪一套？
 *   地址 / Key 连到哪里、拿什么身份连？
 *   对话模型   用哪个模型说话？
 *   向量模型   用哪个模型做记忆检索？（可留空）
 *   温度       它该多发散？
 *   前置提示   有什么必须先于一切生效的设定？
 *   ─────────
 *   测试连接   以上全部是否真的能用？
 * ```
 *
 * 视觉上完全沿用主界面的克制基调：没有卡片、没有阴影、没有第二强调色。
 * 唯一的彩色是「测试连接」那一个强调色按钮，以及出错时的一行低饱和红字。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiConfigSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AiConfigViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // 面板每次重新进入组合时把草稿重置成磁盘上那一份，避免上次没保存的编辑残留。
    LaunchedEffect(Unit) { viewModel.onSheetOpened() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
        sheetState = sheetState,
        containerColor = FocusTheme.colors.surfaceSheet,
        contentColor = FocusTheme.colors.textPrimary,
    ) {
        AiConfigContent(
            uiState = uiState,
            onPresetSelected = viewModel::onPresetSelected,
            onNameChange = viewModel::onNameChange,
            onBaseUrlChange = viewModel::onBaseUrlChange,
            onApiKeyChange = viewModel::onApiKeyChange,
            onModelChange = viewModel::onModelChange,
            onPrePromptChange = viewModel::onPrePromptChange,
            onTemperatureChange = viewModel::onTemperatureChange,
            onToggleApiKeyVisibility = viewModel::toggleApiKeyVisibility,
            onFetchModels = viewModel::fetchModels,
            onModelPicked = viewModel::onModelPicked,
            onModelPickerDismiss = viewModel::onModelPickerDismiss,
            onSaveCurrent = viewModel::saveCurrentPreset,
            onCreatePreset = viewModel::createPreset,
            onDeletePreset = viewModel::deleteCurrentPreset,
            onTestConnection = viewModel::testConnection,
            onClose = onDismiss,
        )
    }
}

/**
 * 面板内容。无状态，便于预览与测试。
 */
@Composable
private fun AiConfigContent(
    uiState: AiConfigUiState,
    onPresetSelected: (String) -> Unit,
    onNameChange: (String) -> Unit,
    onBaseUrlChange: (String) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onModelChange: (String) -> Unit,
    onPrePromptChange: (String) -> Unit,
    onTemperatureChange: (Float) -> Unit,
    onToggleApiKeyVisibility: () -> Unit,
    onFetchModels: () -> Unit,
    onModelPicked: (String) -> Unit,
    onModelPickerDismiss: () -> Unit,
    onSaveCurrent: () -> Unit,
    onCreatePreset: () -> Unit,
    onDeletePreset: () -> Unit,
    onTestConnection: () -> Unit,
    onClose: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = SheetHorizontalPadding)
            .padding(bottom = SheetBottomPadding),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            SheetTitle(text = "AI 配置中心", modifier = Modifier.weight(1f))
            // 面板不再「测试成功就自动关闭」，所以需要一个明确的关闭入口。
            TextButton(onClick = onClose) {
                Text(
                    text = "关闭",
                    style = MaterialTheme.typography.bodySmall,
                    color = FocusTheme.colors.textSecondary,
                )
            }
        }

        PresetBar(
            uiState = uiState,
            onPresetSelected = onPresetSelected,
            onCreatePreset = onCreatePreset,
            onDeletePreset = onDeletePreset,
        )

        SheetTextField(
            label = "预设名称",
            value = uiState.draftName,
            onValueChange = onNameChange,
            placeholder = "给这套配置起个名字",
        )

        SheetTextField(
            label = "Base URL",
            value = uiState.draft.baseUrl,
            onValueChange = onBaseUrlChange,
            placeholder = "https://api.deepseek.com/v1",
            monospace = true,
            footer = baseUrlFooter(uiState.draft.baseUrl),
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
                TextButton(onClick = onToggleApiKeyVisibility) {
                    Text(
                        text = if (uiState.isApiKeyVisible) "隐藏" else "显示",
                        style = MaterialTheme.typography.bodySmall,
                        color = FocusTheme.colors.accent,
                    )
                }
            },
            footer = "Key 以明文保存在本机应用私有目录，只会发往你填写的这个端点。",
        )

        SheetTextField(
            label = "对话模型",
            value = uiState.draft.model,
            onValueChange = onModelChange,
            placeholder = "deepseek-chat",
            monospace = true,
            trailing = {
                FetchButton(
                    isFetching = uiState.isFetchingModels,
                    onClick = onFetchModels,
                )
            },
        )

        if (uiState.isModelPickerVisible && uiState.fetchedModels.isNotEmpty()) {
            ModelPicker(
                models = uiState.fetchedModels,
                currentModel = uiState.draft.model,
                onModelPicked = onModelPicked,
                onDismiss = onModelPickerDismiss,
            )
        }

        TemperatureRow(
            temperature = uiState.draft.temperature,
            onTemperatureChange = onTemperatureChange,
        )

        SheetTextField(
            label = "前置提示（破限）",
            value = uiState.draft.prePrompt,
            onValueChange = onPrePromptChange,
            placeholder = "这里的文字会被**逐字**放在整段提示词的最前面，\n先于人设、记忆、待办与一切功能。",
            singleLine = false,
            minHeight = 110,
            footer = "留空则不注入。位置在所有内容之前，因此它压过人设与全部系统规则。",
        )

        CacheStatsRow(stats = uiState.cacheStats)

        SheetMessageLine(message = uiState.message, isError = uiState.isMessageError)

        // 保存与测试是两件独立的事，两个按钮并排。
        // 上一版把测试做成「成功就自动保存并关面板」—— 用户在点之前无法预期它会写盘，
        // 而且面板一关，失败原因就没地方显示了。
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SheetPrimaryButton(
                text = when {
                    uiState.isSaving -> "保存中…"
                    uiState.isDirty -> "保存修改"
                    else -> "保存"
                },
                enabled = uiState.canSave,
                onClick = onSaveCurrent,
                modifier = Modifier.weight(1f),
            )
            SheetSecondaryButton(
                text = if (uiState.isTesting) "测试中…" else "测试连接",
                enabled = uiState.canTest,
                onClick = onTestConnection,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** 明文 HTTP 的即时提醒。 */
private fun baseUrlFooter(baseUrl: String): String =
    if (baseUrl.trim().startsWith("http://", ignoreCase = true)) {
        "注意：这是明文 HTTP，Key 会在链路上以明文传输。"
    } else {
        "填域名即可，会自动尝试补 /v1；中转站的路径也会依次试过。"
    }

// ---------------------------------------------------------------------------
// 预设栏
// ---------------------------------------------------------------------------

/**
 * 预设管理栏：横向切换 + 保存当前 + 新建 + 删除。
 *
 * 用 `horizontalScroll` 而不是 `LazyRow`：预设数量在个位数，惰性布局的那套
 * 复用机制在这里换不到任何东西，反而会让「滚动到选中项」这件事变复杂。
 */
@Composable
private fun PresetBar(
    uiState: AiConfigUiState,
    onPresetSelected: (String) -> Unit,
    onCreatePreset: () -> Unit,
    onDeletePreset: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SheetSectionLabel(text = "预设")

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            uiState.presets.forEach { preset ->
                SheetChip(
                    text = preset.name,
                    selected = preset.id == uiState.selectedPresetId,
                    onClick = { onPresetSelected(preset.id) },
                )
            }
            // 「新建」复用同一个外形，让它在视觉上就是列表里的下一个位置，
            // 而不是一个额外的动作按钮。
            SheetChip(
                text = "＋ 新建",
                selected = false,
                onClick = onCreatePreset,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (!uiState.isSelectedBuiltIn && uiState.selectedPresetId.isNotBlank()) {
                TextButton(onClick = onDeletePreset) {
                    Text(
                        text = "删除此预设",
                        style = MaterialTheme.typography.bodySmall,
                        color = FocusTheme.colors.textSecondary,
                    )
                }
            }
        }
    }
}

/** 「拉取」按钮，两个模型字段共用。 */
@Composable
private fun FetchButton(isFetching: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = !isFetching) {
        Text(
            text = if (isFetching) "拉取中" else "拉取",
            style = MaterialTheme.typography.bodySmall,
            color = if (isFetching) FocusTheme.colors.textSecondary else FocusTheme.colors.accent,
        )
    }
}

/**
 * 模型选择器。
 *
 * 点「拉取」成功后展开，直接点一行填进对应的模型框。做成内联的下拉而不是弹出
 * 对话框：用户此刻的注意力就在那一行上，就地展开最省一次视线跳转。
 */
@Composable
private fun ModelPicker(
    models: List<String>,
    currentModel: String,
    onModelPicked: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(FocusTheme.colors.inputField)
            // heightIn 必须在 verticalScroll **之前**：外层 Column 已经是一个
            // 纵向滚动容器，内层滚动若拿到无限高约束会直接抛异常。
            .heightIn(max = 220.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        models.forEachIndexed { index, model ->
            if (index > 0) {
                HorizontalDivider(thickness = 0.5.dp, color = FocusTheme.colors.hairline)
            }
            Text(
                text = model,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = if (model == currentModel) {
                    FocusTheme.colors.accent
                } else {
                    FocusTheme.colors.textPrimary
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onModelPicked(model) }
                    .padding(horizontal = 12.dp, vertical = 11.dp),
            )
        }

        HorizontalDivider(thickness = 0.5.dp, color = FocusTheme.colors.hairline)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onDismiss)
                .padding(horizontal = 12.dp, vertical = 11.dp),
        ) {
            Text(
                text = "收起",
                style = MaterialTheme.typography.bodySmall,
                color = FocusTheme.colors.textSecondary,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 温度
// ---------------------------------------------------------------------------

/**
 * 温度滑块。
 *
 * 显示保留一位小数。范围取 [AiConfig.MIN_TEMPERATURE] ~ [AiConfig.MAX_TEMPERATURE]，
 * 不在这里写死数字 —— 上下限与仓库层的校验共用同一组常量，改的时候不会漏掉一处。
 */
@Composable
private fun TemperatureRow(
    temperature: Float,
    onTemperatureChange: (Float) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            SheetSectionLabel(text = "Temperature", modifier = Modifier.weight(1f))
            Text(
                text = formatTemperature(temperature),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = FocusTheme.colors.textPrimary,
            )
        }

        Slider(
            value = temperature,
            onValueChange = onTemperatureChange,
            valueRange = AiConfig.MIN_TEMPERATURE..AiConfig.MAX_TEMPERATURE,
            // 0.1 步进 → 0.0~1.5 之间共 16 个取值，中间有 14 个刻度点。
            steps = TEMPERATURE_STEPS,
            colors = SliderDefaults.colors(
                thumbColor = FocusTheme.colors.accent,
                activeTrackColor = FocusTheme.colors.accent,
                inactiveTrackColor = FocusTheme.colors.hairline,
            ),
        )

        Text(
            text = "越低越稳定保守，越高越发散。监督场景建议 0.3 ~ 0.8。",
            style = MaterialTheme.typography.bodySmall,
            color = FocusTheme.colors.textSecondary,
        )
    }
}

/**
 * 上下文缓存命中情况。
 *
 * ===========================================================================
 * 为什么要把它摆在配置页里
 * ===========================================================================
 * DeepSeek 的上下文硬盘缓存按**前缀**命中，命中部分的输入价约为未命中的 1/30~1/50。
 * 而命中率完全取决于提示词里内容的摆放顺序 —— 这是个极其脆弱的性质：只要往 system
 * 里加一个「当前时间」，命中率会立刻掉到 0，**而界面上不会有任何别的变化**。
 *
 * 所以把最近一次的数字摆在这里。有这么一个东西盯着，以后任何人改提示词都能立刻
 * 看出代价，而不是等到月底看账单。
 *
 * `stats == null` 表示**端点没有上报 usage**（很多中转站不透传），不是「一次都没
 * 命中」—— 这两件事必须分开说，否则用户会去优化一个本来就正常的东西。
 */
@Composable
private fun CacheStatsRow(stats: PromptCacheStats?) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SheetSectionLabel(text = "上下文缓存")

        Text(
            text = when {
                stats == null -> "还没有记录。发一条消息之后，这里会显示最近一次的命中情况。"
                else -> "最近一次：命中 ${stats.cachedTokens} / 输入 ${stats.promptTokens} tokens" +
                    "（${(stats.hitRatio * 100).roundToInt()}%）"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = FocusTheme.colors.textPrimary,
        )

        Text(
            text = cacheConclusion(stats),
            style = MaterialTheme.typography.bodySmall,
            color = FocusTheme.colors.textSecondary,
        )
    }
}

private fun cacheConclusion(stats: PromptCacheStats?): String = when {
    stats == null ->
        "有些服务商不会返回缓存用量。看不到数字不等于没命中。"
    stats.promptTokens <= 0L ->
        "这次请求没有输入 token，无法判断。"
    stats.cachedTokens <= 0L ->
        "这次没命中任何前缀。刚换话题时前一两轮本来就不命中（缓存要从重复里才建得起来），" +
            "但如果是连续的每轮都不命中，那说明前缀里有每轮都变的内容。"
    stats.hitRatio < 0.3f ->
        "命中偏低。前缀还太短，或者前缀里混进了每轮都变的东西。"
    stats.hitRatio < 0.7f ->
        "命中正常。重复的部分按缓存价计费了。"
    else ->
        "命中很高，重复内容几乎都走了缓存价。"
}

/** 0.0 ~ 1.5、步进 0.1 对应的中间刻度数。 */
private const val TEMPERATURE_STEPS = 14

/** 只显示一位小数，避免浮点误差把 0.7000001 画到界面上。 */
private fun formatTemperature(value: Float): String =
    String.format(java.util.Locale.US, "%.1f", (value * 10).roundToInt() / 10f)
