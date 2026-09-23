package com.focussupervisor.app.core.notify

import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.focussupervisor.app.MainActivity
import com.focussupervisor.app.R
import com.focussupervisor.app.data.repository.ConversationRepository
import com.focussupervisor.app.domain.model.MessageSender

/**
 * 主动盘问的「触达」通道：让用户知道 AI 刚才开口了。
 *
 * ===========================================================================
 * 为什么要专门做一层，而不是在引擎里直接发通知
 * ===========================================================================
 * 引擎是纯判断逻辑（可单测、不碰 Android）。通知、震动、前台判断都是平台细节，
 * 混进去之后引擎就没法在 JVM 上验证了 —— 而这个引擎最需要验证的恰恰是
 * 「什么时候不该开口」那套闸门逻辑。
 *
 * ===========================================================================
 * 什么时候弹通知：只看「应用是不是在前台」
 * ===========================================================================
 * 他正开着聊天界面盯着看时，消息已经在屏幕上了，再弹一条通知是纯噪音。
 * 只有在应用不可见时才弹 —— 那正是通知存在的意义：把「AI 找你了」送到他眼前。
 *
 * 判断用的是 `getRunningAppProcesses()` 里本进程的 importance。Android 5.0 起
 * 这个接口对**自己**的进程始终可见（别人的看不到），所以它既准确又不需要
 * 额外依赖（引 lifecycle-process 只为一个布尔量不划算）。
 */
class ProactiveNotifier(
    context: Context,
    private val conversation: ConversationRepository,
) {

    private val appContext: Context = context.applicationContext
    private val manager = NotificationManagerCompat.from(appContext)

    /**
     * 发布一次「AI 主动开口」。
     *
     * 震动与通知是两件事，分别独立判断：
     *  - 震动在任何时候都给（手上的反馈是「有人在找你」最直接的信号）；
     *  - 通知只在应用不可见时给。
     */
    fun onProactiveMessage() {
        vibrate()
        if (isAppInForeground()) return
        postNotification()
    }

    // -----------------------------------------------------------------------
    // 震动
    // -----------------------------------------------------------------------

    /**
     * 短震一下。
     *
     * 用固定长度的一次性震动而不是系统默认通知震动：默认震动在「仅响铃一次」之类的
     * 系统设置下会完全消失，而这个反馈是主动盘问能被感知到的最后一道保障。
     * 没有震动马达（平板、模拟器）时静默跳过。
     */
    private fun vibrate() {
        runCatching {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                appContext.getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                appContext.getSystemService(Vibrator::class.java)
            } ?: return

            if (!vibrator.hasVibrator()) return
            vibrator.vibrate(
                VibrationEffect.createOneShot(VIBRATE_MILLIS, VibrationEffect.DEFAULT_AMPLITUDE),
            )
        }.onFailure { Log.w(TAG, "震动失败", it) }
    }

    // -----------------------------------------------------------------------
    // 通知
    // -----------------------------------------------------------------------

    private fun postNotification() {
        ensureChannel()

        val text = conversation.messages.value
            .lastOrNull { it.sender == MessageSender.AI }
            ?.text
            ?.trim()
            ?.take(MAX_NOTIFICATION_TEXT_LENGTH)
            .orEmpty()
        if (text.isEmpty()) return

        val openApp = PendingIntent.getActivity(
            appContext,
            0,
            Intent(appContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_focus)
            .setContentTitle(appContext.getString(R.string.proactive_notification_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()

        runCatching { manager.notify(NOTIFICATION_ID, notification) }
            .onFailure { Log.w(TAG, "通知失败（多半是没给通知权限）", it) }
    }

    /**
     * 建渠道。
     *
     * `IMPORTANCE_DEFAULT` 而不是注视监控那条 `IMPORTANCE_LOW`：那条是「服务在运行」
     * 的状态提示，这条是**AI 在叫你**。前者不该出声，后者必须能让人注意到。
     *
     * `enableVibration(false)`：震动由上面手动做，让渠道再震一次就成了双震。
     */
    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            appContext.getString(R.string.proactive_notification_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = appContext.getString(R.string.proactive_notification_channel_description)
            enableVibration(false)
        }
        appContext.getSystemService(NotificationManager::class.java)
            ?.createNotificationChannel(channel)
    }

    /** 本应用此刻是否在前台（有可见的界面）。 */
    private fun isAppInForeground(): Boolean = runCatching {
        val activityManager = appContext.getSystemService(ActivityManager::class.java)
            ?: return false
        val self = appContext.packageName
        activityManager.getRunningAppProcesses()
            ?.firstOrNull { it.processName == self }
            ?.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }.getOrDefault(false)

    private companion object {
        const val TAG = "ProactiveNotifier"

        /** 与注视监控那条常驻通知分开的 id，否则主动提醒会把它顶掉。 */
        const val NOTIFICATION_ID = 0x10C06

        const val CHANNEL_ID = "focus_proactive"

        const val VIBRATE_MILLIS = 90L

        const val MAX_NOTIFICATION_TEXT_LENGTH = 160
    }
}
