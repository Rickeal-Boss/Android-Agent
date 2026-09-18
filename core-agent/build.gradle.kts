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
}
