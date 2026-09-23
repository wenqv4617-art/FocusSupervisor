package com.focussupervisor.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.focussupervisor.app.service.FocusMonitorService

/**
 * 应用级初始化。
 *
 * 目前只承担一件事：建好前台服务要用的通知渠道。
 *
 * 为什么放在 Application 而不是 Service 里：通知渠道是「建一次、全局复用」的
 * 系统资源，重复创建同名渠道是空操作但会有一堆无意义的调用；更重要的是，
 * 渠道必须在**发出第一条通知之前**就存在，否则 Android 8.0+ 会直接丢弃通知
 * 并在 logcat 里骂人。放在进程启动时创建是最省心的做法。
 */
class FocusSupervisorApp : Application() {

    override fun onCreate() {
        super.onCreate()
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
