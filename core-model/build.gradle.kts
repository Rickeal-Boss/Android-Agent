import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// =============================================================================
// :core-model — 纯领域模型 + 序列化 + 纯算法
//
// 约束（docs/01-architecture.md §1.3 / §2）：
//   * 无 Android 依赖（只用 java.util / java.io）
//   * 无 Compose
//   * 只依赖 kotlinx-serialization-json
// =============================================================================

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.rickeal.agent.core.model"

    // AGP 9 块式 DSL（见 docs/00-recon-brief.md §4 已知风险）
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        minSdk = 31
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // api：领域模型要暴露给所有上层模块
    api(libs.kotlinx.serialization.json)
}
