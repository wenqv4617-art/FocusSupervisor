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

        vectorDrawables { useSupportLibrary = true }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            isDebuggable = true
        }
        release {
            // 骨架阶段先不开混淆：等真正接入代码后，再按需打开并补 keep 规则。
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
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

    // ---- 生命周期 / ViewModel ----------------------------------------------
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)   // collectAsStateWithLifecycle
    implementation(libs.androidx.lifecycle.viewmodel.compose) // viewModel()

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
