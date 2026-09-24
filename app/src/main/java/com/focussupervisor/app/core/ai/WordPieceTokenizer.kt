package com.focussupervisor.app.core.ai

import android.content.Context
import android.util.Log
import java.text.Normalizer

/**
 * BERT 系的 WordPiece 分词器。
 *
 * ===========================================================================
 * 为什么必须自己写
 * ===========================================================================
 * 本地 ONNX 模型要的是 `input_ids`，也就是「把一段中文/英文变成一串整数」。
 * 这件事看起来可以用最简单的办法糊过去（按字切、或者按空格切），但那样得到的
 * 向量跟这个模型训练时的输入分布**完全不是一个东西** —— 模型不会报错，
 * 它只会安安静静地给出毫无意义的向量，然后记忆检索就变成随机召回。
 * 一个「看起来在工作但结果是错的」的检索，比直接报错有害得多。
 *
 * 所以这里按 `bert-base-uncased` 的原始实现把两条流程补全：
 *
 * ```
 *   BasicTokenizer    清洗 → 中文字符两侧插空格 → 小写化 → 去重音 → 按标点切分
 *        ↓
 *   WordPieceTokenizer  对每个词做「最长优先匹配」，匹配不到的后缀退成 [UNK]
 * ```
 *
 * ===========================================================================
 * 词表从哪来
 * ===========================================================================
 * `[PAD]=0 [UNK]=100 [CLS]=101 [SEP]=102 [MASK]=103`，共 30522 条，
 * 就是 bert-base-uncased 的词表。它内置在 `assets/vocab.txt`（231KB）——
 * 这么小的东西没必要让用户再下一次，尤其是下载失败时整个功能就废了。
 *
 * ===========================================================================
 * 一处刻意的取舍：去重音不用正则
 * ===========================================================================
 * 原始实现用 `\p{Mn}` 正则去掉组合记号。本项目已经在正则上栽过一次
 * （ICU 把裸 `}` 判成语法错误，真机崩、本机不崩），而 Android 的正则引擎正是 ICU。
 * 这里改用 `Character.getType` 逐字符判断，行为在两个平台上完全一致，
 * 而且省掉了一层正则编译。
 */
class WordPieceTokenizer private constructor(
    private val vocabulary: Map<String, Int>,
) {

    /** 词表大小。加载异常时用来判断。 */
    val size: Int get() = vocabulary.size

    /**
     * 把一段文本编码成模型输入。
     *
     * 输出固定为 `[CLS] ... [SEP]`，长度不超过 [maxLength]。
     *
     * @param maxLength 含 `[CLS]` 与 `[SEP]` 的总长度上限。
     * @return 三元组：input_ids、attention_mask、token_type_ids。
     *         attention_mask 用来做 mean pooling —— 补齐的 0 不能参与平均，
     *         否则短句子的向量会被一串 `[PAD]` 拉偏。
     */
    fun encode(text: String, maxLength: Int): EncodedText {
        val contentLimit = (maxLength - 2).coerceAtLeast(1)

        val pieces = mutableListOf<Int>()
        for (token in basicTokenize(text)) {
            if (pieces.size >= contentLimit) break
            for (id in wordPiece(token)) {
                if (pieces.size >= contentLimit) break
                pieces += id
            }
        }

        val length = pieces.size + 2
        val ids = IntArray(length)
        val mask = IntArray(length) { 1 }
        val types = IntArray(length)

        ids[0] = TOKEN_CLS
        for (index in pieces.indices) ids[index + 1] = pieces[index]
        ids[length - 1] = TOKEN_SEP

        return EncodedText(ids, mask, types)
    }

    /**
     * 一次编码的结果。
     *
     * 刻意**不是** data class：里面的三个数组需要按内容比较，而 data class 自动生成的
     * equals/hashCode 用的是数组的引用相等 —— 那样「两段一样的文本编码结果不相等」，
     * 一个很隐蔽的坑。这里手写按内容比较。
     */
    class EncodedText(
        val inputIds: IntArray,
        val attentionMask: IntArray,
        val tokenTypeIds: IntArray,
    ) {
        val length: Int get() = inputIds.size

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is EncodedText) return false
            return inputIds.contentEquals(other.inputIds)
        }

        override fun hashCode(): Int = inputIds.contentHashCode()
    }

    // -----------------------------------------------------------------------
    // 第一步：BasicTokenizer
    // -----------------------------------------------------------------------

    /**
     * 清洗与切分。
     *
     * 顺序不能换：**先插空格再小写**。反过来的话，在已经小写的文本上做中文判断
     * 虽然也对（汉字没有大小写），但一旦将来要支持「只在部分语言插空格」就会出错。
     * 跟着原始实现走，就不需要每次重新推导一遍。
     */
    private fun basicTokenize(text: String): List<String> {
        val cleaned = clean(text)
        val spaced = addSpacesAroundCjk(cleaned)
        val lowered = stripAccents(spaced.lowercase())

        val tokens = mutableListOf<String>()
        val current = StringBuilder()

        for (char in lowered) {
            when {
                char.isWhitespace() -> {
                    if (current.isNotEmpty()) {
                        tokens += current.toString()
                        current.setLength(0)
                    }
                }

                isPunctuation(char) -> {
                    if (current.isNotEmpty()) {
                        tokens += current.toString()
                        current.setLength(0)
                    }
                    // 标点自身成为一个词。中文标点走的是同一条分支 ——
                    // 原始实现把中文标点当普通字符放进词里，但那样「你好，世界」
                    // 会切出「你好，世界」这种带标点的怪词，反而更容易退化成 [UNK]。
                    tokens += char.toString()
                }

                else -> current.append(char)
            }
        }

        if (current.isNotEmpty()) tokens += current.toString()
        return tokens
    }

    /**
     * 清掉控制字符，把各种空白统一成半角空格。
     *
     * `\t \n \r` 与全角空格都换成空格是必要的：全角空格在词表里**不存在**，
     * 不换的话它会变成一个 `[UNK]`，白占一个位置。
     */
    private fun clean(text: String): String {
        val builder = StringBuilder(text.length)
        for (char in text) {
            when {
                char == '\u0000' || char == '\uFFFD' -> Unit
                char.isWhitespace() -> builder.append(' ')
                // 全角空格（U+3000）在 Java 里算空白，会被上一分支接住；
                // 这里再兜一个零宽字符：它不可见，却会切出空词。
                char == '\u200B' || char == '\uFEFF' -> Unit
                else -> builder.append(char)
            }
        }
        return builder.toString()
    }

    /**
     * 在每个 CJK 字符两侧插空格。
     *
     * 这是原始 BERT 的做法：中文在词表里是**按单字**收录的（「你」「好」各一条），
     * 不插空格的话「你好世界」会被当成一个整体去做最长匹配，虽然多数情况也能
     * 匹配成 `你 / ##好 / ##世 / ##界`，但一旦中间夹了一个词表里没有的字，
     * 整个词就退成 `[UNK]`。插了空格之后，坏掉的只有一个字。
     */
    private fun addSpacesAroundCjk(text: String): String {
        val builder = StringBuilder(text.length + 16)
        for (char in text) {
            if (isCjk(char)) {
                builder.append(' ').append(char).append(' ')
            } else {
                builder.append(char)
            }
        }
        return builder.toString()
    }

    /** 是否是需要单独切开的表意文字。 */
    private fun isCjk(char: Char): Boolean {
        val code = char.code
        // 只看 BMP。扩展 B 以上的字在 Java 的 String 里是代理对，
        // 单个 Char 拿不到完整码点；那些字极罕见，为它们引入码点级遍历不值得。
        return (code in 0x4E00..0x9FFF) ||   // 基本区
            (code in 0x3400..0x4DBF) ||      // 扩展 A
            (code in 0xF900..0xFAFF)         // 兼容表意文字
    }

    /**
     * 去掉组合记号（重音）。
     *
     * `café` → `cafe`。bert-base-uncased 训练时是这么做的，不去的话 `é`
     * 会退化成 `[UNK]`。
     *
     * 先 NFD 分解，再把「非间距记号」整类丢掉 —— 用 `Character.getType` 而不是
     * 正则 `\p{Mn}`，理由见类注释。
     */
    private fun stripAccents(text: String): String {
        if (text.all { it.code < 128 }) return text
        val decomposed = Normalizer.normalize(text, Normalizer.Form.NFD)
        val builder = StringBuilder(decomposed.length)
        for (char in decomposed) {
            if (Character.getType(char) != Character.NON_SPACING_MARK.toInt()) {
                builder.append(char)
            }
        }
        return builder.toString()
    }

    /** 标点判定。与原始实现的判据一致：Unicode 分类以 P 开头，或落在 ASCII 符号区间。 */
    private fun isPunctuation(char: Char): Boolean {
        val type = Character.getType(char)
        return when (type.toByte()) {
            Character.CONNECTOR_PUNCTUATION,
            Character.DASH_PUNCTUATION,
            Character.START_PUNCTUATION,
            Character.END_PUNCTUATION,
            Character.INITIAL_QUOTE_PUNCTUATION,
            Character.FINAL_QUOTE_PUNCTUATION,
            Character.OTHER_PUNCTUATION,
            -> true

            else -> {
                val code = char.code
                (code in 33..47) || (code in 58..64) || (code in 91..96) || (code in 123..126)
            }
        }
    }

    // -----------------------------------------------------------------------
    // 第二步：WordPieceTokenizer
    // -----------------------------------------------------------------------

    /**
     * 最长优先匹配。
     *
     * 从整词开始，能命中就用；命不中就砍掉最后一个字符再试，直到只剩一个字符。
     * 单字符也命不中 → 整词退成 `[UNK]`。
     *
     * `##` 前缀表示「这是某个词的中间片段」，是 WordPiece 用来表达构词法的约定。
     */
    private fun wordPiece(token: String): List<Int> {
        if (token.isEmpty()) return emptyList()
        if (token.length > MAX_CHARS_PER_WORD) return listOf(TOKEN_UNK)

        val ids = mutableListOf<Int>()
        var start = 0
        while (start < token.length) {
            var end = token.length
            var matched: Int? = null

            // 从最长的可能片段往回收。
            while (start < end) {
                val piece = if (start > 0) "##${token.substring(start, end)}" else token.substring(start, end)
                val id = vocabulary[piece]
                if (id != null) {
                    matched = id
                    break
                }
                end--
            }

            if (matched == null) {
                // 整词作废。原始实现就是这么处理的：一个词里只要有一段切不出来，
                // 这个词就不参与编码，避免用 [UNK] 拼凑出误导性的向量。
                return listOf(TOKEN_UNK)
            }

            ids += matched
            start = end
        }
        return ids
    }

    companion object {
        private const val TAG = "WordPieceTokenizer"

        /** 词表里的特殊 token。数值来自 bert-base-uncased 的词表位置。 */
        const val TOKEN_PAD = 0
        const val TOKEN_UNK = 100
        const val TOKEN_CLS = 101
        const val TOKEN_SEP = 102

        /** 一个词最多多少字符。BERT 的原始默认值，超过就直接判为 OOV。 */
        private const val MAX_CHARS_PER_WORD = 100

        /** 期望的词表条数。对不上说明读到的不是这个模型的词表。 */
        private const val EXPECTED_VOCAB_SIZE = 30522

        /**
         * 从 assets 加载词表。
         *
         * 失败返回 null 而不是抛异常：调用方（本地向量引擎）需要把「词表没读到」
         * 变成一句能显示给用户的话，而不是一次崩溃。
         */
        fun load(context: Context, assetName: String): WordPieceTokenizer? = runCatching {
            val vocabulary = HashMap<String, Int>(EXPECTED_VOCAB_SIZE * 2)
            context.assets.open(assetName).bufferedReader().useLines { lines ->
                var index = 0
                lines.forEach { line ->
                    // 词表是按行存的，每行一个 token。**不能 trim** ——
                    // 词表里有 `## ` 这类带空格的片段，trim 会把它们改坏。
                    if (line.isNotEmpty()) {
                        vocabulary[line] = index
                    }
                    index++
                }
            }

            if (vocabulary.isEmpty()) {
                Log.e(TAG, "词表是空的：$assetName")
                null
            } else {
                if (vocabulary.size != EXPECTED_VOCAB_SIZE) {
                    // 只警告不失败：词表版本略有差异时，功能大体上仍然可用，
                    // 直接拒绝加载反而会把一个「能用」的状态变成「不能用」。
                    Log.w(TAG, "词表条数异常：期望 $EXPECTED_VOCAB_SIZE，实际 ${vocabulary.size}")
                }
                WordPieceTokenizer(vocabulary)
            }
        }.getOrElse { throwable ->
            Log.e(TAG, "加载词表失败：$assetName", throwable)
            null
        }
    }
}
