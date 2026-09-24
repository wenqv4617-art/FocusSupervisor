// ============================================================================
//  FocusSupervisor —— :app 模块
//
//  分层约定（包结构）：
//    domain/   纯 Kotlin 领域模型，不依赖 Android 框架
//    data/     数据来源（当前为 Mock，后续接 DataStore / Room / 网络）
//    ui/       Jetpack Compose 表现层，单向数据流，状态由 ViewModel 持有
//    service/  系统级能力（无障碍、前台服务），与 UI 完全解耦
// ============================================================================

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    // Kotlin 2.0 的 Compose 编译器插件：等价于旧版的 composeOptions {} 配置。
    alias(libs.plugins.kotlin.compose)
}

// ---- 签名密钥 --------------------------------------------------------------
//
// 调试密钥是 Android 生态里**公开**的东西（口令就是 android），所以这几个值直接写在
// 构建脚本里没有任何保密问题 —— 它唯一的用途是让签名**稳定**。
// 发布密钥相反：只从环境变量读，仓库里一个字都不留（见 android.signingConfigs）。
val DEBUG_KEYSTORE_FILE = "debug.keystore"
val DEBUG_KEYSTORE_PASSWORD = "android"
val DEBUG_KEY_ALIAS = "androiddebugkey"

/** 发布密钥文件的路径。CI（或本机）通过 FOCUS_KEYSTORE_PATH 提供，缺省为空。 */
val releaseKeystorePath: String? =
    System.getenv("FOCUS_KEYSTORE_PATH")?.takeIf { it.isNotBlank() }

android {
    namespace = "com.focussupervisor.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.focussupervisor.app"
        // minSdk 26：自适应图标、通知渠道、java.time 都是 26 起原生可用，
        // 可以直接避开一批兼容性分支和 desugaring。
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 目前只有中英两种文案，锁一下资源配置能让 APK 更小、构建更快。
        resourceConfigurations += listOf("en", "zh")

        // 只保留真机用得上的两种 ABI。
        //
        // ML Kit 的打包版带了四个架构的人脸检测原生库（x86 与 x86_64 各约 9.6 MB，
        // 那是给模拟器用的）。用户的手机不是 arm64-v8a 就是 armeabi-v7a，
        // 留下这两个足以让包小一半——而这个包已经因为人脸模型变大了。
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }

        vectorDrawables { useSupportLibrary = true }
    }

    // ---- 签名 --------------------------------------------------------------
    //
    // 固定的**调试**密钥，直接提交在 app/debug.keystore（照 rpbox 的做法）。
    //
    // 为什么必须固定：AGP 默认在每台构建机上自动生成一把新的调试密钥，于是两次 CI
    // 产出的 APK 签名不同，覆盖安装必然报 INSTALL_FAILED_UPDATE_INCOMPATIBLE ——
    // 用户看到的现象就是「每次更新都得先卸载」，而卸载会清空全部数据。
    //
    // 必须说清楚它的性质：这是一把**公开**密钥（口令就是 Android 约定的
    // android / android），它只保证签名**稳定**，不提供任何保密性 —— 仓库里的
    // 任何人都能拿它签出一个系统愿意当作升级包接受的 APK。它适合自用与内测，
    // 不适合上架。真正的发布签名走下面的 release 配置（从环境变量读密钥，
    // 绝不入库），配好 CI Secrets 之后换过去即可，代价是那一次要重新卸载安装。
    signingConfigs {
        getByName("debug") {
            storeFile = file(DEBUG_KEYSTORE_FILE)
            storePassword = DEBUG_KEYSTORE_PASSWORD
            keyAlias = DEBUG_KEY_ALIAS
            keyPassword = DEBUG_KEYSTORE_PASSWORD
        }

        // 只有在提供了密钥文件的环境里才创建这个配置 —— 别人 clone 下来直接构建时，
        // 配置阶段不能因为缺少密钥而失败。
        val releaseKeystore = releaseKeystorePath
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("FOCUS_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("FOCUS_KEY_ALIAS")
                keyPassword = System.getenv("FOCUS_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            isDebuggable = true
            // 用仓库里那把固定密钥顶掉 AGP 自动生成的临时密钥。
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            // 骨架阶段先不开混淆：等真正接入代码后，再按需打开并补 keep 规则。
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // 没有发布密钥时退回固定调试密钥：release 变体照样产出可安装的包，
            // 不会因为「没配密钥」而在打包中途失败。
            signingConfig = if (releaseKeystorePath != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    lint {
        // 骨架阶段不让 lint 阻断 CI；正式发版前应改回 abortOnError = true。
        abortOnError = false
    }
}

// 用 kotlin.compilerOptions 而不是已弃用的 android.kotlinOptions。
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // ---- 基础 --------------------------------------------------------------
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    // ---- 持久化 / 网络 ------------------------------------------------------
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.okhttp)

    // ---- 摄像头 / 视觉（注视监控）-------------------------------------------
    // CameraX 负责取帧，ML Kit 负责人脸与头部姿态。两者都在设备本地跑；
    // 唯一会离开设备的，是「判定为正在注视」之后主动上传的那一张截图。
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.mlkit.face.detection)

    // ---- 本地向量模型 --------------------------------------------------------
    // ONNX Runtime 负责跑下载到本机的 all-MiniLM-L6-v2 量化模型。
    // 只在用户主动下载并启用本地模型时才会被加载，但**库本身必须在包里** ——
    // 运行时装一个 native 库是做不到的。体积代价见 libs.versions.toml 的注释。
    implementation(libs.onnxruntime.android)

    // ---- 端侧对话模型 --------------------------------------------------------
    // MediaPipe GenAI 的 LLM Inference：跑下载到本机的 .task 模型。
    // 只吃 .task（LiteRT 打包格式），不吃 GGUF —— 那是 llama.cpp 的格式。
    implementation(libs.mediapipe.tasks.genai)

    // ---- 端侧识图（备选 A：中文 OCR）------------------------------------------
    // 模型打包版，不依赖 Google Play 服务。约 +10MB 原生组件，
    // 换来的是「完全离线的屏幕文字理解」。
    implementation(libs.mlkit.text.recognition.chinese)

    // ---- 生命周期 / ViewModel ----------------------------------------------
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)   // collectAsStateWithLifecycle
    implementation(libs.androidx.lifecycle.viewmodel.compose) // viewModel()
    implementation(libs.androidx.lifecycle.service)           // LifecycleService

    // ---- Compose -----------------------------------------------------------
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)

    // @Preview 只在 debug 变体里需要，不污染正式包。
    debugImplementation(libs.androidx.compose.ui.tooling)
    // ---- 测试 --------------------------------------------------------------
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
}
