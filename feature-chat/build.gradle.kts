import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// =============================================================================
// :feature-chat — 对话页 + 参数面板 + 多模态输入
//
// 依赖按 docs/01-architecture.md §1.3 一次给足（dev-B 不需要再改构建脚本）。
// =============================================================================

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.rickeal.agent.feature.chat"

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

    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core-model"))
    implementation(project(":core-engine"))
    implementation(project(":core-agent"))
    implementation(project(":core-data"))
    implementation(project(":core-design"))

    implementation(platform(libs.androidx.compose.bom))

    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.animation)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // JVM 单测（Wave 31 流2 点亮 src/test 源集）。与 :core-agent 同款坐标（同一
    // catalog 键，不新增依赖版本）：AGP 不自动提供 junit 必须显式声明；kotlin-test
    // 提供断言 API（kotlin-test-junit 变体，见 gradle/libs.versions.toml）。
    // 测试只覆盖纯逻辑（ChatUiState 的事件 → 状态映射），不触 Compose / Android API /
    // ViewModel 的 Android 依赖，因此无需 Robolectric / coroutines-test。
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
}
