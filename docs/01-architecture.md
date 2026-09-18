# Android-Agent（LiquidAgent）架构方案与代码级契约 v1.0

> 作者：沈思远（solution-strategist） · 日期：2026-09-18
> 基线：`docs/00-recon-brief.md`（主理人祁研深侦察简报）。**本文件不推翻简报的任何硬约束。**
> 阅读对象：dev-A（基础设施 / 核心逻辑）、dev-B（设计系统 / UI / feature）。两人按 §9 文件清单零冲突并行。

## 0. 本文档的使用方式与三条铁律

### 0.1 三条铁律（违反即 CI 红）
1. **所有 Kotlin 片段可直接粘贴编译**。凡是我没把握的 API，我都做了「可摘除隔离」：单独一个文件 + 明确标注「若 CI 报 unresolved reference，删除本文件，其余不受影响」。dev 遇到这种情况**不要自己改 API**，按标注摘除并回报。
2. **版本矩阵（§1.2）是锁死的**，任何人不得升降级。CI 报错时只允许走简报 §4 已声明的回退路径（Kotlin 2.3.0 → 2.2.21）。
3. **不得新增依赖**。特别是：KSP / Room / Hilt / Dagger / Koin / Nav3 / Retrofit / Coil / 任何注解处理器 / 任何 JitPack 库。需要什么功能用手写替代，方案我已在下文给出。

### 0.2 知识库状态说明
本仓库为全新空仓，不存在 `references/cam-p-knowledge-base.md` 这类历史资产。因此本文档的"历史坑点规避"一栏，**依据来自简报 §3.1 的 LiteRT-LM API 摘录、§6 工程量约束，以及端侧推理/Android 构建的一般性经验教训**（已写入 §10 风险表）。后续每次 CI 失败与修复，请在 `docs/02-changelog.md` 追加一条"坑点记忆"，逐步沉淀为真实知识库。

### 0.3 名词表
| 名词 | 含义 |
|---|---|
| 引擎（Engine） | 真正跑模型的东西：LiteRT-LM 本地引擎 或 OpenAI 兼容远程引擎 |
| Agent 运行时 | 在引擎之上做「思考 → 工具调用 → 观察 → 继续」循环的编排层 |
| 材质（GlassMaterial） | Liquid Glass 的分层厚度：ultraThin / thin / regular / thick |
| 本地模型 | `.litertlm` / `.task` 文件，由用户自行下载导入，**CI 与构建期绝不下载** |

---

## 1. 模块划分与依赖图

### 1.1 命名决策：扁平 `:core-model`，不用 `:core:model`

**决策：全部使用扁平命名** `include(":core-model")`、`include(":feature-chat")`。

理由（按权重排序）：
1. **出错面最小**：扁平命名下 project path 与目录名**字面一致**（`:core-model` ↔ 根目录 `core-model/`）。嵌套命名 `:core:model` 要求目录必须是 `core/model/`，且 settings 里必须 `include(":core:model")`——一旦写成 `include(":core-model")` 而目录是 `core/model`，Gradle 报 "Project not found"，而这类错误在无法本地编译的环境下极难靠肉眼发现。
2. **与包名同构**：`com.rickeal.agent.core.model` ↔ `:core-model`，肉眼可交叉验证。
3. **CI 日志可读**：扁平名在 `--scan` / 日志里一眼定位，嵌套名会被折叠成 `:core:model` 与 `:core:engine` 两个短名混在一起。
4. **9 个模块的规模**根本不需要层级分组（层级是为 30+ 模块准备的）。

> 目录即 `Android-Agent/core-model/`、`Android-Agent/feature-chat/`……**不要**建 `core/` 父目录。

### 1.2 版本矩阵（锁死，见简报 §4）

| 项 | 值 | 备注 |
|---|---|---|
| Gradle | 9.7.1 | CI 用 `gradle/actions/setup-gradle@v6` 显式安装（`gradle` 在 PATH 上），不走 wrapper |
| AGP | 9.3.2 | **AGP 9 自带 Kotlin 支持**，见下方 ⚠️ |
| Kotlin | 由 AGP 9.3.2 内置 KGP 决定 | ⚠️ **不再由本仓库决定**，见下方 ⚠️。原"回退档 2.2.21"路径已失效 |
| JDK | 21 (temurin) | 构建工具链；字节码目标见 `jvmTarget` |
| compileSdk | 36 | AGP 9 块式 DSL：`compileSdk { version = release(36) }` |
| targetSdk | 36 | |
| minSdk | 31 | = gallery，`RenderEffect` 可用 |
| Compose BOM | 2026.02.00 | |
| androidx.core-ktx | 1.15.0 | |
| androidx.activity-compose | 1.10.1 | |
| lifecycle-runtime-ktx / viewmodel-compose / runtime-compose | 2.8.7 | 后两个简报未列，同版本补齐（必需） |
| navigation-compose | 2.8.9 | |
| kotlinx-serialization-json | 1.7.3 | |
| kotlinx-coroutines-android | 1.9.0 | 简报未列；若与 Compose BOM 冲突，Gradle 自动取高版本 |
| material-icons-extended | 1.7.8 | |
| datastore-preferences | 1.1.7 | |
| okhttp | 4.12.0 | |
| litertlm-android | 0.11.0 | **仅 `:core-engine` 依赖** |
| applicationId | `com.rickeal.agent` | 应用名 LiquidAgent |
| jvmTarget | **17** | 实测采用值（JDK 21 工具链 + 17 字节码目标）。本文 §9.5 模板写的 21 以本行为准 |

#### ⚠️ 1.2.1 AGP 9 内置 Kotlin —— 由 CI 实测修正（2026-09-18，提交 6fb4108）

**硬事实**：AGP 9.0+ 内置 Kotlin 支持，**禁止再显式应用 `org.jetbrains.kotlin.android`**。GitHub Actions 真实报错：

```
* Where: Build file 'app/build.gradle.kts' line: 12
An exception occurred applying plugin request [id: 'org.jetbrains.kotlin.android', version: '2.3.0']
> Failed to apply plugin 'org.jetbrains.kotlin.android'.
   > ⛔ Failed to apply plugin 'org.jetbrains.kotlin.android'
     The 'org.jetbrains.kotlin.android' plugin is no longer required for Kotlin support since AGP 9.0.
     Solution: Remove the 'org.jetbrains.kotlin.android' plugin from this project's build file: app/build.gradle.kts.
     See https://kotl.in/gradle/agp-built-in-kotlin for more details.
```

**规则**：
- ❌ 任何模块、包括根工程，**都不得**声明 `alias(libs.plugins.kotlin.android)`。
- ✅ `org.jetbrains.kotlin.plugin.compose` 与 `org.jetbrains.kotlin.plugin.serialization` **仍需显式声明**（AGP 不代管这两个）。
- ✅ `kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }` 保留可用（AGP 内置 KGP 后该扩展仍存在）。
- **Kotlin 版本由 AGP 内置的 KGP 决定**，不再是 `libs.versions.toml` 里的 `kotlin` 版本。

**回退路径（若未来降到 AGP 8.x）**：必须把 `org.jetbrains.kotlin.android` 加回 9 个模块 + 根工程，此时 `kotlin` 版本才重新由本仓库控制。

**⚠️ 由此产生的次生风险（最高优先级，见 §10 R16）**：`kotlin-compose` / `kotlin-serialization` 两个插件的版本目前仍 `version.ref = "kotlin"`（2.3.0）。若 AGP 9.3.2 内置的 Kotlin 不是 2.3.0，Compose 编译器插件会报版本不匹配。**必须核实 AGP 9.3.2 的 KGP 依赖版本，并把 catalog 里的 `kotlin` 对齐到该值。**

#### 1.2.1b 上游官方文档核实结论（Android Developers「迁移到内置 Kotlin」+ AGP 9.0 Release Notes）

| 事实 | 出处 | 对我们的影响 |
|---|---|---|
| AGP 9.0 对 **KGP 有运行时依赖**（AGP 9.0 = **KGP 2.2.10**）。声明更低版本会被 Gradle 自动升到该版本 | AGP 9.0 RN「对 Kotlin Gradle 插件的运行时依赖项」 | **AGP 9.3.2 的 KGP 版本需单独查**（见下方 →）。本文 `kotlin = "2.3.0"` 若**低于** AGP 9.3.2 的 KGP，会被自动升级 → compose 插件 2.3.0 与 KGP 不匹配 → **编译失败** |
| 升级到更高 KGP：在**顶层** build 文件 `buildscript { dependencies { classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:KGP_VERSION") } }` | 同上 | 这是"我们要用比 AGP 内置更高 Kotlin"的**官方指定做法**（不是改 version catalog） |
| 降级 KGP **必须**先 opt-out 内置 Kotlin；最低只能降到 2.0.0 | 同上 | 反向印证：内置 Kotlin 下 KGP 版本只能升不能降 |
| Opt-out 开关：`gradle.properties` 设 `android.builtInKotlin=false`，且**必须同时**设 `android.newDsl=false`；AGP 10.0 将移除 opt-out | 同上 | 一条兜底路径：实在过不去可以 opt-out 并把 `kotlin.android` 加回来。**但 AGP 10 会失效，只作临时手段** |
| 使用内置 Kotlin 时**无需**设置 `kotlin.compilerOptions.jvmTarget`，默认取 `android.compileOptions.targetCompatibility` | 同上 | 我们显式设了 `JVM_17` 而 `targetCompatibility=21` → **显式值优先**。两者不一致但都能编过；若要统一，建议删掉 jvmTarget 让它跟随 21，或把 compileOptions 也降到 17 |
| 逐模块 opt-out：`android { enableKotlin = false }`（纯 Java/无 Kotlin 模块可用，省编译开销） | 同上 | 本项目 9 个模块全有 Kotlin，不适用 |
| AGP 9.0 最低/默认 **Gradle 9.1.0** | AGP 9.0 RN「兼容性」 | 我们用 9.7.1 ✅ |
| AGP 9.0 最低/默认 **JDK 17** | 同上 | CI 用 JDK 21 ✅（更高允许） |
| AGP 9.0 支持最高 API **36.1** | 同上 | compileSdk 36 ✅ |
| ⚠️ `android.r8.proguardAndroidTxt.disallowed=true`：`getDefaultProguardFile()` **仅支持 `proguard-android-optimize.txt`**，`proguard-android.txt` 在 AGP 9.0 被禁 | AGP 9.0 RN「行为变更」 | **若 `app/build.gradle.kts` 里写的是 `getDefaultProguardFile("proguard-android.txt")`，会直接构建失败。** 必须改成 `-optimize.txt` |
| ⚠️ `android.uniquePackageNames` 默认 `true`：每个库必须有不同包名 | 同上 | 9 个模块 namespace 各不相同 ✅ |
| `android.newDsl` 默认 `true`（`false` 可 opt-out） | 同上 | 块式 DSL `compileSdk { version = release(36) }` 属于新 DSL，**与之相符**，是好信号 |
| `android.useAndroidx` 默认已翻转为 `true` | 同上 | 我们 gradle.properties 里显式写了，无害 |
| ⚠️ `android.enableAppCompileTimeRClass=true`：app 按**非 final** R 类编译 | 同上 | 若代码把 R 字段用在需要编译期常量的位置（`when`/`switch` 分支），会报错。本项目纯 Compose，风险低但需留意 |
| `android.sdk.defaultTargetSdkToCompileSdkIfUnset=true` | 同上 | 我们显式 `targetSdk=36`，不受影响 ✅ |
| `android.proguard.failOnMissingFiles=true`：keep 文件不存在即失败 | 同上 | `app/proguard-rules.pro` 存在 ✅ |

> **→ 必须去查的一个数**：打开 <https://developer.android.google.cn/build/releases/agp-9-3-0-release-notes>，看「**兼容性**」表格里 **Kotlin Gradle 插件 (KGP)** 那一行的「默认版本」。
> 然后把 `gradle/libs.versions.toml` 的 `kotlin` 改成**该值**（`kotlin-compose` / `kotlin-serialization` 会同步）。
> 若想用比它更高的 Kotlin，则按官方做法在**顶层** `build.gradle.kts` 加 `buildscript { dependencies { classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:<版本>") } }`，**不要**只改 catalog。

#### ⚠️ 1.2.2 AGP 9 时代需复核/已失效的建议清单（列给 team-lead，不自行改构建脚本）

| # | 本文原建议 | 状态 | 说明 |
|---|---|---|---|
| 1 | 「Kotlin 2.3.0，回退档 2.2.21，只改一行」 | **已失效** | Kotlin 版本改由 AGP 决定，改 catalog 一行不再能改 Kotlin 版本。回退路径需重新定义（改 AGP 版本，或查 AGP 是否提供覆盖内置 Kotlin 版本的开关） |
| 2 | 显式应用 `kotlin.android` | **已失效（禁止）** | 见 §1.2.1 |
| 3 | `kotlin-compose` / `kotlin-serialization` 用 `version.ref = "kotlin"` | **高风险待核** | 两插件版本必须与 AGP 内置 Kotlin 版本**严格一致**，否则 Compose 编译器报不匹配。需先查出 AGP 9.3.2 内置哪个 Kotlin 版本再对齐 |
| 4 | `jvmTarget = JVM_21` | 实测改为 **17** | §9.5 模板仍在写 21，以 §1.2 表格的 17 为准。17 更稳（AGP 9 内置 Kotlin 的下限），JDK 21 仅作工具链 |
| 5 | `compileSdk { version = release(36) }` 块式 DSL | **尚未被 CI 验证** | 报错停在插件应用阶段（line 12），`android {}` 块还没执行到。需下一次 CI 才能确认 |
| 6 | `buildFeatures { compose = true }` | **尚未被 CI 验证** | 同上。AGP 9 + 内置 Kotlin 下是否仍必需，需实测；若报 compose 相关错，这是第一个要查的点 |
| 7 | `packaging { jniLibs { useLegacyPackaging = true } }` | **尚未被 CI 验证** | 仅 `:app` 使用。若 AGP 9 改了 DSL，删掉不影响功能（只影响运行时 native 库解压方式） |
| 8 | `kotlin { compilerOptions { jvmTarget.set(...) } }` 写法 | 实测保留 | 若后续报该扩展不存在，回退 `kotlinOptions { jvmTarget = "17" }` |
| 9 | `kotlinx-coroutines-android 1.9.0` | **待核** | 若 AGP 内置 Kotlin 版本较高，coroutines 1.9.0 可能有元数据兼容告警；Gradle 会取高版本，暂不处理 |

### 1.3 模块清单（9 个）

| # | 模块 | namespace | 类型 | 职责 | 依赖 | 被谁依赖 |
|---|---|---|---|---|---|---|
| 1 | `:app` | `com.rickeal.agent` | com.android.application | 壳：Application / MainActivity / NavHost / DI 组装 | 全部 | — |
| 2 | `:core-model` | `com.rickeal.agent.core.model` | com.android.library | **纯领域模型 + 序列化 + 纯算法**。无 Android 依赖（除 `java.util`）、无 Compose | 无 | 全部 |
| 3 | `:core-engine` | `com.rickeal.agent.core.engine` | com.android.library | 引擎抽象 + LiteRT-LM 实现 + OpenAI 兼容实现 + 能力探测 | `:core-model` | `:core-agent`、feature、`app` |
| 4 | `:core-agent` | `com.rickeal.agent.core.agent` | com.android.library | Agent 循环、工具注册中心、内置工具、上下文压缩 | `:core-model`、`:core-engine` | feature、`app` |
| 5 | `:core-data` | `com.rickeal.agent.core.data` | com.android.library | DataStore + JSON 文件持久化 + 仓库 + **AppContainer + CompositionLocal** | `:core-model` | feature、`app` |
| 6 | `:core-design` | `com.rickeal.agent.core.design` | com.android.library | Liquid Glass 设计系统（tokens / 颜色 / 动效 / 组件）+ 窗口尺寸自适应工具 | 无（纯 Compose） | feature、`app` |
| 7 | `:feature-chat` | `com.rickeal.agent.feature.chat` | com.android.library | 对话页 + 参数面板 + 多模态输入 | 2,3,4,5,6 | `app` |
| 8 | `:feature-models` | `com.rickeal.agent.feature.models` | com.android.library | 模型库 / 导入 / 加载 / 后端选择 / 能力探测 | 2,3,5,6 | `app` |
| 9 | `:feature-settings` | `com.rickeal.agent.feature.settings` | com.android.library | 设置页 + **Agent 工具页（子包 `tools`）** + 远程端点 CRUD | 2,4,5,6 | `app` |

**为什么 Agent-Tools 屏放 `:feature-settings` 而不是新开模块**：简报要求控制在 7~9 个模块。工具页本质是「一组开关 + 一个试跑表单」，与设置页共享 `SettingsRepository`，独立成模块收益低于成本。它在 `feature-settings` 内以独立包 `com.rickeal.agent.feature.settings.tools` 隔离，未来要拆只需搬目录。

### 1.4 依赖图（ASCII，箭头 = "依赖"）

```
                            :app
          ┌──────────┬───────┼────────┬──────────┐
          │          │       │        │          │
   :feature-chat :feature-models :feature-settings
          │  \        │  \        │   \          │
          │   \       │   \       │    \         │
          │    \      │    \      │     \        │
          │     :core-agent   :core-data  :core-design
          │          │            │
          │     :core-engine      │
          │          │            │
          └──── :core-model ◄─────┘   （core-design 不依赖任何 project）
```

**无环校验**：`core-model` 出度 0；`core-design` 出度 0；`core-engine → core-model`；`core-data → core-model`；`core-agent → {model, engine}`；feature → {model, design, data, +engine/agent}；`app` 汇合。✓

**为什么 `:core-design` 不依赖 `:core-model`**：设计系统必须是"纯视觉"的，一旦依赖领域模型，`ModelDescriptor` 的每次字段变更都会触发全量 UI 重编译；且能独立预览（`@Preview` 不需要构造领域对象）。需要展示模型信息的卡片放在 feature 层组装。

---

## 2. 领域模型（`:core-model`，完整可编译代码）

**序列化统一约定**：
- 所有模型用 `Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }`。
- 时间一律 `Long` 毫秒，**不用 `Instant`**（避免额外依赖与序列化器）。
- `ByteArray` **不进领域模型**（会撑爆内存 + 序列化麻烦）。附件只存 `uri`/`path` 字符串，字节按需读取。若确实需要序列化字节，用 §2.9 的 `Base64ByteArraySerializer`。
- 枚举：kotlinx.serialization 原生支持，直接 `@Serializable enum class`。
- **sealed 基类只用「抽象函数」携带公共行为，绝不用「抽象属性」**——抽象属性在某些插件版本下会触发 duplicate serial name 问题；抽象函数不参与序列化，零风险。

### 2.1 `Ids.kt`

```kotlin
package com.rickeal.agent.core.model

import java.util.UUID

/** 统一 ID 生成入口。默认参数里可以直接调用。 */
fun newId(): String = UUID.randomUUID().toString()

fun newShortId(): String = UUID.randomUUID().toString().substring(0, 8)
```

### 2.2 `AgentJson.kt`

```kotlin
package com.rickeal.agent.core.model

import kotlinx.serialization.json.Json

/**
 * 全局 Json 实例。
 * - ignoreUnknownKeys：远程引擎/旧版本会话文件向前兼容
 * - explicitNulls=false：不写 null，文件更小、跨版本更稳
 * - encodeDefaults=true：保证旧字段不丢失
 */
object AgentJson {
    val Default: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        isLenient = true
        prettyPrint = false
    }

    val Pretty: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        prettyPrint = true
    }
}
```

### 2.3 `Role.kt`

```kotlin
package com.rickeal.agent.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class Role {
    SYSTEM,
    USER,
    MODEL,
    TOOL,
}
```

### 2.4 `Attachment.kt`（多模态附件）

```kotlin
package com.rickeal.agent.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 多模态附件。只保存「引用」，不保存字节。
 * 字节在真正调用引擎时由 :core-engine 的 AttachmentBytesReader 按需读取。
 */
@Serializable
sealed class Attachment {

    abstract fun key(): String
    abstract fun label(): String

    @Serializable
    @SerialName("text")
    data class Text(
        val id: String = newId(),
        val text: String = "",
        val name: String = "文本",
    ) : Attachment() {
        override fun key(): String = id
        override fun label(): String = name
    }

    @Serializable
    @SerialName("image")
    data class Image(
        val id: String = newId(),
        val uri: String = "",
        val name: String = "图片",
        val mimeType: String = "image/png",
        val width: Int = 0,
        val height: Int = 0,
        val sizeBytes: Long = 0L,
    ) : Attachment() {
        override fun key(): String = id
        override fun label(): String = name
    }

    @Serializable
    @SerialName("audio")
    data class Audio(
        val id: String = newId(),
        val uri: String = "",
        val name: String = "音频",
        val mimeType: String = "audio/wav",
        val durationMillis: Long = 0L,
        val sizeBytes: Long = 0L,
    ) : Attachment() {
        override fun key(): String = id
        override fun label(): String = name
    }

    @Serializable
    @SerialName("file")
    data class File(
        val id: String = newId(),
        val uri: String = "",
        val name: String = "文件",
        val mimeType: String = "*/*",
        val sizeBytes: Long = 0L,
    ) : Attachment() {
        override fun key(): String = id
        override fun label(): String = name
    }
}

/** 便捷取值：任意附件的稳定 key（用于 LazyColumn 的 item key）。 */
fun Attachment.stableKey(): String = key() + "_" + label()
```

### 2.5 `Generation.kt`（流式原语：chunk / finish / usage / tool delta）

```kotlin
package com.rickeal.agent.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class FinishReason {
    STOP,
    LENGTH,
    TOOL_CALLS,
    CANCELLED,
    ERROR,
    FILTER,
}

@Serializable
data class TokenUsage(
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val totalTokens: Int = 0,
    val tokensPerSecond: Float = 0f,
    val firstTokenLatencyMillis: Long = 0L,
    val decodeMillis: Long = 0L,
)

/**
 * 一次流式回调的最小增量。
 * 设计原则：所有字段都有默认值，引擎只填自己有的字段；上层用「非空即追加」的方式合并。
 */
data class GenerationChunk(
    val textDelta: String = "",
    val thinkingDelta: String = "",
    val toolCallDelta: ToolCallDelta? = null,
    val finishReason: FinishReason? = null,
    val usage: TokenUsage? = null,
)

/**
 * 增量式工具调用片段（对齐 OpenAI 的 delta.tool_calls）。
 * argumentsFragment 是 JSON 的「碎片」，需要按 index 累积后再整体解析。
 */
data class ToolCallDelta(
    val index: Int = 0,
    val id: String? = null,
    val name: String? = null,
    val argumentsFragment: String = "",
)
```

> `GenerationChunk` / `ToolCallDelta` **不加 `@Serializable`**：它们是进程内运行时对象，不落盘。落盘的是合并后的 `ToolCall`。

### 2.6 `ToolSpec.kt`（工具契约）

```kotlin
package com.rickeal.agent.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class ToolParamType {
    STRING,
    NUMBER,
    INTEGER,
    BOOLEAN,
    ARRAY,
    OBJECT,
}

@Serializable
data class ToolParameter(
    val name: String,
    val type: ToolParamType = ToolParamType.STRING,
    val description: String = "",
    val required: Boolean = true,
    val enumValues: List<String> = emptyList(),
)

/**
 * 工具的「声明」。用于：
 * 1) 传给原生 tool 通道（LiteRT-LM ToolProvider / OpenAI tools）
 * 2) 拼进系统提示词（文本协议模式）
 * 3) UI 展示与开关
 */
@Serializable
data class ToolSpec(
    val name: String,
    val description: String,
    val parameters: List<ToolParameter> = emptyList(),
    val requiresConfirmation: Boolean = false,
    val dangerous: Boolean = false,
    val category: String = "general",
) {
    /** 生成文本协议模式下写进 system prompt 的一行描述。 */
    fun toPromptLine(): String =
        "- " + name + "：" + description +
            (if (parameters.isEmpty()) "" else " 参数(" + parameters.joinToString(",") { it.name } + ")")
}

/** 一次工具调用请求。arguments 保持 JSON 字符串，避免嵌套 Any 的序列化地狱。 */
@Serializable
data class ToolCall(
    val id: String = newId(),
    val name: String = "",
    val argumentsJson: String = "{}",
    val raw: String = "",
)

@Serializable
data class ToolResult(
    val callId: String = "",
    val name: String = "",
    val ok: Boolean = true,
    val output: String = "",
    val errorMessage: String? = null,
    val elapsedMillis: Long = 0L,
    val truncated: Boolean = false,
)
```

### 2.7 `ChatMessage.kt` / `Conversation.kt`

```kotlin
package com.rickeal.agent.core.model

import kotlinx.serialization.Serializable

/**
 * 一条消息。不可变：流式期间由上层累加后「替换最后一条」，而不是原地改字段。
 */
@Serializable
data class ChatMessage(
    val id: String = newId(),
    val role: Role = Role.USER,
    val text: String = "",
    val thinking: String? = null,
    val attachments: List<Attachment> = emptyList(),
    val toolCalls: List<ToolCall> = emptyList(),
    val toolResults: List<ToolResult> = emptyList(),
    val createdAtMillis: Long = System.currentTimeMillis(),
    val usage: TokenUsage? = null,
    val finishReason: FinishReason? = null,
    val modelRef: String? = null,
    val errorMessage: String? = null,
) {
    val isEmpty: Boolean
        get() = text.isBlank() && thinking.isNullOrBlank() && attachments.isEmpty() && toolCalls.isEmpty()
}
```

```kotlin
package com.rickeal.agent.core.model

import kotlinx.serialization.Serializable

@Serializable
data class Conversation(
    val id: String = newId(),
    val title: String = "新对话",
    val createdAtMillis: Long = System.currentTimeMillis(),
    val updatedAtMillis: Long = System.currentTimeMillis(),
    val messages: List<ChatMessage> = emptyList(),
    val modelId: String? = null,
    val endpointId: String? = null,
    val config: InferenceConfig = InferenceConfig(),
    val summary: String? = null,
    val pinned: Boolean = false,
)

/** 列表页用的轻量摘要，避免把整条会话读进内存。 */
@Serializable
data class ConversationMeta(
    val id: String = newId(),
    val title: String = "新对话",
    val updatedAtMillis: Long = System.currentTimeMillis(),
    val messageCount: Int = 0,
    val preview: String = "",
    val modelId: String? = null,
    val pinned: Boolean = false,
)

fun Conversation.toMeta(): ConversationMeta = ConversationMeta(
    id = id,
    title = title,
    updatedAtMillis = updatedAtMillis,
    messageCount = messages.size,
    preview = messages.lastOrNull { it.role == Role.MODEL }?.text?.take(80)
        ?: messages.lastOrNull()?.text?.take(80).orEmpty(),
    modelId = modelId,
    pinned = pinned,
)
```

### 2.8 `SamplingParams.kt` / `InferenceConfig.kt` / `ThinkingMode.kt` / `InferenceBackend.kt`

```kotlin
package com.rickeal.agent.core.model

import kotlinx.serialization.Serializable

/** LiteRT-LM 与 OpenAI 的 backend 统一枚举。 */
@Serializable
enum class InferenceBackend {
    CPU,
    GPU,
    NPU,
}

@Serializable
enum class ThinkingMode {
    /** 关闭思考通道 */
    OFF,

    /** 强制开启（extraContext["enable_thinking"] = true） */
    ON,

    /** 由模型/端点能力决定：能开就开 */
    AUTO,
}

/**
 * 纯采样参数。注意：LiteRT-LM 的 SamplerConfig 只认 topK / topP / temperature，
 * repetitionPenalty 与 seed 需要在上层模拟或忽略（见 §6）。
 */
@Serializable
data class SamplingParams(
    val temperature: Float = 0.8f,
    val topP: Float = 0.95f,
    val topK: Int = 40,
    val repetitionPenalty: Float = 1.0f,
    val seed: Int? = null,
) {
    init {
        require(temperature >= 0f) { "temperature must be >= 0" }
        require(topP in 0f..1f) { "topP must be in [0,1]" }
        require(topK >= 1) { "topK must be >= 1" }
    }

    fun coerce(): SamplingParams = copy(
        temperature = temperature.coerceIn(0f, 2f),
        topP = topP.coerceIn(0f, 1f),
        topK = topK.coerceIn(1, 200),
        repetitionPenalty = repetitionPenalty.coerceIn(1f, 2f),
    )
}

@Serializable
enum class EngineKind {
    LOCAL,
    REMOTE,
}

/**
 * 一次推理的完整配置。这是「参数调节页」的唯一数据出口。
 */
@Serializable
data class InferenceConfig(
    val sampling: SamplingParams = SamplingParams(),
    val maxTokens: Int = 1024,
    val contextLength: Int = 4096,
    val backend: InferenceBackend = InferenceBackend.CPU,
    val visionBackend: InferenceBackend? = null,
    val audioBackend: InferenceBackend? = null,
    val thinking: ThinkingMode = ThinkingMode.AUTO,
    val systemInstruction: String = "",
    val engineKind: EngineKind = EngineKind.LOCAL,
    val remoteEndpointId: String? = null,
    val maxAgentRounds: Int = 8,
    val enableTools: Boolean = true,
    val stream: Boolean = true,
) {
    fun coerce(): InferenceConfig = copy(
        sampling = sampling.coerce(),
        maxTokens = maxTokens.coerceIn(64, 32768),
        contextLength = contextLength.coerceIn(512, 131072),
        maxAgentRounds = maxAgentRounds.coerceIn(1, 32),
    )
}
```

### 2.9 `ModelDescriptor.kt`

```kotlin
package com.rickeal.agent.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class ModelFamily {
    GEMMA_3N,
    GEMMA_3,
    QWEN_3,
    LLAMA,
    PHI,
    OTHER,
}

@Serializable
enum class Quantization {
    NONE,
    INT4,
    INT8,
    Q4_K_M,
    Q8,
    FP16,
    BF16,
    UNKNOWN,
}

/** 能力位：驱动 UI 的「哪些开关可用」与引擎的「要不要传 visionBackend」。 */
@Serializable
data class ModelCapabilities(
    val text: Boolean = true,
    val image: Boolean = false,
    val audio: Boolean = false,
    val toolCalling: Boolean = false,
    val thinking: Boolean = false,
    val speculativeDecoding: Boolean = false,
    val preferredBackends: Set<InferenceBackend> = setOf(InferenceBackend.CPU),
)

@Serializable
data class ModelDescriptor(
    val id: String = newId(),
    val displayName: String = "",
    val family: ModelFamily = ModelFamily.OTHER,
    /** .litertlm / .task 的绝对路径 */
    val path: String = "",
    val fileName: String = "",
    val sizeBytes: Long = 0L,
    val sha256: String? = null,
    val capabilities: ModelCapabilities = ModelCapabilities(),
    val defaultParams: SamplingParams = SamplingParams(),
    val quantization: Quantization = Quantization.UNKNOWN,
    val contextLength: Int = 4096,
    val version: String? = null,
    val sourceUrl: String? = null,
    val addedAtMillis: Long = System.currentTimeMillis(),
    val isBuiltIn: Boolean = false,
    val notes: String? = null,
) {
    fun exists(): Boolean = path.isNotBlank() && java.io.File(path).exists()

    fun sizeText(): String = when {
        sizeBytes <= 0L -> "未知"
        sizeBytes >= 1024L * 1024 * 1024 -> "%.2f GB".format(sizeBytes / 1024.0 / 1024.0 / 1024.0)
        else -> "%.1f MB".format(sizeBytes / 1024.0 / 1024.0)
    }
}
```

### 2.10 `RemoteEndpoint.kt`

```kotlin
package com.rickeal.agent.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class RemotePreset {
    OPENAI,
    DEEPSEEK,
    OLLAMA,
    VLLM,
    SILICONFLOW,
    CUSTOM,
}

/**
 * 不同后端「开启思考」的字段名不一样，这里用枚举收敛差异，避免在引擎里写 if-else 面条。
 */
@Serializable
enum class ThinkingParamStyle {
    /** 不支持 */
    NONE,

    /** body: enable_thinking = true（Qwen3 / vLLM 部分模型） */
    ENABLE_THINKING_BOOL,

    /** body: reasoning_effort = "low|medium|high"（OpenAI o 系列） */
    REASONING_EFFORT,

    /** body: chat_template_kwargs = { enable_thinking: true }（vLLM 另一派） */
    CHAT_TEMPLATE_KWARGS,
}

@Serializable
data class RemoteEndpoint(
    val id: String = newId(),
    val name: String = "",
    val baseUrl: String = "",
    val apiKey: String = "",
    val modelId: String = "",
    val preset: RemotePreset = RemotePreset.CUSTOM,
    val supportsThinking: Boolean = false,
    val thinkingParam: ThinkingParamStyle = ThinkingParamStyle.NONE,
    val thinkingEffort: String = "medium",
    val supportsTools: Boolean = false,
    val supportsVision: Boolean = false,
    val contextLength: Int = 32768,
    val extraHeaders: Map<String, String> = emptyMap(),
    val addedAtMillis: Long = System.currentTimeMillis(),
) {
    fun chatCompletionsUrl(): String {
        val base = baseUrl.trim().trimEnd('/')
        return if (base.endsWith("/chat/completions")) base else "$base/chat/completions"
    }

    val requiresApiKey: Boolean
        get() = preset != RemotePreset.OLLAMA
}

/** 预置端点，首次启动写入，用户可改。 */
object RemotePresets {
    fun openai(): RemoteEndpoint = RemoteEndpoint(
>     fun openai(): RemoteEndpoint = RemoteEndpoint(
>         id = "preset-openai",
>         name = "OpenAI",
>         baseUrl = "https://api.openai.com/v1",
>         preset = RemotePreset.OPENAI,
>         modelId = "gpt-4o-mini",
>         supportsTools = true,
>         supportsVision = true,
>         contextLength = 128000,
>     )
>     fun deepseek(): RemoteEndpoint = RemoteEndpoint(
>         id = "preset-deepseek",
>         name = "DeepSeek",
>         baseUrl = "https://api.deepseek.com/v1",
>         preset = RemotePreset.DEEPSEEK,
>         modelId = "deepseek-chat",
>         supportsThinking = true,
>         thinkingParam = ThinkingParamStyle.ENABLE_THINKING_BOOL,
>         supportsTools = true,
>         contextLength = 65536,
>     )
>     fun ollamaLocal(): RemoteEndpoint = RemoteEndpoint(
>         id = "preset-ollama",
>         name = "Ollama（本机）",
>         baseUrl = "http://10.0.2.2:11434/v1",
>         preset = RemotePreset.OLLAMA,
>         modelId = "qwen3:4b",
>         supportsThinking = true,
>         thinkingParam = ThinkingParamStyle.ENABLE_THINKING_BOOL,
>         contextLength = 32768,
>     )
>     fun all(): List<RemoteEndpoint> = listOf(openai(), deepseek(), ollamaLocal())
}
```

### 2.11 `Base64ByteArraySerializer.kt`（ByteArray 序列化方案）

```kotlin
package com.rickeal.agent.core.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.util.Base64

/**
 * ByteArray <-> Base64(String)。
 * 只在「必须把字节塞进可序列化对象」时使用（例如要落盘的缩略图）。
 * 常规图片/音频请走 Attachment.Image.uri，不要用它。
 */
object Base64ByteArraySerializer : KSerializer<ByteArray> {
    override val descriptor = PrimitiveSerialDescriptor("ByteArrayBase64", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: ByteArray) {
        encoder.encodeString(Base64.getEncoder().encodeToString(value))
    }

    override fun deserialize(decoder: Decoder): ByteArray {
        return Base64.getDecoder().decode(decoder.decodeString())
    }
}

/** 用法示例（不要直接照抄进模型，除非真的需要）：
 * @Serializable(with = Base64ByteArraySerializer::class) val thumb: ByteArray? = null
 */
```

### 2.12 `TokenEstimator.kt`（无 tokenizer 的朴素估算）

```kotlin
package com.rickeal.agent.core.model

import kotlin.math.ceil

/**
 * LiteRT-LM 0.11.0 没暴露 tokenizer，远程端点也不一定返回 usage。
 * 统一用「3.2 字符 ≈ 1 token」的英语近似（中文按 1 字 ≈ 1 token 更准，这里取折中）。
 * 用途只有一个：上下文窗口裁剪的预算判断。不要拿它给用户看精确数字。
 */
object TokenEstimator {
    private const val CHARS_PER_TOKEN = 3.2f

    fun estimate(text: String): Int {
        if (text.isEmpty()) return 0
        return ceil(text.length / CHARS_PER_TOKEN).toInt().coerceAtLeast(1)
    }

    fun estimate(message: ChatMessage): Int {
        var total = estimate(message.text) + estimate(message.thinking.orEmpty())
        for (attachment in message.attachments) {
            total += when (attachment) {
                is Attachment.Image -> 256
                is Attachment.Audio -> 128
                is Attachment.File -> 64
                is Attachment.Text -> estimate(attachment.text)
            }
        }
        for (call in message.toolCalls) total += estimate(call.argumentsJson)
        for (result in message.toolResults) total += estimate(result.output)
        return total
    }

    fun estimate(messages: List<ChatMessage>): Int = messages.sumOf { estimate(it) }
}
```

### 2.13 `StreamAccumulator.kt`（增量合并器，引擎与 Agent 共用）

```kotlin
package com.rickeal.agent.core.model

/**
 * 把一串 GenerationChunk 合并成「完整文本 + 思考文本 + 完整工具调用列表」。
 * 关键：ToolCallDelta 是 JSON 碎片，必须按 index 累积后再整体解析。
 */
class StreamAccumulator {
    private val textBuilder = StringBuilder()
    private val thinkingBuilder = StringBuilder()
    private val partials = LinkedHashMap<Int, PartialToolCall>()

    var finishReason: FinishReason? = null
        private set

    var usage: TokenUsage? = null
        private set

    val text: String get() = textBuilder.toString()
    val thinking: String get() = thinkingBuilder.toString()

    fun append(chunk: GenerationChunk) {
        if (chunk.textDelta.isNotEmpty()) textBuilder.append(chunk.textDelta)
        if (chunk.thinkingDelta.isNotEmpty()) thinkingBuilder.append(chunk.thinkingDelta)
        val delta = chunk.toolCallDelta
        if (delta != null) {
            val partial = partials.getOrPut(delta.index) { PartialToolCall() }
            if (delta.id != null) partial.id = delta.id
            if (delta.name != null) partial.name = delta.name
            if (delta.argumentsFragment.isNotEmpty()) partial.arguments.append(delta.argumentsFragment)
        }
        if (chunk.finishReason != null) finishReason = chunk.finishReason
        if (chunk.usage != null) usage = chunk.usage
    }

    fun toolCalls(): List<ToolCall> = partials.entries
        .sortedBy { it.key }
        .filter { it.value.name.isNotBlank() }
        .map {
            ToolCall(
                id = it.value.id,
                name = it.value.name,
                argumentsJson = it.value.arguments.toString().ifBlank { "{}" },
            )
        }

    private class PartialToolCall(
        var id: String = newId(),
        var name: String = "",
        val arguments: StringBuilder = StringBuilder(),
    )
}

/**
 * 增量提取器。
 * 存在意义：LiteRT-LM 的 onMessage(message) 到底给的是「本帧增量」还是「到目前为止的全文」，
 * 简报 §3.1 无法确定（gallery 里两种用法都见过）。这个启发式对两种情况都正确：
 *  - 若 incoming 以 last 开头且更长 → 认为累积式，取后缀
 *  - 否则 → 认为增量式，整体作为 delta
 */
class DeltaTracker {
    private var last: String = ""

    fun next(incoming: String): String {
        if (incoming.isEmpty()) return ""
        val delta = if (incoming.length > last.length && incoming.startsWith(last)) {
            incoming.substring(last.length)
        } else {
            incoming
        }
        last = incoming
        return delta
    }

    fun reset() {
        last = ""
    }
}
```

---

## 3. 引擎抽象层（`:core-engine`）

### 3.1 设计要点总览

| 关注点 | 决策 |
|---|---|
| 流式形态 | `Flow<GenerationChunk>`，**不用回调**。回调在 UI 侧会导致生命周期/取消地狱 |
| 线程模型 | 每个引擎实例持有一个 `Dispatchers.IO.limitedParallelism(1)`，所有引擎调用串行化其上；加载/创建会话是阻塞调用，必须在 IO 单线程 |
| 回压策略 | **永不丢 token**：显式 `Channel<GenerationChunk>(Channel.UNLIMITED)`，`trySend` 恒成功；`consumeAsFlow()` 转成 Flow |
| 取消语义 | 流被取消（collector 取消）→ `finally` 里调 `conversation.cancelProcess()`；`stop()` 主动取消 |
| 错误语义 | 统一 `EngineException`；`Channel.close(cause)` 让错误沿 Flow 抛出，UI 只需 `catch` |
| 生命周期 | `load` 幂等（同 modelId+config 直接返回）；`unload` 释放会话保留引擎；`close` 全释放 |

### 3.2 `EngineContract.kt`

```kotlin
package com.rickeal.agent.core.engine

import com.rickeal.agent.core.model.ChatMessage
import com.rickeal.agent.core.model.EngineKind
import com.rickeal.agent.core.model.InferenceBackend
import com.rickeal.agent.core.model.InferenceConfig
import com.rickeal.agent.core.model.ModelDescriptor
import com.rickeal.agent.core.model.RemoteEndpoint
import com.rickeal.agent.core.model.ToolSpec
import kotlinx.coroutines.flow.Flow

/** 引擎加载所需的一切。刻意不传 Context —— 只传字符串，便于测试与隔离。 */
data class EngineLoadConfig(
    val model: ModelDescriptor? = null,
    val remote: RemoteEndpoint? = null,
    val config: InferenceConfig = InferenceConfig(),
    /** context.cacheDir —— LiteRT-LM 的权重缓存目录 */
    val cacheDir: String? = null,
    /** context.applicationInfo.nativeLibraryDir —— NPU 后端必需 */
    val nativeLibraryDir: String? = null,
    /** context.getExternalFilesDir(null) —— gallery 用它作 cacheDir */
    val externalFilesDir: String? = null,
    /** 附件（图片/音频/文件）字节读取的根目录白名单 */
    val sandboxDir: String? = null,
)

data class GenerationRequest(
    val messages: List<ChatMessage>,
    val config: InferenceConfig = InferenceConfig(),
    val model: ModelDescriptor? = null,
    val remote: RemoteEndpoint? = null,
    val tools: List<ToolSpec> = emptyList(),
    /**
     * 会话标识。LiteRT-LM 的 Conversation 自带历史，本引擎的策略是：
     * conversationId 变化 => 关闭旧 Conversation 并重建（不回放历史，见 §3.4）。
     */
    val conversationId: String? = null,
)

/** 探测出来的引擎能力。驱动 UI 的开关可用性与 Agent 的工具通道选择。 */
data class EngineCapabilities(
    val supportsText: Boolean = true,
    val supportsImage: Boolean = false,
    val supportsAudio: Boolean = false,
    val supportsTools: Boolean = false,
    val supportsThinking: Boolean = false,
    val supportedBackends: Set<InferenceBackend> = setOf(InferenceBackend.CPU),
    val maxContextTokens: Int = 4096,
    /** 是否支持「模型原生 tool 通道」。false 时 Agent 必须走文本协议。 */
    val nativeToolChannel: Boolean = false,
    val nativeThinkingChannel: Boolean = false,
    val engineLabel: String = "",
)

class EngineException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

interface LlmEngine {
    val kind: EngineKind

    /** 当前是否已可生成。 */
    val isLoaded: Boolean

    /** 幂等加载。同 model/endpoint + 同 config 时直接返回。 */
    suspend fun load(config: EngineLoadConfig)

    /** 释放会话/连接，保留引擎对象。 */
    suspend fun unload()

    suspend fun capabilities(): EngineCapabilities

    /** 冷流：collect 时才真正开始生成。取消 collect 即取消生成。 */
    fun generateStream(request: GenerationRequest): Flow<com.rickeal.agent.core.model.GenerationChunk>

    /** 主动停止（等价取消）。 */
    suspend fun stop()

    /** 估算 token 数。LiteRT-LM 无 tokenizer，走启发式。 */
    suspend fun tokenCount(text: String): Int

    /** 彻底释放，之后必须重新 load。 */
    fun close()
}

interface EngineFactory {
    fun create(kind: EngineKind): LlmEngine
    fun closeAll()
}
```

### 3.3 `GenerationAccumulator` 已移至 `:core-model`（见 §2.13）

> 放在 core-model 是因为 Agent 与 UI 都要用，而 core-model 被所有人依赖。

### 3.4 `LiteRtLmEngine.kt`（核心：回调 → Flow 的桥接）

```kotlin
package com.rickeal.agent.core.engine.local

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Capabilities
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation as LiteRtConversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import com.rickeal.agent.core.engine.EngineCapabilities
import com.rickeal.agent.core.engine.EngineException
import com.rickeal.agent.core.engine.EngineLoadConfig
import com.rickeal.agent.core.engine.GenerationRequest
import com.rickeal.agent.core.engine.LlmEngine
import com.rickeal.agent.core.model.Attachment
import com.rickeal.agent.core.model.DeltaTracker
import com.rickeal.agent.core.model.EngineKind
import com.rickeal.agent.core.model.FinishReason
import com.rickeal.agent.core.model.GenerationChunk
import com.rickeal.agent.core.model.InferenceBackend
import com.rickeal.agent.core.model.Role
import com.rickeal.agent.core.model.ThinkingMode
import com.rickeal.agent.core.model.TokenEstimator
import com.rickeal.agent.core.model.TokenUsage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.cancellable
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 简报 §3.1：思考文本走 channels["thought"]。 */
internal const val THOUGHT_CHANNEL = "thought"

class LiteRtLmEngine(
    private val engineDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1),
) : LlmEngine {

    override val kind: EngineKind = EngineKind.LOCAL

    private val mutex = Mutex()
    private var engine: Engine? = null
    private var conversation: LiteRtConversation? = null
    private var loadedModelPath: String? = null
    private var loadedMaxTokens: Int = -1
    private var loadedBackend: InferenceBackend? = null
    private var currentConversationId: String? = null
    private var loadConfig: EngineLoadConfig? = null

    @Volatile
    private var loaded: Boolean = false

    override val isLoaded: Boolean
        get() = loaded

    // ---------------------------------------------------------------- load

    override suspend fun load(config: EngineLoadConfig) {
        withContext(engineDispatcher) {
            mutex.withLock {
                val modelPath = config.model?.path
                if (modelPath.isNullOrBlank()) {
                    throw EngineException("LiteRT-LM: modelPath 为空")
                }
                val sameEngine = engine != null &&
                    loadedModelPath == modelPath &&
                    loadedMaxTokens == config.config.maxTokens &&
                    loadedBackend == config.config.backend
                if (sameEngine) {
                    loadConfig = config
                    loaded = true
                    return@withLock
                }
                releaseInternal()

                val backend = toBackend(config.config.backend, config.nativeLibraryDir)
                val wantsVision = config.model?.capabilities?.image == true
                val wantsAudio = config.model?.capabilities?.audio == true

                val engineConfig = EngineConfig(
                    modelPath = modelPath,
                    backend = backend,
                    visionBackend = if (wantsVision) {
                        toBackend(config.config.visionBackend ?: InferenceBackend.GPU, config.nativeLibraryDir)
                    } else {
                        null
                    },
                    audioBackend = if (wantsAudio) {
                        toBackend(config.config.audioBackend ?: InferenceBackend.CPU, config.nativeLibraryDir)
                    } else {
                        null
                    },
                    maxNumTokens = config.config.maxTokens,
                    cacheDir = config.externalFilesDir ?: config.cacheDir,
                )

                val created = try {
                    Engine(engineConfig)
                } catch (t: Throwable) {
                    throw EngineException("LiteRT-LM: 创建 Engine 失败 (${t.message})", t)
                }
                try {
                    created.initialize()
                } catch (t: Throwable) {
                    runCatching { created.close() }
                    throw EngineException("LiteRT-LM: initialize 失败 (${t.message})", t)
                }

                engine = created
                loadedModelPath = modelPath
                loadedMaxTokens = config.config.maxTokens
                loadedBackend = config.config.backend
                loadConfig = config
                loaded = true
            }
        }
    }

    private fun toBackend(backend: InferenceBackend, nativeLibraryDir: String?): Backend = when (backend) {
        InferenceBackend.CPU -> Backend.CPU()
        InferenceBackend.GPU -> Backend.GPU()
        // 简报 §3.1：NPU 需要 nativeLibraryDir，且 samplerConfig 必须为 null
        InferenceBackend.NPU -> Backend.NPU(nativeLibraryDir = nativeLibraryDir ?: "")
    }

    // -------------------------------------------------------- conversation

    private fun ensureConversation(request: GenerationRequest): LiteRtConversation {
        val currentEngine = engine
            ?: throw EngineException("LiteRT-LM: 引擎未加载，请先 load()")
        if (request.conversationId != currentConversationId) {
            runCatching { conversation?.close() }
            conversation = null
        }
        val existing = conversation
        if (existing != null) return existing

        val cfg = request.config
        val isNpu = cfg.backend == InferenceBackend.NPU
        // 简报：NPU 后端 samplerConfig 必须为 null
        val samplerConfig = if (isNpu) {
            null
        } else {
            SamplerConfig(
                topK = cfg.sampling.topK,
                topP = cfg.sampling.topP.toDouble(),
                temperature = cfg.sampling.temperature.toDouble(),
            )
        }
        val created = currentEngine.createConversation(
            ConversationConfig(
                samplerConfig = samplerConfig,
                systemInstruction = null,
                tools = emptyList(),
                initialMessages = emptyList(),
            )
        )
        conversation = created
        currentConversationId = request.conversationId
        return created
    }

    // -------------------------------------------------------- generate

    override fun generateStream(request: GenerationRequest): Flow<GenerationChunk> = flow {
        val conv = ensureConversation(request)
        val thinkingOn = when (request.config.thinking) {
            ThinkingMode.ON -> true
            ThinkingMode.OFF -> false
            ThinkingMode.AUTO -> request.model?.capabilities?.thinking == true
        }
        val extraContext: Map<String, Any> =
            if (thinkingOn) mapOf("enable_thinking" to true) else emptyMap()

        // UNLIMITED：LLM 流式决不能丢 token，宁可堆积内存（chunk 只有几十字节）
        val channel = Channel<GenerationChunk>(Channel.UNLIMITED)
        val textTracker = DeltaTracker()
        val thoughtTracker = DeltaTracker()
        val startNs = System.nanoTime()
        var firstTokenNs = 0L
        var chunkCount = 0

        val callback = object : MessageCallback {
            override fun onMessage(message: Message) {
                if (firstTokenNs == 0L) firstTokenNs = System.nanoTime()
                val textDelta = textTracker.next(message.toString())
                val thoughtDelta = thoughtTracker.next(message.channels[THOUGHT_CHANNEL] ?: "")
                if (textDelta.isEmpty() && thoughtDelta.isEmpty()) return
                chunkCount++
                channel.trySend(
                    GenerationChunk(textDelta = textDelta, thinkingDelta = thoughtDelta)
                )
            }

            override fun onDone() {
                val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
                val tps = if (elapsedMs > 0) chunkCount * 1000f / elapsedMs else 0f
                channel.trySend(
                    GenerationChunk(
                        finishReason = FinishReason.STOP,
                        usage = TokenUsage(
                            promptTokens = TokenEstimator.estimate(request.messages),
                            completionTokens = chunkCount,
                            totalTokens = TokenEstimator.estimate(request.messages) + chunkCount,
                            tokensPerSecond = tps,
                            firstTokenLatencyMillis = if (firstTokenNs == 0L) 0L else (firstTokenNs - startNs) / 1_000_000L,
                            decodeMillis = elapsedMs,
                        ),
                    )
                )
                channel.close()
            }

            override fun onError(throwable: Throwable) {
                channel.close(EngineException("LiteRT-LM: 生成失败 (${throwable.message})", throwable))
            }
        }

        val contents = buildContents(request)
        conv.sendMessageAsync(Contents.of(contents), callback, extraContext)

        try {
            channel.consumeAsFlow().collect { chunk -> emit(chunk) }
        } finally {
            // 流结束（正常 / 取消 / 异常）都确保底层停止，避免 GPU 继续烧电
            runCatching { conv.cancelProcess() }
            runCatching { channel.cancel() }
        }
    }
        .flowOn(engineDispatcher)
        .cancellable()

    private fun buildContents(request: GenerationRequest): List<Content> {
        val out = ArrayList<Content>(4)
        val last = request.messages.lastOrNull { it.role == Role.USER }
            ?: request.messages.lastOrNull()
        if (last != null) {
            for (attachment in last.attachments) {
                when (attachment) {
                    is Attachment.Image -> AttachmentBytesReader.imagePngBytes(attachment.uri)
                        ?.let { out.add(Content.ImageBytes(it)) }
                    is Attachment.Audio -> AttachmentBytesReader.audioBytes(attachment.uri)
                        ?.let { out.add(Content.AudioBytes(it)) }
                    is Attachment.Text -> if (attachment.text.isNotBlank()) {
                        out.add(Content.Text(attachment.text))
                    }
                    is Attachment.File -> Unit
                }
            }
        }
        // 简报 §3.1：文本必须放在最后
        out.add(Content.Text(last?.text.orEmpty()))
        return out
    }

    // -------------------------------------------------------- misc

    override suspend fun capabilities(): EngineCapabilities {
        return withContext(engineDispatcher) {
            val model = loadConfig?.model
            val path = model?.path
            var speculative = false
            if (!path.isNullOrBlank()) {
                speculative = try {
                    Capabilities(path).use { it.hasSpeculativeDecodingSupport() }
                } catch (t: Throwable) {
                    false
                }
            }
            val caps = model?.capabilities
            EngineCapabilities(
                supportsText = caps?.text ?: true,
                supportsImage = caps?.image ?: false,
                supportsAudio = caps?.audio ?: false,
                supportsTools = caps?.toolCalling ?: false,
                supportsThinking = caps?.thinking ?: false,
                supportedBackends = caps?.preferredBackends ?: setOf(InferenceBackend.CPU),
                maxContextTokens = model?.contextLength ?: 4096,
                nativeToolChannel = false,
                nativeThinkingChannel = caps?.thinking ?: false,
                engineLabel = "LiteRT-LM ${model?.displayName.orEmpty()}",
            )
        }
    }

    override suspend fun stop() {
        withContext(engineDispatcher) {
            runCatching { conversation?.cancelProcess() }
        }
    }

    override suspend fun tokenCount(text: String): Int = TokenEstimator.estimate(text)

    override suspend fun unload() {
        withContext(engineDispatcher) {
            mutex.withLock {
                runCatching { conversation?.close() }
                conversation = null
                currentConversationId = null
                loaded = false
            }
        }
    }

    override fun close() {
        runCatching { conversation?.close() }
        runCatching { engine?.close() }
        conversation = null
        engine = null
        loadedModelPath = null
        loaded = false
    }

    private fun releaseInternal() {
        runCatching { conversation?.close() }
        runCatching { engine?.close() }
        conversation = null
        engine = null
        currentConversationId = null
        loaded = false
    }
}
```

> **关键解释（dev-A 必读）**
> 1. **为什么不用 `callbackFlow`**：`callbackFlow` 内部 channel 容量是 `Channel.BUFFERED`(64)，`trySend` 在缓冲满时会返回失败并**丢帧**——LLM 流式丢一个 token 就是丢字。显式 `Channel(UNLIMITED)` + `consumeAsFlow()` 是唯一"绝不丢"的方案。
> 2. **为什么 `flow{}` 而不是 `callbackFlow{}.awaitClose{}`**：`flow{}` 里我们可以同步拿到 `conv` 引用，并在 `finally` 中精确 `cancelProcess()`，语义比 `awaitClose` 更直白。
> 3. **为什么 `flowOn(engineDispatcher)`**：`ensureConversation` / `sendMessageAsync` 是阻塞调用；`flowOn` 让整个 flow 体（含 emit）跑在单一 IO 线程上。LiteRT 的回调线程只做 `trySend`（线程安全），不触碰任何共享状态。
> 4. **为什么 `ConversationConfig` 里 `systemInstruction/tools/initialMessages` 全给空**：这三个参数在 0.11.0 的确切构造方式我们无法核对，**传空值是最保险**。系统提示词改由上层的 `messages[0]`（`role=SYSTEM`）承载；工具改由 Agent 层的文本协议 + `nativeToolChannel=false` 兜底（§4.4）。这是**有意的能力降级换编译确定性**。
> 5. **Conversation 生命周期**：`conversationId` 变化即重建会话，**不回放历史**（因为 `Message` 的构造 API 未核对）。历史由 `:core-data` 持久化，UI 侧展示；引擎侧只保证"当前会话连续"。这是一处明确的取舍，写进 README。

### 3.5 `AttachmentBytesReader.kt`

```kotlin
package com.rickeal.agent.core.engine.local

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 把 uri / 绝对路径 读成引擎需要的字节。
 * 图片统一转 PNG（Content.ImageBytes 要求 PNG）；音频直接给原始字节（调用方保证 16kHz mono WAV）。
 */
object AttachmentBytesReader {

    fun imagePngBytes(uri: String): ByteArray? {
        if (uri.isBlank()) return null
        return try {
            val file = File(stripScheme(uri))
            if (!file.exists()) return null
            val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return null
            val scaled = downscale(bitmap, 1024L * 1024L)
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.PNG, 90, out)
            if (scaled !== bitmap) scaled.recycle()
            bitmap.recycle()
            out.toByteArray()
        } catch (t: Throwable) {
            null
        }
    }

    fun audioBytes(uri: String): ByteArray? {
        if (uri.isBlank()) return null
        return try {
            val file = File(stripScheme(uri))
            if (!file.exists()) return null
            file.readBytes()
        } catch (t: Throwable) {
            null
        }
    }

    private fun stripScheme(uri: String): String =
        if (uri.startsWith("file://")) uri.removePrefix("file://") else uri

    /** 简单按像素总量等比缩小，避免 4000x3000 的原图把 4B 模型的显存打爆。 */
    private fun downscale(bitmap: Bitmap, maxPixels: Long): Bitmap {
        val pixels = bitmap.width.toLong() * bitmap.height.toLong()
        if (pixels <= maxPixels) return bitmap
        val ratio = kotlin.math.sqrt(maxPixels.toDouble() / pixels.toDouble())
        val w = (bitmap.width * ratio).toInt().coerceAtLeast(1)
        val h = (bitmap.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, w, h, true)
    }
}
```

### 3.6 `OpenAiDto.kt` + `OpenAiCompatibleEngine.kt`

```kotlin
package com.rickeal.agent.core.engine.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
internal data class ChatRequestDto(
    val model: String,
    val messages: List<MessageDto>,
    val stream: Boolean = true,
    val temperature: Float? = null,
    @SerialName("top_p") val topP: Float? = null,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    val seed: Int? = null,
    @SerialName("frequency_penalty") val frequencyPenalty: Float? = null,
    val tools: List<ToolDto>? = null,
    @SerialName("tool_choice") val toolChoice: String? = null,
    @SerialName("enable_thinking") val enableThinking: Boolean? = null,
    @SerialName("reasoning_effort") val reasoningEffort: String? = null,
    @SerialName("chat_template_kwargs") val chatTemplateKwargs: JsonObject? = null,
)

@Serializable
internal data class ToolDto(
    val type: String = "function",
    val function: FunctionDto,
)

@Serializable
internal data class FunctionDto(
    val name: String,
    val description: String,
    val parameters: JsonObject,
)

@Serializable
internal data class MessageDto(
    val role: String,
    val content: JsonElement? = null,
    val name: String? = null,
    @SerialName("tool_calls") val toolCalls: List<ToolCallDto>? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
)

@Serializable
internal data class ToolCallDto(
    val id: String,
    val type: String = "function",
    val function: ToolFunctionDto,
)

@Serializable
internal data class ToolFunctionDto(
    val name: String? = null,
    val arguments: String? = null,
)

@Serializable
internal data class StreamChunkDto(
    val id: String? = null,
    val choices: List<StreamChoiceDto> = emptyList(),
    val usage: StreamUsageDto? = null,
)

@Serializable
internal data class StreamChoiceDto(
    val index: Int = 0,
    val delta: StreamDeltaDto? = null,
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
internal data class StreamDeltaDto(
    val content: String? = null,
    /** DeepSeek / vLLM */
    @SerialName("reasoning_content") val reasoningContent: String? = null,
    /** 另一派命名 */
    val reasoning: String? = null,
    @SerialName("tool_calls") val toolCalls: List<StreamToolCallDto>? = null,
)

@Serializable
internal data class StreamToolCallDto(
    val index: Int = 0,
    val id: String? = null,
    val type: String? = null,
    val function: StreamToolFunctionDto? = null,
)

@Serializable
internal data class StreamToolFunctionDto(
    val name: String? = null,
    val arguments: String? = null,
)

@Serializable
internal data class StreamUsageDto(
    @SerialName("prompt_tokens") val promptTokens: Int = 0,
    @SerialName("completion_tokens") val completionTokens: Int = 0,
    @SerialName("total_tokens") val totalTokens: Int = 0,
)
```

```kotlin
package com.rickeal.agent.core.engine.remote

import com.rickeal.agent.core.engine.EngineCapabilities
import com.rickeal.agent.core.engine.EngineException
import com.rickeal.agent.core.engine.EngineLoadConfig
import com.rickeal.agent.core.engine.GenerationRequest
import com.rickeal.agent.core.engine.LlmEngine
import com.rickeal.agent.core.model.Attachment
import com.rickeal.agent.core.model.ChatMessage
import com.rickeal.agent.core.model.EngineKind
import com.rickeal.agent.core.model.FinishReason
import com.rickeal.agent.core.model.GenerationChunk
import com.rickeal.agent.core.model.InferenceConfig
import com.rickeal.agent.core.model.RemoteEndpoint
import com.rickeal.agent.core.model.Role
import com.rickeal.agent.core.model.ThinkingMode
import com.rickeal.agent.core.model.ThinkingParamStyle
import com.rickeal.agent.core.model.ToolCallDelta
import com.rickeal.agent.core.model.ToolSpec
import com.rickeal.agent.core.model.TokenUsage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.cancellable
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * OpenAI 兼容后端引擎（DeepSeek / OpenAI / Ollama / vLLM / SiliconFlow）。
 * SSE 手写解析，不用 Retrofit（简报 §6）。
 */
class OpenAiCompatibleEngine(
    private val baseClient: OkHttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : LlmEngine {

    override val kind: EngineKind = EngineKind.REMOTE

    private var endpoint: RemoteEndpoint? = null

    @Volatile
    private var loaded: Boolean = false

    override val isLoaded: Boolean
        get() = loaded

    override suspend fun load(config: EngineLoadConfig) {
        val remote = config.remote ?: throw EngineException("远程引擎：未配置 RemoteEndpoint")
        if (remote.baseUrl.isBlank()) throw EngineException("远程引擎：baseUrl 为空")
        if (remote.requiresApiKey && remote.apiKey.isBlank()) {
            throw EngineException("远程引擎：${remote.name} 需要填写 API Key")
        }
        endpoint = remote
        loaded = true
    }

    override suspend fun unload() {
        loaded = false
    }

    override suspend fun capabilities(): EngineCapabilities {
        val remote = endpoint
        return EngineCapabilities(
            supportsText = true,
            supportsImage = remote?.supportsVision ?: false,
            supportsAudio = false,
            supportsTools = remote?.supportsTools ?: false,
            supportsThinking = remote?.supportsThinking ?: false,
            supportedBackends = emptySet(),
            maxContextTokens = remote?.contextLength ?: 32768,
            nativeToolChannel = remote?.supportsTools ?: false,
            nativeThinkingChannel = remote?.supportsThinking ?: false,
            engineLabel = remote?.name.orEmpty(),
        )
    }

    override fun generateStream(request: GenerationRequest): Flow<GenerationChunk> = flow {
        val remote = request.remote ?: endpoint
            ?: throw EngineException("远程引擎：未配置 RemoteEndpoint")
        val config = request.config
        val payload = ChatRequestDto(
            model = remote.modelId,
            messages = buildMessages(request.messages),
            stream = true,
            temperature = config.sampling.temperature,
            topP = config.sampling.topP,
            maxTokens = config.maxTokens,
            seed = config.sampling.seed,
            // OpenAI 无 repetition penalty，用 frequency_penalty 近似
            frequencyPenalty = ((config.sampling.repetitionPenalty - 1f) * 2f)
                .coerceIn(-2f, 2f).takeIf { config.sampling.repetitionPenalty != 1f },
            tools = if (remote.supportsTools && request.tools.isNotEmpty()) {
                request.tools.map { it.toDto() }
            } else {
                null
            },
            toolChoice = if (remote.supportsTools && request.tools.isNotEmpty()) "auto" else null,
            enableThinking = if (remote.thinkingParam == ThinkingParamStyle.ENABLE_THINKING_BOOL) {
                thinkingEnabled(config, remote)
            } else {
                null
            },
            reasoningEffort = if (remote.thinkingParam == ThinkingParamStyle.REASONING_EFFORT) {
                remote.thinkingEffort
            } else {
                null
            },
            chatTemplateKwargs = if (remote.thinkingParam == ThinkingParamStyle.CHAT_TEMPLATE_KWARGS) {
                buildJsonObject { put("enable_thinking", JsonPrimitive(thinkingEnabled(config, remote) ?: false)) }
            } else {
                null
            },
        )

        val requestBody = json.encodeToString(payload)
        val httpRequest = Request.Builder()
            .url(remote.chatCompletionsUrl())
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "text/event-stream")
            .apply {
                if (remote.apiKey.isNotBlank()) {
                    addHeader("Authorization", "Bearer ${remote.apiKey}")
                }
                for ((key, value) in remote.extraHeaders) addHeader(key, value)
            }
            .post(requestBody.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        // 流式读取不能设 readTimeout
        val client = baseClient.newBuilder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
        val call = client.newCall(httpRequest)
        val startNs = System.nanoTime()
        var firstTokenNs = 0L
        var chunkCount = 0

        val response = try {
            call.execute()
        } catch (t: Throwable) {
            throw EngineException("远程引擎：请求失败 (${t.message})", t)
        }

        try {
            if (!response.isSuccessful) {
                val errorBody = runCatching { response.body?.string() }.getOrNull().orEmpty()
                throw EngineException("远程引擎：HTTP ${response.code} ${errorBody.take(300)}")
            }
            val source = response.body?.source()
                ?: throw EngineException("远程引擎：响应体为空")

            while (true) {
                val line = source.readUtf8Line() ?: break
                if (line.isBlank()) continue
                if (!line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                if (data.isEmpty()) continue
                if (data == "[DONE]") break

                val dto = try {
                    json.decodeFromString<StreamChunkDto>(data)
                } catch (t: Throwable) {
                    continue // 心跳 / 非法行，跳过
                }

                val choice = dto.choices.firstOrNull()
                val delta = choice?.delta
                if (delta != null) {
                    if (firstTokenNs == 0L) firstTokenNs = System.nanoTime()
                    val text = delta.content.orEmpty()
                    val thinking = delta.reasoningContent ?: delta.reasoning.orEmpty()
                    if (text.isNotEmpty() || thinking.isNotEmpty()) {
                        chunkCount++
                        emit(GenerationChunk(textDelta = text, thinkingDelta = thinking))
                    }
                    val toolDeltas = delta.toolCalls
                    if (!toolDeltas.isNullOrEmpty()) {
                        for (toolDelta in toolDeltas) {
                            emit(
                                GenerationChunk(
                                    toolCallDelta = ToolCallDelta(
                                        index = toolDelta.index,
                                        id = toolDelta.id,
                                        name = toolDelta.function?.name,
                                        argumentsFragment = toolDelta.function?.arguments.orEmpty(),
                                    )
                                )
                            )
                        }
                    }
                }

                val reason = choice?.finishReason
                if (reason != null) {
                    emit(GenerationChunk(finishReason = mapFinishReason(reason)))
                }
                if (dto.usage != null) {
                    val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
                    emit(
                        GenerationChunk(
                            usage = TokenUsage(
                                promptTokens = dto.usage.promptTokens,
                                completionTokens = dto.usage.completionTokens,
                                totalTokens = dto.usage.totalTokens,
                                tokensPerSecond = if (elapsedMs > 0) dto.usage.completionTokens * 1000f / elapsedMs else 0f,
                                firstTokenLatencyMillis = if (firstTokenNs == 0L) 0L else (firstTokenNs - startNs) / 1_000_000L,
                                decodeMillis = elapsedMs,
                            )
                        )
                    )
                }
            }

            if (chunkCount == 0) {
                emit(GenerationChunk(finishReason = FinishReason.STOP))
            }
        } finally {
            runCatching { response.close() }
            runCatching { call.cancel() }
        }
    }
        .flowOn(ioDispatcher)
        .cancellable()

    override suspend fun stop() {
        // SSE 是冷流：取消 collect 即断开连接，没有额外句柄。这里只做状态复位。
    }

    override suspend fun tokenCount(text: String): Int {
        val remote = endpoint ?: return 0
        return (text.length * 1000 / remote.contextLength.coerceAtLeast(1)).coerceAtLeast(1)
    }

    override fun close() {
        loaded = false
        endpoint = null
    }

    private fun thinkingEnabled(config: InferenceConfig, remote: RemoteEndpoint): Boolean? = when (config.thinking) {
        ThinkingMode.ON -> true
        ThinkingMode.OFF -> false
        ThinkingMode.AUTO -> if (remote.supportsThinking) true else null
    }

    private fun mapFinishReason(reason: String): FinishReason = when (reason) {
        "stop" -> FinishReason.STOP
        "length" -> FinishReason.LENGTH
        "tool_calls", "function_call" -> FinishReason.TOOL_CALLS
        "content_filter" -> FinishReason.FILTER
        else -> FinishReason.STOP
    }

    private fun buildMessages(messages: List<ChatMessage>): List<MessageDto> = messages.map { message ->
        val roleName = when (message.role) {
            Role.SYSTEM -> "system"
            Role.USER -> "user"
            Role.MODEL -> "assistant"
            Role.TOOL -> "tool"
        }
        val toolResults = message.toolResults
        MessageDto(
            role = roleName,
            content = buildContent(message),
            toolCalls = message.toolCalls.takeIf { it.isNotEmpty() }?.map { call ->
                ToolCallDto(id = call.id, function = ToolFunctionDto(name = call.name, arguments = call.argumentsJson))
            },
            toolCallId = toolResults.firstOrNull()?.callId?.takeIf { roleName == "tool" },
        )
    }

    private fun buildContent(message: ChatMessage): JsonElement {
        val parts = ArrayList<JsonElement>()
        if (message.text.isNotEmpty()) parts.add(JsonPrimitive(message.text))
        for (attachment in message.attachments) {
            when (attachment) {
                is Attachment.Image -> {
                    val dataUri = imageToDataUri(attachment.uri)
                    if (dataUri != null) {
                        parts.add(
                            buildJsonObject {
                                put("type", JsonPrimitive("image_url"))
                                put(
                                    "image_url",
                                    buildJsonObject { put("url", JsonPrimitive(dataUri)) }
                                )
                            }
                        )
                    }
                }
                is Attachment.Audio -> Unit
                is Attachment.File -> Unit
                is Attachment.Text -> if (attachment.text.isNotBlank()) {
                    parts.add(
                        buildJsonObject {
                            put("type", JsonPrimitive("text"))
                            put("text", JsonPrimitive(attachment.text))
                        }
                    )
                }
            }
        }
        if (parts.isEmpty()) return JsonPrimitive("")
        if (parts.size == 1 && message.attachments.isEmpty() && message.text.isNotEmpty()) {
            return JsonPrimitive(message.text)
        }
        return JsonArray(parts)
    }

    private fun imageToDataUri(uri: String): String? {
        return try {
            val file = java.io.File(if (uri.startsWith("file://")) uri.removePrefix("file://") else uri)
            if (!file.exists()) return null
            val bytes = file.readBytes()
            val base64 = Base64.getEncoder().encodeToString(bytes)
            val mime = when {
                uri.endsWith(".jpg", true) || uri.endsWith(".jpeg", true) -> "image/jpeg"
                uri.endsWith(".webp", true) -> "image/webp"
                else -> "image/png"
            }
            "data:$mime;base64,$base64"
        } catch (t: Throwable) {
            null
        }
    }

    private fun ToolSpec.toDto(): ToolDto = ToolDto(
        function = FunctionDto(
            name = name,
            description = description,
            parameters = buildJsonObject {
                put("type", JsonPrimitive("object"))
                put(
                    "properties",
                    buildJsonObject {
                        for (parameter in parameters) {
                            put(
                                parameter.name,
                                buildJsonObject {
                                    put("type", JsonPrimitive(parameter.type.toJsonType()))
                                    put("description", JsonPrimitive(parameter.description))
                                }
                            )
                        }
                    }
                )
                put(
                    "required",
                    buildJsonArray {
                        for (parameter in parameters.filter { it.required }) {
                            add(JsonPrimitive(parameter.name))
                        }
                    }
                )
            },
        )
    )

    private fun com.rickeal.agent.core.model.ToolParamType.toJsonType(): String = when (this) {
        com.rickeal.agent.core.model.ToolParamType.STRING -> "string"
        com.rickeal.agent.core.model.ToolParamType.NUMBER -> "number"
        com.rickeal.agent.core.model.ToolParamType.INTEGER -> "integer"
        com.rickeal.agent.core.model.ToolParamType.BOOLEAN -> "boolean"
        com.rickeal.agent.core.model.ToolParamType.ARRAY -> "array"
        com.rickeal.agent.core.model.ToolParamType.OBJECT -> "object"
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
```

> ⚠️ `finishReason` 与 `usage` 可能分两条 SSE 到达（OpenAI `stream_options` 行为），也可能只有其一。上层 `StreamAccumulator` 必须容忍「只收到 finish 没收到 usage」——已按「空即忽略」实现。

### 3.7 `DefaultEngineFactory.kt`

```kotlin
package com.rickeal.agent.core.engine

import com.rickeal.agent.core.engine.local.LiteRtLmEngine
import com.rickeal.agent.core.engine.remote.OpenAiCompatibleEngine
import com.rickeal.agent.core.model.EngineKind
import com.rickeal.agent.core.model.InferenceConfig
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 手写 DI（简报 §6：禁 Hilt/Koin）。由 :core-data 的 AppContainer 构造。
 * 按 kind 缓存引擎实例 —— 加载 4B 模型很贵，绝不能每次请求都重建。
 */
class DefaultEngineFactory(
    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build(),
) : EngineFactory {

    private val engines = ConcurrentHashMap<EngineKind, LlmEngine>()

    override fun create(kind: EngineKind): LlmEngine = engines.getOrPut(kind) {
        when (kind) {
            EngineKind.LOCAL -> LiteRtLmEngine()
            EngineKind.REMOTE -> OpenAiCompatibleEngine(baseClient = okHttpClient)
        }
    }

    override fun closeAll() {
        for (engine in engines.values) runCatching { engine.close() }
        engines.clear()
    }
}

/** 组装 EngineLoadConfig 的便捷入口，避免每个调用点重复传 4 个目录。 */
class EngineEnvironment(
    val cacheDir: String?,
    val nativeLibraryDir: String?,
    val externalFilesDir: String?,
    val sandboxDir: String,
) {
    fun loadConfig(
        model: com.rickeal.agent.core.model.ModelDescriptor?,
        remote: com.rickeal.agent.core.model.RemoteEndpoint?,
        config: InferenceConfig,
    ): EngineLoadConfig = EngineLoadConfig(
        model = model,
        remote = remote,
        config = config,
        cacheDir = cacheDir,
        nativeLibraryDir = nativeLibraryDir,
        externalFilesDir = externalFilesDir,
        sandboxDir = sandboxDir,
    )
}
```

---

## 4. Agent 运行时（`:core-agent`）

### 4.1 循环模型

```
                 ┌──────────────────────────────────────┐
                 │  RoundStarted(round)                  │
                 ▼                                       │
   history ──► 引擎流式生成 ──► TextDelta / ThinkingDelta │
                 │                                       │
                 ▼                                       │
        有 tool_calls？──否──► 提交 MODEL 消息 ──► Finished
                 │ 是                                    │
                 ▼                                       │
        提交 MODEL(带 toolCalls)                          │
                 ▼                                       │
        逐个执行工具（超时/截断/护栏）                     │
                 ▼                                       │
        提交 TOOL(带 toolResults) ────────────────────────┘
                 （round++，超过 maxRounds 强制终止）
```

### 4.2 `Tool.kt` / `ToolRegistry.kt`

```kotlin
package com.rickeal.agent.core.agent

import com.rickeal.agent.core.model.ToolResult
import com.rickeal.agent.core.model.ToolSpec
import java.io.File

/** 工具执行环境。所有 Android 侧能力都从这里注入，方便测试。 */
data class ToolContext(
    val sandboxDir: File,
    /** Application Context，避免持有 Activity */
    val appContext: android.content.Context,
    val clipboard: android.content.ClipboardManager? = null,
    val nowMillis: () -> Long = { System.currentTimeMillis() },
)

interface Tool {
    val spec: ToolSpec
    suspend fun invoke(argumentsJson: String): ToolResult
}

/** 扩展点：第三方可注册自定义工具（无需注解处理器、无需 ServiceLoader）。 */
fun interface ToolContributor {
    fun contribute(context: ToolContext): List<Tool>
}

/**
 * 工具注册中心。用 ConcurrentHashMap，允许在生成过程中动态开关。
 */
class ToolRegistry {
    private val tools = java.util.concurrent.ConcurrentHashMap<String, Tool>()
    private val disabled = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun register(tool: Tool) {
        tools[tool.spec.name] = tool
    }

    fun registerAll(list: List<Tool>) {
        for (tool in list) register(tool)
    }

    fun unregister(name: String) {
        tools.remove(name)
        disabled.remove(name)
    }

    fun setEnabled(name: String, enabled: Boolean) {
        if (enabled) disabled.remove(name) else disabled.add(name)
    }

    fun isEnabled(name: String): Boolean = !disabled.contains(name)

    fun get(name: String): Tool? = if (isEnabled(name)) tools[name] else null

    fun all(): List<Tool> = tools.values.sortedBy { it.spec.name }

    fun enabledTools(): List<Tool> = all().filter { isEnabled(it.spec.name) }

    fun specs(): List<ToolSpec> = enabledTools().map { it.spec }
}
```

### 4.3 `AgentPolicy.kt` / `AgentEvents.kt`

```kotlin
package com.rickeal.agent.core.agent

import kotlinx.serialization.Serializable

@Serializable
data class AgentPolicy(
    /** 最大轮次（一轮 = 一次模型生成 + 一批工具执行） */
    val maxRounds: Int = 8,
    /** 单个工具超时 */
    val toolTimeoutMillis: Long = 15_000L,
    /** 工具输出截断长度，防止把上下文撑爆 */
    val maxToolOutputChars: Int = 4000,
    /** 是否自动执行 dangerous 工具 */
    val autoApproveDangerous: Boolean = false,
    /** 是否启用文本协议兜底解析（```json / <tool_call>） */
    val enableTextProtocol: Boolean = true,
    /** 是否在每轮前做上下文压缩 */
    val compressContext: Boolean = true,
    /** 上下文占用比例阈值，超过则压缩 */
    val compressThreshold: Float = 0.75f,
)
```

```kotlin
package com.rickeal.agent.core.agent

import com.rickeal.agent.core.model.ChatMessage
import com.rickeal.agent.core.model.ToolCall
import com.rickeal.agent.core.model.ToolResult
import com.rickeal.agent.core.model.TokenUsage

sealed interface AgentEvent {
    data class RoundStarted(val round: Int, val maxRounds: Int) : AgentEvent
    data class TextDelta(val text: String) : AgentEvent
    data class ThinkingDelta(val text: String) : AgentEvent
    data class ToolCallStarted(val call: ToolCall) : AgentEvent
    data class ToolResultReceived(val result: ToolResult) : AgentEvent
    data class ToolSkipped(val call: ToolCall, val reason: String) : AgentEvent
    /** 一条完整消息落库（UI 用它把 streaming 气泡转成正式气泡） */
    data class MessageCommitted(val message: ChatMessage) : AgentEvent
    data class Finished(val text: String, val rounds: Int, val usage: TokenUsage?) : AgentEvent
    data class Failed(val message: String, val cause: Throwable? = null) : AgentEvent
    data class Cancelled(val partialText: String) : AgentEvent
}
```

```kotlin
package com.rickeal.agent.core.agent

import com.rickeal.agent.core.model.ChatMessage
import com.rickeal.agent.core.model.InferenceConfig
import com.rickeal.agent.core.model.ModelDescriptor
import com.rickeal.agent.core.model.RemoteEndpoint

data class AgentRequest(
    val conversationId: String? = null,
    val history: List<ChatMessage> = emptyList(),
    val userInput: ChatMessage,
    val config: InferenceConfig = InferenceConfig(),
    val model: ModelDescriptor? = null,
    val endpoint: RemoteEndpoint? = null,
    /** null = 使用全部已启用工具；否则只用白名单内的 */
    val toolNames: Set<String>? = null,
    val policy: AgentPolicy = AgentPolicy(),
)
```

### 4.4 `TextToolProtocol.kt`（双协议兼容的核心）

```kotlin
package com.rickeal.agent.core.agent

import com.rickeal.agent.core.model.ToolCall
import com.rickeal.agent.core.model.newId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 文本协议解析：兼容 4B 级模型常见的三种"口头调用工具"写法。
 *  1) ```json ... ``` 围栏
 *  2) <tool_call>...</tool_call> 标签（Qwen 系）
 *  3) 裸 JSON（首个 { 到最后一个 }）
 * 支持两种载荷形状：单个对象 {"tool":..,"arguments":..} 或数组 [{...},{...}]
 */
object TextToolProtocol {

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): List<ToolCall> {
        if (text.isBlank()) return emptyList()
        val blocks = extractBlocks(text)
        val calls = ArrayList<ToolCall>()
        for (block in blocks) {
            calls.addAll(parseBlock(block))
        }
        if (calls.isEmpty()) {
            val bare = text.substringAfter('{', "").let { if (it.isEmpty()) "" else "{" + it }
            val trimmed = bare.substringBeforeLast('}', "").let { if (it.isEmpty()) "" else it + "}" }
            if (trimmed.isNotBlank()) calls.addAll(parseBlock(trimmed))
        }
        return calls
    }

    /** 把工具 JSON 从展示文本里剥掉，避免用户看到一堆代码。 */
    fun strip(text: String): String {
        var result = text
        for (block in extractBlocks(text)) {
            result = result.replace("```json", "", ignoreCase = true)
            result = result.replace(block, "")
        }
        result = result.replace("```", "")
        result = Regex("<tool_call>[\\s\\S]*?</tool_call>").replace(result, "")
        return result.trim()
    }

    private fun extractBlocks(text: String): List<String> {
        val out = ArrayList<String>()
        var cursor = 0
        while (cursor < text.length) {
            val fenceStart = text.indexOf("```", cursor)
            if (fenceStart < 0) break
            val fenceEnd = text.indexOf("```", fenceStart + 3)
            if (fenceEnd < 0) break
            val inner = text.substring(fenceStart + 3, fenceEnd)
            out.add(inner.trim().removePrefix("json").trim())
            cursor = fenceEnd + 3
        }
        val tag = Regex("<tool_call>([\\s\\S]*?)</tool_call>")
        for (match in tag.findAll(text)) out.add(match.groupValues[1].trim())
        return out
    }

    private fun parseBlock(block: String): List<ToolCall> {
        val element = try {
            json.parseToJsonElement(block)
        } catch (t: Throwable) {
            return emptyList()
        }
        return when (element) {
            is JsonArray -> element.mapNotNull { toCall(it) }
            is JsonObject -> listOfNotNull(toCall(element))
            else -> emptyList()
        }
    }

    private fun toCall(element: JsonElement): ToolCall? {
        val obj = element as? JsonObject ?: return null
        val name = (obj["tool"] ?: obj["name"] ?: obj["function"])?.let { it as? JsonPrimitive }?.content
            ?: return null
        val args = (obj["arguments"] ?: obj["parameters"] ?: obj["args"] ?: obj["input"])
        val argsJson = when (args) {
            null -> "{}"
            is JsonPrimitive -> args.content
            else -> args.toString()
        }
        return ToolCall(id = newId(), name = name, argumentsJson = argsJson, raw = obj.toString())
    }
}
```

> **兼容策略（重点）**：`AgentRunner` 优先用「模型原生 tool 通道」（`EngineCapabilities.nativeToolChannel == true`，即 OpenAI 兼容后端）；否则（LiteRT-LM 本地，`nativeToolChannel=false`）走文本协议。两者结果统一成 `ToolCall`，后续流程完全一致。**这样即便 LiteRT-LM 的 ToolProvider API 我们不敢用，工具能力也不会缺失。**

### 4.5 `ContextCompressor.kt`

```kotlin
package com.rickeal.agent.core.agent

import com.rickeal.agent.core.model.ChatMessage
import com.rickeal.agent.core.model.Role
import com.rickeal.agent.core.model.TokenEstimator

interface ContextCompressor {
    suspend fun compress(messages: List<ChatMessage>, budgetTokens: Int): List<ChatMessage>
}

/**
 * 朴素滑动窗口：
 *  - 系统消息与「最近 K 条」永不丢
 *  - 从最新往回贪心装填，装不下就丢最老的
 */
class WindowContextCompressor(
    private val keepRecent: Int = 8,
) : ContextCompressor {

    override suspend fun compress(messages: List<ChatMessage>, budgetTokens: Int): List<ChatMessage> {
        if (messages.isEmpty()) return messages
        val system = messages.filter { it.role == Role.SYSTEM }
        val rest = messages.filter { it.role != Role.SYSTEM }
        if (TokenEstimator.estimate(messages) <= budgetTokens) return messages

        val kept = ArrayList<ChatMessage>()
        var used = TokenEstimator.estimate(system)
        val recent = rest.takeLast(keepRecent)
        for (message in recent.reversed()) {
            val cost = TokenEstimator.estimate(message)
            if (used + cost > budgetTokens) break
            kept.add(0, message)
            used += cost
        }
        // 还有预算就把更早的按"从新到旧"继续补
        val older = rest.dropLast(keepRecent)
        for (message in older.reversed()) {
            val cost = TokenEstimator.estimate(message)
            if (used + cost > budgetTokens) break
            kept.add(0, message)
            used += cost
        }
        return system + kept
    }
}

/**
 * 摘要版：先滑窗，若仍超预算，把「被丢弃的中间段」交给 summarizer 压缩成一条 SYSTEM 消息。
 * summarizer 由上层注入（通常就是引擎本身跑一次"请总结以下对话"）。
 * 这是一个**可实现**的朴素方案，不追求最优。
 */
class SummarizingContextCompressor(
    private val window: ContextCompressor = WindowContextCompressor(),
    private val summarizer: suspend (String) -> String?,
) : ContextCompressor {

    override suspend fun compress(messages: List<ChatMessage>, budgetTokens: Int): List<ChatMessage> {
        val windowed = window.compress(messages, budgetTokens)
        if (TokenEstimator.estimate(windowed) <= budgetTokens) return windowed
        val keptIds = windowed.map { it.id }.toSet()
        val dropped = messages.filter { it.id !in keptIds }
        if (dropped.isEmpty()) return windowed
        val digest = dropped.joinToString("\n") { "${it.role}: ${it.text.take(300)}" }
        val summary = try {
            summarizer(digest)
        } catch (t: Throwable) {
            null
        }
        if (summary.isNullOrBlank()) return windowed
        val summaryMessage = ChatMessage(
            role = Role.SYSTEM,
            text = "以下是较早对话的摘要，请参考：\n$summary",
        )
        return listOf(summaryMessage) + windowed
    }
}
```

### 4.6 `AgentRunner.kt`（完整循环）

```kotlin
package com.rickeal.agent.core.agent

import com.rickeal.agent.core.engine.EngineFactory
import com.rickeal.agent.core.engine.GenerationRequest
import com.rickeal.agent.core.model.ChatMessage
import com.rickeal.agent.core.model.EngineKind
import com.rickeal.agent.core.model.FinishReason
import com.rickeal.agent.core.model.InferenceConfig
import com.rickeal.agent.core.model.Role
import com.rickeal.agent.core.model.StreamAccumulator
import com.rickeal.agent.core.model.ToolCall
import com.rickeal.agent.core.model.ToolResult
import com.rickeal.agent.core.model.ToolSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.cancellable
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withTimeout

class AgentRunner(
    private val engineFactory: EngineFactory,
    private val toolRegistry: ToolRegistry,
    private val environment: com.rickeal.agent.core.engine.EngineEnvironment,
    private val compressor: ContextCompressor = WindowContextCompressor(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {

    fun run(request: AgentRequest): Flow<AgentEvent> = flow {
        val policy = request.policy
        val config: InferenceConfig = request.config.coerce()
        val kind: EngineKind = if (request.endpoint != null) EngineKind.REMOTE else EngineKind.LOCAL
        val engine = engineFactory.create(kind)

        try {
            engine.load(environment.loadConfig(request.model, request.endpoint, config))
        } catch (t: Throwable) {
            emit(AgentEvent.Failed("引擎加载失败：${t.message}", t))
            return@flow
        }

        val capabilities = try {
            engine.capabilities()
        } catch (t: Throwable) {
            null
        }
        val useNativeTools = (capabilities?.nativeToolChannel == true) && config.enableTools

        val availableTools: List<ToolSpec> = if (config.enableTools) {
            toolRegistry.specs().filter { request.toolNames?.contains(it.name) ?: true }
        } else {
            emptyList()
        }

        val working = ArrayList<ChatMessage>()
        if (config.systemInstruction.isNotBlank()) {
            working.add(ChatMessage(role = Role.SYSTEM, text = buildSystemInstruction(config, availableTools)))
        }
        working.addAll(request.history)
        working.add(request.userInput)

        var round = 0
        var finalText = ""
        var lastUsage = request.history.firstOrNull()?.usage

        while (round < policy.maxRounds) {
            emit(AgentEvent.RoundStarted(round, policy.maxRounds))

            val budget = (config.contextLength * 0.75f).toInt()
            val window = if (policy.compressContext) {
                compressor.compress(working, budget)
            } else {
                working
            }

            val accumulator = StreamAccumulator()
            val generationRequest = GenerationRequest(
                messages = window,
                config = config,
                model = request.model,
                remote = request.endpoint,
                tools = if (useNativeTools) availableTools else emptyList(),
                conversationId = request.conversationId,
            )

            try {
                engine.generateStream(generationRequest).collect { chunk ->
                    accumulator.append(chunk)
                    if (chunk.textDelta.isNotEmpty()) emit(AgentEvent.TextDelta(chunk.textDelta))
                    if (chunk.thinkingDelta.isNotEmpty()) emit(AgentEvent.ThinkingDelta(chunk.thinkingDelta))
                }
            } catch (t: Throwable) {
                if (t is CancellationException) {
                    emit(AgentEvent.Cancelled(accumulator.text))
                    throw t
                }
                emit(AgentEvent.Failed("生成失败：${t.message}", t))
                return@flow
            }

            if (accumulator.finishReason == FinishReason.CANCELLED) {
                emit(AgentEvent.Cancelled(accumulator.text))
                return@flow
            }
            if (accumulator.usage != null) lastUsage = accumulator.usage

            val nativeCalls = accumulator.toolCalls()
            val calls: List<ToolCall> = if (nativeCalls.isNotEmpty()) {
                nativeCalls
            } else if (policy.enableTextProtocol) {
                TextToolProtocol.parse(accumulator.text)
            } else {
                emptyList()
            }

            if (calls.isEmpty()) {
                val cleanText = if (policy.enableTextProtocol) {
                    TextToolProtocol.strip(accumulator.text)
                } else {
                    accumulator.text
                }
                finalText = cleanText
                val committed = ChatMessage(
                    role = Role.MODEL,
                    text = cleanText,
                    thinking = accumulator.thinking.takeIf { it.isNotBlank() },
                    usage = accumulator.usage,
                    finishReason = accumulator.finishReason ?: FinishReason.STOP,
                    modelRef = request.model?.id ?: request.endpoint?.id,
                )
                working.add(committed)
                emit(AgentEvent.MessageCommitted(committed))
                break
            }

            working.add(
                ChatMessage(
                    role = Role.MODEL,
                    text = accumulator.text,
                    thinking = accumulator.thinking.takeIf { it.isNotBlank() },
                    toolCalls = calls,
                    finishReason = FinishReason.TOOL_CALLS,
                )
            )

            for (call in calls) {
                val tool = toolRegistry.get(call.name)
                if (tool == null) {
                    val result = ToolResult(
                        callId = call.id,
                        name = call.name,
                        ok = false,
                        output = "",
                        errorMessage = "未注册的工具：${call.name}",
                    )
                    emit(AgentEvent.ToolResultReceived(result))
                    working.add(ChatMessage(role = Role.TOOL, toolResults = listOf(result)))
                    continue
                }
                if (tool.spec.dangerous && !policy.autoApproveDangerous) {
                    emit(AgentEvent.ToolSkipped(call, "危险工具需用户授权"))
                    val result = ToolResult(
                        callId = call.id,
                        name = call.name,
                        ok = false,
                        output = "",
                        errorMessage = "该工具需要用户授权后才会执行",
                    )
                    working.add(ChatMessage(role = Role.TOOL, toolResults = listOf(result)))
                    continue
                }

                emit(AgentEvent.ToolCallStarted(call))
                val result = executeWithGuard(call, tool, policy)
                emit(AgentEvent.ToolResultReceived(result))
                working.add(ChatMessage(role = Role.TOOL, toolResults = listOf(result)))
            }

            round++
        }

        emit(AgentEvent.Finished(finalText, round, lastUsage))
    }
        .flowOn(dispatcher)
        .cancellable()

    private suspend fun executeWithGuard(call: ToolCall, tool: Tool, policy: AgentPolicy): ToolResult {
        val started = System.currentTimeMillis()
        return try {
            val raw = withTimeout(policy.toolTimeoutMillis) { tool.invoke(call.argumentsJson) }
            val output = raw.output
            val truncated = output.length > policy.maxToolOutputChars
            raw.copy(
                output = if (truncated) output.take(policy.maxToolOutputChars) + "\n…(已截断)" else output,
                elapsedMillis = System.currentTimeMillis() - started,
                truncated = truncated,
            )
        } catch (t: Throwable) {
            val message = if (t is kotlinx.coroutines.TimeoutCancellationException) {
                "工具执行超时（${policy.toolTimeoutMillis}ms）"
            } else {
                t.message ?: "工具执行异常"
            }
            ToolResult(
                callId = call.id,
                name = call.name,
                ok = false,
                output = "",
                errorMessage = message,
                elapsedMillis = System.currentTimeMillis() - started,
            )
        }
    }

    private fun buildSystemInstruction(config: InferenceConfig, tools: List<ToolSpec>): String {
        if (tools.isEmpty()) return config.systemInstruction
        val header = "你可以使用以下工具。当需要调用工具时，请只输出一个 ```json 代码块，格式为：" +
            "[{\"tool\": \"工具名\", \"arguments\": {\"参数名\": 值}}]，不要输出其它文字。\n可用工具：\n"
        return config.systemInstruction + "\n\n" + header + tools.joinToString("\n") { it.toPromptLine() }
    }
}
```

> `run()` 里所有错误都靠 `catch (t: Throwable)` + `AgentEvent.Failed` 上报，UI 只需要处理一个事件分支，`try/catch` 不会散落到 UI 层。

### 4.7 内置工具清单

| 工具名 | 类 | 能力 | 危险 | 备注 |
|---|---|---|---|---|
| `calculator` | `CalculatorTool` | 四则运算 + `^` + 括号 | 否 | 自带递归下降求值器，**不用 JS 引擎** |
| `current_time` | `DateTimeTool` | 当前时间/日期/时区/时间戳转换 | 否 | `java.time` |
| `file_read` | `FileReadTool` | 读沙箱目录内文本文件 | 否 | 路径逃逸检查 |
| `file_write` | `FileWriteTool` | 写沙箱目录内文件 | **是** | 默认需授权 |
| `file_list` | `FileListTool` | 列沙箱目录 | 否 | |
| `clipboard_read` / `clipboard_write` | `ClipboardTool` | 剪贴板读写 | 否 | 需 `ToolContext.clipboard` |
| `web_search` | `WebSearchTool` | 网络搜索**占位** | 否 | 未配置时返回明确提示，不假装成功 |
| `image_describe` | `ImageDescribeTool` | 图片理解**占位** | 否 | 预留 `vlmDescriber` 注入点 |
| 自定义 | `ToolContributor` | 注册点 | — | `ToolRegistry.registerAll(contributor.contribute(ctx))` |

```kotlin
package com.rickeal.agent.core.agent.tools

import com.rickeal.agent.core.agent.Tool
import com.rickeal.agent.core.model.ToolResult
import com.rickeal.agent.core.model.ToolSpec
import com.rickeal.agent.core.model.ToolParameter
import com.rickeal.agent.core.model.ToolParamType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 从 argumentsJson 里安全地取一个字符串参数。 */
internal fun stringArg(argumentsJson: String, key: String, default: String = ""): String {
    val obj = try {
        Json.parseToJsonElement(argumentsJson) as? JsonObject
    } catch (t: Throwable) {
        null
    } ?: return default
    val value = obj[key] ?: return default
    return (value as? JsonPrimitive)?.content ?: value.toString()
}

internal fun json(argumentsJson: String): JsonObject =
    try { Json.parseToJsonElement(argumentsJson) as? JsonObject ?: JsonObject(emptyMap()) }
    catch (t: Throwable) { JsonObject(emptyMap()) }

class CalculatorTool : Tool {
    override val spec: ToolSpec = ToolSpec(
        name = "calculator",
        description = "计算数学表达式，支持 + - * / ^ 与括号，例如 (1+2)*3^2",
        parameters = listOf(
            ToolParameter("expression", ToolParamType.STRING, "要计算的表达式", required = true),
        ),
        category = "utility",
    )

    override suspend fun invoke(argumentsJson: String): ToolResult {
        val expression = stringArg(argumentsJson, "expression")
        if (expression.isBlank()) {
            return ToolResult(name = spec.name, ok = false, errorMessage = "缺少 expression 参数")
        }
        return try {
            val value = ExpressionEvaluator.evaluate(expression)
            ToolResult(name = spec.name, ok = true, output = formatNumber(value))
        } catch (t: Throwable) {
            ToolResult(name = spec.name, ok = false, errorMessage = "无法计算：${t.message}")
        }
    }

    private fun formatNumber(value: Double): String =
        if (value == kotlin.math.floor(value) && kotlin.math.abs(value) < 1e15) {
            value.toLong().toString()
        } else {
            "%.10f".format(value).trimEnd('0').trimEnd('.')
        }
}
```

```kotlin
package com.rickeal.agent.core.agent.tools

/**
 * 极简递归下降求值器。刻意不引 JS 引擎（简报 §6 禁额外依赖）。
 * 支持：+ - * / ^ ( ) 一元正负 小数
 */
object ExpressionEvaluator {

    fun evaluate(expression: String): Double {
        val parser = Parser(expression)
        val value = parser.parseExpression()
        parser.skipWhitespace()
        if (!parser.isEnd()) throw IllegalArgumentException("无法解析：位置 ${parser.position}")
        return value
    }

    private class Parser(private val source: String) {
        var position: Int = 0
            private set

        fun isEnd(): Boolean = position >= source.length

        fun skipWhitespace() {
            while (position < source.length && source[position].isWhitespace()) position++
        }

        private fun peek(): Char? = if (isEnd()) null else source[position]

        private fun expect(char: Char) {
            skipWhitespace()
            if (peek() != char) throw IllegalArgumentException("期望 '$char' 于位置 $position")
            position++
        }

        fun parseExpression(): Double {
            var value = parseTerm()
            while (true) {
                skipWhitespace()
                when (peek()) {
                    '+' -> { position++; value += parseTerm() }
                    '-' -> { position++; value -= parseTerm() }
                    else -> return value
                }
            }
        }

        private fun parseTerm(): Double {
            var value = parsePower()
            while (true) {
                skipWhitespace()
                when (peek()) {
                    '*' -> { position++; value *= parsePower() }
                    '/' -> { position++; val divisor = parsePower(); value /= divisor }
                    else -> return value
                }
            }
        }

        private fun parsePower(): Double {
            val base = parseUnary()
            skipWhitespace()
            if (peek() == '^') {
                position++
                return Math.pow(base, parsePower())
            }
            return base
        }

        private fun parseUnary(): Double {
            skipWhitespace()
            when (peek()) {
                '+' -> { position++; return parseUnary() }
                '-' -> { position++; return -parseUnary() }
                '(' -> {
                    position++
                    val value = parseExpression()
                    expect(')')
                    return value
                }
                else -> return parseNumber()
            }
        }

        private fun parseNumber(): Double {
            skipWhitespace()
            val start = position
            while (position < source.length && (source[position].isDigit() || source[position] == '.')) position++
            if (start == position) throw IllegalArgumentException("位置 $position 处缺少数字")
            val literal = source.substring(start, position)
            return literal.toDoubleOrNull() ?: throw IllegalArgumentException("非法数字：$literal")
        }
    }
}
```

```kotlin
package com.rickeal.agent.core.agent.tools

import com.rickeal.agent.core.agent.Tool
import com.rickeal.agent.core.agent.ToolContext
import com.rickeal.agent.core.model.ToolParameter
import com.rickeal.agent.core.model.ToolParamType
import com.rickeal.agent.core.model.ToolResult
import com.rickeal.agent.core.model.ToolSpec
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class DateTimeTool(private val context: ToolContext) : Tool {
    override val spec: ToolSpec = ToolSpec(
        name = "current_time",
        description = "获取当前日期与时间。可选参数 timeZone（如 Asia/Shanghai）、pattern（如 yyyy-MM-dd HH:mm:ss）",
        parameters = listOf(
            ToolParameter("timeZone", ToolParamType.STRING, "时区 ID，默认系统时区", required = false),
            ToolParameter("pattern", ToolParamType.STRING, "时间格式，默认 yyyy-MM-dd HH:mm:ss", required = false),
        ),
        category = "utility",
    )

    override suspend fun invoke(argumentsJson: String): ToolResult {
        val zoneId = runCatching { ZoneId.of(stringArg(argumentsJson, "timeZone")) }
            .getOrElse { ZoneId.systemDefault() }
        val pattern = stringArg(argumentsJson, "pattern").ifBlank { "yyyy-MM-dd HH:mm:ss" }
        val formatter = runCatching { DateTimeFormatter.ofPattern(pattern) }
            .getOrElse { DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss") }
        val instant = Instant.ofEpochMilli(context.nowMillis())
        val formatted = formatter.withZone(zoneId).format(instant)
        return ToolResult(
            name = spec.name,
            ok = true,
            output = "$formatted（时区：$zoneId，epochMillis=${context.nowMillis()}）",
        )
    }
}
```

```kotlin
package com.rickeal.agent.core.agent.tools

import com.rickeal.agent.core.agent.Tool
import com.rickeal.agent.core.agent.ToolContext
import com.rickeal.agent.core.model.ToolParameter
import com.rickeal.agent.core.model.ToolParamType
import com.rickeal.agent.core.model.ToolResult
import com.rickeal.agent.core.model.ToolSpec
import java.io.File

/**
 * 所有文件工具的基类：做沙箱路径逃逸检查。
 * 这是安全护栏的第一道，任何新增文件工具都必须继承它。
 */
internal abstract class SandboxedFileTool(protected val context: ToolContext) : Tool {

    protected fun resolveSafe(relativePath: String): File {
        val base = context.sandboxDir.canonicalFile
        val target = File(base, relativePath).canonicalFile
        val basePath = base.path
        if (target.path != basePath && !target.path.startsWith(basePath + File.separator)) {
            throw SecurityException("拒绝访问沙箱之外的路径：$relativePath")
        }
        return target
    }

    protected fun param(name: String, description: String, required: Boolean = true): ToolParameter =
        ToolParameter(name, ToolParamType.STRING, description, required)
}

class FileReadTool(context: ToolContext) : SandboxedFileTool(context) {
    override val spec: ToolSpec = ToolSpec(
        name = "file_read",
        description = "读取沙箱目录内的文本文件",
        parameters = listOf(param("path", "相对于沙箱目录的文件路径")),
        category = "file",
    )

    override suspend fun invoke(argumentsJson: String): ToolResult {
        val path = stringArg(argumentsJson, "path")
        return try {
            val file = resolveSafe(path)
            if (!file.exists()) return ToolResult(name = spec.name, ok = false, errorMessage = "文件不存在：$path")
            if (!file.isFile) return ToolResult(name = spec.name, ok = false, errorMessage = "不是文件：$path")
            val text = file.readText().take(200_000)
            ToolResult(name = spec.name, ok = true, output = text)
        } catch (t: Throwable) {
            ToolResult(name = spec.name, ok = false, errorMessage = t.message ?: "读取失败")
        }
    }
}

class FileWriteTool(context: ToolContext) : SandboxedFileTool(context) {
    override val spec: ToolSpec = ToolSpec(
        name = "file_write",
        description = "把内容写入沙箱目录内的文件（会覆盖）",
        parameters = listOf(
            param("path", "相对于沙箱目录的文件路径"),
            param("content", "要写入的文本内容"),
        ),
        dangerous = true,
        requiresConfirmation = true,
        category = "file",
    )

    override suspend fun invoke(argumentsJson: String): ToolResult {
        val path = stringArg(argumentsJson, "path")
        val content = stringArg(argumentsJson, "content")
        return try {
            val file = resolveSafe(path)
            file.parentFile?.mkdirs()
            file.writeText(content)
            ToolResult(name = spec.name, ok = true, output = "已写入 ${file.absolutePath}（${content.length} 字符）")
        } catch (t: Throwable) {
            ToolResult(name = spec.name, ok = false, errorMessage = t.message ?: "写入失败")
        }
    }
}

class FileListTool(context: ToolContext) : SandboxedFileTool(context) {
    override val spec: ToolSpec = ToolSpec(
        name = "file_list",
        description = "列出沙箱目录内的文件",
        parameters = listOf(param("path", "相对于沙箱目录的子目录，默认根目录", required = false)),
        category = "file",
    )

    override suspend fun invoke(argumentsJson: String): ToolResult {
        val path = stringArg(argumentsJson, "path")
        return try {
            val dir = resolveSafe(path)
            if (!dir.exists()) return ToolResult(name = spec.name, ok = false, errorMessage = "目录不存在：$path")
            val listing = (dir.listFiles() ?: emptyArray())
                .sortedBy { it.name }
                .joinToString("\n") { if (it.isDirectory) "[DIR] ${it.name}" else "[FILE] ${it.name} (${it.length()} B)" }
            ToolResult(name = spec.name, ok = true, output = listing.ifBlank { "（空目录）" })
        } catch (t: Throwable) {
            ToolResult(name = spec.name, ok = false, errorMessage = t.message ?: "列目录失败")
        }
    }
}
```

```kotlin
package com.rickeal.agent.core.agent.tools

import android.content.ClipData
import android.content.ClipboardManager
import com.rickeal.agent.core.agent.Tool
import com.rickeal.agent.core.agent.ToolContext
import com.rickeal.agent.core.model.ToolParameter
import com.rickeal.agent.core.model.ToolParamType
import com.rickeal.agent.core.model.ToolResult
import com.rickeal.agent.core.model.ToolSpec

class ClipboardTool(private val context: ToolContext) : Tool {
    override val spec: ToolSpec = ToolSpec(
        name = "clipboard",
        description = "读取或写入系统剪贴板。action 取 get 或 set；set 时需要 text",
        parameters = listOf(
            ToolParameter("action", ToolParamType.STRING, "get / set", required = true, enumValues = listOf("get", "set")),
            ToolParameter("text", ToolParamType.STRING, "set 时要写入的文本", required = false),
        ),
        category = "system",
    )

    override suspend fun invoke(argumentsJson: String): ToolResult {
        val manager: ClipboardManager? = context.clipboard
        if (manager == null) {
            return ToolResult(name = spec.name, ok = false, errorMessage = "剪贴板不可用")
        }
        return when (stringArg(argumentsJson, "action")) {
            "set" -> {
                val text = stringArg(argumentsJson, "text")
                manager.setPrimaryClip(ClipData.newPlainText("LiquidAgent", text))
                ToolResult(name = spec.name, ok = true, output = "已写入剪贴板（${text.length} 字符）")
            }
            else -> {
                val clip = manager.primaryClip
                val text = if (clip != null && clip.itemCount > 0) {
                    clip.getItemAt(0).coerceToText(context.appContext).toString()
                } else {
                    ""
                }
                ToolResult(name = spec.name, ok = true, output = text.ifBlank { "（剪贴板为空）" })
            }
        }
    }
}

/**
 * 网络搜索占位。真实实现需要外部 API Key，第一版不引入任何 SDK。
 * 通过构造参数注入 fetcher，未来接任何搜索 API 都不需要改 Agent 层。
 */
class WebSearchTool(
    private val fetcher: suspend (query: String) -> String? = { null },
) : Tool {
    override val spec: ToolSpec = ToolSpec(
        name = "web_search",
        description = "联网搜索（需先在设置中配置搜索服务，未配置时不可用）",
        parameters = listOf(ToolParameter("query", ToolParamType.STRING, "搜索关键词", required = true)),
        category = "network",
    )

    override suspend fun invoke(argumentsJson: String): ToolResult {
        val query = stringArg(argumentsJson, "query")
        if (query.isBlank()) return ToolResult(name = spec.name, ok = false, errorMessage = "缺少 query")
        val result = try {
            fetcher(query)
        } catch (t: Throwable) {
            null
        }
        return if (result.isNullOrBlank()) {
            ToolResult(
                name = spec.name,
                ok = false,
                errorMessage = "网络搜索未配置：请在「设置 → 远程与搜索」中填写搜索服务后再使用",
            )
        } else {
            ToolResult(name = spec.name, ok = true, output = result)
        }
    }
}

/**
 * 图片理解占位。预留注入点：把 (图片uri, 问题) 交给本地 VLM 或远程多模态端点。
 */
class ImageDescribeTool(
    private val describer: suspend (uri: String, question: String) -> String? = { _, _ -> null },
) : Tool {
    override val spec: ToolSpec = ToolSpec(
        name = "image_describe",
        description = "描述一张图片的内容（需要模型或端点支持视觉输入）",
        parameters = listOf(
            ToolParameter("uri", ToolParamType.STRING, "图片路径或 uri", required = true),
            ToolParameter("question", ToolParamType.STRING, "关于这张图的问题", required = false),
        ),
        category = "vision",
    )

    override suspend fun invoke(argumentsJson: String): ToolResult {
        val uri = stringArg(argumentsJson, "uri")
        val question = stringArg(argumentsJson, "question").ifBlank { "请描述这张图片" }
        val result = try {
            describer(uri, question)
        } catch (t: Throwable) {
            null
        }
        return if (result.isNullOrBlank()) {
            ToolResult(name = spec.name, ok = false, errorMessage = "图片理解不可用：当前模型或端点不支持视觉输入")
        } else {
            ToolResult(name = spec.name, ok = true, output = result)
        }
    }
}
```

### 4.8 `DefaultTools.kt`（装配）

```kotlin
package com.rickeal.agent.core.agent

import com.rickeal.agent.core.agent.tools.CalculatorTool
import com.rickeal.agent.core.agent.tools.ClipboardTool
import com.rickeal.agent.core.agent.tools.DateTimeTool
import com.rickeal.agent.core.agent.tools.FileListTool
import com.rickeal.agent.core.agent.tools.FileReadTool
import com.rickeal.agent.core.agent.tools.FileWriteTool
import com.rickeal.agent.core.agent.tools.ImageDescribeTool
import com.rickeal.agent.core.agent.tools.WebSearchTool

fun ToolRegistry.installBuiltInTools(context: ToolContext) {
    register(CalculatorTool())
    register(DateTimeTool(context))
    register(FileReadTool(context))
    register(FileWriteTool(context))
    register(FileListTool(context))
    register(ClipboardTool(context))
    register(WebSearchTool())
    register(ImageDescribeTool())
}
```

---

## 5. 参数与能力探测（能力矩阵与降级策略）

### 5.1 `InferenceConfig` 字段 → 落地位置

| 字段 | LiteRT-LM 0.11.0 | OpenAI 兼容后端 | 降级 / 兜底 |
|---|---|---|---|
| `sampling.temperature` | ✅ `SamplerConfig.temperature` | ✅ `temperature` | — |
| `sampling.topP` | ✅ `SamplerConfig.topP` | ✅ `top_p` | — |
| `sampling.topK` | ✅ `SamplerConfig.topK` | ❌ 无此参数 | 忽略（UI 上标注"仅本地引擎"） |
| `sampling.repetitionPenalty` | ❌ 不支持 | ⚠️ 映射成 `frequency_penalty = (rp-1)*2`，**近似** | 本地引擎忽略并在 UI 提示 |
| `sampling.seed` | ❌ 不支持 | ✅ `seed` | 本地忽略 |
| `maxTokens` | ✅ `EngineConfig.maxNumTokens` | ✅ `max_tokens` | — |
| `contextLength` | ❌ **无此参数** | ⚠️ 仅作裁剪预算，不下发 | 上层用 `TokenEstimator` 裁剪窗口 |
| `backend` | ✅ `Backend.CPU/GPU/NPU` | ❌ 无意义（UI 隐藏） | — |
| `visionBackend` / `audioBackend` | ✅ `EngineConfig.visionBackend/audioBackend`（模型支持时才传） | ❌ | — |
| `thinking` | ✅ `extraContext["enable_thinking"]=true`，输出走 `message.channels["thought"]` | ✅ 按 `ThinkingParamStyle` 下发三种字段之一；输出读 `reasoning_content` / `reasoning` | 不支持时不下发，UI 灰显 |
| `systemInstruction` | ⚠️ `ConversationConfig.systemInstruction` 传空，**改由 `messages[0]` (role=SYSTEM) 承载** | ✅ `messages[0].role=system` | 见 §3.4 说明 4 |
| `maxAgentRounds` | → `AgentPolicy.maxRounds` | 同 | — |
| `enableTools` | ⚠️ 原生 `ToolProvider` 不使用 → 走文本协议 | ✅ `tools` + `tool_choice=auto` | — |

### 5.2 降级策略（写代码时的判定顺序）

1. **NPU 后端**：若 `backend == NPU` → `samplerConfig = null`，UI 把 temperature/topP/topK 三个滑块置灰并提示 "NPU 后端不支持自定义采样"。
2. **远程引擎**：隐藏 backend/visionBackend/audioBackend 三个控件；`topK` 滑块置灰。
3. **模型不支持思考**：`ModelCapabilities.thinking == false` 且 `ThinkingMode.AUTO` → 不下发 `enable_thinking`，UI 的思考开关显示"该模型不支持"。
4. **模型不支持视觉**：`image == false` → 不传 `visionBackend`（传了会初始化失败），图片附件按钮隐藏。
5. **上下文超长**：`TokenEstimator.estimate > contextLength * 0.75` → 触发 `WindowContextCompressor`；若仍超，尝试 `SummarizingContextCompressor`（需要一次额外推理，用户可在设置里关掉）。
6. **引擎初始化失败（GPU 不支持）**：捕获 `EngineException`，UI 提示"GPU 初始化失败，是否切换到 CPU？"，并把 `backend` 自动改成 CPU 重试一次（**只重试一次**，避免死循环）。

### 5.3 能力探测流程

```
用户选中模型
   └─> Capabilities(modelPath).use { hasSpeculativeDecodingSupport() }   // 仅探测，失败吞掉
   └─> 读 ModelDescriptor.capabilities（导入时由用户勾选 / 按文件名启发式推断）
        文件名含 "3n" -> GEMMA_3N (image+audio+tool)
        文件名含 "gemma-3" -> GEMMA_3 (image)
        文件名含 "qwen3" -> QWEN_3 (thinking+tool)
        含 "int4"/"q4" -> Quantization.INT4
   └─> 合并成 EngineCapabilities -> UI 决定哪些开关可用
```

启发式函数放 `:core-model` 的 `ModelHeuristics.kt`（纯字符串判断，无依赖，可单测）。

---

## 6. 数据层（`:core-data`）

### 6.1 `JsonFileStore.kt`（手写持久化，禁 Room）

```kotlin
package com.rickeal.agent.core.data

import com.rickeal.agent.core.model.AgentJson
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 原子写 JSON 文件（先写 .tmp 再 rename），避免写到一半崩溃导致会话文件损坏。
 */
class JsonFileStore(
    private val baseDir: File,
    private val json: Json = AgentJson.Default,
) {
    suspend fun ensureDir() = withContext(Dispatchers.IO) {
        if (!baseDir.exists()) baseDir.mkdirs()
    }

    suspend fun <T> read(fileName: String, strategy: DeserializationStrategy<T>): T? =
        withContext(Dispatchers.IO) {
            val file = File(baseDir, fileName)
            if (!file.exists()) return@withContext null
            try {
                json.decodeFromString(strategy, file.readText())
            } catch (t: Throwable) {
                null
            }
        }

    suspend fun <T> write(fileName: String, value: T, strategy: SerializationStrategy<T>) =
        withContext(Dispatchers.IO) {
            if (!baseDir.exists()) baseDir.mkdirs()
            val target = File(baseDir, fileName)
            val tmp = File(baseDir, "$fileName.tmp")
            tmp.writeText(json.encodeToString(strategy, value))
            if (!tmp.renameTo(target)) {
                target.writeText(tmp.readText())
                tmp.delete()
            }
        }

    suspend fun delete(fileName: String) = withContext(Dispatchers.IO) {
        File(baseDir, fileName).delete()
        File(baseDir, "$fileName.tmp").delete()
    }

    suspend fun list(suffix: String = ".json"): List<File> = withContext(Dispatchers.IO) {
        if (!baseDir.exists()) return@withContext emptyList()
        baseDir.listFiles()?.filter { it.isFile && it.name.endsWith(suffix) } ?: emptyList()
    }
}
```

### 6.2 `SettingsRepository.kt`

```kotlin
package com.rickeal.agent.core.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.rickeal.agent.core.model.AgentJson
import com.rickeal.agent.core.model.InferenceConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "liquid_agent_settings")

class SettingsRepository(private val context: Context) {

    private object Keys {
        val INFERENCE_CONFIG = stringPreferencesKey("inference_config")
        val ACTIVE_MODEL_ID = stringPreferencesKey("active_model_id")
        val ACTIVE_ENDPOINT_ID = stringPreferencesKey("active_endpoint_id")
        val DARK_MODE = booleanPreferencesKey("dark_mode")
        val REDUCE_MOTION = booleanPreferencesKey("reduce_motion")
        val GLASS_INTENSITY = floatPreferencesKey("glass_intensity")
        val ENABLE_NOISE = booleanPreferencesKey("enable_noise")
    }

    val inferenceConfig: Flow<InferenceConfig> = context.settingsDataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { prefs ->
            val raw = prefs[Keys.INFERENCE_CONFIG]
            if (raw.isNullOrBlank()) InferenceConfig() else runCatching {
                AgentJson.Default.decodeFromString(InferenceConfig.serializer(), raw)
            }.getOrDefault(InferenceConfig())
        }

    val themeState: Flow<ThemeState> = context.settingsDataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { prefs ->
            ThemeState(
                darkMode = prefs[Keys.DARK_MODE] ?: true,
                reduceMotion = prefs[Keys.REDUCE_MOTION] ?: false,
                glassIntensity = prefs[Keys.GLASS_INTENSITY] ?: 1f,
                enableNoise = prefs[Keys.ENABLE_NOISE] ?: true,
            )
        }

    val activeModelId: Flow<String?> = context.settingsDataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[Keys.ACTIVE_MODEL_ID] }

    suspend fun updateInferenceConfig(transform: (InferenceConfig) -> InferenceConfig) {
        context.settingsDataStore.edit { prefs ->
            val current = prefs[Keys.INFERENCE_CONFIG]?.let { raw ->
                runCatching { AgentJson.Default.decodeFromString(InferenceConfig.serializer(), raw) }.getOrNull()
            } ?: InferenceConfig()
            prefs[Keys.INFERENCE_CONFIG] = AgentJson.Default.encodeToString(
                InferenceConfig.serializer(),
                transform(current).coerce(),
            )
        }
    }

    suspend fun setActiveModel(id: String?) {
        context.settingsDataStore.edit { prefs ->
            if (id == null) prefs.remove(Keys.ACTIVE_MODEL_ID) else prefs[Keys.ACTIVE_MODEL_ID] = id
        }
    }

    suspend fun setActiveEndpoint(id: String?) {
        context.settingsDataStore.edit { prefs ->
            if (id == null) prefs.remove(Keys.ACTIVE_ENDPOINT_ID) else prefs[Keys.ACTIVE_ENDPOINT_ID] = id
        }
    }

    suspend fun setThemeState(state: ThemeState) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.DARK_MODE] = state.darkMode
            prefs[Keys.REDUCE_MOTION] = state.reduceMotion
            prefs[Keys.GLASS_INTENSITY] = state.glassIntensity
            prefs[Keys.ENABLE_NOISE] = state.enableNoise
        }
    }

    suspend fun snapshot(): InferenceConfig = inferenceConfig.first()
}

data class ThemeState(
    val darkMode: Boolean = true,
    val reduceMotion: Boolean = false,
    val glassIntensity: Float = 1f,
    val enableNoise: Boolean = true,
)
```

### 6.3 `ModelRepository.kt` / `ConversationRepository.kt` / `EndpointRepository.kt`

```kotlin
package com.rickeal.agent.core.data

import android.content.Context
import com.rickeal.agent.core.model.ModelDescriptor
import com.rickeal.agent.core.model.RemoteEndpoint
import com.rickeal.agent.core.model.RemotePresets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

class ModelRepository(context: Context) {
    private val store = JsonFileStore(File(context.filesDir, "models"))
    private val _models = MutableStateFlow<List<ModelDescriptor>>(emptyList())
    val models: StateFlow<List<ModelDescriptor>> = _models.asStateFlow()

    suspend fun refresh() = withContext(Dispatchers.IO) {
        val list = store.read("models.json", ListSerializer(ModelDescriptor.serializer()))
            ?.filter { it.path.isNotBlank() } ?: emptyList()
        _models.value = list
    }

    suspend fun upsert(model: ModelDescriptor) = withContext(Dispatchers.IO) {
        val next = _models.value.filter { it.id != model.id } + model
        store.write("models.json", next, ListSerializer(ModelDescriptor.serializer()))
        _models.value = next
    }

    suspend fun remove(id: String) = withContext(Dispatchers.IO) {
        val next = _models.value.filter { it.id != id }
        store.write("models.json", next, ListSerializer(ModelDescriptor.serializer()))
        _models.value = next
    }

    suspend fun find(id: String?): ModelDescriptor? =
        if (id == null) null else _models.value.firstOrNull { it.id == id }
}

class EndpointRepository(context: Context) {
    private val store = JsonFileStore(File(context.filesDir, "endpoints"))
    private val _endpoints = MutableStateFlow<List<RemoteEndpoint>>(RemotePresets.all())
    val endpoints: StateFlow<List<RemoteEndpoint>> = _endpoints.asStateFlow()

    suspend fun refresh() = withContext(Dispatchers.IO) {
        val list = store.read("endpoints.json", ListSerializer(RemoteEndpoint.serializer()))
        _endpoints.value = if (list.isNullOrEmpty()) RemotePresets.all() else list
    }

    suspend fun upsert(endpoint: RemoteEndpoint) = withContext(Dispatchers.IO) {
        val next = _endpoints.value.filter { it.id != endpoint.id } + endpoint
        store.write("endpoints.json", next, ListSerializer(RemoteEndpoint.serializer()))
        _endpoints.value = next
    }

    suspend fun remove(id: String) = withContext(Dispatchers.IO) {
        val next = _endpoints.value.filter { it.id != id }
        store.write("endpoints.json", next, ListSerializer(RemoteEndpoint.serializer()))
        _endpoints.value = next
    }

    suspend fun find(id: String?): RemoteEndpoint? =
        if (id == null) null else _endpoints.value.firstOrNull { it.id == id }
}
```

```kotlin
package com.rickeal.agent.core.data

import android.content.Context
import com.rickeal.agent.core.model.ChatMessage
import com.rickeal.agent.core.model.Conversation
import com.rickeal.agent.core.model.ConversationMeta
import com.rickeal.agent.core.model.toMeta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

/**
 * 会话存储：index.json 存列表摘要（列表页秒开），每个会话一个 <id>.json 存全文。
 */
class ConversationRepository(context: Context) {
    private val store = JsonFileStore(File(context.filesDir, "conversations"))
    private val _metas = MutableStateFlow<List<ConversationMeta>>(emptyList())
    val metas: StateFlow<List<ConversationMeta>> = _metas.asStateFlow()

    suspend fun refresh() = withContext(Dispatchers.IO) {
        val list = store.read("index.json", ListSerializer(ConversationMeta.serializer())) ?: emptyList()
        _metas.value = list.sortedByDescending { it.updatedAtMillis }
    }

    suspend fun load(id: String): Conversation? =
        store.read("$id.json", Conversation.serializer())

    suspend fun save(conversation: Conversation) = withContext(Dispatchers.IO) {
        store.write("${conversation.id}.json", conversation, Conversation.serializer())
        val next = (_metas.value.filter { it.id != conversation.id } + conversation.toMeta())
            .sortedByDescending { it.updatedAtMillis }
        _metas.value = next
        store.write("index.json", next, ListSerializer(ConversationMeta.serializer()))
    }

    suspend fun appendMessage(conversationId: String, message: ChatMessage) {
        val current = load(conversationId) ?: Conversation(id = conversationId)
        save(current.copy(messages = current.messages + message, updatedAtMillis = System.currentTimeMillis()))
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        store.delete("$id.json")
        val next = _metas.value.filter { it.id != id }
        _metas.value = next
        store.write("index.json", next, ListSerializer(ConversationMeta.serializer()))
    }

    suspend fun create(title: String = "新对话"): Conversation {
        val conversation = Conversation(title = title)
        save(conversation)
        return conversation
    }
}
```

### 6.4 `AppContainer.kt`（手写 DI，禁 Hilt/Koin）

```kotlin
package com.rickeal.agent.core.data

import android.content.Context
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import com.rickeal.agent.core.agent.AgentRunner
import com.rickeal.agent.core.agent.ToolContext
import com.rickeal.agent.core.agent.ToolRegistry
import com.rickeal.agent.core.agent.installBuiltInTools
import com.rickeal.agent.core.engine.DefaultEngineFactory
import com.rickeal.agent.core.engine.EngineEnvironment
import com.rickeal.agent.core.engine.EngineFactory
import java.io.File

class AppContainer(private val context: Context) {

    val settingsRepository: SettingsRepository = SettingsRepository(context)
    val modelRepository: ModelRepository = ModelRepository(context)
    val endpointRepository: EndpointRepository = EndpointRepository(context)
    val conversationRepository: ConversationRepository = ConversationRepository(context)

    val sandboxDir: File = File(context.filesDir, "agent_sandbox").apply { mkdirs() }

    val engineEnvironment: EngineEnvironment = EngineEnvironment(
        cacheDir = context.cacheDir?.absolutePath,
        nativeLibraryDir = context.applicationInfo.nativeLibraryDir,
        externalFilesDir = context.getExternalFilesDir(null)?.absolutePath,
        sandboxDir = sandboxDir.absolutePath,
    )

    val engineFactory: EngineFactory = DefaultEngineFactory()

    val toolContext: ToolContext = ToolContext(
        sandboxDir = sandboxDir,
        appContext = context.applicationContext,
        clipboard = context.applicationContext
            .getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager,
    )

    val toolRegistry: ToolRegistry = ToolRegistry().apply { installBuiltInTools(toolContext) }

    val agentRunner: AgentRunner = AgentRunner(
        engineFactory = engineFactory,
        toolRegistry = toolRegistry,
        environment = engineEnvironment,
    )

    /** 冷启动预热：把三个仓库的内存快照拉起来。 */
    suspend fun bootstrap() {
        modelRepository.refresh()
        endpointRepository.refresh()
        conversationRepository.refresh()
    }

    fun close() {
        engineFactory.closeAll()
    }
}

/** UI 层唯一的取依赖入口。放在 :core-data 是为了让 feature 模块能引用类型。 */
val LocalAppContainer: ProvidableCompositionLocal<AppContainer> =
    staticCompositionLocalOf { error("LocalAppContainer 未提供：请在 LiquidAgentTheme 外层 CompositionLocalProvider") }
```

---

## 7. UI 架构与屏幕清单

### 7.1 导航图

```
AgentNavHost(startDestination = "chat")
│
├── "chat?conversationId={conversationId}"   -> ChatRoute        (feature-chat)
├── "models"                                 -> ModelsRoute      (feature-models)
├── "settings"                               -> SettingsRoute    (feature-settings)
├── "settings/tools"                         -> ToolsRoute       (feature-settings.tools)
├── "settings/endpoints"                     -> EndpointsRoute   (feature-settings)
└── "benchmark?modelId={modelId}"            -> BenchmarkRoute   (feature-models, 可选/P2)
```

每个 feature 模块导出 **route 常量 + NavGraphBuilder 扩展**，app 只做组装：

```kotlin
// feature-chat
object ChatRoute {
    const val ARG_CONVERSATION_ID = "conversationId"
    const val ROUTE = "chat?$ARG_CONVERSATION_ID={$ARG_CONVERSATION_ID}"
    fun build(conversationId: String? = null): String =
        if (conversationId.isNullOrBlank()) "chat" else "chat?$ARG_CONVERSATION_ID=$conversationId"
}

fun NavGraphBuilder.chatGraph(
    navController: NavHostController,
    onOpenModels: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    composable(
        route = ChatRoute.ROUTE,
        arguments = listOf(navArgument(ChatRoute.ARG_CONVERSATION_ID) {
            type = NavType.StringType
            nullable = true
            defaultValue = null
        }),
    ) { backStackEntry ->
        val container = LocalAppContainer.current
        val conversationId = backStackEntry.arguments?.getString(ChatRoute.ARG_CONVERSATION_ID)
        ChatScreen(
            viewModel = viewModel(
                factory = chatViewModelFactory(container, conversationId),
            ),
            onOpenModels = onOpenModels,
            onOpenSettings = onOpenSettings,
        )
    }
}
```

### 7.2 屏幕 / 状态容器清单

| 屏幕 | 模块 | ViewModel | UiState 关键字段 | 主要事件 |
|---|---|---|---|---|
| Chat | feature-chat | `ChatViewModel(container, conversationId)` | `conversationId / title / messages / streaming / agentRound / agentMaxRounds / draftInput / attachments / params / engineKind / isGenerating / error / toolsEnabled` | `onInputChange / onSend / onStop / onAttach(uris) / onRemoveAttachment / onParamChange / onToggleThinking / onSwitchEngine / onRetry / onNewConversation` |
| Models | feature-models | `ModelsViewModel(container)` | `models / activeModelId / importState / loadingState / backendOptions / probeResult / message` | `onPickFile(uri) / onSelect(id) / onLoad(id, backend) / onUnload / onDelete(id) / onEditCapabilities(id, caps)` |
| Settings | feature-settings | `SettingsViewModel(container)` | `config / themeState / endpoints / activeEndpointId / editingEndpoint / compactParams` | `onConfigChange / onThemeChange / onEndpointSave / onEndpointDelete / onTestEndpoint` |
| Agent Tools | feature-settings.tools | `ToolsViewModel(container)` | `tools(List<ToolSpecUi>) / testName / testArgs / testResult / dangerousApproved` | `onToggle(name) / onTest(name, args) / onApproveDangerous(name)` |
| Benchmark（P2） | feature-models.benchmark | `BenchmarkViewModel(container)` | `running / ttftMs / tokensPerSecond / peakMemoryMb / rounds` | `onStart(modelId) / onStop` |

### 7.3 `ChatViewModel` 契约（完整签名 + 关键实现）

```kotlin
package com.rickeal.agent.feature.chat

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rickeal.agent.core.agent.AgentEvent
import com.rickeal.agent.core.agent.AgentRequest
import com.rickeal.agent.core.data.AppContainer
import com.rickeal.agent.core.model.Attachment
import com.rickeal.agent.core.model.ChatMessage
import com.rickeal.agent.core.model.InferenceConfig
import com.rickeal.agent.core.model.Role
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Immutable
data class ChatUiState(
    val conversationId: String? = null,
    val title: String = "新对话",
    /** 已落库/已完成的消息，稳定不变 */
    val messages: List<ChatMessage> = emptyList(),
    /** 正在流式的那条（独立 StateFlow 化，避免整列重组） */
    val streamingText: String = "",
    val streamingThinking: String = "",
    val streamingRole: Role = Role.MODEL,
    val isStreaming: Boolean = false,
    val agentRound: Int = 0,
    val agentMaxRounds: Int = 8,
    val draftInput: String = "",
    val attachments: List<Attachment> = emptyList(),
    val config: InferenceConfig = InferenceConfig(),
    val availableModels: List<String> = emptyList(),
    val isGenerating: Boolean = false,
    val error: String? = null,
    val toolsEnabled: Boolean = true,
)

class ChatViewModel(
    private val container: AppContainer,
    private val initialConversationId: String?,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var runJob: Job? = null

    init {
        viewModelScope.launch {
            container.conversationRepository.refresh()
            if (initialConversationId != null) {
                val conversation = container.conversationRepository.load(initialConversationId)
                if (conversation != null) {
                    _uiState.update {
                        it.copy(
                            conversationId = conversation.id,
                            title = conversation.title,
                            messages = conversation.messages,
                            config = conversation.config,
                        )
                    }
                }
            }
            container.settingsRepository.inferenceConfig.collect { config ->
                _uiState.update { it.copy(config = config) }
            }
        }
    }

    fun onInputChange(text: String) { _uiState.update { it.copy(draftInput = text) } }

    fun onAttachImage(uri: String, name: String, width: Int, height: Int) {
        _uiState.update {
            it.copy(attachments = it.attachments + Attachment.Image(uri = uri, name = name, width = width, height = height))
        }
    }

    fun onAttachAudio(uri: String, name: String) {
        _uiState.update {
            it.copy(attachments = it.attachments + Attachment.Audio(uri = uri, name = name))
        }
    }

    fun onRemoveAttachment(id: String) {
        _uiState.update { it.copy(attachments = it.attachments.filter { a -> a.key() != id }) }
    }

    fun onParamChange(transform: (InferenceConfig) -> InferenceConfig) {
        val next = transform(_uiState.value.config).coerce()
        _uiState.update { it.copy(config = next) }
        viewModelScope.launch { container.settingsRepository.updateInferenceConfig { next } }
    }

    fun onSend() {
        val state = _uiState.value
        val text = state.draftInput
        if (text.isBlank() && state.attachments.isEmpty()) return
        if (state.isGenerating) return

        val userMessage = ChatMessage(role = Role.USER, text = text, attachments = state.attachments)
        _uiState.update {
            it.copy(
                messages = it.messages + userMessage,
                draftInput = "",
                attachments = emptyList(),
                isGenerating = true,
                isStreaming = true,
                streamingText = "",
                streamingThinking = "",
                error = null,
            )
        }

        runJob = viewModelScope.launch {
            val model = container.modelRepository.find(container.settingsRepository.activeModelIdSync())
            val endpoint = if (state.config.engineKind == com.rickeal.agent.core.model.EngineKind.REMOTE) {
                container.endpointRepository.find(state.config.remoteEndpointId)
            } else {
                null
            }
            container.agentRunner.run(
                AgentRequest(
                    conversationId = state.conversationId,
                    history = _uiState.value.messages,
                    userInput = userMessage,
                    config = state.config,
                    model = model,
                    endpoint = endpoint,
                )
            ).collect { event ->
                when (event) {
                    is AgentEvent.RoundStarted -> _uiState.update {
                        it.copy(agentRound = event.round, agentMaxRounds = event.maxRounds)
                    }
                    is AgentEvent.TextDelta -> _uiState.update { it.copy(streamingText = it.streamingText + event.text) }
                    is AgentEvent.ThinkingDelta -> _uiState.update { it.copy(streamingThinking = it.streamingThinking + event.text) }
                    is AgentEvent.ToolCallStarted -> Unit
                    is AgentEvent.ToolResultReceived -> Unit
                    is AgentEvent.ToolSkipped -> Unit
                    is AgentEvent.MessageCommitted -> commit(event.message)
                    is AgentEvent.Finished -> finish()
                    is AgentEvent.Failed -> _uiState.update {
                        it.copy(isGenerating = false, isStreaming = false, error = event.message)
                    }
                    is AgentEvent.Cancelled -> finish()
                }
            }
        }
    }

    fun onStop() {
        runJob?.cancel()
        runJob = null
        finish()
    }

    fun onDismissError() { _uiState.update { it.copy(error = null) } }

    private fun commit(message: ChatMessage) {
        _uiState.update {
            it.copy(
                messages = it.messages + message,
                streamingText = "",
                streamingThinking = "",
                isStreaming = false,
            )
        }
        val id = _uiState.value.conversationId
        if (id != null) {
            viewModelScope.launch { container.conversationRepository.appendMessage(id, message) }
        }
    }

    private fun finish() {
        _uiState.update { it.copy(isGenerating = false, isStreaming = false) }
    }

    override fun onCleared() {
        runJob?.cancel()
        super.onCleared()
    }
}
```

> `activeModelIdSync()` 需在 `SettingsRepository` 里补一个同步读取：`suspend fun activeModelIdSync(): String? = activeModelId.first()`。dev-A 请补上（已列在 §9 清单）。

### 7.4 ViewModel Factory（无 Hilt 的标准写法）

```kotlin
package com.rickeal.agent.core.data

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

inline fun <reified VM : ViewModel> viewModelFactory(crossinline creator: () -> VM): ViewModelProvider.Factory =
    object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = creator() as T
    }
```

用法：`viewModel(factory = viewModelFactory { ChatViewModel(container, conversationId) })`

### 7.5 自适应策略（平板 / 折叠屏）

`core-design` 提供**零依赖**的窗口尺寸分类（不引 `material3-window-size-class`）：

```kotlin
package com.rickeal.agent.core.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.platform.LocalConfiguration

@Immutable
enum class WindowWidthClass { COMPACT, MEDIUM, EXPANDED }

@Immutable
enum class WindowHeightClass { COMPACT, MEDIUM, EXPANDED }

@Immutable
data class WindowSizeClass(
    val width: WindowWidthClass = WindowWidthClass.COMPACT,
    val height: WindowHeightClass = WindowHeightClass.MEDIUM,
) {
    val isExpanded: Boolean get() = width == WindowWidthClass.EXPANDED
    /** 折叠屏展开 / 平板横屏 */
    val useTwoPane: Boolean get() = width >= WindowWidthClass.MEDIUM
    /** 三栏（列表 + 对话 + 常驻参数面板） */
    val useThreePane: Boolean get() = width == WindowWidthClass.EXPANDED &&
        height != WindowHeightClass.COMPACT
}

@Composable
fun rememberWindowSizeClass(): WindowSizeClass {
    val configuration = LocalConfiguration.current
    val w = configuration.screenWidthDp
    val h = configuration.screenHeightDp
    val width = when {
        w < 600 -> WindowWidthClass.COMPACT
        w < 840 -> WindowWidthClass.MEDIUM
        else -> WindowWidthClass.EXPANDED
    }
    val height = when {
        h < 480 -> WindowHeightClass.COMPACT
        h < 900 -> WindowHeightClass.MEDIUM
        else -> WindowHeightClass.EXPANDED
    }
    return WindowSizeClass(width, height)
}
```

**布局决策表**

| 场景 | width | Chat | Models | Settings |
|---|---|---|---|---|
| 手机竖屏 | COMPACT | 单栏：消息列表 + 底部胶囊输入栏；参数走底部抽屉 | 单列卡片列表 | 单列分组 |
| 手机横屏 / 折叠屏半开 | MEDIUM | 双栏：左侧 300dp 会话列表 + 右侧对话 | 双列网格 | 双列分组 |
| 平板 / 折叠屏展开 | EXPANDED | 三栏：会话列表 320dp + 对话（max width 720dp 居中）+ 参数面板 360dp 常驻 | 三列网格 + 右侧详情面板 | 双列 + 右侧预览卡 |

实现要点：
- 用 `WindowSizeClass` 在 `ChatScreen` 顶层做 `if/else` 切两套布局，**不要**用 `BoxWithConstraints`（它会强制额外测量 pass）。
- 折叠屏用 `LocalConfiguration` 天然响应（Configuration change 会重组的）。**注意**：必须在 Manifest 给 Activity 声明 `android:configChanges="screenSize|smallestScreenSize|screenLayout|orientation"` 否则会重建 Activity 丢失流式状态；更稳妥的做法是不声明、让 Activity 重建，但把 `streamingText` 等放进 `SavedStateHandle`。**推荐：不声明 configChanges + 关键状态入 SavedStateHandle。**

### 7.6 列表性能要点（流式场景下最容易翻车）

1. **必须 `key`**：`items(messages, key = { it.id })`。流式更新最后一条时，Compose 只重组那一项。
2. **必须 `contentType`**：`items(..., contentType = { if (it.role == Role.USER) 0 else 1 })`，让复用池按类型分槽，避免用户气泡与模型气泡互相复用导致的闪烁。
3. **streaming 文本与 messages 分离**：见 `ChatUiState.streamingText`。不要把流式中的消息塞进 `messages`，否则整个 `List` 每次 chunk 都换引用 → LazyColumn 全量重组可见项。
4. **`@Immutable` 标注所有 UiState**：`androidx.compose.runtime.Immutable`，避免编译器把 `List<ChatMessage>` 判为 unstable 从而禁用智能重组。
5. **滚动到底**：用 `derivedStateOf { listState.firstVisibleItemIndex > 3 }` 控制"回到底部"按钮，不要用 `LaunchedEffect(messages.size)`（会在流式期间每帧触发）。
6. **文本测量**：长消息用 `maxLines` + `TextOverflow` 在折叠态限制；展开态不限制。
7. **图片**：用 `BitmapFactory` + `remember(uri) { BitmapFactory.decodeFile(...).asImageBitmap() }`，并显式 `Bitmap.Config.RGB_565` 缩略图；**不要**在 Composable 里 `ImageBitmap` 全分辨率。
8. **不要**在 `LazyColumn` 的 item 里创建 `ViewModel` 或 `CoroutineScope`。

---

## 8. Liquid Glass 设计系统契约（`:core-design`）

> **iOS 27 / iPadOS 27 视觉要点（dev-B 必须落实）**：
> 1. **连续曲率圆角**：圆角与直边切线连续，视觉上"没有起点"。Compose 的 `drawRoundRect`（圆弧倒角）已接近；真正的超椭圆（squircle）作为 P2 增强。
> 2. **材质分层**：ultraThin / thin / regular / thick —— 差异体现在**底色 alpha + 模糊半径 + 高光强度**，不是简单的深浅。
> 3. **折射高光**：顶边一条 1~1.5dp 的高光描边 + 上部 1/3 区域的柔和白色渐变；底部有一圈更暗的"接触阴影"。
> 4. **背景内容联动**：同一块玻璃在不同壁纸上颜色不同 —— 我们用**程序化光斑场（`GlassBackdrop`）**实现"联动"，见 §8.4。
> 5. **液态动效**：所有状态切换用弹簧（`dampingRatio ≈ 0.82`），禁止线性 Tween；按下时整体缩放到 0.96~0.97 再回弹。
> 6. **微纹理**：极低 alpha（0.03~0.05）的噪点，避免大面积纯色玻璃显"塑料感"。
> 7. **排版层级**：SF 风格 —— 大标题负字距（`-0.5sp`），正文 +0.1sp，次级文字降低对比而非降低字号。

### 8.1 材质 `GlassMaterial.kt`

```kotlin
package com.rickeal.agent.core.design

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Immutable
enum class GlassMaterial {
    ULTRA_THIN,
    THIN,
    REGULAR,
    THICK,
    OPAQUE,
}

@Immutable
data class GlassMaterialSpec(
    /** 材质底色的不透明度（决定"玻璃有多厚"） */
    val backgroundAlpha: Float,
    /** 背景模糊半径（程序化路径下用于光斑的扩散程度） */
    val blurRadius: Dp,
    /** 折射：背景色被"吸"进玻璃的强度 */
    val refractionAlpha: Float,
    /** 内描边（顶亮底暗）的峰值 alpha */
    val borderAlpha: Float,
    /** 顶部高光强度 */
    val specularAlpha: Float,
    /** 噪点 alpha */
    val noiseAlpha: Float,
    /** 外阴影高度 */
    val shadowElevation: Dp,
)

object GlassMaterials {
    val UltraThin = GlassMaterialSpec(
        backgroundAlpha = 0.14f,
        blurRadius = 14.dp,
        refractionAlpha = 0.30f,
        borderAlpha = 0.30f,
        specularAlpha = 0.16f,
        noiseAlpha = 0.020f,
        shadowElevation = 2.dp,
    )
    val Thin = GlassMaterialSpec(
        backgroundAlpha = 0.22f,
        blurRadius = 20.dp,
        refractionAlpha = 0.24f,
        borderAlpha = 0.38f,
        specularAlpha = 0.20f,
        noiseAlpha = 0.026f,
        shadowElevation = 4.dp,
    )
    val Regular = GlassMaterialSpec(
        backgroundAlpha = 0.34f,
        blurRadius = 28.dp,
        refractionAlpha = 0.18f,
        borderAlpha = 0.50f,
        specularAlpha = 0.28f,
        noiseAlpha = 0.032f,
        shadowElevation = 8.dp,
    )
    val Thick = GlassMaterialSpec(
        backgroundAlpha = 0.52f,
        blurRadius = 36.dp,
        refractionAlpha = 0.12f,
        borderAlpha = 0.62f,
        specularAlpha = 0.34f,
        noiseAlpha = 0.038f,
        shadowElevation = 16.dp,
    )
    val Opaque = GlassMaterialSpec(
        backgroundAlpha = 0.92f,
        blurRadius = 40.dp,
        refractionAlpha = 0.04f,
        borderAlpha = 0.18f,
        specularAlpha = 0.10f,
        noiseAlpha = 0.016f,
        shadowElevation = 24.dp,
    )

    fun of(material: GlassMaterial): GlassMaterialSpec = when (material) {
        GlassMaterial.ULTRA_THIN -> UltraThin
        GlassMaterial.THIN -> Thin
        GlassMaterial.REGULAR -> Regular
        GlassMaterial.THICK -> Thick
        GlassMaterial.OPAQUE -> Opaque
    }
}
```

### 8.2 令牌 `GlassTokens.kt`

```kotlin
package com.rickeal.agent.core.design

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Immutable
data class GlassTokens(
    // ---- 圆角（连续曲率阶梯）----
    val radiusXs: Dp = 8.dp,
    val radiusSm: Dp = 14.dp,
    val radiusMd: Dp = 20.dp,
    val radiusLg: Dp = 28.dp,
    val radiusXl: Dp = 36.dp,
    val radiusFull: Dp = 999.dp,
    // ---- 描边 / 高光 ----
    val borderWidth: Dp = 1.dp,
    val highlightStrokeWidth: Dp = 1.5.dp,
    val specularBandRatio: Float = 0.34f,
    // ---- 模糊 / 折射 ----
    val blurRadius: Dp = 28.dp,
    val refractionSpread: Dp = 6.dp,
    // ---- 阴影 ----
    val shadowElevation: Dp = 8.dp,
    val ambientShadowAlpha: Float = 0.18f,
    // ---- 内边距 ----
    val paddingXs: Dp = 6.dp,
    val paddingSm: Dp = 10.dp,
    val paddingMd: Dp = 14.dp,
    val paddingLg: Dp = 18.dp,
    // ---- 间距 ----
    val gapSm: Dp = 8.dp,
    val gapMd: Dp = 12.dp,
    val gapLg: Dp = 20.dp,
    // ---- 控件尺寸 ----
    val minTouchTarget: Dp = 48.dp,
    val topBarHeight: Dp = 56.dp,
    val bottomBarHeight: Dp = 72.dp,
    val fabSize: Dp = 56.dp,
)

object GlassDefaults {
    val Tokens = GlassTokens()
    val RadiusLg = 28.dp
    val ContentPadding = 16.dp
}
```

### 8.3 配色 `GlassColorScheme.kt`

```kotlin
package com.rickeal.agent.core.design

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

@Immutable
data class GlassColorScheme(
    /** 玻璃本体色（会乘以 material.backgroundAlpha） */
    val glassTint: Color,
    val glassTintElevated: Color,
    /** 内描边：顶部亮 */
    val glassBorderTop: Color,
    /** 内描边：底部暗 */
    val glassBorderBottom: Color,
    /** 折射高光 */
    val glassSpecular: Color,
    val glassShadow: Color,
    /** 玻璃上的主文本 */
    val onGlass: Color,
    val onGlassMuted: Color,
    val onGlassSubtle: Color,
    /** 强调色（发送按钮、选中态） */
    val accent: Color,
    val accentMuted: Color,
    val onAccent: Color,
    val success: Color,
    val warning: Color,
    val danger: Color,
    /** 壁纸三色渐变 */
    val wallpaperTop: Color,
    val wallpaperMid: Color,
    val wallpaperBottom: Color,
    val isDark: Boolean,
)

fun lightGlassColorScheme(): GlassColorScheme = GlassColorScheme(
    glassTint = Color(0xFFFCFCFE),
    glassTintElevated = Color(0xFFF2F3F8),
    glassBorderTop = Color(0xFFFFFFFF),
    glassBorderBottom = Color(0x33000000),
    glassSpecular = Color(0xFFFFFFFF),
    glassShadow = Color(0x1F0B1B3A),
    onGlass = Color(0xFF10121A),
    onGlassMuted = Color(0x9910121A),
    onGlassSubtle = Color(0x6610121A),
    accent = Color(0xFF2B6BFF),
    accentMuted = Color(0x332B6BFF),
    onAccent = Color(0xFFFFFFFF),
    success = Color(0xFF1E9E62),
    warning = Color(0xFFD98314),
    danger = Color(0xFFE0403F),
    wallpaperTop = Color(0xFFE9EDFB),
    wallpaperMid = Color(0xFFF6EFFA),
    wallpaperBottom = Color(0xFFE7F1FA),
    isDark = false,
)

fun darkGlassColorScheme(): GlassColorScheme = GlassColorScheme(
    glassTint = Color(0xFF15161C),
    glassTintElevated = Color(0xFF22242E),
    glassBorderTop = Color(0x66FFFFFF),
    glassBorderBottom = Color(0x14000000),
    glassSpecular = Color(0xB3FFFFFF),
    glassShadow = Color(0x66000000),
    onGlass = Color(0xFFF4F5FA),
    onGlassMuted = Color(0xB3F4F5FA),
    onGlassSubtle = Color(0x80F4F5FA),
    accent = Color(0xFF6E9BFF),
    accentMuted = Color(0x3D6E9BFF),
    onAccent = Color(0xFF0B0D12),
    success = Color(0xFF4FD08A),
    warning = Color(0xFFEBA94A),
    danger = Color(0xFFFF736F),
    wallpaperTop = Color(0xFF0B1020),
    wallpaperMid = Color(0xFF1A1430),
    wallpaperBottom = Color(0xFF071620),
    isDark = true,
)
```

### 8.4 配置 / 背景光斑 `GlassConfig.kt`

```kotlin
package com.rickeal.agent.core.design

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

@Immutable
data class GlassConfig(
    /** 真实背景模糊（GraphicsLayer.renderEffect）。关掉则退化为程序化光斑。 */
    val enableBackdropBlur: Boolean = false,
    val enableNoise: Boolean = true,
    val enableSpecular: Boolean = true,
    val reduceMotion: Boolean = false,
    /** 全局材质强度 0.5~1.5，设置页可调 */
    val intensity: Float = 1f,
)

/**
 * 背景光斑场。归一化坐标（0~1），玻璃表面绘制时按自身尺寸缩放同一组光斑，
 * 从而在视觉上"透出背后的壁纸" —— 这就是我们的「背景内容联动模糊」方案。
 * 好处：0 个不确定 API，100% 可编译，且在任何 API 级别表现一致。
 */
@Immutable
data class GlassBlob(
    val x: Float,
    val y: Float,
    val radiusFraction: Float,
    val color: Color,
)

@Immutable
data class GlassBackdrop(
    val blobs: List<GlassBlob> = GlassWallpaperDefaults.blobs,
)

object GlassWallpaperDefaults {
    val blobs: List<GlassBlob> = listOf(
        GlassBlob(0.18f, 0.12f, 0.55f, Color(0xFF6E8BFF)),
        GlassBlob(0.82f, 0.28f, 0.48f, Color(0xFFB87BFF)),
        GlassBlob(0.32f, 0.78f, 0.62f, Color(0xFF5AD6C8)),
        GlassBlob(0.72f, 0.86f, 0.44f, Color(0xFFFF9BC2)),
    )
}

val LocalGlassTokens: ProvidableCompositionLocal<GlassTokens> =
    staticCompositionLocalOf { GlassDefaults.Tokens }

val LocalGlassColors: ProvidableCompositionLocal<GlassColorScheme> =
    staticCompositionLocalOf { lightGlassColorScheme() }

val LocalGlassConfig: ProvidableCompositionLocal<GlassConfig> =
    staticCompositionLocalOf { GlassConfig() }

val LocalGlassBackdrop: ProvidableCompositionLocal<GlassBackdrop> =
    staticCompositionLocalOf { GlassBackdrop() }

val LocalLiquidMotion: ProvidableCompositionLocal<LiquidMotionSpec> =
    staticCompositionLocalOf { LiquidMotion.Default }
```

### 8.5 动效 `LiquidMotion.kt`

```kotlin
package com.rickeal.agent.core.design

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Immutable

@Immutable
data class LiquidMotionSpec(
    val stiffness: Float = Spring.StiffnessMediumLow,
    val dampingRatio: Float = 0.82f,
    val pressScale: Float = 0.965f,
    val hoverScale: Float = 1.02f,
    val enterDurationMillis: Int = 420,
    val exitDurationMillis: Int = 240,
)

object LiquidMotion {
    val Default = LiquidMotionSpec()
    val Gentle = LiquidMotionSpec(stiffness = Spring.StiffnessVeryLow, dampingRatio = 0.95f)
    val Snappy = LiquidMotionSpec(stiffness = Spring.StiffnessMedium, dampingRatio = 0.68f)

    /** 通用弹簧规格（可用于 Dp/Offset/Color 等） */
    fun <T> spring(spec: LiquidMotionSpec = Default): SpringSpec<T> = spring(
        dampingRatio = spec.dampingRatio,
        stiffness = spec.stiffness,
    )

    fun floatSpring(spec: LiquidMotionSpec = Default): SpringSpec<Float> = spring(
        dampingRatio = spec.dampingRatio,
        stiffness = spec.stiffness,
    )
}
```

### 8.6 主题 `LiquidAgentTheme.kt`

```kotlin
package com.rickeal.agent.core.design

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val LiquidTypography = Typography(
    displayLarge = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp),
    headlineMedium = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.1).sp),
    bodyLarge = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Normal, letterSpacing = 0.1.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Normal, letterSpacing = 0.1.sp, lineHeight = 21.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.1.sp),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.2.sp),
)

@Composable
fun LiquidAgentTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    glassConfig: GlassConfig = GlassConfig(),
    backdrop: GlassBackdrop = GlassBackdrop(),
    tokens: GlassTokens = GlassDefaults.Tokens,
    content: @Composable () -> Unit,
) {
    val glassColors = if (darkTheme) darkGlassColorScheme() else lightGlassColorScheme()
    val materialColors = if (darkTheme) {
        darkColorScheme(
            primary = glassColors.accent,
            onPrimary = glassColors.onAccent,
            surface = Color.Transparent,
            background = glassColors.wallpaperTop,
            onBackground = glassColors.onGlass,
        )
    } else {
        lightColorScheme(
            primary = glassColors.accent,
            onPrimary = glassColors.onAccent,
            surface = Color.Transparent,
            background = glassColors.wallpaperTop,
            onBackground = glassColors.onGlass,
        )
    }
    CompositionLocalProvider(
        LocalGlassColors provides glassColors,
        LocalGlassConfig provides glassConfig,
        LocalGlassBackdrop provides backdrop,
        LocalGlassTokens provides tokens,
        LocalLiquidMotion provides if (glassConfig.reduceMotion) LiquidMotion.Gentle else LiquidMotion.Default,
    ) {
        MaterialTheme(
            colorScheme = materialColors,
            typography = LiquidTypography,
            content = content,
        )
    }
}
```

### 8.7 核心 Modifier：`liquidGlass`（**dev-B 的核心实现，可直接粘贴**）

```kotlin
package com.rickeal.agent.core.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CornerRadius
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.random.Random

/**
 * 纯函数版：所有视觉输入显式传入。测试与非 Composable 环境用这个。
 */
fun Modifier.liquidGlass(
    tokens: GlassTokens,
    colors: GlassColorScheme,
    backdrop: GlassBackdrop,
    material: GlassMaterial = GlassMaterial.REGULAR,
    cornerRadius: Dp = GlassDefaults.RadiusLg,
    intensity: Float = 1f,
    noise: Boolean = true,
    specular: Boolean = true,
): Modifier = this
    .drawWithCache {
        val spec = GlassMaterials.of(material)
        val radiusPx = cornerRadius.toPx()
        val corner = CornerRadius(radiusPx, radiusPx)

        // 1) 玻璃底色：上亮下暗的垂直渐变
        val fillBrush = Brush.verticalGradient(
            colors = listOf(
                colors.glassTint.copy(alpha = (spec.backgroundAlpha * intensity).coerceIn(0f, 1f)),
                colors.glassTintElevated.copy(
                    alpha = (spec.backgroundAlpha * 0.72f * intensity).coerceIn(0f, 1f),
                ),
            ),
            startY = 0f,
            endY = size.height,
        )

        // 2) 背景折射：把壁纸光斑场按自身尺寸缩放后画进来
        val refracted = backdrop.blobs.map { blob ->
            RefractionBlob(
                center = Offset(blob.x * size.width, blob.y * size.height),
                radius = blob.radiusFraction * max(size.width, size.height) * 0.5f,
                brush = Brush.radialGradient(
                    colors = listOf(
                        blob.color.copy(alpha = (spec.refractionAlpha * intensity).coerceIn(0f, 1f)),
                        Color.Transparent,
                    ),
                ),
            )
        }

        // 3) 内描边：顶亮底暗
        val borderBrush = Brush.verticalGradient(
            colors = listOf(
                colors.glassBorderTop.copy(alpha = (spec.borderAlpha * intensity).coerceIn(0f, 1f)),
                Color.Transparent,
                colors.glassBorderBottom.copy(alpha = (spec.borderAlpha * 0.8f).coerceIn(0f, 1f)),
            ),
            startY = 0f,
            endY = size.height,
        )

        // 4) 顶部折射高光带
        val specularBrush = Brush.verticalGradient(
            colors = listOf(
                colors.glassSpecular.copy(alpha = (spec.specularAlpha * intensity).coerceIn(0f, 1f)),
                Color.Transparent,
            ),
            startY = 0f,
            endY = size.height * tokens.specularBandRatio,
        )

        // 5) 噪点微纹理（固定种子，尺寸变化时重建）
        val noisePoints = if (noise) buildNoisePoints(size, 150) else emptyList()
        val noisePaint = Paint().apply {
            color = colors.glassSpecular.copy(alpha = spec.noiseAlpha)
            strokeWidth = 1.5f
            strokeCap = StrokeCap.Round
        }

        val strokeWidthPx = tokens.highlightStrokeWidth.toPx()

        onDrawWithContent {
            drawContent()
            for (blob in refracted) {
                drawCircle(brush = blob.brush, center = blob.center, radius = blob.radius)
            }
            drawRoundRect(brush = fillBrush, cornerRadius = corner)
            drawRoundRect(
                brush = borderBrush,
                cornerRadius = corner,
                style = Stroke(width = strokeWidthPx),
            )
            if (specular) {
                drawRoundRect(
                    brush = specularBrush,
                    topLeft = Offset(strokeWidthPx, strokeWidthPx),
                    size = Size(
                        width = (size.width - strokeWidthPx * 2).coerceAtLeast(0f),
                        height = (size.height * tokens.specularBandRatio - strokeWidthPx).coerceAtLeast(0f),
                    ),
                    cornerRadius = CornerRadius(radiusPx * 0.85f, radiusPx * 0.85f),
                )
            }
            if (noisePoints.isNotEmpty()) {
                drawPoints(pointMode = PointMode.Points, points = noisePoints, paint = noisePaint)
            }
        }
    }

/**
 * Composable 版：从 CompositionLocal 取 tokens/colors/backdrop/config。UI 代码一律用这个。
 * （刻意不用 Modifier.composed —— 该 API 在新版 Compose 里已不推荐，用 @Composable 扩展更安全。）
 */
@Composable
fun Modifier.liquidGlass(
    material: GlassMaterial = GlassMaterial.REGULAR,
    cornerRadius: Dp = GlassDefaults.RadiusLg,
    intensity: Float = 1f,
    noise: Boolean = true,
    specular: Boolean = true,
): Modifier {
    val tokens = LocalGlassTokens.current
    val colors = LocalGlassColors.current
    val backdrop = LocalGlassBackdrop.current
    return this.liquidGlass(
        tokens = tokens,
        colors = colors,
        backdrop = backdrop,
        material = material,
        cornerRadius = cornerRadius,
        intensity = intensity * LocalGlassConfig.current.intensity,
        noise = noise && LocalGlassConfig.current.enableNoise,
        specular = specular && LocalGlassConfig.current.enableSpecular,
    )
}

private class RefractionBlob(
    val center: Offset,
    val radius: Float,
    val brush: Brush,
)

private fun buildNoisePoints(size: Size, count: Int): List<Offset> {
    val random = Random(2026)
    return List(count) {
        Offset(random.nextFloat() * size.width, random.nextFloat() * size.height)
    }
}
```

> **注意上面几处导入**：`drawIntoCanvas` / `ContentDrawScope` / `DrawScope` / `DrawModifierNode` 在本实现里**没有用到**，dev-B 请从 import 列表里删掉（Kotlin 未使用 import 只是警告，不影响编译，但保持整洁）。实际需要的是：
> `androidx.compose.ui.draw.drawWithCache`、`androidx.compose.ui.graphics.drawscope.Stroke`、`androidx.compose.foundation.shape.RoundedCornerShape`、`androidx.compose.ui.draw.clip`。
>
> **clip 说明**：上面的 `liquidGlass` 依赖外部先 `.clip(RoundedCornerShape(cornerRadius))`。因此 `LiquidGlassSurface` 的组装顺序必须是：
> `Modifier.clip(shape).liquidGlass(...)`。clip 在前，玻璃绘制才落在圆角内。

### 8.8 组件签名清单（dev-B 直接照抄）

```kotlin
package com.rickeal.agent.core.design

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.animateFloatAsState

/* ------------------------------------------------------------------ 表面 */

/** 一切玻璃容器的基座。onClick 非空时自动带按压弹簧反馈。 */
@Composable
fun LiquidGlassSurface(
    modifier: Modifier = Modifier,
    material: GlassMaterial = GlassMaterial.REGULAR,
    cornerRadius: Dp = GlassDefaults.RadiusLg,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    contentPadding: PaddingValues = PaddingValues(GlassDefaults.ContentPadding),
    contentAlignment: Alignment = Alignment.TopStart,
    propagateMinConstraints: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
)

/** 语义化别名：卡片。默认 REGULAR 材质 + radiusLg + 16dp 内边距。 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    material: GlassMaterial = GlassMaterial.REGULAR,
    cornerRadius: Dp = 24.dp,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(GlassDefaults.ContentPadding),
    content: @Composable BoxScope.() -> Unit,
)

/** 对话气泡。isUser 决定对齐、材质与强调色。 */
@Composable
fun GlassBubble(
    text: String,
    isUser: Boolean,
    modifier: Modifier = Modifier,
    thinking: String? = null,
    thinkingExpanded: Boolean = false,
    onToggleThinking: (() -> Unit)? = null,
    attachments: List<com.rickeal.agent.core.model.Attachment> = emptyList(),
    isStreaming: Boolean = false,
    errorMessage: String? = null,
    usage: com.rickeal.agent.core.model.TokenUsage? = null,
    onLongClick: (() -> Unit)? = null,
)

/* ------------------------------------------------------------------ 骨架 */

@Composable
fun GlassScaffold(
    modifier: Modifier = Modifier,
    topBar: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    snackbarHost: @Composable () -> Unit = {},
    wallpaper: @Composable () -> Unit = { GlassWallpaper(modifier = Modifier.matchParentSize()) },
    contentWindowInsets: WindowInsets = WindowInsets(0, 0, 0, 0),
    content: @Composable (PaddingValues) -> Unit,
)

/** 程序化壁纸：三层渐变 + 光斑场。必须铺满 Scaffold 底层。 */
@Composable
fun GlassWallpaper(
    modifier: Modifier = Modifier,
    colors: GlassColorScheme = LocalGlassColors.current,
    backdrop: GlassBackdrop = LocalGlassBackdrop.current,
    intensity: Float = LocalGlassConfig.current.intensity,
)

@Composable
fun GlassTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    navigationIcon: (@Composable () -> Unit)? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
    /** 0f=完全展开（超薄材质），1f=完全折叠（加厚材质 + 分隔线） */
    scrollFraction: Float = 0f,
)

@Composable
fun GlassBottomBar(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
)

/* ------------------------------------------------------------------ 控件 */

@Composable
fun GlassFab(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    expanded: Boolean = false,
    label: String? = null,
    material: GlassMaterial = GlassMaterial.THICK,
    content: @Composable () -> Unit,
)

@Composable
fun GlassButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    icon: (@Composable () -> Unit)? = null,
    material: GlassMaterial = GlassMaterial.REGULAR,
    cornerRadius: Dp = GlassDefaults.RadiusLg,
    contentPadding: PaddingValues = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
)

/** iOS 风格分段控件，指示块用弹簧位移。 */
@Composable
fun GlassSegmented(
    items: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
)

@Composable
fun GlassSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    valueText: String? = null,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    enabled: Boolean = true,
    onValueChangeFinished: (() -> Unit)? = null,
)

@Composable
fun GlassTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    enabled: Boolean = true,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else 6,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
)

@Composable
fun GlassSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
)

@Composable
fun GlassChip(
    text: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
)

@Composable
fun GlassDialog(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    confirmLabel: String? = null,
    onConfirm: (() -> Unit)? = null,
    dismissLabel: String? = null,
    content: @Composable () -> Unit,
)

/** 思考中的三点呼吸指示器。 */
@Composable
fun GlassThinkingIndicator(
    modifier: Modifier = Modifier,
    label: String? = "思考中",
)

@Composable
fun GlassDivider(modifier: Modifier = Modifier, alpha: Float = 0.35f)

@Composable
fun GlassEmptyState(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    action: (@Composable () -> Unit)? = null,
)

/** 设置页的通用「标题 + 说明 + 右侧控件」行。 */
@Composable
fun GlassSettingRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
)
```

### 8.9 低版本 / 低性能降级方案

| 场景 | 处理 |
|---|---|
| **API < 31（本项目 minSdk=31，实际不会走到）** | 默认路径是纯 Compose Canvas 绘制，**不依赖 `RenderEffect`**，所以在 API 21+ 都能画。若未来降 minSdk，无需改动。 |
| **`RenderEffect` 真模糊** | 放在 P2 的 `GlassBackdropBlur.kt`（独立文件）。仅当 `GlassConfig.enableBackdropBlur == true && Build.VERSION.SDK_INT >= 31` 时使用 `GraphicsLayer.renderEffect`；**若 CI 报 `renderEffect` unresolved，直接删除该文件并把 `enableBackdropBlur` 默认改 false，其余代码零影响。** |
| **低端机 / 用户开启"减弱动效"** | `GlassConfig.reduceMotion = true` → `LiquidMotion.Gentle` + 关闭噪点 + 材质降到 THIN。整块由 `LocalGlassConfig` 一处驱动。 |
| **超大 surface 上的噪点开销** | `buildNoisePoints` 固定 150 个点，与尺寸无关，开销恒定。 |
| **深色/浅色** | 两套 `GlassColorScheme`，随 `isSystemInDarkTheme()` 切换；壁纸光斑的 alpha 会随 `isDark` 自动适配（暗色下光斑更亮）。 |

### 8.10 材质层次使用规范

| 组件 | 材质 | 圆角 |
|---|---|---|
| 壁纸 / 页面底 | OPAQUE（不直接用） | — |
| Scaffold 顶栏（展开） | ULTRA_THIN | — |
| Scaffold 顶栏（折叠） | THIN | — |
| 聊天气泡（模型） | THIN | radiusLg + 一侧小圆角 |
| 聊天气泡（用户） | REGULAR + accent tint | radiusLg |
| 卡片 / 列表项 | REGULAR | radiusMd |
| 底部输入栏 | THICK | radiusXl |
| FAB / 悬浮按钮 | THICK | radiusFull |
| Dialog | THICK | radiusXl |
| 分段控件背景 | THIN | radiusFull |
| Slider 轨道 | ULTRA_THIN | radiusFull |

---

## 9. 构建骨架与完整文件清单

### 9.1 `settings.gradle.kts`

```kotlin
pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Android-Agent"

include(":app")
include(":core-model")
include(":core-engine")
include(":core-agent")
include(":core-data")
include(":core-design")
include(":feature-chat")
include(":feature-models")
include(":feature-settings")
```

### 9.2 `gradle/libs.versions.toml`

```toml
[versions]
agp = "9.3.2"
kotlin = "2.3.0"
compileSdk = "36"
minSdk = "31"
targetSdk = "36"

composeBom = "2026.02.00"
coreKtx = "1.15.0"
activityCompose = "1.10.1"
lifecycle = "2.8.7"
navigationCompose = "2.8.9"
serialization = "1.7.3"
materialIcons = "1.7.8"
datastore = "1.1.7"
okhttp = "4.12.0"
coroutines = "1.9.0"
litertlm = "0.11.0"

[libraries]
androidx-core-ktx = { module = "androidx.core:core-ktx", version.ref = "coreKtx" }
androidx-activity-compose = { module = "androidx.activity:activity-compose", version.ref = "activityCompose" }
androidx-lifecycle-runtime-ktx = { module = "androidx.lifecycle:lifecycle-runtime-ktx", version.ref = "lifecycle" }
androidx-lifecycle-viewmodel-compose = { module = "androidx.lifecycle:lifecycle-viewmodel-compose", version.ref = "lifecycle" }
androidx-lifecycle-runtime-compose = { module = "androidx.lifecycle:lifecycle-runtime-compose", version.ref = "lifecycle" }
androidx-navigation-compose = { module = "androidx.navigation:navigation-compose", version.ref = "navigationCompose" }
androidx-compose-bom = { module = "androidx.compose:compose-bom", version.ref = "composeBom" }
androidx-compose-ui = { module = "androidx.compose.ui:ui" }
androidx-compose-ui-graphics = { module = "androidx.compose.ui:ui-graphics" }
androidx-compose-ui-tooling-preview = { module = "androidx.compose.ui:ui-tooling-preview" }
androidx-compose-ui-tooling = { module = "androidx.compose.ui:ui-tooling" }
androidx-compose-material3 = { module = "androidx.compose.material3:material3" }
androidx-compose-material-icons-extended = { module = "androidx.compose.material:material-icons-extended", version.ref = "materialIcons" }
androidx-datastore-preferences = { module = "androidx.datastore:datastore-preferences", version.ref = "datastore" }
kotlinx-coroutines-android = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-android", version.ref = "coroutines" }
kotlinx-serialization-json = { module = "org.jetbrains.kotlinx:kotlinx-serialization-json", version.ref = "serialization" }
okhttp = { module = "com.squareup.okhttp3:okhttp", version.ref = "okhttp" }
litertlm-android = { module = "com.google.ai.edge.litertlm:litertlm-android", version.ref = "litertlm" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
android-library = { id = "com.android.library", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
```

### 9.3 根 `build.gradle.kts`

```kotlin
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
```

### 9.4 `gradle.properties`

```properties
org.gradle.jvmargs=-Xmx4096m -XX:MaxMetaspaceSize=1024m -Dfile.encoding=UTF-8
org.gradle.parallel=true
org.gradle.caching=true
org.gradle.configuration-cache=false
android.useAndroidX=true
android.nonTransitiveRClass=true
android.nonFinalResIds=true
kotlin.code.style=official
```

### 9.5 模块 `build.gradle.kts` 模板（三种）

```kotlin
// ===== 纯 Kotlin+序列化模块（:core-model） =====
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

android {
    namespace = "com.rickeal.agent.core.model"
    compileSdk { version = release(36) }
    defaultConfig { minSdk = 31 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_21) } }
}
dependencies {
    implementation(libs.kotlinx.coroutines.android)
    api(libs.kotlinx.serialization.json)
}
```

```kotlin
// ===== 带 Android 原生依赖的核心模块（:core-engine / :core-agent / :core-data） =====
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

android {
    namespace = "com.rickeal.agent.core.engine"
    compileSdk { version = release(36) }
    defaultConfig { minSdk = 31 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_21) } }
}
dependencies {
    api(projects.coreModel)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    api(libs.kotlinx.serialization.json)
    // 仅 :core-engine 需要：
    api(libs.litertlm.android)
    implementation(libs.okhttp)
    // 仅 :core-data 需要：
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.compose.runtime)   // CompositionLocal（:core-data 专用）
}
```

```kotlin
// ===== Compose UI 模块（:core-design / :feature-*） =====
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

android {
    namespace = "com.rickeal.agent.core.design"
    compileSdk { version = release(36) }
    defaultConfig { minSdk = 31 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_21) } }
    buildFeatures { compose = true }
}
dependencies {
    val bom = libs.androidx.compose.bom
    implementation(platform(bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
```

> `libs.androidx.compose.runtime` 需要在 catalog 里补一行：
> `androidx-compose-runtime = { module = "androidx.compose.runtime:runtime" }`。
> `projects.coreModel` 是 Gradle 的类型安全项目访问器（AGP 8+/Gradle 8+ 默认开启）；若不生效，退回 `implementation(project(":core-model"))`。
> ⚠️ **2026-09-18 实测修正（AGP 9 时代）**：
> - 模板中的 `alias(libs.plugins.kotlin.android)` 一行**必须删除**（AGP 9 内置 Kotlin，禁止显式应用，见 §1.2.1）。
> - `jvmTarget` 实测采用 **`JvmTarget.JVM_17`**（模板写的 JVM_21 以 §1.2 表格为准）。
> - 若 `kotlin { compilerOptions { jvmTarget.set(...) } }` 编译不过，退回 `kotlinOptions { jvmTarget = "17" }`。
> - `libs.versions.toml` 里的 `kotlin` 版本现在只影响 `kotlin-compose` / `kotlin-serialization` 两个插件，**必须与 AGP 内置 Kotlin 版本一致**（见 §10 R16）。

### 9.6 `app/src/main/AndroidManifest.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.READ_MEDIA_IMAGES" />
    <uses-permission android:name="android.permission.READ_MEDIA_AUDIO" />
    <uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE"
        android:maxSdkVersion="32" />

    <application
        android:name=".LiquidAgentApplication"
        android:allowBackup="false"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:roundIcon="@mipmap/ic_launcher"
        android:supportsRtl="true"
        android:theme="@style/Theme.LiquidAgent"
        android:usesCleartextTraffic="true">

        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:windowSoftInputMode="adjustResize"
            android:theme="@style/Theme.LiquidAgent">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>

</manifest>
```

> `usesCleartextTraffic="true"` 是**必需的**：Ollama / 自建 vLLM 常用 `http://` 局域网地址，targetSdk 28+ 默认禁明文。

### 9.7 文件清单 — **dev-A：基础设施 / 核心逻辑**

| # | 绝对路径（相对 `Android-Agent/`） | 职责 |
|---|---|---|
| A1 | `settings.gradle.kts` | 扁平 include 9 个模块 + 仓库声明 |
| A2 | `build.gradle.kts` | 根：仅声明插件且不 apply |
| A3 | `gradle.properties` | JVM 参数、AndroidX 开关 |
| A4 | `gradle/libs.versions.toml` | 版本目录（唯一版本真源） |
| A5 | `gradle/wrapper/gradle-wrapper.properties` | 声明 9.7.1；jar 取自 gradle/gradle 官方 v9.7.1 tag。**CI 不走 wrapper**，由 setup-gradle 安装 Gradle 9.7.1 |
| A6 | `.gitignore` | `.gradle/ build/ local.properties *.litertlm *.task` |
| A7 | `app/build.gradle.kts` | 应用模块，依赖全部 feature/core |
| A8 | `app/src/main/AndroidManifest.xml` | 权限 + Application + Activity |
| A9 | `app/src/main/res/values/strings.xml` | `app_name=LiquidAgent` 等 |
| A10 | `app/src/main/res/values/themes.xml` | `Theme.LiquidAgent`（parent `android:Theme.Material.Light.NoActionBar`） |
| A11 | `app/src/main/res/values/ic_launcher_background.xml` | 自适应图标底色 |
| A12 | `app/src/main/res/drawable/ic_launcher_foreground.xml` | 自适应图标矢量前景 |
| A13 | `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml` | 自适应图标装配 |
| A14 | `app/src/main/java/com/rickeal/agent/LiquidAgentApplication.kt` | 建 `AppContainer` 并 `bootstrap()` |
| A15 | `app/src/main/java/com/rickeal/agent/MainActivity.kt` | `setContent { AppRoot() }` |
| A16 | `app/src/main/java/com/rickeal/agent/ui/AppRoot.kt` | `LiquidAgentTheme` + `CompositionLocalProvider(LocalAppContainer)` |
| A17 | `app/src/main/java/com/rickeal/agent/ui/AgentNavHost.kt` | 组装 5 条路由的 NavHost |
| A18 | `app/src/main/java/com/rickeal/agent/di/AppProvider.kt` | `AppContainer` 的 remember/获取辅助 |
| A19 | `README.md` | 定位、模型导入说明、LiteRT-LM 版本说明、许可证 |
| A20 | `LICENSE` | Apache-2.0 全文 |
| A21 | `.github/workflows/android-ci.yml` | **由 cicd 负责**：JDK21 + 装 Gradle 9.7.1 + `assembleDebug` |
| A22 | `core-model/build.gradle.kts` | 纯 Kotlin + serialization |
| A23 | `core-model/src/main/java/.../model/Ids.kt` | `newId()` |
| A24 | `core-model/.../AgentJson.kt` | 全局 Json 实例 |
| A25 | `core-model/.../Role.kt` | Role 枚举 |
| A26 | `core-model/.../Attachment.kt` | 多模态附件 sealed 族 |
| A27 | `core-model/.../Generation.kt` | FinishReason/TokenUsage/GenerationChunk/ToolCallDelta |
| A28 | `core-model/.../ToolSpec.kt` | ToolSpec/ToolParameter/ToolCall/ToolResult |
| A29 | `core-model/.../ChatMessage.kt` | ChatMessage |
| A30 | `core-model/.../Conversation.kt` | Conversation + ConversationMeta + toMeta |
| A31 | `core-model/.../SamplingParams.kt` | SamplingParams + InferenceConfig + ThinkingMode + InferenceBackend + EngineKind |
| A32 | `core-model/.../ModelDescriptor.kt` | ModelDescriptor + ModelCapabilities + 枚举 |
| A33 | `core-model/.../RemoteEndpoint.kt` | RemoteEndpoint + RemotePresets + ThinkingParamStyle（**注意：用 §2.10 修正后的版本**） |
| A34 | `core-model/.../Base64ByteArraySerializer.kt` | ByteArray↔Base64 序列化器 |
| A35 | `core-model/.../TokenEstimator.kt` | 无 tokenizer 的 token 估算 |
| A36 | `core-model/.../StreamAccumulator.kt` | StreamAccumulator + DeltaTracker |
| A37 | `core-model/.../ModelHeuristics.kt` | 按文件名推断模型家族/量化/能力位 |
| A38 | `core-engine/build.gradle.kts` | litertlm + okhttp |
| A39 | `core-engine/.../engine/EngineContract.kt` | LlmEngine / EngineFactory / EngineLoadConfig / GenerationRequest / EngineCapabilities / EngineException |
| A40 | `core-engine/.../engine/local/LiteRtLmEngine.kt` | LiteRT-LM 实现（回调→Channel→Flow） |
| A41 | `core-engine/.../engine/local/AttachmentBytesReader.kt` | 图片转 PNG 字节、音频读字节 |
| A42 | `core-engine/.../engine/remote/OpenAiDto.kt` | OpenAI 请求/响应 SSE DTO |
| A43 | `core-engine/.../engine/remote/OpenAiCompatibleEngine.kt` | OkHttp + 手写 SSE |
| A44 | `core-engine/.../engine/DefaultEngineFactory.kt` | 工厂 + EngineEnvironment |
| A45 | `core-agent/build.gradle.kts` | 依赖 model + engine |
| A46 | `core-agent/.../agent/Tool.kt` | Tool / ToolContext / ToolContributor |
| A47 | `core-agent/.../agent/ToolRegistry.kt` | 注册中心 + 开关 |
| A48 | `core-agent/.../agent/AgentPolicy.kt` | 护栏参数 |
| A49 | `core-agent/.../agent/AgentEvents.kt` | AgentEvent sealed + AgentRequest |
| A50 | `core-agent/.../agent/AgentRunner.kt` | Agent 主循环 |
| A51 | `core-agent/.../agent/TextToolProtocol.kt` | ```json / `<tool_call>` 解析与剥离 |
| A52 | `core-agent/.../agent/ContextCompressor.kt` | 滑窗 + 摘要压缩 |
| A53 | `core-agent/.../agent/tools/ToolArgs.kt` | `stringArg` / `json` 辅助 |
| A54 | `core-agent/.../agent/tools/CalculatorTool.kt` | 计算器 + ExpressionEvaluator |
| A55 | `core-agent/.../agent/tools/DateTimeTool.kt` | 时间日期 |
| A56 | `core-agent/.../agent/tools/SandboxedFileTools.kt` | 基类 + 读/写/列目录 |
| A57 | `core-agent/.../agent/tools/ClipboardTool.kt` | 剪贴板读写 |
| A58 | `core-agent/.../agent/tools/PlaceholderTools.kt` | WebSearchTool / ImageDescribeTool 占位 |
| A59 | `core-agent/.../agent/DefaultTools.kt` | 内置工具装配 |
| A60 | `core-data/build.gradle.kts` | datastore + compose-runtime |
| A61 | `core-data/.../data/JsonFileStore.kt` | 原子 JSON 读写 |
| A62 | `core-data/.../data/SettingsRepository.kt` | DataStore 设置（**补 `activeModelIdSync()`**） |
| A63 | `core-data/.../data/ModelRepository.kt` | 模型清单 |
| A64 | `core-data/.../data/EndpointRepository.kt` | 远程端点清单 |
| A65 | `core-data/.../data/ConversationRepository.kt` | 会话 index + 单文件 |
| A66 | `core-data/.../data/AppContainer.kt` | 手写 DI 容器 + LocalAppContainer |
| A67 | `core-data/.../data/ViewModelFactory.kt` | `viewModelFactory { }` |

### 9.8 文件清单 — **dev-B：设计系统 / UI / feature**

| # | 绝对路径（相对 `Android-Agent/`） | 职责 |
|---|---|---|
| B1 | `core-design/build.gradle.kts` | Compose + material3 + icons |
| B2 | `core-design/.../design/GlassMaterial.kt` | 材质枚举 + GlassMaterials |
| B3 | `core-design/.../design/GlassTokens.kt` | 圆角/描边/模糊/间距令牌 + GlassDefaults |
| B4 | `core-design/.../design/GlassColorScheme.kt` | 明暗两套玻璃配色 |
| B5 | `core-design/.../design/GlassConfig.kt` | GlassConfig + GlassBackdrop + 4 个 CompositionLocal |
| B6 | `core-design/.../design/LiquidMotion.kt` | 弹簧动效规格 |
| B7 | `core-design/.../design/LiquidAgentTheme.kt` | 主题 + 排版 |
| B8 | `core-design/.../design/LiquidGlassModifier.kt` | **核心** `Modifier.liquidGlass`（两个重载） |
| B9 | `core-design/.../design/GlassSurface.kt` | LiquidGlassSurface / GlassCard / GlassBubble |
| B10 | `core-design/.../design/GlassScaffold.kt` | GlassScaffold + GlassWallpaper |
| B11 | `core-design/.../design/GlassTopBar.kt` | GlassTopBar + GlassBottomBar |
| B12 | `core-design/.../design/GlassFab.kt` | 悬浮按钮 |
| B13 | `core-design/.../design/GlassSegmented.kt` | 分段控件 |
| B14 | `core-design/.../design/GlassSlider.kt` | 参数滑块 |
| B15 | `core-design/.../design/GlassButton.kt` | 按钮 |
| B16 | `core-design/.../design/GlassTextField.kt` | 输入框 |
| B17 | `core-design/.../design/GlassSwitch.kt` | 开关 |
| B18 | `core-design/.../design/GlassChip.kt` | 标签 |
| B19 | `core-design/.../design/GlassDialog.kt` | 对话框 |
| B20 | `core-design/.../design/GlassIndicators.kt` | GlassThinkingIndicator / GlassDivider / GlassEmptyState |
| B21 | `core-design/.../design/GlassSettingRow.kt` | 设置行 |
| B22 | `core-design/.../design/WindowSizeClass.kt` | 自适应断点 |
| B23 | `core-design/.../design/GlassBackdropBlur.kt` | **P2 可删**：真实 RenderEffect 背景模糊 |
| B24 | `feature-chat/build.gradle.kts` | 依赖 model/design/data/agent |
| B25 | `feature-chat/.../chat/ChatRoute.kt` | 路由常量 + `NavGraphBuilder.chatGraph` |
| B26 | `feature-chat/.../chat/ChatViewModel.kt` | 状态容器 + Agent 事件收集 |
| B27 | `feature-chat/.../chat/ChatScreen.kt` | 页面（含自适应三档布局） |
| B28 | `feature-chat/.../chat/ChatMessageList.kt` | LazyColumn + key/contentType |
| B29 | `feature-chat/.../chat/ChatInputBar.kt` | 输入 + 附件 + 发送/停止 |
| B30 | `feature-chat/.../chat/ChatParamsSheet.kt` | 采样参数底部抽屉（COMPACT/MEDIUM 用） |
| B31 | `feature-chat/.../chat/ChatParamsPanel.kt` | 常驻参数面板（EXPANDED 用） |
| B32 | `feature-chat/.../chat/ChatAttachmentPicker.kt` | 图片/音频选择（`OpenDocument` + `BitmapFactory`） |
| B33 | `feature-models/build.gradle.kts` | |
| B34 | `feature-models/.../models/ModelsRoute.kt` | 路由 |
| B35 | `feature-models/.../models/ModelsViewModel.kt` | 列表/导入/加载/后端 |
| B36 | `feature-models/.../models/ModelsScreen.kt` | 页面（自适应网格） |
| B37 | `feature-models/.../models/ModelCard.kt` | 模型卡片 |
| B38 | `feature-models/.../models/ModelImportDialog.kt` | 导入 + 能力位勾选 |
| B39 | `feature-settings/build.gradle.kts` | |
| B40 | `feature-settings/.../settings/SettingsRoute.kt` | 设置 + 端点两条路由 |
| B41 | `feature-settings/.../settings/SettingsViewModel.kt` | 参数/主题/端点 |
| B42 | `feature-settings/.../settings/SettingsScreen.kt` | 设置页 |
| B43 | `feature-settings/.../settings/EndpointsScreen.kt` | 远程端点 CRUD |
| B44 | `feature-settings/.../settings/tools/ToolsRoute.kt` | 工具页路由 |
| B45 | `feature-settings/.../settings/tools/ToolsViewModel.kt` | 工具开关 + 试跑 |
| B46 | `feature-settings/.../settings/tools/ToolsScreen.kt` | 工具页 |

### 9.9 并行契约边界（两人互不阻塞）

| 契约 | 由谁定义 | 另一方怎么用 |
|---|---|---|
| `:core-model` 全部类型 | dev-A（本文已锁定源码） | dev-B **直接复制本文源码**建文件，不必等 dev-A |
| `:core-design` 全部签名 | dev-B（本文已锁定签名） | dev-A 不需要引用 |
| `LocalAppContainer` / `AppContainer` 字段 | dev-A（§6.4） | dev-B 按字段名调用 |
| `ChatRoute.build()` / `ModelsRoute` / `SettingsRoute` | dev-B | dev-A 在 `AgentNavHost` 里按名字调用 |
| `AgentEvent` 分支 | dev-A（§4.3） | dev-B 的 `ChatViewModel` 按 §7.3 的 `when` 抄 |

**建议顺序**：
1. 两人同时开：dev-A 从构建骨架 + core-model 开始；dev-B 从 core-design 开始（零项目依赖，可独立写完）。
2. dev-A 交付 core-model 后立刻转 core-engine（风险最高，优先暴露 CI 问题）。
3. dev-B 写完 core-design 转 feature-chat（按 §7.3 的锁定签名写，不阻塞）。
4. 最后 dev-A 收尾 core-data/AppContainer，dev-B 收尾 feature-models/settings。
5. **每完成一个模块就推一次 CI**，不要把 9 个模块攒到最后一起验证。

---

## 10. 风险与取舍表

| # | 风险 | 影响 | 概率 | 规避 / 预案 |
|---|---|---|---|---|
| R1 | **CI 编译风险（最大）**：本地无 JDK/SDK/Gradle，唯一验证通道是 Actions；一次红只能靠日志定位 | 交付阻塞 | 高 | ① 每完成 1~2 个模块就推一次 CI，小步验证；② 严格只用本文锁定的 API；③ 所有"没把握"的 API 都隔离在单文件并标注可删（B23 / SquircleShape）；④ CI 同时产出 `assembleDebug` + lint 报告便于定位 |
| R2 | **Compose BOM 2026.02.00 × Kotlin 版本未同仓验证**（gallery 用 Kotlin 2.2.21） | 编译失败 | 中 | ⚠️ **原回退路径已失效**：AGP 9 内置 Kotlin 后，"把 Kotlin 降到 2.2.21"不再是改一行能做到的（§1.2.1）。新回退顺序：① 先确认 AGP 内置 Kotlin 版本并把两个 Kotlin 插件对齐；② 再考虑降 AGP；③ 最后才考虑降 BOM。**不要**再执行"改 catalog 一行降 Kotlin" |
| R3 | **LiteRT-LM 0.11.0 API 漂移**：`EngineConfig` 的 `visionBackend/audioBackend`、`Backend.NPU(nativeLibraryDir=)`、`SamplerConfig(topK/topP/temperature)` 均为 gallery 摘录，未本地核对 | 编译失败 | 中 | ① 编译报错时**只改 `LiteRtLmEngine.kt` 一个文件**，其余不动；② 若 `EngineConfig` 参数不匹配，退化为只传 `modelPath + backend + maxNumTokens + cacheDir`（视觉/音频能力暂关）；③ 升级到 0.17.x 留 TODO，**本次不做** |
| R4 | **`ConversationConfig` 三参数传空导致能力降级**：系统提示词走 `messages[0]`，工具走文本协议 | 功能降级 | 已接受 | 明确写进 README；`nativeToolChannel=false` 已让 Agent 自动走文本协议，功能不缺失 |
| R5 | **模型来源与体积**：4B 模型常 >2GB，CI 与构建期**绝不能下载**；用户导入路径不可控 | CI 超时 / 用户困惑 | 高 | ① CI 只跑 `assembleDebug`，不下载任何模型；② README 给出 HuggingFace 下载指引 + 支持 `.litertlm/.task`；③ App 内提供"扫描本地目录"发现模型；④ `.gitignore` 排除 `*.litertlm/*.task` |
| R6 | **内存 / GC**：4B 模型 + 图片字节 + 会话列表同时驻留；`Channel.UNLIMITED` 理论上可无限堆积 | OOM / 卡顿 | 中 | ① `AttachmentBytesReader.downscale` 限制 1MP；② 会话用 index/content 分离，列表页不读全文；③ 引擎单实例缓存（`DefaultEngineFactory`）；④ UNLIMITED channel 只在"UI 卡死"时才涨，chunk 极小，实测可接受；⑤ 参数面板限制 `maxTokens ≤ 32768`、`contextLength ≤ 131072` |
| R7 | **流式 UI 重组风暴**：每 chunk 触发一次状态更新，LazyColumn 可能全量重组 | 掉帧 | 中 | ① streaming 文本独立于 `messages`（§7.3）；② `key` + `contentType`；③ `@Immutable` 标注 UiState；④ 必要时在 VM 侧用 30~50ms 节流合并 chunk（保留全部文本，只是合并发布） |
| R8 | **NPU 后端兼容性**：需 `nativeLibraryDir`，且 `samplerConfig` 必须为 null；仅少数机型有 NPU | 初始化崩溃 | 中 | ① 已按简报在 `ensureConversation` 里判 NPU → `samplerConfig=null`；② UI 上 NPU 选项标注"实验性"并把采样滑块置灰；③ 初始化失败时自动回落 CPU **仅重试一次**并提示用户 |
| R9 | **机型兼容**：minSdk 31（Android 12），4B 模型对内存要求高（建议 ≥8GB RAM） | 低端机崩溃 | 中 | ① README 明确硬件建议；② 加载前用 `ActivityManager.MemoryInfo` 做一次粗判并提示；③ 提供 1B 级模型（Gemma 3 1B）作为低端默认 |
| R10 | **开源合规**：Apache-2.0；LiteRT-LM / Gemma 权重有各自条款；`material-icons-extended` 为 Apache-2.0 | 合规风险 | 低但必须做 | ① `LICENSE` = Apache-2.0；② README 列第三方依赖许可证清单（LiteRT-LM、Gemma  Terms of Use、OkHttp Apache-2.0、Kotlin/Compose Apache-2.0、material-icons Apache-2.0）；③ **App 内置不含任何模型权重**，模型由用户自行下载并遵守其许可 |
| R11 | **远程端点明文 / API Key 存储**：Key 存 DataStore 明文 | 安全 | 中 | ① 第一版接受（DataStore 属应用私有目录）；② README 明确标注"请勿在不可信设备上填写生产 Key"；③ 不引入 `EncryptedSharedPreferences`（会新增 `androidx.security` 依赖，违反"少依赖"）；留 TODO |
| R12 | **Gradle wrapper**：本地无 Gradle，无法自行生成 `gradle-wrapper.jar` | CI 无法用 `./gradlew` | 已缓解 | CI 用 `gradle/actions/setup-gradle@v6` + `gradle-version: '9.7.1'` 显式安装（不走 wrapper）；仓库内的 jar 取自 gradle/gradle 官方仓库 v9.7.1 tag（sha256 `7a9ce74c…64262c5d`，34 entries 含 GradleWrapperMain），仅供贡献者本地 `./gradlew` 使用。CI 侧保持 `validate-wrappers: false`（CI 不用 wrapper，校验只有风险没有收益）；**若将来 CI 改用 `./gradlew`，需把它翻回 true** |
| R13 | **`material-icons-extended:1.7.8` 与 Compose BOM 2026.02 混用** | 版本冲突警告 | 低 | icons 库只依赖 `compose.ui`，Gradle 取高版本自动对齐；若报 duplicate class，把 icons 版本改为不指定（由 BOM 管理）——**这属于"若报错再改"** |
| R14 | **SSE 解析健壮性**：各家后端分片/`[DONE]`/空行行为不一 | 解析失败 | 中 | ① 单行 `data:` 解析，非法 JSON 静默跳过；② 同时支持 `reasoning_content` 与 `reasoning`；③ `usage` 与 `finish_reason` 可能分帧到达，上层"空即忽略" |
| **R15** | **AGP 9 内置 Kotlin 导致插件冲突**：任何模块显式应用 `org.jetbrains.kotlin.android` 即构建失败 | 编译失败 | **已发生（已修）** | 证据见 §1.2.1 真实报错。规则：9 个模块 + 根工程一律不得声明该插件；`plugin.compose` / `plugin.serialization` 仍需显式声明。已由提交 6fb4108 移除 |
| **R16** | **Kotlin 插件版本与 AGP 的 KGP 运行时依赖不匹配**：AGP 9 对 KGP 有运行时依赖（AGP 9.0 = KGP 2.2.10，声明更低会被自动升级）；我们的 `kotlin-compose` / `kotlin-serialization` 仍 `version.ref = "kotlin"`(2.3.0)。若 AGP 9.3.2 的 KGP 高于 2.3.0，Gradle 会把 KGP 升上去而 compose 插件留在 2.3.0 → 版本不匹配 | 编译失败 | **高（下一个最可能踩的坑）** | ① 查 AGP 9.3.2 RN「兼容性」表的 **KGP 默认版本**；② 把 catalog 的 `kotlin` 改成该值（两插件同步）；③ 若要用**更高** Kotlin，按官方做法在**顶层** build 文件加 `buildscript { dependencies { classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:<版本>") } }`，**只改 catalog 无效**；④ 兜底：`gradle.properties` 设 `android.builtInKotlin=false` + `android.newDsl=false` 并加回 `kotlin.android`（AGP 10 会失效，仅临时） |
| **R18** | **`getDefaultProguardFile("proguard-android.txt")` 在 AGP 9 被禁**（`android.r8.proguardAndroidTxt.disallowed=true`，仅支持 `proguard-android-optimize.txt`） | 构建失败 | 中（仅当 app 模块用了它） | 检查 `app/build.gradle.kts` 的 `proguardFiles(...)`，把 `proguard-android.txt` 改成 `proguard-android-optimize.txt`；需要保留不优化行为则显式写 `-dontoptimize` |
| **R17** | **`android {}` 块内 DSL 尚未被 CI 验证**：报错停在插件应用阶段（line 12），`compileSdk` 块式 DSL、`buildFeatures { compose = true }`、`packaging { jniLibs }` 均未执行到 | 编译失败 | 中 | 下一次 CI 会一次性暴露。处置顺序：先 `compileSdk` → 再 `buildFeatures.compose` → 再 `packaging`。三者都可删可改，不影响 Kotlin 逻辑 |

### 10.1 已明确接受的取舍（写进 README 的"Known Limitations"）

1. LiteRT-LM 的 Conversation 不回放历史（不构造 `Message`），切换会话即重建会话。
2. 本地引擎的工具调用走文本协议（```json），不是原生 tool 通道。
3. `repetitionPenalty` 对本地引擎无效；`seed` 对本地引擎无效。
4. `contextLength` 对 LiteRT-LM 只是上层裁剪预算，不下发给引擎。
5. token 数为启发式估算（3.2 字符/token），不是真实 tokenizer 结果。
6. 网络搜索与图片理解工具为占位实现。
7. 真实背景模糊（RenderEffect）默认关闭，用程序化光斑近似。

---

## 11. 验收标准（CI 绿 = 通过）

- [ ] `gradle :app:assembleDebug` 在 GitHub Actions（JDK 21）一次通过
- [ ] 9 个模块全部参与编译，无 `include` 遗漏
- [ ] 无任何 KSP/Room/Hilt/Koin/Nav3/Retrofit/Coil 依赖出现在依赖树
- [ ] APK 体积不含任何模型权重（< 60MB）
- [ ] 冷启动不崩溃（无模型时不崩，给出"请先导入模型"引导）
- [ ] Chat / Models / Settings / Tools 四个页面可进入




