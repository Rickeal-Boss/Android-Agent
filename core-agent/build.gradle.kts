import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// =============================================================================
// :core-agent — Agent 循环、工具注册中心、内置工具、上下文压缩
// =============================================================================

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.rickeal.agent.core.agent"

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
    // AgentRunner / AgentRequest 的公开签名里带引擎类型，故用 api 透出
    api(project(":core-engine"))

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.core.ktx)

    // JVM 单测（src/test/java，AGP 默认源集）。与 :core-model 同款配置（同一 catalog 键，
    // 不新增依赖版本）：AGP 不自动提供 junit 必须显式声明；kotlin-test 提供断言 API。
    // 测试只覆盖纯函数（TextToolProtocol / ToolApprovalCache / ToolArgsValidator），
    // 不触任何 Android 类或引擎类型，因此无需 Robolectric / coroutines-test。
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
}
