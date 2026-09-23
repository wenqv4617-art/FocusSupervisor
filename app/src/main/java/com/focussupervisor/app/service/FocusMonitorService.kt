package com.focussupervisor.app.service

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ProcessCameraProvider
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.focussupervisor.app.FocusSupervisorApp
import com.focussupervisor.app.MainActivity
import com.focussupervisor.app.R
import com.focussupervisor.app.appContainer
import com.focussupervisor.app.core.network.OpenAiCompatibleClient
import com.focussupervisor.app.core.vision.GazeAnalyzer
import com.focussupervisor.app.core.vision.GazeEstimator
import com.focussupervisor.app.core.vision.GazeReading
import com.focussupervisor.app.core.vision.ScreenCaptureProvider
import com.focussupervisor.app.data.repository.AiConfigRepository
import com.focussupervisor.app.data.repository.AppPolicyRepository
import com.focussupervisor.app.data.repository.GazeRepository
import com.focussupervisor.app.data.repository.TimelineRepository
import com.focussupervisor.app.domain.model.GazeConfig
import com.focussupervisor.app.domain.model.GazeDefaults
import com.focussupervisor.app.domain.model.GazeState
import com.focussupervisor.app.domain.model.TimelineKind
import com.focussupervisor.app.domain.model.VisionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

/**
 * 注视监控的宿主服务。
 *
 * ===========================================================================
 * 为什么必须是前台服务（而不是挂在无障碍服务里）
 * ===========================================================================
 * 从 Android 9 起，**处于后台的应用不能访问摄像头**。唯一的例外是「带着 camera
 * 类型前台服务运行」—— 那种情况下进程被系统视为前台，摄像头才拿得到。而且这个
 * 前台服务必须**从用户可见的状态启动**（Android 12+ 限制后台启动前台服务），
 * 所以入口只能是用户在界面上打开开关。
 *
 * 换句话说：这个功能要能在后台跑，就必须有一条用户可见的常驻通知。这不是设计
 * 选择，是平台的硬性要求 —— 顺带也符合「摄像头在工作时用户必须能看见」这条常识。
 *
 * ===========================================================================
 * 状态、节流与降级
 * ===========================================================================
 * ```
 *   摄像头帧 ──(抽帧)──▶ ML Kit 人脸 ──▶ GazeEstimator 去抖 ──▶ 状态
 *                                              │
 *                                              ├─ 进入/离开注视：记时间线
 *                                              └─ 进入注视：截屏 → 视觉模型 → 记时间线
 * ```
 * 三处刻意保留的降级路径（都不报错、不崩溃，只是少做一件事）：
 *  1. 没有摄像头权限 → 服务停止，界面上有明确原因；
 *  2. 没配视觉模型 / 无障碍没开 / 系统低于 Android 11 → 拿不到截图，
 *     **仍然记录「在看 / 没在看」**，只是不产生描述；
 *  3. 屏幕熄灭 → 暂停分析（省电，而且黑屏时判「是否在看」没有意义），
 *     亮屏后自动恢复。
 */
class FocusMonitorService : LifecycleService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var gaze: GazeRepository
    private lateinit var aiConfig: AiConfigRepository
    private lateinit var client: OpenAiCompatibleClient
    private lateinit var timeline: TimelineRepository
    private lateinit var policy: AppPolicyRepository

    /** 当前生效的配置。由 [observeConfig] 更新，其余地方只读。 */
    private var configNow = GazeConfig()

    /**
     * 判定状态机。
     *
     * 配置用 lambda 传进去而不是传值：用户改了抽帧间隔或容差之后，下一次判定就该
     * 用新值，不需要重建状态机（重建会把「连续注视了 1.2 秒」这种进度清零）。
     */
    private val estimator = GazeEstimator(config = { configNow })

    /**
     * 分析用的单线程执行器。
     *
     * 单线程是刻意的：人脸检测是 CPU 密集型，多线程只会让几帧同时抢算力、
     * 又都要排队，反而更慢更热。
     */
    private val analysisExecutor = Executors.newSingleThreadExecutor()

    private var cameraProvider: ProcessCameraProvider? = null
    private var analyzer: GazeAnalyzer? = null
    private var cameraRunning = false
    private var screenOn = true

    /** 上一次往时间线写「开始注视」的时间。用于限制频率，见 [onReading]。 */
    private var lastLookingEventMillis = 0L

    /** 本次注视会话的开始时间。用于「看了多久」以及决定值不值得记录。 */
    private var lookingStartedAtMillis = 0L

    /**
     * 本次运行以来累计的注视时长。
     *
     * 不落盘，进程重启即归零 —— 理由见 [VisionStatus] 的注释：精确到秒的跨重启统计
     * 需要一套后台计时与持久化，而它带来的判断增量很小；真正需要长期记住的
     * 「他今天看了很久」已经由时间线上的里程碑事件承担了。
     */
    private var todayFocusMillis = 0L

    /** 下一个还没触发过的疲劳里程碑下标。每一轮连续注视开始时归零。 */
    private var nextMilestoneIndex = 0

    /**
     * 是否需要播报一次「已启动」。
     *
     * 只在**摄像头真的绑上了**之后才播报，而不是在用户点开关的那一刻：
     * 摄像头可能被别的应用占用、设备可能没有前置摄像头，那时播报「已启动」
     * 就是在骗人。屏幕点亮导致的重新绑定不会置这个位，所以也不会重复播报。
     */
    private var pendingStartAnnouncement = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    screenOn = false
                    // 黑屏时判「是否在看屏幕」没有意义，而且摄像头白开着最费电。
                    stopCamera()
                    publishStatus(GazeState.OFF)
                }

                Intent.ACTION_SCREEN_ON -> {
                    screenOn = true
                    startCameraIfNeeded()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

        val container = application.appContainer
        gaze = container.gaze
        aiConfig = container.aiConfig
        client = container.openAiClient
        timeline = container.timeline
        policy = container.policy

        if (!promoteToForeground()) {
            // 进不了前台态就没有存在的意义，直接自我了断。
            stopSelf()
            return
        }

        ContextCompat.registerReceiver(
            this,
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        observeConfig()
    }

    /**
     * 每次 `startService` 都会走到这里。
     *
     * 返回 `START_NOT_STICKY` 而不是 `START_STICKY`：这个服务持有摄像头，
     * 让系统在杀进程后自动把它拉起来、在后台悄悄开摄像头，是绝对不能接受的行为。
     * 需要恢复监督时，应该由用户主动打开应用或由前台可见的入口触发。
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (!promoteToForeground()) {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopCamera()
        runCatching { unregisterReceiver(screenReceiver) }
        analysisExecutor.shutdown()
        publishStatus(GazeState.OFF)
        serviceScope.cancel()
        Log.i(TAG, "注视监控已停止")
        super.onDestroy()
    }

    // -----------------------------------------------------------------------
    // 配置 → 摄像头
    // -----------------------------------------------------------------------

    /**
     * 跟随配置开关摄像头。
     *
     * 开关的真正权威是 DataStore 里的配置，而不是「服务有没有在跑」：用户在界面上
     * 关掉开关时，服务可能正忙着，靠这条收集来停掉自己是唯一可靠的做法。
     */
    private fun observeConfig() {
        serviceScope.launch {
            gaze.config.collect { config ->
                // null = 磁盘还没读出来，此刻「开还是关」是未知的。
                // 把它当成「关」会让服务刚起来就自杀，所以直接忽略这一次。
                if (config == null) return@collect

                val wasEnabled = configNow.enabled
                configNow = config

                if (!config.enabled) {
                    stopCamera()
                    publishStatus(GazeState.OFF)
                    stopSelf()
                    return@collect
                }

                if (!wasEnabled) {
                    estimator.reset(GazeState.STARTING)
                    publishStatus(GazeState.STARTING)
                    // 真的绑上摄像头之后再播报，见 pendingStartAnnouncement 的注释。
                    pendingStartAnnouncement = true
                    startCameraIfNeeded()
                }
            }
        }
    }

    private fun startCameraIfNeeded() {
        if (cameraRunning || !screenOn || !configNow.enabled) return

        if (!hasCameraPermission()) {
            gaze.publishError("缺少摄像头权限，注视监控无法启动")
            publishStatus(GazeState.ERROR)
            return
        }

        val future = ProcessCameraProvider.getInstance(this)
        future.addListener(
            {
                try {
                    val provider = future.get()
                    cameraProvider = provider

                    val analysis = ImageAnalysis.Builder()
                        // 只保留最新一帧：处理不过来时**丢旧的**，而不是排队。
                        // 排队会让判定结果滞后好几秒，那对监督毫无意义。
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        // 明确要一个**很低**的分辨率。人脸检测在 640x480 上的准确率
                        // 与 1080p 几乎没有差别，但每帧的解码、色彩转换与推理代价差
                        // 好几倍 —— 而这是要连续跑几个小时的服务。
                        .setResolutionSelector(
                            ResolutionSelector.Builder()
                                .setResolutionStrategy(
                                    ResolutionStrategy(
                                        ANALYSIS_RESOLUTION,
                                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                                    ),
                                )
                                .build(),
                        )
                        .build()

                    val gazeAnalyzer = GazeAnalyzer(
                        configProvider = { configNow },
                        estimator = estimator,
                        onReading = ::onReading,
                        onFailure = { throwable ->
                            gaze.publishError("人脸检测失败：${throwable.javaClass.simpleName}")
                        },
                    )

                    analysis.setAnalyzer(analysisExecutor, gazeAnalyzer)

                    // unbindAll 之后再绑：反复开关功能时，旧用例必须先解绑，
                    // 否则会累积多个分析器，帧被消费两次。
                    provider.unbindAll()
                    provider.bindToLifecycle(
                        this,
                        CameraSelector.DEFAULT_FRONT_CAMERA,
                        analysis,
                    )

                    analyzer = gazeAnalyzer
                    cameraRunning = true
                    gaze.publishError(null)
                    Log.i(TAG, "摄像头已开始分析")

                    if (pendingStartAnnouncement) {
                        pendingStartAnnouncement = false
                        policy.publishNotice(
                            "视觉感知已启动 · 每 ${formatSeconds(configNow.analyzeIntervalMillis)} 秒一帧（低功耗模式）",
                        )
                    }
                } catch (t: Throwable) {
                    // 摄像头被别的应用占用、设备没有前置摄像头、ROM 限制……
                    // 无论哪种，都不能让异常冒出去变成崩溃。
                    Log.e(TAG, "启动摄像头失败", t)
                    gaze.publishError("启动摄像头失败：${t.javaClass.simpleName}")
                    publishStatus(GazeState.ERROR)
                    updateNotification()
                }
            },
            ContextCompat.getMainExecutor(this),
        )
    }

    private fun stopCamera() {
        if (!cameraRunning) return
        runCatching { cameraProvider?.unbindAll() }
            .onFailure { Log.w(TAG, "解绑摄像头失败", it) }
        analyzer?.release()
        analyzer = null
        cameraRunning = false
        Log.i(TAG, "摄像头已停止")
    }

    // -----------------------------------------------------------------------
    // 判定结果
    // -----------------------------------------------------------------------

    /**
     * 收到一帧的判定结果。**跑在主线程上**（ML Kit 的 Task 回调默认派发到主线程）。
     *
     * ===========================================================================
     * 记录策略是这里最值得推敲的地方
     * ===========================================================================
     * 如果把每一次「开始注视」都写进时间线，一个下午就能把 300 条的上限塞满 ——
     * 那等于把时间线变成一个没用的日志。所以分三档：
     *
     *  1. **每次状态变化**只更新内存里的状态（界面上能看到），不写时间线；
     *  2. 「开始注视」有 [LOOKING_EVENT_MIN_GAP_MILLIS] 的最小间隔才记一条；
     *  3. 「离开注视」只在这次注视持续够久时才记，并带上时长；
     *  4. **连续注视的里程碑**（20 / 30 / 45 分钟…）单独记一条，并发一条系统胶囊 ——
     *     那是唯一真正需要打断用户的事件。
     */
    private fun onReading(reading: GazeReading) {
        val previousState = gaze.status.value.state
        val stateChanged = previousState != reading.state
        val now = System.currentTimeMillis()

        // 一轮注视结束：把这一轮的时长累加进今日总量。
        // 必须在下面把 lookingStartedAtMillis 清掉之前算。
        if (reading.leftLooking && lookingStartedAtMillis > 0L) {
            todayFocusMillis += (now - lookingStartedAtMillis).coerceAtLeast(0L)
        }

        publishStatus(reading.state, reading.lookingForMillis)

        if (stateChanged) {
            updateNotification()
        }

        if (reading.enteredLooking) {
            lookingStartedAtMillis = now
            nextMilestoneIndex = 0
            if (now - lastLookingEventMillis >= LOOKING_EVENT_MIN_GAP_MILLIS) {
                lastLookingEventMillis = now
                timeline.record(
                    kind = TimelineKind.GAZE_LOOKING,
                    title = "开始注视屏幕",
                )
            }
            // 截图与视觉描述：只有在用户明确允许上传时才做。
            if (reading.shouldCapture && configNow.uploadScreenshots) {
                captureAndDescribe()
            }
        }

        if (reading.leftLooking) {
            val durationMillis = if (lookingStartedAtMillis > 0L) now - lookingStartedAtMillis else 0L
            lookingStartedAtMillis = 0L

            if (durationMillis >= GazeDefaults.TIMELINE_MIN_SESSION_MILLIS) {
                timeline.record(
                    kind = TimelineKind.GAZE_AWAY,
                    title = "放下手机",
                    detail = "连续看了 ${durationMillis / 60_000L} 分钟",
                )
            }
        }

        // 疲劳里程碑：只在连续注视中累计，离开注视后由下一轮的 enteredLooking 归零。
        if (reading.state == GazeState.LOOKING) {
            checkFatigueMilestone(reading.lookingForMillis)
        }
    }

    /**
     * 连续注视到达里程碑时报一次。
     *
     * 阈值是 20 / 30 / 45 / 60 / 90 / 120 分钟（见 [GazeDefaults.FATIGUE_MILESTONE_MINUTES]）。
     * 用「下标只往前走」而不是「每个阈值一个布尔量」：后者在长时间运行时会有六个
     * 状态要维护，而这里一个整数就够了，且不可能出现「45 分钟的提醒在 30 分钟那档
     * 漏掉之后再也不触发」的错位。
     */
    private fun checkFatigueMilestone(continuousMillis: Long) {
        val minutes = continuousMillis / 60_000L
        val milestone = GazeDefaults.FATIGUE_MILESTONE_MINUTES.getOrNull(nextMilestoneIndex)
            ?: return
        if (minutes < milestone) return

        nextMilestoneIndex++
        val text = "连续注视屏幕已达 $milestone 分钟"
        timeline.record(
            kind = TimelineKind.GAZE_FATIGUE,
            title = text,
            detail = "摄像头感知",
        )
        // 这一条要进会话流：它是整个注视监控里唯一一个「需要用户当场知道的」事件。
        policy.publishNotice("摄像头感知：$text")
    }

    /**
     * 发布状态到仓库。
     *
     * 每次都写 StateFlow（而不是只在状态变化时写）：因为 [VisionStatus.continuousFocusMillis]
     * 每帧都在变，界面要靠它显示「已经连续看了 12 分钟」这种数字。
     * StateFlow 在没有收集者时写入几乎不花代价，而面板打开时它本来就需要刷新。
     */
    private fun publishStatus(state: GazeState, continuousMillis: Long = 0L) {
        gaze.publishStatus(
            VisionStatus(
                state = state,
                continuousFocusMillis = continuousMillis,
                todayFocusMillis = todayFocusMillis,
            ),
        )
    }

    /**
     * 截屏 → 交给视觉模型 → 把描述记进时间线。
     *
     * 整条链路都在「不成功就算了」的前提下跑：任何一个环节缺失（没配视觉模型、
     * 无障碍没开、系统版本不够、截图失败、端点报错），都只写一条错误状态，
     * **不重试、不弹窗、不影响注视监控本身**。这是刻意的 —— 这个功能是增值项，
     * 它坏掉不该让「记录在看没在看」这件基本的事跟着坏掉。
     */
    private fun captureAndDescribe() {
        serviceScope.launch {
            val vision = aiConfig.visionConfigNow()
            if (!vision.isUsable) {
                gaze.publishError("没有配置视觉模型，只记录「在看」不上传截图")
                return@launch
            }

            val capture = ScreenCaptureProvider.capture
            if (capture == null) {
                gaze.publishError("无障碍服务未开启（或系统低于 Android 11），拿不到截图")
                return@launch
            }

            val jpeg = capture()
            if (jpeg == null || jpeg.isEmpty()) {
                gaze.publishError("截图失败，本次跳过")
                return@launch
            }

            client.describeImage(vision, vision.prompt, jpeg)
                .onSuccess { description ->
                    val text = description.trim().take(GazeDefaults.MAX_DESCRIPTION_LENGTH)
                    if (text.isEmpty()) {
                        gaze.publishError("视觉模型返回了空内容")
                        return@onSuccess
                    }
                    gaze.publishError(null)
                    gaze.publishDescription(text)
                    timeline.record(
                        kind = TimelineKind.GAZE_DESCRIBED,
                        title = text,
                        detail = "截图 ${jpeg.size / 1024} KB",
                    )
                    Log.i(TAG, "视觉描述：$text")
                }
                .onFailure { throwable ->
                    val message = throwable.message?.takeIf { it.isNotBlank() }
                        ?: throwable.javaClass.simpleName
                    Log.w(TAG, "视觉描述失败：$message")
                    gaze.publishError("视觉描述失败：$message")
                }
        }
    }

    // -----------------------------------------------------------------------
    // 前台通知
    // -----------------------------------------------------------------------

    /**
     * 把自己提升为前台服务。
     *
     * @return true 表示已成功进入前台态；false 表示缺少必要权限，调用方应停止服务。
     */
    private fun promoteToForeground(): Boolean {
        if (!hasCameraPermission()) {
            Log.w(TAG, "缺少 CAMERA 权限，无法以 camera 类型进入前台。")
            // 界面上的红字需要一条原因，否则用户只会看到开关弹回去。
            if (::gaze.isInitialized) {
                gaze.publishError("缺少摄像头权限，注视监控无法启动")
                publishStatus(GazeState.ERROR)
            }
            return false
        }

        val started = try {
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
            if (::gaze.isInitialized) {
                gaze.publishError("系统拒绝了 camera 类型的前台服务")
                publishStatus(GazeState.ERROR)
            }
            false
        }

        if (started) updateNotification()
        return started
    }

    /** 状态变了就刷新通知文案 —— 用户随时能看到摄像头在做什么。 */
    private fun updateNotification() {
        if (!::gaze.isInitialized) return
        runCatching {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(),
                foregroundServiceType(),
            )
        }.onFailure { Log.w(TAG, "刷新通知失败", it) }
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
     * 四个刻意的选择：
     * - 渠道 `IMPORTANCE_LOW`（在 `FocusSupervisorApp` 里建）：不响不震不弹横幅；
     * - `setOngoing(true)`：用户不能划掉它 —— 它代表「摄像头正在工作」这个事实；
     * - `setSilent(true)`：即便渠道被用户改成高重要性，这条通知本身也不出声；
     * - 点它打开应用：用户看到绿点想知道「谁在用摄像头」时，一眼就能找到答案。
     */
    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, FocusSupervisorApp.CHANNEL_ID_MONITOR)
            .setSmallIcon(R.drawable.ic_notification_focus)
            .setContentTitle(getString(R.string.monitor_notification_title))
            .setContentText(getString(stateTextRes()))
            .setContentIntent(openApp)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    /**
     * 通知正文跟着状态走。
     *
     * 这不只是好看：Android 12+ 在摄像头被使用时会在状态栏亮一个绿点，用户唯一能
     * 确认「它在干什么」的地方就是这条通知。写「正在监督」而不写具体状态，
     * 等于让用户自己去猜。
     */
    private fun stateTextRes(): Int = when (gaze.status.value.state) {
        GazeState.OFF -> R.string.monitor_notification_text_off
        GazeState.STARTING -> R.string.monitor_notification_text_starting
        GazeState.NO_FACE -> R.string.monitor_notification_text_no_face
        GazeState.AWAY -> R.string.monitor_notification_text_away
        GazeState.LOOKING -> R.string.monitor_notification_text_looking
        GazeState.ERROR -> R.string.monitor_notification_text_error
    }

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

        /**
         * 两次「开始注视」时间线记录之间的最小间隔。
         *
         * 用户一天会拿起手机几十次，全都记下来会瞬间填满时间线的 300 条上限，
         * 把真正重要的事件（拦截、放行）挤掉。5 分钟一次既能看出「他一直在看」，
         * 又不会淹没别的东西。
         */
        private const val LOOKING_EVENT_MIN_GAP_MILLIS = 5 * 60_000L

        /**
         * 分析用的分辨率（VGA）。
         *
         * 人脸检测在 640x480 上的准确率与 1080p 几乎没有差别 —— 它需要的是「脸在哪、
         * 朝哪边」，而不是毛孔。但每帧的解码、色彩空间转换与推理代价差好几倍，
         * 而这是要连续跑几个小时的服务，那几倍直接体现在电池和发热上。
         */
        private val ANALYSIS_RESOLUTION = Size(640, 480)

        /** 把毫秒说成秒，保留一位小数（0.7 / 1.0 / 2.0）。 */
        private fun formatSeconds(millis: Long): String =
            String.format(java.util.Locale.US, "%.1f", millis / 1000.0)
    }
}
