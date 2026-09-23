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
import com.focussupervisor.app.domain.model.SystemWhitelist
import com.focussupervisor.app.domain.model.WhitelistApp

/**
 * 设备关键应用的运行时解析。
 *
 * 这两个类从 `AppPolicyRepository` 里拆出来，是因为它们和「仓库」这个概念其实没有
 * 关系 —— 它们是**平台能力的封装**：一个把包名翻成应用名，一个问出这台设备上
 * 绝不能拦的应用是谁。拆出来之后，仓库只负责「状态 + 判定」，解析逻辑可以被任何
 * 实现复用，也更容易单独替换。
 */

/**
 * 包名 → 应用名。
 *
 * 解析不到就返回包名本身。这是刻意设计：拦截播报里宁可变出一串
 * `com.example.thing`，也不能因为查不到名字就不播报 —— 用户必须知道刚才发生了什么。
 *
 * 内部有缓存：应用名在一次进程生命周期里不会变，而拦截播报是高频路径。
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

    /**
     * 当前用户启用的输入法包名。
     *
     * 单独暴露一份（而不是只并进 [resolve] 的结果），是因为无障碍服务需要它做一件
     * 别的事：键盘弹出来时会发一条窗口事件，那条事件**不能**被当成「用户切换了
     * 前台应用」。见 `FocusAccessibilityService` 里对过客型窗口的处理。
     */
    fun inputMethodPackages(): Set<String> = runCatching {
        context.getSystemService(InputMethodManager::class.java)
            ?.enabledInputMethodList
            ?.map { it.packageName }
            ?.toSet()
    }.getOrDefault(emptySet())

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

/**
 * 构造首次启动时写入的常驻白名单。
 *
 * 不只写死 [SystemWhitelist.BASELINE_PACKAGES]，而是把**运行时真正解析出来的**
 * 桌面 / 输入法 / 拨号器也一并列进去：那些才是这台设备上真实存在、且真的会被
 * 用户看到的条目，界面上展示它们才有意义（列一堆没装的包名只会让人困惑）。
 * 静态兜底清单仍然参与判定，只是不进这张展示列表。
 *
 * 之所以要落盘而不是每次现算：现算的话，用户手动撤销某条常驻项就永远撤销不掉
 * （下次启动又冒出来）。落盘之后这份列表就变成了「初始建议」，用户可以改。
 */
internal fun buildBuiltInWhitelist(
    selfPackage: String,
    labelResolver: AppLabelResolver,
    criticalPackages: Set<String>,
): List<WhitelistApp> {
    val packages = buildSet {
        add(selfPackage)
        addAll(criticalPackages)
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
                { it.packageName != selfPackage },
                { it.appName },
            ),
        )
}
