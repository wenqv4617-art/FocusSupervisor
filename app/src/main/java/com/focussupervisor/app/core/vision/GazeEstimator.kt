package com.focussupervisor.app.core.vision

import com.focussupervisor.app.domain.model.GazeConfig
import com.focussupervisor.app.domain.model.GazeState

/**
 * 一帧画面的判断结果。
 *
 * 刻意不直接用 ML Kit 的 `Face` 类型：那样这个类就得依赖 Android 与 ML Kit，
 * 也就没法在 JVM 上单独验证「连续几帧才判定为注视」这套状态机 —— 而状态机
 * 恰恰是这里最容易出错、也最值得测的部分。
 *
 * @param hasFace 这一帧里有没有人脸
 * @param yawDegrees 头部左右转动角度（度）。正视屏幕时接近 0，往右转为正。
 * @param pitchDegrees 头部上下转动角度（度）。
 * @param eyeOpenProbability 眼睛睁开的概率 0~1；分类模式没开时为 null
 */
data class FaceSignal(
    val hasFace: Boolean,
    val yawDegrees: Float = 0f,
    val pitchDegrees: Float = 0f,
    val eyeOpenProbability: Float? = null,
)

/**
 * 一次判定之后的结果快照。
 *
 * @param state 判定之后的状态
 * @param enteredLooking 这一帧刚刚进入「注视」（已经连续注视够久）
 * @param leftLooking 这一帧刚刚离开「注视」
 * @param shouldCapture 现在应该截屏并上传（进入注视 + 距上次上传已超过冷却时间）
 * @param lookingForMillis 本次注视已经持续了多久；不在注视时为 0
 */
data class GazeReading(
    val state: GazeState,
    val enteredLooking: Boolean = false,
    val leftLooking: Boolean = false,
    val shouldCapture: Boolean = false,
    val lookingForMillis: Long = 0L,
)

/**
 * 注视判定的状态机。
 *
 * ===========================================================================
 * 它解决的是什么问题
 * ===========================================================================
 * 单帧的判断一定不准：人眨一下眼、偏一下头、光照晃一下，都会让「是否在看屏幕」
 * 在同一秒里翻好几次。如果直接拿单帧结果去记录、去截屏上传，结果会是满屏噪音
 * 加上一堆没意义的 API 调用。
 *
 * 所以这个类的工作就是**去抖**：
 *  - 候选状态要连续保持一段时间（[GazeConfig.lookHoldMillis] /
 *    [GazeConfig.awayHoldMillis]）才真的切换；
 *  - 「开始注视」时才考虑截屏，而且受 [GazeConfig.captureCooldownMillis] 节流。
 *
 * 它不是线程安全的：设计上只被摄像头分析线程单线程调用。
 * 时间源通过构造函数注入，方便在没有设备的情况下验证这些时序规则。
 */
class GazeEstimator(
    private val config: () -> GazeConfig,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private var state: GazeState = GazeState.OFF

    /** 正在等待确认的候选状态；null 表示候选与当前状态一致，没有待定切换。 */
    private var pendingState: GazeState? = null
    private var pendingSinceMillis: Long = 0L

    /** 本次注视会话是从什么时候开始的（用于判断会话长度，以及记录时的取舍）。 */
    private var lookingSinceMillis: Long = 0L

    /** 上一次截屏上传的时间。0 表示本次运行还没上传过。 */
    private var lastCaptureMillis: Long = 0L

    /** 当前状态。界面与日志用。 */
    fun currentState(): GazeState = state

    /**
     * 把状态机复位。
     *
     * 摄像头重启、用户开关一次功能都要调用 —— 否则上次残留的「正在注视」会让
     * 新一次启动的第一帧就误判为「一直看着」。
     */
    fun reset(newState: GazeState = GazeState.STARTING) {
        state = newState
        pendingState = null
        pendingSinceMillis = 0L
        lookingSinceMillis = 0L
    }

    /** 直接设置状态（启动、出错这类不是从画面推出来的状态）。 */
    fun forceState(newState: GazeState) {
        reset(newState)
    }

    /**
     * 喂一帧。
     *
     * @return 判定结果。调用方据此决定记不记时间线、要不要截屏。
     */
    fun onSignal(signal: FaceSignal): GazeReading {
        val now = clock()
        val cfg = config()

        val candidate = when {
            !signal.hasFace -> GazeState.NO_FACE
            isLookingAtScreen(signal, cfg) -> GazeState.LOOKING
            else -> GazeState.AWAY
        }

        // ---- 候选与现状一致：没有待定切换 ----
        if (candidate == state) {
            pendingState = null
            return reading(now, shouldCapture = false)
        }

        // ---- 候选不同：开始/继续计时 ----
        if (pendingState != candidate) {
            pendingState = candidate
            pendingSinceMillis = now
            return reading(now, shouldCapture = false)
        }

        // ---- 计时还没到，维持原状态 ----
        //
        // 「离开」的确认时间用 awayHoldMillis，「注视」和「没人」共用 lookHoldMillis：
        // 没人也算一种需要确认的状态，否则用户低头系个鞋带就会被记成「离开了」。
        val holdMillis = if (candidate == GazeState.AWAY) {
            cfg.awayHoldMillis
        } else {
            cfg.lookHoldMillis
        }
        if (now - pendingSinceMillis < holdMillis) {
            return reading(now, shouldCapture = false)
        }

        // ---- 切换成立 ----
        val previous = state
        state = candidate
        pendingState = null
        pendingSinceMillis = now

        val enteredLooking = candidate == GazeState.LOOKING
        val leftLooking = previous == GazeState.LOOKING

        if (enteredLooking) {
            lookingSinceMillis = now
        }

        // 截屏上传：只在「刚进入注视」时考虑，并且受冷却时间节流。
        // 冷却在**进入注视**的那一刻就记账，而不是等上传成功 —— 上传失败也不该
        // 让下一次注视立刻再传一遍。
        val shouldCapture = enteredLooking && isCaptureAllowed(now, cfg)
        if (shouldCapture) {
            lastCaptureMillis = now
        }

        if (leftLooking) {
            lookingSinceMillis = 0L
        }

        return GazeReading(
            state = state,
            enteredLooking = enteredLooking,
            leftLooking = leftLooking,
            shouldCapture = shouldCapture,
            lookingForMillis = if (state == GazeState.LOOKING) now - lookingSinceMillis else 0L,
        )
    }

    /**
     * 这一帧算不算「在看屏幕」。
     *
     * 用**头部姿态**而不是眼球位置：ML Kit 的人脸检测直接给出三个欧拉角，
     * 而精确的视线（虹膜）估计要换 MediaPipe + 一个额外模型文件，收益与成本
     * 不成比例 —— 对「他在不在看屏幕」这个问题，头部朝向已经足够有判别力
     * （低头刷手机、扭头看电视，头都会转）。
     *
     * 眼睛睁开概率只作为**附加**条件：拿不到概率（分类模式没开或该机型不支持）
     * 时不能因此判成没在看，否则会出现「所有帧都判为离开」这种彻底失效。
     */
    private fun isLookingAtScreen(signal: FaceSignal, cfg: GazeConfig): Boolean {
        val withinYaw = kotlin.math.abs(signal.yawDegrees) <= cfg.yawToleranceDegrees
        val withinPitch = kotlin.math.abs(signal.pitchDegrees) <= cfg.pitchToleranceDegrees
        if (!withinYaw || !withinPitch) return false

        val eyeOpen = signal.eyeOpenProbability ?: return true
        return eyeOpen >= cfg.eyeOpenThreshold
    }

    private fun isCaptureAllowed(now: Long, cfg: GazeConfig): Boolean =
        lastCaptureMillis == 0L || now - lastCaptureMillis >= cfg.captureCooldownMillis

    private fun reading(now: Long, shouldCapture: Boolean): GazeReading = GazeReading(
        state = state,
        shouldCapture = shouldCapture,
        lookingForMillis = if (state == GazeState.LOOKING) now - lookingSinceMillis else 0L,
    )
}
