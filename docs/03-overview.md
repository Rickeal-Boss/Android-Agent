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
- **模型获取**：SAF 手动导入、目录扫描、**URL 直链下载**（系统 DownloadManager，后台 + 断点续传 + 通知栏进度，完成后自动登记入库）
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

## CI 实战踩坑记录（本次开荒真实遇到，按出现顺序）

| # | 报错 | 根因 | 修法 |
|---|---|---|---|
| 1 | `Failed to apply plugin 'org.jetbrains.kotlin.android'` | **AGP 9.0 起内置 Kotlin**，禁止再显式应用该插件 | 9 个模块 + 根工程全部移除；`kotlin-compose` / `kotlin-serialization` 保留 |
| 2 | `Unresolved reference 'AlignmentEnd'` | 笔误 | `Arrangement.spacedBy(10.dp, Alignment.End)` |
| 3 | `Error parsing AndroidManifest.xml` | XML 注释里出现连续 `--`（XML 规范禁止） | 清理分隔线注释 |
| 4 | `Unresolved reference 'matchParentSize'` | 该 API 的导入包在本项目 Compose 版本下无法确认 | 改用语义等价的 `fillMaxSize()` |
| 5 | `Assignment type mismatch: android.graphics.RenderEffect vs androidx.compose.ui.graphics.RenderEffect` | `graphicsLayer` 要的是 Compose 包装类型 | 平台 `RenderEffect.createBlurEffect(...).asComposeRenderEffect()` |
| 6 | `Unresolved reference 'createBlurEffect'` | Compose 的 `RenderEffect` 没有该工厂方法 | 该 P2 能力（默认关闭）先摘除，程序化光斑模糊效果不缺失 |
| 7 | `Unresolved reference 'background' / 'pointerInput' / 'KeyboardOptions' / 'CornerRadius'` | 扩展函数与类缺导入 / 包名搬家 | 写脚本按「用到但没导入」自动补齐；`KeyboardOptions` 现属 `androidx.compose.foundation.text`，`CornerRadius` 属 `androidx.compose.ui.geometry` |
| 8 | `Cannot access 'RowColumnParentData?.weight'` | `weight` / `align` 是 Row/Column/BoxScope 的**成员**，不能 import | 删掉误加的导入 |
| 9 | `Unresolved reference 'useThreePane'` | UI 用了设计系统未定义的属性 | 在 `WindowSizeClass` 上补 `useTwoPane` / `useThreePane` |

**结论**：AGP 9 + Compose BOM 2026.02 的**工具链与依赖解析本身没有问题**，全部失败都是代码层面对新版本 API 变化的适配。

## CI 状态（已全绿）

HEAD 提交 `d6e6d02` 的 `:app:assembleDebug` 构建**成功**，产物为 `liquidagent-debug-*` APK（约 39.7 MB），
由 Actions 保留 30 天。已实测通过的组合：ubuntu-24.04 / JDK 21 / Gradle 9.7.1 / AGP 9.3.2 / Kotlin（AGP 9 内置）/ compileSdk 36 / Compose BOM 2026.02.00 / litertlm 0.11.0。

## 已知待办

1. LiteRT-LM 0.11.0 → 0.17.1 升级评估（需先核对新版 API 面）
2. 真实背景模糊（`GlassConfig.enableBackdropBlur`）暂被摘除，恢复方式见 `LiquidGlassModifier.kt` 注释
3. 内置 Hugging Face 模型目录（当前需用户自己粘贴直链，未做仓库内模型索引）
4. 端到端真机验证：本地 4B 模型加载、多模态输入、工具调用循环尚未在真机跑过（云端只保证可编译可打包）

## 代码审查后的运行时修复（CI 绿 ≠ 能用）

质量审查（`docs/04-code-review.md`）指出 4 个 **P0 运行时缺陷**——它们不抛异常、不崩溃，
而是「静默给出错误结果」，因此 CI 永远抓不到。现均已修复：

| 编号 | 症状 | 根因 | 修法 |
|---|---|---|---|
| P0-1 | 远端工具调用无意义 | `buildContent()` 只读 `text`，而 TOOL 消息的载荷在 `toolResults` 里 → 发出 `content:""` | TOOL 角色特判，取 `output ?: errorMessage` |
| P0-2 | 本地 Agent 循环退化为单轮瞎猜 | `buildContents()` 只取最后一条 USER 消息，系统提示词与工具结果全丢 | 改为按 message.id 做**发送水印**，增量装配 system/user/model/tool 全量上下文 |
| P0-3 | 每条回答出现两个气泡、会话文件写两份 | `MessageCommitted` 与 `Finished` 两条路径都落库 | `MessageCommitted` 只渲染（去重），落库统一交给 `Finished` |
| P0-4 | 多模态附件 100% 丢失（用户看得见图，模型看不到） | SAF 返回 `content://`，引擎侧按文件路径 `File(uri)` 读取必然失败 | 附件**选中即落盘**到 `filesDir/attachments`，领域模型里存真实路径 |

另修 P1-2：Agent 轮次耗尽时原本会把「上一轮带工具 JSON 的原始输出」当答案，
现改为回退到最后一轮可见文本并剥离工具协议片段。

## 借鉴 Edge0（https://github.com/Edge0-AI/Edge0）

Edge0 是 Python/MLX 的端侧流式 MoE 推理框架（Apple Silicon），**推理内核不可直接迁移**，
但两处工程实践已吸收：

1. **用 CI grep 强制依赖方向**（它用这招保证 MLX 不外溢）→ 我们新增
   `scripts/arch-guard.sh` + 工作流里的 `Architecture guard` 步骤，强制：
   `litertlm` 只出现在 `:core-engine`、`:core-design` 零业务依赖、`:core-model` 无框架依赖、禁依赖为零。
2. **量化披露每个模型档位的资源占用**（它给出 35b ≈2.9GB / 8b ≈1.0GB 峰值）→
   我们的 `ModelPresets` 增加「建议可用内存」估算（1.5× 权重体积），
   让用户在下载 GB 级文件**之前**就能判断自己的机器跑不跑得动。
