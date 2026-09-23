package com.focussupervisor.app.data.repository

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.telecom.TelecomManager
import android.view.inputmethod.InputMethodManager
import com.focussupervisor.app.domain.model.SystemNotice
import com.focussupervisor.app.domain.model.SystemWhitelist
import com.focussupervisor.app.domain.model.TodoItem
import com.focussupervisor.app.domain.model.WhitelistApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

/**
 * 数据层：监督策略仓库
 *
 * ===========================================================================
 * 为什么是「内存实现 + 接口」而不是直接上 DataStore
 * ===========================================================================
 * 决定性原因只有一个：**判定必须是同步的**。
 *
 * 无障碍服务在 `onAccessibilityEvent` 里拿到前台包名后，要在同一个主线程回调里
 * 立刻回答「放不放行」。DataStore 是异步 API，读它必须挂起；一旦在这里挂起，
 * 用户已经能看到目标应用的界面了，遮罩才慢半拍盖上去 —— 防沉迷最忌讳的就是这半拍。
 *
 * 所以正确的形态本来就是：**内存里保一份权威快照，判定走同步读；持久化只是快照的
 * 备份**。本阶段先把上面那半层做扎实（[AppPolicyRepository] 接口 + 内存实现），
 * 下半层（DataStore 落盘 + 启动时回灌）留给下一阶段，届时只需要新增一个
 * `DataStoreAppPolicyRepository` 实现同一个接口，上层一行都不用改。
 *
 * ===========================================================================
 * 为什么「系统播报」也放在这里
 * ===========================================================================
 * 拦截事件产生在 service 层，要显示在 UI 层的聊天流里。两个组件同进程，中间需要
 * 一条通道。可选做法有两种：再引入一个全局 EventBus，或者复用这个已经是「进程内
 * 唯一事实来源」的仓库。这里选后者 —— 多一个全局单例就多一个需要初始化的东西，
 * 而仓库本来就已经被两边共同持有。
 *
 * 用 StateFlow 而不是 SharedFlow 承载播报：SharedFlow(replay=0) 会把「应用还没
 * 打开时发生的拦截」直接丢掉；StateFlow 保留最近 [MAX_NOTICES] 条，UI 后启动也能
 * 补上历史。代价是需要靠 [SystemNotice.id] 去重，这一层由 ViewModel 负责。
 */
interface AppPolicyRepository {

    /** 当前生效的白名单：系统常驻项 + AI/用户授予的临时豁免。 */
    val whitelist: StateFlow<List<WhitelistApp>>

    /** 待办列表。 */
    val todos: StateFlow<List<TodoItem>>

    /** 最近若干条系统播报，供聊天界面插入居中胶囊。 */
    val notices: StateFlow<List<SystemNotice>>

    /**
     * 判定一个包名此刻是否放行。**同步方法，可以在主线程直接调。**
     *
     * 判定顺序（任何一层命中都放行）：
     *  1. 本应用自己；
     *  2. 系统常驻兜底清单（桌面 / 输入法 / 电话 / 系统界面……）；
     *  3. 运行时动态解析出的设备关键应用（这台机器真正的桌面、输入法、拨号器）；
     *  4. 白名单里尚未过期的条目。
     */
    fun isAppWhitelisted(packageName: String): Boolean

    /** 该包名是否属于「绝不能拦」的设备关键应用（第 1~3 层），与用户配置无关。 */
    fun isDeviceCritical(packageName: String): Boolean

    /** 把包名解析成可读的应用名；解析不到时原样返回包名。 */
    fun resolveAppLabel(packageName: String): String

    /**
     * 授予一次临时豁免。这是留给后续 AI 调用的入口。
     *
     * @param packageName 目标应用
     * @param durationMinutes 豁免时长（分钟），必须为正数
     * @param reason 豁免理由，会原样出现在会话播报里
     * @param appName 可选的展示名；不传则自动解析
     */
    suspend fun grantTemporaryWhitelist(
        packageName: String,
        durationMinutes: Int,
        reason: String,
        appName: String? = null,
    )

    /** 撤销某个包名的全部临时豁免。常驻条目不受影响。 */
    suspend fun revokeTemporaryWhitelist(packageName: String)

    /** 新建一条待办。 */
    suspend fun addTodo(title: String, plannedAtMillis: Long): TodoItem

    /** 勾选 / 取消勾选一条待办。 */
    suspend fun setTodoDone(id: String, isDone: Boolean)

    /** 投递一条系统播报（会自动补 `[系统]` 前缀并截断长度）。 */
    fun publishNotice(text: String)

    /** 清空播报历史。 */
    fun clearNotices()
}

/**
 * 内存实现。
 *
 * 线程安全说明：所有可变状态都是 [MutableStateFlow]，写入统一走 [MutableStateFlow.update]
 * （CAS 循环），因此无障碍服务线程、UI 线程、协程之间不需要额外加锁。
 *
 * @param context 任意 Context，内部立刻转成 applicationContext，不会持有 Activity。
 * @param clock 时间源。抽成函数是为了让「豁免到期」这类逻辑可以在单元测试里
 *        把时间拨快，而不必真的等上 10 分钟。
 */
class InMemoryAppPolicyRepository(
    context: Context,
    private val clock: () -> Long = System::currentTimeMillis,
) : AppPolicyRepository {

    private val appContext: Context = context.applicationContext
    private val labelResolver = AppLabelResolver(appContext)
    private val criticalResolver = CriticalPackageResolver(appContext)

    private val _whitelist = MutableStateFlow(buildBuiltInWhitelist())
    override val whitelist: StateFlow<List<WhitelistApp>> = _whitelist.asStateFlow()

    private val _todos = MutableStateFlow(seedTodos())
    override val todos: StateFlow<List<TodoItem>> = _todos.asStateFlow()

    private val _notices = MutableStateFlow<List<SystemNotice>>(emptyList())
    override val notices: StateFlow<List<SystemNotice>> = _notices.asStateFlow()

    // -----------------------------------------------------------------------
    // 判定
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

        val now = clock()
        val entry = WhitelistApp(
            packageName = packageName,
            appName = appName ?: labelResolver.resolve(packageName),
            expiresAtMillis = now + durationMinutes * MILLIS_PER_MINUTE,
            reason = reason,
        )

        _whitelist.update { current ->
            // 同一个包名的旧临时豁免被覆盖；常驻条目原样保留。
            val kept = current.filterNot { it.packageName == packageName && !it.isPermanent }
            kept + entry
        }
    }

    override suspend fun revokeTemporaryWhitelist(packageName: String) {
        _whitelist.update { current ->
            current.filterNot { it.packageName == packageName && !it.isPermanent }
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
        _todos.update { current -> (current + item).sortedBy { it.plannedAtMillis } }
        return item
    }

    override suspend fun setTodoDone(id: String, isDone: Boolean) {
        _todos.update { current ->
            current.map { if (it.id == id) it.copy(isDone = isDone) else it }
        }
    }

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
    // 初始数据
    // -----------------------------------------------------------------------

    /**
     * 构造常驻白名单。
     *
     * 不只写死 [SystemWhitelist.BASELINE_PACKAGES]，而是把**运行时真正解析出来的**
     * 桌面 / 输入法 / 拨号器也一并列进去：那些才是这台设备上真实存在、且真的会被
     * 用户看到的条目，界面上展示它们才有意义（列一堆没装的包名只会让人困惑）。
     * 静态兜底清单仍然参与判定，只是不进这张展示列表。
     */
    private fun buildBuiltInWhitelist(): List<WhitelistApp> {
        val self = appContext.packageName
        val packages = buildSet {
            add(self)
            addAll(criticalResolver.resolve())
        }

        return packages
            .map { pkg ->
                WhitelistApp(
                    packageName = pkg,
                    appName = labelResolver.resolve(pkg),
                    expiresAtMillis = null,
                    reason = SystemWhitelist.REASON_BUILT_IN,
                )
            }
            .sortedWith(
                // 显式写出类型参数：compareBy 的两个选择器返回类型不同
                // （Boolean 与 String），靠上下文推断容易失败。
                compareBy<WhitelistApp>(
                    { it.packageName != self },
                    { it.appName },
                ),
            )
    }

    /**
     * 阶段二的样例待办。
     *
     * 这两条不是真实数据，只是让「待办与白名单」面板有东西可展示。等阶段三接上
     * 真正的 TodoList 盘问后，它们应该被删掉，改由 AI 或用户创建。
     */
    private fun seedTodos(): List<TodoItem> {
        val now = clock()
        return listOf(
            TodoItem(
                id = "todo-seed-1",
                title = "把第二节的代码写完",
                plannedAtMillis = now + 45 * MILLIS_PER_MINUTE,
            ),
            TodoItem(
                id = "todo-seed-2",
                title = "出门走 20 分钟",
                plannedAtMillis = now + 3 * 60 * MILLIS_PER_MINUTE,
            ),
        )
    }

    private companion object {
        const val MILLIS_PER_MINUTE = 60_000L
        const val NOTICE_PREFIX = "[系统]"

        /** 播报历史上限。防沉迷应用会长时间运行，不设上限就是一个慢性内存泄漏。 */
        const val MAX_NOTICES = 200

        /** 单条播报的最大长度，防止异常输入把界面撑坏。 */
        const val MAX_NOTICE_LENGTH = 200
    }
}

// ===========================================================================
// Android 侧解析工具
// ===========================================================================

/**
 * 包名 → 应用名。
 *
 * 解析不到就返回包名本身。这是刻意设计：拦截播报里宁可变出一串
 * `com.example.thing`，也不能因为查不到名字就不播报 —— 用户必须知道刚才发生了什么。
 */
internal class AppLabelResolver(private val context: Context) {

    private val cache = mutableMapOf<String, String>()

    fun resolve(packageName: String): String {
        cache[packageName]?.let { return it }

        val label = runCatching {
            val info = applicationInfoOf(packageName)
            if (info == null) {
                packageName
            } else {
                context.packageManager.getApplicationLabel(info).toString()
                    .takeIf { it.isNotBlank() } ?: packageName
            }
        }.getOrDefault(packageName)

        cache[packageName] = label
        return label
    }

    private fun applicationInfoOf(packageName: String): ApplicationInfo? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getApplicationInfo(
                packageName,
                PackageManager.ApplicationInfoFlags.of(0L),
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getApplicationInfo(packageName, 0)
        }
}

/**
 * 运行时解析「这台设备上绝不能拦的应用」。
 *
 * 静态清单只能覆盖常见机型，而现实中桌面可以是任何第三方启动器、输入法更是五花八门，
 * 拨号器还可能被厂商替换。所以这里直接向系统提问，把答案并进白名单。
 *
 * 关于包可见性（Android 11+）：查询结果会被过滤，因此清单里已经声明了
 * `<queries>`（MAIN/HOME、MAIN/LAUNCHER、DIAL/tel、VIEW/tel），这几条足以让桌面、
 * 拨号器和所有可启动应用对本应用可见。刻意**不申请 `QUERY_ALL_PACKAGES`** ——
 * 那是一个上架时需要专项说明的高敏感权限，而这里只需要四类 intent。
 *
 * 结果缓存 [CACHE_TTL_MILLIS]：判定会在每次窗口切换时被调用，而解析要走
 * PackageManager 的跨进程查询。缓存既能挡住高频调用，又能在用户中途换桌面 /
 * 换输入法之后自动跟上。
 */
internal class CriticalPackageResolver(private val context: Context) {

    @Volatile
    private var cached: Set<String> = emptySet()

    @Volatile
    private var cachedAtMillis: Long = 0L

    fun resolve(): Set<String> {
        val now = SystemClock.elapsedRealtime()
        val snapshot = cached
        if (snapshot.isNotEmpty() && now - cachedAtMillis < CACHE_TTL_MILLIS) return snapshot

        val resolved = buildSet {
            add(context.packageName)

            // 桌面：谁响应 HOME，谁就是桌面。
            queryPackages(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))
                .forEach { add(it) }

            // 拨号：默认拨号器 + 所有能处理 DIAL/tel 的应用。
            // 取并集是因为有些 ROM 的来电界面和拨号盘不在同一个包里。
            runCatching {
                context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage
            }.getOrNull()?.let { add(it) }
            queryPackages(Intent(Intent.ACTION_DIAL).setData(Uri.parse("tel:")))
                .forEach { add(it) }

            // 输入法：用户启用的每一套都要放行，否则切一下输入法就被拦了。
            runCatching {
                context.getSystemService(InputMethodManager::class.java)
                    ?.enabledInputMethodList
                    ?.map { it.packageName }
            }.getOrNull()?.forEach { add(it) }
        }

        cached = resolved
        cachedAtMillis = now
        return resolved
    }

    private fun queryPackages(intent: Intent): List<String> {
        val infos: List<ResolveInfo> = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.queryIntentActivities(
                    intent,
                    PackageManager.ResolveInfoFlags.of(0L),
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.queryIntentActivities(intent, 0)
            }
        }.getOrDefault(emptyList())

        return infos.mapNotNull { it.activityInfo?.packageName }
    }

    private companion object {
        /** 解析结果缓存时长。够短，能跟上用户换桌面；够长，能挡住窗口切换的频率。 */
        const val CACHE_TTL_MILLIS = 60_000L
    }
}
