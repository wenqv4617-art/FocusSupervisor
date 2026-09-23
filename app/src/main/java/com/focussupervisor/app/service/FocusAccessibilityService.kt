package com.focussupervisor.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import com.focussupervisor.app.MainActivity
import com.focussupervisor.app.appContainer
import com.focussupervisor.app.data.repository.AppPolicyRepository
import com.focussupervisor.app.ui.overlay.LockOverlayController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

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

    override fun onCreate() {
        super.onCreate()
        // 容器由 Application.onCreate 建好，此时一定已就绪。
        val container = application.appContainer
        policy = container.policy
        lockOverlay = container.lockOverlay
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
        hasWarnedMissingOverlayPermission = false

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
        serviceScope.cancel()
        Log.i(TAG, "无障碍服务已销毁")
        super.onDestroy()
    }

    // -----------------------------------------------------------------------
    // 判定与执行
    // -----------------------------------------------------------------------

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
            // 放行。注意这里必须处理「上一个应用留下的遮罩」：用户按返回键回桌面时，
            // 遮罩是靠这次判定撤掉的。
            if (lockOverlay.isShowing.value) {
                lockOverlay.dismiss()
            }
            lastEvaluatedPackage = packageName
            return
        }

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

        if (publishBlockNotice) {
            policy.publishNotice("启动未受豁免应用 [$packageName]，已执行压制")
        }
    }

    companion object {
        private const val TAG = "FocusAccessibility"

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
