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
import com.focussupervisor.app.domain.model.AiPreset
import com.focussupervisor.app.domain.model.TodoItem
import com.focussupervisor.app.domain.model.WhitelistApp
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * Preferences DataStore 的进程内唯一入口。
 *
 * ===========================================================================
 * 为什么用 Preferences DataStore 而不是 Room
 * ===========================================================================
 * 要存的东西一共三类，每类的规模都在几十条以内，而且**从来不需要按条件查询** ——
 * 永远是「整份读出来、整份写回去」。这正是 Preferences DataStore 的适用场景。
 * Room 会带来 schema、DAO、迁移文件和一整套编译期代码生成，在这个数据量下
 * 换不到任何实际收益。
 *
 * ===========================================================================
 * 序列化：为什么用 org.json 而不是 kotlinx.serialization
 * ===========================================================================
 * 只有三个列表要编码，字段总数不到二十个。引入 kotlinx.serialization 需要额外加
 * 一个 Gradle 插件、对齐一套版本、再给每个类写 `@Serializable`，而换来的只是
 * 少写二十行 `put`/`optString`。org.json 是 Android 平台自带 API，零依赖。
 *
 * 代价要说清楚：org.json 的解析在 JVM 单元测试里不可用（android.jar 的桩会抛
 * NotImplementedError）。如果将来要给这层写单测，就应该把它换成 kotlinx.serialization
 * 或 Moshi。**这也是这些编解码函数全部写成纯函数、只依赖字符串的原因** ——
 * 换实现时不需要动调用方。
 *
 * ===========================================================================
 * 关于 API Key 的存储
 * ===========================================================================
 * 现在是把 Key 明文写进 DataStore。这是一个**已知的、经过权衡的**选择：
 *  - DataStore 文件位于应用私有目录，其他应用读不到（除非设备已 root）；
 *  - 更安全的做法是 EncryptedSharedPreferences / Jetpack Security，但它依赖
 *    Android Keystore，而 Keystore 在部分定制 ROM 上会随机失效 —— 失效的后果是
 *    「用户的 Key 悄悄读不出来了」，对监督类应用来说比明文更糟；
 *  - 折中方案（首次使用口令派生密钥加密）需要用户记一个口令，与「打开就能用」
 *    的产品目标冲突。
 * 因此这里选明文，并在「AI 配置」界面上明确告知用户 Key 存在本机。
 * 真要处理高价值 Key，应该由用户自己用一次性 Key。
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
    // 写入
    // -----------------------------------------------------------------------
    //
    // 全部是**原子读改写**：在 `edit` 块里先解码出当前值、改完再编码回去。
    // 这一点很重要 —— 如果上层先读内存、算好新列表、再整体写回，两个并发写就会
    // 互相丢更新（后写的那个基于过期快照）。放进 edit 块里由 DataStore 串行化，
    // 就不存在这个问题。
    //
    // 唯一的「整体写入」是首次启动的 seedIfNeeded，它本身也是一个 edit 事务。

    /**
     * 插入或覆盖一条**临时**豁免。
     *
     * 同一个包名的旧临时条目会被顶掉；常驻条目（expiresAtMillis == null）原样保留，
     * 不会被一条限时豁免覆盖成会过期的。
     */
    suspend fun upsertTemporaryWhitelist(entry: WhitelistApp) {
        dataStore.edit { prefs ->
            val current = decodeWhitelist(prefs[KEY_WHITELIST])
            val kept = current.filterNot {
                it.packageName == entry.packageName && !it.isPermanent
            }
            prefs[KEY_WHITELIST] = encodeWhitelist(kept + entry)
        }
    }

    /** 移除某个包名的全部临时豁免。常驻条目不受影响。 */
    suspend fun removeTemporaryWhitelist(packageName: String) {
        dataStore.edit { prefs ->
            val current = decodeWhitelist(prefs[KEY_WHITELIST])
            prefs[KEY_WHITELIST] = encodeWhitelist(
                current.filterNot { it.packageName == packageName && !it.isPermanent },
            )
        }
    }

    /** 清掉所有已经过期的临时豁免。返回是否真的删掉了东西，调用方据此决定要不要打日志。 */
    suspend fun removeExpiredWhitelist(nowMillis: Long): Boolean {
        var removed = false
        dataStore.edit { prefs ->
            val current = decodeWhitelist(prefs[KEY_WHITELIST])
            val alive = current.filter { it.isActiveAt(nowMillis) }
            if (alive.size != current.size) {
                removed = true
                prefs[KEY_WHITELIST] = encodeWhitelist(alive)
            }
        }
        return removed
    }

    /** 插入或覆盖一条待办。 */
    suspend fun upsertTodo(todo: TodoItem) {
        dataStore.edit { prefs ->
            val current = decodeTodos(prefs[KEY_TODOS]).filterNot { it.id == todo.id }
            prefs[KEY_TODOS] = encodeTodos((current + todo).sortedBy { it.plannedAtMillis })
        }
    }

    /** 勾选 / 取消勾选一条待办。 */
    suspend fun setTodoDone(id: String, isDone: Boolean) {
        dataStore.edit { prefs ->
            val current = decodeTodos(prefs[KEY_TODOS])
            prefs[KEY_TODOS] = encodeTodos(
                current.map { if (it.id == id) it.copy(isDone = isDone) else it },
            )
        }
    }

    /** 插入或覆盖一个 AI 预设。 */
    suspend fun upsertPreset(preset: AiPreset) {
        dataStore.edit { prefs ->
            val current = decodePresets(prefs[KEY_AI_PRESETS]).filterNot { it.id == preset.id }
            prefs[KEY_AI_PRESETS] = encodePresets(current + preset)
        }
    }

    /** 删除一个 AI 预设。 */
    suspend fun removePreset(id: String) {
        dataStore.edit { prefs ->
            val current = decodePresets(prefs[KEY_AI_PRESETS])
            prefs[KEY_AI_PRESETS] = encodePresets(current.filterNot { it.id == id })
        }
    }

    /** 记录当前选中的 AI 预设。 */
    suspend fun updateSelectedPreset(presetId: String) {
        dataStore.edit { prefs -> prefs[KEY_AI_SELECTED_PRESET] = presetId }
    }

    /**
     * 首次启动时把内置数据一次性灌进去。已经在同一个 `edit` 事务里完成，
     * 因此不存在「白名单灌了、预置没灌」这种半截状态。
     *
     * 为什么把三份数据的初始化收在一个方法里、而不是让两个仓库各自判断「我该不该
     * 初始化」：那样会有一个隐蔽的竞态 —— 仓库 A 先跑完并置上了 seeded 标记，
     * 仓库 B 随后读到 `seeded == true` 就跳过自己的初始化，于是它的数据永远是空的。
     * 用一个共享的、原子的事务来回答「是不是首次启动」，这个坑就不存在。
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

            prefs[KEY_WHITELIST] = encodeWhitelist(builtInWhitelist)
            prefs[KEY_AI_PRESETS] = encodePresets(defaultPresets)
            prefs[KEY_AI_SELECTED_PRESET] = defaultSelectedPresetId
            prefs[KEY_SEEDED] = true
            prefs[KEY_SCHEMA_VERSION] = CURRENT_SCHEMA_VERSION
            seeded = true
        }
        return seeded
    }

    // -----------------------------------------------------------------------
    // Preferences <-> 领域模型
    // -----------------------------------------------------------------------

    private fun Preferences.toAppPreferences(): AppPreferences = AppPreferences(
        whitelist = decodeWhitelist(this[KEY_WHITELIST]),
        todos = decodeTodos(this[KEY_TODOS]),
        presets = decodePresets(this[KEY_AI_PRESETS]),
        selectedPresetId = this[KEY_AI_SELECTED_PRESET].orEmpty(),
        isSeeded = this[KEY_SEEDED] ?: false,
        schemaVersion = this[KEY_SCHEMA_VERSION] ?: 0,
    )

    companion object {
        private const val TAG = "AppPreferences"

        /**
         * 数据格式版本。
         *
         * 现在只有 1。将来字段结构变了（例如给 TodoItem 加了优先级），就在这里加一，
         * 并在 [toAppPreferences] 里按版本号做迁移 —— 有了这个字段，迁移才有依据；
         * 没有它，只能靠「猜用户存的是哪一版」。
         */
        const val CURRENT_SCHEMA_VERSION = 1

        /**
         * DataStore 文件名。
         *
         * `internal` 而不是 `private`：文件末尾那个顶层 `preferencesDataStore` 委托
         * 需要引用它，而**companion 里的 private 成员对同文件的顶层声明不可见**。
         */
        internal const val DATASTORE_NAME = "focus_supervisor_preferences"

        private val KEY_WHITELIST = stringPreferencesKey("whitelist_json")
        private val KEY_TODOS = stringPreferencesKey("todos_json")
        private val KEY_AI_PRESETS = stringPreferencesKey("ai_presets_json")
        private val KEY_AI_SELECTED_PRESET = stringPreferencesKey("ai_selected_preset_id")
        private val KEY_SEEDED = booleanPreferencesKey("seeded")
        private val KEY_SCHEMA_VERSION = intPreferencesKey("schema_version")

        // -- 白名单 ---------------------------------------------------------

        internal fun encodeWhitelist(whitelist: List<WhitelistApp>): String {
            val array = JSONArray()
            whitelist.forEach { entry ->
                array.put(
                    JSONObject().apply {
                        put(FIELD_PACKAGE_NAME, entry.packageName)
                        put(FIELD_APP_NAME, entry.appName)
                        // null 表示常驻。用 JSONObject.NULL 而不是省略字段，
                        // 这样解码端只需要判断 isNull，不用区分「字段不存在」。
                        put(FIELD_EXPIRES_AT, entry.expiresAtMillis ?: JSONObject.NULL)
                        put(FIELD_REASON, entry.reason)
                    },
                )
            }
            return array.toString()
        }

        internal fun decodeWhitelist(json: String?): List<WhitelistApp> {
            if (json.isNullOrBlank()) return emptyList()
            return runCatching {
                val array = JSONArray(json)
                (0 until array.length()).mapNotNull { index ->
                    val obj = array.optJSONObject(index) ?: return@mapNotNull null
                    val packageName = obj.optString(FIELD_PACKAGE_NAME).takeIf { it.isNotBlank() }
                        ?: return@mapNotNull null
                    WhitelistApp(
                        packageName = packageName,
                        appName = obj.optString(FIELD_APP_NAME).takeIf { it.isNotBlank() }
                            ?: packageName,
                        expiresAtMillis = if (obj.isNull(FIELD_EXPIRES_AT)) {
                            null
                        } else {
                            obj.optLong(FIELD_EXPIRES_AT).takeIf { it > 0L }
                        },
                        reason = obj.optString(FIELD_REASON),
                    )
                }
            }.getOrElse { throwable ->
                // 单条脏数据不能让整份配置作废，更不能让应用起不来。
                Log.w(TAG, "白名单解析失败，已丢弃这份数据", throwable)
                emptyList()
            }
        }

        // -- 待办 -----------------------------------------------------------

        internal fun encodeTodos(todos: List<TodoItem>): String {
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

        internal fun decodeTodos(json: String?): List<TodoItem> {
            if (json.isNullOrBlank()) return emptyList()
            return runCatching {
                val array = JSONArray(json)
                (0 until array.length()).mapNotNull { index ->
                    val obj = array.optJSONObject(index) ?: return@mapNotNull null
                    val id = obj.optString(FIELD_ID).takeIf { it.isNotBlank() }
                        ?: return@mapNotNull null
                    TodoItem(
                        id = id,
                        title = obj.optString(FIELD_TITLE),
                        plannedAtMillis = obj.optLong(FIELD_PLANNED_AT),
                        isDone = obj.optBoolean(FIELD_IS_DONE, false),
                    )
                }
            }.getOrElse { throwable ->
                Log.w(TAG, "待办解析失败，已丢弃这份数据", throwable)
                emptyList()
            }
        }

        // -- AI 预设 ---------------------------------------------------------

        internal fun encodePresets(presets: List<AiPreset>): String {
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
                    },
                )
            }
            return array.toString()
        }

        internal fun decodePresets(json: String?): List<AiPreset> {
            if (json.isNullOrBlank()) return emptyList()
            return runCatching {
                val array = JSONArray(json)
                (0 until array.length()).mapNotNull { index ->
                    val obj = array.optJSONObject(index) ?: return@mapNotNull null
                    val id = obj.optString(FIELD_ID).takeIf { it.isNotBlank() }
                        ?: return@mapNotNull null
                    AiPreset(
                        id = id,
                        name = obj.optString(FIELD_NAME).takeIf { it.isNotBlank() } ?: id,
                        config = com.focussupervisor.app.domain.model.AiConfig(
                            baseUrl = obj.optString(FIELD_BASE_URL),
                            apiKey = obj.optString(FIELD_API_KEY),
                            model = obj.optString(FIELD_MODEL),
                            temperature = obj.optDouble(
                                FIELD_TEMPERATURE,
                                com.focussupervisor.app.domain.model.AiConfig.DEFAULT_TEMPERATURE.toDouble(),
                            ).toFloat(),
                        ),
                    )
                }
            }.getOrElse { throwable ->
                Log.w(TAG, "AI 预设解析失败，已丢弃这份数据", throwable)
                emptyList()
            }
        }

        // -- 字段名 ---------------------------------------------------------
        //
        // 全部集中在这里：这些字符串一旦写进用户的磁盘就不能再改（改了等于读不出
        // 旧数据），集中定义能让人一眼看出「这些是持久化契约，不是普通常量」。

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
    }
}

/**
 * DataStore 里的一份完整配置快照。
 *
 * 这是一个**传输对象**：它的职责只是把 Preferences 里的各个 key 聚成一个整体，
 * 好让仓库层一次拿到全部内容。它不参与任何业务判断 —— 比如「豁免是否过期」的
 * 判断在 [WhitelistApp.isActiveAt] 里，不在这里。
 *
 * @param selectedPresetId 当前选中的 AI 预设 id；空串表示还没选过。
 * @param isSeeded 是否已经写入过内置数据。用它而不是「列表是否为空」来判断首次启动：
 *        用户完全可能手动清空白名单，那不代表要重新灌一遍内置数据。
 */
data class AppPreferences(
    val whitelist: List<WhitelistApp> = emptyList(),
    val todos: List<TodoItem> = emptyList(),
    val presets: List<AiPreset> = emptyList(),
    val selectedPresetId: String = "",
    val isSeeded: Boolean = false,
    val schemaVersion: Int = 0,
)

/**
 * 进程内唯一的 DataStore 实例。
 *
 * `preferencesDataStore` 这个委托内部保证「同一个 name 在一个进程里只有一个实例」，
 * 因此必须放在**顶层**：放进类里的话，每个类实例都会触发一次创建，DataStore 会
 * 直接抛异常拒绝第二个实例。
 */
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = AppPreferencesDataSource.DATASTORE_NAME,
)
