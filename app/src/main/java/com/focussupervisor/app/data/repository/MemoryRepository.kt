package com.focussupervisor.app.data.repository

import android.util.Log
import com.focussupervisor.app.core.ai.TextVectorizer
import com.focussupervisor.app.data.datastore.AppPreferences
import com.focussupervisor.app.data.datastore.AppPreferencesDataSource
import com.focussupervisor.app.domain.model.IndexedDocKind
import com.focussupervisor.app.domain.model.IndexedDocument
import com.focussupervisor.app.domain.model.MemoryDefaults
import com.focussupervisor.app.domain.model.MemoryEntry
import com.focussupervisor.app.domain.model.MemoryHit
import com.focussupervisor.app.domain.model.MemorySource
import com.focussupervisor.app.domain.model.MemoryTier
import com.focussupervisor.app.domain.model.MessageSender
import com.focussupervisor.app.domain.model.RecallResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * 把一批文本转向量的能力。
 *
 * 定义成 `fun interface` 而不是让记忆仓库直接依赖 `OpenAiCompatibleClient`：
 *  - 记忆仓库因此**完全不知道网络的存在**，可以脱离 Android 单独测；
 *  - 换一家 embedding 服务时只需要换一个实现；
 *  - 没配向量模型时，上层传一个「总是失败」的实现即可，仓库代码一行不用改。
 */
fun interface TextEmbedder {
    suspend fun embed(texts: List<String>): Result<List<List<Float>>>
}

/**
 * 数据层：记忆仓库
 *
 * ===========================================================================
 * 三层记忆是怎么流动的
 * ===========================================================================
 * ```
 *   写入（用户 / AI）
 *        │
 *        ▼
 *   短期记忆 ──被召回 3 次──▶ 长久记忆
 *        │                        │
 *        └────────┬───────────────┘
 *                 ▼
 *          向量索引（记忆条目 + 对话原文共用同一个向量空间）
 *                 │
 *                 ▼
 *          召回：余弦相似度 Top-K ──▶ 注入提示词
 * ```
 *
 * **提升机制的关键在于「召回」会反过来改数据**：每次召回都会给命中的记忆
 * `recallCount + 1`，累计到阈值就自动把短期记忆提升为长久记忆。所以这个类里
 * 唯一一个「读操作会写数据」的方法就是 [recall] —— 这是刻意的，注释里会再强调一次。
 *
 * ===========================================================================
 * 没有向量模型时会怎样
 * ===========================================================================
 * 不会失灵，只会变差：文档照样进索引，只是 `embedding` 为空，召回时自动退回
 * 关键词打分（[TextVectorizer.lexicalScore]）。提升机制照常工作 ——
 * 因为它依据的是「被召回次数」，而不是「是否用了向量」。
 *
 * 等用户补上向量模型，[embedPending] 会把积压的文档批量补上向量，无需重建索引。
 */
interface MemoryRepository {

    /** 全部记忆，新的在前。 */
    val memories: StateFlow<List<MemoryEntry>>

    /** 已被索引的文档数（记忆 + 对话）。用于在界面上显示索引规模。 */
    val indexedDocumentCount: StateFlow<Int>

    /** 已被向量化的文档数。和上一个一起看，就知道有多少还在排队。 */
    val embeddedDocumentCount: StateFlow<Int>

    /** 写一条记忆。@return 新建的条目；失败返回 null。 */
    suspend fun remember(
        content: String,
        tier: MemoryTier = MemoryTier.SHORT_TERM,
        source: MemorySource,
        pinned: Boolean = false,
    ): MemoryEntry?

    /** 删除一条记忆。 */
    suspend fun forget(id: String): Boolean

    /** 钉住 / 取消钉住。钉住的不会被自动清理，也不参与自动提升。 */
    suspend fun setPinned(id: String, pinned: Boolean): Boolean

    /** 手动调整层级。 */
    suspend fun setTier(id: String, tier: MemoryTier): Boolean

    /**
     * 把新出现的记忆条目与对话原文加入索引。
     *
     * **不涉及网络**：新文档先以「无向量」的状态入库，保证关键词召回立刻可用。
     * 向量由 [embedPending] 补。
     */
    suspend fun syncIndex(): Boolean

    /**
     * 给还没有向量的文档补向量，一次最多 [MemoryDefaults.EMBED_BATCH_SIZE] 条。
     *
     * @return 本次成功向量化的条数
     */
    suspend fun embedPending(embedder: TextEmbedder): Int

    /**
     * 召回。
     *
     * **注意：这个方法会写数据。** 命中的记忆条目 `recallCount` 会 +1，
     * 达到阈值时层级会被提升为长久记忆。这不是副作用，而是设计要求 ——
     * 「反复被想到的事才值得长期记住」这条规则就实现在这里。
     *
     * @param queryEmbedding 当前输入的向量；为 null 时退回关键词打分
     */
    suspend fun recall(
        queryText: String,
        queryEmbedding: List<Float>?,
        limit: Int = MemoryDefaults.RECALL_TOP_K,
    ): RecallResult

    /** 核心记忆，按写入时间倒序。 */
    fun coreMemories(): List<MemoryEntry>

    /** 最近的短期记忆。 */
    fun recentShortTerm(limit: Int = MemoryDefaults.MAX_SHORT_TERM_IN_PROMPT): List<MemoryEntry>

    /** 清空全部记忆与索引。 */
    suspend fun clearAll(): Boolean
}

/**
 * [MemoryRepository] 的 DataStore 实现。
 */
class DataStoreMemoryRepository(
    private val preferences: AppPreferencesDataSource,
    scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) : MemoryRepository {

    private val _memories = MutableStateFlow<List<MemoryEntry>>(emptyList())
    override val memories: StateFlow<List<MemoryEntry>> = _memories.asStateFlow()

    private val _indexedDocumentCount = MutableStateFlow(0)
    override val indexedDocumentCount: StateFlow<Int> = _indexedDocumentCount.asStateFlow()

    private val _embeddedDocumentCount = MutableStateFlow(0)
    override val embeddedDocumentCount: StateFlow<Int> = _embeddedDocumentCount.asStateFlow()

    /**
     * 最近一次从 DataStore 读到的整份配置。
     *
     * 召回、索引同步都需要「当前的对话列表」和「当前的索引」，而 DataStore 的 Flow
     * 没有 `.value`。自己缓存一份，比每次去 `preferences.first()` 重新解码整份 JSON
     * 便宜一个数量级。
     */
    @Volatile
    private var latest: AppPreferences = AppPreferences()

    init {
        scope.launch {
            preferences.preferences.collect { prefs ->
                latest = prefs
                _memories.value = prefs.memories
                _indexedDocumentCount.value = prefs.memoryIndex.size
                _embeddedDocumentCount.value = prefs.memoryIndex.count { it.embedding.isNotEmpty() }
            }
        }
    }

    // -----------------------------------------------------------------------
    // 写入
    // -----------------------------------------------------------------------

    override suspend fun remember(
        content: String,
        tier: MemoryTier,
        source: MemorySource,
        pinned: Boolean,
    ): MemoryEntry? {
        val trimmed = content.trim().take(MAX_CONTENT_LENGTH)
        if (trimmed.isEmpty()) return null

        val entry = MemoryEntry(
            id = "mem-${UUID.randomUUID()}",
            content = trimmed,
            tier = tier,
            source = source,
            createdAtMillis = clock(),
            pinned = pinned,
        )

        return if (write("保存记忆") { preferences.upsertMemory(entry) }) entry else null
    }

    override suspend fun forget(id: String): Boolean =
        write("删除记忆") { preferences.removeMemory(id) }

    override suspend fun setPinned(id: String, pinned: Boolean): Boolean =
        updateMemory(id) { it.copy(pinned = pinned) }

    override suspend fun setTier(id: String, tier: MemoryTier): Boolean =
        updateMemory(id) { it.copy(tier = tier) }

    override suspend fun clearAll(): Boolean = write("清空记忆") {
        preferences.replaceMemories(emptyList())
        preferences.replaceIndex(emptyList())
    }

    /**
     * 改一条记忆。
     *
     * 从 [latest] 里取当前值、改完、整份写回。这里不用 DataStore 的原子 edit，
     * 是因为「召回计数累加」需要在同一个逻辑步骤里同时决定「要不要提升层级」，
     * 那是一个跨字段的判断，写成一个纯函数（`applyRecall`）比塞进 edit 块清晰得多。
     * 代价是并发写会丢更新 —— 而记忆的写入方只有用户和 AI，两者实际上不会同时发生。
     */
    private suspend fun updateMemory(
        id: String,
        transform: (MemoryEntry) -> MemoryEntry,
    ): Boolean {
        val current = latest.memories
        if (current.none { it.id == id }) return false

        val next = current.map { if (it.id == id) transform(it) else it }
        return write("更新记忆") { preferences.replaceMemories(next) }
    }

    // -----------------------------------------------------------------------
    // 索引
    // -----------------------------------------------------------------------

    override suspend fun syncIndex(): Boolean {
        val prefs = latest
        val existingIds = prefs.memoryIndex.mapTo(mutableSetOf()) { it.id }

        val additions = mutableListOf<IndexedDocument>()

        // 记忆条目（id 前缀去重，避免和对话文档撞 id）
        prefs.memories.forEach { entry ->
            val docId = memoryDocId(entry.id)
            if (docId !in existingIds) {
                additions += IndexedDocument(
                    id = docId,
                    kind = IndexedDocKind.MEMORY,
                    text = entry.content,
                    createdAtMillis = entry.createdAtMillis,
                    embedding = entry.embedding,
                    memoryId = entry.id,
                )
            }
        }

        // 对话原文。系统胶囊不索引 —— 它们是界面状态播报，不是「说过的话」。
        prefs.messages.forEach { message ->
            if (message.sender == MessageSender.SYSTEM) return@forEach
            val docId = messageDocId(message.id)
            if (docId !in existingIds) {
                additions += IndexedDocument(
                    id = docId,
                    kind = IndexedDocKind.CONVERSATION,
                    text = message.text,
                    createdAtMillis = message.timestampMillis,
                )
            }
        }

        val merged = prefs.memoryIndex + additions
        val trimmed = enforceIndexLimit(merged)

        val changed = additions.isNotEmpty() || trimmed.size != merged.size
        if (!changed) return true

        return write("同步记忆索引") { preferences.replaceIndex(trimmed) }
    }

    /**
     * 索引容量控制。
     *
     * 淘汰顺序：**先丢最旧的对话原文，记忆条目只在超过自己的上限时才丢最旧的**。
     * 因为对话原文是可再生的（原文还在 `messages` 里，只是不再参与语义召回），
     * 而记忆条目一旦被丢掉就真的没了。
     */
    private fun enforceIndexLimit(documents: List<IndexedDocument>): List<IndexedDocument> {
        val memoryDocs = documents.filter { it.kind == IndexedDocKind.MEMORY }
            .sortedByDescending { it.createdAtMillis }
            .take(MemoryDefaults.MAX_MEMORY_ENTRIES)

        val conversationDocs = documents.filter { it.kind == IndexedDocKind.CONVERSATION }
            .sortedByDescending { it.createdAtMillis }
            .take(MemoryDefaults.MAX_CONVERSATION_DOCS)

        return memoryDocs + conversationDocs
    }

    override suspend fun embedPending(embedder: TextEmbedder): Int {
        val prefs = latest
        val pending = prefs.memoryIndex
            .filter { it.embedding.isEmpty() }
            .takeLast(MemoryDefaults.EMBED_BATCH_SIZE)

        if (pending.isEmpty()) return 0

        val result = try {
            embedder.embed(pending.map { it.text })
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w(TAG, "向量化请求失败，本轮跳过", t)
            return 0
        }

        val vectors = result.getOrNull() ?: return 0
        if (vectors.size != pending.size) {
            // 数量对不上说明端点行为不符合协议，与其错位写入不如整轮放弃。
            Log.w(TAG, "向量数量与文本数量不一致（${vectors.size} vs ${pending.size}），本轮放弃")
            return 0
        }

        val vectorsById = pending.mapIndexed { index, doc -> doc.id to vectors[index] }.toMap()
        val updated = prefs.memoryIndex.map { doc ->
            val vector = vectorsById[doc.id]
            if (vector == null || vector.isEmpty()) doc else doc.copy(embedding = vector)
        }

        // 记忆条目自己也留一份向量：这样即使索引被淘汰，记忆仍能参与向量召回。
        val updatedMemories = prefs.memories.map { entry ->
            val vector = vectorsById[memoryDocId(entry.id)]
            if (vector == null || vector.isEmpty()) entry else entry.copy(embedding = vector)
        }

        val embeddedCount = vectorsById.values.count { it.isNotEmpty() }
        if (embeddedCount == 0) return 0

        write("写入向量") {
            preferences.replaceIndex(updated)
            if (updatedMemories != prefs.memories) {
                preferences.replaceMemories(updatedMemories)
            }
        }
        return embeddedCount
    }

    // -----------------------------------------------------------------------
    // 召回
    // -----------------------------------------------------------------------

    override suspend fun recall(
        queryText: String,
        queryEmbedding: List<Float>?,
        limit: Int,
    ): RecallResult {
        val prefs = latest
        if (prefs.memoryIndex.isEmpty()) return RecallResult.EMPTY

        val normalizedQuery = queryEmbedding
            ?.takeIf { it.isNotEmpty() }
            ?.let { normalize(it) }

        val scored = prefs.memoryIndex.mapNotNull { doc ->
            val score = if (normalizedQuery != null &&
                doc.embedding.isNotEmpty() &&
                doc.embedding.size == normalizedQuery.size
            ) {
                TextVectorizer.cosine(normalizedQuery, doc.embedding)
            } else {
                TextVectorizer.lexicalScore(queryText, doc.text)
            }

            if (score < MemoryDefaults.RECALL_MIN_SCORE) null else doc to score
        }
            .sortedByDescending { it.second }
            .take(limit)

        if (scored.isEmpty()) return RecallResult.EMPTY

        val memoryById = prefs.memories.associateBy { it.id }

        val memoryHits = scored
            .filter { it.first.kind == IndexedDocKind.MEMORY }
            .mapNotNull { (doc, score) ->
                memoryById[doc.memoryId]?.let { MemoryHit(it, score) }
            }

        val snippets = scored
            .filter { it.first.kind == IndexedDocKind.CONVERSATION }
            .map { it.first.text }

        bumpRecallCounts(memoryHits.map { it.entry.id })

        return RecallResult(memories = memoryHits, conversationSnippets = snippets)
    }

    /**
     * 累加召回计数，并把够格的短期记忆提升为长久记忆。
     *
     * 这是整个记忆系统里唯一「读操作写数据」的地方，理由见类注释。
     */
    private suspend fun bumpRecallCounts(ids: List<String>) {
        if (ids.isEmpty()) return

        val now = clock()
        val idSet = ids.toSet()
        val current = latest.memories

        var promoted = 0
        val next = current.map { entry ->
            if (entry.id !in idSet) return@map entry

            val bumped = entry.copy(
                recallCount = entry.recallCount + 1,
                lastRecalledAtMillis = now,
            )
            if (bumped.isEligibleForPromotion) {
                promoted++
                bumped.copy(tier = MemoryTier.LONG_TERM)
            } else {
                bumped
            }
        }

        if (next == current) return

        write("更新召回计数") { preferences.replaceMemories(next) }
        if (promoted > 0) {
            Log.i(TAG, "$promoted 条短期记忆因反复被召回，已沉入长久记忆")
        }
    }

    // -----------------------------------------------------------------------
    // 读取
    // -----------------------------------------------------------------------

    override fun coreMemories(): List<MemoryEntry> =
        _memories.value
            .filter { it.tier == MemoryTier.CORE }
            .take(MemoryDefaults.MAX_CORE_IN_PROMPT)

    override fun recentShortTerm(limit: Int): List<MemoryEntry> =
        _memories.value
            .filter { it.tier == MemoryTier.SHORT_TERM }
            .sortedByDescending { it.createdAtMillis }
            .take(limit)

    // -----------------------------------------------------------------------
    // 工具
    // -----------------------------------------------------------------------

    private suspend fun write(action: String, block: suspend () -> Unit): Boolean = try {
        block()
        true
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        Log.e(TAG, "$action 失败", t)
        false
    }

    private fun normalize(vector: List<Float>): List<Float> {
        var sumOfSquares = 0f
        vector.forEach { sumOfSquares += it * it }
        val norm = kotlin.math.sqrt(sumOfSquares)
        if (norm <= 0f) return vector
        return vector.map { it / norm }
    }

    private fun memoryDocId(memoryId: String) = "mem:$memoryId"

    private fun messageDocId(messageId: String) = "msg:$messageId"

    private companion object {
        const val TAG = "MemoryRepository"

        /** 单条记忆内容的长度上限，防止模型把整篇小作文塞进来。 */
        const val MAX_CONTENT_LENGTH = 500
    }
}
