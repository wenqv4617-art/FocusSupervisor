package com.focussupervisor.app.core

import android.content.Context
import com.focussupervisor.app.core.permission.PermissionManager
import com.focussupervisor.app.data.repository.AppPolicyRepository
import com.focussupervisor.app.data.repository.InMemoryAppPolicyRepository
import com.focussupervisor.app.ui.overlay.LockOverlayController

/**
 * 进程内的依赖容器（手写 Service Locator）。
 *
 * ===========================================================================
 * 为什么是手写容器，而不是 Hilt / Koin
 * ===========================================================================
 * 本应用需要共享依赖的地方只有三种，而且它们**都不走常规的构造注入路径**：
 *
 *  - `FocusAccessibilityService`：由系统实例化，我们无法参与它的构造；
 *  - `LockOverlayController`：生命周期比任何 Activity 都长；
 *  - `ChatViewModel`：需要上面两个，且必须拿到同一个实例。
 *
 * 引入 Hilt 的代价是 KSP、注解处理器、额外的构建时间，以及一套「为什么这个注入能用
 * 那个不能用」的隐式规则；而收益在这个规模下只是省掉本文件。等到依赖图真的长到
 * 十来个节点时再换，那时替换点也正好只有这一个文件。
 *
 * ===========================================================================
 * 生命周期
 * ===========================================================================
 * 由 [com.focussupervisor.app.FocusSupervisorApp] 在 `onCreate` 里创建，存活时间
 * 等于进程寿命。三个成员全部只持有 applicationContext，不会泄漏任何界面对象。
 */
class AppContainer(context: Context) {

    private val appContext: Context = context.applicationContext

    /**
     * 监督策略仓库。无障碍服务与界面共享同一个实例 —— 这是「拦截结果能实时反映到
     * 界面上」的前提。
     */
    val policy: AppPolicyRepository = InMemoryAppPolicyRepository(appContext)

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
}
