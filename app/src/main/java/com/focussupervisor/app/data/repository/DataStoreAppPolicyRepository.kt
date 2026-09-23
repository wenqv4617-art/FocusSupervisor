package com.focussupervisor.app.data.repository

import android.content.Context
import android.util.Log
import com.focussupervisor.app.core.time.TimeNarrator
import com.focussupervisor.app.data.datastore.AppPreferencesDataSource
import com.focussupervisor.app.domain.model.SystemNotice
import com.focussupervisor.app.domain.model.SystemWhitelist
import com.focussupervisor.app.domain.model.TimelineKind
import com.focussupervisor.app.domain.model.TodoItem
import com.focussupervisor.app.domain.model.WhitelistApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * 数据层：监督策略仓库（DataStore 实现）
 *
 * ===========================================================================
 * 核心形态：内存快照 + 同步读 + 异步刷入
 * ===========================================================================
 * ```
 *   DataStore（磁盘，唯一权威）
 *        │  Flow 持续监听（后台协程）
 *        ▼
 *   内存快照  MutableStateFlow<List<...>>
 *        │  同步 .value 读取
 *        ▼
 *   isAppWhitelisted()  ← 无障碍服务在主线程直接调，零挂起、零 IO
 *
 *   写路径：调用方 → 原子 edit 事务 → DataStore → Flow 回环 → 内存快照
 * ```
 *
 * 三条必须守住的规则：
 *
 * 1. **判定路径绝不碰磁盘。** `isAppWhitelisted` 只读内存快照。DataStore 是异步 API，
 *    在 `onAccessibilityEvent` 里挂起一次，用户就多看到一帧目标应用的内容。
 *
 * 2. **内存快照只由 Flow 回环写，写路径不直接改内存。** 看起来「先写磁盘、等回环」
 *    比「同时写内存」慢，但那点延迟在毫秒级，肉眼不可见；而省掉乐观写入就省掉了
 *    「磁盘写失败但内存以为成功了」这类最难查的不一致。**单一事实来源**比省一毫秒
 *    重要得多。
 *
 * 3. **首次启动之前，判定依然安全。** 冷启动到 DataStore 第一次发射之间有一个极短的
 *    窗口，此时内存快照是空的。但因为 [isDeviceCritical] 走的是**运行时解析**
 *    （桌面 / 输入法 / 拨号器 / 本应用自身），这些应用在这个窗口里照样不会被拦 ——
 *    最坏的结果只是用户几十毫秒前刚授予的那条临时豁免晚一点点生效。
 *
 * @param context 任意 Context，内部立刻转 applicationContext
 * @param preferences DataStore 访问入口
 * @param scope 进程级协程作用域。传入而不是自建，是为了让所有后台任务共享同一个
 *        SupervisorJob，也便于将来在测试里整体取消。
 * @param clock 时间源，抽成函数以便在测试里把时间拨快验证「豁免到期」。
 */
class DataStoreAppPolicyRepository(
    context: Context,
    private val preferences: AppPreferencesDataSource,
    private val scope: CoroutineScope,
    private val timeline: TimelineRepository,
    private val clock: () -> Long = System::currentTimeMillis,
) : AppPolicyRepository {

    private val appContext: Context = context.applicationContext
    private val labelResolver = AppLabelResolver(appContext)
    private val criticalResolver = CriticalPackageResolver(appContext)

    private val _whitelist = MutableStateFlow<List<WhitelistApp>>(emptyList())
    override val whitelist: StateFlow<List<WhitelistApp>> = _whitelist.asStateFlow()

    private val _todos = MutableStateFlow<List<TodoItem>>(emptyList())
    override val todos: StateFlow<List<TodoItem>> = _todos.asStateFlow()

    /**
     * 播报**不持久化**：它是「刚刚发生了什么」，不是用户配置。
     *
     * 重启后把昨天的拦截记录重新倒进聊天流，只会让用户以为又被拦了一次。所以它
     * 是一个纯内存的 StateFlow，进程结束就没了。
     */
    private val _notices = MutableStateFlow<List<SystemNotice>>(emptyList())
    override val notices: StateFlow<List<SystemNotice>> = _notices.asStateFlow()

    init {
        scope.launch { observePreferences() }
        scope.launch { expiryLoop() }
    }

    /** 唯一的写入内存快照的地方。见类注释规则 2。 */
    private suspend fun observePreferences() {
        preferences.preferences.collect { prefs ->
            _whitelist.value = prefs.whitelist
            _todos.value = prefs.todos
        }
    }

    /**
     * 定期清理已经过期的临时豁免。
     *
     * 判定层面其实不需要它 —— [WhitelistApp.isActiveAt] 已经保证过期的条目不会被
     * 放行。清理的意义在于**存储与界面**：不清理的话，用户几个月后会看到一屏早已
     * 失效的豁免记录，而且 JSON 会无限增长。
     */
    private suspend fun expiryLoop() {
        while (true) {
            delay(EXPIRY_SWEEP_INTERVAL_MILLIS)
            val now = clock()
            if (_whitelist.value.none { !it.isPermanent && !it.isActiveAt(now) }) continue

            runCatching { preferences.removeExpiredWhitelist(now) }
                .onFailure { Log.w(TAG, "清理过期豁免失败", it) }
        }
    }

    // -----------------------------------------------------------------------
    // 判定（全部同步）
    // -----------------------------------------------------------------------

    override fun isAppWhitelisted(packageName: String): Boolean {
        if (isDeviceCritical(packageName)) return true

        // 白名单里可能存在同一个包名的多条记录（常驻 + 临时），只要有一条没过期就算放行。
        val now = clock()
        return _whitelist.value.any { entry ->
            entry.packageName == packageName && entry.isActiveAt(now)
        }
    }

    override fun isDeviceCritical(packageName: String): Boolean {
        if (packageName == appContext.packageName) return true
        if (packageName in SystemWhitelist.BASELINE_PACKAGES) return true
        return packageName in criticalResolver.resolve()
    }

    override fun isInputMethod(packageName: String): Boolean =
        packageName in criticalResolver.inputMethodPackages()

    override fun resolveAppLabel(packageName: String): String =
        labelResolver.resolve(packageName)

    // -----------------------------------------------------------------------
    // 白名单写入
    // -----------------------------------------------------------------------

    override suspend fun grantTemporaryWhitelist(
        packageName: String,
        durationMinutes: Int,
        reason: String,
        appName: String?,
    ) {
        if (packageName.isBlank() || durationMinutes <= 0) return

        // 设备关键应用不需要豁免 —— 它们本来就永远放行。
        // 这里静默返回而不是抛异常：调用方多半是 AI，让它因为一个多余的操作失败
        // 只会污染会话，不如什么都不做。
        if (isDeviceCritical(packageName)) return

        val entry = WhitelistApp(
            packageName = packageName,
            appName = appName ?: labelResolver.resolve(packageName),
            expiresAtMillis = clock() + durationMinutes * MILLIS_PER_MINUTE,
            reason = reason,
        )

        val saved = writeOrReport("保存临时豁免") {
            preferences.upsertTemporaryWhitelist(entry)
        }

        // 只有真的写进去了才记时间线 —— 记一条「已放行」而实际没生效，
        // 会让 AI 以为豁免存在，比不记更糟。
        if (saved) {
            timeline.record(
                kind = TimelineKind.WHITELIST_GRANTED,
                title = entry.appName,
                detail = "$durationMinutes 分钟 · 理由：${entry.reason}",
                atMillis = clock(),
            )
        }
    }

    override suspend fun revokeTemporaryWhitelist(packageName: String) {
        // 先取一次名字：撤销之后白名单里就没这条了，标签只能从包名解析。
        val existing = _whitelist.value.firstOrNull {
            it.packageName == packageName && !it.isPermanent
        }

        val saved = writeOrReport("撤销临时豁免") {
            preferences.removeTemporaryWhitelist(packageName)
        }

        // 本来就没什么可撤的，就不要在时间线上留一条「收回了没有的东西」。
        if (saved && existing != null) {
            timeline.record(
                kind = TimelineKind.WHITELIST_REVOKED,
                title = existing.appName,
                detail = packageName,
                atMillis = clock(),
            )
        }
    }

    // -----------------------------------------------------------------------
    // 待办
    // -----------------------------------------------------------------------

    override suspend fun addTodo(title: String, plannedAtMillis: Long): TodoItem {
        val item = TodoItem(
            id = "todo-${UUID.randomUUID()}",
            title = title,
            plannedAtMillis = plannedAtMillis,
        )
        val saved = writeOrReport("保存待办") {
            preferences.upsertTodo(item)
        }
        if (saved) {
            timeline.record(
                kind = TimelineKind.TODO_ADDED,
                title = item.title,
                detail = "计划 ${TimeNarrator.stamp(plannedAtMillis)}",
                atMillis = clock(),
            )
        }
        return item
    }

    override suspend fun setTodoDone(id: String, isDone: Boolean) {
        val target = _todos.value.firstOrNull { it.id == id }

        val saved = writeOrReport("更新待办") {
            preferences.setTodoDone(id, isDone)
        }

        // 只记「完成」。取消完成不记：那不是一件发生过的事，而是撤销。
        if (saved && isDone && target != null) {
            timeline.record(
                kind = TimelineKind.TODO_COMPLETED,
                title = target.title,
                atMillis = clock(),
            )
        }
    }

    /**
     * 删除一条待办。
     *
     * 不写时间线：删掉的是「一条本来就记错的计划」，它没有发生过。
     * 时间线是**发生过什么**的记录，往里塞没发生过的事会污染 AI 的判断。
     */
    override suspend fun removeTodo(id: String): Boolean =
        writeOrReport("删除待办") { preferences.removeTodo(id) }

    // -----------------------------------------------------------------------
    // 播报
    // -----------------------------------------------------------------------

    override fun publishNotice(text: String) {
        val trimmed = text.trim().take(MAX_NOTICE_LENGTH)
        if (trimmed.isEmpty()) return

        val notice = SystemNotice(
            id = "notice-${UUID.randomUUID()}",
            text = if (trimmed.startsWith(NOTICE_PREFIX)) trimmed else "$NOTICE_PREFIX $trimmed",
            timestampMillis = clock(),
        )
        _notices.update { current -> (current + notice).takeLast(MAX_NOTICES) }
    }

    override fun clearNotices() {
        _notices.value = emptyList()
    }

    // -----------------------------------------------------------------------
    // 写失败处理
    // -----------------------------------------------------------------------

    /**
     * 执行一次写操作，失败时记录日志并往会话里插一条播报。
     *
     * 为什么不把异常往上抛：这些写操作大多由界面按钮或无障碍链路触发，调用方没有
     * 合适的兜底位置 —— 抛出去只会变成一个崩溃对话框。而「写失败了」这件事用户
     * 有权知道：以为已经开了 10 分钟豁免、结果重启就没了，比当场看到一条错误更难接受。
     */
    private suspend fun writeOrReport(action: String, block: suspend () -> Unit): Boolean {
        return try {
            block()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.e(TAG, "$action 失败", t)
            publishNotice("本地保存失败：$action")
            false
        }
    }

    private companion object {
        const val TAG = "AppPolicyRepository"

        const val MILLIS_PER_MINUTE = 60_000L

        /** 过期豁免清理周期。10 分钟一次，对这个数据量来说绰绰有余。 */
        const val EXPIRY_SWEEP_INTERVAL_MILLIS = 10 * 60_000L

        const val NOTICE_PREFIX = "[系统]"

        /** 播报历史上限。防沉迷应用会长时间运行，不设上限就是一个慢性内存泄漏。 */
        const val MAX_NOTICES = 200

        /** 单条播报的最大长度，防止异常输入把界面撑坏。 */
        const val MAX_NOTICE_LENGTH = 200
    }
}
