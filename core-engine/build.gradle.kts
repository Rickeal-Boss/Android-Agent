import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// =============================================================================
// :core-engine — 引擎抽象 + LiteRT-LM 实现 + OpenAI 兼容实现 + 能力探测
//
// 唯一依赖 litertlm-android 的模块（docs/01-architecture.md §1.2）。
// 说明：project 依赖统一用字符串形式 project(":x")，不用类型安全访问器，
//      避免 Gradle 9 上 TYPESAFE_PROJECT_ACCESSORS 开关带来的不确定性。
// =============================================================================

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.rickeal.agent.core.engine"

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
    api(project(":core-model"))

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.core.ktx)

    // 端侧推理（仅本模块）
    implementation(libs.litertlm.android)
}
