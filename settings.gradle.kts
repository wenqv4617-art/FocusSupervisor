// ============================================================================
//  FocusSupervisor —— 全局工程配置
//
//  这里集中声明「插件从哪来」与「依赖从哪来」，模块级 build.gradle.kts 不再
//  各自写 repositories，避免多模块各自为政导致解析结果不一致。
// ============================================================================

pluginManagement {
    repositories {
        // Google 的 Maven 仓库：Android Gradle Plugin 与 AndroidX 都在这里。
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    // 依赖仓库只允许在这里声明；模块内写 repositories 会直接构建失败，
    // 这是刻意的约束，防止后续有人偷偷引一个不明来源的仓库。
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)

    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "FocusSupervisor"

// 单模块起步：Clean Architecture 的分层先落在包结构上（domain / data / ui），
// 等编译期和团队边界真的需要时再拆 :core:domain 这类 Gradle 模块。
include(":app")
