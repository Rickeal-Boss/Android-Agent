# 11 · Harness 蓝图 —— ZCode / Octop → LiquidAgent 移植总纲

> 分支：`harness`（自 `12f1fd1` 起）。
> 目标：把 LiquidAgent 从「能跑 Agent 循环的端侧应用」升级为**行业 SOTA 级别的 Android harness**。
> 本文是移植工作的总纲：分析结论、已落地清单、协调原则与后续波次。
> 深度侦察素材：`.recon-harness/zsrc`（ZCode 全源码）、`.recon-harness/osrc`（Octop 全源码）。

---

## 1. 两个参考系到底是什么

### 1.1 ZCode（zai-org/ZCode）

桌面/网页 AI 工作台（Electron + TypeScript monorepo）。核心资产在
`apps/zcode-cli/packages/`：

| 资产 | 位置 | 一句话 |
|---|---|---|
| **Dynamic Workflow** | `packages/dynamic-workflow` | 模型生成的 TS workflow 经完整静态分析（taint / causality / flow / phase graph）后进沙箱执行 |
| **Workflow Runtime** | `packages/dynamic-workflow-runtime` | NDJSON 桥接父进程 WorkflowEngine：Actor 创建、ask、journal、结算 |
| **Journal** | `engine/types.ts` JournalStorePort | run/actor/node/event 四级记录，崩溃后 replay 已完成节点、只重派未完成部分 |
| **Actor** | `engine/types.ts` PersonaSpec/ActorRef | 具名持久上下文子代理；名字是 amend-resume 的缓存身份键 |
| **typed ask** | `schema/` | `ask<T>()` 把 TS 类型编译成 JSON Schema 校验子代理输出，违规「路径+期望+实得」可读可修复 |
| **结构化错误** | `engine/types.ts` WorkflowErrorCode | 稳定错误码 + mismatch 明细，流程判断绝不匹配错误文本 |
| **结算语义** | `RunSettlement` | completed / failed / stopped(user|model|interrupted|superseded|provider) |
| **Git Checkpoint** | `packages/services/src/git/gitCheckpoint.ts` | 本地 git object store 快照，挂 `refs/zcode/checkpoints/<hash>/<id>` |
| **能力护栏（Caps）** | `facade/*-caps.ts` | world-read 上限（grep 2000 条 / 256KB）、report 上限（256 条 / 32KB） |

知乎技术分析（2026-09-21）的五个机制（checkpoint / Actor / Workflow Compiler /
Journal / AmendWorkflow）在源码中全部得到实证，位置如上表。

**移植判定**：Workflow Compiler 的 TS 静态分析栈（6.5k 行）**不可移植**，但其
「从程序恢复结构信息」的思想以降级形态落地（见 §3.2 的 Phase/计划机制规划）。
Journal、Actor、typed-ask 校验、结构化错误、结算语义、Caps —— **全部可移植**。

### 1.2 Octop（TencentCloud/Octop）

自托管多用户 AI 助手平台（Python 单进程，底层是 harness-agent / LangGraph）：

| 资产 | 位置 | 一句话 |
|---|---|---|
| **Agent Teams / Inbox** | `docs/agent-interop-mailbox.md` | 全局 inbox：target.call → compose_followup → source.call(source_thread_id) → on_reply；**全局单 worker 串行**（ADR 已确认） |
| **ask_agent 工具** | 同上 §6 | 默认同步阻塞；启用 team 后支持 background 入队 |
| **分段历史 v2** | `docs/versioned-history.md` | 按回合分段归档；正文按内容哈希共享存储；游标分页；前缀分叉；回合七态（active/paused/complete/partial/failed/interrupted…） |
| **工具护栏** | `docs/architecture.md` §安全 | shell 命令允许/拒绝规则 + 高风险工具显式审批 + 出工作区敏感信息脱敏 |
| **分层记忆** | harness-memory | 分层召回 + 全文检索，记忆随工作区迁移 |
| **人格系统** | `docs/personas.md` | 16 MBTI 模板 + SOUL.md 工作区文件 |
| **ACP** | `docs/acp.md` | 入站（给 IDE 提供 stdio ACP）/ 出站（委派给 OpenCode/Claude Code）双向 |
| **插件** | `plugins/README_CN.md` | bundled 种子 + 已安装插件目录，一键启停 |
| **定时任务** | CronManager | 自然语言 Cron，与 IM/控制台共用处理链路 |
| **ADR 沉淀** | `docs/adr/001/002` | 单进程无外部队列；SQLite/Postgres 双后端、三层存储不混淆 |

**移植判定**：Inbox 委派语义、工具护栏、记忆、人格、历史版本化 **可移植**；
ACP/浏览器/远程桌面/IM 通道 **不适用端侧**（无服务器、无桌面）。

---

## 2. LiquidAgent 现状基线（移植前）

`core-agent` 已有（1592 行，质量高于多数同类开荒项目）：

- 主循环 `AgentRunner`：串行 Mutex 根治引擎并发、引擎重建重试（各一次）、
  文本协议三态判定（Calls/FinalAnswer/NoProtocol 防死循环）、重复回答检测注入提醒、
  连续零工具检测、显式终止原因（ModelStopped/MaxRounds）
- 工具系统：`ToolSpec` 声明（含 dangerous/requiresConfirmation）、`ToolRegistry`
  动态启停、超时 + 输出截断、callId 收口
- 上下文：`WindowContextCompressor` 预算贪心 + 工具组安全切点 + 无损放弃；
  `sanitizeForProvider` 终检配对
- 事件模型：RoundStarted/TextDelta/ThinkingDelta/ToolCall*/MessageCommitted/
  Finished/Failed/Cancelled/Retrying

缺口（= 移植空间）：崩溃恢复、子代理、参数校验、审批流、长期记忆、
计划/阶段结构、历史版本化、定时任务、i18n、可观测性深度。

---

## 3. 已落地（Wave 1，全部在 harness 分支）

| 提交 | 内容 | 移植来源 |
|---|---|---|
| `1d82a8e` | build 工作流覆盖 harness 分支 + cache-maintenance.yml（每周巡检 + 手动 purge） | CI 治理 |
| `a0f627c` | **工具参数 Schema 校验**：按 ToolSpec.parameters 校验类型/必填/枚举，违规以「路径+期望+实得」回给模型定向修复 | ZCode `ask<T>` / Violation |
| `49c7593` | **运行日志 Journal**：JSONL 追加 run_started/round_started/message/settled；进程死亡后从已提交消息恢复；写入 best-effort 永不成为失败面 | ZCode JournalStorePort |
| `49c7593` | **工具审批闸门**：dangerous/requiresConfirmation 执行前 emit `ApprovalRequested` 并挂起等待宿主裁决，fail-closed；拒绝原因写回上下文供模型调整策略 | Octop tool_guard + ZCode 命令审批 |
| `acb3bf0` | **子代理框架 ask_actor**：markdown 定义 Actor（frontmatter+正文），串行执行、上下文按「会话×Actor」累积、防递归、父上下文走协程元素注入；runUnlocked 仅限持锁方嵌套（本地引擎 KV 重建安全性论证见注释）；内置 planner/critic/summarizer | ZCode Actor + Octop ask_agent |
| `acb3bf0` | **Agent 长期记忆**：memory.json 按标题 upsert（64 条上限），memory_write/read/delete 工具，系统提示词注入「长期记忆」节 | Octop harness-memory + ZCode project memory |
| `acb3bf0` | ToolSpec.timeoutMillisOverride 单工具超时覆盖（ask_actor 240s） | — |

### 3.1 关键设计决策（为什么这样做）

1. **端侧单引擎 → Actor 串行而非并行**。ZCode 靠子进程沙箱并行，Octop 的 Inbox
   也是「全局单 worker 串行」（ADR 已确认）。端侧没有并发推理资源，串行是正确而非妥协。
2. **嵌套 run 在本地引擎上安全**：子 run 发生在父 run 的工具阶段（父 generateStream
   已完整返回，非并发生成）；`ensureConversation` 因 conversationId 切换重建
   Conversation 并清水印，父 run 下一轮全量重发历史 → KV cache 正确重建，
   代价是一次 re-prefill，正确性无损（buildContents 水印语义保证）。
3. **Journal 恢复的是「已花的推理与工具结果」，不 replay 引擎内部状态**
   （LiteRT Conversation 无法跨进程存活）——诚实边界，与 ZCode 的差异写在注释里。
4. **审批 fail-closed**：通道缺失维持历史行为（零默认行为变化）；通道异常按拒绝；
   `autoApproveDangerous` 显式豁免优先。
5. **参数校验只拦「必然执行失败」**：未声明键放行（ignoreUnknownKeys 语义）、
   显式 null 视同缺失 —— 对齐 TextToolProtocol 三态哲学，宁可放过不可死锁。

### 3.2 冲突协调（避免两套移植互相打架）

| 潜在冲突 | 裁决 |
|---|---|
| ZCode Journal（过程记录） vs Octop harness-memory（结论沉淀） | 两个独立文件、两种生命周期：journal 单 run 只读，memory 跨会话读写；注释互指 |
| Octop 审批（同步阻塞 UI） vs ZCode 并发 workflow | 端侧串行：审批挂起的是当前 run，UI 用协程取消即可逃生（停止按钮路径已保真） |
| 子代理工具面 vs 主工具面 | 子 run 白名单显式排除 ask_actor（防递归）；危险工具在子 run 因无审批通道被拒（fail-closed） |
| 参数校验 vs 工具自校验 | 校验器只管类型/必填/枚举（执行必然失败的部分），语义校验仍归工具 |
| Journal 文件 vs 会话持久化 | journal 放 `filesDir/journal/<cid>/`，不动 ConversationRepository 的 messages.json |

---

## 4. 后续波次（按价值/风险排序）

**Wave 2 —— 结构与恢复（高价值低风险）**
1. Journal→恢复接线：ChatViewModel 检测未结算 journal，提示「从中断处继续」
2. Actor 会话持久化（复用 journal 写路径）
3. **阶段图 Phase Graph 降级移植**：plan/todo 机制（工具 `plan_set`/`plan_update`）+
   UI 时间线；审批单元从「单工具」升到「阶段」
4. **结算语义对齐**：TerminationReason 扩展 ProviderStop / Interrupted / Superseded
5. **历史版本化 Wave A**（Octop history_v2 降级）：回合快照 + 前缀分叉

**Wave 3 —— 能力扩展（中风险，需依赖决策）**
6. 定时任务（WorkManager 新依赖需过版本矩阵评审）
7. 检索记忆（FTS/向量，RAG 知识库）
8. 人格系统（SOUL.md + 模板库，接 feature-settings）
9. 插件化工具装载（ToolContributor 已有，补清单式声明 + 热启停 UI）
10. ACP 出站降级形态：委派给远程 coding agent 端点

**明确不做（端侧不适用）**：Workflow Compiler 静态分析栈、浏览器自动化、
远程桌面、IM 通道、多用户 JWT、Postgres。

---

## 5. 验证状态

- 本地无 JDK，唯一验证通道是 CI（`assembleDebug` + arch-guard）
- Wave 1 已推送 CI；⚠️ 以下两点 CI 只能验证编译，不能验证行为，留待真机：
  - 审批闸门的挂起/恢复与停止按钮交互
  - ask_actor 在本地引擎上的 KV 重建时延（长会话下可能明显）
