package com.focussupervisor.app.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.focussupervisor.app.appContainer
import com.focussupervisor.app.domain.model.MemoryDefaults
import com.focussupervisor.app.domain.model.MemoryEntry
import com.focussupervisor.app.domain.model.MemorySource
import com.focussupervisor.app.domain.model.MemoryTier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 记忆管理面板的状态。
 *
 * @param filter 当前筛选的层级；null 表示看全部
 * @param draft 正在输入的待写入记忆
 * @param indexedCount 已索引的文档数（记忆条目 + 对话原文）
 * @param embeddedCount 其中已被向量化的条数
 * @param isEmbedding 正在补向量
 */
data class MemoryUiState(
    val memories: List<MemoryEntry> = emptyList(),
    val filter: MemoryTier? = null,
    val draft: String = "",
    val draftTier: MemoryTier = MemoryTier.SHORT_TERM,
    val draftPinned: Boolean = false,
    val indexedCount: Int = 0,
    val embeddedCount: Int = 0,
    val isEmbedding: Boolean = false,
    val message: String? = null,
) {
    /** 按当前筛选条件可见的记忆。 */
    val visibleMemories: List<MemoryEntry>
        get() = if (filter == null) memories else memories.filter { it.tier == filter }

    val canWrite: Boolean get() = draft.isNotBlank()

    /** 向量覆盖率，用于在界面上显示「多少条还没向量化」。 */
    val pendingEmbeddingCount: Int get() = (indexedCount - embeddedCount).coerceAtLeast(0)
}

/**
 * 记忆面板的状态持有者。
 *
 * 它把「用户手写记忆」与「向量化进度」这两件事放在同一个界面上，是因为它们
 * 互相解释：用户看到「索引 128 条 · 已向量化 0 条」时立刻能明白
 * 「我还没配向量模型，现在靠关键词匹配」—— 比在别处写一段说明文字有效得多。
 */
class MemoryViewModel(application: Application) : AndroidViewModel(application) {

    private val container = application.appContainer
    private val repository = container.memory
    private val engine = container.conversationEngine

    private val _uiState = MutableStateFlow(MemoryUiState())
    val uiState: StateFlow<MemoryUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                repository.memories,
                repository.indexedDocumentCount,
                repository.embeddedDocumentCount,
            ) { memories, indexed, embedded -> Triple(memories, indexed, embedded) }
                .collect { (memories, indexed, embedded) ->
                    _uiState.update {
                        it.copy(
                            memories = memories,
                            indexedCount = indexed,
                            embeddedCount = embedded,
                        )
                    }
                }
        }

        // 打开面板时顺手补一次索引：用户刚刚可能写了记忆、或者聊了几句。
        viewModelScope.launch { repository.syncIndex() }
    }

    fun onDraftChange(value: String) {
        _uiState.update { it.copy(draft = value.take(MAX_DRAFT_LENGTH), message = null) }
    }

    fun onDraftTierChange(tier: MemoryTier) {
        _uiState.update { it.copy(draftTier = tier) }
    }

    fun onDraftPinnedChange(pinned: Boolean) {
        _uiState.update { it.copy(draftPinned = pinned) }
    }

    fun onFilterChange(tier: MemoryTier?) {
        _uiState.update { it.copy(filter = tier) }
    }

    /** 用户手写一条记忆。 */
    fun writeMemory() {
        val state = _uiState.value
        if (!state.canWrite) return

        viewModelScope.launch {
            val entry = repository.remember(
                content = state.draft,
                tier = state.draftTier,
                source = MemorySource.USER,
                pinned = state.draftPinned,
            )
            if (entry == null) {
                _uiState.update { it.copy(message = "写入失败，请稍后重试") }
            } else {
                _uiState.update {
                    it.copy(draft = "", draftPinned = false, message = "已写入${entry.tier.label}")
                }
                // 新记忆立刻进索引，并顺手尝试向量化。
                repository.syncIndex()
                engine.embedPendingMemories()
            }
        }
    }

    fun forget(id: String) {
        viewModelScope.launch {
            if (!repository.forget(id)) {
                _uiState.update { it.copy(message = "删除失败") }
            }
        }
    }

    fun togglePinned(entry: MemoryEntry) {
        viewModelScope.launch { repository.setPinned(entry.id, !entry.pinned) }
    }

    /**
     * 手动提升 / 降级层级。
     *
     * 提供这条路是必要的：自动提升依赖「被召回 3 次」，而一条明显重要的记忆
     * 完全可能还没被召回够 3 次。用户的眼睛永远比计数准。
     */
    fun changeTier(entry: MemoryEntry, tier: MemoryTier) {
        viewModelScope.launch { repository.setTier(entry.id, tier) }
    }

    /** 手动触发一次向量化。没配向量模型时它会静默返回 0。 */
    fun embedNow() {
        if (_uiState.value.isEmbedding) return

        _uiState.update { it.copy(isEmbedding = true, message = null) }
        viewModelScope.launch {
            val count = engine.embedPendingMemories()
            _uiState.update {
                it.copy(
                    isEmbedding = false,
                    message = when {
                        count > 0 -> "已向量化 $count 条"
                        !container.aiConfig.embeddingConfigNow().isUsable ->
                            "还没配置向量模型（「+」→「向量模型」），当前靠关键词匹配召回"

                        else -> "没有待向量化的内容"
                    },
                )
            }
        }
    }

    fun clearAll() {
        viewModelScope.launch {
            repository.clearAll()
            _uiState.update { it.copy(message = "已清空全部记忆与索引") }
        }
    }

    private companion object {
        /** 手写记忆的长度上限，与 [MemoryDefaults.MAX_MEMORY_LENGTH] 保持一致。 */
        const val MAX_DRAFT_LENGTH = 500
    }
}
