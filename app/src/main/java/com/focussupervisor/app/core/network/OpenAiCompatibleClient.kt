package com.focussupervisor.app.core.network

import com.focussupervisor.app.domain.model.AiConfig
import com.focussupervisor.app.domain.model.EmbeddingConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlin.coroutines.coroutineContext

/**
 * OpenAI 兼容端点的最小客户端。
 *
 * ===========================================================================
 * 一、为什么用 execute() 而不是 enqueue()
 * ===========================================================================
 * OkHttp 的异步回调 `RealCall.AsyncCall.run()` 里有一个很容易被忽略的分支：
 *
 * ```
 * catch (t: Throwable) {
 *     ...onFailure(...)
 *     throw t          // ← 在 OkHttp 自己的线程上重新抛出
 * }
 * ```
 *
 * 也就是说，**任何不是 IOException 的异常（最典型的是缺 INTERNET 权限抛出的
 * SecurityException）都会先在回调里被报一次、然后在调度线程上再抛一次**，而那个
 * 线程没有异常处理器 —— 结果是整个进程被杀掉，用户看到「点一下按钮就闪退回桌面」，
 * 连一条能看懂的报错都没有。
 *
 * 所以这里改用同步的 `execute()`，跑在 [Dispatchers.IO] 上。异常会顺着调用栈回到
 * 我们这个协程里，被下面的 try/catch 接住，翻译成一句中文错误。**从结构上就不可能
 * 因为网络问题崩溃**，而不是依赖「记住某个权限别漏配」。
 *
 * 代价是少了取消能力，用 [executeOrCancel] 里的 `invokeOnCompletion` 补回来：
 * 协程被取消时主动 `cancel()` 掉这条请求，不让它空转到超时。
 *
 * ===========================================================================
 * 二、为什么要试多个 URL
 * ===========================================================================
 * 用户填的 Base URL 形态太多：有的要 `/v1`，有的不要，有的自带 `/api/v1`。
 * 上一版用的是「不以 /v1 结尾就补一个」，规则简单但会在**中转站**上翻车 ——
 * 中转站的路径千奇百怪，猜错一次就是一次「拉取失败」，而用户根本不知道该怎么改。
 *
 * 现在改成：把几种可能的根地址都列出来，依次试，**只在 404（路径不存在）时换下一个**。
 * 401/403/5xx 都直接返回 —— 那些不是「地址写错了」，换地址也没用，只会掩盖真实原因。
 *
 * ===========================================================================
 * 三、关于「不崩溃」
 * ===========================================================================
 * 所有异常都被翻译成 `Result.failure(AiClientException(友好中文说明))`。
 * 唯一的例外是 [kotlinx.coroutines.CancellationException]，它会被原样放行 ——
 * 吞掉取消异常会让协程结构化并发失效。
 */
class OpenAiCompatibleClient(
    private val client: OkHttpClient = defaultClient(),
) {

    /**
     * 拉取端点支持的模型列表。
     *
     * `GET {root}/models`
     *
     * @return 成功时是模型 id 列表（已去重、已排序）。
     */
    suspend fun fetchModels(baseUrl: String, apiKey: String): Result<List<String>> {
        val roots = candidateRoots(baseUrl)
        if (roots.isEmpty()) {
            return Result.failure(AiClientException("Base URL 为空，请先填写端点地址"))
        }

        return tryWithRoots(roots) { root ->
            val request = Request.Builder()
                .url("$root/models")
                .applyAuth(apiKey)
                .header("Accept", "application/json")
                .get()
                .build()

            client.newCall(request).executeOrCancel().use { response ->
                val body = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    Result.success(parseModelIds(body))
                } else {
                    Result.failure(httpFailure(response.code, body))
                }
            }
        }
    }

    /**
     * 用一条最轻量的 Prompt 验证连通性、URL、Key 与模型名是否都对。
     *
     * 为什么不直接请求 `/models` 就算握手成功：列模型不需要鉴权的情况很多
     * （Ollama、部分中转站），也就是说「能列模型」并不代表「Key 有效、模型可用」。
     * 真正发一次对话请求才能把这两件事一起验掉。
     *
     * @return 成功时是模型返回的文本内容（可能是空串）。
     */
    suspend fun testConnection(config: AiConfig): Result<String> {
        val roots = candidateRoots(config.baseUrl)
        if (roots.isEmpty()) {
            return Result.failure(AiClientException("Base URL 为空，请先填写端点地址"))
        }
        if (config.model.isBlank()) {
            return Result.failure(AiClientException("模型名为空，请先填写或点「拉取」选一个"))
        }

        val payload = JSONObject().apply {
            put("model", config.model)
            put("messages", JSONArray().put(message("user", TEST_PROMPT)))
            // 只要一个字的输出。测试连接不该消耗用户的额度。
            put("max_tokens", TEST_MAX_TOKENS)
            // 温度固定 0：这是连通性测试，输出越确定越容易判断。
            put("temperature", 0)
            put("stream", false)
        }.toString()

        return tryWithRoots(roots) { root ->
            val request = Request.Builder()
                .url("$root/chat/completions")
                .applyAuth(config.apiKey)
                .header("Accept", "application/json")
                .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            client.newCall(request).executeOrCancel().use { response ->
                val body = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    Result.success(parseAssistantContent(body))
                } else {
                    Result.failure(httpFailure(response.code, body))
                }
            }
        }
    }

    /**
     * 发一次完整的对话请求。这是对话功能的实际出口。
     *
     * 刻意不做流式（SSE）：流式要在客户端解析分片、处理断线、维护半截消息，而收益只是
     * 「字一个个蹦出来」的观感。先把非流式跑通，等真的需要再换 —— 接口形状不用变。
     *
     * @param turns 已经排好序的对话消息，system 在最前
     * @param maxTokens 输出上限；null 表示用端点默认值
     */
    suspend fun chat(
        config: AiConfig,
        turns: List<ChatTurn>,
        maxTokens: Int? = null,
    ): Result<ChatCompletion> {
        val roots = candidateRoots(config.baseUrl)
        if (roots.isEmpty()) {
            return Result.failure(AiClientException("Base URL 为空，请先在「AI 配置」里填写端点地址"))
        }
        if (config.model.isBlank()) {
            return Result.failure(AiClientException("模型名为空，请先在「AI 配置」里选一个模型"))
        }
        if (turns.isEmpty()) {
            return Result.failure(AiClientException("没有可发送的消息"))
        }

        val payload = JSONObject().apply {
            put("model", config.model)
            put("messages", JSONArray().apply {
                turns.forEach { turn -> put(message(turn.role, turn.content)) }
            })
            // 温度保留一位小数再发：0.7000001 这种数有些端点会直接 400。
            put("temperature", (config.temperature * 10).toInt() / 10.0)
            put("stream", false)
            if (maxTokens != null) put("max_tokens", maxTokens)
        }.toString()

        return tryWithRoots(roots) { root ->
            val request = Request.Builder()
                .url("$root/chat/completions")
                .applyAuth(config.apiKey)
                .header("Accept", "application/json")
                .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            client.newCall(request).executeOrCancel().use { response ->
                val body = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    // usage 顺手解析出来，交给上层显示缓存命中率。
                    Result.success(
                        ChatCompletion(
                            content = parseAssistantContent(body),
                            cacheStats = parseCacheStats(body),
                        ),
                    )
                } else {
                    Result.failure(httpFailure(response.code, body))
                }
            }
        }
    }

    /**
     * 把一批文本转向量。记忆检索靠它。
     *
     * 端点必须实现 OpenAI 的 `/embeddings`。没有配向量模型的用户会拿到一个明确的
     * 错误，记忆功能退回到关键词打分 —— 见 `MemoryRepository`。
     *
     * @return 与 [texts] 一一对应的向量列表，顺序必须一致
     */
    suspend fun embed(config: EmbeddingConfig, texts: List<String>): Result<List<List<Float>>> {
        if (texts.isEmpty()) return Result.success(emptyList())

        val model = config.model.trim()
        if (model.isEmpty()) {
            return Result.failure(AiClientException("还没有配置向量模型（「+」→「向量模型」）"))
        }

        val roots = candidateRoots(config.baseUrl)
        if (roots.isEmpty()) {
            return Result.failure(AiClientException("Base URL 为空"))
        }

        val payload = JSONObject().apply {
            put("model", model)
            put("input", JSONArray().apply { texts.forEach { put(it) } })
        }.toString()

        return tryWithRoots(roots) { root ->
            val request = Request.Builder()
                .url("$root/embeddings")
                .applyAuth(config.apiKey)
                .header("Accept", "application/json")
                .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            client.newCall(request).executeOrCancel().use { response ->
                val body = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    Result.success(parseEmbeddings(body, texts.size))
                } else {
                    Result.failure(httpFailure(response.code, body))
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // URL 候选
    // -----------------------------------------------------------------------

    /**
     * 把一个 Base URL 展开成若干候选根地址。
     *
     * 顺序就是尝试顺序：
     *  1. **用户原样输入的**（去掉结尾的 `/`）—— 尊重用户意图，他多半是照着文档填的；
     *  2. 补上 `/v1` 的版本 —— 覆盖「只填了域名」的情况；
     *  3. 去掉 `/v1` 的版本 —— 覆盖「多填了 /v1」的中转站。
     *
     * 去重后返回，最多三个。
     */
    private fun candidateRoots(baseUrl: String): List<String> {
        val trimmed = baseUrl.trim().trimEnd('/')
        if (trimmed.isEmpty()) return emptyList()

        val hasVersionSuffix = trimmed.endsWith(VERSION_SEGMENT)
        val withVersion = if (hasVersionSuffix) trimmed else "$trimmed$VERSION_SEGMENT"
        val withoutVersion = if (hasVersionSuffix) {
            trimmed.removeSuffix(VERSION_SEGMENT)
        } else {
            trimmed
        }

        return listOf(trimmed, withVersion, withoutVersion).distinct()
    }

    /**
     * 依次尝试候选根地址。
     *
     * **只在 404 时换下一个**：404 的含义是「这个路径上没有东西」，属于地址形态问题；
     * 而 401/403/429/5xx 说明地址找对了、是别的地方有问题，换地址只会把真实原因盖掉。
     *
     * `block` 显式标成 `suspend` lambda：它内部要发起网络请求。靠「inline 之后调用点
     * 处于挂起上下文」这条路也能编译，但那是一条隐式规则，写清楚更不容易被后人改坏。
     */
    private suspend inline fun <T> tryWithRoots(
        roots: List<String>,
        block: suspend (String) -> Result<T>,
    ): Result<T> {
        var lastFailure: Throwable? = null

        for (root in roots) {
            val outcome = try {
                block(root)
            } catch (e: IOException) {
                Result.failure(AiClientException(describeNetwork(e, root), e))
            } catch (e: Throwable) {
                e.ensureNotCancellation()
                Result.failure(AiClientException(describeUnexpected(e), e))
            }

            outcome.onSuccess { return outcome }

            val failure = outcome.exceptionOrNull()
            if (failure !is AiClientException || failure.httpCode != HTTP_NOT_FOUND) {
                return outcome
            }
            lastFailure = failure
        }

        return Result.failure(
            lastFailure ?: AiClientException("请求失败"),
        )
    }

    // -----------------------------------------------------------------------
    // 请求构建
    // -----------------------------------------------------------------------

    private fun message(role: String, content: String): JSONObject = JSONObject().apply {
        put("role", role)
        put("content", content)
    }

    /**
     * 按需附加鉴权头。
     *
     * Key 为空时**不加**这个头：Ollama 之类的本地端点收到
     * `Authorization: Bearer `（空 token）会直接判定鉴权失败并返回 400，
     * 而它们本来就不需要鉴权。
     */
    private fun Request.Builder.applyAuth(apiKey: String): Request.Builder =
        if (apiKey.isBlank()) this else header("Authorization", "Bearer ${apiKey.trim()}")

    // -----------------------------------------------------------------------
    // 响应解析
    // -----------------------------------------------------------------------

    /**
     * 解析模型列表。标准形态是 `{"data":[{"id":"..."}]}`，也认直接返回 `["a","b"]` 的。
     */
    private fun parseModelIds(body: String): List<String> {
        if (body.isBlank()) return emptyList()

        val ids = mutableListOf<String>()

        runCatching {
            val data = JSONObject(body).optJSONArray("data") ?: return@runCatching
            for (index in 0 until data.length()) {
                val id = data.optJSONObject(index)?.optString("id").orEmpty()
                if (id.isNotBlank()) ids += id
            }
        }

        if (ids.isEmpty()) {
            runCatching {
                val array = JSONArray(body)
                for (index in 0 until array.length()) {
                    val asObject = array.optJSONObject(index)
                    val id = asObject?.optString("id") ?: array.optString(index)
                    if (id.isNotBlank()) ids += id
                }
            }
        }

        return ids.distinct().sorted()
    }

    /**
     * 从 chat completion 响应里取出助手回复的正文。
     *
     * 取不到就返回空串而不是报错：「握手成功但模型没说话」和「连不上」是两回事。
     */
    /**
     * 解析 usage 里的缓存命中字段。
     *
     * 两种上报方式都要认，因为这个应用的定位就是「用户会把它指向各种中转站」：
     *  - DeepSeek 官方：`usage.prompt_cache_hit_tokens` / `prompt_cache_miss_tokens`
     *  - OpenAI 官方：`usage.prompt_tokens_details.cached_tokens`
     *
     * 认不出就返回 null，界面上那一行整段不显示 —— 不猜、不显示 0，
     * 因为「端点没上报」和「一次都没命中」是两件完全不同的事。
     */
    private fun parseCacheStats(body: String): PromptCacheStats? {
        if (body.isBlank()) return null
        return runCatching {
            val usage = JSONObject(body).optJSONObject("usage") ?: return@runCatching null
            val promptTokens = usage.optLong("prompt_tokens", 0L)
            if (promptTokens <= 0L) return@runCatching null

            val deepSeekHit = usage.optLong("prompt_cache_hit_tokens", -1L).takeIf { it >= 0L }
            val openAiHit = usage.optJSONObject("prompt_tokens_details")
                ?.optLong("cached_tokens", 0L)
            val cached = (deepSeekHit ?: openAiHit ?: 0L).coerceIn(0L, promptTokens)

            PromptCacheStats(
                promptTokens = promptTokens,
                cachedTokens = cached,
                completionTokens = usage.optLong("completion_tokens", 0L),
            )
        }.getOrNull()
    }

    private fun parseAssistantContent(body: String): String {
        if (body.isBlank()) return ""
        return runCatching {
            val choices = JSONObject(body).optJSONArray("choices") ?: return@runCatching ""
            val first = choices.optJSONObject(0) ?: return@runCatching ""
            first.optJSONObject("message")?.optString("content").orEmpty()
                .ifBlank { first.optString("text") }
        }.getOrDefault("")
    }

    /**
     * 解析向量响应，并按 `index` 字段重新排好序。
     *
     * 必须按 index 重排：OpenAI 的返回**不保证顺序与输入一致**，一旦错位，
     * 记忆检索就会把 A 的向量当成 B 的，召回结果全是乱的 —— 而且这种错误
     * 在界面上完全看不出来。
     */
    private fun parseEmbeddings(body: String, expected: Int): List<List<Float>> {
        val slots = arrayOfNulls<List<Float>>(expected)

        runCatching {
            val data = JSONObject(body).optJSONArray("data") ?: return@runCatching
            for (i in 0 until data.length()) {
                val item = data.optJSONObject(i) ?: continue
                val index = item.optInt("index", i).coerceIn(0, expected - 1)
                val vectorArray = item.optJSONArray("embedding") ?: continue
                val vector = ArrayList<Float>(vectorArray.length())
                for (j in 0 until vectorArray.length()) {
                    vector.add(vectorArray.optDouble(j, 0.0).toFloat())
                }
                slots[index] = vector
            }
        }

        return slots.map { it ?: emptyList() }
    }

    // -----------------------------------------------------------------------
    // 错误翻译
    // -----------------------------------------------------------------------

    /**
     * 把一个 HTTP 失败响应翻译成异常。带上 `httpCode`，供 [tryWithRoots] 判断
     * 「要不要换下一个候选地址」。
     */
    private fun httpFailure(code: Int, body: String): AiClientException =
        AiClientException(describeHttp(code, body), httpCode = code)

    /**
     * 把 HTTP 状态码翻译成用户能看懂的一句话。
     *
     * 尽量带上服务端自己返回的 `error.message` —— 那是唯一能解释「为什么 401」的
     * 信息来源（是 Key 过期、还是余额不足、还是模型没开通）。
     */
    private fun describeHttp(code: Int, body: String): String {
        val base = when (code) {
            400 -> "400 请求被拒绝：请检查模型名、向量模型名与端点是否匹配"
            401 -> "401 无效的 API Key"
            403 -> "403 拒绝访问：Key 可能没有该模型或该端点的权限"
            404 -> "404 路径不存在：Base URL 可能不对（已尝试自动补 /v1）"
            408 -> "408 服务端等待超时"
            413 -> "413 请求体过大：上下文或记忆太长，试试减少注入内容"
            429 -> "429 请求过于频繁或额度不足"
            in 500..599 -> "$code 服务端错误，稍后重试或联系端点提供方"
            else -> "HTTP $code"
        }
        val serverMessage = extractServerMessage(body)
        return if (serverMessage.isNullOrBlank()) base else "$base（$serverMessage）"
    }

    /** 从错误响应体里挖出服务端的错误说明。挖不到就返回 null。 */
    private fun extractServerMessage(body: String): String? {
        if (body.isBlank()) return null
        return runCatching {
            val json = JSONObject(body)
            json.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
                ?: json.optString("message").takeIf { it.isNotBlank() }
        }.getOrNull()?.take(MAX_SERVER_MESSAGE_LENGTH)
    }

    /**
     * 把网络异常翻译成用户能看懂的一句话。
     *
     * 分支顺序不能乱：这些异常都是 [IOException] 的子类，父类判断必须放在最后。
     */
    private fun describeNetwork(e: IOException, root: String): String = when (e) {
        is SocketTimeoutException -> "连接超时：$root 在 ${READ_TIMEOUT_SECONDS} 秒内没有响应"
        is UnknownHostException -> "无法解析域名：${e.message ?: root}"
        is ConnectException -> "连接被拒绝：$root 上没有服务在监听"
        is SSLException -> "TLS 握手失败：证书不受信任或协议不匹配"
        is InterruptedIOException -> "请求被中断：${e.message ?: "未知原因"}"
        else -> "网络错误：${e.message ?: e.javaClass.simpleName}"
    }

    /**
     * 兜底文案。
     *
     * 走到这里说明出现了一个我们没预料到的异常类型（缺权限、SDK 内部错误……）。
     * 无论如何都不能把它漏到界面上 —— 用户需要的是「发生了什么」，不是类名。
     */
    private fun describeUnexpected(e: Throwable): String =
        "请求失败：${e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName}"

    // -----------------------------------------------------------------------
    // 协程桥接
    // -----------------------------------------------------------------------

    /**
     * 在当前 IO 线程上同步执行请求，并在协程被取消时主动取消它。
     *
     * 见文件头注释一：这里刻意不用 `enqueue`，因为 OkHttp 会在自己的线程上重新抛出
     * 非 IOException 的异常，那会直接杀进程。
     */
    private suspend fun Call.executeOrCancel(): Response = withContext(Dispatchers.IO) {
        val handle = coroutineContext[Job]?.invokeOnCompletion { cause ->
            // 只有异常结束（含取消）才取消请求；正常完成时什么都不做。
            if (cause != null) runCatching { cancel() }
        }
        try {
            execute()
        } finally {
            handle?.dispose()
        }
    }

    companion object {
        private const val VERSION_SEGMENT = "/v1"
        private const val HTTP_NOT_FOUND = 404

        /** 测试用的一句话 Prompt。够短，够明确，模型随便回什么都行。 */
        private const val TEST_PROMPT = "ping"

        /** 测试请求的最大输出 token 数。1 是很多端点能接受的最小值。 */
        private const val TEST_MAX_TOKENS = 1

        private const val CONNECT_TIMEOUT_SECONDS = 15L
        private const val READ_TIMEOUT_SECONDS = 60L
        private const val WRITE_TIMEOUT_SECONDS = 30L

        private const val MAX_SERVER_MESSAGE_LENGTH = 120

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        /**
         * 共用一个 OkHttpClient 实例。
         *
         * OkHttp 自己维护连接池与线程池，每次请求新建一个客户端等于把池化全扔掉。
         * 这个实例由 `AppContainer` 持有，寿命等于进程寿命。
         *
         * 读超时给到 60 秒：对话请求要等模型把整段话生成完，比列模型慢得多。
         */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}

/**
 * 一条发给模型的消息。
 *
 * `role` 直接就是 OpenAI 协议里的字符串（system / user / assistant），不做枚举 ——
 * 这个类型是**线格式**的一部分，多一层映射只会让调试时对不上号。
 */
data class ChatTurn(
    val role: String,
    val content: String,
) {
    companion object {
        fun system(content: String) = ChatTurn("system", content)
        fun user(content: String) = ChatTurn("user", content)
        fun assistant(content: String) = ChatTurn("assistant", content)
    }
}

/**
 * 一次对话请求的结果：正文 + 这次请求的**缓存命中情况**。
 *
 * 为什么要专门把 usage 带出来：DeepSeek 的上下文硬盘缓存是按前缀命中的，而
 * 「命中没命中」只能从响应里读。不给用户看这个数，就没有任何办法验证提示词的
 * 排布到底有没有生效 —— 而这正是最容易在后续改动里被无声破坏的东西
 * （往 system 里加一个随时间变化的字段，命中率立刻归零，界面上却毫无变化）。
 *
 * @param content 模型回复的正文
 * @param cacheStats 端点上报了 usage 才有；不认识这个字段的端点返回 null
 */
data class ChatCompletion(
    val content: String,
    val cacheStats: PromptCacheStats?,
)

/**
 * 一次请求的前缀缓存命中情况。
 *
 * 字段名按 DeepSeek 的语义命名，但取值同时兼容两种上报方式：
 *  - DeepSeek：`usage.prompt_cache_hit_tokens`
 *  - OpenAI：`usage.prompt_tokens_details.cached_tokens`
 *
 * @param promptTokens 本次请求的输入 token 总数
 * @param cachedTokens 其中**命中缓存**的部分（按缓存价计费）
 */
data class PromptCacheStats(
    val promptTokens: Long,
    val cachedTokens: Long,
    val completionTokens: Long = 0L,
) {
    /** 未命中的输入 token 数（按原价计费）。 */
    val missTokens: Long get() = (promptTokens - cachedTokens).coerceAtLeast(0L)

    /** 命中率，0f~1f。用于界面上那句结论。 */
    val hitRatio: Float
        get() = if (promptTokens <= 0L) 0f else cachedTokens.toFloat() / promptTokens.toFloat()
}

/**
 * 网络层对外抛出的唯一异常类型。
 *
 * [message] 一定是**可以直接显示给用户的中文**，界面上不需要再做任何翻译。
 * [httpCode] 只在内部用于判断「要不要换下一个候选 Base URL」，不显示给用户。
 */
class AiClientException(
    message: String,
    cause: Throwable? = null,
    val httpCode: Int = 0,
) : Exception(message, cause)

/**
 * 取消异常必须原样放行。
 *
 * 把它当成普通失败吞掉，协程的结构化并发就失效了 —— 上层会以为「请求失败了」而
 * 继续往下走，实际上下面的代码本来就不该再执行。
 */
internal fun Throwable.ensureNotCancellation() {
    if (this is kotlinx.coroutines.CancellationException) throw this
}
