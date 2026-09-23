package com.focussupervisor.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import com.focussupervisor.app.domain.model.EmbeddingConfig
import com.focussupervisor.app.ui.theme.FocusTheme

/**
 * 向量模型配置页。
 *
 * **完全独立于「AI 配置」**：独立入口、独立页面、独立存储。
 *
 * 为什么不跟对话配置合用一份：现实中这两件事经常不在一家 ——
 * DeepSeek 没有公开的 embeddings 接口，中转站往往只代理对话模型。
 * 硬塞在一份配置里，用户为了让记忆用上向量，就得把对话模型也切走，
 * 那不是他能接受的取舍。
 *
 * 这个页面刻意**不做**成「跟对话配置一样的表单」：
 *  - 顶部一段说明，讲清它是什么、不配会怎样 —— 因为它是可选的，
 *    用户点进来时往往不确定自己需要不需要；
 *  - 三个字段，没有预设、没有温度、没有前置提示。向量模型只有一个职责：把文本
 *    变成向量，别的参数对它没有意义。
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
        containerColor = FocusTheme.colors.chromeBackground,
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

            SheetTextField(
                label = "Base URL",
                value = uiState.draft.baseUrl,
                onValueChange = viewModel::onBaseUrlChange,
                placeholder = EmbeddingConfig.OLLAMA_BASE_URL,
                monospace = true,
                footer = "可以和对话端点不同。填域名即可，会自动尝试补 /v1。",
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

            SheetMessageLine(message = uiState.message, isError = uiState.isMessageError)

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SheetPrimaryButton(
                    text = if (uiState.isSaving) "保存中…" else "保存",
                    enabled = !uiState.isBusy,
                    onClick = viewModel::save,
                    modifier = Modifier.weight(1f),
                )
                SheetSecondaryButton(
                    text = if (uiState.isTesting) "测试中…" else "测试连接",
                    enabled = uiState.canTest,
                    onClick = viewModel::testConnection,
                    modifier = Modifier.weight(1f),
                )
            }

            Text(
                text = "测试连接会真的发一次向量化请求（只发一个词），用来确认地址、Key " +
                    "与模型名都对得上。",
                style = MaterialTheme.typography.bodySmall,
                color = FocusTheme.colors.textSecondary,
                modifier = Modifier.heightIn(min = 0.dp),
            )
        }
    }
}
