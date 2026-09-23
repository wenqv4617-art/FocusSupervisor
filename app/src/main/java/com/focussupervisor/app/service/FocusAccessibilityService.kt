package com.focussupervisor.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityWindowInfo
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.focussupervisor.app.MainActivity
import com.focussupervisor.app.appContainer
import com.focussupervisor.app.core.vision.ScreenCaptureProvider
import com.focussupervisor.app.data.repository.AppPolicyRepository
import com.focussupervisor.app.data.repository.TimelineRepository
import com.focussupervisor.app.domain.model.SystemWhitelist
import com.focussupervisor.app.domain.model.TimelineKind
import com.focussupervisor.app.ui.overlay.LockOverlayController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

/**
 * 无障碍服务 —— 应用监督的执行端。
 *
 * ===========================================================================
 * 它在整条链路里的位置
 * ===========================================================================
 * ```
 *   窗口变化事件
 *        ↓  (系统回调，主线程)
 *   取出前台包名 ──→ AppPolicyRepository.isAppWhitelisted()   ← 同步判定
 *        │
 *        ├─ 放行 → 撤掉可能存在的遮罩，什么都不做
 *        │
 *        └─ 拦截 → LockOverlayController.show()  拉起全屏遮罩
 *                   AppPolicyRepository.publishNotice()  往聊天流插一条 [系统] 播报
 *
 *   遮罩期间按下返回键
 *        ↓
 *   onKeyEvent 吞掉按键 ──→ performGlobalAction(GLOBAL_ACTION_HOME)  强制回桌面
 * ```
 *
 * ===========================================================================
 * 为什么判定必须在主线程同步完成
 * ===========================================================================
 * `onAccessibilityEvent` 跑在主线程上。这个回调每迟到一帧，用户就多看到一帧目标
 * 应用的内容 —— 防沉迷的体感全在「多快盖上」这一件事上。所以
 * `isAppWhitelisted` 被刻意设计成不挂起的同步方法，仓库也因此在内存里保留一份
 * 权威快照（理由详见 `AppPolicyRepository` 的类注释）。
 *
 * 反过来，任何可能耗时的动作都绝不能放在这个回调里：本类除了读内存状态和调用
 * `WindowManager.addView` 之外不做别的事，也不在这里发网络请求或查数据库。
 *
 * ===========================================================================
 * 能力边界（写给未来改这段代码的人）
 * ===========================================================================
 * - 本服务**不能自行开启**，只能由用户在「设置 → 无障碍」里手动打开；
 * - 系统可以随时回收它。重新开启后 `onServiceConnected` 会再次回调，因此所有
 *   运行期状态都必须能在这一步重建，不能依赖构造时机；
 * - 用户关掉服务后本进程收不到任何回调，**无法自我感知已被关闭**。所以界面上的
 *   「权限检查」入口是必需的，它是用户唯一能发现「监督已经失效」的地方。
 */
class FocusAccessibilityService : AccessibilityService() {

    /**
     * 服务自己的协程作用域。
     *
     * 用 `Dispatchers.Main.immediate`：收集白名单变化之后往往要立刻操作
     * WindowManager，已经在主线程时 `immediate` 能省掉一次调度，让解锁更跟手。
     * 用 `SupervisorJob` 保证某一次收集失败不会连坐整个作用域。
     */
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var policy: AppPolicyRepository
    private lateinit var lockOverlay: LockOverlayController
    private lateinit var timeline: TimelineRepository

    /**
     * 上一次判定过的前台包名。
     *
     * `TYPE_WINDOW_STATE_CHANGED` 的触发频率远高于「用户换了一个应用」：同一个应用
     * 内部翻页、弹对话框、甚至输入法切换都会触发。没有这个字段，同一个应用会被
     * 反复判定、反复播报，聊天流会被瞬间刷屏。
     */
    private var lastEvaluatedPackage: String? = null

    /**
     * 是否已经提醒过「悬浮窗权限缺失」。
     *
     * 只在每次服务连接后提醒一次。缺权限时每一次窗口变化都会走到这里，不设这个
     * 开关会立刻把聊天流灌满同一条警告。
     */
    private var hasWarnedMissingOverlayPermission = false

    /**
     * 上一次已经播报过「已压制」的包名。
     *
     * 和 [lastEvaluatedPackage] 分开：那个负责挡住重复判定，这个只负责挡住重复播报。
     * 两者混用会让「遮罩被误撤后重新盖上」这种情况播报两遍 —— 用户会看到聊天流里
     * 凭空多出一条一模一样的拦截记录。
     */
    private var lastBlockNotifiedPackage: String? = null

    /**
     * 窗口 id → 包名。
     *
     * 扫描窗口时首选 `window.root?.packageName`，但那个值不总能拿到（窗口刚出现、
     * 应用无响应、系统虚拟窗口都会返回 null），而事件里的 `packageName` 是可靠的。
     * 把见到过的对应关系记下来兜底。
     *
     * 只在主线程读写（无障碍回调与 Handler 都在主线程），所以不需要加锁。
     */
    private val packageByWindowId = mutableMapOf<Int, String>()

    /** 窗口扫描的去抖，见 [scheduleWindowScan]。 */
    private val mainHandler = Handler(Looper.getMainLooper())
    private val windowScan = Runnable { evaluateWindows() }

    override fun onCreate() {
        super.onCreate()
        // 容器由 Application.onCreate 建好，此时一定已就绪。
        val container = application.appContainer
        policy = container.policy
        lockOverlay = container.lockOverlay
        timeline = container.timeline
    }

    /**
     * 服务被系统连接（用户开启开关，或系统重新拉起服务）。
     *
     * 这里是唯一可靠的「初始化运行期状态」时机 —— 服务实例可能是新的，但系统状态
     * 不是，所以每次都把去重状态清空并重新判定一次当前前台应用。
     */
    override fun onServiceConnected() {
        super.onServiceConnected()

        lastEvaluatedPackage = null
        lastBlockNotifiedPackage = null
        hasWarnedMissingOverlayPermission = false
        packageByWindowId.clear()

        // 把「截屏」这项能力挂到全局通道上，供注视监控取用。
        // 两者生命周期不同（无障碍服务会被系统随时重建），所以只传能力、不传实例。
        ScreenCaptureProvider.capture = { captureScreen() }

        Log.i(TAG, "无障碍服务已连接")
        policy.publishNotice("无障碍服务已连接，开始监督前台应用")

        // 白名单变化时立刻重新判定当前前台应用。
        // 少了这一段，「AI 批准了 10 分钟豁免」要等用户手动切一次应用才生效 ——
        // 而用户此刻正盯着遮罩，他根本没有机会去切。
        serviceScope.launch {
            policy.whitelist.collect {
                val current = lastEvaluatedPackage ?: return@collect
                evaluate(current, publishBlockNotice = false)
            }
        }

        // 服务可能是在用户已经打开某个应用之后才被开启的。系统不会为「开启之前」
        // 的窗口变化补发事件，所以主动查一次当前前台窗口。
        evaluateActiveWindow()
    }

    /**
     * 核心回调：系统推送的窗口事件。
     *
     * 现在只关心 `TYPE_WINDOW_STATE_CHANGED`（换了窗口/页面）。刻意不监听
     * `typeWindowContentChanged`：它在滚动列表时会高频触发，拿它判断应用切换既不准
     * 也很耗电。
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 系统在服务即将断开时可能给 null。
        val safeEvent = event ?: return

        // 记下「窗口 → 包名」。窗口的 root 节点不一定随时可取（刚出现、应用无响应、
        // 系统虚拟窗口），拿到过就先记住，扫描窗口时当作兜底。
        val windowId = safeEvent.windowId
        safeEvent.packageName?.toString()
            ?.takeIf { it.isNotEmpty() && windowId >= 0 }
            ?.let { packageByWindowId[windowId] = it }

        // -------------------------------------------------------------------
        // 窗口集合本身的变化：小窗 / 分屏 / 画中画 走这条
        // -------------------------------------------------------------------
        // 这是之前漏掉的一整类事件。它的 packageName 常常是空的或者不相干的，
        // 而真正需要知道的是「屏幕上现在有哪些窗口」—— 按「前台是谁」判断，
        // 一旦用户把被拦应用缩成小窗，前台就变成了桌面（白名单），
        // 遮罩自己就撤了，而小窗还在播。
        if (safeEvent.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            scheduleWindowScan()
            return
        }

        if (safeEvent.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val packageName = safeEvent.packageName
            ?.toString()
            ?.takeIf { it.isNotEmpty() }
            ?: return

        // -------------------------------------------------------------------
        // 必须区分「自己的窗口事件」里的两种情况，否则拦截会彻底失效
        // -------------------------------------------------------------------
        // 我们用 WindowManager.addView 挂上去的遮罩窗口，本身就属于本应用，因此
        // 挂上去和撤下来都会触发一条 packageName == 本应用的窗口事件。
        //
        // 如果对这条事件照常做判定，结论会是「前台是本应用 → 本应用在白名单里 →
        // 放行」，于是遮罩刚挂上就被自己撤掉 —— 用户看到的是一闪而过的黑屏，
        // 拦截形同虚设。
        //
        // 判据是 className：
        //   - 等于本应用 Activity 的类名 → 用户真的打开了监督界面，正常判定；
        //   - 其它（典型是我们的遮罩根视图 FrameLayout、或者应用内的弹窗窗口）
        //     → 不是一次「应用切换」，直接忽略。
        //
        // 这个判断的失败方向是**安全的**：万一某个 ROM 把 Activity 窗口的 className
        // 报成了别的值，后果只是「用户切回本应用时遮罩没被自动撤掉」，而用户依然
        // 可以按返回键回桌面。反过来若不做这层过滤，代价是拦截完全失效。
        if (packageName == this.packageName && safeEvent.className?.toString() != SELF_ACTIVITY_CLASS) {
            return
        }

        // -------------------------------------------------------------------
        // 过客型系统窗口：不允许改变锁定状态
        // -------------------------------------------------------------------
        // 状态栏、通知栏、音量面板、权限弹窗、厂商安全中心的「遮挡检测」提示……
        // 这些包都在白名单里，但它们弹出时用户其实**还待在被拦的应用里**。
        // 如果照常判定，结论会是「前台变成了白名单应用 → 放行」，遮罩自己掉下来；
        // 等弹窗消失、被拦应用重新发一次窗口事件，遮罩又盖上 —— 用户看到的就是
        // 「锁一下、自己掉了、过一会又锁上」。
        //
        // 所以这类事件在这里就掐掉，既不解锁也不上锁。
        if (packageName in SystemWhitelist.TRANSIENT_WINDOW_PACKAGES) return

        // 键盘同理：输入法弹出来会发一条窗口事件，但用户根本没换应用。
        // 输入法是从系统动态解析出来的（用户启用的每一套都要算），所以不能只靠
        // 上面那份静态清单。
        if (policy.isInputMethod(packageName)) return

        evaluate(packageName, publishBlockNotice = true)
    }

    /**
     * 返回键拦截。
     *
     * 这是「不可绕过」的最后一块拼图：遮罩本身不提供任何按钮，用户唯一的出路就是
     * 回桌面。如果直接放行返回键，用户会在遮罩底下盲操作被拦的应用（返回上一层、
     * 关掉弹窗），遮罩就退化成了一个「看不见的皮肤」。
     *
     * 为什么 DOWN 和 UP 都要吞：只吞 UP 的话，DOWN 会先被下层应用收到，某些应用
     * 在 ACTION_DOWN 上就触发了返回逻辑。
     *
     * 需要 `android:canRequestFilterKeyEvents="true"`，见
     * `res/xml/accessibility_service_config.xml`。少数定制 ROM 会限制按键过滤，
     * 那种情况下返回键会落到下层应用手里 —— 但用户仍然可以按 Home 键（系统级，
     * 应用无法拦截）回到桌面，兜底路径始终存在。
     */
    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_BACK) return super.onKeyEvent(event)
        if (!lockOverlay.isShowing.value) return super.onKeyEvent(event)

        if (event.action == KeyEvent.ACTION_UP) {
            val moved = performGlobalAction(GLOBAL_ACTION_HOME)
            Log.i(TAG, "遮罩期间按下返回键，强制回桌面：$moved")
            // 回桌面之后前台会变成 Launcher，下一个窗口事件会重新判定并撤掉遮罩。
            // 这里先清掉去重状态，保证那次判定一定会被执行。
            lastEvaluatedPackage = null
        }
        return true
    }

    /**
     * 服务被系统要求中断（例如用户临时关闭无障碍）。
     */
    override fun onInterrupt() {
        Log.i(TAG, "无障碍服务被中断")
    }

    /**
     * 服务被解绑（用户关闭开关）。
     *
     * 这里就要撤掉遮罩，不能等到 `onDestroy` —— 两者之间可能隔着一段不短的时间，
     * 而这段时间里用户面对的是一块点不动的黑屏。
     */
    override fun onUnbind(intent: Intent?): Boolean {
        lockOverlay.dismiss()
        lastEvaluatedPackage = null
        lastBlockNotifiedPackage = null
        mainHandler.removeCallbacks(windowScan)
        packageByWindowId.clear()
        ScreenCaptureProvider.capture = null
        Log.i(TAG, "无障碍服务已解绑")
        return super.onUnbind(intent)
    }

    /**
     * 服务实例被销毁。
     *
     * **这里是整个应用最重要的一个安全点**：如果服务消失而遮罩还在，用户会面对一块
     * 再也解除不掉的黑屏，只能长按电源键强制重启。所以无论走哪条销毁路径，都要
     * 无条件撤掉遮罩。
     */
    override fun onDestroy() {
        lockOverlay.dismiss()
        lastEvaluatedPackage = null
        lastBlockNotifiedPackage = null
        mainHandler.removeCallbacks(windowScan)
        packageByWindowId.clear()
        ScreenCaptureProvider.capture = null
        serviceScope.cancel()
        Log.i(TAG, "无障碍服务已销毁")
        super.onDestroy()
    }

    // -----------------------------------------------------------------------
    // 截屏能力
    // -----------------------------------------------------------------------

    /**
     * 截取当前屏幕，返回压缩后的 JPEG 字节；拿不到就返回 null。
     *
     * ===========================================================================
     * 为什么用无障碍的 takeScreenshot 而不是 MediaProjection
     * ===========================================================================
     * `MediaProjection` 每次都要用户点一次系统授权弹窗，而且 Android 14 起每个会话
     * 都得重新授权 —— 那种东西没法用在「后台自动看一眼」的场景里。
     * 无障碍服务的 `takeScreenshot`（API 30+）在服务已启用的前提下直接可用，
     * 代价是系统会限制调用频率（间隔太短会回调
     * `ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT`），而那正好也是一层保护。
     *
     * 低于 Android 11 时返回 null —— 注视监控照常记录「在看 / 没在看」，
     * 只是不发截图。功能降级，而不是功能失效。
     */
    private suspend fun captureScreen(): ByteArray? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null

        return suspendCancellableCoroutine { continuation ->
            try {
                takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    ContextCompat.getMainExecutor(this),
                    object : TakeScreenshotCallback {
                        override fun onSuccess(screenshot: ScreenshotResult) {
                            val bytes = runCatching { screenshot.toJpegBytes() }
                                .onFailure { Log.w(TAG, "截图转码失败", it) }
                                .getOrNull()
                            // HardwareBuffer 是有限资源，必须显式释放。
                            runCatching { screenshot.hardwareBuffer.close() }
                            if (continuation.isActive) continuation.resume(bytes)
                        }

                        override fun onFailure(errorCode: Int) {
                            Log.w(TAG, "截屏失败，错误码 $errorCode")
                            if (continuation.isActive) continuation.resume(null)
                        }
                    },
                )
            } catch (t: Throwable) {
                // 少数 ROM 会直接抛（例如服务刚重建、权限被回收）。
                Log.w(TAG, "截屏调用异常", t)
                if (continuation.isActive) continuation.resume(null)
            }
        }
    }

    /**
     * 把系统给的硬件位图压成一张小 JPEG。
     *
     * 三步都是必要的：
     *  1. `wrapHardwareBuffer` 得到的是**只读**的硬件位图，不能直接压缩，必须先 copy；
     *  2. 缩到 [SCREENSHOT_MAX_WIDTH] 宽 —— 这一步决定了发出去的 token 数量，
     *     比任何提示词技巧都省得多；
     *  3. JPEG 质量 70：手机截图以文字为主，再高只是白白增加体积。
     */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun ScreenshotResult.toJpegBytes(): ByteArray? {        val hardware = Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace) ?: return null
        val software = hardware.copy(Bitmap.Config.ARGB_8888, false)
        hardware.recycle()
        if (software == null) return null

        val scaled = if (software.width > SCREENSHOT_MAX_WIDTH) {
            val height = software.height * SCREENSHOT_MAX_WIDTH / software.width
            Bitmap.createScaledBitmap(software, SCREENSHOT_MAX_WIDTH, height, true)
                .also { software.recycle() }
        } else {
            software
        }

        return ByteArrayOutputStream().use { stream ->
            scaled.compress(Bitmap.CompressFormat.JPEG, SCREENSHOT_QUALITY, stream)
            scaled.recycle()
            stream.toByteArray()
        }
    }

    // -----------------------------------------------------------------------
    // 判定与执行
    // -----------------------------------------------------------------------

    /**
     * 延迟一小会儿再扫描窗口。
     *
     * 进出小窗/分屏时系统会连着发好几条窗口变化事件（动画期间 bounds 一直在动），
     * 每次都去 `getWindows()` 既浪费又会让判定在动画中间来回抖。250ms 足够越过
     * 动画，又快到用户感觉不出延迟。
     */
    private fun scheduleWindowScan() {
        mainHandler.removeCallbacks(windowScan)
        mainHandler.postDelayed(windowScan, WINDOW_SCAN_DELAY_MILLIS)
    }

    /**
     * 扫描当前所有窗口，决定该锁还是该放。
     *
     * 与 [evaluateActiveWindow] 的区别：那个问「前台是谁」，这个问「屏幕上有没有
     * 不该看的窗口」。小窗、分屏、画中画这三种形态下，「前台」和「屏幕上有什么」
     * 是两回事 —— 前台可能是桌面，而小窗里还开着被拦的应用。
     */
    private fun evaluateWindows() {
        // 测试遮罩盖的是本应用自己，撤不撤由 runLockTest 的定时器决定，
        // 这里必须绕开：否则一有窗口变化就会被误撤，5 秒测试变成一闪而过。
        if (lockOverlay.isShowing.value && lockOverlay.lockedPackage.value == this.packageName) return

        val offender = findBlockedWindow()
        if (offender != null) {
            evaluate(offender, publishBlockNotice = true)
            return
        }

        // 扫描没找到任何违规窗口 —— 这是比「问前台是谁」更硬的证据：
        // 前台可能查不到（正在切换、锁屏），也可能正好是桌面。
        if (lockOverlay.isShowing.value) {
            lockOverlay.dismiss()
            lastEvaluatedPackage = null
            lastBlockNotifiedPackage = null
        }
    }

    /**
     * 返回一个「此刻真的显示在屏幕上、且不该看」的应用包名；没有就返回 null。
     *
     * 判据全部取自窗口本身，不看前台是谁 —— 这正是小窗能拦住的原理。
     */
    private fun findBlockedWindow(): String? {
        val windowList = try {
            windows
        } catch (t: Throwable) {
            // 少数 ROM 在服务刚连接时会抛，放弃本次扫描即可，后面的窗口事件会补上。
            Log.w(TAG, "读取窗口列表失败", t)
            null
        } ?: return null

        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels

        for (window in windowList) {
            if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue

            // 空矩形 = 这个窗口在无障碍看来没有任何可交互区域，也就是没显示在屏幕上。
            // 完全退到后台的应用、只剩最近任务里的快照，都会走到这里被排除。
            val bounds = Rect()
            window.getBoundsInScreen(bounds)
            if (bounds.isEmpty) continue
            if (bounds.width() < MIN_WINDOW_SIDE_PX || bounds.height() < MIN_WINDOW_SIDE_PX) continue
            if (!isBlockableWindow(window, bounds, screenWidth, screenHeight)) continue

            val pkg = window.root?.packageName?.toString()?.takeIf { it.isNotEmpty() }
                ?: packageByWindowId[window.id]
                ?: continue

            if (pkg == this.packageName) continue
            if (pkg in SystemWhitelist.TRANSIENT_WINDOW_PACKAGES) continue
            if (policy.isInputMethod(pkg)) continue
            if (policy.isAppWhitelisted(pkg)) continue

            return pkg
        }

        return null
    }

    /**
     * 这个窗口值不值得为它盖一整屏遮罩。
     *
     * 为什么要这么绕：`TYPE_APPLICATION` 里混着一些**不是应用界面**的东西 ——
     * 别的应用弹的 Toast 也会被系统映射成 `TYPE_APPLICATION`。如果只按
     * 「非白名单就拦」，别人弹一条提示就能让用户满屏黑一次。
     *
     * 所以要求下面三条里至少满足一条：
     *  - 它在画中画里（小窗；这种窗口通常既不 active 也不 focused，必须单独认）；
     *  - 它拿到了焦点或处于活动状态（正常前台、分屏里被操作的那一侧）；
     *  - 它在某一维上至少占了半屏（分屏的另一侧、自由窗口里较大的那些）。
     *
     * Toast 又小又不抢焦点，三条都不满足，于是被排除。
     */
    private fun isBlockableWindow(
        window: AccessibilityWindowInfo,
        bounds: Rect,
        screenWidth: Int,
        screenHeight: Int,
    ): Boolean {
        if (window.isInPictureInPictureMode()) return true
        if (window.isFocused() || window.isActive()) return true
        return bounds.width() >= screenWidth / 2 || bounds.height() >= screenHeight / 2
    }

    /**
     * 询问系统「现在前台是谁」并立即判定一次。
     *
     * `rootInActiveWindow` 需要清单里的 `canRetrieveWindowContent="true"`，
     * 在某些时刻（正在切换、锁屏）会返回 null，此时放弃本次判定即可 ——
     * 紧随其后的窗口事件会补上。
     */
    private fun evaluateActiveWindow() {
        val packageName = rootInActiveWindow
            ?.packageName
            ?.toString()
            ?.takeIf { it.isNotEmpty() }
            ?: return

        evaluate(packageName, publishBlockNotice = true)
    }

    /**
     * 对一个前台包名执行完整判定。
     *
     * @param packageName 当前前台应用包名
     * @param publishBlockNotice 是否允许产生「已压制」播报。白名单变化引发的**复判**
     *        要传 false：那种情况下用户什么都没做，不该凭空多出一条播报。
     */
    private fun evaluate(packageName: String, publishBlockNotice: Boolean) {
        // 判定是同步的，这是整条链路能跟手的前提。
        val whitelisted = policy.isAppWhitelisted(packageName)

        if (whitelisted) {
            // -----------------------------------------------------------------
            // 先确认屏幕上没有**别的**违规窗口
            // -----------------------------------------------------------------
            // 「刚发生事件的这个包」在白名单里，只说明它自己没问题，不代表屏幕上没有
            // 别的。用户把被拦的应用缩成小窗时，前台会变成桌面，而桌面在白名单里 ——
            // 只看前台就等于把小窗放跑了（这正是「一开小窗遮罩就解除」的原因）。
            val offender = findBlockedWindow()
            if (offender != null) {
                if (!lockOverlay.isShowing.value || lockOverlay.lockedPackage.value != offender) {
                    showLockFor(offender, publishBlockNotice)
                }
                return
            }

            // -----------------------------------------------------------------
            // 关键：撤遮罩之前，先确认「用户真的已经不在被拦的应用里了」
            // -----------------------------------------------------------------
            // 分两种情况，不能混为一谈：
            //
            //   A. 事件里的包名**就是**被遮罩盖住的那个应用
            //      → 它刚刚变成白名单（AI 批准了豁免），用户还停在这个应用里，
            //        此时**必须**立刻解锁。这一条不能拦。
            //
            //   B. 事件里的包名是**另一个**白名单应用
            //      → 可能只是状态栏、键盘、某个厂商弹窗冒了一下（它们的包名也都在
            //        白名单里）。这时要再问系统一次：当前**输入焦点所在**的窗口是谁。
            //        我们的遮罩带 FLAG_NOT_FOCUSABLE 不抢焦点，所以只要焦点还在被拦
            //        应用上，就说明用户其实没走，那条事件是噪音，不许撤遮罩。
            //
            // 少了 B 这道闸，遮罩会在没有任何用户操作的情况下自己掉下来，等系统
            // 弹窗消失、被拦应用重新发一次窗口事件，遮罩又盖上 —— 表现出来就是
            // 「锁一下、自己掉了、过一会又锁上」。
            val lockedPackage = lockOverlay.lockedPackage.value
            val noiseFromOtherPackage = lockOverlay.isShowing.value &&
                packageName != lockedPackage &&
                stillFocusedOnLockedApp()

            if (noiseFromOtherPackage) return

            if (lockOverlay.isShowing.value) {
                lockOverlay.dismiss()
            }
            lastEvaluatedPackage = packageName
            lastBlockNotifiedPackage = null
            return
        }

        showLockFor(packageName, publishBlockNotice)
    }

    /**
     * 真正的压制动作：盖上遮罩，必要时播报一次。
     *
     * 单独抽出来是因为它有两个入口：常规的「前台是非白名单应用」，以及
     * 「前台是白名单应用、但屏幕上还有别的小窗」。两条路共用同一套去重与播报规则。
     */
    private fun showLockFor(packageName: String, publishBlockNotice: Boolean) {
        // 同一个被拦应用、遮罩也还在，就没有任何事要做。
        // 少了这一句，同一次拦截会被反复 show()，虽然 show() 本身幂等，但播报会刷屏。
        if (packageName == lastEvaluatedPackage && lockOverlay.isShowing.value) return

        lastEvaluatedPackage = packageName

        val shown = lockOverlay.show(packageName)
        if (!shown) {
            // 拦不住的原因几乎总是缺悬浮窗权限。这条播报是用户能看到的唯一线索，
            // 因此必须有，且只提醒一次。
            if (!hasWarnedMissingOverlayPermission) {
                hasWarnedMissingOverlayPermission = true
                policy.publishNotice("拦截失败：缺少悬浮窗权限，无法压制 $packageName")
                Log.w(TAG, "遮罩未能拉起，缺少悬浮窗权限")
            }
            return
        }

        if (publishBlockNotice && packageName != lastBlockNotifiedPackage) {
            lastBlockNotifiedPackage = packageName
            policy.publishNotice("启动未受豁免应用 [$packageName]，已执行压制")

            // 拦截是监督里最重要的证据，必须进时间线（带时间），AI 才可能在对话里
            // 说出「你今天已经被拦了七次」这种话。
            //
            // 这条分支每个应用每次连续锁定只走一次（上面的去重），而且走到这里时遮罩
            // 已经盖上了，所以顺手解析一次应用名（内部有缓存）不会让用户看到目标应用。
            timeline.record(
                kind = TimelineKind.APP_BLOCKED,
                title = policy.resolveAppLabel(packageName),
                detail = packageName,
            )
        }
    }

    /**
     * 当前输入焦点是否仍然停在「被遮罩盖住的那个应用」上。
     *
     * 这是判断「用户到底走没走」的第二道闸。返回 true 表示**不该撤遮罩**。
     *
     * `rootInActiveWindow` 的取值时机不完全可控，因此三条分支全部朝「保守」的方向写：
     *  - 查不到活动窗口  → true（宁可多锁一会儿，也不要闪一下）；
     *  - 还是被拦的那个应用 → true（用户没走，刚才那条事件是噪音）；
     *  - 活动窗口是「我们自己、但不是监督界面」→ true（那是我们自己的遮罩窗口）；
     *  - 其余 → false（用户确实切走了，放行）。
     *
     * 这个判断的代价是一次进程内的窗口查询，不涉及跨进程遍历节点，可以承受。
     */
    private fun stillFocusedOnLockedApp(): Boolean {
        val lockedPackage = lockOverlay.lockedPackage.value ?: return false
        val root = rootInActiveWindow ?: return true
        val activePackage = root.packageName?.toString().orEmpty()

        return when {
            activePackage.isEmpty() -> true
            activePackage == lockedPackage -> true
            activePackage == this.packageName &&
                root.className?.toString() != SELF_ACTIVITY_CLASS -> true
            else -> false
        }
    }

    companion object {
        private const val TAG = "FocusAccessibility"

        /**
         * 窗口扫描的去抖延迟。
         *
         * 进出小窗/分屏时系统会连发好几条窗口变化事件（动画期间 bounds 一直在动），
         * 250ms 足够越过动画，又快到用户感觉不出延迟。
         */
        private const val WINDOW_SCAN_DELAY_MILLIS = 250L

        /**
         * 小于这个边长的窗口直接忽略（单位：像素）。
         *
         * 用来滤掉分割条一类的装饰窗口。别设得太大 —— 画中画小窗在某些机器上
         * 只有一百多像素宽，滤掉了就正好漏掉要拦的那一个。
         */
        private const val MIN_WINDOW_SIDE_PX = 24

        /**
         * 截图缩放后的最大宽度（像素）。
         *
         * 720 宽时模型基本读不清界面上的文字，只能说出「他在刷一个信息流」这种
         * 没用的结论 —— 截图的意义就在于让 AI 知道**他在具体做什么**，读不到字
         * 等于白传。1080 是「文字可读」与「体积可控」的平衡点：一张手机截图
         * 压完约 150~250 KB，而上传本身有 3 分钟的冷却，这个量级完全承受得起。
         */
        private const val SCREENSHOT_MAX_WIDTH = 1080

        /**
         * JPEG 压缩质量。
         *
         * 80 而不是 70：手机截图以文字与细线为主，正是 JPEG 最容易出块效应的地方，
         * 70 会把小字糊掉。多出来的二三十 KB 换模型能看清内容，划算。
         */
        private const val SCREENSHOT_QUALITY = 80

        /**
         * 本应用唯一 Activity 的类名。
         *
         * 用来把「用户打开了监督界面」与「我们自己挂了个窗口」区分开，理由见
         * [onAccessibilityEvent]。用 `::class.java.name` 而不是字符串字面量：
         * 改了类名或挪了包，这里会跟着变，不会静默失效。
         */
        private val SELF_ACTIVITY_CLASS: String = MainActivity::class.java.name

        /**
         * 查询本应用的无障碍服务当前是否处于开启状态。
         *
         * 用途：「+」面板的权限红点与权限检查对话框都要用它。
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
