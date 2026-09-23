package com.focussupervisor.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
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
 * 三段结构，对应三个问题：
 * ```
 *   索引状况   我的记忆现在是什么状态？（多少条进了索引、多少条还没向量化）
 *   写入       我要记住一件事
 *   列表       它记住了什么，层级对不对
 * ```
 *
 * 把「索引状况」放在最上面是有意的：这个功能的内部机制（向量、召回、提升）
 * 对用户是黑盒，如果不给一个可观测的数字，他无法判断「记忆到底是活的还是坏的」。
 * 一行「索引 128 条 · 已向量化 0 条 · 靠关键词匹配」就能解释掉大部分困惑。
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
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = SheetHorizontalPadding)
            .padding(bottom = SheetBottomPadding),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SheetTitle(text = "记忆管理")

        // ---------------- 索引状况 ----------------
        IndexStatusBlock(uiState = uiState, onEmbedNow = onEmbedNow, onClearAll = onClearAll)

        HorizontalDivider(thickness = 0.5.dp, color = FocusTheme.colors.hairline)

        // ---------------- 写入 ----------------
        SheetSectionLabel(text = "写入一条记忆")
        SheetTextField(
            label = "内容",
            value = uiState.draft,
            onValueChange = onDraftChange,
            placeholder = "一句自足的话，例如「他习惯先做最难的那件事」",
            singleLine = false,
            minHeight = 76,
            footer = "写「一句话」而不是「关键词」—— 这段文字会被原样注入提示词。",
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
                text = "选「短期」即可：被反复回想起来的记忆，系统会自动把它沉入长久记忆。",
                style = MaterialTheme.typography.bodySmall,
                color = FocusTheme.colors.textSecondary,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SheetChip(
                text = if (uiState.draftPinned) "已置顶" else "置顶",
                selected = uiState.draftPinned,
                onClick = { onDraftPinnedChange(!uiState.draftPinned) },
            )
        }

        SheetPrimaryButton(
            text = "写入",
            enabled = uiState.canWrite,
            onClick = onWrite,
        )

        SheetMessageLine(
            message = uiState.message,
            isError = uiState.message?.contains("失败") == true,
        )

        HorizontalDivider(thickness = 0.5.dp, color = FocusTheme.colors.hairline)

        // ---------------- 列表 ----------------
        SheetSectionLabel(text = "已记住的内容（${uiState.memories.size}）")
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
            visible.forEach { entry ->
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

/**
 * 索引状况。
 *
 * 三个数字 + 一句结论。结论那行是关键：光给数字用户还是不知道意味着什么。
 */
@Composable
private fun IndexStatusBlock(
    uiState: MemoryUiState,
    onEmbedNow: () -> Unit,
    onClearAll: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SheetSectionLabel(text = "索引状况")
        Text(
            text = "已索引 ${uiState.indexedCount} 条 · 已向量化 ${uiState.embeddedCount} 条",
            style = MaterialTheme.typography.bodyMedium,
            color = FocusTheme.colors.textPrimary,
        )
        Text(
            text = when {
                uiState.indexedCount == 0 -> "还没有任何内容进入索引。"
                uiState.embeddedCount == 0 ->
                    "全部靠关键词匹配召回。配一个向量模型之后召回质量会明显提升。"

                uiState.pendingEmbeddingCount > 0 ->
                    "还有 ${uiState.pendingEmbeddingCount} 条没向量化，它们暂时只能靠关键词匹配。"

                else -> "全部已向量化，召走的是余弦相似度主路径。"
            },
            style = MaterialTheme.typography.bodySmall,
            color = FocusTheme.colors.textSecondary,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = onEmbedNow, enabled = !uiState.isEmbedding) {
                Text(
                    text = if (uiState.isEmbedding) "补向量中…" else "补齐向量",
                    style = MaterialTheme.typography.bodySmall,
                    color = FocusTheme.colors.accent,
                )
            }
            TextButton(onClick = onClearAll) {
                Text(
                    text = "清空全部",
                    style = MaterialTheme.typography.bodySmall,
                    color = FocusTheme.colors.textSecondary,
                )
            }
        }
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
            .padding(vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = entry.content,
            style = MaterialTheme.typography.bodyMedium,
            color = FocusTheme.colors.textPrimary,
        )
        Text(
            text = buildString {
                append(entry.tier.label)
                append(" · ").append(entry.source.label)
                append(" · 被召回 ").append(entry.recallCount).append(" 次")
                if (entry.pinned) append(" · 已置顶")
                if (!entry.hasEmbedding) append(" · 未向量化")
            },
            style = MaterialTheme.typography.bodySmall,
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

        HorizontalDivider(thickness = 0.5.dp, color = FocusTheme.colors.hairline)
    }
}

/** 让面板能引用到这个常量做文案，避免两处各写一遍「3 次」。 */
private val promotionHint: Int get() = MemoryDefaults.PROMOTION_RECALL_THRESHOLD
