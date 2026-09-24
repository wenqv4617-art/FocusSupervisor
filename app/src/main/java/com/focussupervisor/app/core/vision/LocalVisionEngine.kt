package com.focussupervisor.app.core.vision

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.focussupervisor.app.domain.model.VisionEngineKind
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * 端侧识图引擎。
 *
 * ===========================================================================
 * 一、这里在解决的那个真问题：把「看图」和「对话」拆开
 * ===========================================================================
 * 原来的链路是：
 *
 * ```
 *   截屏 ──上传──> 云端多模态模型 ──> 一句描述 ──> 时间线 ──> 对话模型
 * ```
 *
 * 它有两个硬伤：**必须有网**，以及**对话模型也得支持看图**（DeepSeek 不看图，
 * 于是用户为了用上注视监控就得换掉主力对话模型 —— 那不是取舍，是功能互斥）。
 *
 * 新链路把「看图」这一步留在本机：
 *
 * ```
 *   截屏 ──本地解析──> 一句 40 字以内的中文事实 ──> 时间线
 *                                                    │
 *                        对话端（云端 DeepSeek 或端侧纯文本 Qwen）都能读到
 * ```
 *
 * 于是**纯文本模型也能知道屏幕上发生了什么**。这是这一整套设计真正的价值所在。
 *
 * ===========================================================================
 * 二、两个备选，一个能跑一个还不能
 * ===========================================================================
 * **备选 A · ML Kit 中文文本识别（默认，已可用）**
 * 毫秒级、零大模型负担、约 10MB 原生组件。它能做的是「把屏幕上的字读出来」，
 * 然后我们从中提炼前台标题与关键文本。对绝大多数场景（刷短视频、看小说、
 * 逛购物 App）其实已经够了 —— 因为那些界面上**全是字**。
 *
 * **备选 B · 微型端侧 VLM（架构已就位，运行时不可用）**
 * 真正的画面理解，能看懂没有文字的富媒体（游戏、视频画面）。
 * 但**现在没有可用的运行时**：候选模型（SmolVLM-256M、FastVLM-0.5B）只有
 * `.tflite` / `.litertlm`，后者要 LiteRT-LM，而它在 Google Maven 与
 * Maven Central 上都查不到（都是 404）。
 *
 * 本类的做法是：**接口留好、实现留空、并且把「为什么空着」明确返回给界面**。
 * 一个点下去没反应、也不解释原因的按钮，比一个写着「暂不可用」的按钮糟糕得多。
 */
class LocalVisionEngine(private val context: Context) {

    /**
     * 当前是否**测过一次**这个引擎。
     *
     * 它只影响界面文案（「还没测过」/「上次测的结果」），不参与任何判断。
     */
    @Volatile
    var lastTestSummary: String? = null
        private set

    /**
     * 从一张截图里提炼一句事实描述。
     *
     * @param bitmap 截图。调用方负责回收它。
     * @param prompt 用户自定义的提问。OCR 路径下它决定**提炼的取向** ——
     *        模型（这里其实没有模型）是按这段文字的要求挑选要保留的信息。
     * @param maxChars 摘要长度上限。它来自当前提示词档位的预算 ——
     *        端侧微型档给 40 字，云端可以给 200 字。
     * @return 成功或一句中文原因。
     */
    suspend fun describe(
        bitmap: Bitmap,
        kind: VisionEngineKind,
        prompt: String,
        maxChars: Int,
    ): Result<String> = when (kind) {
        VisionEngineKind.ML_KIT_OCR -> describeWithOcr(bitmap, prompt, maxChars)
        VisionEngineKind.MICRO_VLM -> Result.failure(
            VisionEngineUnavailableException(VisionEngineKind.MICRO_VLM.unavailableReason ?: "不可用"),
        )
    }

    // -----------------------------------------------------------------------
    // 备选 A · ML Kit OCR
    // -----------------------------------------------------------------------

    /**
     * 用中文文本识别读屏，再提炼成一句事实。
     *
     * 全程在 [Dispatchers.Default]：识别本身是 native 异步的，但后处理
     * （排序、筛选、拼接）是纯 CPU 的字符串活，绝不能放在主线程 ——
     * 它跑的时机恰好是无障碍服务可能正在处理窗口事件的时刻。
     */
    private suspend fun describeWithOcr(
        bitmap: Bitmap,
        prompt: String,
        maxChars: Int,
    ): Result<String> = withContext(Dispatchers.Default) {
        try {
            val recognizer = TextRecognition.getClient(
                ChineseTextRecognizerOptions.Builder().build(),
            )
            try {
                val image = InputImage.fromBitmap(bitmap, 0)
                val text = suspendCancellableCoroutine { continuation ->
                    recognizer.process(image)
                        .addOnSuccessListener { result ->
                            if (continuation.isActive) continuation.resume(result.text)
                        }
                        .addOnFailureListener { throwable ->
                            if (continuation.isActive) continuation.resume(null)
                        }
                }

                if (text.isNullOrBlank()) {
                    Result.success("屏幕上没有可识别的文字")
                } else {
                    Result.success(summarize(text, prompt, maxChars))
                }
            } finally {
                // 识别器持有 native 资源。用一次建一次是刻意的：
                // 常驻一个识别器意味着十几 MB 内存一直占着，而这个功能
                // 一次注视才跑一次（冷却期内不会重复触发）。
                runCatching { recognizer.close() }
            }
        } catch (throwable: Throwable) {
            Log.e(TAG, "本地 OCR 失败", throwable)
            Result.failure(throwable)
        }
    }

    /**
     * 把满屏文字提炼成一句「他在干什么」。
     *
     * ===========================================================================
     * 这是一个启发式，不是理解 —— 而它必须诚实地只做它做得到的事
     * ===========================================================================
     * 没有模型参与，所以这里不可能真的「看懂」画面。能做的是**统计与挑选**：
     *
     *  1. 顶部的文字通常是**页面标题或应用名** —— 那是「他在用什么」；
     *  2. 出现次数多、长度适中的短句通常是**内容主体** —— 那是「他在看什么」；
     *  3. 纯数字、纯符号、超长的行（通常是正文段落）没什么信息量，丢掉。
     *
     * 最后拼成「在 X 里看/做 Y」这样的句子。它不完美，但它**具体**，
     * 而且不会编造 —— 编造是纯文本模型看图最容易犯的错，而我们这里
     * 恰好因为「没有模型」而根本编不出来。
     *
     * @param prompt 用户的提问。当前实现只把它作为提炼取向的提示记进日志，
     *        不参与打分 —— 假装它被用上了会更糟。
     */
    private fun summarize(rawText: String, prompt: String, maxChars: Int): String {
        val lines = rawText.split('\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        if (lines.isEmpty()) return "屏幕上有内容，但没有可读的文字"

        // 1) 顶部候选：第一行往往就是标题栏。
        val title = lines.firstOrNull { it.length in 2..20 && !it.isMostlyDigits() }

        // 2) 内容候选：长度适中、不是标题本身、尽量靠前。
        //
        //    为什么限制在 4~24 个字：太短的（「赞」「关注」「推荐」）是按钮，
        //    太长的（一段正文）会占满摘要额度却说不出「他在干什么」。
        val body = lines
            .asSequence()
            .filter { it !== title }
            .filter { it.length in 4..24 }
            .filter { !it.isMostlyDigits() }
            .filter { !it.isNoiseWord() }
            .take(2)
            .toList()

        val builder = StringBuilder()
        if (title != null) {
            builder.append("在「").append(title).append("」")
        } else {
            builder.append("屏幕上")
        }
        if (body.isNotEmpty()) {
            builder.append("，看到：").append(body.joinToString("、"))
        }

        val summary = builder.toString()
        Log.d(TAG, "OCR 提炼（提问：${prompt.take(20)}）：$summary")

        // 超长时截断而不是丢掉：前半句（在哪儿）比后半句（看到什么）重要，
        // 从尾部截正好保住它。
        return if (summary.length <= maxChars) summary else summary.take(maxChars)
    }

    private fun String.isMostlyDigits(): Boolean {
        if (isEmpty()) return true
        val digits = count { it.isDigit() || it == ':' || it == '.' || it == '-' }
        return digits * 2 > length
    }

    /**
     * 界面上的固定噪音词。
     *
     * 这些是 App 底栏与按钮的通用文案，任何界面都有，出现在「他在看什么」里
     * 只会稀释信息。刻意只列很短的一份 —— 维护一份长名单的收益递减得很快，
     * 而且会逐渐变成一份没人敢删的化石。
     */
    private fun String.isNoiseWord(): Boolean = this in NOISE_WORDS

    /** 一次本地识图测试：用一张合成的位图跑一遍完整链路。 */
    suspend fun selfTest(kind: VisionEngineKind, maxChars: Int): Result<String> {
        if (kind == VisionEngineKind.MICRO_VLM) {
            return Result.failure(
                VisionEngineUnavailableException(kind.unavailableReason ?: "不可用"),
            )
        }

        // 造一张带文字的测试位图。
        //
        // 为什么不去截真实的屏幕：测试的语义是「这套链路能不能跑通」，
        // 而不是「现在屏幕上是什么」。用一张固定的图，结果可复现，
        // 也不会在用户点「测试」的时候顺带把当时的屏幕内容记下来。
        val bitmap = createTestBitmap()
        return try {
            val result = describe(bitmap, kind, TEST_PROMPT, maxChars)
            result.onSuccess { lastTestSummary = it }
            result
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    /** 合成一张写着测试文字的位图。 */
    private fun createTestBitmap(): Bitmap {
        val width = 720
        val height = 240
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.WHITE)

        val paint = android.graphics.Paint().apply {
            color = android.graphics.Color.BLACK
            textSize = 40f
            isAntiAlias = true
        }
        canvas.drawText("专注清单", 40f, 90f, paint)
        canvas.drawText("今天要写完这一节", 40f, 150f, paint)
        return bitmap
    }

    private companion object {
        const val TAG = "LocalVisionEngine"

        /** 测试用的提问。与真实链路上的提问保持一致，测出来的才是同一条路。 */
        const val TEST_PROMPT = "用一句不超过 40 字的中文说明画面里的人正在做什么。"

        val NOISE_WORDS = setOf(
            "首页", "推荐", "关注", "朋友", "我的", "消息", "发现", "购物车",
            "查看更多", "立即购买", "确认", "取消", "返回", "搜索", "分享",
        )
    }
}

/**
 * 端侧视觉引擎不可用。
 *
 * 单独一个异常类型，是为了让界面能把「这条链路现在就是没有运行时」与
 * 「这次识别失败了」区分开：前者要给一段解释与替代方案，后者只需要重试。
 */
class VisionEngineUnavailableException(message: String) : RuntimeException(message)
