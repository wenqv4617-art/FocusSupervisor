package com.focussupervisor.app.core.ai

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import com.focussupervisor.app.domain.model.LocalEmbeddingModels
import java.io.File
import java.nio.LongBuffer
import kotlin.math.sqrt

/**
 * 本机向量引擎：用 ONNX Runtime 跑 all-MiniLM-L6-v2。
 *
 * ===========================================================================
 * 一次向量是怎么算出来的
 * ===========================================================================
 * ```
 *   文本
 *    └─ WordPieceTokenizer ──→ input_ids / attention_mask / token_type_ids
 *         └─ ONNX 模型 ──────→ last_hidden_state  [1, seq, 384]
 *              └─ mean pooling + L2 归一化 ──→ 一条 384 维单位向量
 * ```
 *
 * 后两步都是**必须**的，缺一个结果就是错的：
 *
 *  - **mean pooling**：模型吐出来的是「每个 token 一个向量」，句子级的向量要自己
 *    按 attention_mask 求平均。补齐用的 `[PAD]` 位置必须排除在外 ——
 *    否则短句的向量会被几十个 padding 拉向无意义的方向，而长句几乎不受影响，
 *    相似度排序会整体失真。
 *  - **L2 归一化**：余弦相似度本来就不受长度影响，但记忆检索里还用到向量的模长
 *    做排序辅助，而且 sentence-transformers 训练时就是归一化过的输出。
 *    保持与训练一致，才谈得上「同一套语义空间」。
 *
 * ===========================================================================
 * 生命周期
 * ===========================================================================
 * ONNX 的 session 加载要几百毫秒到一两秒，**绝不能每算一条就建一次**。
 * 所以它是懒加载 + 常驻的：第一次用到时 [load]，之后一直留着，直到
 * [close] 或者进程结束。`AppContainer` 持有一个实例。
 *
 * 线程安全：`OrtSession.run` 本身是线程安全的，但我们的 [tokenizer] 与
 * [inputNames] 是普通字段。这里的做法是整类加 `@Synchronized` —— 记忆补向量
 * 本来就是一个后台串行任务，为它引入一把细粒度锁只会增加出错的机会。
 */
class LocalEmbeddingEngine(private val context: Context) {

    private val appContext: Context = context.applicationContext

    private var environment: OrtEnvironment? = null
    private var session: OrtSession? = null
    private var tokenizer: WordPieceTokenizer? = null
    private var inputNames: List<String> = emptyList()

    /** 模型是否已经加载好，可以直接算。 */
    @get:Synchronized
    val isReady: Boolean
        get() = session != null && tokenizer != null

    /** 当前向量维度。模型没加载时为 0。 */
    @get:Synchronized
    val dimension: Int
        get() = loadedDimension

    private var loadedDimension: Int = 0

    /**
     * 加载模型与词表。
     *
     * @param modelFile ONNX 模型文件。调用方负责确认它存在且校验通过。
     * @return 成功或一句中文错误原因。
     */
    @Synchronized
    fun load(modelFile: File): Result<Unit> {
        if (isReady) return Result.success(Unit)

        if (!modelFile.isFile) {
            return Result.failure(IllegalStateException("模型文件不存在，请先下载"))
        }

        return runCatching {
            val wordPiece = WordPieceTokenizer.load(appContext, LocalEmbeddingModels.VOCAB_ASSET)
                ?: error("分词词表加载失败（assets/${LocalEmbeddingModels.VOCAB_ASSET}）")

            val ortEnvironment = OrtEnvironment.getEnvironment()
            val options = OrtSession.SessionOptions().apply {
                // 2 个线程：补向量是后台任务，让它把 8 核吃满只会换来手机发烫，
                // 而且用户会明显感觉到界面卡顿。
                setIntraOpNumThreads(EMBEDDING_THREADS)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }

            val ortSession = ortEnvironment.createSession(modelFile.absolutePath, options)

            environment = ortEnvironment
            session = ortSession
            tokenizer = wordPiece
            inputNames = ortSession.inputNames.toList()
            // 维度直接取模型清单里声明的值，不去问 ONNX 的输出形状。
            //
            // 试过读 outputInfo，但那个 API 的形状挂在 TensorInfo 上而不是 NodeInfo 上，
            // 而且中间两维是动态的（-1），真要用还得自己推断。既然我们只内置一个模型，
            // 从清单里读是唯一不会出错的做法 —— 将来加模型时，清单里本来就要求
            // 填 dimension（仓库要用它判断「维度变了没有」）。
            loadedDimension = LocalEmbeddingModels.DEFAULT.dimension

            Log.i(TAG, "本地向量模型已加载，维度 $loadedDimension，输入 $inputNames")
            Unit
        }.onFailure { throwable ->
            Log.e(TAG, "加载本地向量模型失败", throwable)
            // 加载到一半失败时把已建的对象收掉，否则下次重试会叠加一个泄漏的 session。
            closeQuietly()
        }
    }

    /**
     * 批量算向量。
     *
     * @return 与输入等长、顺序一致的向量列表。任何一条失败整体失败 ——
     *         半份结果会让调用方往索引里写入不完整的向量，
     *         那种状态比「这次没算成」难查得多。
     */
    @Synchronized
    fun embed(texts: List<String>): Result<List<List<Float>>> {
        val ortSession = session ?: return Result.failure(IllegalStateException("本地模型尚未加载"))
        val wordPiece = tokenizer ?: return Result.failure(IllegalStateException("分词器尚未加载"))
        val ortEnvironment = environment
            ?: return Result.failure(IllegalStateException("ONNX 运行环境尚未初始化"))

        if (texts.isEmpty()) return Result.success(emptyList())

        return runCatching {
            texts.map { text -> embedOne(ortEnvironment, ortSession, wordPiece, text) }
        }.onFailure { throwable ->
            Log.e(TAG, "本地向量计算失败", throwable)
        }
    }

    /** 单条文本的完整流程。 */
    private fun embedOne(
        ortEnvironment: OrtEnvironment,
        ortSession: OrtSession,
        wordPiece: WordPieceTokenizer,
        text: String,
    ): List<Float> {
        val encoded = wordPiece.encode(text, LocalEmbeddingModels.MAX_SEQ_LENGTH)
        val shape = longArrayOf(1L, encoded.length.toLong())

        // 三个输入张量都要显式关掉。它们是 native 内存，靠 GC 回收等于把
        // 「什么时候释放」交给运气 —— 补向量是循环任务，几百轮之后就是一次 OOM。
        //
        // 这一整条 use 链是函数的**返回值**，所以前面必须有 return：
        // 少了它，编译器只会说一句「Missing return statement」，
        // 而不会告诉你「你忘了返回最后那个表达式」。
        return OnnxTensor.createTensor(ortEnvironment, asLongBuffer(encoded.inputIds), shape).use { ids ->
            OnnxTensor.createTensor(ortEnvironment, asLongBuffer(encoded.attentionMask), shape)
                .use { mask ->
                OnnxTensor.createTensor(ortEnvironment, asLongBuffer(encoded.tokenTypeIds), shape)
                    .use { types ->
                    val inputs = mutableMapOf<String, OnnxTensor>()
                    // 按模型声明的输入名填，而不是硬编码三个名字：
                    // 换一个模型（比如不需要 token_type_ids 的）也不必改这里，
                    // 多的输入会被忽略，少的会在 session.run 里直接报错。
                    if (INPUT_IDS in inputNames) inputs[INPUT_IDS] = ids
                    if (INPUT_MASK in inputNames) inputs[INPUT_MASK] = mask
                    if (INPUT_TYPES in inputNames) inputs[INPUT_TYPES] = types

                    ortSession.run(inputs).use { result ->
                        val hidden = result[0].value
                        meanPoolAndNormalize(hidden, encoded.attentionMask)
                    }
                }
            }
        }
    }

    /** IntArray 转成 ONNX 要的 LongBuffer。逐元素转而不是先 map 再 toLongArray，省一层中间列表。 */
    private fun asLongBuffer(source: IntArray): LongBuffer {
        val target = LongArray(source.size)
        for (index in source.indices) target[index] = source[index].toLong()
        return LongBuffer.wrap(target)
    }

    /**
     * 按 attention_mask 做平均池化，再 L2 归一化。
     *
     * 这里的 `hidden` 是 ONNX Runtime 解出来的嵌套数组：`[batch][seq][dim]`。
     * batch 固定为 1，但类型上仍然要按三层剥 —— 直接当两层用会在某些模型上抛
     * ClassCastException。
     */
    private fun meanPoolAndNormalize(hidden: Any?, attentionMask: IntArray): List<Float> {
        @Suppress("UNCHECKED_CAST")
        val batch = hidden as? Array<Array<FloatArray>>
            ?: error("模型输出不是预期的 [1, seq, dim] 浮点张量")

        val sequence = batch.firstOrNull() ?: error("模型返回了空的 batch")
        if (sequence.isEmpty()) error("模型返回了空的序列")

        val dimension = sequence[0].size
        val sum = FloatArray(dimension)
        var counted = 0

        for (token in sequence.indices) {
            // mask 比序列短时按「保留」处理：宁可多算一个 token，也不要因为
            // 长度对不上就整条作废 —— 那会让功能表现为「什么都没算出来」。
            val keep = token >= attentionMask.size || attentionMask[token] != 0
            if (!keep) continue

            val row = sequence[token]
            counted++
            for (index in 0 until dimension) {
                sum[index] += row[index]
            }
        }

        if (counted == 0) error("attention_mask 全是 0")

        val pooled = FloatArray(dimension)
        var squareSum = 0f
        for (index in 0 until dimension) {
            val value = sum[index] / counted
            pooled[index] = value
            squareSum += value * value
        }

        val norm = sqrt(squareSum)
        // 全零向量没法归一化（除零）。理论上不会出现，出现了就返回零向量 ——
        // 余弦相似度对零向量返回 0，它会自然沉底，而不是污染排序。
        if (norm <= 0f) return pooled.toList()

        for (index in 0 until dimension) {
            pooled[index] /= norm
        }
        return pooled.toList()
    }

    /** 释放 native 资源。进程退出前调用一次即可。 */
    @Synchronized
    fun close() {
        closeQuietly()
    }

    private fun closeQuietly() {
        runCatching { session?.close() }.onFailure { Log.w(TAG, "关闭 ONNX session 失败", it) }
        session = null
        tokenizer = null
        inputNames = emptyList()
        loadedDimension = 0
        // OrtEnvironment 是进程级单例，**不要**关它：关闭之后同进程内再也
        // getEnvironment 不回来，而那会让「重新下载模型后再启用」直接失败。
        environment = null
    }

    private companion object {
        const val TAG = "LocalEmbeddingEngine"

        /** 推理线程数。 */
        const val EMBEDDING_THREADS = 2

        const val INPUT_IDS = "input_ids"
        const val INPUT_MASK = "attention_mask"
        const val INPUT_TYPES = "token_type_ids"
    }
}
