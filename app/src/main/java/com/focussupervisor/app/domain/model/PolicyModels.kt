package com.focussupervisor.app.domain.model

/**
 * 领域层：监督策略模型
 *
 * 这个文件回答一个问题：**「什么算被允许、什么算被拦下」需要哪些数据？**
 * 它同样是纯 Kotlin，不碰 Context、不碰 Room、不碰 WindowManager。Android 侧
 * 的探测（读取真实桌面包名、查询权限状态）全部放在 core 层，由那里翻译成本文件
 * 定义的类型。
 *
 * 三块内容：
 *  1. 待办（[TodoItem]）—— 后续「TodoList 盘问」的数据地基；
 *  2. 豁免（[WhitelistApp]）—— 白名单与 AI 临时豁免；
 *  3. 权限与系统播报（[PermissionTarget] / [PermissionStatus] / [SystemNotice]）。
 */

// ===========================================================================
// 1. 待办
// ===========================================================================

/**
 * 一条待办。
 *
 * 阶段三的「TodoList 盘问」会围绕它展开：AI 在监控时段内定期询问进度，用户答不上来
 * 就收紧豁免。因此这里刻意区分「计划完成时间」和「是否完成」两件事 ——
 * 盘问的判断依据是「计划时间已过但还没完成」，而不是简单的时间排序。
 *
 * @param id 稳定唯一标识，同时用作列表 key。
 * @param title 待办标题，用户或 AI 生成的一句话。
 * @param plannedAtMillis 计划完成的 Unix 时间戳（毫秒）。是「计划」不是「截止」，
 *        逾期只触发盘问，不触发惩罚。
 * @param isDone 是否已完成。完成后不再参与盘问。
 */
data class TodoItem(
    val id: String,
    val title: String,
    val plannedAtMillis: Long,
    val isDone: Boolean = false,
) {
    /**
     * 在给定时刻是否已逾期。
     *
     * 已完成的项永远不逾期 —— 这个判断放在模型里而不是散在 UI 或策略代码里，
     * 是为了保证「已完成的不再盘问」这条规则只有一处定义。
     */
    fun isOverdueAt(nowMillis: Long): Boolean = !isDone && nowMillis > plannedAtMillis
}

// ===========================================================================
// 2. 白名单与豁免
// ===========================================================================

/**
 * 一个被允许（或临时被允许）使用的应用。
 *
 * 两种形态：
 *  - **常驻白名单**：[expiresAtMillis] 为 null。系统桌面、输入法、电话、本应用自身
 *    属于这一类，永远放行。
 *  - **临时豁免**：[expiresAtMillis] 是未来某个时刻。由用户在设置里给出，或者由
 *    AI 在「盘问」之后批准（例如「写完这一节就给你 10 分钟」）。
 *
 * @param packageName 应用包名，判定的唯一依据。绝不用应用名做判定 —— 应用名可重复、
 *        可被仿冒，而且会随系统语言变化。
 * @param appName 展示用名称。只用于界面，任何时候都不参与逻辑判断。
 * @param expiresAtMillis 豁免到期时刻（Unix 毫秒）。null 表示永久。
 * @param reason 豁免理由。常驻项写「系统常驻」，临时项写用户或 AI 给出的原因，
 *        将来会原样显示在会话里，让用户知道「为什么它现在是开着的」。
 */
data class WhitelistApp(
    val packageName: String,
    val appName: String,
    val expiresAtMillis: Long?,
    val reason: String,
) {
    /** 是否为常驻（永不过期）条目。 */
    val isPermanent: Boolean get() = expiresAtMillis == null

    /**
     * 在给定时刻这条豁免是否仍然有效。
     *
     * 用 `now < expiresAt` 而不是 `<=`：到期瞬间即失效，避免「恰好卡在边界上」
     * 这种说不清的状态。
     */
    fun isActiveAt(nowMillis: Long): Boolean =
        expiresAtMillis == null || nowMillis < expiresAtMillis

    /**
     * 距离到期还剩多少分钟；常驻条目返回 null；已过期返回 0。
     *
     * 用于在界面上显示「还剩 8 分钟」。向上取整，避免出现「还剩 0 分钟但还没到期」
     * 这种看起来像 bug 的显示。
     */
    fun remainingMinutesAt(nowMillis: Long): Long? =
        expiresAtMillis?.let { expires ->
            val remaining = expires - nowMillis
            if (remaining <= 0L) 0L else (remaining + 59_999L) / 60_000L
        }
}

/**
 * 系统常驻白名单。
 *
 * **这是整个防沉迷功能的安全底线**：这些包名任何时候都不允许被拦截，否则设备会
 * 变成一块砖 —— 拦掉桌面就回不了家，拦掉输入法就打不了字，拦掉电话就可能漏掉
 * 急救来电。
 *
 * 为什么要「静态清单 + 动态解析」两层：
 *  - 静态清单（本对象）覆盖已知机型和常见第三方实现，纯数据、可测、零成本；
 *  - 动态解析（见 data 层的 `CriticalPackageResolver`）在运行时向 PackageManager
 *    问出「这台设备真正的桌面 / 输入法 / 拨号器是谁」，覆盖静态清单猜不到的机型。
 * 两层取并集。任何一层命中都不拦。
 */
object SystemWhitelist {

    /** 常驻白名单条目的默认理由文本。 */
    const val REASON_BUILT_IN: String = "系统常驻"

    /**
     * 兜底包名清单。
     *
     * 分成五组，每组都写清楚「少了它会怎样」，方便以后有人想删条目时先看一眼代价。
     */
    val BASELINE_PACKAGES: Set<String> = setOf(
        // --- 系统界面与权限：少了它，通知栏、权限弹窗、安装器会一起被黑屏盖住 ---
        "com.android.systemui",
        "com.android.settings",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",

        // --- 电话与通话中界面：少了它，来电会被自己的遮罩挡掉 ---
        "com.android.phone",
        "com.android.server.telecom",
        "com.android.incallui",
        "com.android.dialer",
        "com.google.android.dialer",
        "com.samsung.android.dialer",
        "com.android.emergency",

        // --- 常见桌面：少了它，按返回键之后无处可去 ---
        "com.android.launcher",
        "com.android.launcher3",
        "com.google.android.apps.nexuslauncher",
        "com.miui.home",
        "com.huawei.android.launcher",
        "com.oppo.launcher",
        "com.vivo.launcher",
        "com.bbk.launcher2",
        "com.sec.android.app.launcher",

        // --- 常见输入法：少了它，用户在任何输入框里都打不出字 ---
        "com.android.inputmethod.latin",
        "com.google.android.inputmethod.latin",
        "com.baidu.input",
        "com.sohu.inputmethod.sogou",
        "com.iflytek.inputmethod",
        "com.tencent.qqpinyin",

        // --- 本应用：少了它，用户一打开监督界面就被自己拦下 ---
        "com.focussupervisor.app",
        "com.focussupervisor.app.debug",
    )

    /**
     * 本应用自身的所有可能包名。
     *
     * debug 构建如果加了 applicationIdSuffix，包名会和 release 不一样；判定时用
     * 「运行时读到的 context.packageName」为准，这里只是给纯数据层一个参考值。
     */
    val SELF_PACKAGES: Set<String> = setOf(
        "com.focussupervisor.app",
        "com.focussupervisor.app.debug",
    )
}

// ===========================================================================
// 3. 权限与系统播报
// ===========================================================================

/**
 * 需要引导用户去系统设置里开启的能力。
 *
 * 这五项都不是普通的运行时权限（除了通知），只能靠跳转到系统设置页让用户手动开。
 * 枚举里带上中文名，是因为它同时充当界面标签；真要上多语言时应该换成 string 资源 id。
 */
enum class PermissionTarget(val label: String) {
    /** 无障碍服务：识别前台应用、以及拦截返回键的唯一途径。 */
    ACCESSIBILITY("无障碍服务"),

    /** 悬浮窗：全屏遮罩的绘制权限。 */
    OVERLAY("悬浮窗"),

    /** 使用情况访问：读取应用使用时长的入口（本阶段只做检查，还没用上）。 */
    USAGE_ACCESS("使用情况访问"),

    /** 通知：前台服务常驻通知（Android 13+ 为运行时权限）。 */
    NOTIFICATIONS("通知"),

    /** 电池优化白名单：降低被系统冻结的概率。 */
    BATTERY_OPTIMIZATION("电池优化白名单"),
}

/**
 * 某一项权限在某一时刻的状态快照。
 *
 * 刻意带 [checkedAtMillis]：这些状态随时可能被用户在设置里改掉，界面需要知道
 * 「这是什么时候查的」，否则会拿着过期结论误导用户。
 */
data class PermissionStatus(
    val target: PermissionTarget,
    val granted: Boolean,
    val checkedAtMillis: Long,
)

/**
 * 需要插进聊天流的一条系统播报。
 *
 * 产生方是 service 层（拦截事件），消费方是 UI 层（居中的系统胶囊）。两者同进程，
 * 中间靠 repository 里的一个 StateFlow 传递，见 `AppPolicyRepository.notices`。
 *
 * @param id 稳定唯一标识。ViewModel 靠它去重 —— 同一个 StateFlow 的当前值会在
 *        新订阅者接入时重放一次，没有 id 就会把历史事件重复插一遍。
 * @param text 播报正文，已经带上 `[系统]` 前缀，UI 直接显示。
 * @param timestampMillis 产生时刻。
 */
data class SystemNotice(
    val id: String,
    val text: String,
    val timestampMillis: Long,
)
