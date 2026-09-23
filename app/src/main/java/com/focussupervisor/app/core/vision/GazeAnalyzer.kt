package com.focussupervisor.app.core.vision

import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.focussupervisor.app.domain.model.GazeConfig
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions

/**
 * 摄像头帧的分析器：把每一帧转成 [FaceSignal]，再交给 [GazeEstimator] 判定。
 *
 * ===========================================================================
 * 三条必须遵守的规则
 * ===========================================================================
 * 1. **每一帧都必须 close**。`ImageProxy` 是有限资源，漏掉一次 close，CameraX 就
 *    再也不会给下一帧 —— 表现出来是「功能打开了但永远停在第一帧」。所以 close 挂在
 *    `addOnCompleteListener` 上（成功、失败都会走），而不是散在各个分支里。
 * 2. **降频**。摄像头能给 30fps，但判断「在不在看屏幕」每秒一帧都绰绰有余。
 *    按 [GazeConfig.analyzeIntervalMillis] 抽帧，直接换来电池和发热。
 * 3. **用完即弃**。画面帧不落盘、不缓存、不上传 —— 会离开设备的只有上层主动
 *    截取的那一张屏幕截图，而且由用户单独授权。
 *
 * ML Kit 的 Task 回调默认跑在主线程，所以 [estimator] 实际上始终被同一个线程调用，
 * 不需要额外加锁；而 [analyze] 本身跑在 CameraX 的分析线程上。
 */
class GazeAnalyzer(
    private val configProvider: () -> GazeConfig,
    private val estimator: GazeEstimator,
    private val onReading: (GazeReading) -> Unit,
    private val onFailure: (Throwable) -> Unit,
) : ImageAnalysis.Analyzer {

    private val detector: FaceDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            // FAST：只做人脸框 + 欧拉角 + 睁开概率，不测轮廓与关键点。
            // 判断「头有没有朝向屏幕」用不到那些，而它们是最贵的一部分。
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
            // 分类模式给的是「眼睛睁开的概率」，是判定的附加条件。
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setMinFaceSize(MIN_FACE_SIZE)
            .enableTracking()
            .build(),
    )

    /** 上一次真正送进模型的时间（uptime，不受系统时间调整影响）。 */
    private var lastAnalyzedAtMillis = 0L

    /** 由主线程写、分析线程读，所以必须 volatile。 */
    @Volatile
    private var released = false

    override fun analyze(imageProxy: ImageProxy) {
        if (released) {
            imageProxy.close()
            return
        }

        val now = SystemClock.uptimeMillis()
        if (now - lastAnalyzedAtMillis < configProvider().analyzeIntervalMillis) {
            imageProxy.close()
            return
        }
        lastAnalyzedAtMillis = now

        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        val input = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

        detector.process(input)
            .addOnSuccessListener { faces ->
                onReading(estimator.onSignal(toSignal(faces.firstOrNull())))
            }
            .addOnFailureListener { throwable ->
                Log.w(TAG, "人脸检测失败", throwable)
                onFailure(throwable)
            }
            // 无论成功失败都必须还帧，否则下一帧永远不会来。
            .addOnCompleteListener { imageProxy.close() }
    }

    /** 释放模型。服务停止时必须调用 —— ML Kit 的检测器持有原生资源。 */
    fun release() {
        released = true
        runCatching { detector.close() }
    }

    /**
     * 只取**最大的一张脸**。
     *
     * 画面里可能同时有别人（例如旁边坐着同事），但监督的对象只有一个。ML Kit 返回
     * 的列表按人脸大小排序，第一张就是离摄像头最近的，通常也就是拿着手机的人。
     */
    private fun toSignal(face: Face?): FaceSignal {
        face ?: return FaceSignal(hasFace = false)

        // 左右眼的睁开概率取平均；两个都拿不到就是 null（视为「不知道」，
        // 判定时不会因此把它算成闭眼）。
        val eyeOpen = listOfNotNull(face.leftEyeOpenProbability, face.rightEyeOpenProbability)
            .takeIf { it.isNotEmpty() }
            ?.average()
            ?.toFloat()

        return FaceSignal(
            hasFace = true,
            // 注意坐标含义：headEulerAngleY 是左右转头，X 是上下点头。别接反 ——
            // 反了会让「低头看手机」被判成「往左看」，判定彻底失灵。
            yawDegrees = face.headEulerAngleY,
            pitchDegrees = face.headEulerAngleX,
            eyeOpenProbability = eyeOpen,
        )
    }

    private companion object {
        const val TAG = "GazeAnalyzer"

        /** 小于这个比例的人脸忽略，避免把背景里的一张海报当成用户。 */
        const val MIN_FACE_SIZE = 0.15f
    }
}
