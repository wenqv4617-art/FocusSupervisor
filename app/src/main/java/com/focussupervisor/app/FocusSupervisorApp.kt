package com.focussupervisor.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.focussupervisor.app.core.AppContainer

/**
 * 应用级初始化。
 *
 * 承担两件事：
 *  1. 建好前台服务要用的通知渠道（必须在发出第一条通知之前就存在）；
 *  2. 创建进程级依赖容器 [container]，供无障碍服务与界面共享同一份策略状态。
 *
 * 为什么容器在这里建而不是懒加载：无障碍服务可能在用户从未打开过界面的情况下
 * 就被系统唤起（开机后无障碍服务会自行启动）。如果容器是懒加载的，那个时刻它会
 * 在 Service 的调用栈里被顺手创建 —— 能跑，但初始化时机变得不可预测。放在
 * `onCreate` 里一次建好，之后所有使用方拿到的都是同一个已就绪的实例。
 */
class FocusSupervisorApp : Application() {

    /**
     * 进程级依赖容器。
     *
     * `lateinit` 是安全的：它在 `onCreate` 的第一行就被赋值，而任何使用方
     * （Service、Activity、ViewModel）都只可能在 `onCreate` 返回之后才被系统创建。
     */
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        createMonitorNotificationChannel()
    }

    /**
     * 创建监督服务的常驻通知渠道。
     *
     * 用 IMPORTANCE_LOW：这是「前台服务正在运行」这类持续性通知，不该响铃、
     * 不该弹横幅 —— 它只是告诉用户「东西还活着」，打扰用户就违背了这个应用的
     * 全部初衷。
     */
    private fun createMonitorNotificationChannel() {
        // minSdk 26，NotificationChannel 全平台可用，不需要版本分支；
        // 这里的判断只是为了让意图显式化，编译期也能过 lint。
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val channel = NotificationChannel(
            CHANNEL_ID_MONITOR,
            getString(R.string.monitor_notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.monitor_notification_channel_description)
            setShowBadge(false)
            enableVibration(false)
        }

        getSystemService(NotificationManager::class.java)
            ?.createNotificationChannel(channel)
    }

    companion object {
        /** 前台监督服务的通知渠道 id，Service 侧直接引用这里，避免两处写字符串。 */
        const val CHANNEL_ID_MONITOR = "focus_monitor"
    }
}

/**
 * 从任意 Context 取进程级容器。
 *
 * 刻意放在**顶层**而不是 companion object 里：companion 里的扩展属性必须写成
 * `import com.focussupervisor.app.FocusSupervisorApp.Companion.appContainer` 才能用，
 * 这个导入路径又长又容易被误写成包级导入，然后得到一个很难看懂的
 * 「unresolved reference」。放顶层就是普通的 `import ...appContainer`。
 *
 * 用扩展属性而不是让每个调用方自己写 `(context.applicationContext as FocusSupervisorApp)`：
 * 强制转换散落各处时，一旦有人在测试里换成别的 Application，报错点会离现场很远。
 */
val Context.appContainer: AppContainer
    get() = (applicationContext as FocusSupervisorApp).container
