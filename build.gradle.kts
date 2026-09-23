// ============================================================================
//  FocusSupervisor —— 根构建脚本
//
//  只做一件事：把插件声明出来并 apply false，真正的 apply 交给各模块。
//  所有版本号统一来自 gradle/libs.versions.toml（Version Catalog），
//  这里不出现任何字面量版本号。
// ============================================================================

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
