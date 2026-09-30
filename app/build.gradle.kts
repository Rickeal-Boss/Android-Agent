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
            // Wave 38：`isShrinkResources` 默认即 false，显式声明被 lint 判 NotShrinkingResources；
            // 删除该行后行为逐字节等价。若将来要开资源收缩，需真机验证动态引用资源
            // （getIdentifier / 按名查资源）不被误删。
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
    //   * baseline = file("lint-baseline.xml") —— 冻结的是 Wave 32 首轮实测
    //     lint 9.3.2 报出的 83 条存量问题（3 Error + 63 Warning + 17 Hints，
    //     覆盖 :app 与全部 8 个 library 模块 —— checkDependencies=true 时
    //     library 的 issue 会写进 :app 的基线，首轮 CI 已实证）。存量之外的
    //     **任何新问题**都会被 lint 抓到。
    //     【Wave 37 清障】83 → **35 条**。依据 = 上一轮 CI 的 `lint-reports-<sha>`
    //     artifact（唯一零 CI 成本的真实 issue 来源）：83 条里 28 条是**对已 disable
    //     检查的失效条目**（lint 自己在报告里以 LintBaselineFixed 建议删除，见下），
    //     另 16 条 AutoboxingStateCreation + 4 条 RenderEffect 的冗余
    //     @RequiresApi(S)（minSdk 31 = S ⇒ 恒真）已在同波修掉。
    //     【Wave 38 清障】35 → **3 条**。D1~D4 四路把 35 条里 32 条的**底层代码问题
    //     真修掉**（UseKtx 换 toUri/scale、ObsoleteSdkInt 删恒真版本判断与 -v26 冗余
    //     目录、AnnotateVersionCheck 补 @ChecksSdkIntAtLeast、NewApi 改 removeAt(0)/
    //     显式 @Suppress、ConfigurationScreenWidthHeight 与 ModifierParameter 就地
    //     抑制/重排、Manifest 的 DiscouragedApi/DataExtractionRules、isShrinkResources、
    //     monochrome 图标、MissingPermission 补 @SuppressLint），这 32 条随之变成
    //     LintBaselineFixed（lint 主动建议删除）—— 留着的唯一后果是「被重新引入的问题」
    //     静默命中旧条目而**永不报出**，故必须同步删除。保留的 3 条均为「需真机/行为
    //     变更才能修」的诚实豁免：2 条 OldTargetApi（targetSdk=36，升版本是行为变更，
    //     须真机验证）+ 1 条 AutoboxingStateCreation（rememberSaveable 与 mutableIntStateOf
    //     的 saver 语义不可离线验证）。清障依据同 Wave 37 = 上一轮 CI 的 lint-reports artifact。
    //     ⚠️ 教训：清障前**必须先读 lint artifact** —— 否则无从知道基线是否已失配
    //     （本波实测：55 条真实抑制仍精确匹配，未失配）。
    //     重新生成基线 = 删掉 app/lint-baseline.xml 再跑一次 :app:lintDebug
    //     （lint 会重建并中止构建，取产物提交后再跑一次即绿）；
    //     ⛔ 不要手工**新增/合成**条目 —— 路径形态与 id 匹配规则由 lint 决定；
    //        **删除**失效条目则是允许的（lint 会以 LintBaselineFixed 主动建议删：
    //        留着会让「被重新引入的问题」静默命中旧条目而永不报出）。
    //     ⛔ 基线只许缩不许涨：arch-guard 第 14 项守卫冻结其条目数（3）。
    //   * abortOnError = true + warningAsErrors = true —— 【Wave 32 第二轮翻转】
    //     CI 从此拦**新增**的 lint 问题（含 Warning 级 —— 只翻 abortOnError 的
    //     门禁面仅 3 条 Error，形同虚设；Wave 32 首轮的 83 条里 Warning 占 63 条）。
    //     实测分布（Build #262）：3 Error / 63 Warning / 17 Hints。
    //   * disable 三项版本通告类（GradleDependency 19 / NewerVersionAvailable 6 /
    //     AndroidGradlePluginVersion 3）—— 这三类会在**上游发版**时自动产生新
    //     issue：「没改代码 CI 也红」的门禁是坏门禁，会训练人无视红灯。它们是
    //     环境通告而非代码质量；需要跟进版本时手动查。**Wave 37 已把这 28 条
    //     失效条目从基线里删除** —— 它们因检查被 disable 而永远匹配不上（lint
    //     会报 LintBaselineFixed），留着只会让「基线里到底还剩什么」不可读。
    //   * checkReleaseBuilds = false —— 关掉 assembleRelease 附带的
    //     lintVital（fatal-only），release 构建不再被 lint 意外卡住。
    //   * checkDependencies = true —— 单点 :app:lintDebug 即覆盖 :app 与
    //     全部 8 个 library 模块的源码，无需逐模块跑 lint。
    //     盲区说明：纯 JVM（org.jetbrains.kotlin.jvm）模块没有 lint 任务，
    //     但本仓 9 个模块全部是 AGP 模块（core-model / core-design 用的
    //     也是 com.android.library），因此没有盲区。
    // ---------------------------------------------------------------------
    lint {
        abortOnError = true
        warningsAsErrors = true
        checkReleaseBuilds = false
        checkDependencies = true
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion")
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
