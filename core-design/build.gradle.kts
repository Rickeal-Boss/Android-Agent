import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// =============================================================================
// :core-design — Liquid Glass 设计系统（tokens / 颜色 / 动效 / 组件）
//
// 纯 Compose 模块：不依赖任何 project（docs/01-architecture.md §1.4），
// 这样 @Preview 不需要构造领域对象，且 ModelDescriptor 变更不会触发 UI 全量重编译。
// =============================================================================

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.rickeal.agent.core.design"

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

    implementation(libs.kotlinx.coroutines.android)

    // JVM 单测（src/test/java，AGP 默认源集）—— 与 :core-engine 同款配置
    // （同一 catalog 键，不新增依赖版本）：AGP 不自动提供 junit，必须显式声明；
    // kotlin-test 提供断言 API（catalog 里 kotlin-test 键指向 kotlin-test-junit 变体，
    // 见 gradle/libs.versions.toml 注释）。
    // ⚠️ 本模块是纯 Compose，**Composable 一律不在 JVM 上测**（需要 Compose runtime
    // 与 androidx.compose.ui 在 JVM 上的支持，本仓未配）。这里只覆盖被 Composable
    // 调用、且**不触碰任何 Android / Compose 类型**的纯函数：拖拽轴向判据与进度归一化
    // （liquid/interactive/DampedDragAnimation.kt）、窗口尺寸分档（WindowSizeClass.kt）。
    // 因此无需 Robolectric / coroutines-test / Compose UI test。
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
}
