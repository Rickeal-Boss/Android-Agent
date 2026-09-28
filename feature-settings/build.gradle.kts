import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// =============================================================================
// :feature-settings — 设置页 + Agent 工具页（子包 tools）+ 远程端点 CRUD
//
// 依赖按 docs/01-architecture.md §1.3 一次给足（dev-B 不需要再改构建脚本）。
// =============================================================================

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.rickeal.agent.feature.settings"

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

    // JVM 单测（src/test/java，AGP 默认源集）—— 与 :core-engine / :core-design /
    // :feature-models 同款配置（同一 catalog 键，不新增依赖版本）。只覆盖**不触碰
    // Android / Compose / ViewModel 运行时**的纯函数：工具分类展示名映射
    // （ToolCategories.kt）与搜索/分类/启用筛选归约（ToolsViewModel.kt 文件级
    // internal 函数）。Composable 一律不在 JVM 上测。
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
}
