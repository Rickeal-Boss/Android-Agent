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

    // JVM 单测（src/test/java，AGP 默认源集）。与 :core-agent / :core-model 同款配置
    // （同一 catalog 键，不新增依赖版本）：AGP 不自动提供 junit，必须显式声明；
    // kotlin-test 提供断言 API（catalog 里 kotlin-test 键指向 kotlin-test-junit 变体，
    // 见 gradle/libs.versions.toml 注释）。
    // 测试只覆盖**不触 native / Android API 的纯逻辑**（EngineLoadCoordinator 状态机、
    // EngineEnvironment 的 GPU 白名单回落、AttachmentBytesReader 的字节读取边界），
    // 因此无需 Robolectric / coroutines-test：runBlocking 来自 kotlinx-coroutines-core
    // （经 kotlinx.coroutines.android 传递引入，已在 implementation 依赖里）。
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
}
