package com.focussupervisor.app.core.network

import com.focussupervisor.app.domain.model.AiConfig
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
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
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * OpenAI 兼容端点的最小客户端。
 *
 * ===========================================================================
 * 为什么只有两个方法
 * ===========================================================================
 * 这个阶段应用需要的只是「能不能连上」和「有哪些模型可选」，还没有到真正发对话
 * 请求的时候。所以这个类刻意只做这两件事 —— 一个只有两个方法的客户端不会有人想
 * 在里面塞业务逻辑，将来要加 `chat()` 的时候自然会把它提升成独立的一层。
 *
 * 之所以能同时兼容 DeepSeek / OneAPI / Ollama / OpenAI：它们都实现了同一套
 * `/v1/models`、`/v1/chat/completions` 的 REST 约定。差异只有两处，都在这里处理掉了：
 *  1. **URL 拼装**：用户可能填 `https://api.deepseek.com`，也可能填
 *     `https://api.deepseek.com/v1`。见 [apiRoot]。
 *  2. **鉴权**：Ollama 不需要 Key，而部分服务端收到空的 `Authorization: Bearer `
 *     会直接返回 400。所以 Key 为空时干脆不带这个头。
 *
 * ===========================================================================
 * 关于「不崩溃」
 * ===========================================================================
 * 所有网络异常都被翻译成 `Result.failure(AiClientException(友好中文说明))`，
 * 绝不外抛。调用方拿到的永远是一个可以直接显示给用户的字符串，而不是
 * `java.net.SocketTimeoutException: failed to connect to /10.0.2.2 (port 11434)`。
 * 唯一的例外是 [kotlinx.coroutines.CancellationException]，它会被原样放行 ——
 * 吞掉取消异常会让协程结构化并发失效。
 */
class OpenAiCompatibleClient(
    private val client: OkHttpClient = defaultClient(),
) {

    /**
     * 拉取端点支持的模型列表。
     *
     * `GET {apiRoot}/models`
     *
     * @return 成功时是模型 id 列表（已去重、已排序）；失败时是带友好说明的异常。
     */
    suspend fun fetchModels(baseUrl: String, apiKey: String): Result<List<String>> {
        val root = apiRoot(baseUrl)
            ?: return Result.failure(AiClientException("Base URL 为空，请先填写端点地址"))

        val request = Request.Builder()
            .url("$root/models")
            .applyAuth(apiKey)
            .header("Accept", "application/json")
            .get()
            .build()

        return try {
            client.newCall(request).await().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    Result.failure(AiClientException(describeHttp(response.code, body)))
                } else {
                    Result.success(parseModelIds(body))
                }
            }
        } catch (e: IOException) {
            Result.failure(AiClientException(describeNetwork(e, root), e))
        } catch (e: RuntimeException) {
            // org.json 解析失败会抛 JSONException（RuntimeException 的子类）。
            // 有些网关在出错时返回 HTML，这里也要兜住。
            Result.failure(AiClientException("响应不是合法的 JSON，端点可能不是 OpenAI 兼容接口", e))
        }
    }

    /**
     * 用一条最轻量的 Prompt 验证连通性、URL、Key 与模型名是否都对。
     *
     * 为什么不直接请求 `/models` 就算握手成功：列模型不需要鉴权的情况很多
     * （Ollama、部分网关），也就是说「能列模型」并不代表「Key 有效、模型可用」。
     * 真正发一次对话请求才能把这两件事一起验掉。
     *
     * @return 成功时是模型返回的文本内容（可能是空串）。
     */
    suspend fun testConnection(config: AiConfig): Result<String> {
        val root = apiRoot(config.baseUrl)
            ?: return Result.failure(AiClientException("Base URL 为空，请先填写端点地址"))
        if (config.model.isBlank()) {
            return Result.failure(AiClientException("模型名为空，请先填写或点「拉取」选一个"))
        }

        val payload = JSONObject().apply {
            put("model", config.model)
            put("messages", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("content", TEST_PROMPT)
            }))
            // 只要一个字的输出。测试连接不该消耗用户的额度。
            put("max_tokens", TEST_MAX_TOKENS)
            // 温度固定 0：这是连通性测试，输出越确定越容易判断。
            put("temperature", 0)
            put("stream", false)
        }.toString()

        val request = Request.Builder()
            .url("$root/chat/completions")
            .applyAuth(config.apiKey)
            .header("Accept", "application/json")
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        return try {
            client.newCall(request).await().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    Result.failure(AiClientException(describeHttp(response.code, body)))
                } else {
                    Result.success(parseAssistantContent(body))
                }
            }
        } catch (e: IOException) {
            Result.failure(AiClientException(describeNetwork(e, root), e))
        } catch (e: RuntimeException) {
            Result.failure(AiClientException("响应不是合法的 JSON，端点可能不是 OpenAI 兼容接口", e))
        }
    }

    // -----------------------------------------------------------------------
    // URL 与请求头
    // -----------------------------------------------------------------------

    /**
     * 把用户填的 Base URL 规范成 OpenAI 兼容端点的根地址。
     *
     * 规则只有一条：**以 `/v1` 结尾就用原样，否则补一个 `/v1`。**
     *
     * 这样两种写法都能用：
     *  - `https://api.deepseek.com`        → `https://api.deepseek.com/v1`
     *  - `https://api.deepseek.com/v1`     → 不变
     *  - `http://127.0.0.1:11434/v1`       → 不变
     *
     * 刻意不做「智能识别是否已有版本号」那一套：像 `/api/v1`、`/openai/v1` 这类路径，
     * 任何猜测都会在某一家网关上翻车。规则简单且可预期，比覆盖更多情况重要 ——
     * 用户看到拼错的 URL，改一下就行；看到一个自作聪明的 URL，他连从哪改起都不知道。
     *
     * @return null 表示输入为空，调用方应当直接返回友好错误。
     */
    private fun apiRoot(baseUrl: String): String? {
        val trimmed = baseUrl.trim().trimEnd('/')
        if (trimmed.isEmpty()) return null
        return if (trimmed.endsWith(VERSION_SEGMENT)) trimmed else "$trimmed$VERSION_SEGMENT"
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
     * 解析模型列表。
     *
     * 标准形态是 `{"object":"list","data":[{"id":"deepseek-chat",...}]}`，
     * 但也见过直接返回 `["a","b"]` 的极简实现，两种都认。
     */
    private fun parseModelIds(body: String): List<String> {
        if (body.isBlank()) return emptyList()

        val ids = mutableListOf<String>()

        // 形态一：顶层对象，data 数组里是对象
        runCatching {
            val data = JSONObject(body).optJSONArray("data") ?: return@runCatching
            for (index in 0 until data.length()) {
                val item = data.optJSONObject(index)
                val id = item?.optString("id").orEmpty()
                if (id.isNotBlank()) ids += id
            }
        }

        // 形态二：顶层直接是数组
        if (ids.isEmpty()) {
            runCatching {
                val array = JSONArray(body)
                for (index in 0 until array.length()) {
                    val asObject = array.optJSONObject(index)
                    val id = asObject?.optString("id") ?: array.optString(index)
                    if (!id.isNullOrBlank()) ids += id
                }
            }
        }

        return ids.distinct().sorted()
    }

    /**
     * 从 chat completion 响应里取出助手回复的正文。
     *
     * 取不到就返回空串而不是报错：「握手成功但模型没说话」和「连不上」是两回事，
     * 前者应该显示成成功。
     */
    private fun parseAssistantContent(body: String): String {
        if (body.isBlank()) return ""
        return runCatching {
            val choices = JSONObject(body).optJSONArray("choices") ?: return@runCatching ""
            val first = choices.optJSONObject(0) ?: return@runCatching ""
            first.optJSONObject("message")?.optString("content").orEmpty()
                .ifBlank { first.optString("text") }
        }.getOrDefault("")
    }

    // -----------------------------------------------------------------------
    // 错误翻译
    // -----------------------------------------------------------------------

    /**
     * 把 HTTP 状态码翻译成用户能看懂的一句话。
     *
     * 尽量带上服务端自己返回的 `error.message` —— 那是唯一能解释「为什么 401」的
     * 信息来源（是 Key 过期、还是余额不足、还是模型没开通）。
     */
    private fun describeHttp(code: Int, body: String): String {
        val base = when (code) {
            400 -> "400 请求被拒绝，请检查模型名与 Base URL 是否匹配"
            401 -> "401 无效的 API Key"
            403 -> "403 拒绝访问，Key 可能没有该模型或该端点的权限"
            404 -> "404 路径不存在，请检查 Base URL 是否包含 /v1"
            408 -> "408 服务端等待超时"
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
            val error = json.optJSONObject("error")
            error?.optString("message")?.takeIf { it.isNotBlank() }
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

    // -----------------------------------------------------------------------
    // 协程桥接
    // -----------------------------------------------------------------------

    /**
     * 把 OkHttp 的异步回调桥接成可挂起的调用。
     *
     * 用 `enqueue` 而不是 `execute`：`execute` 是阻塞调用，协程被取消时那条线程
     * 仍然会跑满超时，而 `enqueue` 可以在取消时立刻 `cancel()` 掉请求。
     * 对一个会在用户手滑连点两次「拉取」的场景来说，这个差别是实打实的。
     */
    private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation {
            // 取消时连接可能已经结束，cancel 会抛 IllegalStateException，忽略即可。
            runCatching { cancel() }
        }

        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) {
                    continuation.resumeWithException(e)
                }
            }

            override fun onResponse(call: Call, response: Response) {
                if (continuation.isActive) {
                    continuation.resume(response)
                } else {
                    // 已经没人要这个响应了，但 body 必须显式关闭，否则连接不会归还
                    // 到连接池，泄漏会一直累积。
                    response.close()
                }
            }
        })
    }

    companion object {
        private const val VERSION_SEGMENT = "/v1"

        /** 测试用的一句话 Prompt。够短，够明确，模型随便回什么都行。 */
        private const val TEST_PROMPT = "ping"

        /** 测试请求的最大输出 token 数。1 是很多端点能接受的最小值。 */
        private const val TEST_MAX_TOKENS = 1

        private const val CONNECT_TIMEOUT_SECONDS = 10L
        private const val READ_TIMEOUT_SECONDS = 30L
        private const val WRITE_TIMEOUT_SECONDS = 15L

        private const val MAX_SERVER_MESSAGE_LENGTH = 120

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        /**
         * 共用一个 OkHttpClient 实例。
         *
         * OkHttp 自己维护连接池与线程池，每次请求新建一个客户端等于把池化全扔掉。
         * 这个实例由 `AppContainer` 持有，寿命等于进程寿命。
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
 * 网络层对外抛出的唯一异常类型。
 *
 * [message] 一定是**可以直接显示给用户的中文**，界面上不需要再做任何翻译。
 */
class AiClientException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
