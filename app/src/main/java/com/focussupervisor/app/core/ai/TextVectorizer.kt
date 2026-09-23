package com.focussupervisor.app.core.ai

import kotlin.math.sqrt

/**
 * 向量相似度与关键词兜底打分。
 *
 * ===========================================================================
 * 为什么要有兜底
 * ===========================================================================
 * 记忆检索的主路径是向量余弦相似度，但它有一个前提：用户配了向量模型。
 * 现实是很多人（尤其是用 DeepSeek 的）手上那家端点**没有 embeddings 接口** ——
 * 如果此时记忆功能整个失灵，用户会觉得「这功能是坏的」，而真正的原因只是
 * 少配了一个模型名。
 *
 * 所以这里再给一条关键词路径：中文按**字符二元组**、英文数字按整词切分，
 * 再做一次集合余弦。它比向量差得远，但「用户反复提到的事」照样能被捞出来，
 * 足以让提升机制继续工作。配了向量模型之后自动切回主路径。
 *
 * ===========================================================================
 * 为什么中文用二元组而不是分词
 * ===========================================================================
 * Android 上没有开箱即用的中文分词器，引一个词典要几 MB。而「二元组」在短文本
 * 相似度上表现相当接近粗分词，代价只有几十行代码 —— 对一个兜底路径来说，
 * 这个性价比是对的。
 */
object TextVectorizer {

    private val LATIN_RUN = Regex("[a-z0-9_]+")

    /**
     * 中文按字符区间切分。
     *
     * 区间两端写成**字面字符**而不是 `\\u4e00` 转义：Kotlin 在编译期就把 `\uXXXX`
     * 变成真字符，于是运行时交给正则引擎的是一段普通区间。Android 的正则引擎是
     * ICU，和 JVM 的转义支持并不完全一致（见 [AiCommandParser] 那次崩溃），
     * 能不依赖转义就不依赖。
     */
    private val CJK_RUN = Regex("[\u4e00-\u9fff]+")

    /**
     * 余弦相似度。
     *
     * 维度不一致直接返回 0：那说明两个向量来自**不同的向量模型**（用户中途换了
     * embedding model），此时任何比较都没有意义。返回 0 会让它自然沉底，
     * 而不是产生一个看似合理、实则胡说八道的分数。
     */
    fun cosine(a: List<Float>, b: List<Float>): Float {
        if (a.isEmpty() || b.isEmpty() || a.size != b.size) return 0f

        var dot = 0f
        var normA = 0f
        var normB = 0f
        for (index in a.indices) {
            val x = a[index]
            val y = b[index]
            dot += x * y
            normA += x * x
            normB += y * y
        }

        val denominator = sqrt(normA) * sqrt(normB)
        return if (denominator <= 0f) 0f else dot / denominator
    }

    /**
     * 关键词兜底打分，返回 0~1。
     *
     * 用「集合余弦」（交集大小除以两边集合大小的几何平均）而不是简单计数：
     * 一条很长的记忆天然会命中更多词，几何平均能把长文本的天然优势压回去，
     * 否则召回结果会永远被最长的那几条占据。
     */
    fun lexicalScore(query: String, text: String): Float {
        val queryTokens = tokenize(query).toSet()
        val textTokens = tokenize(text).toSet()
        if (queryTokens.isEmpty() || textTokens.isEmpty()) return 0f

        val intersection = queryTokens.count { it in textTokens }
        if (intersection == 0) return 0f

        return intersection.toFloat() / sqrt(queryTokens.size.toFloat() * textTokens.size.toFloat())
    }

    /**
     * 切词。
     *
     * 中文：连续汉字串切成字符二元组（单字串保留自身）。
     * 英文数字：整段作为一个词。
     * 其余字符（标点、空白、Emoji）直接丢弃。
     */
    fun tokenize(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val lower = text.lowercase()
        val tokens = mutableListOf<String>()

        LATIN_RUN.findAll(lower).forEach { match -> tokens += match.value }

        CJK_RUN.findAll(lower).forEach { match ->
            val run = match.value
            if (run.length == 1) {
                tokens += run
            } else {
                for (index in 0 until run.length - 1) {
                    tokens += run.substring(index, index + 2)
                }
            }
        }

        return tokens
    }
}
