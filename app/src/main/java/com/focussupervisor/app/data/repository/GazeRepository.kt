package com.focussupervisor.app.data.repository

import android.util.Log
import com.focussupervisor.app.data.datastore.AppPreferencesDataSource
import com.focussupervisor.app.domain.model.GazeConfig
import com.focussupervisor.app.domain.model.GazeState
import com.focussupervisor.app.domain.model.VisionStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 数据层：注视监控仓库
 *
 * ===========================================================================
 * 它同时装两样东西，这是刻意的
 * ===========================================================================
 * ```
 *   config           持久化：开关、抽帧间隔、容差、要不要上传截图
 *   state            运行期：此刻判定成什么状态
 *   lastDescription  运行期：视觉模型最近一次说了什么
 *   lastError        运行期：最近一次失败的原因
 * ```
 * 前一项要落盘（重启后用户的设置必须还在），后三项**绝不该落盘** ——
 * 「三秒前判定为正在注视」在重启后毫无意义，显示出来只会误导。
 *
 * 放在同一个类里，是因为它们的读法完全一样（都是「现在是什么情况」），
 * 而这个应用里已经有五个仓库了，再为三个内存字段开一个类不划算。
 * 但两种字段的生命周期在类型上是分开的：只有 [updateConfig] 会写磁盘。
 */
interface GazeRepository {

    /**
     * 持久化的配置。
     *
     * **可能是 null**：DataStore 的第一次读取是异步的，在那之前「用户到底是开是关」
     * 是未知的，而不是「关」。这个区别很要命 —— 前台服务如果拿一个假的默认值当真，
     * 就会在刚启动的瞬间认为自己被关闭了，然后立刻自杀（表现为「打开开关后一闪就关」）。
     * 所以这里用 null 表示未知，调用方必须显式处理，不许猜。
     */
    val config: StateFlow<GazeConfig?>

    /** 运行期状态（含连续/累计注视时长）。进程重启即回到 [GazeState.OFF]。 */
    val status: StateFlow<VisionStatus>

    /** 最近一次视觉描述；没有或已被清空时为 null。 */
    val lastDescription: StateFlow<String?>

    /** 最近一次错误原因（中文，可直接显示）；正常时为 null。 */
    val lastError: StateFlow<String?>

    /** 保存配置。@return 是否写入成功。 */
    suspend fun updateConfig(config: GazeConfig): Boolean

    /** 由前台服务发布当前状态。 */
    fun publishStatus(status: VisionStatus)

    /** 由前台服务发布视觉描述。 */
    fun publishDescription(text: String?)

    /** 由前台服务发布错误。 */
    fun publishError(message: String?)
}

/** [GazeRepository] 的 DataStore 实现（配置文件落盘，运行期状态留在内存）。 */
class DataStoreGazeRepository(
    private val preferences: AppPreferencesDataSource,
    scope: CoroutineScope,
) : GazeRepository {

    private val _config = MutableStateFlow<GazeConfig?>(null)
    override val config: StateFlow<GazeConfig?> = _config.asStateFlow()

    private val _status = MutableStateFlow(VisionStatus())
    override val status: StateFlow<VisionStatus> = _status.asStateFlow()

    private val _lastDescription = MutableStateFlow<String?>(null)
    override val lastDescription: StateFlow<String?> = _lastDescription.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    override val lastError: StateFlow<String?> = _lastError.asStateFlow()

    init {
        scope.launch {
            preferences.preferences.collect { prefs ->
                _config.value = prefs.gazeConfig
            }
        }
    }

    override suspend fun updateConfig(config: GazeConfig): Boolean = try {
        preferences.updateGazeConfig(config)
        true
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        Log.e(TAG, "保存注视监控配置失败", t)
        false
    }

    override fun publishStatus(status: VisionStatus) {
        _status.value = status
        // 一进入非错误状态就把上一次的错误清掉：界面上的红字必须反映**当前**情况，
        // 否则用户会以为刚刚又失败了一次。
        if (status.state != GazeState.ERROR) _lastError.value = null
    }

    override fun publishDescription(text: String?) {
        _lastDescription.value = text
    }

    override fun publishError(message: String?) {
        _lastError.value = message
    }

    private companion object {
        const val TAG = "GazeRepository"
    }
}
