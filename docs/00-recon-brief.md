# 侦察简报（主理人祁研深，2026-09-18）

> 本文件是团队共享的硬性事实基线。所有成员的方案/实现必须与之一致，不得自行发明版本或 API。

## 1. 目标

从零开荒一款 Android 端侧 Agent 开源项目（仓库 `Rickeal-Boss/Android-Agent`，默认分支 `main`，当前完全空仓），
定位：类 `google-ai-edge/gallery` 的本地模型部署 + 类 `deepseek-harness` / `Octop` 的 Agent 运行时，
面向 4B 级别端侧模型（Gemma 3n / Gemma 3 / Qwen 3 等），支持多模态输入输出、工具调用、思考模式、全参数调节。
UI 采用 iOS 27 / iPadOS 27「Liquid Glass」视觉语言。产物需推送到 GitHub 并由 Actions 云端构建通过。

## 2. 仓库与环境事实

- 仓库：`https://github.com/Rickeal-Boss/Android-Agent`，public，默认分支 `main`，`size = 0`（空仓）。
- 仓库 **没有** 任何 Actions variables（`GET /actions/variables` → `total_count: 0`）；secrets 无法用当前 PAT 枚举（403）。
  → **CI 不能依赖任何签名 secrets**，必须保证 `assembleDebug` 无条件可构建；release 签名只能做成「secrets 存在才启用」的可选步骤。
- GitHub 连接：GitHub MCP 连接器只读（push 类 403）；`api.github.com` 可达（PAT 可用，actions runs 读权限 200）。
- 推送：只能由主理人在会话末尾用 `git push`（PAT 内嵌 URL + 禁用 credential helper）执行，成员不得推送。

## 3. 端侧推理引擎（关键决策）

Google 官方 gallery 仓库（main 分支，2026）已经从 MediaPipe `tasks-genai` 迁移到 **LiteRT-LM**：

- 依赖坐标：`com.google.ai.edge.litertlm:litertlm-android`
- 已发布版本：0.8.0 … 0.17.1（latest 0.17.1，2026-09-16 发布）
- **本项目锁定 `0.11.0`**：因为我们能拿到的、经过 gallery main 分支验证的 API 用法就是这个版本，0.17.x 的 API 是否漂移未知，CI 无法本地预演，风险不可控。升级留 TODO。
- 注意：`com.google.mediapipe:tasks-genai` 与 `com.google.ai.edge:litertlm` 均不作为主引擎依赖。

### 3.1 LiteRT-LM 真实 API（摘录自 gallery `ui/llmchat/LlmChatModelHelper.kt`，可直接照抄用法）

```kotlin
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Capabilities
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ToolProvider

val engineConfig = EngineConfig(
    modelPath = modelPath,                 // 本地 .litertlm / .task 文件绝对路径
    backend = Backend.GPU() /* CPU()/GPU()/NPU(nativeLibraryDir = ctx.applicationInfo.nativeLibraryDir) */,
    visionBackend = Backend.GPU(),         // 需要图片输入时非空（Gemma 3n 要求 GPU）
    audioBackend = Backend.CPU(),          // 需要音频输入时非空（Gemma 3n 要求 CPU）
    maxNumTokens = maxTokens,
    cacheDir = context.getExternalFilesDir(null)?.absolutePath,
)
val engine = Engine(engineConfig)
engine.initialize()

val conversation: Conversation = engine.createConversation(
    ConversationConfig(
        samplerConfig = SamplerConfig(topK = topK, topP = topP.toDouble(), temperature = temperature.toDouble()),
        systemInstruction = systemInstruction,   // Contents?
        tools = tools,                           // List<ToolProvider>
        initialMessages = initialMessages,       // List<Message>
    )
)

val contents = mutableListOf<Content>()
contents.add(Content.ImageBytes(pngByteArray))  // 图片（PNG 字节）
contents.add(Content.AudioBytes(byteArray))     // 音频
contents.add(Content.Text(prompt))              // 文本必须放在最后

conversation.sendMessageAsync(
    Contents.of(contents),
    object : MessageCallback {
        override fun onMessage(message: Message) {
            val text = message.toString()
            val thinking = message.channels[THOUGHT_CHANNEL]   // THOUGHT_CHANNEL = "thought"（思考模式通道）
        }
        override fun onDone() {}
        override fun onError(throwable: Throwable) {}
    },
    mapOf<String, Any>("enable_thinking" to true),   // extraContext：思考模式开关
)

conversation.cancelProcess()   // 停止生成
conversation.close()
engine.close()
Capabilities(modelPath).use { it.hasSpeculativeDecodingSupport() }   // 探测模型能力
ExperimentalFlags.enableSpeculativeDecoding / enableConversationConstrainedDecoding  // @ExperimentalApi
```

其它事实：
- `Backend.NPU(...)` 时必须传 `samplerConfig = null`（NPU 后端不支持自定义采样）。
- `sendMessageAsync` 返回的思考文本通过 `message.channels["thought"]` 取；常规文本用 `message.toString()`。
- gallery 自身 minSdk = 31、targetSdk = 37、compileSdk = 37。

## 4. 版本矩阵（必须严格遵守，任何人不得自行升级）

采用「gallery main 已验证 + nowinandroid main 已验证」的交集：

| 项 | 版本 | 来源 |
|---|---|---|
| Gradle wrapper | 9.7.1 | nowinandroid main（gradle-wrapper.properties） |
| AGP | 9.3.2 | nowinandroid main |
| Kotlin | 2.3.0 | nowinandroid main（compose compiler 由 KGP 内置，无需单独声明） |
| JDK（CI） | 21（temurin） | nowinandroid CI |
| Compose BOM | 2026.02.00 | gallery main（与 litertlm 0.11.0 同一矩阵验证） |
| androidx.core-ktx | 1.15.0 | gallery |
| androidx.activity-compose | 1.10.1 | gallery |
| lifecycle-runtime-ktx | 2.8.7 | gallery |
| navigation-compose | 2.8.9 | gallery |
| kotlinx-serialization-json | 1.7.3 | gallery |
| material-icons-extended | 1.7.8 | gallery |
| datastore-preferences | 1.1.7 | gallery |
| work-runtime-ktx | 2.10.0 | gallery（可选） |
| okhttp | 4.12.0 | nowinandroid |

已知风险（若 CI 报错再降档，禁止提前自行改动）：
- Compose BOM 2026.02.00 × Kotlin 2.3.0 未在同一仓库验证过（gallery 用 Kotlin 2.2.21）。若报 Compose 编译器/运行时不匹配，
  优先把 Kotlin 降到 **2.2.21**（与 gallery 完全一致），再考虑降 BOM。
- AGP 9.x 的 `compileSdk` 用新块式 DSL：`compileSdk { version = release(36) }`。本项目 compileSdk=36、targetSdk=36、minSdk=31。

## 5. UI 决策

- 用户点名的 `Kyant0/AndroidLiquidGlass` 走 JitPack，且其 Compose API 面我们无法核对，引入即等于引入编译不确定性。
  → **自研 Liquid Glass 设计系统**（`core/design` 模块），用 Compose 自绘实现：背景模糊（API 31+ `RenderEffect`）、
  折射高光、内描边、噪声微纹理、弹性动效，低版本自动降级为半透明+渐变。README 中说明可平滑替换为 AndroidLiquidGlass。
- 视觉基线：iOS 27 / iPadOS 27 —— 大圆角连续曲面、半透明分层、玻璃材质控件、液态过渡动效、SF 风格排版层级。

## 6. 工程量约束（极其重要）

- 本地 **没有 JDK / Android SDK / Gradle**，无法本地编译验证；唯一验证通道是 GitHub Actions。
- 因此：依赖越少越好，**禁止** KSP / Room / Hilt / Dagger / Koin / Nav3 / 任何注解处理器。
  - 持久化：`DataStore Preferences` + `kotlinx.serialization` 写 JSON 文件（会话、模型清单）。
  - DI：手写 `AppContainer`（纯 Kotlin）。
  - 远程后端：OkHttp + 手写 SSE 解析 + kotlinx.serialization，不引 Retrofit。
  - 图片预览：`BitmapFactory` + Compose `ImageBitmap`，不引 Coil。
- 每个模块都必须能被 `:app:assembleDebug` 编译通过；任何「可能更好但没把握」的写法一律放弃，宁可朴素。

## 7. 交付目录

本地工程根目录：`D:\WBfil\2026-09-18-18-27-37\Android-Agent\`（Git 仓库根即此目录）。
包名前缀：`com.rickeal.agent`。应用名：**LiquidAgent**。
