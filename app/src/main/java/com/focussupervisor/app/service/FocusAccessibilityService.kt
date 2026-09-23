package com.focussupervisor.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager

/**
 * 无障碍服务骨架 —— 「当前打开了哪个 App」的唯一可靠来源。
 *
 * 它为什么必须存在
 * ----------------
 * Android 从 5.0 起就关掉了「查询前台应用」的普通接口。要在**实时**层面知道
 * 用户切到了哪个应用，只有两条路：`UsageStatsManager`（有延迟，且拿不到
 * 精确的切换瞬间）和无障碍服务（系统主动把窗口变化推给你）。监督拦截必须在
 * 毫秒级响应，所以主路径只能是无障碍。
 *
 * 能力边界（写清楚，避免以后误用）
 * --------------------------------
 * - 本服务**不能自行开启**。只能由用户在「设置 → 无障碍」里手动打开，应用最多
 *   能做的只是 `Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)` 把用户送过去。
 * - 系统可以随时回收它，重新开启后 `onServiceConnected()` 会再次回调，
 *   因此任何状态都必须能在这一步重建，不能依赖构造时机。
 * - 用户关掉后本进程不会有任何回调，无法「感知到自己被关了」。
 *
 * 当前实现状态
 * ------------
 * 只做窗口识别并把结果打到 logcat，**不做任何拦截**。这是刻意留白：拦截动作
 * （`performGlobalAction(GLOBAL_ACTION_BACK)` 或拉起悬浮遮罩）一旦写错就会把
 * 用户的手机变成砖，必须等策略层与 UI 都就绪后再接。
 */
class FocusAccessibilityService : AccessibilityService() {

    /**
     * 上一次记录的前台包名。
     *
     * `TYPE_WINDOW_STATE_CHANGED` 在同一应用内部跳页面时也会触发，如果每次都上报，
     * 监督策略会被同一个 App 反复唤醒。用这个字段做去重，只有「包名真的变了」
     * 才算一次应用切换。
     */
    private var lastForegroundPackage: String? = null

    /**
     * 服务被系统连接时回调（用户开启开关、或系统重新拉起服务）。
     *
     * 事件类型、反馈类型等配置写在 `res/xml/accessibility_service_config.xml` 里，
     * 这里不重复设置；如果将来需要按用户配置动态收窄监听范围，再在这里调用
     * `setServiceInfo(...)`。
     */
    override fun onServiceConnected() {
        super.onServiceConnected()
        lastForegroundPackage = null
        Log.i(TAG, "无障碍服务已连接，开始监听窗口变化")
    }

    /**
     * 核心回调：接收系统推送的窗口事件。
     *
     * 现在的处理链路：
     * ```
     *  事件到达 → 过滤事件类型 → 取包名 → 过滤自己 → 与上一次比对 → 上报
     * ```
     * 「上报」目前只是打日志并预留扩展点。
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // AccessibilityEvent 是可空的（系统在服务即将断开时可能给 null）。
        val safeEvent = event ?: return

        // 只关心「换了窗口/页面」。内容变化（typeWindowContentChanged）在滚动列表
        // 时会高频触发，拿它做应用切换判断既不准也很耗电。
        if (safeEvent.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val packageName = safeEvent.packageName?.toString()?.takeIf { it.isNotEmpty() } ?: return

        // 忽略自己：本应用自己的界面弹出（比如权限引导页）不应该被当成一次切换，
        // 否则会出现「进入 A 应用 → 弹提醒 → 自己弹窗又触发一次」的回环。
        if (packageName == this.packageName) return

        if (packageName == lastForegroundPackage) return
        lastForegroundPackage = packageName

        onForegroundAppChanged(packageName)
    }

    /**
     * 前台应用发生真实切换。
     *
     * TODO(阶段二)：接入监督策略。
     * 期望链路：
     * ```
     *  onForegroundAppChanged(pkg)
     *    → UsageStatsRepository 补全应用名与今日累计时长
     *    → SupervisionPolicy.decide(pkg, 当前时间, 今日时长) 得到 Decision
     *    → Decision.Allow    ：什么都不做
     *    → Decision.Warn     ：向会话写入一条 [系统] 消息
     *    → Decision.Block    ：拉起悬浮遮罩 / performGlobalAction(GLOBAL_ACTION_BACK)
     * ```
     * 注意：策略判定必须是**非阻塞**的，无障碍服务的回调跑在主线程上，
     * 任何耗时操作（查数据库、调模型）都要切到后台协程，否则会拖慢整机响应。
     */
    private fun onForegroundAppChanged(packageName: String) {
        Log.d(TAG, "前台应用切换 -> $packageName")
    }

    /**
     * 服务被系统要求中断（例如用户临时关闭无障碍）。
     */
    override fun onInterrupt() {
        Log.i(TAG, "无障碍服务被中断")
    }

    /**
     * 服务被解绑（用户关闭开关 / 服务被卸载）。
     *
     * 清理掉去重状态，避免下次连上时把「同一个 App」误判成「没有切换」。
     */
    override fun onUnbind(intent: Intent?): Boolean {
        lastForegroundPackage = null
        Log.i(TAG, "无障碍服务已断开")
        return super.onUnbind(intent)
    }

    companion object {
        private const val TAG = "FocusAccessibility"

        /**
         * 查询本应用的无障碍服务当前是否处于开启状态。
         *
         * 用途：主界面的「权限检查」入口需要知道该不该引导用户去设置页。
         *
         * 实现说明：系统没有提供「按包名查某个无障碍服务是否开启」的 API，
         * 标准做法是拿**已开启的服务列表**，再逐个比对它们所属的包名。
         * 用 `FEEDBACK_ALL_MASK` 是为了拿到全部种类，不因反馈类型不同而漏判。
         *
         * @return true 表示本应用至少有一个无障碍服务正在运行。
         */
        fun isEnabled(context: Context): Boolean {
            val manager = context.getSystemService(AccessibilityManager::class.java)
                ?: return false
            val enabledServices = manager.getEnabledAccessibilityServiceList(
                AccessibilityServiceInfo.FEEDBACK_ALL_MASK,
            )
            return enabledServices.any { info ->
                info.resolveInfo?.serviceInfo?.packageName == context.packageName
            }
        }
    }
}
