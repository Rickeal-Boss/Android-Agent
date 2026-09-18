// =============================================================================
// LiquidAgent — 根构建脚本
//
// 只做一件事：声明插件（apply false），版本全部来自 gradle/libs.versions.toml。
// 不在这里写任何业务逻辑，也不加任何 buildscript {} 依赖。
// =============================================================================

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
