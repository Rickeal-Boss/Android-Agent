import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// =============================================================================
// :core-data — DataStore Preferences + JSON 文件持久化 + 仓库 + AppContainer
//              （手写 DI + CompositionLocal）
// =============================================================================

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.rickeal.agent.core.data"

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
    api(project(":core-engine"))
    api(project(":core-agent"))

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.datastore.preferences)

    // 只需要 runtime：AppContainer.kt 里的 LocalAppContainer 用 staticCompositionLocalOf。
    // 不引 ui / material3，也不需要开 buildFeatures.compose（本模块没有 @Composable）。
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.runtime)

    // ViewModelFactory.kt（架构文档 §7.4）需要 ViewModel / ViewModelProvider
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
}
