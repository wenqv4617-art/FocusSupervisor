package com.focussupervisor.app.core.backup

import com.focussupervisor.app.data.datastore.AppPreferences
import com.focussupervisor.app.data.datastore.PreferencesCodec
import org.json.JSONObject

/**
 * 备份文件的正文编解码。
 *
 * ===========================================================================
 * 为什么备份的不是 DataStore 的二进制文件
 * ===========================================================================
 * DataStore 把 Preferences 存成一个 protobuf 二进制文件。把它原样打进备份包看起来
 * 最省事，但有三个问题：
 *
 *  1. **无法校验、无法手看**。用户拿到一个二进制块，除了「恢复了没」以外什么都
 *     确认不了。而备份这种东西，能自己打开看一眼内容是很有价值的。
 *  2. **跟 DataStore 的内部实现绑死**。它的文件格式不是公开契约，某天变了，
 *     旧备份就全废了。
 *  3. **恢复时只能整文件替换**，而那正是最容易出事的一条路（见
 *     `AppPreferencesDataSource.restoreAll` 的注释）。
 *
 * 所以备份的正文是一份 **JSON**，键名与 DataStore 里的键一一对应。
 * 它可读、可校验、可以只恢复其中一部分，而且走的是正常的写入口。
 *
 * ===========================================================================
 * 为什么直接复用 PreferencesCodec
 * ===========================================================================
 * 每个字段怎么变成字符串，`PreferencesCodec` 里已经有一份实现，而且那份实现
 * 承担着「解析失败只丢自己」的容错责任。备份这里再抄一遍等于维护两套格式，
 * 早晚会漂移。所以这里只负责**把各个字段的字符串拼成一个 JSON 对象**，
 * 格式本身仍然只有一个来源。
 */
internal object BackupCodec {

    /** 备份格式版本。将来字段变了，靠它决定怎么迁移。 */
    const val FORMAT_VERSION = 1

    /**
     * 键名与 DataStore 的键保持一致的字段。
     *
     * 用列表驱动而不是手写十几行 —— 加一个字段只需要在这里加一行，
     * 漏掉一个字段是这类代码最典型的 bug，而列表让遗漏在对比时一眼可见。
     */
    private val FIELDS: List<Pair<String, (AppPreferences) -> String>> = listOf(
        "whitelist_json" to { PreferencesCodec.encodeWhitelist(it.whitelist) },
        "todos_json" to { PreferencesCodec.encodeTodos(it.todos) },
        "ai_presets_json" to { PreferencesCodec.encodePresets(it.presets) },
        "ai_selected_preset_id" to { it.selectedPresetId },
        "embedding_config_json" to { PreferencesCodec.encodeEmbeddingConfig(it.embeddingConfig) },
        "vision_config_json" to { PreferencesCodec.encodeVisionConfig(it.visionConfig) },
        "gaze_config_json" to { PreferencesCodec.encodeGazeConfig(it.gazeConfig) },
        "ai_persona_json" to { PreferencesCodec.encodeAiPersona(it.aiPersona).toString() },
        "user_persona_json" to { PreferencesCodec.encodeUserPersona(it.userPersona).toString() },
        "memories_json" to { PreferencesCodec.encodeMemories(it.memories) },
        "memory_index_json" to { PreferencesCodec.encodeIndex(it.memoryIndex) },
        "messages_json" to { PreferencesCodec.encodeMessages(it.messages) },
        "timeline_json" to { PreferencesCodec.encodeTimeline(it.timeline) },
        "appearance_json" to { PreferencesCodec.encodeAppearance(it.appearance) },
    )

    /** 把一份完整偏好编码成 JSON 文本。 */
    fun encode(preferences: AppPreferences): String = JSONObject().apply {
        put("formatVersion", FORMAT_VERSION)
        FIELDS.forEach { (key, encoder) -> put(key, encoder(preferences)) }
    }.toString()

    /**
     * 把备份正文解回一份偏好。
     *
     * 逐字段解析、单字段失败只丢自己 —— 与 [PreferencesCodec] 的容错原则一致。
     * 一份备份里有一条坏掉的记忆，不该让整个恢复失败。
     */
    fun decode(json: String): AppPreferences {
        val obj = JSONObject(json)

        fun raw(key: String): String? = obj.optString(key).takeIf { it.isNotBlank() }

        return AppPreferences(
            whitelist = PreferencesCodec.decodeWhitelist(raw("whitelist_json")),
            todos = PreferencesCodec.decodeTodos(raw("todos_json")),
            presets = PreferencesCodec.decodePresets(raw("ai_presets_json")),
            selectedPresetId = obj.optString("ai_selected_preset_id"),
            embeddingConfig = PreferencesCodec.decodeEmbeddingConfig(raw("embedding_config_json")),
            visionConfig = PreferencesCodec.decodeVisionConfig(raw("vision_config_json")),
            gazeConfig = PreferencesCodec.decodeGazeConfig(raw("gaze_config_json")),
            aiPersona = PreferencesCodec.decodeAiPersona(raw("ai_persona_json")),
            userPersona = PreferencesCodec.decodeUserPersona(raw("user_persona_json")),
            memories = PreferencesCodec.decodeMemories(raw("memories_json")),
            memoryIndex = PreferencesCodec.decodeIndex(raw("memory_index_json")),
            messages = PreferencesCodec.decodeMessages(raw("messages_json")),
            timeline = PreferencesCodec.decodeTimeline(raw("timeline_json")),
            appearance = PreferencesCodec.decodeAppearance(raw("appearance_json")),
            isSeeded = true,
        )
    }

    /** 备份正文的文件名（zip 内）。 */
    const val ENTRY_DATA = "data.json"

    /** 清单文件名（zip 内）。 */
    const val ENTRY_MANIFEST = "manifest.json"

    /** 背景图在 zip 内的条目名。 */
    const val ENTRY_BACKGROUND = "chat_background.jpg"
}
