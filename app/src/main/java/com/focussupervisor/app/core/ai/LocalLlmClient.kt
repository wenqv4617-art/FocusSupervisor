package com.focussupervisor.app.core.ai

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.ProgressListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 端侧对话模型客户端（MediaPipe GenAI LLM Inference）。
 *
 * ===========================================================================
 * 一、模型的格式是硬约束
 * ===========================================================================
 * MediaPipe 只吃 `.task` 打包格式（内部是 LiteRT 的 tflite + 分词器 + 元数据）。
 * GGUF 是 llama.cpp 的格式，这里读不了 —— 这不是配置问题，是两套运行时。
 * 所以 `LocalLlmModels` 里每一个模型都必须能追到一个真实的 `.task` 文件。
 *
 * ===========================================================================
 * 二、生命周期：这是整个模块最容易出内存事故的地方
 * ===========================================================================
 * 一个 1.5B 的 q8 模型常驻大约 1.5~2GB（权重 + KV 缓存）。这已经不是「一点内存」，
 * 而是「决定这台手机还能不能开别的应用」。所以规矩定得很死：
 *
 * ```
 *   懒加载   —— 没人用就一个字节都不占。加载只发生在用户明确选了本地模型之后。
 *   显式释放 —— 切换模型、切回云端、用户关掉开关，三处都必须调 release()。
 *   不并发   —— 生成过程中拒绝释放：native 层正在读写那块内存，边跑边关是崩溃。
 * ```
 *
 * **不要指望 GC 帮你回收。** MediaPipe 的权重在 native 堆上，Java 侧的
 * `LlmInference` 只是一个句柄；句柄被回收了，native 那块内存仍然在。
 * 这就是为什么 [release] 必须被显式调用，而不是「交给 GC」。
 *
 * ===========================================================================
 * 三、为什么默认用 CPU 后端
 * ===========================================================================
 * [LocalLlmBackend.GPU] 在支持的机型上确实快得多，但它的失败模式很难看：
 * 驱动不兼容时不是抛异常，而是**直接崩在 native 层**（SIGSEGV），
 * 我们连一句中文错误都来不及给。
 *
 * 所以默认 CPU，GPU 作为用户可以在界面上打开的选项。这是一个「先能跑，再快」的取舍。
 */
class LocalLlmClient(private val context: Context) {

    /** 推理后端。 */
    enum class Backend {
        /** CPU 后端。兼容性最好，任何 64 位设备都能跑。 */
        CPU,

        /** GPU 后端。明显更快，但驱动不兼容时会崩在 native 层。 */
        GPU,
    }

    private var inference: LlmInference? = null
    private var loadedPath: String? = null
    private var loadedBackend: Backend? = null

    /**
     * 是否有一次生成正在进行。
     *
     * 它的唯一作用是**阻止释放**。native 层正在读权重时释放句柄，最好的结果是崩溃，
     * 最坏的结果是读出一堆随机数据然后当成文字输出。
     */
    private val generating = AtomicBoolean(false)

    /** 模型是否已加载，可以直接生成。 */
    @get:Synchronized
    val isReady: Boolean
        get() = inference != null

    /** 当前加载的模型路径。没加载时为 null。 */
    @get:Synchronized
    val currentModelPath: String?
        get() = loadedPath

    /**
     * 加载模型。
     *
     * 会先释放上一个：用户从 0.5B 换到 1.5B 时，两块权重同时在内存里会直接 OOM。
     *
     * @param modelFile `.task` 文件。调用方负责确认它已下载并通过校验。
     * @param backend 推理后端。
     * @param maxOutputTokens 单次生成最多吐多少 token。它**必须**在加载时定下来，
     *        因为它是 native 侧 KV 缓存与输出缓冲的尺寸参数，不是每次请求的参数。
     * @return 成功，或一句可以直接显示给用户的中文原因。
     */
    @Synchronized
    fun load(
        modelFile: File,
        backend: Backend = Backend.CPU,
        maxOutputTokens: Int = DEFAULT_MAX_OUTPUT_TOKENS,
    ): Result<Unit> {
        // 同一个文件、同一个后端、已经加载好了：直接复用，不重建。
        // 重建要几百毫秒到几秒，而用户可能只是在界面上来回切了几次。
        if (inference != null && loadedPath == modelFile.absolutePath && loadedBackend == backend) {
            return Result.success(Unit)
        }

        if (!modelFile.isFile) {
            return Result.failure(IllegalStateException("模型文件不存在，请先下载"))
        }

        releaseInternal()

        return runCatching {
            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelFile.absolutePath)
                .setMaxTokens(maxOutputTokens)
                .setPreferredBackend(
                    when (backend) {
                        Backend.CPU -> LlmInference.Backend.CPU
                        Backend.GPU -> LlmInference.Backend.GPU
                    },
                )
                .build()

            val engine = LlmInference.createFromOptions(context, options)
            inference = engine
            loadedPath = modelFile.absolutePath
            loadedBackend = backend
            Log.i(TAG, "端侧模型已加载：${modelFile.name}，后端 $backend")
            // 显式的 Unit：块的最后一句是 Log.i（返回 Int），
            // 少了它 runCatching 的类型会变成 Result<Int>，与声明的 Result<Unit> 对不上。
            Unit
        }.recoverCatching { throwable ->
            // 把 OOM 与「文件坏了」翻译成人话。
            //
            // 这里必须区分：OutOfMemoryError 是**内存不够**（换小模型或关掉别的应用），
            // 其它异常多半是**文件不是 MediaPipe 格式**（换个模型，或者重新下载）。
            // 两者给用户的操作完全不同，混成一句「加载失败」等于什么都没说。
            releaseInternal()
            throw when (throwable) {
                is OutOfMemoryError -> LocalLlmException(
                    "内存不够，加载这个模型需要更多可用运存。换一个更小的模型，或者先关掉其它应用。",
                    throwable,
                )

                else -> LocalLlmException(
                    "模型加载失败。文件可能不是 MediaPipe 的 .task 格式，或者下载不完整，" +
                        "建议删掉重新下载一次。",
                    throwable,
                )
            }
        }
    }

    /**
     * 流式生成。
     *
     * 每次发射的是**增量**文本（不是累计全文）。MediaPipe 给的回调是累计的，
     * 这里做了差分 —— 让调用方直接 `append` 就能上屏，不必自己记长度。
     *
     * @param prompt 已经渲染好的单串提示词（见 `ChatTemplate`）。
     * @return 文本片段流。出错时流以异常结束。
     */
    fun generate(prompt: String): Flow<String> = callbackFlow {
        val engine = inference
        if (engine == null) {
            close(IllegalStateException("端侧模型还没加载"))
            return@callbackFlow
        }
        if (!generating.compareAndSet(false, true)) {
            close(IllegalStateException("上一次生成还没结束"))
            return@callbackFlow
        }

        var emitted = 0
        val listener = ProgressListener<String> { partial, done ->
            val text = partial ?: ""
            if (text.length > emitted) {
                trySend(text.substring(emitted))
                emitted = text.length
            }
            if (done) {
                generating.set(false)
                close()
            }
        }

        try {
            engine.generateResponseAsync(prompt, listener)
        } catch (throwable: Throwable) {
            generating.set(false)
            Log.e(TAG, "端侧生成失败", throwable)
            close(
                LocalLlmException(
                    throwable.message?.takeIf { it.isNotBlank() } ?: "端侧模型生成失败",
                    throwable,
                ),
            )
        }

        awaitClose {
            // 收集方取消（用户退出界面）时也要把标志放回去，否则这个引擎以后
            // 会永远认为「上一次还在跑」，再也无法使用。
            generating.set(false)
        }
        // 推理绝不能沾主线程。MediaPipe 的回调虽然在自己的线程上，但
        // 分片处理与差分子串的分配仍在收集侧发生，那部分要离开 UI 线程。
    }.flowOn(Dispatchers.Default)

    /**
     * 阻塞式生成，用于**跑分**。
     *
     * 跑分要的是「从发出到拿到完整回答花了多久」这一个数字，中间的分片对它没有意义，
     * 用流式反而会引入调度噪声。所以走同步接口。
     */
    fun generateBlocking(prompt: String): Result<String> {
        val engine = inference ?: return Result.failure(IllegalStateException("端侧模型还没加载"))
        if (!generating.compareAndSet(false, true)) {
            return Result.failure(IllegalStateException("上一次生成还没结束"))
        }
        return runCatching { engine.generateResponse(prompt) }
            .onFailure { Log.e(TAG, "端侧同步生成失败", it) }
            .also { generating.set(false) }
    }

    /** 估算一段文本在**这个模型**里有多少 token。跑分用它算吞吐。 */
    fun countTokens(text: String): Int {
        val engine = inference ?: return 0
        return runCatching { engine.sizeInTokens(text) }.getOrDefault(0)
    }

    /**
     * 释放 native 资源。
     *
     * @return 是否真的释放了。正在生成时返回 false 并**不做任何事** ——
     *         这不是「失败」，是「现在不能」，调用方应当稍后再试。
     */
    @Synchronized
    fun release(): Boolean {
        if (generating.get()) {
            Log.w(TAG, "正在生成，拒绝释放模型")
            return false
        }
        releaseInternal()
        return true
    }

    private fun releaseInternal() {
        runCatching { inference?.close() }
            .onFailure { Log.w(TAG, "关闭端侧模型失败", it) }
        inference = null
        loadedPath = null
        loadedBackend = null
    }

    companion object {
        private const val TAG = "LocalLlmClient"

        /**
         * 单次生成的默认 token 上限。
         *
         * 384 大致对应中文 400~500 字。这个应用的回答本来就要求「一到三句」，
         * 给再多也只是给模型一个写小作文的机会 —— 而在端侧，写小作文的代价是
         * 每秒几个 token 的等待。
         */
        const val DEFAULT_MAX_OUTPUT_TOKENS = 384

        /**
         * 微型模型的上限。
         *
         * 0.5B 每秒只能吐十几个 token，而它一旦开始写长文就会开始编。
         * 160 约等于两百字，已经超过它该说的量了。
         */
        const val TINY_MAX_OUTPUT_TOKENS = 160
    }
}

/**
 * 端侧模型相关的错误。
 *
 * 单独一个类型是为了让界面能区分「模型的问题」与「代码的问题」：
 * 前者给用户一段可操作的建议，后者只该出现在日志里。
 */
class LocalLlmException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
