package com.focussupervisor.app.data.datastore

import android.util.Log
import com.focussupervisor.app.domain.model.AiConfig
import com.focussupervisor.app.domain.model.AiPersona
import com.focussupervisor.app.domain.model.AiPreset
import com.focussupervisor.app.domain.model.ChatMessage
import com.focussupervisor.app.domain.model.IndexedDocKind
import com.focussupervisor.app.domain.model.IndexedDocument
import com.focussupervisor.app.domain.model.MemoryEntry
import com.focussupervisor.app.domain.model.MemorySource
import com.focussupervisor.app.domain.model.MemoryTier
import com.focussupervisor.app.domain.model.MessageSender
import com.focussupervisor.app.domain.model.PersonaGender
import com.focussupervisor.app.domain.model.TodoItem
import com.focussupervisor.app.domain.model.UserPersona
import com.focussupervisor.app.domain.model.WhitelistApp
import org.json.JSONArray
import java.util.Base64
import org.json.JSONObject

/**
 * DataStore 里所有值的 JSON 编解码。
 *
 * ===========================================================================
 * 为什么单独拆一个文件
 * ===========================================================================
 * 要持久化的东西已经有七类（白名单、待办、AI 预设、两个人设、记忆、向量索引、
 * 对话）。把它们和 DataStore 的读写混在一个类里，那个类会膨胀到七八百行，
 * 而其中真正跟 DataStore 有关的只有三十行。
 *
 * 拆开之后：`AppPreferencesDataSource` 只管「什么时候读、什么时候写」，
 * 本文件只管「怎么把对象变成字符串、怎么变回来」——
 * **后者是纯函数，不依赖 Android，可以单独测**。
 *
 * ===========================================================================
 * 所有字段名都是持久化契约
 * ===========================================================================
 * 这些字符串一旦写进用户的磁盘就不能再改 —— 改了等于读不出旧数据。
 * 所以全部集中在本文件底部，让人一眼看出「这些不是普通常量」。
 *
 * ===========================================================================
 * 解析失败的处理原则
 * ===========================================================================
 * **单条脏数据只丢自己，整份数据读不出来才丢整份。** 每一项都是
 * `runCatching { ... }.getOrElse { 记日志 + 返回空 }`，绝不让一个坏字符把
 * 用户的全部配置带走，更不让它把应用搞得起不来。
 */
internal object PreferencesCodec {

    private const val TAG = "PreferencesCodec"

    // =======================================================================
    // 白名单
    // =======================================================================

    fun encodeWhitelist(whitelist: List<WhitelistApp>): String {
        val array = JSONArray()
        whitelist.forEach { entry ->
            array.put(
                JSONObject().apply {
                    put(FIELD_PACKAGE_NAME, entry.packageName)
                    put(FIELD_APP_NAME, entry.appName)
                    // null 表示常驻。用 JSONObject.NULL 而不是省略字段，
                    // 这样解码端只需要判断 isNull。
                    put(FIELD_EXPIRES_AT, entry.expiresAtMillis ?: JSONObject.NULL)
                    put(FIELD_REASON, entry.reason)
                },
            )
        }
        return array.toString()
    }

    fun decodeWhitelist(json: String?): List<WhitelistApp> = decodeList(json, "白名单") { obj ->
        val packageName = obj.optString(FIELD_PACKAGE_NAME).takeIf { it.isNotBlank() }
            ?: return@decodeList null
        WhitelistApp(
            packageName = packageName,
            appName = obj.optString(FIELD_APP_NAME).takeIf { it.isNotBlank() } ?: packageName,
            expiresAtMillis = if (obj.isNull(FIELD_EXPIRES_AT)) {
                null
            } else {
                obj.optLong(FIELD_EXPIRES_AT).takeIf { it > 0L }
            },
            reason = obj.optString(FIELD_REASON),
        )
    }

    // =======================================================================
    // 待办
    // =======================================================================

    fun encodeTodos(todos: List<TodoItem>): String {
        val array = JSONArray()
        todos.forEach { todo ->
            array.put(
                JSONObject().apply {
                    put(FIELD_ID, todo.id)
                    put(FIELD_TITLE, todo.title)
                    put(FIELD_PLANNED_AT, todo.plannedAtMillis)
                    put(FIELD_IS_DONE, todo.isDone)
                },
            )
        }
        return array.toString()
    }

    fun decodeTodos(json: String?): List<TodoItem> = decodeList(json, "待办") { obj ->
        val id = obj.optString(FIELD_ID).takeIf { it.isNotBlank() } ?: return@decodeList null
        TodoItem(
            id = id,
            title = obj.optString(FIELD_TITLE),
            plannedAtMillis = obj.optLong(FIELD_PLANNED_AT),
            isDone = obj.optBoolean(FIELD_IS_DONE, false),
        )
    }

    // =======================================================================
    // AI 预设
    // =======================================================================

    fun encodePresets(presets: List<AiPreset>): String {
        val array = JSONArray()
        presets.forEach { preset ->
            array.put(
                JSONObject().apply {
                    put(FIELD_ID, preset.id)
                    put(FIELD_NAME, preset.name)
                    put(FIELD_BASE_URL, preset.config.baseUrl)
                    put(FIELD_API_KEY, preset.config.apiKey)
                    put(FIELD_MODEL, preset.config.model)
                    // org.json 只有 put(String, double)，Float 会被隐式提升；
                    // 读回来再转 Float，往返精度对 0.1 这种步进足够。
                    put(FIELD_TEMPERATURE, preset.config.temperature.toDouble())
                    put(FIELD_EMBEDDING_MODEL, preset.config.embeddingModel)
                    // 前置提示可能很长且含换行，JSON 会自己转义，不需要特殊处理。
                    put(FIELD_PRE_PROMPT, preset.config.prePrompt)
                },
            )
        }
        return array.toString()
    }

    fun decodePresets(json: String?): List<AiPreset> = decodeList(json, "AI 预设") { obj ->
        val id = obj.optString(FIELD_ID).takeIf { it.isNotBlank() } ?: return@decodeList null
        AiPreset(
            id = id,
            name = obj.optString(FIELD_NAME).takeIf { it.isNotBlank() } ?: id,
            config = AiConfig(
                baseUrl = obj.optString(FIELD_BASE_URL),
                apiKey = obj.optString(FIELD_API_KEY),
                model = obj.optString(FIELD_MODEL),
                temperature = obj.optDouble(
                    FIELD_TEMPERATURE,
                    AiConfig.DEFAULT_TEMPERATURE.toDouble(),
                ).toFloat(),
                embeddingModel = obj.optString(FIELD_EMBEDDING_MODEL),
                prePrompt = obj.optString(FIELD_PRE_PROMPT),
            ),
        )
    }

    // =======================================================================
    // 人设
    // =======================================================================

    fun encodeAiPersona(persona: AiPersona): JSONObject = JSONObject().apply {
        put(FIELD_NAME, persona.name)
        put(FIELD_AVATAR_PATH, persona.avatarPath ?: JSONObject.NULL)
        put(FIELD_GENDER, persona.gender.name)
        put(FIELD_DESCRIPTION, persona.description)
    }

    fun decodeAiPersona(json: String?): AiPersona {
        if (json.isNullOrBlank()) return AiPersona()
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return AiPersona()

        return AiPersona(
            name = obj.optString(FIELD_NAME).takeIf { it.isNotBlank() } ?: AiPersona.DEFAULT_NAME,
            avatarPath = obj.optString(FIELD_AVATAR_PATH).takeIf { it.isNotBlank() },
            gender = runCatching { PersonaGender.valueOf(obj.optString(FIELD_GENDER)) }
                .getOrDefault(PersonaGender.UNSPECIFIED),
            description = obj.optString(FIELD_DESCRIPTION),
        )
    }

    fun encodeUserPersona(persona: UserPersona): JSONObject = JSONObject().apply {
        put(FIELD_NAME, persona.name)
        put(FIELD_AVATAR_PATH, persona.avatarPath ?: JSONObject.NULL)
    }

    fun decodeUserPersona(json: String?): UserPersona {
        if (json.isNullOrBlank()) return UserPersona()
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return UserPersona()

        return UserPersona(
            name = obj.optString(FIELD_NAME).takeIf { it.isNotBlank() } ?: UserPersona.DEFAULT_NAME,
            avatarPath = obj.optString(FIELD_AVATAR_PATH).takeIf { it.isNotBlank() },
        )
    }

    // =======================================================================
    // 记忆
    // =======================================================================

    fun encodeMemories(memories: List<MemoryEntry>): String {
        val array = JSONArray()
        memories.forEach { entry ->
            array.put(
                JSONObject().apply {
                    put(FIELD_ID, entry.id)
                    put(FIELD_CONTENT, entry.content)
                    put(FIELD_TIER, entry.tier.name)
                    put(FIELD_SOURCE, entry.source.name)
                    put(FIELD_CREATED_AT, entry.createdAtMillis)
                    put(FIELD_LAST_RECALLED_AT, entry.lastRecalledAtMillis ?: JSONObject.NULL)
                    put(FIELD_RECALL_COUNT, entry.recallCount)
                    put(FIELD_PINNED, entry.pinned)
                    put(FIELD_EMBEDDING, encodeVector(entry.embedding))
                },
            )
        }
        return array.toString()
    }

    fun decodeMemories(json: String?): List<MemoryEntry> = decodeList(json, "记忆") { obj ->
        val id = obj.optString(FIELD_ID).takeIf { it.isNotBlank() } ?: return@decodeList null
        MemoryEntry(
            id = id,
            content = obj.optString(FIELD_CONTENT),
            tier = enumOrDefault(obj.optString(FIELD_TIER), MemoryTier.SHORT_TERM),
            source = enumOrDefault(obj.optString(FIELD_SOURCE), MemorySource.SYSTEM),
            createdAtMillis = obj.optLong(FIELD_CREATED_AT),
            lastRecalledAtMillis = if (obj.isNull(FIELD_LAST_RECALLED_AT)) {
                null
            } else {
                obj.optLong(FIELD_LAST_RECALLED_AT).takeIf { it > 0L }
            },
            recallCount = obj.optInt(FIELD_RECALL_COUNT, 0),
            embedding = decodeVector(obj.optString(FIELD_EMBEDDING)),
            pinned = obj.optBoolean(FIELD_PINNED, false),
        )
    }

    // =======================================================================
    // 向量索引
    // =======================================================================

    fun encodeIndex(documents: List<IndexedDocument>): String {
        val array = JSONArray()
        documents.forEach { doc ->
            array.put(
                JSONObject().apply {
                    put(FIELD_ID, doc.id)
                    put(FIELD_KIND, doc.kind.name)
                    put(FIELD_CONTENT, doc.text)
                    put(FIELD_CREATED_AT, doc.createdAtMillis)
                    put(FIELD_MEMORY_ID, doc.memoryId ?: JSONObject.NULL)
                    put(FIELD_EMBEDDING, encodeVector(doc.embedding))
                },
            )
        }
        return array.toString()
    }

    fun decodeIndex(json: String?): List<IndexedDocument> = decodeList(json, "向量索引") { obj ->
        val id = obj.optString(FIELD_ID).takeIf { it.isNotBlank() } ?: return@decodeList null
        IndexedDocument(
            id = id,
            kind = enumOrDefault(obj.optString(FIELD_KIND), IndexedDocKind.CONVERSATION),
            text = obj.optString(FIELD_CONTENT),
            createdAtMillis = obj.optLong(FIELD_CREATED_AT),
            embedding = decodeVector(obj.optString(FIELD_EMBEDDING)),
            memoryId = obj.optString(FIELD_MEMORY_ID).takeIf { it.isNotBlank() },
        )
    }

    // =======================================================================
    // 对话
    // =======================================================================

    fun encodeMessages(messages: List<ChatMessage>): String {
        val array = JSONArray()
        messages.forEach { message ->
            array.put(
                JSONObject().apply {
                    put(FIELD_ID, message.id)
                    put(FIELD_SENDER, message.sender.name)
                    put(FIELD_TEXT, message.text)
                    put(FIELD_CREATED_AT, message.timestampMillis)
                },
            )
        }
        return array.toString()
    }

    fun decodeMessages(json: String?): List<ChatMessage> = decodeList(json, "对话") { obj ->
        val id = obj.optString(FIELD_ID).takeIf { it.isNotBlank() } ?: return@decodeList null
        ChatMessage(
            id = id,
            sender = enumOrDefault(obj.optString(FIELD_SENDER), MessageSender.SYSTEM),
            text = obj.optString(FIELD_TEXT),
            timestampMillis = obj.optLong(FIELD_CREATED_AT),
        )
    }

    // =======================================================================
    // 向量
    // =======================================================================

    /**
     * 向量的存储格式：**归一化 → 8bit 量化 → Base64**。
     *
     * ===========================================================================
     * 为什么要搞得这么麻烦
     * ===========================================================================
     * 一个 1536 维的向量，如果按 `[0.123456,0.234567,...]` 这样存：
     *  - 每维约 9 个字符 → 单条约 14KB；
     *  - 300 条对话 + 200 条记忆 = 500 条 → **7MB 的 JSON**。
     *
     * 而 DataStore 每次写入都要重写整份文件、每次读取都要重新解码。7MB 意味着
     * 每发一条消息都要序列化 + 反序列化 7MB —— 这是能把手机卡出白屏的量级。
     *
     * 三步压缩之后：
     *  1. **归一化**：向量变成单位长度，于是余弦相似度退化成点积，检索更快；
     *  2. **8bit 量化**：单位向量的每一维必在 [-1,1]，用 1 个字节表示精度绰绰有余
     *     （检索排序不需要小数第 6 位）；
     *  3. **Base64**：1536 字节 → 2048 个可打印字符，且 JSON 里不需要转义。
     *
     * 结果：单条约 2KB，500 条约 1MB。**精度损失对排序没有可观测影响**，
     * 因为余弦相似度的判别力远大于 1/127 的量化误差。
     *
     * 维度不一致（用户中途换了 embedding 模型）时，余弦函数会返回 0，
     * 那些旧向量会自然沉底 —— 不会产生看似合理实则胡说八道的分数。
     */
    private fun encodeVector(vector: List<Float>): String {
        if (vector.isEmpty()) return ""

        val normalized = normalize(vector)
        val bytes = ByteArray(normalized.size) { index ->
            // [-1, 1] → [0, 255]
            val scaled = ((normalized[index] + 1f) * 127.5f).toInt().coerceIn(0, 255)
            scaled.toByte()
        }
        return Base64.getEncoder().encodeToString(bytes)
    }

    private fun decodeVector(raw: String?): List<Float> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val bytes = Base64.getDecoder().decode(raw)
            List(bytes.size) { index -> (bytes[index].toInt() and 0xFF) / 127.5f - 1f }
        }.getOrElse { throwable ->
            Log.w(TAG, "向量解码失败，按未向量化处理", throwable)
            emptyList()
        }
    }

    /** 把向量缩成单位长度。零向量原样返回。 */
    private fun normalize(vector: List<Float>): FloatArray {
        var sumOfSquares = 0f
        vector.forEach { sumOfSquares += it * it }

        val norm = kotlin.math.sqrt(sumOfSquares)
        val result = FloatArray(vector.size)
        if (norm <= 0f) return result

        vector.forEachIndexed { index, value -> result[index] = value / norm }
        return result
    }

    // =======================================================================
    // 通用工具
    // =======================================================================

    /**
     * 通用列表解码。
     *
     * @param what 用于日志的可读名称
     * @param item 单条解码；返回 null 表示这条不合法，跳过它
     */
    private inline fun <T> decodeList(
        json: String?,
        what: String,
        item: (JSONObject) -> T?,
    ): List<T> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(json)
            (0 until array.length()).mapNotNull { index ->
                array.optJSONObject(index)?.let(item)
            }
        }.getOrElse { throwable ->
            Log.w(TAG, "$what 解析失败，已丢弃这份数据", throwable)
            emptyList()
        }
    }

    /** 枚举解析失败时回退到默认值，而不是抛异常。 */
    private inline fun <reified T : Enum<T>> enumOrDefault(name: String, fallback: T): T =
        runCatching { enumValueOf<T>(name) }.getOrDefault(fallback)

    // =======================================================================
    // 字段名（持久化契约，改不得）
    // =======================================================================

    private const val FIELD_PACKAGE_NAME = "packageName"
    private const val FIELD_APP_NAME = "appName"
    private const val FIELD_EXPIRES_AT = "expiresAtMillis"
    private const val FIELD_REASON = "reason"
    private const val FIELD_ID = "id"
    private const val FIELD_TITLE = "title"
    private const val FIELD_PLANNED_AT = "plannedAtMillis"
    private const val FIELD_IS_DONE = "isDone"
    private const val FIELD_NAME = "name"
    private const val FIELD_BASE_URL = "baseUrl"
    private const val FIELD_API_KEY = "apiKey"
    private const val FIELD_MODEL = "model"
    private const val FIELD_TEMPERATURE = "temperature"
    private const val FIELD_EMBEDDING_MODEL = "embeddingModel"
    private const val FIELD_PRE_PROMPT = "prePrompt"
    private const val FIELD_AVATAR_PATH = "avatarPath"
    private const val FIELD_GENDER = "gender"
    private const val FIELD_DESCRIPTION = "description"
    private const val FIELD_CONTENT = "content"
    private const val FIELD_TIER = "tier"
    private const val FIELD_SOURCE = "source"
    private const val FIELD_CREATED_AT = "createdAtMillis"
    private const val FIELD_LAST_RECALLED_AT = "lastRecalledAtMillis"
    private const val FIELD_RECALL_COUNT = "recallCount"
    private const val FIELD_PINNED = "pinned"
    private const val FIELD_EMBEDDING = "embedding"
    private const val FIELD_KIND = "kind"
    private const val FIELD_MEMORY_ID = "memoryId"
    private const val FIELD_SENDER = "sender"
    private const val FIELD_TEXT = "text"
}
