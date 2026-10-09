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
| 📒 **Token 账本** | run 级 token 账本（Wave 30/31）：发送侧估算（`sentTokens`）与**本仓自算口径**（`cumulativeIn` / `cumulativeOut`）双口径**并列、不换算不对账**；上下文占用条并列显示「发送前≈ / 引擎回报≈」 | ✅ 已落地 | 发送侧预估口径已接 UI（`ChatContextMeter`，Wave 31 流2）；**双口径一致性真机未实测**。⚠️ `cumulativeIn`/`cumulativeOut` 是**本仓自算**（prompt 用 `TokenEstimator` 估算 + completion 为**内容 chunk 帧计数**，非 token）——**引擎从不回报 usage** ⇒ 该对照观测力弱于「估算 vs 真实」 |

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
- ❌ 网络栈（Retrofit / OkHttp / SSE 等）—— 云端 API 已整体删除。**精确口径 = 「推理纯端侧」**：无云端 API、无自有网络栈、推理零数据外发；模型**下载**经系统 `DownloadManager`（其 `enqueue()` 要求调用方持有 `INTERNET`，见 `AndroidManifest.xml` 注释），故 manifest 声明 `INTERNET`，**用途仅限下载、下载源全部 https**（由 arch-guard #5/#6 继续强制「无网络栈 / 无进程执行」）
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

**本地构建是第一验证通道**（2026-10-01 起本机已有 JDK 21 + SDK，全落在工作区 `_j2env/`，经 `_ci-tools/localbuild.sh` 封装）。无本地环境时走 CI：GitHub Actions 的 **Build** 工作流是第二验证通道：

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
| [`build.yml`](.github/workflows/build.yml) | push `main` / `UI` / `harness` / `harness-improve` / `improve` / PR → `main`·`UI`·`harness` / 手动 | debug APK（artifact，保留 30 天）；失败时上传 `**/build/reports` |
| [`release.yml`](.github/workflows/release.yml) | push `UI` / `harness` / `harness-improve` / `improve` / tag `v*` / 手动 | debug APK（保底）+ 可选签名 release APK / AAB + GitHub Release |

> **分支口径**：`improve` / `harness-improve` 与 `harness` 同规格 —— `release.yml` 在三条分支上都出
> 「仓库密钥签名正式包 + debug 包」双产物（`push.branches` 同时列了三者）。PR 不触发
> `release.yml`（PR 门禁走 `build.yml` 的零密钥快速轨）。
> `improve` 是自 W48 起的主开发分支（云端自 `harness-improve` 的同一提交 `1a0b45a` 新建，W48 接入 CI 触发）。
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
- [ ] **M9** — **Harness 升级**（`harness` 分支）—— `harness` 分支自 2026-09-26 起冻结；`harness-improve` 为过渡分支，**自 W48 起活跃开发迁至 `improve`**（云端自 `harness-improve` 的同一提交 `1a0b45a` 新建）：移植 ZCode（Journal/Actor/typed-ask）
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

> **Wave 24–49 实况（2026-10-05 同步）**：Wave 3 的大项**尚未开工**，实际推进的是「Harness 加固 + 治理 + 验收」这条线，逐波细节见
> [`docs/handoff-*.md`](docs/)（最新在前）。**当前分支 `improve`** —— 自 W48 起为本仓主开发分支，云端自 `harness-improve` 的同一提交 `1a0b45a` 新建（`harness-improve` 冻结保留）。
>
> - **加固**：W24 角色通道根治（回显主根因）→ W26 Operit 侦察（**许可证不兼容 ⇒ 零代码搬运**）→ W27 渐进式披露 → W28 KV 预算 → W29 A1 拆分 → W30 断路器 → W31 接线收口 → W33 preface 第三态闸门 + 沙箱 → W34 原生工具通道 + 记忆 pull 化 → W42 策略收敛 + 守卫拆分
> - **治理**：W32 lint 门禁翻转 → W35 外部报告批处理 → W36/W37/W38 挂账清零 + **lint baseline 83 → 35 → 4** + 记忆存储契约 + 守卫网 15 项 / selftest 21 例
> - **验收（W39–W47）**：W39 验收取证通道（诊断日志并入 logcat 出口 TAG `LiquidAgentDiag` + 诊断页「复制全部」+ 跨波清单合并到 [`docs/10-device-acceptance.md`](docs/10-device-acceptance.md) §11）→ W43/W45/W46/W47 逐波真机验收
> - **W44–W48**：W44 模型健康度门禁（小样本自检）+ NOT_FOUND 模态降级链 → W45 降级链迁到**会话创建路径**（真机三判据全命中）→ W46 修「模型回复从未落盘」（P0）→ W47 thinking 独立预算 + 熔断保留输出 + 取消带 thinking + 速度显示 → W48 N1（MiniCPM5 `<think>` 明文混正文）根修 + `ChatRunCoordinator` 外提（`ChatViewModel` 1593 → 547 行）+ 能力位来源标记（用户显式设置优先）
> - **W49（2026-10-05）**：**W48 核心修法的真机回归 + 加固批**
>   - 真机回归（OPPO PDRM00 / Android 13）：**N1 通过**（MiniCPM5 正文无 `<think>`、思考进 `thinking`）／**1-B 部分通过**（通道关闭成立；直答不成立，但已定性为 **MiniCPM5-2B int4 固有能力限制、非 W48 引入**）／**能力位迁移 + 持久 + 门控模态后端请求**全部通过（关 audio 后 AUDIO 降级重建 **1 → 0**）
>   - **R-A 通道 def 数据驱动化**：`channels` 改「**默认 `null` = 信任容器元数据**，仅 Gemma-4 显式」（`null` ≠ `emptyList()`——后者是**禁用通道**）；消除「else → Gemma def」这个 N1 机制本体，为**预设扩容**清路。真机复验 3 通过（MiniCPM5 / Qwen2.5 / gemma-4 回归）+ 1 不适用，**无回退**
>   - **加固批**：D-1/D-4 措辞与解释器探测、**E1 探针判据分通道 + 采样对齐档案**（修正外部审查的因果描述）、**R-E salvage 内容级 HARD 闸门**（防退化输出进用户历史）、**R-B `scripts/fulltest.sh`**（把 `--continue` 纪律固化进仓库）、**R-C/R-D 守卫**（`ChatRunCoordinator` 行数 + 能力位 `USER` 章印唯一写入路径）⇒ **arch-guard 18 → 21 项、selftest 32 → 44**
>   - **CI 双绿**（`79b00c3`）：**Build `37296661956`** ✅（含 `Lint (baseline gate)`，真实新问题 = 0）+ **Release `37296662094`** ✅（签名 APK / AAB / debug 三产物齐，R8 mapping 附）
> - **W50（2026-10-05）**：**§11 顺位 1–8 真机回收 + 三件外部报告 P3 收口 + CI 可观测性**
>   - **§11 真机回收 8 ✅ / 3 ⚠️ / 2 ⛔**（W50 段 13 条逐条台账，见 §11.0.1 #19–#31）：✅ R1 层1 探测通过／层2 `tool_call` 下发／**层3 审批卡红线实证**（须用 `file_write`——只读工具不弹卡是正确行为）／沙箱子目录下钻／符号链接剔除／记忆 >2000 字符／通知档A／返回键两段式。⚠️ 层5「会话重建后一致性」（仅覆盖工具集变化子路径）／分段控件（仅 CPU/NPU 两段实证）／回显四关键字（Qwen 档，Gemma 两档未跑）。⛔ 层4「≥3 轮长任务」被 P1 中断／P1 模板渲染失败（本仓输入侧，W51 已修）
>   - 🔴 **P1 定案（本仓输入侧）**：Qwen2.5 在工具结果回灌那一轮的**生成期**模板渲染失败（`string + sequence`）。真机 **A/B 证明「关掉原生工具通道、走文本协议同样炸」⇒ 排除上游**；决定性取证 = `Contents.toJson()` 返回 `JsonArray`（数组非 string）+ `prompt_template.cc:112-120` 未展平 ⇒ **模板侧期望 string、实际收到数组**。修法 = 合并多元素为单个 `Content.Text`（离线可改）⇒ **W51 已实施并通过真机复验**，见下
>   - **代码侧 9 commit 全为零行为改动**：C1/C3 KDoc + C6 失败出口日志 + **删死字段 `ChatUiState.conversationId`**（全仓零读点）+ `fulltest.sh --summary-only` + **守卫 #22** + `build.yml` 非阻断用例数汇总（job summary，绿跑也带逐模块用例数）+ 上下文口径标签 B（清掉「引擎回报」错误措辞）+ C6 日志提级 `info → warn` ⇒ **arch-guard 21 → 22 项、selftest 44 → 47**
>   - **外部报告对账**：复审 11 / deepdive 审的是 W48，开放项仅剩 C1/C3/C6 三条 P3，**W50 全部收口**；另挖出**第 20–26 处「描述不成立」**（含第 26 处这条方法论级的：**「未触发的论断」冒充「已验证的排除」，强度等同假绿**）
>   - **UI 排版/配色真机审查**（用户点名「覆盖层覆盖范围 + 文字颜色混淆」）：**覆盖层机制经穷尽核查全部正确、无需改**（10 个 `LocalBottomBarOverlay` 消费点位置全对 / 14 处对话框全走玻璃）；「颜色混淆」的病灶**不是对比度**（全部达标，最紧 4.66:1）而是**「同角色不同色」6 处**。确证 **3 条 P2 挂 W51**：① 键盘态输入框离键盘多 **84dp**（`ChatScreen.kt:248` 的 overlay 无条件生效 + `imePadding()` 叠加）② **`GlassSegmented` 文字缺 `overflow`** ⇒ 真机「CPU/GPU/NPU」显示为 **CP/GP/NP** ③ **Snackbar 深色下渲染浅色块**（`LiquidAgentTheme.kt:98` 未映射 `inverseSurface`）。⚠️ 方法论：**`uiautomator dump` 的 bounds 对 Compose 不可靠**（导航栏 `Text` 报 8px、实际 ≈38px）⇒ UI 取证必须截图目视；本波据此**纠正 6 处假阳性**（含 2 处曾误报为「问题」）
> - **W51（2026-10-07）**：**P1 修法落地 + 生成期自愈 + 热降档换手段 + UI 批三条 P2 + CI 守卫 #23**
>   - 🔴 **P1「模板渲染失败」从归因到修法全部落地**（本仓唯一真代码 P1）：本波**抽出设备容器内嵌的真实 chat_template**（偏移 16423）并用**真实 `minijinja` 离线复现** ⇒ `content` 为 JSON 数组**必炸（1 个元素也炸）**、为字符串才正常，报错文案与行号与真机**逐字一致**；`javap` 反编译证实 `Contents.toJson()` **恒返回 `JsonArray`**（**推翻 W50「单元素时为字符串」的说法**，第 27 处「描述不成立」）。修法 = **`foldAdjacentText` 只折叠相邻连续 `Content.Text`**（非 Text 子类原样透传，防打碎多模态）+ **4 处下发点收口** + **`onError` 生成期自愈**（仅原生通道激活时证伪，根因与通道无关）。⚠️ **明确不覆盖**：消息含 Image/Audio 时必然 ≥2 元素，仍可能触发同一模板错误（Qwen2.5 纯文本模型本就不下发多模态）
>   - **真机复验（OPPO PDRM00 / A13）**：用 W50 造成**确定性崩溃**的原始题面复跑 —— **文本协议组 `Failed to apply template` 0 命中**、`settled=ModelStopped`、**3 条工具结果同批回灌**（模型 round 0 一次发 3 个 tool_call ⇒ **非 vacuous**）、工具真执行（`file_write` ok、沙箱文件内容 `hello`）；native ON 对照组 0 命中（但该组「多结果单批」子场景 **vacuous/未行使**，不记通过）；单文本基线 `17×23=391` ✅
>   - 🔴 **热降档改走轮次**（外部复审点名的「唯一活的代码缺陷」）：原 `thermallyCappedConfig` 把 maxTokens 2048→1024，却被 `ModelSamplingProfiles.appliedTo` 的 `maxOf(…, minMaxTokens=2048)` 顶回 ⇒ **降档 100% 失效 + 日志说谎**（真机 W43 实测 SoC 83℃）。改为**恒压 `maxAgentRounds`**（轮次不受采样档案影响）+ maxTokens 只在档案 `minMaxTokens==0` 时压 + 日志逐项写明**实际生效值**
>   - **UI 批三条 P2 全部落地并真机目视**：① 键盘态输入框离键盘多 84dp（overlay 无条件生效 + `imePadding()` 叠加）⇒ 条件化后空隙 **≈25–36dp**（旧 ≈94dp）、收起态不压底栏 ② `GlassSegmented` **两处** Text 补 `overflow` ⇒ 真机由「CP/GP/NP」半字截断变为 **`C…`（1 全字 + 省略号）** ③ Snackbar 深色浅色块 ⇒ 抽 `bridgeGlassToMaterial` 单一事实源 + 补 13 个色角色，深色块色实测 **`(34,36,46)`**
>   - **其他**：`renameTo` 返回值检查（全仓同族 4 处都查了、就这一处漏）；上下文条补 `maxLines`+`Ellipsis`；订正 `RunTokenLedger` 残留作废口径（全仓 `估算≈` **3 处全清**）；**守卫 #23**（`--summary-only` 退出码契约）+ `fulltest.sh` `files==0` 的 `::warning::`（**并修复它被 `$(…)` 捕获后不成为注解的缺口**）+ `build.yml` 用例数低于基线的非阻断 soft-check ⇒ **arch-guard 22 → 23 项、selftest 47 → 52**；全量单测 **598 → 619**
>   - **外部报告对账**：复审 13 的「P1 涉及面 2 → **4 处**」与「先主因后自愈」顺序纪律均已采纳；每日简报（审 `e003509`）的两大建议**正是本波内容**，其「引擎 0.11.0 过时」在仓内不成立
> - **W52（2026-10-08）**：**清观测盲区 + 挂账对账 + 小件行为修复**
>   - 🔴 **B1 审批等待停表**（行为修复）：墙钟硬预算原自 run 起点起算、判定在**轮头**，而审批等待发生在**轮中** ⇒ 用户审批犹豫被全额计入任务耗时，批准后下一轮轮头可能立即 HARD 熔断（W51 一个污染 run 即此现象）。修法 = `RunState.pausedNanos` 累计审批挂起并从 `remaining` 剔除（evidence / 日志 / 诊断卡统一走 `effectiveElapsedMillis()`）+ 子 run 继承墙同步延长等效量；算术抽文件级纯函数 + 2 例边界单测。⚠️ **取舍**：只排除审批挂起，**不排除**模型生成 / 工具执行
>   - 🔴 **B2 层3 判据首次有正向观测面**：原仅「漂移才 warn」且诊断未产出时恒判「一致」⇒ 离线永远判不了「引擎侧原生通道是否真失效」（层3 恒 `⛔`）。改为每 run 落一条 info 报 `useNativeTools` + 引擎 `nativeToolChannel`（诊断未产出报 `null`，**不冒充**一致）
>   - **V-2 H-A 观测面**：主折叠点（`LiteRtLmEngine.kt:1766`）在**折叠前 ≥2 元素**时落「折叠前 N → 折叠后 M」日志（单元素不落，防刷屏）
>   - 🔴 **`fulltest.sh` 假全量「对症」加固**（⚠️ 外部三份报告的因果**不成立**）：`rm -rf test-results` **自脚本创建（`6790cdd`）起就在 gradle 之前**——空环境实测「植入残留 → 跑默认模式 → 残留被删 + gradle 快速失败 + `::warning::` + exit 1」⇒ **无假全量**。真实 fail-open 面收窄为「`rm` 静默失败 + gradle 也失败」⇒ 修法 = rm 失败可见化 + 打印 XML mtime 范围 + 默认模式模块数 ≠9 告警（红线：`--summary-only` 恒 exit 0 **逐字保留**）
>   - **守卫 23 → 25 项**（#24 fold 收口不变式：`Message.user` 下发点数 == `foldAdjacentText` 调用数，防新增下发点**静默重开 P1**；#25 `LiteRtLmEngine.kt` ≤2400）+ **selftest 52 → 59** + `build.yml` 用例数基线 **598 → 619** + 全量单测 **619 → 621**（唯一失败仍为 `SandboxFileScannerTest.kt:184` Windows 符号链接**既有基线**）
>   - **台账聚合计数订正**：逐条实数（`awk` 计结论列）= **35 行 = 28 ✅ / 5 ⚠️ / 2 ⛔**（README 原「31 条 / 27 ✅ / 4」**三项全错**；handoff §五.5/§五.13 同步）。**W53 追加 #36**（层3 日志观测面回收）⇒ **36 行 = 29 ✅ / 5 ⚠️ / 2 ⛔**；**W55 追加 #37**（H-A 毒化 A/B 回收）+ **#38**（B1 长审批停表回收）⇒ **38 行 = 31 ✅ / 5 ⚠️ / 2 ⛔**（并由 arch-guard 第 27 条机械钉住聚合句 ↔ 逐条台账）
>   - **真机复验（OPPO PDRM00 / A13）**：文本协议下模型一次发 **3 个 tool_call** ⇒ `多元素 content 下发：折叠前 3 → 折叠后 1`（**fold 真实行使、非 vacuous**）、`Failed to apply template` **0 命中**；B2 日志真机命中（`true`/`false` 两态，未冒充）；审批卡被批准 **5 次**且 **WallClockBudget 硬预算熔断 = 0**；沙箱正确拒绝绝对路径。⛔ 未行使：B1 的「长审批」效应（审批 ~7s 自动通过）
>   - **多模态 P1 未覆盖补独立挂账** + **6 个**视觉预设标注「图片输入尚未验证」（与既有「视觉 GPU 未实测 → 禁 GPU」同纪律）
> - **W53（2026-10-08）**：**守卫网扩展 + 引擎 god-file Stage-1 拆分 + 多模态 P1 取证（L1/L2）**
>   - **守卫 25 → 27 项**（#26 测试基线双向同步：`build.yml` baseline == 全仓 `@Test` 代码位实数，**双向告警**，根治「计数链三连犯」；#27 A5 台账聚合句 ↔ §11.0.1 逐条三态机械钉住）+ **selftest 59 → 65** + `build.yml` 基线 **619 → 623**
>   - 🔴 **引擎 god-file Stage-1 拆分**：`LiteRtLmEngine.kt` **2354 → 2054 行**（`4ce095f` 拆分结果），抽 4 个同包文件（`EngineLoadDegrade` / `ThoughtChannels` / `PrefaceCheck` / `TemplateRenderGuards` = 349 行**纯搬运零行为变更**）；`foldAdjacentText` + `summarizeContentTypes` 留在原文件以保守卫 #24 不变式
>   - **多模态 P1 取证（L1/L2，无真机窗口）**：`Contents.toJson()` 恒返回 `JsonArray`（`javap`）+ C++ `NormalizeContent()` 原样透传（**不展平**）+ `adb exec-out dd` 抽容器 `chat_template` + `minijinja` 离线复现 ⇒ **数组问题真实存在**；逐容器定案：✅ 安全（模板用 `is sequence` + `for`）= `gemma-4-E2B-it`(CPU) / `gemma-4-E2B-it-gpu` / `MiniCPM-V-4-int8`；❌ 不安全 = `Qwen2.5-1.5B`（`:23` `'…' + message.content + '…'`）；**5 项未取证如实标保留**（`Qwen2-VL-2B` / `LFM2.5-VL×3` / `SmolVLM2 500M`）
>   - **H-A 矛盾定性**：三层源码（Kotlin/JNI/C++）**未找到 collapse 实现**，但 W52 真机「折后 1 元素不炸」冲突 ⇒ **不写「已证伪」**（「没找到 ≠ 证伪」）；补 **role 观测面**（`outboundRoleForDiag` + onError 自愈日志带 role）
>   - **台账 #36 回收**（层3 正向观测面）⇒ 聚合句 **35 → 36 = 29 ✅ / 5 ⚠️ / 2 ⛔**
>   - **CI 双绿**（`4ce095f`）：**Build `37780427636`** ✅ + **Release `37780427551`** ✅
> - **W54（2026-10-09）**：**四份外部审查报告对账 + 观测面补强 + 审查收口**
>   - ⛔ **报告1 的「唯一现行缺陷」N-W1 证伪**：其「B1 停表不向子 run 传播」漏看了 `AgentRunner.kt:1936` 的 `state.hardDeadlineNanos + state.pausedNanos` ⇒ 子 run 与父**共用同一堵墙**；其建议修法会**双重计入**、重开 Wave 31 已修的「上界放大」缺陷 ⇒ **否决实现**。**采纳其测试意图**做反向加固：spawn 点算术外提纯函数 `childDeadlineNanos` + 1 例测（钉「父挂起只计一次」，此前**零测试覆盖**）
>   - **自愈重建计数观测面**（毒化测试前置件）：`LiteRtLmEngine` 加实例级 `@Volatile var templateRebuildCount`（`releaseInternal` 复位），`onError` 模板失败分支 `++`、两处 `warn` 带 `（会话重建 #N）`；⚠️ 阈值**待真机 N 分布确定，勿现在拍**
>   - **#25 注释非写死化**（治 HEAD 处**活 stale**：W53 `48b6880` 一边订正注释数字、一边改同文件代码净 +9 行 ⇒ 注释当场过期）⇒ 注释只留冻结阈值 + 理由，实测行数以违规输出 `当前 $n 行` 为准
>   - **M1 R-E KDoc 口径** + **对账子项级固化**（补 2 条 W53 漏账：「反复炸防护」「图片入口硬闸门」）+ **W53 文档勘误**（2389 → **2354** / `GPU_FAILURE_*` private→internal / 4new → **5new** / 行数加 commit 锚）
>   - `build.yml` 基线 **623 → 624**（+1 例 @Test）；引擎 **2063 → 2087 行**（余量 313）
>   - **真机冒烟（OPPO PDRM00 / A13）**：`assembleDebug` ✅ + `adb push` / `pm install -r` Success（`lastUpdateTime=2026-10-09 14:51:19`）+ 冷启动 PID 13804 存活 + **全量 logcat 无 app `FATAL EXCEPTION`** + UI 截图目视正常。⛔ 未行使：native 相关（本档**不含 native 行**）、B1 长审批、毒化
>   - **CI 双绿**（`04efed3`）：**Build `37896782929`** ✅（`Assemble Debug` / `Lint (baseline gate)` / `Unit tests` 三 job 全绿）+ **Release `37896782933`** ✅。🔴 **本波首次可读 CI job log**（PAT 有 `actions:read`）⇒ 实测 CI 内 `架构守卫全部通过。`（27 条）+ `自测结果：PASS=65 FAIL=0`（case33/34/35「红来自真命中」可见）⇒ W51 记的「CI 内守卫读数不可程序化读取」**已不成立**
> - **W55（2026-10-09）**：**H-A 毒化定案（离线源码级 + 真机 A/B）+ 多模态 L1 取证 + 同步路径自愈**
>   - 🔴 **H-A 定案（本波旗舰）**：litertlm **v0.17.1** 的 `runtime/conversation/model_data_processor/generic_data_processor.cc:105-112` 把「**恰 1 个 `text` 元素**」的 content 数组**收敛为 string**（条件 `content.size()==1 && [0].type=="text" && !requires_typed_content`）；≥2 元素保数组 ⇒ Qwen2.5 模板 `+` 必炸。**W53「未找到」的原因 = 版本边界陷阱**（W53 读仓库 **tip**，tip **已删除**该收敛、该文件仅 92 行、无 `MessageToTemplateInput`/`requires_typed_content`）⇒「未找到」≠「不存在」。**真机同构建 A/B（OPPO PDRM00 / A13）**：fold ON（折后 1 元素）**不炸** / fold OFF（3 元素）炸 `:23` ⇒ 元素数（1 vs ≥2）是唯一区分维度。离线三路径（tag diff / minijinja 版本 / JVM 0.17.1 AAR 实跑）互相印证
>   - 🔴 **bump 硬风险（新增第四条前置）**：bump 过该删除点 ⇒ fold 折出的 1 元素数组**不再被收敛** ⇒ Qwen2.5 模板 `+` 必炸 ⇒ **P1 静默回归**；`#24` 守卫只钉 fold 调用点**存在性**，拦不住此**行为级**漂移
>   - 🔴 **同步路径模板失败自愈（治本）**：真机证实模板失败在 `conv.sendMessageAsync(...)` **同步抛出**、**不经** `onError` 回调 ⇒ W51 自愈（置 `conversationDirty` / 证伪 `nativeToolsRejected`）与 W54 `templateRebuildCount` 对**真实路径失效**。修法 = 把「模板失败处置」抽成 `handleTemplateRenderFailure`、**两条路径共用**（同步 catch / 异步 `onError`）+ 同步 `sendMessageAsync` 包 `try/catch`（**必须 rethrow**，不吞异常）+ 两处 `warn` 带**来源标识**（`同步下发` / `异步回调`）
>   - **多模态 L1 剩 5 容器取证（全部数组安全）**：`Qwen2-VL-2B` / `SmolVLM2-500M` / `LFM2.5-VL-450M` / `LFM2.5-VL-1.6B` / `LFM2.5-VL-3B` 的 `chat_template` 用 `content is string` + `for item in content`（非 `+` 拼接）⇒ 三种 content 形状（string / 单元素数组 / 多元素数组）全 OK。🔴 **推翻 W53 假设**「Qwen 系同源 ⇒ 风险最高」——`Qwen2-VL-2B` 与 `Qwen2.5-1.5B` 模板形态**不同**（**同家族 ≠ 同模板**）。⚠️ 仅 L1（模板层）；**端到端图片输入（L3）仍待真机验证**
>   - **台账 #37/#38 回收**（H-A 毒化 A/B + B1 长审批停表）⇒ 聚合句 **36 → 38 = 31 ✅ / 5 ⚠️ / 2 ⛔**（`build.yml` 基线维持 **624**，本波无新增 `@Test`）
> - ⚠️ **真机验收台账：Wave 33 起累计 38 条（其中 31 条 `✅回收` / 5 条 `⚠️部分` / 2 条 `⛔不适用`）**（逐条台账见 [`docs/10-device-acceptance.md`](docs/10-device-acceptance.md) §11.0.1）——仍是最大风险敞口。**W51 新增回收 4 项**（P1 主组 / 单文本基线 / F1 键盘态 / F3 Snackbar；F2 见上）；**W52 新增回收 1 项**（层3 `useNativeTools` 正向观测面，B2）；**W55 新增回收 2 项**（H-A 毒化 A/B：fold ON 不炸 / fold OFF 炸 `:23`，台账 #37；**B1 长审批停表：审批挂起 ≈334s 不被 HARD 熔断**，台账 #38）；**未覆盖项挂 W52**（通知档B／Gemma 两档／压缩触发重建／记忆磁盘满·只读／W37 UI 手感／lint gate／W38 行为变更／W40 验收面／F4 文字 token 统一）；验收清单与取证命令见 §11。


---

## 挂账台账（🟡 已实现未接线）

「代码状态」四态里的 🟡 条目集中记在这里（不再散落在路线图里），每条必须写明**重启前提**
（对齐 `SegmentedHistoryStore` 类头的三前提范式）：

| 条目 | 现状与挂账理由 | 重启前提 |
|---|---|---|
| **完整 i18n（含 RTL）** | `app/src/main/res/values/strings.xml` 只有 1 条串（`app_name`），Composable 里 ~145 处中文硬编码 ⇒ RTL 布局从未被验证、也无从验证。Wave 32 已撤下 `AndroidManifest.xml` 的 `android:supportsRtl="true"` —— 先不声明未验证过的能力 | ① 硬编码中文串抽到 `strings.xml`；② 补 `values-ldrtl` / 布局镜像的真机或预览验证；③ 验证通过后才恢复 `supportsRtl` 声明 |
| **`termsVersion`（法务条款版本化）** ⚠️ **时序风险** | `SettingsRepository` 的 `is_tos_accepted` / `is_gemma_terms_accepted` 都是**无版本 boolean**，只能表达「同意过 / 没同意过」，表达不了「同意的是**哪一版**」。⇒ 一旦替换法务文本，**当天所有老用户**都会命中 `true` 而被视为「已同意新条款」，首启门禁与法律页开关被直接跳过（**未同意却被视为已同意**，合规事故），且事后无法反推用户当年同意的是哪一版。Wave 39 只把债务固化为注释（`SettingsRepository.kt` 的 `IS_TOS_ACCEPTED` 上方），**未改行为** —— 实现涉及产品/法务决策（老用户的 `true` 算「已同意第 1 版」还是「未同意任何版本」） | ✅ **落地顺序是硬约束**：`termsVersion` **必须先于任何法务文本替换落地**，不能反序（反序则老用户同意状态不可区分、不可补征）。行为实现需先裁定上述产品/法务问题 |
| **J4：`SandboxFilesViewModel` 直构造白名单唯一（工作区覆盖层）** | `LiquidAgentApp.kt:378` 是全仓**唯一**绕过 `viewModelFactory` / ViewModelStore 的 VM 实例化点（「首次打开才创建 + 旋转即关」刻意取舍；VM 内目前只有自终止任务，现状无实害，但绕过 store ⇒ 离场时靠 `DisposableEffect` 手工补偿 cancel）。唯一性已由 `scripts/arch-guard.sh` **第 17 项冻结**（第二处直构造即红）；迁移正规 viewModel 路径 = **行为变更项**（改变重开覆盖层的重扫语义 /「旋转即关」取舍） | 触发条件：① 给该 VM 加轮询 / 常驻监听（旋转会从「无实害」变真泄漏）；② 需跨开关保留面板状态。实施时必须一并处理重开覆盖层的重扫语义，并过真机验证；迁移落地后 `LiquidAgentApp.kt` 的补偿清理块随删、守卫白名单同步清空 |
| **模型加载后小样本自检（健康度门禁）** ✅ **Wave 44 已实现（手动档）** | **痛点（真机实锤）**：坏容器要等用户下完 2GB、发第一条消息才发现 —— `gemma-4-E2B-it-gpu.litertlm` 输出退化到采样出 `<unused1556>` 等**保留未训练 token**。<br>**Wave 44 已实现手动档**：诊断页「运行自检」按钮（`ModelHealthProbe` + `ModelHealthCriteria`）—— 两条固定短 prompt（`PROBE_MAX_TOKENS=96`）、独立探针会话（`conversationId="__health_probe__"` + 递增 `contextVersion`）、判据 A 保留 token / A′ 非白名单通道（软）/ B1 单字符 run / B2 多字符周期 / C 空输出，重复类判据复用 `StreamRepetitionDetector`（零口径分叉）；结论 PASS/DEGRADED/BAD，BAD 经既有 sink 自动落盘。**仅手动触发，绝不加载后自动跑**（会话重建成本只由按钮支付）。<br>**W44 真机实测**：好容器 PASS 稳定（`gemma-4-E2B-it` PASS ×2、`MiniCPM5-2B_int4` PASS）；坏容器 `gemma-4-E2B-it-gpu` 因容器同时缺 AUDIO/VISION 两个 section，先 3× 执行失败（`NOT_FOUND`）、后 2× **DEGRADED**（仅命中 `channel_marker[SOFT]`，**无 `<unusedNNNN>`**）⇒ 判据稳定，但「预期 BAD 实为 DEGRADED」；探针会话隔离生效（`cid=__health_probe__`，`estPrompt`≈14 tok，用户会话零污染），无激活模型时中性拒绝（`NO_ACTIVE_MODEL`）。 | 二段翻转（**自动档**）前提：① 真机验证判据不误报（1 个坏容器 Gemma-4 GPU + 2 个好容器 MiniCPM5 / Gemma-4 CPU）；② 裁定自动触发时机与是否在模型卡展示结论；③ 自动档需解决「加载后自动跑 = 无条件付 1 次会话重建」的成本（仅在上层确认可接受时才翻转） |
| **NOT_FOUND 错误驱动模态降级链** ✅ **Wave 45 已根修（落点从 `load()` 迁到会话创建路径）** | **痛点（真机实锤）**：Gemma-4 E2B 启发式 `audio=true` 但容器无 audio section ⇒ 旧实现加载直接失败；`gemma-4-E2B-it-gpu` 容器 section 表只有 text decoder ⇒ GPU 加载亦失败。<br>**Wave 44 首次实现**：动态事件驱动降级（`EngineLoadDegrade` 纯逻辑 + `EngineAttempt`）—— 只认 `NOT_FOUND` 触发模态降级（先 AUDIO 后 VISION），GPU→CPU 二段正交叠加，上限 4、每模态降一次；降级事实经 `EngineSessionDiagnostics.degradedModality` 出口，`capabilities()` 随降级收窄能力位，对话页小字提示「已去 X 模态完成加载」。**但落点错误**：`NOT_FOUND` 实际由 `createConversation` 抛出（引擎已建成功、建**会话**时才绑定 audio 子图），而修复挂在 `load()` ⇒ **真实故障下从未触发**。<br>**Wave 45 已根修**：降级链迁到**会话创建路径**（触发点 = 发第一条消息，**不在**模型加载页）。**W45 真机三判据全命中**：`会话创建遇容器缺` 重建日志 ×8、编码器 `role=on` ×8、`role=legacy`=0；负向对照 `MiniCPM-V-4-int8` 零降级（见 §11.0.1 台账 #1）。 | ① ✅ 已真机验证降级链端到端（W45 三判据全命中，Gemma-4 E2B GPU 去 AUDIO/VISION 后加载成功 + UI 小字）；② 上游容器补全 section / litert-lm 支持该变体后，可移除此降级（`NOT_FOUND` 不再出现）；③ 本轮不推仓库、不跑 CI，验证在本地闸门 |
| **Gemma-4 GPU 特化变体输出退化（上游错配）** 🆕 | `gemma-4-E2B-it-gpu.litertlm`（2.0GB）在 LiteRT-LM **0.17.1** 下输出退化：temp 0.4/20 → n-gram 死锁；temp 1.0/64（官方口径）→ 采样出保留未训练 token（logits 分布退化）；容器 section 表只有 `tf_lite_artisan_text_decoder`，CPU 后端 engine init 直接 `NOT_FOUND`。同转换线的 `gemma-4-E4B-it-gpu` 未验证（已标注谨慎）。CPU 变体（2.41GB）实测通过，是唯一推荐。<br>**W45 真机实测**：该 GPU 变体容器同时缺 AUDIO+VISION 两个 section ⇒ 加载即触发 **2 次引擎重建**（各降一次）；健康自检对同一容器给出 **DEGRADED**（仅 `channel_marker[SOFT]`，**无 `<unusedNNNN>`**）—— 与「采样保留未训练 token」的 BAD 判据不符。 | ① 等 LiteRT-LM 发布含该变体支持的新版本后 bump `litertlm` 并重测；或 ② 改用上游单文件双后端容器（README 实证 `gemma-4-E4B-it.litertlm --backend=gpu` 单文件跑 GPU）；或 ③ 上游确认该变体仅适配更高版本 runtime → 从预设下架。恢复前 preset 维持 `recommended=false` |
| **N1：MiniCPM5 `<think>` 明文混进正文** ✅ **Wave 48 根修 + Wave 49 真机已验证；Wave 49 追加 R-A 消除机制本体** | **真机铁证**（`_w47_after/08bacd4c`）：MODEL 消息 `text` 以 `<think>\n` 开头、含完整 `</think>`，思维链与答案全在正文、`thinking` 字段恒空；连带 `tok/s` 被思考 token 污染、1-B「关闭思考仍有思考区」。<br>**根因（比报告更精确）**：不是「引擎未识别」，而是**配置的 `channels` 覆盖了容器元数据的 `<think>` 声明** —— 本仓无条件下发 Gemma 专用 `THOUGHT_CHANNEL_DEFS`，native 的 overwrite 语义（`conversation.cc:189-200`）整体丢弃元数据通道 ⇒ MiniCPM5 永不切分。**且不能简单 append 第二 def**：native thinking 预算只用 `channels.front()`（`conversation.cc:371-392`，含上游 TODO）⇒ append 会让 W47 预算对 MiniCPM5 静默失效。<br>**Wave 48 修法**：`THOUGHT_CHANNEL_DEFS` 常量 → 纯函数 `thoughtChannelDefsFor(model)`，**按模型身份选 channel def**（MiniCPM5 → `<think>`/`</think>`，其余 → `<|channel>thought`/`<channel|>`），保证 `front()` 恒为该模型自己的思考通道（切分与预算同时正确）；**1-B** 同源修：关思考时**显式下发 `enable_thinking=false`**（原为 absent，而 absent ≠ off）。 | ✅ **Wave 49 真机已验证**（OPPO PDRM00 / Android 13，debug 包）：cid `ac99077c`（交叉 `033d652e`）—— `text` **无 `<think>`**、`thinking` **非空(472)**、logcat「thought 通道解析声明已随会话下发（正文剥离 `<think>`…`</think>`）」；对照 W47 `08bacd4c`（正文含 `<think>` 开头）。<br>⚠️ **1-B 只「部分通过」**：**通道关闭 ✅ 成立**（`thinking` 空、`text` 无 `<think>`、tok/s 7.26 对照 ON 态 0.29~0.92），但**直答 ❌ 不成立**（`text[0:70]` 为推理腔/元规划文本）—— 已定性为 **MiniCPM5-2B int4 固有能力限制、非 W48 引入**（W47 `bbd8db82` 同款 OFF 态已无直答）。⇒ 1-B 的可宣称收益**仅限**「通道层面 thinking 不再混入正文」+「开关层面 OFF 态 `thinking` 为空」，**不得宣称「OFF 态直答」**。<br>✅ **Wave 49 R-A 消除机制本体**：`thoughtChannelDefsFor` 的「else → Gemma def」会让**任何新预设**若容器自声明通道就再落一次同类 N1 ⇒ 改为**数据驱动**（`ChannelSyntax` 默认 `null` = **信任容器元数据**，仅 Gemma-4 显式；`null` ≠ `emptyList()`——后者是**禁用通道**）。真机复验 3 通过 + 1 不适用、**无回退**（cid `2b70da35`；负向对照 `What is 2+2` 亦 `ModelStopped` ⇒ 难度无法解释熔断；gemma-4 回归逐项不变） | **残留（P3，非本波）**：① 治「OFF 态规划外溢」应走**提示词面**（评估 `MEMORY_MAINTENANCE` 段是否被 2B 模型当待办复述）或换模型，**不要**动 `thoughtChannelDefsFor` / 1-B 通道逻辑（那是通道问题）；② 既有闸门对「改写型回显」是**结构性盲区**（`prefaceContainsSystem` 只验渲染完整性；回显检测要求逐字句级指纹 + 连续 2 句，且记忆段被 `AgentRunner.kt:657` 显式排除）|
| **能力位虚高（每次加载双重建）** ✅ **Wave 48 已修 + Wave 49 真机已验证** | **真机实证**（`_w45_models.json`）：5 个模型能力位全虚高（Qwen2.5 `image/audio/thinking=true`、MiniCPM5 `image/audio=true`、gemma-4 `audio=true`）⇒ 每次加载都付两次引擎重建（AUDIO+VISION 降级），且给不支持 thinking 的模型开 thinking 通道。**根因**：`ModelHeuristics.mergeHeuristic` 用 `||` **并集只增不减**，历史误写 true 被永久锁死，用户手动关闭后又被抬回。<br>**Wave 48 修法（用户裁决「用户显式设置优先」）**：新增 `CapabilitySource { HEURISTIC, USER }` 标记（`ModelDescriptor.capabilitiesSource: CapabilitySource? = null`，`@Serializable` 向后兼容）；语义由「并集只增不减」改为**用户显式设置优先**（`resolveCapabilities`：`source == USER ? persisted : heuristic`，**替换**非并集）；`setCapabilities` 置 `USER`、`probe()` 尊重 `USER`；**旧数据（`null`）按启发式重算**（修好现有虚高，首次 `refresh()` 记一条迁移日志）。 | ✅ **Wave 49 真机已验证**（三项全过）：① **迁移** —— 迁移行只出一次（`10-03 23:04:56 … 旧条目能力位来源未知，已按启发式重算：5 条`）+ 迁移后 5 条全 `HEURISTIC` 且与启发式矩阵**逐格吻合** + **幂等**（重启不再出迁移行）；② **持久** —— Qwen2.5 `image/audio` 改 `USER` → `am force-stop` → 重启 → 仍 `source=USER` 且值保持；③ **门控模态后端请求** —— `gemma-4-E2B-it-gpu` @ GPU 后端 A/B：基线（`img/aud=true`）`会话创建遇容器缺 AUDIO` **×1** / `VISION` **×1**，关闭后（`source=USER`）**0 命中**、会话一次建成。<br>⚠️ before 列取自 `_ci-tools/_w45_models.json`（W45 快照）——**近似基线、不作判定依据**（精确快照因冒烟启动早于备份约束而丢失） | **残留（P2，需产品/真机）**：`DeepSeek-R1-Distill-Qwen-1.5B` 被 `inferFamily` 误判为 `thinking=false`（文件名含 `qwen` 不含 `qwen3` ⇒ 落 `OTHER`），而 W48 让 `enable_thinking` **恒发** ⇒ 可能关掉它的推理。**重启前提**：① 拿到该容器（或任何 `deepseek-r1*` / `qwen3*` 推理件）；② 真机验 AUTO 模式是否仍推理；③ 若确被关，扩 `inferFamily` 识别推理模型（含单测）——**不得在无容器时盲改** |
| **`ChatViewModel` 职责堆积（1593 行 / 余量 7）** ✅ **Wave 48 已解** | `feature-chat/.../ChatViewModel.kt` 曾达 **1593 行**（arch-guard 第 18 项上限 1600、余量仅 7）。**Wave 48 真外提**：run 编排（`onSend`/`onRetry`/`onSendFrom`/`onRecover`/`onStop`/`handleEvent` 三终态/流式缓冲/落库决策/journal 恢复/热档位设施）整体外提到新类 **`ChatRunCoordinator`**（1152 行），VM 保留 UI 状态与输入事件 + 薄转发 ⇒ **1593 → 547 行**（余量 ≥1000）。三条硬不变量（`persistState`/`salvageText` 清零仅在 run 起点；`onStop` thinking 快照先于 `resetStreaming`）与两条**相反顺序**（`Finished`/`Cancelled` = commit 早于 reset；`onStop` = reset 早于 commit）经逐函数比对**原样保留**，`ChatScreen` 零改动。 | —（已落地；arch-guard 阈值维持 1600 **未改**，外提后远低于上限，无挂账） |

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

- **三项「挂账蒸发」实为从未存在** —— **Wave 39 核实销账**、**Wave 44 复核修正**（原论据不成立，见下）：
  - `SubagentProgress` 事件：**源码零命中**（从未实现）。`AgentEvent` 现役 14 分支无此值，
    `git log -S"SubagentProgress" --all -- '*.kt'` 零 commit ⇒ 是**从未开工的提案项**（非「做过又丢」）。
  - `ConversationRepository` 增量写：**类存在、增量写机制不存在** —— `ConversationRepository`
    全仓**有命中**（类定义在 `core-data/.../ConversationRepository.kt` + 多处生产引用），
    原「全仓 grep 零命中」对它是**字面假命题**；现役写路径是**全量读 → 全量写**
    （`appendMessage` → `save` 整份重写 `<id>.json`）。「增量写」是**机制描述符**、非可 grep 符号，
    以 grep 判其不存在属方法错误。此为**从未开工的性能改造提案**。
  - `formatVersion`：**源码零命中**（从未实现为具名版本字段）。跨版本读兼容意图已由
    **「全字段带默认值」**这一替代纪律覆盖（`AgentRunJournal` / `TurnRecord` / `SubagentSessionStore`
    三处明文）—— 不是「schema 兼容空白」。来源仅为 Wave 26 Operit 侦察的「别家做法」待办，从未落地。
  - 结论方向（三者都不在现役源码中）与台账一致，但**论据与笼统措辞已按上述逐项做实**。

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
