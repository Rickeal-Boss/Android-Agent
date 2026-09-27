# Wave 26 交接文档 — Operit / Operit2 深度挖掘与首批落地

> 写于 2026-09-27。基线：`harness-improve` 起点 `4b910e4`（Wave 25 双绿）。
> 本波产出：**外部参考仓库深度挖掘报告 + 分波次落地路线图 + 首批落地（3 个提交）**。

---

## 0. 本波任务与范围

用户要求：深度挖取 `AAswordman/Operit` 与 `AAswordman/Operit2` 的实现思路，为我们的项目做
全方位改进与功能增加，**并注意对方的许可证边界，不要抄袭**。

### 0.1 ⛔ 许可证边界（硬前提，先于一切技术判断）

| 仓库 | 语言/形态 | Stars | 许可证 |
|---|---|---|---|
| **Operit** | Kotlin/Compose Android 端侧 Agent | 8178 | **LGPL-3.0-only** |
| **Operit2** | Rust 跨平台 Agent（第二代） | 358 | **AGPL-3.0** |
| **本仓 LiquidAgent** | Kotlin/Compose Android | — | **Apache-2.0** |

**结论：三者互不兼容，且方向是单向的 —— copyleft 代码进入 Apache-2.0 项目会迫使整体
许可转换**（LGPL 会污染并入的文件及其衍生；AGPL 更强，连网络服务提供都算分发）。

因此本波执行的红线：

1. **零代码搬运**：不复制、不改写式搬运、不粘贴任何源码片段 / 提示词原文 / 配置 / 资源 /
   schema 文本。
2. **只提取不受著作权保护的客体**：架构思路、设计模式、交互范式、工程方法论、能力清单、
   协议思想、边界原则。著作权保护表达，不保护思想与方法。
3. **本文档及团队报告中不出现参考仓库的源码块**。所有机制均以我们自己的话重新表述。
4. 凡「只有靠复制才能实现」的能力，一律写「不做」。

参考源码仅作**本地只读**研究，落在 `.recon-harness/operit`、`.recon-harness/operit2` ——
注意这两个目录位于**工作区根（git 仓库之外）**，不在 `Android-Agent` 仓库树内，
因此不存在被提交的风险。

---

## 1. 挖掘发现（七个维度）

### 1.1 规模定位（先建立量级感）

| 维度 | Operit | 本仓 |
|---|---|---|
| 工具系统代码 | **89,149 行**（`core/tools/`，含无障碍/ADB/Root/浏览器四通道） | `core-agent` 全部 **4,683 行** |
| 提示词工程 | **9,063 行**（`core/config/`，其中工具说明 5,992 行） | 集中在 `AgentRunner` 的常量段 |
| 全部 Kotlin | 约 1,163 个文件（`app/`） | 9 模块共约 **33,381 行** |
| 引擎层 | `llm/llama` + `llm/mnn` 双引擎，Kotlin 侧仅 **1,941 行**（重活在 C++） | `core-engine` 1,389 行（litertlm 单引擎） |
| 测试 | `app/src/test` 52 + `androidTest` 37 + `ci/test` 6 | `core-model` 34 用例（Wave 24/26） |

**判断：追平其规模既不现实也无必要。** 我们的正确路线是「**安全模型先做对，能力精选补齐**」。

### 1.2 能力模型（最高价值发现，本波已落地）

Operit2 的公开架构文档给出的**四层能力模型**（自外向内逐层收紧）：

```
0. App Runtime Sandbox     —— 虚拟机/容器/系统账号/Android 应用沙盒（与软件内部策略无关）
1. Host Authorization      —— 操作系统真正授予的能力（Android 普通 / Shizuku / root）
2. AI Capability Limit     —— 用户给 AI 选择的能力档位（ReadOnly / WorkspaceWrite / Full）
3. User Tool Approval      —— 用户是否批准 AI 发起的**具体**这一次工具调用
```

铁律：**上层没有的能力，下层变不出来；下层只能继续限制，不能绕过上层。**

其中对我们最有价值的两个设计判断：

- **「用户授权」与「AI 能力档位」必须解耦**。逐工具的危险标记回答「这工具危不危险」，
  而用户真正关心的是「我允许 AI 做什么」—— 这是两件事。此前我们只有前者。
- **工具效果（effect）必须是「本次调用」的结果，不能是注册期静态元数据**。同一个工具
  不同参数效果不同（剪贴板 get 读 / set 写；终端查询读 / 执行写）。

### 1.3 权限引导的声明式模型

Host 注册「onboarding requirement」清单（id / 标题 / 说明 / 关联能力 / 状态
Satisfied|Missing|Unavailable / 动作 RuntimePermission|OpenSystemSettings|HostManaged|None），
UI 只做**渲染 + 触发**，**最终状态以 Host 复检为准**，页面不承诺「授权后一定拥有某能力」。

对 Android 的直接映射：`ACCESSIBILITY`（需跳系统设置，无法运行时弹窗申请）、
`SYSTEM_ALERT_WINDOW`（`canDrawOverlays`）、`POST_NOTIFICATIONS`、
`MANAGE_EXTERNAL_STORAGE`（Android 11+ 已收紧，SAF 是唯一稳路）。

### 1.4 Agent 主循环与工具治理

- **工具描述与原生通道二选一**：其系统提示词组装里，当走原生 tool-call API 时，工具清单段
  会被**置空**（因为原生通道自带工具定义）。这直接回答了我们 Wave 24 挂账的
  `nativeToolChannel` 评估 —— **启用原生工具通道能同时解决「工具描述挤占提示词预算」与
  「工具描述被模型当模板续写」两个问题**。
- **提示词 = 模板 + 动态段 + 组装钩子**：模板常量 + 运行态填充（扩展段 / 工作区规则段 /
  工具清单段）+ 可注册/注销的「组装前后」钩子。提示词因此可被插件修改，且组装过程可观测。
- **专用提示词集中管理**：总结 / 标题 / 翻译 / 描述生成 / UI 自动化 / 记忆提取各有独立入口，
  且每个都带语言开关（其做法是中英双语硬编码切换；我们单语，但「集中 + 可测」值得学）。
- **工具权限三态**（v1 时期）：ALLOW / ASK / FORBID + 全局 master + 逐工具覆盖，默认 ASK，
  审批弹窗有超时（超时按拒绝）；部分工具可注册「操作描述生成器」，把本次参数渲染成人话
  再让用户确认 —— 这一点比单纯弹「是否允许 file_write」对用户友好得多。
- **严格模式**：首选权限档不可用时**不静默降级**，返回结构化原因（「当前 ROOT 不可用：…」），
  让模型和用户都知道「为什么做不到」。

### 1.5 扩展生态（四来源归一）

不是四层并列，而是「**一个契约 + 四个来源**」：脚本包 / ToolPkg / MCP / Skill 全部归一化为
统一的包抽象（名 / 工具表 / 版本 / 分类 / 环境变量 / 默认启用）后注册。

- **三层版本分离**：清单格式版本 / **API 版本**（宿主契约，严格 `x.y.z`，加载阶段比对宿主
  支持集，不匹配指名报错）/ 包自身版本。
- **包不声明「我要什么权限」**，而是用「条件」声明「什么能力下暴露哪些工具」—— 授权仍由
  宿主在运行时统一判定。这条对我们的启示：**扩展不得自证可信，闸门必须由宿主掌握**。
- **激活三态**：安装 → 启用 → **会话级激活**（才真正注册进当前对话）。这是**提示词预算的
  核心手段** —— 装了很多包不等于每轮都要把它们的工具描述塞进提示词。
- **Skill 不带工具**，是目录化知识资源，靠可见性开关被按需检索 —— 本质是**上下文预算手段**
  （「先检索再读片段」）。

### 1.6 记忆与上下文

- 记忆单元粒度是「文档块 + 图谱节点」：有记忆空间（多库隔离）、标题去重、相似标题合并、
  文件夹分组、导出模型、**检索调试信息**（检索过程可观测，这一点很值得学）。
- 支持向量 embedding（含维度使用统计、云端 embedding 配置），但**不是唯一路径** ——
  其检索是混合的（关键词 + 语义 + 关系 + 时间）。
- 自动保存候选（提取后先进候选再确认）与自动归类。

### 1.7 工程治理（我们的短板）

- **CI 检查脚本自带单测**（`ci/test/` 6 个，用标准库 unittest 测门禁逻辑）。我们的
  `arch-guard.sh` 是 bash，**无测试** —— 这是可直接借鉴的一条。
- **增量归责**：「只阻断 candidate 新增的问题」「历史问题只显示计数，不归责给未触碰它们的
  PR」。避免历史债阻塞新提交。
- **快速 lane 门控昂贵 lane**：先跑完所有廉价检查并收集全部诊断，失败就不启动耗时阶段。
- **权限最小化**：PR 工作流只有 `contents: read`，不读 secret，不上传构建产物；可信构建
  单独工作流。
- **诚实声明未知**：文档里明确写「这些归档目前还没有内容 hash，在取得并审计真实归档前
  不记录推测值」「某上游模型元数据未声明许可证，发布前仍需完成审计」——**拒绝编造**。
  这条与我们本波正在处理的许可证边界问题直接呼应。

### 1.8 架构演进教训（Operit → Operit2，稀缺素材）

其官方重构计划自述的病症：**「当前系统不是一棵树，而是多个宿主手工组装出来的一张共享引用
图；初始化/关闭顺序由宿主代码决定」**。一代把 UI 状态、持久化模型、原生接入三类职责装进
同一个 app 模块，最终只能靠第二代整体重写拆开。

**我们应当自检的四条**：

1. 每个模块是否只装一类职责？（我们的 `core-agent` 目前只依赖 `core-model` + `core-engine`，
   干净 —— 已用 arch-guard 第 11 项把它钉住。）
2. `AppContainer` 的组装顺序与关闭顺序，是否只有一处知道？（值得补一份显式顺序注释。）
3. 落盘格式有没有版本字段？（其做法：一律带 `formatVersion`，未知版本明确报错。）
4. 崩溃后是从检查点续跑还是重来？（我们的 `AgentRunJournal` 已做到 —— 这一点**我们领先**。）

另：其边界约束主要靠**文档约定 + PR 级人工边界评审**，未发现自动化脚本；我们的
`arch-guard.sh`（11 项）在这点上更强，**别退坡**。

---

## 2. 本波已落地（3 个提交，`4b910e4..39393f3`）

### `3a0cd5e` feat(model)：AI 能力档位与工具效果声明

- `AiCapabilityMode { READ_ONLY, WORKSPACE_WRITE, FULL }`，**默认 WORKSPACE_WRITE**
  （零行为回归是引入档位的前提）；`requiresApprovalFor(effect)` 为唯一判定入口。
- `ToolEffect { READ, WRITE }` + `ToolSpec.effect`，**默认 WRITE（fail-closed）**：
  新增工具忘了声明只会更保守，不会被静默当作只读放行。
- 10 个用例锁住**判定真值表**（3 档 × 2 效果）与默认值方向，另锁枚举名（落盘契约）。

### `ae89160` feat(agent)：档位进入执行路径 + 动态效果 + 子代理继承

- `AgentRunner` 审批闸门新增**第三类正交判据**（静态标志 / 参数门控 / **档位**），
  命中走审批而非硬拒绝 —— 只读档是默认收窄，不是牢笼。
- `EffectAwareTool.effectFor(argsJson)`：效果按**本次调用**声明，纯函数 + fail-closed。
  `ClipboardTool` 首个实现（get 读 / set 写）。
- 12 个内置工具逐个标注 effect（READ 5 / WRITE 7）。
- **安全修复**：`AskSubagentTool.ParentContext` 补 `capabilityMode` 并继承。
  不传就是真实逃逸 —— 子 run 无审批通道，档位是唯一闸门；本仓 `plan_set` / `ask_actor`
  正是「WRITE 但无危险标记」的漏网对象。

### `39393f3` feat(settings)：设置项 + 会话下发 + arch-guard 第 11 项

- 设置页新增「AI 能力」卡（玻璃分段三档），副文案按档位切换，**「完整权限」明确写清
  它不是提权**。
- `SettingsRepository`：存枚举名，**解析失败回退 WORKSPACE_WRITE 而非 FULL**（读坏绝不能
  变成静默放宽）。`ChatViewModel` 3 处请求全量下发。
- `arch-guard.sh` 第 11 项：core-agent 不依赖 core-data/core-design（已实测为绿）。

**规模**：16 文件改动 + 2 新文件，+254/-2 行。

---

## 3. 路线图（后续波次）

### Wave 27（建议下一波）：设备能力与安全模型续建 —— 无新权限，CI 全可验

1. `CapabilityRegistry` 能力位抽象（接口在 core-agent，实现在 app）：
   位集合 `ACCESSIBILITY / SHIZUKU_ADB / ROOT / VIRTUAL_DISPLAY / OVERLAY /
   NOTIFICATION_LISTENER / APP_MANAGE`，每项带状态与「申请动作」；
   快照同时喂 UI 与系统提示词（让模型知道「为什么做不到」）。
2. `CapabilityGatedTool`：`requires` 能力集 + `STRICT | FALLBACK`，**默认 STRICT**
   （不静默降级，返回结构化原因）。
3. 审批三态化：布尔 → `ALLOW / ASK / FORBID` + 逐工具覆盖（保留现有审批缓存做「本次会话允许」）。
4. 操作审计：`{时间, 工具, 档位, 效果, 参数摘要, 决策, 耗时}` 落盘。
5. 路径归属判定结构化：`SandboxedFileTool` 目前用 canonical 前缀字符串比对，
   换成 `Path.relativize` 的结构化判定（抗符号链接 / 大小写 / 尾分隔符边角）。
6. `arch-guard` 增「CI 门禁脚本单测」与「落盘格式带 formatVersion」检查项。

### Wave 28：轻量终端（真机验证）

`shell_exec`（`/system/bin/sh -c`，参数 command / timeout / cwd；默认 15s，超时 destroy 并
返回部分输出，头尾截断）+ 审批门 + 档位门。**不做 PTY、不内置 Ubuntu/PRoot**（体积与复杂度
不可接受，其 v1 也是把完整终端做成独立 APK 的）。

### Wave 29：上下文预算与 token 可视化

我们已有 `TokenUsage` / `TokenEstimator` / `ContextCompressor`，缺**展示层**：
每条消息的 token 统计、上下文占用条、压缩触发可见化。

### Wave 30：扩展点（进程内 Kotlin 工具贡献者）

`ToolContributor` 接口**已存在但零调用点**（声明了从未接线）。接线成本低：装配收口在
`AppContainer`，新增 `origin` 字段供 UI 分组与溯源，**未知来源默认禁用**（现在注册即生效），
补按贡献者批量注销。契约已就位、零新依赖、纯 JVM 可测。

### 挂账（需用户裁决或真机）

- **原生工具通道**（`litertlm` 的 `supportsFunctionCalling()`）：若启用，工具描述不必进
  系统提示词 —— 这是「回显问题」的终解方向，但真机未验证，且我们 Wave 24 的角色通道修复
  已把回显压住。建议在真机复测后再评估。
- THIN 0.21 底色裁决、跟随手势竞争（Wave 25 已 defer）仍挂账。

---

## 4. 明确不做（附技术理由，避免重复调研）

| 项 | 理由 |
|---|---|
| 嵌入式 JS 引擎（QuickJS 类） | 需 NDK/CMake 原生工具链 + 仅 arm64；CI 无原生构建能力；本仓禁令 |
| 可视化工作流画布 | 与现有 `plan` / `ask_actor` 语义重叠，画布成本与当前规模不匹配 |
| 统一市场 / 包分发 | 需后端与审核体系，与端侧纯本地承诺冲突 |
| 本地 STDIO MCP | Android 应用进程无 `exec`，无 npx/uvx 可用 —— 死路 |
| 远程 HTTP MCP | 技术上可行，但会**打破现有「无外发通道」的 I/O 边界承诺**（DefaultTools 顶部明确声明），须先过安全评审，且本仓禁网络栈（arch-guard 第 5 项） |
| 向量库 / 云端 embedding | 新依赖 + 端侧模型上下文有限，收益不足；混合检索可先用关键词 + 时间衰减 |
| 内置 Ubuntu / PRoot 终端 | 体积与复杂度不可接受 |
| 内置浏览器 Agent | 需 WebView 自动化 + 网络栈，与端侧收敛冲突 |
| 无障碍 / Shizuku / Root 自动化 | 权限风险最高、CI 不可验、真机独占 —— 排在模式门与三态审批就位之后，且需用户明确裁决 |
| 跨平台 / 多节点 / Space / 设备间同步 | 我们是单设备 Android 项目，无此需求 |

---

## 5. 真机验收清单（CI 查不出）

1. 设置页「AI 能力」三档切换后**立即生效**（下一次发送即按新档位判定），重启后保持。
2. 选「只读」后：`file_write` / `memory_write` / `clipboard set` 应弹授权卡；
   `file_read` / `current_time` / `calculator` / `clipboard get` **不应**弹卡。
3. **档位逃逸回归验证**：只读档下让模型「用 ask_actor 帮你写个文件」——子代理路径
   也应被拦住（这是本波修的那条逃逸）。
4. 「完整权限」档下行为应与引入档位前完全一致（零回归验证）。
5. 默认档（工作区读写）下不出现任何新增弹卡。

---

## 6. 环境与工具沉淀

- `_ci-tools/gitclone.sh`（新增）：外部仓库浅克隆助手，PAT 单一来源取自 `ghapi.sh`，
  自动禁用会静默挂起的 GCM credential helper。
- 已知坑：`--filter=blob:none --sparse` 克隆后，按需 fetch blob 会走 schannel 吊销检查
  （`CRYPT_E_NO_REVOCATION_CHECK`）→ 需在仓库内 `git config http.sslVerify false`；
  全量 `--depth 1` 克隆不受影响（92 MB / 3 分钟）。
- 参考源码位置：`.recon-harness/operit`、`.recon-harness/operit2`（**本地只读，勿提交**）。
