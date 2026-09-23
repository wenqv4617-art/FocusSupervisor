package com.focussupervisor.app.domain.model

/**
 * 领域层：记忆
 *
 * ===========================================================================
 * 三层结构，以及它们各自回答的问题
 * ===========================================================================
 * | 层 | 回答的问题 | 生命周期 |
 * | --- | --- | --- |
 * | [MemoryTier.CORE]      | 「他是谁、我该怎么对待他」——不可动摇的设定 | 永不过期，用户手动维护 |
 * | [MemoryTier.SHORT_TERM]| 「最近发生了什么」 | 会衰减、会被提升或被清掉 |
 * | [MemoryTier.LONG_TERM] | 「关于他，我知道些什么」 | 长期保留 |
 *
 * ===========================================================================
 * 提升机制：短期 → 长久
 * ===========================================================================
 * 判据是 [MemoryEntry.recallCount] ——**这条记忆被向量检索命中过多少次**。
 * 一条短期记忆每被召回一次就 +1，累计到 [MemoryDefaults.PROMOTION_RECALL_THRESHOLD]
 * 就自动沉入长久记忆。
 *
 * 为什么用「被召回次数」而不是「创建时间」或「用户手动标星」：
 * 时间只能筛掉旧的，筛不掉「一次性噪音」；手动标星要用户自己判断哪条重要，
 * 而他恰恰是记不住的那个人（不然也不需要这个应用）。**反复被检索到**才是
 * 「这件事对他真的重要」的客观证据 —— 因为每一次检索都源于一句真实的当前输入。
 */

/**
 * 记忆层级。
 *
 * 枚举的**声明顺序就是注入提示词时的顺序**（核心 → 长久 → 短期）。这不是巧合：
 * 越靠前的内容对模型的影响越大，而这三层的优先级本来就该是这个顺序。
 */
enum class MemoryTier(val label: String) {
    /** 核心记忆：人设级别的设定，永不过期。 */
    CORE("核心记忆"),

    /** 长久记忆：被反复召回而沉淀下来的事实。 */
    LONG_TERM("长久记忆"),

    /** 短期记忆：最近发生的事，可能衰减。 */
    SHORT_TERM("短期记忆"),
}

/** 一条记忆是谁写进来的。 */
enum class MemorySource(val label: String) {
    USER("用户"),
    AI("AI"),
    SYSTEM("系统"),
}

/**
 * 一条记忆。
 *
 * @param id 稳定唯一标识
 * @param content 记忆正文。**这就是注入提示词时用的原文**，所以它必须是一句
 *        自足的话（「用户偏好先做最难的事」），而不是一个关键词。
 * @param tier 当前层级
 * @param source 写入方
 * @param createdAtMillis 写入时刻
 * @param lastRecalledAtMillis 最后一次被召回的时刻；从未召回为 null
 * @param recallCount 被召回次数。提升机制的唯一依据。
 * @param embedding 向量。空列表表示还没向量化 —— 此时它只能靠关键词匹配召回。
 * @param pinned 是否被用户钉住。钉住的条目不会被自动清理，也不会被自动降级。
 */
data class MemoryEntry(
    val id: String,
    val content: String,
    val tier: MemoryTier,
    val source: MemorySource,
    val createdAtMillis: Long,
    val lastRecalledAtMillis: Long? = null,
    val recallCount: Int = 0,
    val embedding: List<Float> = emptyList(),
    val pinned: Boolean = false,
) {
    val hasEmbedding: Boolean get() = embedding.isNotEmpty()

    /**
     * 是否够格提升为长久记忆。
     *
     * 核心记忆已经是最高层，不再提升；被钉住的条目用户已经表过态，也不靠计数来定。
     */
    val isEligibleForPromotion: Boolean
        get() = tier == MemoryTier.SHORT_TERM &&
            !pinned &&
            recallCount >= MemoryDefaults.PROMOTION_RECALL_THRESHOLD
}

/** 一条记忆的召回结果。 */
data class MemoryHit(
    val entry: MemoryEntry,
    val score: Float,
)

/**
 * 一次召回的完整结果。
 *
 * 记忆条目与对话原文分开返回，因为它们在提示词里落在**不同的段落**：
 * 记忆进「长久记忆 / 短期记忆」，对话原文进「相关往事」。
 * 合成一个列表就得在渲染时再判一次类型，不如在这里分清楚。
 */
data class RecallResult(
    val memories: List<MemoryHit> = emptyList(),
    val conversationSnippets: List<String> = emptyList(),
) {
    val isEmpty: Boolean get() = memories.isEmpty() && conversationSnippets.isEmpty()

    companion object {
        val EMPTY = RecallResult()
    }
}

/**
 * 被向量索引的文档类型。
 *
 * 记忆条目与**对话原文**进的是同一个向量空间，因为召回时它们是竞争关系：
 * 用户问「上次那个 bug 怎么解的」，答案可能在一条记忆里，也可能直接躺在
 * 三天前的一段对话原文里。分成两个索引就得合并两边的分数，反而更难排序。
 */
enum class IndexedDocKind {
    /** 来自一条 [MemoryEntry]。 */
    MEMORY,

    /** 来自一条对话消息原文。 */
    CONVERSATION,
}

/**
 * 索引里的一篇文档。
 *
 * @param id 文档 id：记忆是 `mem:<memoryId>`，对话是 `msg:<messageId>`
 * @param text 原文。检索命中后要把它（或其对应记忆）注入提示词，所以必须留着。
 * @param embedding 向量。空表示这条还没向量化，只能靠关键词匹配。
 * @param memoryId 当 [kind] 为 MEMORY 时指向 [MemoryEntry.id]
 */
data class IndexedDocument(
    val id: String,
    val kind: IndexedDocKind,
    val text: String,
    val createdAtMillis: Long,
    val embedding: List<Float> = emptyList(),
    val memoryId: String? = null,
)

/**
 * 记忆系统的全局参数。
 *
 * 集中放在这里而不是散在各个类里：这几个数字之间是有关系的 ——
 * 比如 [MAX_INDEX_DOCUMENTS] 太小会让召回质量下降，太大又会让每次检索的
 * 余弦计算变慢。放在一起改的时候才看得见彼此。
 */
object MemoryDefaults {

    /**
     * 短期记忆提升为长久记忆所需的召回次数。
     *
     * 取 3：一次命中可能是巧合（同一句话里恰好有个高频词），两次也比较弱，
     * 三次以上基本可以确定「这件事反复出现在他的真实输入里」。
     */
    const val PROMOTION_RECALL_THRESHOLD = 3

    /** 每次注入提示词的召回条数上限。太多会挤掉待办与近期对话的预算。 */
    const val RECALL_TOP_K = 6

    /**
     * 召回的相似度下限。
     *
     * 低于这个分数宁可什么都不注入。**召回错的记忆比不召回更有害** ——
     * 模型会把它当成确定的事实说出来，而用户无从分辨。
     */
    const val RECALL_MIN_SCORE = 0.32f

    /**
     * 对话原文进入索引的条数上限。
     *
     * 这是**整个记忆系统里最贵的一个数字**。每条对话向量按 1536 维量化后是
     * 约 2KB 的 Base64，300 条就是 600KB —— 而 DataStore 每次写入都要重写整份
     * JSON。再往上加，写入延迟就会开始被用户感觉到。
     *
     * 所以这里是明确的取舍：保留最近 300 条对话原文的向量，更早的对话仍然
     * 留在 `messages` 里（可翻看），只是不再参与语义召回。要突破这个上限，
     * 正确的做法是把索引挪出 DataStore（换成独立文件或 Room），而不是把这个数字调大。
     */
    const val MAX_CONVERSATION_DOCS = 300

    /** 记忆条目数量上限。 */
    const val MAX_MEMORY_ENTRIES = 200

    /** 一次索引请求最多带多少条文本去转向量。 */
    const val EMBED_BATCH_SIZE = 24

    /** 短期记忆的保留时长。超过且从未被召回过的短期记忆会被清掉。 */
    const val SHORT_TERM_TTL_MILLIS = 7L * 24 * 60 * 60 * 1000

    /** 注入提示词时，核心记忆最多带几条（核心记忆应当是精炼的）。 */
    const val MAX_CORE_IN_PROMPT = 12

    /** 注入提示词时，短期记忆最多带几条。 */
    const val MAX_SHORT_TERM_IN_PROMPT = 8
}
