package com.focussupervisor.app.data.repository

import com.focussupervisor.app.domain.model.SystemNotice
import com.focussupervisor.app.domain.model.TodoItem
import com.focussupervisor.app.domain.model.WhitelistApp
import kotlinx.coroutines.flow.StateFlow

/**
 * 数据层：监督策略仓库（接口）
 *
 * ===========================================================================
 * 这个接口最重要的一条约定：判定必须是同步的
 * ===========================================================================
 * [isAppWhitelisted] 是**普通方法，不是 suspend 方法**。这不是疏忽，而是整个防沉迷
 * 功能的性能命门：
 *
 * 无障碍服务在 `onAccessibilityEvent` 里拿到前台包名后，要在同一个主线程回调里
 * 立刻回答「放不放行」。这个回调每迟到一帧，用户就多看到一帧目标应用的内容 ——
 * 体感全在「多快盖上」这一件事上。
 *
 * 所以实现必须遵循「**内存快照 + 同步读**」这个形态：真正的权威状态放在内存里，
 * 持久化（DataStore / Room / 什么都行）只在后台协程里异步监听并刷入内存。
 * 任何把 DataStore 直接读到判定路径上的实现都是错的 —— 那等于每次窗口切换都做一次
 * 磁盘 IO。
 *
 * 这个约定由 [DataStoreAppPolicyRepository] 落实，它的类注释里写了具体做法。
 *
 * ===========================================================================
 * 为什么「系统播报」也在这个接口上
 * ===========================================================================
 * 拦截事件产生在 service 层，要显示在 UI 层的聊天流里。两个组件同进程，中间需要
 * 一条通道。可选做法有两种：再引入一个全局 EventBus，或者复用这个已经是「进程内
 * 唯一事实来源」的仓库。这里选后者 —— 多一个全局单例就多一个需要初始化的东西，
 * 而仓库本来就已经被两边共同持有。
 *
 * 播报**不持久化**：它是「刚刚发生了什么」，不是用户配置。重启后把昨天的拦截记录
 * 重新倒进聊天流，只会让用户以为又被拦了一次。
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

    /**
     * 该包名此刻是否是一个输入法。
     *
     * 无障碍服务用它过滤窗口事件：键盘弹出会发一条窗口事件，那不是「应用切换」。
     */
    fun isInputMethod(packageName: String): Boolean

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
