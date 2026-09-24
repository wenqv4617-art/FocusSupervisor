package com.focussupervisor.app.ui.settings

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
import com.focussupervisor.app.data.repository.LocalModelState
import com.focussupervisor.app.domain.model.EmbeddingConfig
import com.focussupervisor.app.domain.model.LocalEmbeddingModels
import com.focussupervisor.app.ui.theme.FocusTheme
import com.focussupervisor.app.ui.theme.PastelTone
import java.util.Locale

/**
 * 向量模型配置页。
 *
 * ===========================================================================
 * 一、为什么它完全独立于「AI 配置」
 * ===========================================================================
 * 独立入口、独立页面、独立存储。理由很现实：DeepSeek 没有公开的 embeddings
 * 接口，中转站往往只代理对话模型。硬塞在一份配置里，用户为了让记忆用上向量，
 * 就得把对话模型也切走 —— 那不是取舍，是功能互斥。
 *
 * ===========================================================================
 * 二、两条路是平级的
 * ===========================================================================
 * ```
 *   在线端点    填 URL / Key / 模型名，请求发到别人的服务器上
 *   本地模型    下载 22MB 的 ONNX，之后完全离线，数据一条都不出手机
 * ```
 *
 * 「测试」按钮在两条路下做的是同一件事 —— **真的算一次向量**。为什么不只测
 * `/models`：那个接口在多数端点不需要鉴权，能列出来不代表 Key 有效、
 * 更不代表这个模型真的能转向量。发一次真请求才能把三件事一起验掉。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmbeddingSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: EmbeddingViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    LaunchedEffect(Unit) { viewModel.onSheetOpened() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
        sheetState = sheetState,
        containerColor = FocusTheme.colors.surfaceSheet,
        contentColor = FocusTheme.colors.textPrimary,
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
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SheetTitle(text = "向量模型", modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss) {
                    Text(
                        text = "关闭",
                        style = MaterialTheme.typography.bodySmall,
                        color = FocusTheme.colors.textSecondary,
                    )
                }
            }

            Text(
                text = "记忆检索靠它把文本变成向量。不配置也能用 —— 记忆会退回关键词匹配，" +
                    "能用，但召回质量会差一些。",
                style = MaterialTheme.typography.bodySmall,
                color = FocusTheme.colors.textSecondary,
            )

            SheetSegmented(
                options = listOf("在线端点", "本地模型"),
                selectedIndex = if (uiState.isLocal) 1 else 0,
                onSelect = { index -> viewModel.selectSource(useLocal = index == 1) },
            )

            if (uiState.isLocal) {
                LocalModelSection(uiState = uiState, viewModel = viewModel)
            } else {
                OnlineSection(uiState = uiState, viewModel = viewModel)
            }

            SheetMessageLine(message = uiState.message, isError = uiState.isMessageError)

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SheetPrimaryButton(
                    text = if (uiState.isSaving) "保存中…" else "保存并启用",
                    enabled = !uiState.isBusy,
                    onClick = viewModel::save,
                    modifier = Modifier.weight(1f),
                )
                SheetSecondaryButton(
                    text = when {
                        uiState.isTesting -> "测试中…"
                        uiState.isLocal -> "测一次本地推理"
                        else -> "测试连接"
                    },
                    enabled = uiState.canTest,
                    onClick = viewModel::testConnection,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 在线端点
// ---------------------------------------------------------------------------

@Composable
private fun OnlineSection(
    uiState: EmbeddingUiState,
    viewModel: EmbeddingViewModel,
) {
    SheetSection(
        title = "端点",
        subtitle = "可以和对话端点不同。填域名即可，会自动尝试补 /v1。",
        tone = PastelTone.SKY,
    ) {
        SheetTextField(
            label = "Base URL",
            value = uiState.draft.baseUrl,
            onValueChange = viewModel::onBaseUrlChange,
            placeholder = EmbeddingConfig.OLLAMA_BASE_URL,
            monospace = true,
        )

        SheetTextField(
            label = "API Key",
            value = uiState.draft.apiKey,
            onValueChange = viewModel::onApiKeyChange,
            placeholder = "本地端点可留空",
            monospace = true,
            visualTransformation = if (uiState.isApiKeyVisible) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            trailing = {
                TextButton(onClick = viewModel::toggleApiKeyVisibility) {
                    Text(
                        text = if (uiState.isApiKeyVisible) "隐藏" else "显示",
                        style = MaterialTheme.typography.bodySmall,
                        color = FocusTheme.colors.accent,
                    )
                }
            },
        )

        SheetTextField(
            label = "向量模型",
            value = uiState.draft.model,
            onValueChange = viewModel::onModelChange,
            placeholder = EmbeddingConfig.OLLAMA_DEFAULT_MODEL,
            monospace = true,
            footer = "常见选择：text-embedding-3-small（OpenAI）、bge-m3（硅基流动）、" +
                "nomic-embed-text（Ollama）。",
        )

        // 一键填入本地 Ollama 的建议值。绝大多数人是想在本机跑向量，
        // 让他手打一条 URL 和模型名没有必要。
        TextButton(onClick = viewModel::applyOllamaPreset) {
            Text(
                text = "填入本地 Ollama 建议值",
                style = MaterialTheme.typography.bodySmall,
                color = FocusTheme.colors.accent,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 本地模型
// ---------------------------------------------------------------------------

/**
 * 本地模型这一块。
 *
 * 它是按状态分支渲染的，五种状态各有各的界面 —— 这也是为什么状态用一个密封接口
 * 而不是「布尔 + 进度 + 错误串」：后者允许出现「正在下载 60% 同时错误信息还在」
 * 这种自相矛盾的组合，界面就得写一堆优先级判断来决定显示什么。
 */
@Composable
private fun LocalModelSection(
    uiState: EmbeddingUiState,
    viewModel: EmbeddingViewModel,
) {
    val model = LocalEmbeddingModels.DEFAULT

    SheetSection(
        title = "本地模型",
        subtitle = "下好之后完全离线，不产生任何请求，也不花钱。",
        tone = PastelTone.MINT,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = model.name,
                style = MaterialTheme.typography.bodyMedium,
                color = FocusTheme.colors.textPrimary,
            )
            when (uiState.modelState) {
                is LocalModelState.Ready -> SheetPill("已就绪", PastelTone.MINT)
                is LocalModelState.Downloading -> SheetPill("下载中", PastelTone.SKY)
                is LocalModelState.Verifying -> SheetPill("校验中", PastelTone.SKY)
                is LocalModelState.Partial -> SheetPill("未下完", PastelTone.LILAC)
                is LocalModelState.Failed -> SheetPill("失败", PastelTone.BLUSH)
                LocalModelState.NotInstalled -> SheetPill("未下载", PastelTone.LILAC)
            }
        }

        Text(
            text = model.description,
            style = MaterialTheme.typography.bodySmall,
            color = FocusTheme.colors.textSecondary,
        )

        when (val state = uiState.modelState) {
            LocalModelState.NotInstalled -> {
                SheetStatRow(label = "大小", value = formatBytes(model.sizeBytes))
                SheetStatRow(label = "向量维度", value = "${model.dimension} 维")
                SheetStatRow(label = "存放位置", value = "应用私有目录")
                SheetPrimaryButton(
                    text = "下载模型（约 ${formatBytes(model.sizeBytes)}）",
                    enabled = true,
                    onClick = viewModel::startModelDownload,
                )
            }

            is LocalModelState.Partial -> {
                SheetProgressBar(
                    fraction = if (model.sizeBytes > 0) {
                        state.downloadedBytes.toFloat() / model.sizeBytes.toFloat()
                    } else {
                        null
                    },
                    tone = PastelTone.LILAC,
                )
                SheetStatRow(
                    label = "已下载",
                    value = "${formatBytes(state.downloadedBytes)} / ${formatBytes(model.sizeBytes)}",
                    emphasized = true,
                )
                SheetNote(
                    text = "上次下到一半。已经下好的部分不会重来，点继续就是断点续传。",
                    tone = PastelTone.LILAC,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SheetPrimaryButton(
                        text = "继续下载",
                        enabled = true,
                        onClick = viewModel::startModelDownload,
                        modifier = Modifier.weight(1f),
                    )
                    SheetSecondaryButton(
                        text = "删除已下载部分",
                        enabled = true,
                        onClick = viewModel::deleteModel,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            is LocalModelState.Downloading -> {
                val progress = state.progress
                SheetProgressBar(
                    fraction = if (progress.isTotalKnown) progress.fraction else null,
                    tone = PastelTone.MINT,
                )
                SheetStatRow(
                    label = "进度",
                    value = if (progress.isTotalKnown) {
                        "${formatBytes(progress.downloadedBytes)} / ${formatBytes(progress.totalBytes)}" +
                            " · ${(progress.fraction * 100).toInt()}%"
                    } else {
                        formatBytes(progress.downloadedBytes)
                    },
                    emphasized = true,
                )
                SheetStatRow(
                    label = "速度",
                    value = if (progress.bytesPerSecond > 0) {
                        "${formatBytes(progress.bytesPerSecond)}/s"
                    } else {
                        "测量中…"
                    },
                )
                SheetStatRow(
                    label = "剩余",
                    value = formatRemaining(progress.remainingMillis),
                )
                SheetNote(
                    text = "可以放心退出这个面板，下载会继续。中途断网也没关系，" +
                        "下次点继续会从这里接着下。",
                    tone = PastelTone.SKY,
                )
                SheetSecondaryButton(
                    text = "取消下载",
                    enabled = true,
                    onClick = viewModel::cancelModelDownload,
                )
            }

            LocalModelState.Verifying -> {
                SheetProgressBar(fraction = null, tone = PastelTone.SKY)
                Text(
                    text = "正在校验文件完整性并加载模型，大约需要几秒。",
                    style = MaterialTheme.typography.bodySmall,
                    color = FocusTheme.colors.textSecondary,
                )
            }

            is LocalModelState.Ready -> {
                SheetStatRow(
                    label = "大小",
                    value = formatBytes(state.sizeBytes),
                    emphasized = true,
                )
                SheetStatRow(label = "向量维度", value = "${state.dimension} 维")
                SheetNote(
                    text = "模型已经在本机了。记忆的向量化会完全在这里完成，" +
                        "不会再发出任何请求 —— 包括你写下的那些最私密的内容。",
                    tone = PastelTone.MINT,
                )
                SheetSecondaryButton(
                    text = "删除模型（释放 ${formatBytes(state.sizeBytes)}）",
                    enabled = true,
                    onClick = viewModel::deleteModel,
                )
            }

            is LocalModelState.Failed -> {
                SheetNote(
                    title = "下载没有成功",
                    text = state.message,
                    tone = PastelTone.BLUSH,
                )
                SheetPrimaryButton(
                    text = "重试",
                    enabled = true,
                    onClick = viewModel::startModelDownload,
                )
            }
        }
    }

    SheetNote(
        text = "本地模型是可选的。嫌它占地方就继续用在线端点，两条路随时可以切换，" +
            "切换时会自动重算维度对不上的旧向量。",
        tone = PastelTone.SKY,
    )
}

// ---------------------------------------------------------------------------
// 格式化
// ---------------------------------------------------------------------------

/** 字节数转人话。速度也用同一个函数 —— 它接受的是「每秒多少字节」，量纲刚好一致。 */
private fun formatBytes(bytes: Long): String = when {
    bytes <= 0L -> "0 B"
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
}

/** 剩余时间转人话。速度还没测出来时给一句「计算中」而不是一个假的 0。 */
private fun formatRemaining(millis: Long): String = when {
    millis < 0L -> "计算中…"
    millis < 1000L -> "不到 1 秒"
    millis < 60_000L -> "${millis / 1000} 秒"
    else -> "${millis / 60_000} 分 ${(millis % 60_000) / 1000} 秒"
}
