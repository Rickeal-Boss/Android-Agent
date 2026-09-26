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

| 类别 | 能力 | 状态 |
|---|---|---|
| 🧠 **本地推理** | 端侧加载 `.litertlm` / `.task` 模型，CPU / GPU / NPU 后端可选 | 规划中 |
| 👁 **多模态** | 文本 + 图片 + 音频输入（Gemma 3n 要求 vision=GPU、audio=CPU） | 规划中 |
| 🔧 **工具调用** | 内置工具集 + 自定义工具，模型自主决定是否调用 | 规划中 |
| 💭 **思考模式** | `enable_thinking` 开关，独立渲染 `thought` 通道内容 | 规划中 |
| 🎛 **参数调节** | topK / topP / temperature / maxTokens / system prompt 全可调 | 规划中 |
| 🌐 **远程后端** | ~~可选接入远程模型服务（OkHttp + SSE 流式）~~ 已移除（云端 API 整体删除，现为纯端侧） | 已移除 |
| 💾 **会话管理** | 多会话持久化，DataStore + JSON，支持导入导出 | 规划中 |
| ✨ **Liquid Glass UI** | Compose 液态玻璃设计系统（基于 Kyant0/AndroidLiquidGlass 移植改造，见 [NOTICE](NOTICE)）：背景模糊、折射高光、内描边、噪声微纹理、弹性动效 | 规划中 |
| 🔌 **模型市场** | 模型清单管理、下载状态、能力探测（speculative decoding 等） | 规划中 |

> 状态说明：仓库刚开荒，模块正在逐步落地。上表为设计目标，实际进度见 [路线图](#路线图) 与各模块代码。

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
| LiteRT-LM | `com.google.ai.edge.litertlm:litertlm-android:0.11.0` | gallery main 已验证 |

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
- ❌ 网络封装（Retrofit 等）—— 用 OkHttp + 手写 SSE 解析
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

> 本项目锁定 `litertlm-android:0.11.0`，因此**优先使用与该版本配套的转换产物**。升级引擎版本属于独立的技术决策，见 [`docs/00-recon-brief.md`](docs/00-recon-brief.md) 第 3 节。

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
├── core-engine/                  # 引擎抽象 + LiteRT-LM 实现 + OpenAI 兼容实现 + 能力探测
├── core-agent/                   # Agent 循环、工具注册中心、内置工具、上下文压缩
├── core-data/                    # DataStore + JSON 持久化 + 仓库 + AppContainer + CompositionLocal
├── core-design/                  # Liquid Glass 设计系统（tokens / 颜色 / 动效 / 组件）
├── feature-chat/                 # 对话页 + 参数面板 + 多模态输入
├── feature-models/               # 模型库 / 导入 / 加载 / 后端选择 / 能力探测
├── feature-settings/             # 设置页 + Agent 工具页（子包 tools）+ 远程端点 CRUD
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
| `:core-engine` | `com.rickeal.agent.core.engine` | 引擎抽象 + LiteRT-LM 本地实现 + OpenAI 兼容实现 + 能力探测 | `:core-model` |
| `:core-agent` | `com.rickeal.agent.core.agent` | Agent 循环（思考 → 工具调用 → 观察 → 继续）、工具注册中心、上下文压缩 | `:core-model`、`:core-engine` |
| `:core-data` | `com.rickeal.agent.core.data` | DataStore + JSON 文件持久化 + 仓库 + **AppContainer + CompositionLocal** | `:core-model` |
| `:core-design` | `com.rickeal.agent.core.design` | Liquid Glass 设计系统（tokens / 颜色 / 动效 / 组件）+ 窗口尺寸自适应，**纯视觉、出度 0** | 无 |
| `:feature-chat` | `com.rickeal.agent.feature.chat` | 对话页 + 参数面板 + 多模态输入 | 2,3,4,5,6 |
| `:feature-models` | `com.rickeal.agent.feature.models` | 模型库 / 导入 / 加载 / 后端选择 / 能力探测 | 2,3,5,6 |
| `:feature-settings` | `com.rickeal.agent.feature.settings` | 设置页 + **Agent 工具页（子包 `tools`）** + 远程端点 CRUD | 2,4,5,6 |

依赖无环：`core-model`、`core-design` 出度 0 → `core-engine` / `core-data` → `core-agent` → 三个 feature → `:app` 汇合。

> 设计系统刻意**不依赖**领域模型：一旦依赖，`ModelDescriptor` 的每次字段变更都会触发全量 UI 重编译，
> 且 `@Preview` 就必须构造领域对象。需要展示模型信息的卡片在 feature 层组装。

---

## CI / 发布

| 工作流 | 触发 | 产物 |
|---|---|---|
| [`build.yml`](.github/workflows/build.yml) | push `main` / `UI` / `harness` / PR → 三者 / 手动 | debug APK（artifact，保留 30 天）；失败时上传 `**/build/reports` |
| [`release.yml`](.github/workflows/release.yml) | push `UI` / `harness` / tag `v*` / 手动 | debug APK（保底）+ 可选签名 release APK / AAB + GitHub Release |

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
- [ ] **M9** — **Harness 升级**（`harness` 分支）：移植 ZCode（Journal/Actor/typed-ask）
  与 Octop（工具审批/长期记忆/委派）的核心机制 —— 蓝图见
  [`docs/11-harness-blueprint.md`](docs/11-harness-blueprint.md)
  - [x] Wave 1：Journal、参数 Schema 校验、审批闸门、ask_actor 子代理、长期记忆
  - [x] Wave 2：崩溃恢复接线、计划机制（plan_set/plan_update + 时间线）、真审批 UI、
    Actor 会话持久化、结算语义对齐（ProviderStop/Interrupted）
  - [ ] Wave 3：历史版本化、定时任务、检索记忆、人格系统、插件化装载

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
