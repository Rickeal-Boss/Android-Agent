# LiquidAgent

[![Build](https://github.com/Rickeal-Boss/Android-Agent/actions/workflows/build.yml/badge.svg)](https://github.com/Rickeal-Boss/Android-Agent/actions/workflows/build.yml)
[![License: Apache 2.0](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.3.0-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![AGP](https://img.shields.io/badge/AGP-9.3.2-3DDC84?logo=gradle&logoColor=white)](https://developer.android.com/build)
[![minSdk](https://img.shields.io/badge/minSdk-31-3DDC84?logo=android&logoColor=white)](https://developer.android.com)
[![Platform](https://img.shields.io/badge/Platform-Android-lightgrey.svg)](https://developer.android.com)

**端侧优先的 Android 原生 Agent。** 把 4B 级别的多模态模型装进手机，在本地完成理解、思考、工具调用与任务执行——不依赖云端，数据不出设备。

> 项目代号 **LiquidAgent**，包名前缀 `com.rickeal.agent`。
> 当前处于开荒阶段，API 与目录结构可能变动。

---

## 目录

- [为什么做这个项目](#为什么做这个项目)
- [特性](#特性)
- [技术栈](#技术栈)
- [快速开始](#快速开始)
- [模型获取与导入](#模型获取与导入)
- [工程结构](#工程结构)
- [CI / 发布](#ci--发布)
- [路线图](#路线图)
- [参与贡献](#参与贡献)
- [致谢](#致谢)
- [许可证](#许可证)

---

## 为什么做这个项目

现有的移动端 LLM 应用大致分成两类：一类是**模型演示壳**（能跑、能聊，但没有 Agent 能力），另一类是**云端 Agent**（能力强，但隐私和离线场景无从谈起）。

LiquidAgent 想补上中间那块：**在设备本地跑一个真正有 Agent 能力的模型**。它同时借鉴了两个方向：

- 本地模型部署与多模态输入 —— 对齐 Google 官方 [`google-ai-edge/gallery`](https://github.com/google-ai-edge/gallery) 的能力面；
- Agent 运行时（多轮、工具调用、思考模式、参数调节）—— 对齐 `deepseek-harness` / `Octop` 这类 harness 的思路。

UI 上没有沿用 Material 的默认观感，而是采用 iOS 27 / iPadOS 27 的 **Liquid Glass** 视觉语言：大圆角连续曲面、半透明分层、玻璃材质控件、液态过渡动效。

---

## 特性

| 类别 | 能力 | 代码状态 | 验证状态 |
|---|---|---|---|
| 🧠 **本地推理** | 端侧加载 `.litertlm` / `.task` 模型，CPU / GPU / NPU 后端可选 | ✅ 已落地 | CPU/GPU 可用；**NPU 未实测** |
| 👁 **多模态** | 文本 + 图片 + 音频输入（Gemma 3n 要求 vision=GPU、audio=CPU） | ✅ 已落地 | 图/音链路通；**VL+GPU 组合未实测** |
| 🔧 **工具调用** | 内置工具集，模型自主决定是否调用；审批闸门 + 能力档位 + 按需披露 | ✅ 已落地 | 走文本协议；原生 tool 通道未启用 |
| 💭 **思考模式** | `enable_thinking` 开关，独立渲染 `thought` 通道内容 | ✅ 已落地 | `thought` 通道已通 |
| 🎛 **参数调节** | topK / topP / temperature / maxTokens / system prompt 全可调 | ✅ 已落地 | — |
| 🌐 **远程后端** | ~~可选接入远程模型服务（OkHttp + SSE 流式）~~ 已移除（云端 API 整体删除，现为纯端侧） | ⛔ 已移除 | — |
| 💾 **会话管理** | 多会话持久化（DataStore + JSON），崩溃恢复 | ⚠️ 部分 | 持久化 + 恢复已落地；**导入导出未实现** |
| ✨ **Liquid Glass UI** | Compose 液态玻璃设计系统（基于 Kyant0/AndroidLiquidGlass 移植改造，见 [NOTICE](NOTICE)）：背景模糊、折射高光、内描边、噪声微纹理、弹性动效 | ✅ 已落地 | 底层可降级（API 31~32 / 无 RuntimeShader） |
| 🔌 **模型市场** | 模型清单管理、下载状态、能力探测（speculative decoding 等） | ✅ 已落地 | 13+ 官方预设 + 双镜像；直链下载依赖系统 DownloadManager |
| 🛑 **物理断路器** | run 级物理量熔断与诊断卡（Wave 30）：墙钟预算（3min 提醒 / 5min 终止）、工具失败连击、调用振荡检测、Token 软预算、热保护四档（降参数 / 轮间冷却 / 拒新 run / 释放引擎）、熔断诊断卡（尝试清单 / 卡点 / 固定建议） | ✅ 已落地 | 纯函数判据 JVM 单测过；**热档位 / 墙钟 / 振荡真机未实测** |
| 📒 **Token 账本** | run 级 token 账本（Wave 30/31）：发送侧估算（`sentTokens`）与引擎回报（`cumulativeIn` / `cumulativeOut`）双口径**并列、不换算不对账**；上下文占用条并列显示「估算≈ / 实测」 | ✅ 已落地 | 发送侧预估口径已接 UI（`ChatContextMeter`，Wave 31 流2）；**双口径一致性真机未实测** |

> 状态说明：**「代码状态」= 代码实际状态；「验证状态」= 真机验证程度，未标注项表示尚无真机数据**。代码状态分四态：
> `✅ 已落地`（代码与单测完备，且生产路径上有构造点与消费方，运行时会执行）、
> `🟡 已实现未接线`（代码与单测完备，但**生产路径上没有构造点 / 消费方，运行时不执行** —— 此类条目必须在同处写明「重启前提」，对齐 `SegmentedHistoryStore` 类头的三前提范式）、
> `⚠️ 部分`（功能只落地一部分）、
> `⛔ 已移除`（曾经存在、现已删除）。
> 本项目刻意区分"代码完备"与"真机验证过"——后者只有真机数据才能背书。实际进度见 [路线图](#路线图) 与各模块代码。

---

## 技术栈

### 版本矩阵（硬性，不得擅自升级）

| 项 | 版本 | 来源 |
|---|---|---|
| Gradle wrapper | `9.7.1` | nowinandroid main |
| Android Gradle Plugin | `9.3.2` | nowinandroid main |
| Kotlin | `2.3.0`（Compose 编译器由 KGP 内置，不单独声明） | nowinandroid main |
| JDK | `21`（Temurin） | nowinandroid CI |
| Compose BOM | `2026.02.00` | gallery main |
| LiteRT-LM | `com.google.ai.edge.litertlm:litertlm-android:0.17.1` | Wave 20 起（`4a887a2`，对 v0.17.1 tag 逐符号核验后升级） |

> JDK 口径消歧：CI 工具链 JDK 21，字节码目标 17（jvmTarget 17 是正常组合，不是版本冲突）。

### SDK 配置

| 项 | 值 |
|---|---|
| `compileSdk` | `36` |
| `targetSdk` | `36` |
| `minSdk` | `31`（Android 12） |

### 主要依赖

| 依赖 | 版本 | 用途 |
|---|---|---|
| `androidx.core:core-ktx` | `1.15.0` | 基础 KTX |
| `androidx.activity:activity-compose` | `1.10.1` | Compose 宿主 |
| `androidx.lifecycle:lifecycle-runtime-ktx` | `2.8.7` | 生命周期 |
| `androidx.navigation:navigation-compose` | `2.8.9` | 页面导航 |
| `org.jetbrains.kotlinx:kotlinx-serialization-json` | `1.7.3` | 会话 / 配置序列化 |
| `androidx.compose.material:material-icons-extended` | `1.7.8` | 图标 |
| `androidx.datastore:datastore-preferences` | `1.1.7` | 轻量偏好存储 |

### 明确**不**使用的东西

开荒阶段刻意保持依赖面最小，避免任何编译不确定性：

- ❌ 注解处理器：KSP / Kapt / Room / Hilt / Dagger / Koin
- ❌ 图片加载库（Coil 等）—— 用 `BitmapFactory` + Compose `ImageBitmap`
- ❌ 网络栈（Retrofit / OkHttp / SSE 等）—— 云端 API 已整体删除，现为**纯端侧零网络依赖**
- ❌ JitPack 第三方 UI 库 —— Liquid Glass 设计系统随源码内置（引擎原语移植自 Kyant0/AndroidLiquidGlass 并保留其版权头，见 [NOTICE](NOTICE)）

持久化用 **DataStore Preferences + kotlinx.serialization 写 JSON**，DI 用 **纯 Kotlin 手写容器**。

---

## 快速开始

### 前置条件

| 工具 | 版本 |
|---|---|
| JDK | **21**（推荐 [Temurin](https://adoptium.net/)） |
| Android SDK | `compileSdk 36`，AGP 会自动下载缺失组件 |
| Gradle | **用仓库自带的 wrapper**，不要用系统 gradle |

### 构建

```bash
git clone https://github.com/Rickeal-Boss/Android-Agent.git
cd Android-Agent

# 编译 debug 包
./gradlew :app:assembleDebug

# 产物
# app/build/outputs/apk/debug/app-debug.apk
```

安装到设备：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 没有本地 JDK / SDK？

完全可以直接开 PR —— GitHub Actions 的 **Build** 工作流就是唯一且权威的验证通道：

```bash
# 手动触发一次构建
gh workflow run build.yml
```

构建的 debug APK 会作为 artifact 保留 **30 天**，不需要密钥即可跑通。详见 [`docs/02-ci.md`](docs/02-ci.md)。

---

## 模型获取与导入

LiquidAgent **不内置、不分发任何模型权重**。`.litertlm` / `.task` / `.gguf` / `.bin` 等已在 `.gitignore` 中忽略，需要你自行下载后导入。

### 1. 在 Hugging Face 上找模型

推荐从 [`litert-community`](https://huggingface.co/litert-community) 组织下寻找已转换好的 LiteRT 产物，关键词组合：

- `litert-community/Gemma3-*` —— Gemma 3 系列
- `litert-community/gemma-3n-*` —— Gemma 3n（E2B / E4B，多模态）
- `litert-community/Qwen3-*` —— Qwen 3 系列

在仓库的 Files 面板里找 `.litertlm`（LiteRT-LM 新格式）或 `.task`（MediaPipe 旧格式）后缀的文件。优先选 `.litertlm`。

> ⚠️ 选型建议：4B 级别模型在手机上通常需要 **GPU 后端** 才有可用速度；Gemma 3n 的视觉分支要求 GPU、音频分支要求 CPU。选择前先确认设备内存（建议 ≥ 8 GB）。

### 2. 自行转换（可选）

如果你的目标模型还没有现成的 `.litertlm`，可以用 Google 的 LiteRT / LiteRT-LM 转换工具链把原始权重转成端侧格式。这条路径依赖 Python 工具链，步骤较多且版本敏感，具体命令请以其官方仓库 README 为准，转换完成后同样得到 `.litertlm` 文件。

> 本项目当前使用 `litertlm-android:0.17.1`（Wave 20 升版，对 v0.17.1 tag 源码逐字段核验兼容后才合入），**优先使用与该版本配套的转换产物**。升级引擎版本属于独立的技术决策，见 [`docs/00-recon-brief.md`](docs/00-recon-brief.md) 第 3 节。

### 3. 导入到设备

**方式 A：应用内导入（推荐）**

1. 把 `.litertlm` 文件传到手机的 `Download` 目录：
   ```bash
   adb push gemma-3n-e2b.litertlm /sdcard/Download/
   ```
2. 打开 LiquidAgent → 模型管理页 → 「导入本地模型」。
   （debug 包的 applicationId 带 `.debug` 后缀：`com.rickeal.agent.debug`，
   与 release 包数据目录相互独立 —— adb 直接放到应用外部目录时路径不同，见方式 B。）
3. 用系统文件选择器选中该文件，应用会把它复制到应用私有目录并记录进模型清单。
4. 首次加载会较慢（权重 mmap + 后端初始化），后续走缓存。

**方式 B：adb 直接放到应用外部目录**

```bash
adb push gemma-3n-e2b.litertlm /sdcard/Android/data/com.rickeal.agent/files/
```

> debug 包路径为 `/sdcard/Android/data/com.rickeal.agent.debug/files/`（applicationId 带 `.debug` 后缀）。

对应 `context.getExternalFilesDir(null)`（即 `EngineConfig.cacheDir`）。注意 Android 11+ 的作用域存储限制，此路径在部分设备上可能无法直接 `adb push`，此时请用方式 A。

---

## 工程结构

> 模块划分以 [`docs/01-architecture.md`](docs/01-architecture.md) 为准（本表为其摘要）。
> 采用**扁平模块命名**：Gradle project path 与目录名字面一致（`:core-model` ↔ 根目录 `core-model/`），
> 避免嵌套命名在无法本地编译的环境下产生难以发现的配置错误。

```
Android-Agent/
├── app/                          # 应用壳：Application / MainActivity / NavHost / DI 组装
│   └── proguard-rules.pro        # R8 规则（保留 litertlm / serialization / Compose）
├── core-model/                   # 纯领域模型 + 序列化 + 纯算法（无 Android / Compose 依赖）
├── core-engine/                  # 引擎抽象 + LiteRT-LM 实现 + 能力探测
├── core-agent/                   # Agent 循环、工具注册中心、内置工具、上下文压缩
├── core-data/                    # DataStore + JSON 持久化 + 仓库 + AppContainer + CompositionLocal
├── core-design/                  # Liquid Glass 设计系统（tokens / 颜色 / 动效 / 组件）
├── feature-chat/                 # 对话页 + 参数面板 + 多模态输入
├── feature-models/               # 模型库 / 导入 / 加载 / 后端选择 / 能力探测
├── feature-settings/             # 设置页 + Agent 工具页（子包 tools）+ 记忆管理页
├── docs/
│   ├── 00-recon-brief.md         # 侦察简报（版本矩阵与硬约束的事实基线）
│   ├── 01-architecture.md        # 架构方案与代码级契约
│   └── 02-ci.md                  # CI 设计与排障手册
└── .github/
    ├── workflows/build.yml       # 主构建（零密钥）
    ├── workflows/release.yml     # 可选发布（密钥存在才签名）
    └── ci-gradle.properties      # CI 专用 Gradle 覆盖
```

### 模块一览（9 个）

| 模块 | namespace | 职责 | 依赖 |
|---|---|---|---|
| `:app` | `com.rickeal.agent` | 应用壳：Application / MainActivity / NavHost / DI 组装 | 全部 |
| `:core-model` | `com.rickeal.agent.core.model` | **纯领域模型 + 序列化 + 纯算法**，出度 0 | 无 |
| `:core-engine` | `com.rickeal.agent.core.engine` | 引擎抽象 + LiteRT-LM 本地实现 + 能力探测 + 多模态附件读取 + 加载编排 | 2 |
| `:core-agent` | `com.rickeal.agent.core.agent` | Agent 循环（思考 → 工具调用 → 观察 → 继续）、工具注册中心、上下文压缩、断路器 / Token 账本 / 计划 / 记忆 / 子代理 | 2,3 |
| `:core-data` | `com.rickeal.agent.core.data` | DataStore + JSON 文件持久化 + 仓库 + **AppContainer + CompositionLocal** + 通知 / 性能采样 / 热档位治理 | 2,3,4 |
| `:core-design` | `com.rickeal.agent.core.design` | Liquid Glass 设计系统（tokens / 颜色 / 动效 / 组件）+ 窗口尺寸自适应，**纯视觉、出度 0** | 无 |
| `:feature-chat` | `com.rickeal.agent.feature.chat` | 对话页 + 参数面板 + 多模态输入 | 2,3,4,5,6 |
| `:feature-models` | `com.rickeal.agent.feature.models` | 模型库 / 导入 / 加载 / 后端选择 / 能力探测 | 2,3,4,5,6 |
| `:feature-settings` | `com.rickeal.agent.feature.settings` | 设置页 + **Agent 工具页（子包 `tools`）** + 记忆管理页 | 2,3,4,5,6 |

> 「依赖」列数字 = 本表行序：1 `:app`、2 `:core-model`、3 `:core-engine`、4 `:core-agent`、5 `:core-data`、6 `:core-design`。

依赖无环：`core-model` / `core-design` 出度 0 → `core-engine` → `core-agent` → `core-data` → 三个 feature → `:app` 汇合。
（注意方向：`core-data` **依赖** `core-agent`，而不是反过来 —— 内核不得反向依赖持久化层，见 `scripts/arch-guard.sh` 第 10 条。）

> 设计系统刻意**不依赖**领域模型：一旦依赖，`ModelDescriptor` 的每次字段变更都会触发全量 UI 重编译，
> 且 `@Preview` 就必须构造领域对象。需要展示模型信息的卡片在 feature 层组装。

---

## CI / 发布

| 工作流 | 触发 | 产物 |
|---|---|---|
| [`build.yml`](.github/workflows/build.yml) | push `main` / `UI` / `harness` / `harness-improve` / PR → `main`·`UI`·`harness` / 手动 | debug APK（artifact，保留 30 天）；失败时上传 `**/build/reports` |
| [`release.yml`](.github/workflows/release.yml) | push `UI` / `harness` / `harness-improve` / tag `v*` / 手动 | debug APK（保底）+ 可选签名 release APK / AAB + GitHub Release |

> **分支口径**：`harness-improve` 与 `harness` 同规格 —— `release.yml` 在两条分支上都出
> 「仓库密钥签名正式包 + debug 包」双产物（`push.branches` 同时列了二者）。PR 不触发
> `release.yml`（PR 门禁走 `build.yml` 的零密钥快速轨）。
>
> **纯文档改动**：`build.yml` 的 `on.push` / `on.pull_request` 已加 `paths-ignore`
> （`docs/**`、`**/*.md`、`.github/ISSUE_TEMPLATE/**`），纯文档提交不再触发构建；
> `release.yml` 本就用 `paths:` 白名单，无需再改。

**设计要点：**

- **零密钥**：`assembleDebug` 不依赖任何 secret / variable，新 fork 的仓库开箱即绿。
- **可选签名**：`SIGNING_KEYSTORE_BASE64` 等 4 个 secrets 缺失时，release 步骤优雅跳过，只产出 debug 包，流水线不会失败。
- **自愈**：自动探测并接受 Android SDK 许可、缓存 Gradle 依赖、失败时输出可读的诊断日志。

完整说明与排障手册见 [`docs/02-ci.md`](docs/02-ci.md)。

---

## 路线图

- [x] 仓库开荒：CI 体系、版本矩阵、文档基线
- [x] **M1** — 工程骨架：`app` + `core:design` + `core:model`，Liquid Glass 基础组件
- [x] **M2** — 引擎接入：`core-engine` 打通 LiteRT-LM，本地文本推理跑通
- [x] **M3** — 会话体验：`feature-chat` 流式输出 + 会话持久化 + 参数调节
- [ ] **M4** — 多模态：图片 / 音频输入，GPU / NPU 后端切换
- [x] **M5** — Agent 能力：思考模式、工具调用、Agent 循环编排
- [x] **M6** — 模型市场：导入、能力探测、下载管理
- [x] **M7** — ~~远程后端：OkHttp + SSE，与本地引擎统一切换~~ 已移除（云端 API 整体删除，现为纯端侧）
- [ ] **M8** — 打磨：动效、无障碍、性能、发布签名
- [ ] **M9** — **Harness 升级**（`harness` 分支）—— `harness` 分支自 2026-09-26 起冻结，活跃开发在 `harness-improve`：移植 ZCode（Journal/Actor/typed-ask）
  与 Octop（工具审批/长期记忆/委派）的核心机制 —— 蓝图见
  [`docs/11-harness-blueprint.md`](docs/11-harness-blueprint.md)
  - [x] Wave 1：Journal、参数 Schema 校验、审批闸门、ask_actor 子代理、长期记忆
  - [x] Wave 2：崩溃恢复接线、计划机制（plan_set/plan_update + 时间线）、真审批 UI、
    Actor 会话持久化、结算语义对齐（`Interrupted` — 判据是「journal 无 `settled` 行」，不主动写入）
  - [x] ~~🟡 ProviderStop 写入点~~ **已裁定删除（Wave 32）**：该分支的语义前提
    （「REMOTE+EngineException → journal ProviderStop」）随远程供应商通道整体移除而失效，
    接线等于给架构上不存在的分支写死代码，还会与 `BreakerTripped` / 引擎异常路径制造第二种口径；
    且全仓零生产写入点、无 `valueOf` 反解析 ⇒ **删除枚举值**（零持久化兼容风险），而非接线。
    详见下文「挂账台账」节的「已裁定（不再是挂账）」小节。
  - [ ] Wave 3：历史版本化、定时任务、检索记忆、人格系统、插件化装载

> **Wave 24–39 实况（2026-09-30 同步）**：Wave 3 的大项**尚未开工**，实际推进的是「Harness 加固 + 治理 + 验收」这条线，逐波细节见
> [`docs/handoff-*.md`](docs/)（最新在前）。当前分支 `harness-improve`，功能 tip `bb149f5`（CI 双绿）。
>
> - **加固**：W24 角色通道根治（回显主根因）→ W26 Operit 侦察（**许可证不兼容 ⇒ 零代码搬运**）→ W27 渐进式披露 → W28 KV 预算 → W29 A1 拆分 → W30 断路器 → W31 接线收口 → W33 preface 第三态闸门 + 沙箱 → W34 原生工具通道 + 记忆 pull 化
> - **治理**：W32 lint 门禁翻转 → W35 外部报告批处理 → W36/W37/W38 挂账清零 + **lint baseline 83 → 35 → 4** + 记忆存储契约 + 守卫网 15 项 / selftest 21 例
> - **验收（当前）**：**Wave 39 —— 验收取证通道**。修的是「验收跑得起来但**拿不到判据**」：诊断日志原本只在内存环形缓冲、落盘 sink 只转 ERROR、全仓不写 logcat、诊断页不能导出 ⇒ 真机跑完还是「没抓到」。本波给 `AgentLogStore` 的 sink 并入 **logcat 出口**（TAG `LiquidAgentDiag`，全级别）、诊断页加**「复制全部」**、合并跨波验收清单到 [`docs/10-device-acceptance.md`](docs/10-device-acceptance.md) §11。
> - ⚠️ **真机验收积压 ≈32 条、至今零回收** —— 这是当前最大风险敞口；验收清单与取证命令见 §11。

---

## 挂账台账（🟡 已实现未接线）

「代码状态」四态里的 🟡 条目集中记在这里（不再散落在路线图里），每条必须写明**重启前提**
（对齐 `SegmentedHistoryStore` 类头的三前提范式）：

| 条目 | 现状与挂账理由 | 重启前提 |
|---|---|---|
| **完整 i18n（含 RTL）** | `app/src/main/res/values/strings.xml` 只有 1 条串（`app_name`），Composable 里 ~145 处中文硬编码 ⇒ RTL 布局从未被验证、也无从验证。Wave 32 已撤下 `AndroidManifest.xml` 的 `android:supportsRtl="true"` —— 先不声明未验证过的能力 | ① 硬编码中文串抽到 `strings.xml`；② 补 `values-ldrtl` / 布局镜像的真机或预览验证；③ 验证通过后才恢复 `supportsRtl` 声明 |
| **`termsVersion`（法务条款版本化）** ⚠️ **时序风险** | `SettingsRepository` 的 `is_tos_accepted` / `is_gemma_terms_accepted` 都是**无版本 boolean**，只能表达「同意过 / 没同意过」，表达不了「同意的是**哪一版**」。⇒ 一旦替换法务文本，**当天所有老用户**都会命中 `true` 而被视为「已同意新条款」，首启门禁与法律页开关被直接跳过（**未同意却被视为已同意**，合规事故），且事后无法反推用户当年同意的是哪一版。Wave 39 只把债务固化为注释（`SettingsRepository.kt` 的 `IS_TOS_ACCEPTED` 上方），**未改行为** —— 实现涉及产品/法务决策（老用户的 `true` 算「已同意第 1 版」还是「未同意任何版本」） | ✅ **落地顺序是硬约束**：`termsVersion` **必须先于任何法务文本替换落地**，不能反序（反序则老用户同意状态不可区分、不可补征）。行为实现需先裁定上述产品/法务问题 |
| **J4：`SandboxFilesViewModel` 直构造白名单唯一（工作区覆盖层）** | `LiquidAgentApp.kt:378` 是全仓**唯一**绕过 `viewModelFactory` / ViewModelStore 的 VM 实例化点（「首次打开才创建 + 旋转即关」刻意取舍；VM 内目前只有自终止任务，现状无实害，但绕过 store ⇒ 离场时靠 `DisposableEffect` 手工补偿 cancel）。唯一性已由 `scripts/arch-guard.sh` **第 17 项冻结**（第二处直构造即红）；迁移正规 viewModel 路径 = **行为变更项**（改变重开覆盖层的重扫语义 /「旋转即关」取舍） | 触发条件：① 给该 VM 加轮询 / 常驻监听（旋转会从「无实害」变真泄漏）；② 需跨开关保留面板状态。实施时必须一并处理重开覆盖层的重扫语义，并过真机验证；迁移落地后 `LiquidAgentApp.kt` 的补偿清理块随删、守卫白名单同步清空 |

### 已裁定（不再是挂账）

- **`TerminationReason.ProviderStop`**（结算语义的「模型侧确定性故障」分支）—— **Wave 32 裁定删除枚举值，不接线**。
  理由：语义前提（「REMOTE+EngineException → journal ProviderStop」）随远程供应商通道整体移除而失效；
  全仓零生产写入点、无 `valueOf` 反解析（journal 只写 `termination.name` 字符串）⇒ 删除零持久化兼容风险。
  模型侧确定性故障现统一归入 `BreakerTripped` / 引擎异常路径，不再保留第二种口径。

- **THIN `backgroundAlpha = 0.21f`** —— **Wave 39 裁定销账，不再跟进**（此前跨 6 波未裁决）。
  所谓「与 KDoc 规范 ≤0.15 冲突」的**前提已不存在**：`≤0.15` 只存活于 2026-09-26 的两份 handoff
  （`docs/handoff-20260926-211413.md` / `handoff-20260926-234500.md`），**现役源码里零命中**；
  `core-design/.../GlassMaterial.kt` 的 KDoc 早已把 `0.21f` 写成**有意值**（Wave 9 真机反馈「卡片更实」后整体加厚一档，
  并顺带捋直了 Thin(0.18) > Regular(0.16) 的历史倒挂）。⇒ 它不是待裁决的临时值，改它等于改回 Wave 9 已否掉的方向。

- **三项「挂账蒸发」实为从未存在** —— **Wave 39 核实销账**：`SubagentProgress` 事件、
  `ConversationRepository` 增量写、`formatVersion` 三者**全仓 grep 零命中**（不是「做过又丢了」，是**从未实现过**，
  此前作为「蒸发项」挂在台账里属记录失真）。

- **`build.yml` 的 lint `continue-on-error`（G1③）** —— **Wave 39 确认已是有意保留并显式标注**
  （workflow 注释已写明观察期与摘除前提），该项不再是待确认挂账。

### `AgentRequest` 可空字段的接线归属（`@wire-owner` 三态）

`AgentRequest` 的每个 `= null` 字段都在字段上方一行用 `// @wire-owner: <值>` **自申报**接线归属，
`scripts/arch-guard.sh` 第 13 条据此在**对应源集**校验赋值点（旧实现是全局 grep，对
`deadlineNanos` 只命中内核透传点，属「碰巧正确」）：

| 标记 | 含义 | 守卫校验 |
|---|---|---|
| `host` | 由宿主装配（`app/` `feature-*/` `core-data/`） | 宿主源集必须有 `<字段> = ` 赋值点 |
| `internal` | 仅内核内部透传 / 子 run 自装配（如 `deadlineNanos` 由父 run 传给子 run） | 只在 `core-agent/` 内查赋值点 |
| `pending:理由串` | 已挂账、尚未接线 | **理由串必须能在本节 grep 到** —— 标记与挂账双向交叉，一边清掉另一边立刻判红 |

当前 11 个可空字段：9 个 `host`、2 个 `internal`（`toolNames` / `deadlineNanos`，宿主侧刻意不传）、
0 个 `pending`。新增可空字段**必须**先申报归属（无标记即判红），字段删除时标记也要一并删
（标记总数 != 可空字段总数即判红，防僵尸标记）。

---

## 参与贡献

欢迎开 issue 或 PR。开始之前请读：

- [`CONTRIBUTING.md`](CONTRIBUTING.md) —— 环境、约定、提交规范
- [`docs/00-recon-brief.md`](docs/00-recon-brief.md) —— 版本矩阵与硬约束（**不得擅自升级依赖**）

几条最容易踩的线：

1. 不引入任何注解处理器（KSP / Kapt / Room / Hilt / Koin）。
2. 不擅自改动版本矩阵；确需变更请在 PR 中说明理由并附 CI 结果。
3. 每个模块都必须能被 `:app:assembleDebug` 编译通过。宁可朴素，不要没把握的写法。
4. 不要提交模型权重或密钥，相关后缀已在 `.gitignore` 中忽略。

---

## 致谢

- [`google-ai-edge/gallery`](https://github.com/google-ai-edge/gallery) —— 端侧模型部署的能力面参考与 LiteRT-LM 用法基线。
- [Google LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM)（`com.google.ai.edge.litertlm`）—— 本项目采用的核心推理引擎。
- [`Kyant0/AndroidLiquidGlass`](https://github.com/Kyant0/AndroidLiquidGlass) —— `core/design/liquid/` 的**移植与深度改造基底**（Apache-2.0，见根目录 [`NOTICE`](NOTICE)）。
  引擎原语（backdrop 录制 / lens 折射 / InteractiveHighlight / DampedDragAnimation 等）移植自该库并保留其版权头；材质分级体系、中文排版适配与业务组件在其上增量实现。此前"自研"的表述不准确，已更正。
- [`android/nowinandroid`](https://github.com/android/nowinandroid) —— 构建配置与 CI 实践参考。
- 模型提供方：Google（Gemma 3n / Gemma 3）、Qwen 团队（Qwen 3），以及 Hugging Face 上做端侧转换的 [`litert-community`](https://huggingface.co/litert-community)。

---

## 许可证

本项目基于 [Apache License 2.0](LICENSE) 发布。

```
Copyright 2026 Rickeal-Boss

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```

模型权重遵循各自发布方的许可条款，与本仓库的许可证相互独立。
