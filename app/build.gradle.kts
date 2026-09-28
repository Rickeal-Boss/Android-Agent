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
                // ⚠️ CI 实测（AGP 9.3.2 / minSdk 31），别凭直觉改：
                //   - 两个 enable* 都不写   → apksigner 报 v2=true,  v3=false
                //   - 只写 enableV3Signing  → v2=false, v3=true
                //   - v2/v3 都写 true       → v2=false, v3=true（enableV2Signing 被忽略）
                // AGP 9 是按「minSdk 需要的最低方案」来选签名方案，**不是叠加**，
                // 所以开启 v3 会取代 v2。minSdk 31 的设备全部支持 v3（Android 9/API 28 引入），
                // v3-only 完全合法，且相对 v2 多了**密钥轮换**（key rotation）能力：
                // 将来换签名密钥时老用户可无缝升级，而不是必须卸载重装。
                // 只写 enableV3Signing —— **不要再加 enableV2Signing**：
                // CI 实测它会被 AGP 忽略（写了也是 v2=false），留着等于一行误导人的
                // 无效代码：注释说它死了、代码却写着它活着。
                // release.yml 的校验已改成「v2 或 v3 命中其一」，不再要求两者同时命中。
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

    // ---------------------------------------------------------------------
    // Lint（AGP 自带 Android Lint，不引入 detekt/ktlint 等第三方依赖）
    //
    // 由 .github/workflows/build.yml 的 lint job 单点调用 :app:lintDebug：
    //   * abortOnError = false —— 【Wave 32 两轮计划·第一轮，仍 warn-only】
    //     第一次全量 lint 必然爆出存量问题（未用资源 / 硬编码 / 缺
    //     contentDescription 等），直接阻断会把 CI 打红。
    //     ⚠️ 本轮保持 false 是**硬性前提**而不是保守：baseline 文件尚不存在，
    //     而 lint 在 abortOnError=true 且遇到 error 时**不会**写 baseline，
    //     那样第一轮就白跑了。
    //     第二轮：主理人把 CI 产出的 app/lint-baseline.xml 提交进仓库后，
    //     把这里翻成 true —— CI 从此只拦**新增**的 lint 问题。
    //     ⚠️ 翻转时必须同时知道：abortOnError 只拦 Error/Fatal 级 issue，
    //     Warning 级（83 条里的绝大多数）不拦；想让 83 条全量进门禁要再加
    //     warningsAsErrors = true，且必须在确认 baseline 覆盖全部存量之后。
    //   * baseline = file("lint-baseline.xml") —— 冻结的是 Wave 32 首轮实测
    //     lint 9.3.2 报出的 83 条存量问题（GradleDependency 19 /
    //     AutoboxingStateCreation 17 / ObsoleteSdkInt 11 / UseKtx 9 /
    //     NewerVersionAvailable 6 / ModifierParameter 4 / ... ）。
    //     存量之外的**任何新问题**都会被 lint 抓到。
    //     重新生成基线 = 删掉 app/lint-baseline.xml 再跑一次 :app:lintDebug；
    //     ⛔ 不要手工编辑该 XML —— 路径形态与 id 匹配规则由 lint 决定。
    //   * checkReleaseBuilds = false —— 关掉 assembleRelease 附带的
    //     lintVital（fatal-only），release 构建不再被 lint 意外卡住。
    //   * checkDependencies = true —— 单点 :app:lintDebug 即覆盖 :app 与
    //     全部 8 个 library 模块的源码，无需逐模块跑 lint。
    //     盲区说明：纯 JVM（org.jetbrains.kotlin.jvm）模块没有 lint 任务，
    //     但本仓 9 个模块全部是 AGP 模块（core-model / core-design 用的
    //     也是 com.android.library），因此没有盲区。
    // ---------------------------------------------------------------------
    lint {
        // ⚠️ 本轮仍是 warn-only —— 下一轮提交生成的基线后才翻 abortOnError = true。
        abortOnError = false
        checkReleaseBuilds = false
        checkDependencies = true
        // Wave 32：文件不存在 ⇒ :app:lintDebug 首次运行时由 lint 自动生成
        // app/lint-baseline.xml，再由 build.yml 的 lint job 作为 artifact 上传。
        // 第二轮翻转 abortOnError 后本行保持不变，它负责吞掉存量问题。
        baseline = file("lint-baseline.xml")
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

    // JVM 单测（src/test/java，AGP 默认源集）—— 与其余模块同款配置（同一 catalog 键，
    // 不新增依赖版本）。:app 的源码几乎全是 Compose / Navigation 脚手架，唯一可测的
    // 纯逻辑是对话路由判据（LiquidAgentApp.isChatRoute）与相对时间分档
    // （ConversationDrawer.relativeTimeBetween）。
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
}
