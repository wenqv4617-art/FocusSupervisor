package com.focussupervisor.app.core

import android.content.Context
import com.focussupervisor.app.core.network.OpenAiCompatibleClient
import com.focussupervisor.app.core.permission.PermissionManager
import com.focussupervisor.app.data.datastore.AppPreferencesDataSource
import com.focussupervisor.app.data.repository.AiConfigRepository
import com.focussupervisor.app.data.repository.AppLabelResolver
import com.focussupervisor.app.data.repository.AppPolicyRepository
import com.focussupervisor.app.data.repository.CriticalPackageResolver
import com.focussupervisor.app.data.repository.DataStoreAiConfigRepository
import com.focussupervisor.app.data.repository.DataStoreAppPolicyRepository
import com.focussupervisor.app.data.repository.buildBuiltInWhitelist
import com.focussupervisor.app.domain.model.AiPresetDefaults
import com.focussupervisor.app.ui.overlay.LockOverlayController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 进程内的依赖容器（手写 Service Locator）。
 *
 * ===========================================================================
 * 为什么是手写容器，而不是 Hilt / Koin
 * ===========================================================================
 * 本应用需要共享依赖的地方只有几种，而且它们**都不走常规的构造注入路径**：
 *
 *  - `FocusAccessibilityService`：由系统实例化，我们无法参与它的构造；
 *  - `LockOverlayController`：生命周期比任何 Activity 都长；
 *  - `ChatViewModel` / `AiConfigViewModel`：需要共享同一份仓库实例，
 *    否则一个界面改了配置、另一个界面看不到。
 *
 * 引入 Hilt 的代价是 KSP、注解处理器、额外的构建时间，以及一套「为什么这个注入能用
 * 那个不能用」的隐式规则；而收益在这个规模下只是省掉本文件。等到依赖图真的长到
 * 十来个节点时再换，那时替换点也正好只有这一个文件。
 *
 * ===========================================================================
 * 生命周期
 * ===========================================================================
 * 由 [com.focussupervisor.app.FocusSupervisorApp] 在 `onCreate` 里创建，存活时间
 * 等于进程寿命。所有成员都只持有 applicationContext，不会泄漏任何界面对象。
 * [appScope] 也随进程存活 —— 这是**刻意**的：DataStore 的监听与过期豁免清理必须
 * 活得比任何界面都久，无障碍服务在用户从没打开过界面的情况下也要能正常判定。
 */
class AppContainer(context: Context) {

    private val appContext: Context = context.applicationContext

    /**
     * 进程级协程作用域。
     *
     * 用 `Dispatchers.Default` 而不是 Main：挂在这个作用域上的都是磁盘与后台任务
     * （DataStore 监听、过期清理），没有任何一处需要主线程。
     * `SupervisorJob` 保证某一个任务失败不会连坐其它任务。
     */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 持久化入口。策略仓库与配置仓库共用同一个 DataStore 文件。 */
    private val preferences = AppPreferencesDataSource(appContext)

    /** 监督策略仓库：白名单、待办、系统播报。 */
    val policy: AppPolicyRepository = DataStoreAppPolicyRepository(
        context = appContext,
        preferences = preferences,
        scope = appScope,
    )

    /** AI 端点配置仓库：预设、选中项、当前生效配置。 */
    val aiConfig: AiConfigRepository = DataStoreAiConfigRepository(
        preferences = preferences,
        scope = appScope,
    )

    /** 权限状态检查与系统设置跳转。 */
    val permissions: PermissionManager = PermissionManager(appContext)

    /**
     * 全屏遮罩控制器。
     *
     * 放在容器里而不是某个 Service 里，是因为它有两个使用方：无障碍服务的真实拦截，
     * 以及界面上「强制锁定测试」的模拟弹窗。两者必须是同一个实例，否则会出现
     * 「测试的遮罩把自己盖住了，真实拦截却以为没遮罩」这种对不上的状态。
     */
    val lockOverlay: LockOverlayController = LockOverlayController(appContext)

    /**
     * OpenAI 兼容端点客户端。
     *
     * 全局一个实例：OkHttp 自己维护连接池与线程池，每次请求新建客户端等于把池化
     * 全扔掉。
     */
    val openAiClient: OpenAiCompatibleClient = OpenAiCompatibleClient()

    init {
        // 首次启动时把内置白名单与默认预置一次性灌进 DataStore。
        // 放在容器初始化里而不是某个仓库里，理由见 AppPreferencesDataSource.seedIfNeeded：
        // 让两个仓库各自判断「我该不该初始化」会产生竞态。
        appScope.launch {
            val labelResolver = AppLabelResolver(appContext)
            val criticalResolver = CriticalPackageResolver(appContext)
            val builtInWhitelist = buildBuiltInWhitelist(
                selfPackage = appContext.packageName,
                labelResolver = labelResolver,
                criticalPackages = criticalResolver.resolve(),
            )
            preferences.seedIfNeeded(
                builtInWhitelist = builtInWhitelist,
                defaultPresets = AiPresetDefaults.defaults(),
                defaultSelectedPresetId = AiPresetDefaults.defaultSelectedId(),
            )
        }
    }
}
