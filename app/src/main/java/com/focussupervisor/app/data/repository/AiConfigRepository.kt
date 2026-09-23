package com.focussupervisor.app.data.repository

import android.util.Log
import com.focussupervisor.app.data.datastore.AppPreferencesDataSource
import com.focussupervisor.app.domain.model.AiConfig
import com.focussupervisor.app.domain.model.AiPreset
import com.focussupervisor.app.domain.model.AiPresetDefaults
import com.focussupervisor.app.domain.model.EmbeddingConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * 数据层：AI 端点配置仓库
 *
 * ===========================================================================
 * 为什么和 [AppPolicyRepository] 分成两个接口
 * ===========================================================================
 * 它们共用同一个 DataStore 文件，但**没有任何语义交集**：
 *  - 策略仓库回答「这个应用该不该拦」，被无障碍服务在主线程高频调用；
 *  - 配置仓库回答「连哪个模型」，只在用户打开配置面板时被读写。
 *
 * 合成一个接口的后果是：无障碍服务依赖的那个接口里，会躺着四个它永远不碰的
 * AI 字段。将来要给策略做单元测试，就得连带把 AI 配置也 mock 掉。
 * 拆开之后两边各自独立演进，唯一的耦合点是它们共享同一个 DataStore 事务。
 *
 * 对内实现上，[DataStoreAiConfigRepository] 与 `DataStoreAppPolicyRepository` 一样
 * 遵循「内存快照 + Flow 回环」：界面读 `StateFlow`，不直接碰 DataStore。
 */
interface AiConfigRepository {

    /** 全部预设，含三个内置预置与用户自建的。 */
    val presets: StateFlow<List<AiPreset>>

    /** 当前选中的预设 id。 */
    val selectedPresetId: StateFlow<String>

    /** 当前选中预设展开后的配置。找不到选中项时回退到第一个预设。 */
    val activeConfig: StateFlow<AiConfig>

    /** 同步取当前配置。给不方便收集 Flow 的地方（例如日志、状态摘要）用。 */
    fun activeConfigNow(): AiConfig

    /**
     * 向量模型配置。**与对话配置完全独立**，理由见 [EmbeddingConfig] 的注释。
     */
    val embeddingConfig: StateFlow<EmbeddingConfig>

    /** 同步取向量配置。 */
    fun embeddingConfigNow(): EmbeddingConfig

    /** 保存向量模型配置。@return 是否写入成功。 */
    suspend fun saveEmbeddingConfig(config: EmbeddingConfig): Boolean

    /** 插入或覆盖一个预设。@return 是否写入成功。 */
    suspend fun savePreset(preset: AiPreset): Boolean

    /** 切换选中的预设。@return 是否写入成功。 */
    suspend fun selectPreset(presetId: String): Boolean

    /**
     * 删除一个预设。
     *
     * 内置预置（[AiPresetDefaults.BUILT_IN_IDS]）不允许删除 —— 删光之后用户就没有
     * 可以「恢复默认」的锚点了，而且这三个是唯一保证 URL 正确的配置。
     *
     * @return 是否真的删除了。
     */
    suspend fun deletePreset(presetId: String): Boolean

    /** 新建一个用户预设并立刻选中它。@return 新建的预设；写入失败时返回 null。 */
    suspend fun createPreset(name: String, config: AiConfig): AiPreset?
}

/**
 * [AiConfigRepository] 的 DataStore 实现。
 *
 * @param preferences DataStore 访问入口
 * @param scope 进程级协程作用域，与策略仓库共用
 */
class DataStoreAiConfigRepository(
    private val preferences: AppPreferencesDataSource,
    scope: CoroutineScope,
) : AiConfigRepository {

    private val _presets = MutableStateFlow<List<AiPreset>>(emptyList())
    override val presets: StateFlow<List<AiPreset>> = _presets.asStateFlow()

    private val _selectedPresetId = MutableStateFlow("")
    override val selectedPresetId: StateFlow<String> = _selectedPresetId.asStateFlow()

    /**
     * 派生状态：预设列表 × 选中 id。
     *
     * 用 `stateIn(..., Eagerly, ...)` 把它变成热的 StateFlow，好处是
     * [activeConfigNow] 可以直接读 `.value` —— 这正是不想为了一个同步读而
     * 到处传 Flow 的原因。Eagerly 而不是 WhileSubscribed：这个组合本身极轻
     * （两个内存列表的查找），没必要为了省这点开销引入「订阅者归零就停算」的复杂度。
     */
    override val activeConfig: StateFlow<AiConfig> =
        combine(_presets, _selectedPresetId) { presets, selectedId ->
            resolveActive(presets, selectedId)
        }.stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = EMPTY_CONFIG,
        )

    private val _embeddingConfig = MutableStateFlow(EmbeddingConfig())
    override val embeddingConfig: StateFlow<EmbeddingConfig> = _embeddingConfig.asStateFlow()

    init {
        scope.launch {
            preferences.preferences.collect { prefs ->
                _presets.value = prefs.presets
                _selectedPresetId.value = prefs.selectedPresetId
                _embeddingConfig.value = prefs.embeddingConfig
            }
        }
    }

    override fun activeConfigNow(): AiConfig = activeConfig.value

    override fun embeddingConfigNow(): EmbeddingConfig = _embeddingConfig.value

    override suspend fun saveEmbeddingConfig(config: EmbeddingConfig): Boolean =
        writeOrLog("保存向量模型配置") { preferences.updateEmbeddingConfig(config) }

    override suspend fun savePreset(preset: AiPreset): Boolean =
        writeOrLog("保存预设") { preferences.upsertPreset(preset) }

    override suspend fun selectPreset(presetId: String): Boolean =
        writeOrLog("切换预设") { preferences.updateSelectedPreset(presetId) }

    override suspend fun deletePreset(presetId: String): Boolean {
        if (presetId in AiPresetDefaults.BUILT_IN_IDS) return false

        val removed = writeOrLog("删除预设") { preferences.removePreset(presetId) }
        if (!removed) return false

        // 删掉的正好是当前选中项 → 回落到第一个可用预设，避免界面停在空配置上。
        if (_selectedPresetId.value == presetId) {
            val fallback = _presets.value.firstOrNull { it.id != presetId }?.id.orEmpty()
            writeOrLog("回退选中预设") { preferences.updateSelectedPreset(fallback) }
        }
        return true
    }

    override suspend fun createPreset(name: String, config: AiConfig): AiPreset? {
        val preset = AiPreset(
            id = "${AiPresetDefaults.ID_CUSTOM_PREFIX}-${UUID.randomUUID()}",
            name = name.ifBlank { AiPresetDefaults.CUSTOM_NAME },
            config = config,
        )
        if (!writeOrLog("新建预设") { preferences.upsertPreset(preset) }) return null
        writeOrLog("选中新建预设") { preferences.updateSelectedPreset(preset.id) }
        return preset
    }

    private fun resolveActive(presets: List<AiPreset>, selectedId: String): AiConfig =
        presets.firstOrNull { it.id == selectedId }?.config
            ?: presets.firstOrNull()?.config
            ?: EMPTY_CONFIG

    /**
     * 执行一次写操作，失败只记日志、不抛异常。
     *
     * 返回 Boolean 而不是把异常抛给 ViewModel：ViewModel 的调用点都在
     * `viewModelScope.launch` 里，抛出去就是一个崩溃对话框。而配置写失败是个
     * 可以通过界面提示解决的问题，不值得让应用挂掉。
     */
    private suspend fun writeOrLog(action: String, block: suspend () -> Unit): Boolean = try {
        block()
        true
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        Log.e(TAG, "$action 失败", t)
        false
    }

    private companion object {
        const val TAG = "AiConfigRepository"

        val EMPTY_CONFIG = AiConfig(
            baseUrl = "",
            apiKey = "",
            model = "",
            temperature = AiConfig.DEFAULT_TEMPERATURE,
        )
    }
}
