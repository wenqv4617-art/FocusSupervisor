package com.focussupervisor.app.service

import android.Manifest
import android.app.Notification
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.focussupervisor.app.FocusSupervisorApp
import com.focussupervisor.app.R

/**
 * 前台监督服务骨架（占位）。
 *
 * 它要解决的问题
 * --------------
 * 无障碍服务负责「知道发生了什么」，但**摄像头视线检测、使用时长轮询、定时检查**
 * 这些主动行为需要一个不会被系统随手杀掉的宿主。Android 上唯一被官方认可的长期
 * 运行方式就是前台服务：一条用户可见的常驻通知，换取进程优先级。
 *
 * 骨架阶段的行为
 * --------------
 * 它已经能正确启动为前台服务，但**不做任何检测**，也**不会被任何地方自动拉起**。
 * 现在把它写完整，是为了把最容易踩坑的部分（前台服务类型与权限的对应关系）
 * 一次性钉死，后面接业务时不用再回来改这段。
 *
 * 关于 foregroundServiceType（Android 14 起是硬性要求）
 * ---------------------------------------------------
 * - 清单里声明了 `camera`，那么 `startForeground()` 时传的类型必须是它的子集；
 * - 同时，传了 `camera` 就必须**已经持有** CAMERA 运行时权限，否则系统直接抛
 *   `SecurityException`，而不是降级运行；
 * - 因此这里的做法是：没有权限就干脆不启动前台态，并把自己停掉 —— 与其留一个
 *   半死不活的服务，不如让问题在日志里明明白白。
 */
class FocusMonitorService : Service() {

    /**
     * 服务入口。
     *
     * `onCreate` 时就尝试进入前台态，而不是等 `onStartCommand`：两者之间只差几
     * 毫秒，但一旦被系统在 `onStartCommand` 之前回收，就变成了「后台服务」，
     * 在 Android 8+ 会直接崩溃。
     */
    override fun onCreate() {
        super.onCreate()
        if (!promoteToForeground()) {
            // 进不了前台态就没有存在的意义，直接自我了断。
            stopSelf()
        }
    }

    /**
     * 每次 `startService` 都会走到这里。
     *
     * 返回 `START_NOT_STICKY` 而不是 `START_STICKY`：这个服务持有摄像头，
     * 让系统在杀进程后自动把它拉起来、在后台悄悄开摄像头，是绝对不能接受的行为。
     * 需要恢复监督时，应该由用户主动打开应用或由前台可见的入口触发。
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!promoteToForeground()) {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    /**
     * 本服务不提供绑定，返回 null 是「不支持 bindService」的标准写法。
     */
    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * 把自己提升为前台服务。
     *
     * @return true 表示已成功进入前台态；false 表示缺少必要权限，调用方应停止服务。
     */
    private fun promoteToForeground(): Boolean {
        if (!hasCameraPermission()) {
            Log.w(
                TAG,
                "缺少 CAMERA 权限，无法以 camera 类型进入前台；骨架阶段直接停止。",
            )
            return false
        }

        return try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(),
                foregroundServiceType(),
            )
            Log.i(TAG, "已进入前台监督态")
            true
        } catch (e: SecurityException) {
            // Android 14+ 在类型与权限不匹配时会在这里抛。捕获是为了让日志里
            // 有一条明确的记录，而不是一个看不懂的崩溃栈。
            Log.e(TAG, "进入前台态被系统拒绝", e)
            false
        }
    }

    /**
     * 本次前台服务声明的类型。
     *
     * `FOREGROUND_SERVICE_TYPE_CAMERA` 是 API 29 才有的常量。虽然它在编译期会被
     * 内联成字面量、低版本运行时并不会真的去读它，但显式做版本判断可以让
     * 「这个值在低版本上是什么」这件事一目了然。
     */
    private fun foregroundServiceType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        } else {
            0
        }

    /**
     * 构建常驻通知。
     *
     * 三个刻意的选择：
     * - 渠道 `IMPORTANCE_LOW`（在 `FocusSupervisorApp` 里建）：不响不震不弹横幅；
     * - `setOngoing(true)`：用户不能划掉它 —— 它代表「服务正在运行」这个事实；
     * - `setSilent(true)`：即便渠道被用户改成高重要性，这条通知本身也不出声。
     */
    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, FocusSupervisorApp.CHANNEL_ID_MONITOR)
            .setSmallIcon(R.drawable.ic_notification_focus)
            .setContentTitle(getString(R.string.monitor_notification_title))
            .setContentText(getString(R.string.monitor_notification_text))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setSilent(true)
            .build()

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    companion object {
        private const val TAG = "FocusMonitorService"

        /**
         * 常驻通知的 id。
         *
         * 固定值：前台服务的通知必须是一个长期存在的、唯一的通知，
         * 用随机 id 会每次启动都在通知栏留一条新的。
         */
        private const val NOTIFICATION_ID = 0x10C05
    }
}
