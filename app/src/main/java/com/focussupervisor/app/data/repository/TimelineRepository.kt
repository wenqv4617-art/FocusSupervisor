package com.focussupervisor.app.data.repository

import android.util.Log
import com.focussupervisor.app.data.datastore.AppPreferencesDataSource
import com.focussupervisor.app.domain.model.TimelineEvent
import com.focussupervisor.app.domain.model.TimelineKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * 数据层：监督时间线仓库
 *
 * ===========================================================================
 * 为什么 [record] 不是 suspend
 * ===========================================================================
 * 它最重要的一类调用方是**无障碍服务**：`onAccessibilityEvent` 在主线程上跑，
 * 而拦截判定必须当场完成，不能为了记一条日志去挂起。
 *
 * 所以 [record] 是「投递即返回」：构造好事件就交给进程级作用域去落盘，调用方
 * 不等结果。这不是偷懒 —— 记日志本来就不该成为业务路径上的阻塞点，
 * 更不该因为写盘失败而让「拦截」这个真正的动作失败。
 *
 * 唯一的代价是**顺序**：并发投递时落盘顺序可能与发生顺序不一致。因此读取时
 * 按 `atMillis` 排序（见下方 init 里的 `sortedBy`），让时间线始终按时间呈现。
 *
 * ===========================================================================
 * 失败处理
 * ===========================================================================
 * 写盘异常在这里被吞掉并记日志。理由和不抛给调用方是一样的：时间线是**旁路证据**，
 * 它坏掉不该让主流程（拦截 / 放行 / 记待办）跟着失败。
 */
interface TimelineRepository {

    /** 全部事件，按时间**正序**。 */
    val events: StateFlow<List<TimelineEvent>>

    /**
     * 记一条事件。**立即返回**，落盘在后台进行。
     *
     * @param atMillis 事件时间。默认取当前时刻；补记历史事件时才需要显式传。
     */
    fun record(
        kind: TimelineKind,
        title: String,
        detail: String = "",
        atMillis: Long = System.currentTimeMillis(),
    )

    /** 清空时间线。用户主动「重新开始」时用。 */
    suspend fun clear(): Boolean
}

/**
 * [TimelineRepository] 的 DataStore 实现。
 */
class DataStoreTimelineRepository(
    private val preferences: AppPreferencesDataSource,
    private val scope: CoroutineScope,
) : TimelineRepository {

    private val _events = MutableStateFlow<List<TimelineEvent>>(emptyList())
    override val events: StateFlow<List<TimelineEvent>> = _events.asStateFlow()

    init {
        scope.launch {
            preferences.preferences.collect { prefs ->
                // 按时间排序而不是直接用存储顺序：record 是并发投递的，
                // 落盘顺序不保证等于发生顺序（见接口注释）。
                _events.value = prefs.timeline.sortedBy { it.atMillis }
            }
        }
    }

    override fun record(
        kind: TimelineKind,
        title: String,
        detail: String,
        atMillis: Long,
    ) {
        val event = TimelineEvent(
            id = "tl-${UUID.randomUUID()}",
            atMillis = atMillis,
            kind = kind,
            // 截断在这里做，而不是指望每个调用方都记得：标题和补充信息会被拼进
            // 提示词，放任长文本进来等于让某一条日志挤掉整个上下文。
            title = title.trim().take(MAX_TITLE_LENGTH),
            detail = detail.trim().take(MAX_DETAIL_LENGTH),
        )

        scope.launch {
            try {
                preferences.appendTimelineEvent(event)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.e(TAG, "记录时间线失败（${kind.name}）", t)
            }
        }
    }

    override suspend fun clear(): Boolean = try {
        preferences.replaceTimelineEvents(emptyList())
        true
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        Log.e(TAG, "清空时间线失败", t)
        false
    }

    private companion object {
        const val TAG = "TimelineRepository"
        const val MAX_TITLE_LENGTH = 80
        const val MAX_DETAIL_LENGTH = 160
    }
}
