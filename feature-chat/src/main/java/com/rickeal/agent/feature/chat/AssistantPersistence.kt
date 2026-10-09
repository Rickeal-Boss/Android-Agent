package com.rickeal.agent.feature.chat

import com.rickeal.agent.core.agent.TerminationReason
import com.rickeal.agent.core.agent.breaker.BottleneckReport
import com.rickeal.agent.core.agent.breaker.BreakerKind
import com.rickeal.agent.core.model.CriterionHit
import com.rickeal.agent.core.model.CriterionSeverity
import com.rickeal.agent.core.model.ModelHealthCriteria

/**
 * 「助手回答落库」的 **run 级纯状态**（Wave 46 外提，供 JVM 单测；生产路径
 * [ChatRunCoordinator] 真实调用本文件，测试覆盖的是真实逻辑而非镜像）。
 *
 * ## 背景（为什么需要它）
 *
 * 回归 commit `7ec83af` 把 [com.rickeal.agent.core.agent.AgentEvent.MessageCommitted]
 * 分支从 `commit(event.message)`（渲染 + 落库）改成**只渲染**，注释称「落库统一交给
 * `Finished`」。但 `Finished` 走的 `commitAssistant` 的文本去重判据
 * 「最后一条消息 role == MODEL 且 text 相同」在 `MessageCommitted` 刚渲染后**必然命中
 * ⇒ return ⇒ 落库被跳过**。发射顺序恒定（`MessageCommitted` → 主循环 break →
 * `emitFinished` → `Finished`）⇒ **必现**，模型回复永不落盘（P0 数据丢失）。
 *
 * ## 责任边界（Wave 46 方案 A，一句话）
 *
 * > **正常终态（ModelStopped）：由 `MessageCommitted` 落库那条「富消息」（`usage` /
 * > `finishReason` / `modelRef` / `thinking` 全保），`Finished` 只做收尾、按 id 跳过已落库
 * > 的提交；无 `MessageCommitted` 的终态（MaxRounds）：由 `Finished` 落库 text 版；
 * > 取消路径（`Cancelled` / `onStop`）：维持 text 版落库，用 run 级文本去重挡住竞态。**
 *
 * ## 两条去重判据（各服务什么）
 *
 * 1. **id 判据**（[committedMessageId]，服务**正常终态**）：`MessageCommitted` 落库时记下
 *    富消息的 id（`AgentRunner` 生成）；`Finished` 到达时若 `messages.lastOrNull()?.id`
 *    正是该 id，说明本 run 已落库 ⇒ 跳过。id 精确，不依赖「谁排在最后」的文本巧合。
 * 2. **run 级文本判据**（[lastCommittedText]，服务**取消竞态**）：`onStop` 与
 *    `AgentEvent.Cancelled` 都走 `commitAssistant`、文本相同，谁先到不确定 ⇒ 先到者落库
 *    并记文本，后到者命中该判据跳过。**这是取消竞态的唯一防线，不可删。**
 *
 * ## ⚠️ 为何两条判据都必须随 run 起点清零
 *
 * 若不清零，`committedMessageId` / `lastCommittedText` 会跨 run 存活：run1 正常
 * （`committedMessageId = x1`），run2 若**无新 USER 消息**或 `_uiState` 被恢复路径改写，
 * `Finished` 的 `messages.lastOrNull()` 可能仍是 `x1` ⇒ 误跳 ⇒ **静默丢回答**。
 * 清零是硬不变量，靠 `ChatRunCoordinator.collectRunWithPerfWindow`（三条 run 路径
 * `onSend` / `onSendFrom` / `onRecover` 的**唯一共享入口**，每 run 恰调一次）单点保证，
 * 外加 `ChatViewModel.onNewConversation`（经 `ChatRunCoordinator.resetForNewConversation`）。
 *
 * **⛔ 严禁把清零放进 `resetStreaming` / `resetStreamingText`**：它们被 `Finished` /
 * `Cancelled` **在决策之后**调用，且是 10 处散点 —— 放这里既脆弱又语义错位。
 *
 * ## 语义边界（保留既有约定）
 *
 * 只看**本 run**、不做全局去重：用户完全可能连着两轮拿到同样的回答。方案 A 下每 run 各自由
 * `MessageCommitted` 落库（各自 id 不同），故**连续两轮相同回答不会被吞**。
 */
internal data class AssistantPersistenceState(
    /** 本 run 内 `MessageCommitted` 落库的那条消息 id（`AgentRunner` 生成）。新 run 起点清空。 */
    val committedMessageId: String? = null,
    /** 本 run 内 `commitAssistant` 已落库的助手文本（取消竞态去重：`onStop` vs `Cancelled`）。新 run 起点清空。 */
    val lastCommittedText: String? = null,
)

/** run 起点清零（唯一正确清零点，见类头 KDoc）。 */
internal fun AssistantPersistenceState.beginRun() = AssistantPersistenceState()

/** `MessageCommitted` 落库富消息后调用：记住富消息 id（服务 id 判据）。 */
internal fun AssistantPersistenceState.onMessageCommitted(messageId: String) =
    copy(committedMessageId = messageId)

/**
 * `Finished` / `Cancelled` / `onStop` 落库前的决策：`true` = 应当落库。
 *
 * 判据与 `ChatRunCoordinator.commitAssistant` **逐字同源**：
 *  `(1)` 本 run 已由 `MessageCommitted` 落库该条（富消息）→ 不重复落（防双气泡 / 双记录）；
 *  `(2)` 本 run 已落过同样文本（取消竞态，先到者生效）→ 不重复落。
 *
 * @param lastMessageId 当前 `messages.lastOrNull()?.id`（调用方传入，本函数不碰 UI 状态）。
 * @param text 本次待落库的助手文本。
 */
internal fun AssistantPersistenceState.shouldCommitAssistant(lastMessageId: String?, text: String): Boolean =
    !(committedMessageId != null && lastMessageId == committedMessageId) && lastCommittedText != text

/** `commitAssistant` 落库后调用：记住已落库文本（服务文本判据）。 */
internal fun AssistantPersistenceState.onCommitted(text: String) = copy(lastCommittedText = text)

/**
 * 熔断终态「已见输出是否应保留」的判据（Wave 47 项2，纯函数，可 JVM 单测）。
 *
 * ## 背景
 *
 * 熔断（`AgentEvent.Failed`）时 UI 会 `resetStreamingText()` 清掉流式缓冲；但**预算 / 外部型**
 * 熔断（墙钟 / 热 / 振荡 / 失败连击 / 生成超时）发生时，用户**已经看到**的正文不该跟着消失
 * （真机 r5：文本被 `StreamReset` 清掉，但用户已看到 ⇒ 数据丢失）。本判据决定「要不要把
 * UI 侧 run 级救援缓冲 `ChatRunCoordinator.salvageText` 落库」。
 *
 * ## 判据（三级，全部用既有字段，不新造枚举）
 *
 * `应保留 = terminatedBy == BreakerTripped`
 *         ∧ 终止者 kind ∈ {WallClockBudget, ThermalThrottle, ToolCallOscillation,
 *                          ToolFailureStreak, GenerationTimeout}
 *         ∧ 待保留文本非空
 *         ∧ **正文未命中 [ModelHealthCriteria] 的 HARD 判据**（Wave 49 R-E）
 *
 * - 排除 `StreamLoop` / `EmptyOutput` 是**语义正确**的：它们的输出是「被判定的乱文 / 空」，
 *   `resetStreamingText()` 清掉是设计意图（见 `AgentEvent.StreamReset` KDoc）。
 * - 真失败（`terminatedBy != BreakerTripped`，如引擎加载 / 重载 / 生成失败）不保留：那些
 *   路径没有「用户已见的有效输出」语义。
 * - 终止者取 `report.tripped` 里**最后一个 HARD** —— `WallClockBudget` 首次 SOFT trip 只进
 *   ledger 不中断，HARD 再 trip 一次才终止（见 `BreakerKind.WallClockBudget` KDoc）。
 *
 * ## ⚠️ W52 B1 起的耗时口径（Wave 54 补注，勿按旧口径对账）
 *
 * `WallClockBudget` 的耗时口径 = **有效执行时长**（墙钟 − 审批挂起 `RunState.pausedNanos`，
 * 见 `wallClockRemainingMillis`（core-agent 文件级纯函数））。故「审批等待导致的墙钟熔断」已消失、
 * 「真耗尽」的判定边界随之收紧 —— 对账 evidence 与 breaker 记录须用此口径。
 *
 * ## R-E（Wave 49）：为什么还要一道**内容级**闸门
 *
 * 「熔断类型是外部型」**不等于**「正文干净」。回显垃圾 / 保留 token / 循环退化可能**未达**
 * 内容型熔断阈值（`StreamLoop` / `EmptyOutput`），却被外部型熔断（超时 / 墙钟 / 热）收口
 * ⇒ 旧判据会把**已被 `StreamReset` 清掉的垃圾**落进用户历史。
 *
 * 故落库前对正文跑 [ModelHealthCriteria] 的 **HARD** 判据（[salvageDegradationHits]），
 * 命中即**放弃落库**。**选「放弃」而非「标注」**：
 *  1. 现象是「垃圾进历史」，标注只是给垃圾贴标签、垃圾仍在，放弃才消除现象；
 *  2. 标注要动 `ChatMessage` 持久化 schema 或污染 `text` 前缀（= 展示行为变更），与
 *     「UI 可见标签改动挂 W50」的纪律冲突；放弃零 schema / 零 text 改动；
 *  3. 与既有语义同源：内容型熔断被排除的官方理由是「输出是被判定的乱文，清掉是设计意图」
 *     —— 本闸门只是把同一原则从「熔断类型」下沉到「正文内容」；
 *  4. 代价可控：HARD 判据是**确定性证据**（保留 token / 24+ 单字符 run / 周期复读 / 空 /
 *     detector 循环），正常散文与代码误报面小；即便误报，退化为「用户已见半截输出不进历史」
 *     = Wave 47 之前的既有行为，且调用点**留诊断日志**可回溯（不静默丢弃）。
 *
 * ⚠️ 只看 **HARD**：SOFT（如非白名单通道标记）不拦 —— 软判据「可疑但非决定性」，拿它丢
 * 用户已见正文会放大误报成本（与 [com.rickeal.agent.core.model.ModelHealthVerdict.DEGRADED]
 * 只降级不判死同源）。
 *
 * @param terminatedBy `AgentEvent.Failed.terminatedBy`（既有真失败路径恒 null）。
 * @param report `AgentEvent.Failed.report` 诊断卡（含 tripped 清单）；null = 无归因数据。
 * @param salvageText UI 侧 run 级救援缓冲（用户已见的正文）。
 */
internal fun shouldSalvageOutput(
    terminatedBy: TerminationReason?,
    report: BottleneckReport?,
    salvageText: String,
): Boolean {
    if (terminatedBy != TerminationReason.BreakerTripped) return false
    if (salvageText.isBlank()) return false
    val terminator = report?.tripped?.lastOrNull { it.kind.severity == BreakerKind.Severity.HARD }?.kind
    if (terminator !in SALVAGEABLE_BREAKER_KINDS) return false
    // R-E（Wave 49）：内容级闸门 —— 熔断类型可救援不代表正文干净（见上方 KDoc）。
    if (salvageDegradationHits(salvageText).isNotEmpty()) return false
    return true
}

/**
 * R-E（Wave 49）：熔断救援正文的**退化命中**（只取 [CriterionSeverity.HARD]；空 = 未退化）。
 *
 * 与 [shouldSalvageOutput] 的闸门**同源**，另供调用点记诊断（`ChatRunCoordinator` 的
 * `AgentEvent.Failed` 分支）—— 避免「放弃落库」静默丢弃用户已见正文。
 */
internal fun salvageDegradationHits(text: String): List<CriterionHit> =
    ModelHealthCriteria.evaluate(text).filter { it.severity == CriterionSeverity.HARD }

/** 应保留已见输出的熔断判据（预算 / 外部型；**不含**内容型 `StreamLoop` / `EmptyOutput`；
 *  另有 R-E 内容级闸门见 [shouldSalvageOutput]）。 */
private val SALVAGEABLE_BREAKER_KINDS = setOf(
    BreakerKind.WallClockBudget,
    BreakerKind.ThermalThrottle,
    BreakerKind.ToolCallOscillation,
    BreakerKind.ToolFailureStreak,
    BreakerKind.GenerationTimeout,
)
