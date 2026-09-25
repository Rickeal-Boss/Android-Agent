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
| UI | Liquid Glass 设计系统（`:core-design`，零项目依赖）：引擎原语**移植自** [`Kyant0/AndroidLiquidGlass`](https://github.com/Kyant0/AndroidLiquidGlass)（Apache-2.0，见根目录 NOTICE），材质分级/中文排版/业务组件为增量自研 | 直接依赖上游走 JitPack 且 API 面无法核对，引入等于引入编译不确定性；移植后自持可完全控制质感与降级 |
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
2. ~~真实背景模糊（`GlassConfig.enableBackdropBlur`）暂被摘除~~ —— **已恢复并默认开启**：
   正确用法是 Compose 原生的 `BlurEffect(radiusX, radiusY, TileMode)`（不是此前误用的
   `RenderEffect.createBlurEffect`），minSdk 31 = Android 12 官方保证可用。详见
   `core-design/.../GlassBackdrop.kt` 与 `LiquidGlassModifier.kt` 注释。
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

## 第二轮运行时修复（取消安全 / 资源口径）

代码审查之外，又通过「对照 Edge0 的显式生命周期管理」自查出并修复了一组问题：

| 问题 | 症状 | 修法 |
|---|---|---|
| **会话取消后不重建** | 用户点「停止」或流出错后，`cancelProcess()` 会留下半截 KV 状态；复用该 Conversation **不报错**，但后续每轮静默变傻 | `LiteRtLmEngine` 增加脏标记：只有 `onDone` 正常收尾才算健康，其余路径（取消 / onError / `stop()`）下次生成前强制重建会话 |
| `temperature=0` 语义 | LiteRT `SamplerConfig` 底层对 temperature 做除法，0 可能触发除零/NaN | 钳到 `≥ 0.01`（等价于贪心，但不会 NaN） |
| 用户消息重复入上下文 | 调用方常见「history + userInput」写法会导致用户消息出现两遍 | `AgentRunner` 按 message id 去重后再追加 |
| 远程图片原图直传 base64 | 手机原图 base64 后十几 MB → HTTP 413 / OOM | 统一降采样到最长边 1024px 再压 JPEG(85) |

### 「建议可用内存」的口径（已修正）

第一版用统一的 **1.5 × 权重体积** 估算是**错的**：GPU 后端除常驻权重外还要承担 OpenCL buffer
与可能的 fp16 权重副本，而 GPU 变体的**文件更小**（E2B-GPU 1.87GB < CPU 版 2.41GB）只是重新打包
省了磁盘空间，**不代表运行内存更小**。统一 1.5× 会让 GPU 变体低估约 20~27%。

现采用分后端口径：**(W × f_backend + KV@4k + O) × 1.25**，其中 `f_backend` = CPU 1.05 / GPU 1.25 / NPU 1.15。
另外加了一道**加载前内存闸门**：可用内存不足时提前拦下并给出建议，而不是让用户等几十秒后
撞上 native 层崩溃（表现为闪退）。

> 注意：不要用 Edge0 披露的「35b ≈2.9GB / 8b ≈1.0GB」反推系数 —— 那是 MoE + SSD 流式加载下的
> **峰值激活内存**，不含权重驻留，与我们的 dense 全量常驻口径完全不同。

### 明确不做的事

**不在 Android 上暴露本地 OpenAI 兼容 HTTP 端点**：前台服务 + 常驻通知 + 长连接 + 后台网络限制
的合规与实现成本，远高于收益；且 LiteRT-LM 在普通手机上跑 4B 模型时，本机 UI 才是主场景。

## 模型导入：面向小白的设计

端侧 App 最容易劝退新用户的地方不是推理，而是**第一步：怎么把模型弄进来**。
原始版本只支持「手动选择文件」，空状态文案甚至是
「点右下角导入 .litertlm / .task，或把文件放到内部 models 目录后扫描」——
这句话对开发者清楚，对小白等于什么都没说。

现在的第一屏逻辑（无任何术语）：

```
还没有模型
可以直接下载一个（约 1~4GB，建议连 Wi-Fi），也可以选择你已经下载好的文件。
  [ 一键获取模型（推荐） ]   ← 主路径：推荐模型对话框
  [ 我已有模型文件 ]         ← 次路径：系统文件选择器
```

推荐模型对话框遵循四条原则：

1. **替用户做决定**：推荐项置顶并标「新手推荐」，主推 MiniCPM5 2B（1.45GB，中文强、6GB 内存手机可跑），
   备选 Gemma 4 E2B·GPU。不把 8 个技术化名字丢给用户自己选。
2. **代价说在前面**：点击前就能看到体积与建议可用内存，并提示建议 Wi-Fi。
3. **下载前先查空间**：需要 `体积 × 1.2 + 200MB`，不足直接拦下——GB 级文件下到一半失败的代价太高。
4. **下完即可用**：下载完成后自动设为当前模型，并提示「现在可以去对话页开始聊天了」，
   不需要用户再手动选一次模型。

下载使用系统 `DownloadManager`（零新增依赖），自带后台下载、断点续传与通知栏进度；
落盘到应用专属 Download 目录后复制进内部 models 目录。
扫描目录也覆盖应用专属 Download 目录，导入万一中断也不会让几 GB 白下。

同时清理了 manifest 里 `READ_EXTERNAL_STORAGE` / `READ_MEDIA_*` 这几个**从未申请过**的权限
——全程走 SAF，声明了却不用会引来应用商店审核质询。

## 第三轮：能力补齐（不只是修 bug）

前两轮是修复缺陷。这一轮补的是「官方有、而我们完全没有」的东西，
其中两项直接关系到**上架合规**与**可排障性**。

### 上下文占用显示

端侧模型窗口只有 4K 量级，超出会被**静默压缩**。用户看到的只是「模型突然变傻、忘了前面说过的话」，
无从理解为什么。现在输入框上方会显示占用量，接近阈值时变色。

两个实现要点：

- 分母必须用 `InferenceConfig.contextLength`，**不能**用 `EngineConfig.maxNumTokens`
  ——后者是最大**输出** token（默认 1024），拿它算会显示「2.1K / 1K」这种荒谬数字。
- 警戒阈值复用 `AgentPolicy.compressThreshold`，不另写魔数——**变色应当精确对应压缩启动**。

### 应用内诊断日志

此前全工程**零日志**。而唯一自动化检查是云端编译，它证明不了行为。这轮修的缺陷全是静默的，
没有日志就完全看不到它们发生。

- 只记**决策点与异常**，正常路径不记（否则噪声淹没关键信息、冲掉缓冲）。
- 进程内环形缓冲（200 条），**刻意不进会话 JSON**——诊断工具不该让它诊断的对象承担风险。
- 但 **ERROR 级额外落盘**（上限 50 条，约 22KB）。因为端侧最常见的故障是崩溃，而崩溃会清空内存缓冲：
  **最需要日志的场景，恰好是日志必定不存在的场景。**
- 诊断页分「本次运行」与「上次崩溃前的记录」两个区，刻意不合并。
- **不记端点身份**（连 name 都不记）——name 为空时会回退成 baseUrl，URL 可能带凭据。
  脱敏在写之前完成，落盘内容比内存日志更需要脱敏。

### 首启引导与条款体系

此前完全没有（上架合规硬伤）。**Gemma 有独立授权条款**（授权主体是 Google），必须与应用 TOS 分开接受。

- 首启：引导（可跳过）→ 应用 TOS（硬闸门，拒绝则退出）→ Gemma 授权（可稍后）。
- Gemma 授权闸门落在**下载与加载两处**：只拦下载不够——用户可通过 SAF 导入或旧版本遗留拿到 Gemma 权重。
- 判据用「名字含 gemma」而**不是** `ModelDescriptor.family`：`ModelFamily` 原本缺 `GEMMA_4`，
  用 family 会静默放行 Gemma 4（漏拦是危险方向）。该枚举缺项已于本轮补上，但**判定仍用字符串**
  ——family 只覆盖已知启发式，用户导入的 `gemma-2-*` 或改名文件仍会落 `OTHER`。
- 条款正文是**显式占位**并标注「不具备法律效力」，只引用官方原文外链；应用 TOS 的 URL 留空**不编造**。
- 授权闸门**没有**「不接受但仍要使用」的入口——许可证不是估算值，这与内存/存储闸门刻意不同。

### 小模型提示词护栏

系统提示词加 3 行（中文）：不要编造工具名、调用工具时不要向用户解释、
若本意是直接回答要明确说「这是最终答案」而不要输出像工具调用的 JSON。

第三条原本写成「任务已完成」，但那是一个**合法停机口令**——4B 模型在压力下会用它提前收工，
用户拿到「看起来完成、实际没做完」的答案。改成描述输出形态（「这是最终答案」）而非宣告进度。

### NPU 设备门控

`DeviceCapability` 依据 `Build.SOC_MODEL` 的型号数字（≥ 8650，即骁龙 8 Gen 3）判断，
**只警告不禁用**——阈值是估计值，硬拦会误伤。内存系数取保守占位并在注释标注「不是校准值，待真机校准」。

### Gemma 4 家族识别

`ModelFamily` 原本缺 `GEMMA_4`，三条 Gemma 4 预设全落 `OTHER`，继承到错误能力位
（上下文 4096 而非 8192、仅 CPU、`image=false`）。已补枚举与判据，能力位**全部来自一手模型卡**
（依据链接写进代码注释），没有一项靠「系列惯例」推断。

`contextLength` 取 **8192 而非部署卡写的 32768**：预设的 `memBasis` 按 `KV@4096` 估算内存，
声明 32k 会让上层以为能开到 32k 而内存预算根本不是按这个算的——**那是 OOM，不是显示错误**。

---

## 本轮的一个系统性发现

**「用估算值做不可绕过的决策」是系统性错误模式，不是孤立缺陷。** 同一类数据（手写估算值）+ 一层余量，
在三处都做了硬判决：

| 闸门 | 后果 | 处置 |
|---|---|---|
| 校验（下载后） | 删掉用户完整文件，死循环永远装不上 | 永不删除，改由 DM 账本判定 |
| 内存（加载前） | 加载被拦 / 放行后崩溃 | 警告 + 可继续 |
| 存储（下载前） | 无法开始下载 | 警告 + 可继续 |

三处是**分别在不同场景发现的**，分散发现、同一根因，说明是模式而非巧合。

**原则：任何估算值只能用于提示，真正的决定留给用户；加固与出口必须成对落地。**
（缺少出口的加固本身就会变成新的误伤——这是存储闸门被发现的缘由。）
