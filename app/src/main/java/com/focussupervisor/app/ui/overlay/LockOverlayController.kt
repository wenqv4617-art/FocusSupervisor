package com.focussupervisor.app.ui.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.ui.graphics.toArgb
import com.focussupervisor.app.ui.theme.Accent
import com.focussupervisor.app.ui.theme.OverlayBackground
import com.focussupervisor.app.ui.theme.OverlayHeadline
import com.focussupervisor.app.ui.theme.OverlayHint
import com.focussupervisor.app.ui.theme.OverlayMeta
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 全屏防沉迷遮罩。
 *
 * ===========================================================================
 * 它为什么必须是系统级悬浮窗，而不是应用内的一个全屏 Activity
 * ===========================================================================
 * 因为要盖住的不是「我们自己的界面」，而是**别人家的界面**。用户点开某个被拦下的
 * 应用时，那个应用已经在前台跑起来了；只有 `TYPE_APPLICATION_OVERLAY` 这种由
 * WindowManager 直接插入的窗口，才能盖在任意第三方应用之上。
 *
 * ===========================================================================
 * 不可绕过性是怎么实现的
 * ===========================================================================
 *  1. **吞掉所有触摸**：根视图 clickable 且显式返回消费。窗口没有
 *     `FLAG_NOT_TOUCHABLE`，因此触摸落在这里，不会下传到被盖住的应用。
 *  2. **Android 12 的 untrusted touch 保护站在我们这边**：系统会拦截「穿过不透明
 *     悬浮窗」的触摸事件。我们的底色是纯 #0A0A0A，完全不透明，穿透路径被系统
 *     自己掐死。
 *  3. **不提供任何解锁控件**：界面上没有按钮、没有可点区域，只有文字。想解除只有
 *     两条路 —— 代码调用 [dismiss]，或者用户按返回键被无障碍服务送回桌面。
 *  4. **吞掉返回键之外的一切**：窗口带 `FLAG_NOT_FOCUSABLE`，不抢输入焦点，因此
 *     既不会被输入法顶掉，也不会把返回键吃掉（返回键由无障碍服务统一处理，见
 *     `FocusAccessibilityService.onKeyEvent`）。
 *
 * ===========================================================================
 * 线程与生命周期
 * ===========================================================================
 * `WindowManager.addView` / `removeView` 必须在主线程调用，本类内部统一用
 * [mainHandler] 兜底，任何线程调用都安全。[show] / [dismiss] 都是幂等的 ——
 * 无障碍服务、界面上的测试入口可能同时操作它，重复调用不会叠加出两个遮罩，
 * 也不会因为重复移除而抛异常。
 */
class LockOverlayController(context: Context) {

    private val appContext: Context = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val windowManager: WindowManager? =
        appContext.getSystemService(WindowManager::class.java)

    private val _isShowing = MutableStateFlow(false)

    /** 遮罩当前是否挂在屏幕上。界面可以据此显示状态，但不要用它做判定依据。 */
    val isShowing: StateFlow<Boolean> = _isShowing.asStateFlow()

    private val _lockedPackage = MutableStateFlow<String?>(null)

    /** 当前被遮罩盖住的应用包名；没有遮罩时为 null。 */
    val lockedPackage: StateFlow<String?> = _lockedPackage.asStateFlow()

    private var overlayView: View? = null
    private var headlineView: TextView? = null
    private var metaView: TextView? = null

    /** 悬浮窗权限是否已授予。没有它 [show] 一定失败。 */
    val isPermissionGranted: Boolean
        get() = Settings.canDrawOverlays(appContext)

    /**
     * 拉起遮罩。
     *
     * @param packageName 被拦下的应用包名，会显示在提示文字里。
     * @param headline 主提示语。默认是正式拦截的说法；「强制锁定测试」会传自己的文案，
     *        让用户分得清「真的被拦了」和「我在试功能」。
     * @return true 表示遮罩此刻已在屏幕上；false 表示没有权限或添加失败。
     */
    fun show(
        packageName: String,
        headline: String = DEFAULT_HEADLINE,
    ): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { show(packageName, headline) }
            return false
        }

        if (!isPermissionGranted) {
            Log.w(TAG, "缺少悬浮窗权限，遮罩无法拉起")
            return false
        }

        val existing = overlayView
        if (existing != null) {
            // 已经挂着：只更新文案，绝不重复 addView。
            headlineView?.text = headline
            metaView?.text = packageName
            _lockedPackage.value = packageName
            return true
        }

        val manager = windowManager
        if (manager == null) {
            Log.e(TAG, "WindowManager 不可用")
            return false
        }

        val view = buildOverlayView(packageName, headline)
        return try {
            manager.addView(view, buildLayoutParams())
            overlayView = view
            _isShowing.value = true
            _lockedPackage.value = packageName
            Log.i(TAG, "遮罩已拉起：$packageName")
            true
        } catch (t: Throwable) {
            // BadTokenException / IllegalStateException / 厂商定制 ROM 的 SecurityException。
            // 遮罩失败绝不能让调用方崩溃 —— 那会让「拦不住」升级成「用不了」。
            Log.e(TAG, "遮罩拉起失败", t)
            false
        }
    }

    /**
     * 撤下遮罩。幂等：没有遮罩时什么都不做。
     *
     * 用 `removeViewImmediate` 而不是 `removeView`：后者会把移除动作排进下一帧，
     * 在「用户已经回到桌面但遮罩还闪一下」的场景里会看到明显的残影。
     */
    fun dismiss() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { dismiss() }
            return
        }

        val view = overlayView ?: return
        overlayView = null
        headlineView = null
        metaView = null

        try {
            windowManager?.removeViewImmediate(view)
            Log.i(TAG, "遮罩已撤下")
        } catch (t: Throwable) {
            Log.w(TAG, "遮罩撤下失败（可能已被系统移除）", t)
        } finally {
            _isShowing.value = false
            _lockedPackage.value = null
        }
    }

    // -----------------------------------------------------------------------
    // 视图构建
    // -----------------------------------------------------------------------

    /**
     * 遮罩的窗口参数。
     *
     * 逐个 flag 的理由：
     *  - `FLAG_LAYOUT_IN_SCREEN` / `FLAG_LAYOUT_NO_LIMITS`：铺满整块屏幕，包括状态栏
     *    与导航栏底下，不留任何可见缝隙。
     *  - `FLAG_NOT_FOCUSABLE`：不抢输入焦点。既避免把输入法顶掉，也避免返回键被
     *    本窗口吃掉 —— 返回键交给无障碍服务统一处理。
     *  - 刻意**不加** `FLAG_NOT_TOUCHABLE`：那是「让触摸穿过去」的开关，加上去整个
     *    遮罩就形同虚设。
     *
     * 另外把 `layoutInDisplayCutoutMode` 设成允许铺进刘海区，否则在挖孔屏上顶部会
     * 露出一条被盖不住的原应用画面。
     */
    private fun buildLayoutParams(): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            // OPAQUE 而不是 TRANSLUCENT：不需要与下层做 alpha 合成，省一次合成开销，
            // 也让 Android 12 的 untrusted touch 判定走「完全不透明」这条最快路径。
            PixelFormat.OPAQUE,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                    } else {
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                    }
            }
        }

    /**
     * 构建遮罩视图。
     *
     * 用纯 Android View 而不是 Compose：这个视图由 WindowManager 直接持有，没有
     * Activity、没有 ViewTreeLifecycleOwner，套 Compose 需要自己造一整套宿主环境，
     * 收益为零。它的结构只有四个 TextView，用 View 反而更短更稳。
     *
     * 视觉上沿用应用的克制基调：纯黑底、一句主提示、一行包名、底部一句出路说明，
     * 中间一条 2dp 的强调色竖线。没有图标、没有按钮、没有渐变。
     */
    private fun buildOverlayView(packageName: String, headline: String): View {
        val root = FrameLayout(appContext).apply {
            setBackgroundColor(OverlayBackground.toArgb())
            // clickable + 空监听 = 吃掉落在自己身上的所有点击。
            isClickable = true
            setOnClickListener { /* 有意为空 */ }
            // 再兜一层：任何未被消费的触摸序列都在这里被吞掉，绝不外泄。
            setOnTouchListener { _, _ -> true }
            isFocusable = false
        }

        // 用局部变量构建、再赋给字段。
        // 不能直接 column.addView(headlineView, ...) —— headlineView 是可空的类属性，
        // Kotlin 不会对 var 属性做智能转换，那样写会直接编译不过。
        val headlineText = TextView(appContext).apply {
            text = headline
            setTextColor(OverlayHeadline.toArgb())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
            gravity = Gravity.CENTER
            letterSpacing = 0.02f
        }
        headlineView = headlineText

        val metaText = TextView(appContext).apply {
            text = packageName
            setTextColor(OverlayMeta.toArgb())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
        }
        metaView = metaText

        val column = LinearLayout(appContext).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(36f), dp(36f), dp(36f), dp(36f))
        }

        // 一条 2dp 的强调色竖线。整个遮罩上唯一的彩色元素，用来把视线钉在中间。
        column.addView(
            View(appContext).apply { setBackgroundColor(Accent.toArgb()) },
            LinearLayout.LayoutParams(dp(2f), dp(34f)).apply { bottomMargin = dp(28f) },
        )
        column.addView(
            headlineText,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        column.addView(
            metaText,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(14f) },
        )

        root.addView(
            column,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER,
            ),
        )

        // 出路说明。不是按钮 —— 它不可点，只是一个事实陈述。
        root.addView(
            TextView(appContext).apply {
                text = EXIT_HINT
                setTextColor(OverlayHint.toArgb())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                gravity = Gravity.CENTER
            },
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
            ).apply { bottomMargin = dp(76f) },
        )

        return root
    }

    private fun dp(value: Float): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value,
        appContext.resources.displayMetrics,
    ).toInt()

    companion object {
        private const val TAG = "LockOverlay"

        /** 正式拦截时的主提示语。 */
        const val DEFAULT_HEADLINE: String = "检测到非白名单应用 · 处于监管时段"

        /**
         * 出路说明。
         *
         * 必须写出来：一个没有任何按钮的纯黑全屏，如果不告诉用户怎么出去，第一反应
         * 是长按电源键强制重启 —— 那是比「被拦一下」严重得多的体验事故。
         */
        private const val EXIT_HINT: String = "按返回键回到桌面"
    }
}
