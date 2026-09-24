package com.focussupervisor.app.data.datastore

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.focussupervisor.app.domain.model.AiPersona
import com.focussupervisor.app.domain.model.ChatAppearance
import com.focussupervisor.app.domain.model.AiPreset
import com.focussupervisor.app.domain.model.ChatMessage
import com.focussupervisor.app.domain.model.EmbeddingConfig
import com.focussupervisor.app.domain.model.GazeConfig
import com.focussupervisor.app.domain.model.IndexedDocument
import com.focussupervisor.app.domain.model.MemoryDefaults
import com.focussupervisor.app.domain.model.MemoryEntry
import com.focussupervisor.app.domain.model.TimelineDefaults
import com.focussupervisor.app.domain.model.TimelineEvent
import com.focussupervisor.app.domain.model.TodoItem
import com.focussupervisor.app.domain.model.UserPersona
import com.focussupervisor.app.domain.model.VisionConfig
import com.focussupervisor.app.domain.model.WhitelistApp
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

/**
 * Preferences DataStore 的进程内唯一入口。
 *
 * ===========================================================================
 * 为什么用 Preferences DataStore 而不是 Room
 * ===========================================================================
 * 要存的东西规模都很小，而且**从来不需要按条件查询** —— 永远是「整份读出来、
 * 整份写回去」。这正是 Preferences DataStore 的适用场景。Room 会带来 schema、
 * DAO、迁移文件和一整套编译期代码生成，在这个数据量下换不到实际收益。
 *
 * 唯一要留意的是**对话与向量索引**：它们的条数会随使用增长。所以两者都有硬上限
 * （见 [MAX_STORED_MESSAGES] 与 `MemoryDefaults`），不会无限膨胀成一份几十 MB 的
 * JSON —— 那才是这个选择真正的风险点。
 *
 * 本类只管「什么时候读、什么时候写」；「怎么把对象变成字符串」全部在
 * [PreferencesCodec] 里，那里是纯函数，可以单独测。
 */
class AppPreferencesDataSource(context: Context) {

    private val appContext: Context = context.applicationContext

    private val dataStore: DataStore<Preferences> = appContext.dataStore

    /**
     * 持续监听的配置流。
     *
     * `catch` 只吞 [IOException]：那通常意味着磁盘读失败或文件损坏，退化成空配置
     * 总好过让整个应用起不来。其它异常照常抛出 —— 把它们也吞掉只会把 bug 藏起来。
     */
    val preferences: Flow<AppPreferences> = dataStore.data
        .catch { throwable ->
            if (throwable is IOException) {
                Log.e(TAG, "读取本地配置失败，本次退化为空配置", throwable)
                emit(emptyPreferences())
            } else {
                throw throwable
            }
        }
        .map { prefs -> prefs.toAppPreferences() }

    /** 读一次当前配置。用于启动时的「是否已初始化」判断。 */
    suspend fun readOnce(): AppPreferences = preferences.first()

    // -----------------------------------------------------------------------
    // 首次初始化
    // -----------------------------------------------------------------------

    /**
     * 首次启动时把内置数据一次性灌进去。
     *
     * 判断与写入在**同一个 `edit` 事务**里完成，因此不存在「白名单灌了、预置没灌」
     * 这种半截状态。
     *
     * 为什么不给每个仓库各自判断「我该不该初始化」：那样会有一个隐蔽的竞态 ——
     * 仓库 A 先跑完并置上了 seeded 标记，仓库 B 随后读到 `seeded == true` 就跳过
     * 自己的初始化，于是它的数据永远是空的。
     *
     * @return true 表示本次确实执行了初始化。
     */
    suspend fun seedIfNeeded(
        builtInWhitelist: List<WhitelistApp>,
        defaultPresets: List<AiPreset>,
        defaultSelectedPresetId: String,
    ): Boolean {
        var seeded = false
        dataStore.edit { prefs ->
            if (prefs[KEY_SEEDED] == true) return@edit

            prefs[KEY_WHITELIST] = PreferencesCodec.encodeWhitelist(builtInWhitelist)
            prefs[KEY_AI_PRESETS] = PreferencesCodec.encodePresets(defaultPresets)
            prefs[KEY_AI_SELECTED_PRESET] = defaultSelectedPresetId
            prefs[KEY_SEEDED] = true
            prefs[KEY_SCHEMA_VERSION] = CURRENT_SCHEMA_VERSION
            seeded = true
        }
        return seeded
    }

    // -----------------------------------------------------------------------
    // 白名单
    // -----------------------------------------------------------------------
    //
    // 写入全部是**原子读改写**：在 `edit` 块里先解码当前值、改完再编码回去。
    // 如果上层先读内存、算好新列表、再整体写回，两个并发写就会互相丢更新
    // （后写的那个基于过期快照）。放进 edit 块由 DataStore 串行化，就不存在这个问题。

    /**
     * 插入或覆盖一条**临时**豁免。
     *
     * 同一个包名的旧临时条目会被顶掉；常驻条目（expiresAtMillis == null）原样保留，
     * 不会被一条限时豁免覆盖成会过期的。
     */
    suspend fun upsertTemporaryWhitelist(entry: WhitelistApp) {
        dataStore.edit { prefs ->
            val current = PreferencesCodec.decodeWhitelist(prefs[KEY_WHITELIST])
            val kept = current.filterNot {
                it.packageName == entry.packageName && !it.isPermanent
            }
            prefs[KEY_WHITELIST] = PreferencesCodec.encodeWhitelist(kept + entry)
        }
    }

    /** 移除某个包名的全部临时豁免。常驻条目不受影响。 */
    suspend fun removeTemporaryWhitelist(packageName: String) {
        dataStore.edit { prefs ->
            val current = PreferencesCodec.decodeWhitelist(prefs[KEY_WHITELIST])
            prefs[KEY_WHITELIST] = PreferencesCodec.encodeWhitelist(
                current.filterNot { it.packageName == packageName && !it.isPermanent },
            )
        }
    }

    /** 清掉所有已经过期的临时豁免。 */
    suspend fun removeExpiredWhitelist(nowMillis: Long) {
        dataStore.edit { prefs ->
            val current = PreferencesCodec.decodeWhitelist(prefs[KEY_WHITELIST])
            val alive = current.filter { it.isActiveAt(nowMillis) }
            if (alive.size != current.size) {
                prefs[KEY_WHITELIST] = PreferencesCodec.encodeWhitelist(alive)
            }
        }
    }

    // -----------------------------------------------------------------------
    // 待办
    // -----------------------------------------------------------------------

    suspend fun upsertTodo(todo: TodoItem) {
        dataStore.edit { prefs ->
            val current = PreferencesCodec.decodeTodos(prefs[KEY_TODOS])
                .filterNot { it.id == todo.id }
            prefs[KEY_TODOS] = PreferencesCodec.encodeTodos(
                (current + todo).sortedBy { it.plannedAtMillis },
            )
        }
    }

    suspend fun removeTodo(id: String) {
        dataStore.edit { prefs ->
            val current = PreferencesCodec.decodeTodos(prefs[KEY_TODOS])
            prefs[KEY_TODOS] = PreferencesCodec.encodeTodos(current.filterNot { it.id == id })
        }
    }

    suspend fun setTodoDone(id: String, isDone: Boolean) {
        dataStore.edit { prefs ->
            val current = PreferencesCodec.decodeTodos(prefs[KEY_TODOS])
            prefs[KEY_TODOS] = PreferencesCodec.encodeTodos(
                current.map { if (it.id == id) it.copy(isDone = isDone) else it },
            )
        }
    }

    // -----------------------------------------------------------------------
    // AI 预设
    // -----------------------------------------------------------------------

    suspend fun upsertPreset(preset: AiPreset) {
        dataStore.edit { prefs ->
            val current = PreferencesCodec.decodePresets(prefs[KEY_AI_PRESETS])
                .filterNot { it.id == preset.id }
            prefs[KEY_AI_PRESETS] = PreferencesCodec.encodePresets(current + preset)
        }
    }

    suspend fun removePreset(id: String) {
        dataStore.edit { prefs ->
            val current = PreferencesCodec.decodePresets(prefs[KEY_AI_PRESETS])
            prefs[KEY_AI_PRESETS] = PreferencesCodec.encodePresets(current.filterNot { it.id == id })
        }
    }

    suspend fun updateSelectedPreset(presetId: String) {
        dataStore.edit { prefs -> prefs[KEY_AI_SELECTED_PRESET] = presetId }
    }

    // -----------------------------------------------------------------------
    // 向量模型配置（与对话配置完全独立的一份）
    // -----------------------------------------------------------------------

    suspend fun updateEmbeddingConfig(config: EmbeddingConfig) {
        dataStore.edit { prefs ->
            prefs[KEY_EMBEDDING_CONFIG] = PreferencesCodec.encodeEmbeddingConfig(config)
        }
    }

    // -----------------------------------------------------------------------
    // 视觉模型配置（同样独立的一份）
    // -----------------------------------------------------------------------

    suspend fun updateVisionConfig(config: VisionConfig) {
        dataStore.edit { prefs ->
            prefs[KEY_VISION_CONFIG] = PreferencesCodec.encodeVisionConfig(config)
        }
    }

    // -----------------------------------------------------------------------
    // 注视监控
    // -----------------------------------------------------------------------

    suspend fun updateGazeConfig(config: GazeConfig) {
        dataStore.edit { prefs ->
            prefs[KEY_GAZE_CONFIG] = PreferencesCodec.encodeGazeConfig(config)
        }
    }

    // -----------------------------------------------------------------------
    // 聊天页外观
    // -----------------------------------------------------------------------

    suspend fun updateAppearance(appearance: ChatAppearance) {
        val safe = ChatAppearance.sanitize(appearance)
        dataStore.edit { prefs ->
            prefs[KEY_APPEARANCE] = PreferencesCodec.encodeAppearance(safe)
        }
    }

    // -----------------------------------------------------------------------
    // 整份恢复（数据管理用）
    // -----------------------------------------------------------------------

    /**
     * 用一份快照**整体覆盖**当前全部数据。
     *
     * ===========================================================================
     * 为什么走 DataStore 的 edit，而不是直接拿备份文件覆盖 .preferences_pb
     * ===========================================================================
     * 「恢复备份」最直觉的做法是把 DataStore 的磁盘文件替换掉。**但那一定会出事**：
     * DataStore 在进程内是常驻的，它持有内存中的状态与自己的写入队列，
     * 你从外面换了文件，它下一次写入会把内存里的旧状态原样盖回去 ——
     * 用户看到的现象是「恢复成功了，但一重启就变回原样」。
     *
     * 走 `edit` 就完全没有这个问题：它是 DataStore 认可的写入口，会走完整的
     * 串行化与通知流程，恢复完所有监听者立刻拿到新数据，界面同步更新。
     *
     * 代价是恢复的粒度是「整份」而不是「某个键」。对一个备份功能来说，
     * 整份覆盖本来也正是用户点那个按钮时期望的语义。
     *
     * `isSeeded` 一并恢复为 true：恢复完再让首次初始化逻辑跑一遍，
     * 会把用户刚恢复的白名单和预置冲掉。
     *
     * @return 是否写入成功。失败通常是磁盘满或文件损坏。
     */
    suspend fun restoreAll(snapshot: AppPreferences): Boolean = runCatching {
        dataStore.edit { prefs ->
            prefs[KEY_WHITELIST] = PreferencesCodec.encodeWhitelist(snapshot.whitelist)
            prefs[KEY_TODOS] = PreferencesCodec.encodeTodos(snapshot.todos)
            prefs[KEY_AI_PRESETS] = PreferencesCodec.encodePresets(snapshot.presets)
            prefs[KEY_AI_SELECTED_PRESET] = snapshot.selectedPresetId
            prefs[KEY_EMBEDDING_CONFIG] =
                PreferencesCodec.encodeEmbeddingConfig(snapshot.embeddingConfig)
            prefs[KEY_VISION_CONFIG] = PreferencesCodec.encodeVisionConfig(snapshot.visionConfig)
            prefs[KEY_GAZE_CONFIG] = PreferencesCodec.encodeGazeConfig(snapshot.gazeConfig)
            prefs[KEY_AI_PERSONA] =
                PreferencesCodec.encodeAiPersona(snapshot.aiPersona).toString()
            prefs[KEY_USER_PERSONA] =
                PreferencesCodec.encodeUserPersona(snapshot.userPersona).toString()
            prefs[KEY_MEMORIES] = PreferencesCodec.encodeMemories(snapshot.memories)
            prefs[KEY_MEMORY_INDEX] = PreferencesCodec.encodeIndex(snapshot.memoryIndex)
            prefs[KEY_MESSAGES] = PreferencesCodec.encodeMessages(snapshot.messages)
            prefs[KEY_TIMELINE] = PreferencesCodec.encodeTimeline(snapshot.timeline)
            prefs[KEY_APPEARANCE] = PreferencesCodec.encodeAppearance(snapshot.appearance)
            prefs[KEY_SEEDED] = true
            prefs[KEY_SCHEMA_VERSION] = CURRENT_SCHEMA_VERSION
        }
    }.onFailure { throwable ->
        Log.e(TAG, "恢复备份写入失败", throwable)
    }.isSuccess

    // -----------------------------------------------------------------------
    // 人设
    // -----------------------------------------------------------------------

    suspend fun updateAiPersona(persona: AiPersona) {
        dataStore.edit { prefs ->
            prefs[KEY_AI_PERSONA] = PreferencesCodec.encodeAiPersona(persona).toString()
        }
    }

    suspend fun updateUserPersona(persona: UserPersona) {
        dataStore.edit { prefs ->
            prefs[KEY_USER_PERSONA] = PreferencesCodec.encodeUserPersona(persona).toString()
        }
    }

    // -----------------------------------------------------------------------
    // 记忆与向量索引
    // -----------------------------------------------------------------------

    suspend fun upsertMemory(entry: MemoryEntry) {
        dataStore.edit { prefs ->
            val current = PreferencesCodec.decodeMemories(prefs[KEY_MEMORIES])
                .filterNot { it.id == entry.id }
            // 新的排前面，截断时先丢最旧的。
            val next = (listOf(entry) + current).take(MemoryDefaults.MAX_MEMORY_ENTRIES)
            prefs[KEY_MEMORIES] = PreferencesCodec.encodeMemories(next)
        }
    }

    suspend fun removeMemory(id: String) {
        dataStore.edit { prefs ->
            val current = PreferencesCodec.decodeMemories(prefs[KEY_MEMORIES])
            prefs[KEY_MEMORIES] = PreferencesCodec.encodeMemories(current.filterNot { it.id == id })
        }
    }

    /** 一次性写入整份记忆列表。召回计数更新与层级提升都走这里。 */
    suspend fun replaceMemories(memories: List<MemoryEntry>) {
        dataStore.edit { prefs ->
            prefs[KEY_MEMORIES] = PreferencesCodec.encodeMemories(
                memories.take(MemoryDefaults.MAX_MEMORY_ENTRIES),
            )
        }
    }

    /**
     * 整体覆盖向量索引。
     *
     * 永远是「整份替换」而不是逐条 upsert：召回时本来就要对全量做余弦比较，
     * 逐条改写的收益为零；而整份替换能让「淘汰旧文档」变成一个纯函数 ——
     * 在内存里算好最终列表，一次写下去。
     */
    suspend fun replaceIndex(documents: List<IndexedDocument>) {
        dataStore.edit { prefs ->
            // 刻意**不在这里截断**：索引的淘汰策略是「先丢最旧的对话原文、记忆条目
            // 只在超过自己上限时才丢」，那是一个跨字段的判断，实现在
            // `MemoryRepository.enforceIndexLimit` 里。在这一层用 take(n) 简单砍尾巴
            // 会把两边的策略变成两套，而且砍掉的可能是记忆条目。
            prefs[KEY_MEMORY_INDEX] = PreferencesCodec.encodeIndex(documents)
        }
    }

    // -----------------------------------------------------------------------
    // 时间线
    // -----------------------------------------------------------------------

    /**
     * 追加一条时间线事件。
     *
     * 存储顺序是**由新到旧**（新事件放最前），读取时再按 `atMillis` 排序 ——
     * 这样 `take(n)` 天然就是「保留最近的 n 条」，不需要每次写入先排序。
     *
     * 上限用 [TimelineDefaults.MAX_EVENTS]：时间线是高频写入的（每次拦截、每次放行
     * 都写一条），无上限会让每次写盘的成本随时间线性增长，而 DataStore 每次写入
     * 都要重写整份 JSON。
     */
    suspend fun appendTimelineEvent(event: TimelineEvent) {
        dataStore.edit { prefs ->
            val current = PreferencesCodec.decodeTimeline(prefs[KEY_TIMELINE])
            prefs[KEY_TIMELINE] = PreferencesCodec.encodeTimeline(
                (listOf(event) + current).take(TimelineDefaults.MAX_EVENTS),
            )
        }
    }

    /** 整体覆盖时间线。用户主动清空时用。 */
    suspend fun replaceTimelineEvents(events: List<TimelineEvent>) {
        dataStore.edit { prefs ->
            prefs[KEY_TIMELINE] = PreferencesCodec.encodeTimeline(
                events.takeLast(TimelineDefaults.MAX_EVENTS),
            )
        }
    }

    // -----------------------------------------------------------------------
    // 对话
    // -----------------------------------------------------------------------

    /** 改写某条消息的正文。长按消息 -> 编辑。 */
    suspend fun updateMessageText(id: String, text: String) {
        dataStore.edit { prefs ->
            val current = PreferencesCodec.decodeMessages(prefs[KEY_MESSAGES])
            prefs[KEY_MESSAGES] = PreferencesCodec.encodeMessages(
                current.map { if (it.id == id) it.copy(text = text) else it },
            )
        }
    }

    /** 删除某条消息。长按消息 -> 删除。 */
    suspend fun removeMessage(id: String) {
        dataStore.edit { prefs ->
            val current = PreferencesCodec.decodeMessages(prefs[KEY_MESSAGES])
            prefs[KEY_MESSAGES] = PreferencesCodec.encodeMessages(
                current.filterNot { it.id == id },
            )
        }
    }

    suspend fun appendMessage(message: ChatMessage) {
        dataStore.edit { prefs ->
            val current = PreferencesCodec.decodeMessages(prefs[KEY_MESSAGES])
            prefs[KEY_MESSAGES] = PreferencesCodec.encodeMessages(
                (current + message).takeLast(MAX_STORED_MESSAGES),
            )
        }
    }

    suspend fun replaceMessages(messages: List<ChatMessage>) {
        dataStore.edit { prefs ->
            prefs[KEY_MESSAGES] = PreferencesCodec.encodeMessages(
                messages.takeLast(MAX_STORED_MESSAGES),
            )
        }
    }

    // -----------------------------------------------------------------------
    // Preferences <-> 领域模型
    // -----------------------------------------------------------------------

    private fun Preferences.toAppPreferences(): AppPreferences = AppPreferences(
        whitelist = PreferencesCodec.decodeWhitelist(this[KEY_WHITELIST]),
        todos = PreferencesCodec.decodeTodos(this[KEY_TODOS]),
        presets = PreferencesCodec.decodePresets(this[KEY_AI_PRESETS]),
        selectedPresetId = this[KEY_AI_SELECTED_PRESET].orEmpty(),
        embeddingConfig = PreferencesCodec.decodeEmbeddingConfig(this[KEY_EMBEDDING_CONFIG]),
        visionConfig = PreferencesCodec.decodeVisionConfig(this[KEY_VISION_CONFIG]),
        gazeConfig = PreferencesCodec.decodeGazeConfig(this[KEY_GAZE_CONFIG]),
        aiPersona = PreferencesCodec.decodeAiPersona(this[KEY_AI_PERSONA]),
        userPersona = PreferencesCodec.decodeUserPersona(this[KEY_USER_PERSONA]),
        memories = PreferencesCodec.decodeMemories(this[KEY_MEMORIES]),
        memoryIndex = PreferencesCodec.decodeIndex(this[KEY_MEMORY_INDEX]),
        messages = PreferencesCodec.decodeMessages(this[KEY_MESSAGES]),
        timeline = PreferencesCodec.decodeTimeline(this[KEY_TIMELINE]),
        appearance = PreferencesCodec.decodeAppearance(this[KEY_APPEARANCE]),
        isSeeded = this[KEY_SEEDED] ?: false,
        schemaVersion = this[KEY_SCHEMA_VERSION] ?: 0,
    )

    companion object {
        private const val TAG = "AppPreferences"

        /**
         * 数据格式版本。
         *
         * 现在只有 1。将来字段结构变了（例如给 TodoItem 加了优先级），就在这里加一，
         * 并在 [toAppPreferences] 里按版本号做迁移 —— 有了这个字段，迁移才有依据。
         */
        const val CURRENT_SCHEMA_VERSION = 1

        /**
         * DataStore 文件名。
         *
         * `internal` 而不是 `private`：文件末尾那个顶层 `preferencesDataStore` 委托
         * 需要引用它，而 companion 里的 private 成员对同文件的顶层声明不可见。
         */
        internal const val DATASTORE_NAME = "focus_supervisor_preferences"

        /**
         * 对话持久化的条数上限。
         *
         * 2000 条大致相当于几个月的日常使用。超出后丢最旧的 —— 丢掉的原文仍然可能
         * 留在向量索引里（索引有自己的上限与淘汰策略），所以「久远的事」不会因为
         * 这里截断就彻底失忆。
         */
        const val MAX_STORED_MESSAGES = 2000

        private val KEY_WHITELIST = stringPreferencesKey("whitelist_json")
        private val KEY_TODOS = stringPreferencesKey("todos_json")
        private val KEY_AI_PRESETS = stringPreferencesKey("ai_presets_json")
        private val KEY_AI_SELECTED_PRESET = stringPreferencesKey("ai_selected_preset_id")
        private val KEY_EMBEDDING_CONFIG = stringPreferencesKey("embedding_config_json")
        private val KEY_VISION_CONFIG = stringPreferencesKey("vision_config_json")
        private val KEY_GAZE_CONFIG = stringPreferencesKey("gaze_config_json")
        private val KEY_AI_PERSONA = stringPreferencesKey("ai_persona_json")
        private val KEY_USER_PERSONA = stringPreferencesKey("user_persona_json")
        private val KEY_MEMORIES = stringPreferencesKey("memories_json")
        private val KEY_MEMORY_INDEX = stringPreferencesKey("memory_index_json")
        private val KEY_MESSAGES = stringPreferencesKey("messages_json")
        private val KEY_TIMELINE = stringPreferencesKey("timeline_json")
        private val KEY_APPEARANCE = stringPreferencesKey("appearance_json")
        private val KEY_SEEDED = booleanPreferencesKey("seeded")
        private val KEY_SCHEMA_VERSION = intPreferencesKey("schema_version")
    }
}

/**
 * DataStore 里的一份完整配置快照。
 *
 * 这是一个**传输对象**：职责只是把各个 key 聚成一个整体，好让仓库层一次拿到全部内容。
 * 它不参与任何业务判断 —— 比如「豁免是否过期」的判断在 `WhitelistApp.isActiveAt` 里。
 *
 * @param memoryIndex 向量索引。与 [memories] 分开存：索引包含对话原文，条数比记忆
 *        多一个量级，而且淘汰策略完全不同。
 */
data class AppPreferences(
    val whitelist: List<WhitelistApp> = emptyList(),
    val todos: List<TodoItem> = emptyList(),
    val presets: List<AiPreset> = emptyList(),
    val selectedPresetId: String = "",
    val embeddingConfig: EmbeddingConfig = EmbeddingConfig(),
    val visionConfig: VisionConfig = VisionConfig(),
    val gazeConfig: GazeConfig = GazeConfig(),
    val aiPersona: AiPersona = AiPersona(),
    val userPersona: UserPersona = UserPersona(),
    val memories: List<MemoryEntry> = emptyList(),
    val memoryIndex: List<IndexedDocument> = emptyList(),
    val messages: List<ChatMessage> = emptyList(),
    val timeline: List<TimelineEvent> = emptyList(),
    val appearance: ChatAppearance = ChatAppearance(),
    val isSeeded: Boolean = false,
    val schemaVersion: Int = 0,
)

/**
 * 进程内唯一的 DataStore 实例。
 *
 * `preferencesDataStore` 这个委托内部保证「同一个 name 在一个进程里只有一个实例」，
 * 因此必须放在顶层：放进类里的话，每个类实例都会触发一次创建，DataStore 会
 * 直接抛异常拒绝第二个实例。
 */
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = AppPreferencesDataSource.DATASTORE_NAME,
)
