import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// =============================================================================
// :app — 壳：Application / MainActivity / NavHost / DI 组装
//
// 签名约定（CI 同事要求）：
//   * release buildType 必须始终存在
//   * 用 project.findProperty(...) 读 signing.* ，四个属性齐全才挂签名配置，
//     否则退回 debug 签名 —— 保证无密钥时 assembleRelease 也能跑通
// =============================================================================

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.rickeal.agent"

    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.rickeal.agent"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    // ---------------------------------------------------------------------
    // 签名：secrets 缺失时静默退回 debug 签名
    // ---------------------------------------------------------------------
    signingConfigs {
        val storeFilePath = project.findProperty("signing.storeFile")?.toString()
        val storePasswordValue = project.findProperty("signing.storePassword")?.toString()
        val keyAliasValue = project.findProperty("signing.keyAlias")?.toString()
        val keyPasswordValue = project.findProperty("signing.keyPassword")?.toString()

        val hasSigning = !storeFilePath.isNullOrBlank() &&
            storePasswordValue != null &&
            keyAliasValue != null &&
            keyPasswordValue != null

        if (hasSigning) {
            create("release") {
                storeFile = file(storeFilePath!!)
                storePassword = storePasswordValue
                keyAlias = keyAliasValue
                keyPassword = keyPasswordValue
                // 显式启用 APK Signature Scheme v3。
                // 不显式打开时 AGP 只签 v2（CI 实测 apksigner 报
                // "Verified using v3 scheme (APK Signature Scheme v3): false"）。
                // v3 相对 v2 的增量价值是**密钥轮换**（key rotation）：
                // 将来换签名密钥时老用户仍能无缝升级，而不是必须卸载重装。
                // minSdk 31 >= 28，满足 v3 的适用下限。
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }

        release {
            isMinifyEnabled = true
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // 无密钥 -> findByName 返回 null -> 退回 debug 签名，构建不会失败
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.getByName("debug")
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
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
    implementation(project(":feature-chat"))
    implementation(project(":feature-models"))
    implementation(project(":feature-settings"))

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
}
