# LiquidAgent — 交付总览

> Android 端侧 Agent 开源项目 · 仓库 `Rickeal-Boss/Android-Agent` · 主理人 祁研深（CAM-P 专家团）
> 首次开荒日期：2026-09-18

## 一句话定位

在 Android 上本地部署 4B 级模型，并跑完整的 Agent 循环（思考 → 工具调用 → 观察 → 继续），
UI 采用 iOS 27 / iPadOS 27 的 Liquid Glass 视觉语言。

## 技术选型（关键决策与理由）

| 决策 | 结论 | 理由 |
|---|---|---|
| 端侧推理引擎 | **LiteRT-LM**（`com.google.ai.edge.litertlm:litertlm-android:0.11.0`） | Google 官方 gallery 主线已从 MediaPipe `tasks-genai` 迁移到 LiteRT-LM；API 面从 gallery 源码实证提取（Engine/Conversation/SamplerConfig/Backend/MessageCallback/思考通道）。0.17.1 虽最新但 API 漂移未知，锁定有实证的 0.11.0 |
| 版本矩阵 | Gradle 9.7.1 / AGP 9.3.2 / Kotlin 2.3.0 / JDK 21 / Compose BOM 2026.02.00 / compileSdk 36 / minSdk 31 | 取「gallery main 已验证」与「nowinandroid main 已验证」的交集 |
| 依赖策略 | 零注解处理器：无 KSP / Room / Hilt / Koin / Nav3 / Retrofit / Coil | 本地无 JDK/SDK 无法预演，CI 是唯一验证通道，依赖越少编译不确定性越低 |
| 持久化 | DataStore Preferences + JSON 文件（手写 JsonFileStore） | 替代 Room，规避 KSP |
| DI | 手写 `AppContainer` + `LocalAppContainer` | 替代 Hilt |
| UI | 自研 Liquid Glass 设计系统（`:core-design`，零项目依赖） | 用户点名的 `Kyant0/AndroidLiquidGlass` 走 JitPack 且 API 面无法核对，引入等于引入编译不确定性；自研可完全控制质感与降级 |
| 玻璃实现 | 程序化光斑伪模糊（默认）+ 可选真实 `RenderEffect` 背景模糊（API 31+） | 默认路径零风险，真实模糊按需开启且可一键摘除 |

## 架构（9 个扁平模块）

```
:app                     壳：Application / MainActivity / NavHost，全部汇聚
 ├─ :feature-chat        对话页 / 参数面板 / 多模态输入 / 工具轨迹
 ├─ :feature-models      模型库 / SAF 导入 / 后端选择 / 能力探测
 ├─ :feature-settings    设置 / 远程端点 CRUD / Agent 工具（子包 tools）
 ├─ :core-agent          Agent 循环、工具注册中心、内置工具、上下文压缩
 ├─ :core-data           DataStore + JSON 持久化 + AppContainer
 ├─ :core-engine         引擎契约 + LiteRT-LM 实现 + OpenAI 兼容实现（SSE）
 ├─ :core-model          纯领域模型 + 序列化 + 纯算法（无 Android / 无 Compose）
 └─ :core-design         Liquid Glass 设计系统（纯视觉，不依赖任何模块）
```

## 能力清单

- **本地推理**：`.litertlm` / `.task` 加载，CPU / GPU / NPU 后端，能力探测（含投机解码探测）
- **多模态输入**：文本 / 图片（PNG 字节）/ 音频；文本 token 必须排在最后（autoregressive 顺序要求）
- **思考模式**：`enable_thinking` 透传 + `message.channels["thought"]` 思维链通道，UI 可折叠
- **工具调用**：模型原生工具通道 + 文本协议（```json / `<tool_call>`）双兼容，最多 N 轮循环，超时与输出长度护栏
- **参数调节**：temperature / topP / topK / 重复惩罚 / seed / maxTokens / 上下文长度 / 系统提示词 / 思考模式 / 后端 / 流式开关 / Agent 轮次
- **远端引擎**：OpenAI 兼容（OpenAI / DeepSeek / Ollama / vLLM / SiliconFlow …）SSE 流式
- **NPU 降级**：LiteRT-LM 的 NPU 后端不支持自定义采样，自动传 `samplerConfig = null`
- **UI**：玻璃气泡、流式光标、思考折叠、工具卡片、参数面板、自适应导航（手机底部栏 / 平板侧边栏）

## CI

- `.github/workflows/build.yml`：push/PR 触发，`assembleDebug`，零密钥；预置 SDK 许可、Gradle 缓存、失败产物上传
- `.github/workflows/release.yml`：可选签名发布——四个签名属性（`signing.storeFile` / `storePassword` / `keyAlias` / `keyPassword`）缺任一即整体降级为 debug 包，**永不失败**
- **约束**：仓库当前**没有任何 Actions variables / secrets**；工作流文件本身需要带 `workflow` 权限的凭据才能推送

## 已知待办

1. CI 首次构建结果待确认，按日志修到绿灯（本地无 JDK/SDK，只能云端验证）
2. LiteRT-LM 0.11.0 → 0.17.1 升级评估（需先核对新版 API）
3. 真实背景模糊（`GlassConfig.enableBackdropBlur`）默认关闭，可在设置中开放开关
4. 模型下载器（当前仅支持 SAF 手动导入，未内置 HuggingFace 直下）
