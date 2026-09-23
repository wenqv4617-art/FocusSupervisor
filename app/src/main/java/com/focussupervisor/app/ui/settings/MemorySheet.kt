package com.focussupervisor.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.focussupervisor.app.domain.model.MemoryDefaults
import com.focussupervisor.app.domain.model.MemoryEntry
import com.focussupervisor.app.domain.model.MemoryTier
import com.focussupervisor.app.ui.theme.FocusTheme

/**
 * 记忆管理面板。
 *
 * 三块卡片，对应三个问题：
 * ```
 *   ┌ 索引状况 ────────────────┐   我的记忆现在是什么状态？
 *   ┌ 写入一条记忆 ─────────────┐   我要记住一件事
 *   ┌ 已记住的内容 ─────────────┐   它记住了什么，层级对不对
 * ```
 *
 * 「索引状况」放在最上面是有意的：这个功能的内部机制（向量、召回、提升）对用户
 * 是黑盒，不给一个可观测的数字，他无法判断「记忆到底是活的还是坏的」。
 * 一行「已索引 128 条 · 已向量化 0 条」加一句结论，就能解释掉大部分困惑。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemorySheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MemoryViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
        sheetState = sheetState,
        containerColor = FocusTheme.colors.chromeBackground,
        contentColor = FocusTheme.colors.textPrimary,
    ) {
        MemoryContent(
            uiState = uiState,
            onDraftChange = viewModel::onDraftChange,
            onDraftTierChange = viewModel::onDraftTierChange,
            onDraftPinnedChange = viewModel::onDraftPinnedChange,
            onFilterChange = viewModel::onFilterChange,
            onWrite = viewModel::writeMemory,
            onForget = viewModel::forget,
            onTogglePinned = viewModel::togglePinned,
            onChangeTier = viewModel::changeTier,
            onEmbedNow = viewModel::embedNow,
            onClearAll = viewModel::clearAll,
            onClose = onDismiss,
        )
    }
}

@Composable
private fun MemoryContent(
    uiState: MemoryUiState,
    onDraftChange: (String) -> Unit,
    onDraftTierChange: (MemoryTier) -> Unit,
    onDraftPinnedChange: (Boolean) -> Unit,
    onFilterChange: (MemoryTier?) -> Unit,
    onWrite: () -> Unit,
    onForget: (String) -> Unit,
    onTogglePinned: (MemoryEntry) -> Unit,
    onChangeTier: (MemoryEntry, MemoryTier) -> Unit,
    onEmbedNow: () -> Unit,
    onClearAll: () -> Unit,
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
            SheetTitle(text = "记忆管理", modifier = Modifier.weight(1f))
            TextButton(onClick = onClose) {
                Text(
                    text = "关闭",
                    style = MaterialTheme.typography.bodySmall,
                    color = FocusTheme.colors.textSecondary,
                )
            }
        }

        // ---------------- 索引状况 ----------------
        SheetSection(
            title = "索引状况",
            subtitle = indexConclusion(uiState),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                StatCell(label = "已索引", value = uiState.indexedCount.toString(), modifier = Modifier.weight(1f))
                StatCell(label = "已向量化", value = uiState.embeddedCount.toString(), modifier = Modifier.weight(1f))
                StatCell(label = "待补", value = uiState.pendingEmbeddingCount.toString(), modifier = Modifier.weight(1f))
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SheetSecondaryButton(
                    text = if (uiState.isEmbedding) "补向量中…" else "补齐向量",
                    enabled = !uiState.isEmbedding,
                    onClick = onEmbedNow,
                    modifier = Modifier.weight(1f),
                )
                SheetSecondaryButton(
                    text = "清空全部",
                    enabled = !uiState.isEmbedding,
                    onClick = onClearAll,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // ---------------- 写入 ----------------
        SheetSection(
            title = "写入一条记忆",
            subtitle = "写一句自足的话，而不是关键词 —— 这段文字会被原样注入提示词。",
        ) {
            SheetTextField(
                label = "内容",
                value = uiState.draft,
                onValueChange = onDraftChange,
                placeholder = "例如：他习惯先做最难的那件事",
                singleLine = false,
                minHeight = 76,
            )

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SheetSectionLabel(text = "层级")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MemoryTier.entries.forEach { tier ->
                        SheetChip(
                            text = tier.label,
                            selected = tier == uiState.draftTier,
                            onClick = { onDraftTierChange(tier) },
                        )
                    }
                }
                Text(
                    text = "选「短期」即可：被回想 ${MemoryDefaults.PROMOTION_RECALL_THRESHOLD} 次以上的记忆，" +
                        "系统会自动把它沉入长久记忆。",
                    style = MaterialTheme.typography.bodySmall,
                    color = FocusTheme.colors.textSecondary,
                )
            }

            SheetChip(
                text = if (uiState.draftPinned) "已置顶" else "置顶（不参与自动清理）",
                selected = uiState.draftPinned,
                onClick = { onDraftPinnedChange(!uiState.draftPinned) },
            )

            SheetPrimaryButton(
                text = "写入",
                enabled = uiState.canWrite,
                onClick = onWrite,
            )

            SheetMessageLine(
                message = uiState.message,
                isError = uiState.message?.contains("失败") == true,
            )
        }

        // ---------------- 列表 ----------------
        SheetSection(
            title = "已记住的内容（${uiState.memories.size}）",
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SheetChip(
                    text = "全部",
                    selected = uiState.filter == null,
                    onClick = { onFilterChange(null) },
                )
                MemoryTier.entries.forEach { tier ->
                    SheetChip(
                        text = tier.label,
                        selected = uiState.filter == tier,
                        onClick = { onFilterChange(tier) },
                    )
                }
            }

            val visible = uiState.visibleMemories
            if (visible.isEmpty()) {
                Text(
                    text = "这里还是空的。你可以自己写一条，也可以在对话里让 AI 记住某件事。",
                    style = MaterialTheme.typography.bodySmall,
                    color = FocusTheme.colors.textSecondary,
                )
            } else {
                visible.forEachIndexed { index, entry ->
                    if (index > 0) {
                        HorizontalDivider(thickness = 0.5.dp, color = FocusTheme.colors.hairline)
                    }
                    MemoryRow(
                        entry = entry,
                        onTogglePinned = { onTogglePinned(entry) },
                        onChangeTier = { tier -> onChangeTier(entry, tier) },
                        onForget = { onForget(entry.id) },
                    )
                }
            }
        }
    }
}

/**
 * 索引状况的一句话结论。
 *
 * 光给三个数字，用户还是不知道意味着什么。结论那行才是真正有用的部分。
 */
private fun indexConclusion(uiState: MemoryUiState): String = when {
    uiState.indexedCount == 0 -> "还没有任何内容进入索引。聊几句，或者在上面写一条。"
    uiState.embeddedCount == 0 ->
        "目前全部靠关键词匹配召回。配一个向量模型（「+」→「向量模型」）之后质量会明显提升。"

    uiState.pendingEmbeddingCount > 0 ->
        "还有 ${uiState.pendingEmbeddingCount} 条没向量化，它们暂时只能靠关键词匹配。"

    else -> "全部已向量化，召回走余弦相似度主路径。"
}

@Composable
private fun StatCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = FocusTheme.colors.textPrimary,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = FocusTheme.colors.textSecondary,
        )
    }
}

/**
 * 一条记忆。
 *
 * 显示「被召回 N 次」而不是「创建于什么时候」更有用：前者直接解释了
 * 「它为什么还停在短期层」或者「它为什么被提升上来了」。
 */
@Composable
private fun MemoryRow(
    entry: MemoryEntry,
    onTogglePinned: () -> Unit,
    onChangeTier: (MemoryTier) -> Unit,
    onForget: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            TierBadge(
                text = entry.tier.label,
                emphasized = entry.tier == MemoryTier.CORE,
            )
            if (entry.pinned) {
                TierBadge(text = "置顶", emphasized = false)
            }
            if (!entry.hasEmbedding) {
                TierBadge(text = "未向量化", emphasized = false)
            }
            Box(modifier = Modifier.weight(1f))
            Text(
                text = "召回 ${entry.recallCount} 次",
                style = MaterialTheme.typography.labelSmall,
                color = FocusTheme.colors.textSecondary,
            )
        }

        Text(
            text = entry.content,
            style = MaterialTheme.typography.bodyMedium,
            color = FocusTheme.colors.textPrimary,
        )

        Text(
            text = "来自${entry.source.label}",
            style = MaterialTheme.typography.labelSmall,
            color = FocusTheme.colors.textSecondary,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            TextButton(onClick = onTogglePinned) {
                Text(
                    text = if (entry.pinned) "取消置顶" else "置顶",
                    style = MaterialTheme.typography.bodySmall,
                    color = FocusTheme.colors.textSecondary,
                )
            }

            // 只在「长久」和「短期」之间提供升降；核心记忆要靠手动指定，
            // 不提供「一键升为核心」——那会让核心记忆失去「精炼」的意义。
            val targetTier = when (entry.tier) {
                MemoryTier.SHORT_TERM -> MemoryTier.LONG_TERM
                MemoryTier.LONG_TERM -> MemoryTier.SHORT_TERM
                MemoryTier.CORE -> null
            }
            if (targetTier != null) {
                TextButton(onClick = { onChangeTier(targetTier) }) {
                    Text(
                        text = if (targetTier == MemoryTier.LONG_TERM) "沉入长久" else "降回短期",
                        style = MaterialTheme.typography.bodySmall,
                        color = FocusTheme.colors.accent,
                    )
                }
            }

            TextButton(onClick = onForget) {
                Text(
                    text = "删除",
                    style = MaterialTheme.typography.bodySmall,
                    color = FocusTheme.colors.attention,
                )
            }
        }
    }
}
