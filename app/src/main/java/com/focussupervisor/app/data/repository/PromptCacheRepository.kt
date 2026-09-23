package com.focussupervisor.app.data.repository

import com.focussupervisor.app.core.network.PromptCacheStats
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 数据层：最近一次请求的**上下文缓存命中情况**
 *
 * ===========================================================================
 * 它解决的是「看不见」的问题
 * ===========================================================================
 * DeepSeek 的缓存按前缀命中，而命中率完全取决于提示词里内容的摆放顺序。
 * 这是个**极其脆弱**的性质：往 system 里加一个「当前时间」，命中率立刻从 90% 掉到 0，
 * 而界面上不会有任何变化，用户只会觉得「这个月怎么贵了」。
 *
 * 所以把最近一次的命中数摆到界面上。有这么一个数字盯着，后续任何人改动提示词
 * 都能立刻看出代价。
 *
 * ===========================================================================
 * 为什么只存内存、不落盘
 * ===========================================================================
 * 它是「刚才那一次怎么样」，不是配置也不是历史。重启后显示上一次会话的数字没有
 * 任何意义，反而会让人以为刚才那次命中了。所以是一个纯内存的 StateFlow。
 */
interface PromptCacheRepository {

    /** 最近一次请求的命中情况；端点没上报 usage 时为 null。 */
    val last: StateFlow<PromptCacheStats?>

    /** 记录一次请求的结果。 */
    fun record(stats: PromptCacheStats)

    /** 清空（例如用户改了配置，之前的数字不再有参考价值）。 */
    fun clear()
}

/** [PromptCacheRepository] 的内存实现。 */
class InMemoryPromptCacheRepository : PromptCacheRepository {

    private val _last = MutableStateFlow<PromptCacheStats?>(null)
    override val last: StateFlow<PromptCacheStats?> = _last.asStateFlow()

    override fun record(stats: PromptCacheStats) {
        _last.value = stats
    }

    override fun clear() {
        _last.value = null
    }
}
