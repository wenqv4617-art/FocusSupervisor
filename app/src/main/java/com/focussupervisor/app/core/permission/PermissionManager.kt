package com.focussupervisor.app.core.permission

import android.app.AppOpsManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.focussupervisor.app.domain.model.PermissionStatus
import com.focussupervisor.app.domain.model.PermissionTarget
import com.focussupervisor.app.service.FocusAccessibilityService

/**
 * 权限检查与系统设置跳转。
 *
 * 职责边界：**只回答「现在是什么状态」和「怎么把用户送过去」，不负责申请，也不负责解释
 * 为什么需要**。这些能力里除了通知，全都是「特殊权限」—— 系统不允许应用弹窗申请，
 * 用户只能自己进设置页打开。所以这里的产出只有两样：状态快照 + 跳转 Intent。
 *
 * 为什么单独抽一层：同一个判断会被三处用到（「+」面板的红点、权限检查对话框、
 * 以及将来的启动自检），如果每处各写一遍 `Settings.canDrawOverlays`，早晚会出现
 * 三处结论不一致的 bug。
 */
class PermissionManager(private val context: Context) {

    private val appContext: Context = context.applicationContext

    /**
     * 采集一份完整快照。
     *
     * 每次调用都真查一遍，不做缓存 —— 用户可能刚刚从设置页回来，缓存会让界面
     * 继续显示过期状态，那是这个功能最不该犯的错。
     */
    fun snapshot(): List<PermissionStatus> {
        val now = System.currentTimeMillis()
        return PermissionTarget.entries.map { target ->
            PermissionStatus(
                target = target,
                granted = isGranted(target),
                checkedAtMillis = now,
            )
        }
    }

    /** 某一项权限此刻是否已授予。 */
    fun isGranted(target: PermissionTarget): Boolean = when (target) {
        PermissionTarget.ACCESSIBILITY -> FocusAccessibilityService.isEnabled(appContext)

        PermissionTarget.OVERLAY -> Settings.canDrawOverlays(appContext)

        PermissionTarget.USAGE_ACCESS -> hasUsageAccess()

        PermissionTarget.NOTIFICATIONS ->
            NotificationManagerCompat.from(appContext).areNotificationsEnabled()

        PermissionTarget.BATTERY_OPTIMIZATION -> isIgnoringBatteryOptimizations()
    }

    /**
     * 生成跳转到该项权限系统设置页的 Intent。
     *
     * 返回 null 表示这台设备没有对应的设置页（极少数深度定制 ROM）。调用方应当
     * 直接放弃跳转并如实告知，而不是退而求其次跳到一个不相干的页面。
     *
     * 已经带上 `FLAG_ACTIVITY_NEW_TASK`：调用方可能是 Service 或 Application 上下文
     * （例如无障碍服务里引导用户补权限），没有这个 flag 会直接抛异常。
     */
    fun settingsIntent(target: PermissionTarget): Intent? {
        val intent = when (target) {
            PermissionTarget.ACCESSIBILITY ->
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)

            PermissionTarget.OVERLAY ->
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                    .setData(Uri.parse("package:${appContext.packageName}"))

            PermissionTarget.USAGE_ACCESS ->
                Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)

            PermissionTarget.NOTIFICATIONS ->
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, appContext.packageName)

            PermissionTarget.BATTERY_OPTIMIZATION ->
                // 用「请求忽略电池优化」而不是「电池优化设置列表」：前者能直接定位到
                // 本应用，后者要用户自己在长列表里翻。
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:${appContext.packageName}"))
        }

        return intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /**
     * 直接跳转到某项权限的设置页。
     *
     * @return true 表示已经成功把用户送过去；false 表示这台设备不支持该页面。
     */
    fun openSettings(target: PermissionTarget): Boolean {
        val intent = settingsIntent(target) ?: return false
        return try {
            appContext.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "找不到 ${target.name} 对应的设置页", e)
            false
        } catch (t: Throwable) {
            // 部分 ROM 会抛 SecurityException（例如厂商把电池优化页锁起来了）。
            Log.w(TAG, "跳转 ${target.name} 设置页失败", t)
            false
        }
    }

    // -----------------------------------------------------------------------
    // 各项权限的判定细节
    // -----------------------------------------------------------------------

    /**
     * 使用情况访问权限。
     *
     * 这个权限没有公开的检查 API，标准做法是拿 AppOpsManager 查
     * `OPSTR_GET_USAGE_STATS` 这一项的操作模式。
     */
    private fun hasUsageAccess(): Boolean {
        val appOps = appContext.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                appContext.packageName,
            )
        } else {
            // API 26~28 上 checkOpNoThrow 是唯一可用的重载。它在 29 被标记弃用并
            // 由 unsafeCheckOpNoThrow 取代，但低版本没有替代品，只能分支使用。
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                appContext.packageName,
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** 是否已被用户加入电池优化白名单。 */
    private fun isIgnoringBatteryOptimizations(): Boolean =
        appContext.getSystemService(PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(appContext.packageName)
            ?: false

    private companion object {
        const val TAG = "PermissionManager"
    }
}
