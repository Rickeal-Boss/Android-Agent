package com.rickeal.agent.core.agent

import com.rickeal.agent.core.model.StreamRepetitionDetector

// W65 D2 零行为风险迁移：自 `AgentRunner.kt` 整段搬移的三簇顶层声明（连 KDoc）。
//
//  1. 合成提醒常量族（[SYSTEM_REMINDER_PREFIX] 及其族成员）—— 合成 USER 提醒的文案壳、
//     四条提醒文案与两个阈值；
//  2. 墙钟停表算术纯函数族（[effectiveElapsedMillis] / [wallClockRemainingMillis] /
//     [childDeadlineNanos]）—— Wave 52/53/54 外提的 JVM 直测纯函数；
//  3. 轮内失败口径分流纯函数族（[loopFailurePhraseFor] / [loopFailureUserMessageFor] /
//     [loopTruncationNoticeFor]）—— W62/W63/W64 的 marker 语义分流单一落点。
//
// 纪律（与 W42 ProcessHistory / W63 AgentRunnerToolSetup 同一范式）：簇**整体搬移**、
// 逐字节不变（含 KDoc 与注释），调用点形态不变（同包顶层符号，零 import 变更），
// AgentRunner.kt 被迁处只留指针注释。可见性：常量族原为 `private`，迁出后 AgentRunner.kt
// 侧仍有调用点 ⇒ 提为 `internal`（同模块可见，**不是对外 API**，同本类既有
// `wallClockSoftEvidence` 的 internal 口径）；[SYSTEM_REMINDER_PREFIX] 仅本文件内部
// 使用，保持 `private`；三个纯函数族原本即 `internal`（JVM 单测直测口径），不变。

/* ------------------------------------------------------------ 合成提醒常量族 */

/**
 * 合成提醒的统一文本壳（复审3 §2.4 / §4-4，P3）。
 *
 * 为什么需要：下列提醒（[REPEAT_REMINDER] / [NO_TOOL_REMINDER] / [INTRA_LOOP_REMINDER] /
 * [EMPTY_ANSWER_NUDGE]）都是**合成 USER 消息**，与真实用户输入走同一条 user turn 通道。
 * 角色通道修复（bbfa3c9）之后它们不再被压进同一条 user 纯文本，而是**各自独立的 user
 * turn** —— 语义权重反而比旧的压平路径**更高**：500M 级模型会把「你的最新回复重复了先前
 * 的回复」当成真实用户在说话，于是回一句「好的，我换个角度」而不是真的换路径（下方
 * 「提醒落独立 reminder 行」处的 journal 注释早已自认「它是行为矫正不是用户说的话」，
 * 但协议层从未落地）。
 *
 * 为什么不改 role：换 `Role.SYSTEM` 会被引擎的角色门控（`roleChannelActive` 时跳过 SYSTEM）
 * 拦下 —— 提醒**根本不发送**；换 `Role.TOOL` 在文本协议下没有配对的 tool_call，多数 chat
 * template 判非法。加文本前缀是唯一**不动 native 播种口径**（`toNativeMessage` 的
 * USER→Message.user 映射）的做法。
 *
 * 已知代价：这段前缀会进 native 上下文与 journal，属有意为之；它也让「提醒」在日志与
 * 会话回放里一眼可辨。
 */
private const val SYSTEM_REMINDER_PREFIX = "[系统提醒] "

/** 命中重复时的提醒（每轮最多注入一次，且每个签名只提醒一次）。 */
internal const val REPEAT_REMINDER: String =
    SYSTEM_REMINDER_PREFIX +
        "你的最新回复重复了先前的回复。不要重复同一份摘要或同一下车点，重新检视证据，选择一个实质不同的下一步。"

/** 连续多轮零工具调用时的提醒。 */
internal const val NO_TOOL_REMINDER: String =
    SYSTEM_REMINDER_PREFIX +
        "已经连续多轮没有执行任何工具。复述计划、状态或意图都不算进展：要么调用工具去获取证据，要么给出结论并停止。"

/**
 * 同参守卫整个 run 内的提醒总次数上限：超过后不再注入（防提醒风暴，纪律对齐
 * 跨轮重复检测的「每个签名至多提醒一次」）。与 DENIAL_CIRCUIT_LIMIT 并存不冲突：
 * 熔断在审批层跳过询问（按工具名计数），本守卫在计数层提醒（按工具+参数签名计数），
 * 阈值与维度都不同。
 */
internal const val MAX_TOOL_ANOMALY_REMINDERS = 3

/**
 * 空输出提醒（合成 USER，同样带 [SYSTEM_REMINDER_PREFIX] 壳）。
 *
 * 复审3 只点了三类提醒（REPEAT / NO_TOOL / INTRA_LOOP），本条是**同类第四处**：它与那三条
 * 完全同构（合成 USER、进 working 与 journal、不是用户说的话），旧实现把文案内联在调用点，
 * 漏掉了统一处置。抽成常量是为了让「所有合成提醒都带壳」这条纪律只有一个落点，避免下次
 * 再加提醒时又漏。
 */
internal const val EMPTY_ANSWER_NUDGE: String =
    SYSTEM_REMINDER_PREFIX +
        "你上一轮没有输出任何内容。请直接给出最终答案；如果任务无法继续，请说明原因后停止。"

/** 轮内流式重复触发后的提醒（复用 pendingReminder 单槽注入纪律）。 */
internal const val INTRA_LOOP_REMINDER: String =
    SYSTEM_REMINDER_PREFIX +
        "你刚才的输出陷入重复循环，已被截断。请换一个实质不同的回答。"

/**
 * 思考预算耗尽（[StreamRepetitionDetector.THINKING_BUDGET_MARKER]）专用提醒（W62）。
 *
 * 语义与「重复循环」严格分开：**思考过长 ≠ 内容重复**。原实现两种 verdict 共用
 * [INTRA_LOOP_REMINDER]（「你刚才的输出陷入重复循环」）⇒ 模型据此**道歉**、用户看不到
 * 答案（W62 真机定案：gemma 图片问答，thinking 合法长推理被误判为循环）。本文案直陈
 * 「思考过长，请直接作答」，且在「思考流触发 + 本轮正文为空」时对症 —— 空正文情形由
 * 措辞「请直接给出最终答案」天然覆盖，**不**复用 [EMPTY_ANSWER_NUDGE]（两者语义重叠，
 * 搬进来只会加分支复杂度）。
 */
internal const val THINKING_BUDGET_REMINDER: String =
    SYSTEM_REMINDER_PREFIX +
        "你的思考过程过长，已到上限被截断。请直接给出最终答案，不要再展开推理。"

/* --------------------------------------------- 墙钟停表算术纯函数族（W52/W53/W54） */

/**
 * 有效执行时长（毫秒）= run 相对墙钟时长 − 审批挂起时长（Wave 52 B1 停表算术）。
 *
 * 抽成**文件级 `internal` 纯函数**（同 [wallClockSoftEvidence] 的口径）：停表算术必须能被
 * JVM 单测直接钉住 —— [WallClockEvidenceTest] 只断言 evidence 的**文案模板**（入参为字面量），
 * 覆盖不到「elapsed 与 paused 怎么合成」这一步；把算术外提才补得上这个观测面。
 *
 * @param elapsedMillis run 相对墙钟时长（[RunState.elapsedMillis]）。
 * @param pausedNanos 审批挂起累计（[RunState.pausedNanos]，纳秒）。
 * @return 有效执行时长（毫秒），**钳到 ≥ 0**。防御性钳制：审批挂起理论上 ≤ 墙钟（挂起发生在
 *   run 内），但纯函数不依赖该不变量 —— 越界输入一律返回 0 而非负值（负耗时会让上游
 *   「已运行 -N 秒」的文案与判据同时失真）。
 */
internal fun effectiveElapsedMillis(elapsedMillis: Long, pausedNanos: Long): Long =
    (elapsedMillis - pausedNanos / 1_000_000L).coerceAtLeast(0L)

/**
 * 墙钟**判据**的剩余量（毫秒）= (硬截止 + 审批挂起累计 − now) / 1e6（Wave 52 B1 接线，Wave 53 外提）。
 *
 * 与 [effectiveElapsedMillis] 的**分工**（勿混）：[effectiveElapsedMillis] 是 evidence / 日志文案
 * 报的「已运行多久」（墙钟 − 挂起）；本函数是**判据侧**的「距硬截止还剩多少」。二者是同一停表
 * 算术的两种等价表达（`remaining ≈ 硬预算 − 有效时长`），但代码上是**两条独立表达式** ——
 * 这正是 Wave 53 要补的接线观测面：既有 [WallClockEvidenceTest] 只钉住 evidence 用的那一条，
 * 判据侧的 `+ pausedNanos` 一旦被误删，`remaining` 会退回纯墙钟口径（长授权后下一轮**立即** HARD
 * 熔断的 W51 现象复发），而 evidence 那条测试**照样绿**。故把判据表达式外提为纯函数直接钉住。
 *
 * @param hardDeadlineNanos 硬截止绝对时刻（[RunState.hardDeadlineNanos]，nanoTime 刻度）。
 * @param pausedNanos 审批挂起累计（[RunState.pausedNanos]）；加回硬截止 = 把审批等待从预算里剔除。
 * @param nowNanos 当前时刻（`System.nanoTime()`）；显式传入以便 JVM 单测用可控时间源。
 * @return 距硬截止的剩余毫秒（可能为负 ⇒ 已越界，上游按 `<= 0` 判 HARD 熔断）。
 */
internal fun wallClockRemainingMillis(
    hardDeadlineNanos: Long,
    pausedNanos: Long,
    nowNanos: Long,
): Long = (hardDeadlineNanos + pausedNanos - nowNanos) / 1_000_000L

/**
 * 子 run 继承的绝对硬截止（Wave 54 外提）：`子墙 = 父墙 + 父 paused`。
 *
 * **父挂起只计一次**：子墙 = 父墙 + 父 paused；子自身 paused 另计（W52 交接已证不变量）。
 * 父 run 停表后，子 run 若仍拿未延长的绝对墙，就会「父 run 停过表、子 run 却按原墙判」而
 * 提前熔断（W51 现象）—— 故继承时同步加上父 run 的审批挂起累计（[RunState.pausedNanos]）。
 *
 * 为什么外提为**文件级 `internal` 纯函数**（同 [wallClockRemainingMillis] 的口径）：本 spawn
 * 点算术此前**零测试覆盖** —— 外部报告曾声称「父审批停表不向子 run 传播」，回源码却证伪
 *（调用点本就是 `state.hardDeadlineNanos + state.pausedNanos`）。外提即把该算术变成可 JVM
 * 直测的观测面，堵「照报告乱改」的回归口（见 [WallClockEvidenceTest]）。
 *
 * @param parentDeadlineNanos 父 run 的绝对硬截止（[RunState.hardDeadlineNanos]，nanoTime 刻度）。
 * @param parentPausedNanos 父 run 的审批挂起累计（[RunState.pausedNanos]）；加回父墙 = 把父 run
 *   已发生的审批等待从子 run 的预算里剔除（**只计一次**，不重复累加）。
 * @return 子 run 继承的绝对硬截止（nanoTime 刻度）。
 */
internal fun childDeadlineNanos(parentDeadlineNanos: Long, parentPausedNanos: Long): Long =
    parentDeadlineNanos + parentPausedNanos

/* --------------------------------------- 轮内失败口径分流纯函数族（W62/W63/W64） */

/**
 * 轮内截断失败的**终态口径**语义分流（W63 刀A）：给超限收尾的终止日志 /
 * [BreakerKind.StreamLoop] evidence / 轮间进度消息提供与 marker 一致的失败短语。
 *
 * W62 只分流了提醒文案（[intraLoopReminderFor]），终态收尾仍硬编码「重复循环」：
 * 思考预算耗尽的 run 被终止时，收尾日志与诊断卡 evidence 均报「重复循环」，与真
 * 循环不可区分（K-2 残留）。本函数沿用「单一落点」纪律：marker 只在此判一次，
 * 调用点（终止日志 / evidence / StreamReset 文案 / AgentLogStore.warn）不重复判
 * —— 避免口径分叉（W62 教训：同一语义多处落点只改一处）。
 *
 * @return 思考预算 marker ⇒「思考预算耗尽」（不含「重复循环」字样）；其余 marker
 *   （真重复循环）与 `null`（兜底）⇒「轮内重复循环」。
 */
internal fun loopFailurePhraseFor(marker: String?): String =
    if (marker == StreamRepetitionDetector.THINKING_BUDGET_MARKER) {
        "思考预算耗尽"
    } else {
        "轮内重复循环"
    }

/**
 * 终态失败的用户可见消息（W63 刀A，与 [loopFailurePhraseFor] 同族）。
 *
 * 与短语分设两个函数的原因：[AgentEvent.Failed] 的句式是「模型输出陷入X」/
 * 「模型X」，与日志的「连续 N 轮触发X」句式不同，塞进一个函数只会加分支。两者
 * 判别的是同一个 marker 常量，且单测钉住判别特征词互斥（思考预算耗尽 / 重复循环），
 * 不至于分叉。
 *
 * @return 思考预算 marker ⇒「模型思考预算耗尽，已停止本轮任务」；其余与 `null`
 *   （兜底）⇒「模型输出陷入重复循环，已停止本轮任务」（原句保持不变）。
 */
internal fun loopFailureUserMessageFor(marker: String?): String =
    if (marker == StreamRepetitionDetector.THINKING_BUDGET_MARKER) {
        "模型思考预算耗尽，已停止本轮任务"
    } else {
        "模型输出陷入重复循环，已停止本轮任务"
    }

/**
 * [AgentEvent.StreamReset] 截断提示文案的语义分流（W64 L-6，与 [loopFailurePhraseFor] 同族）。
 *
 * 既有拼接 `${loopFailurePhraseFor(marker)}截断（连续第 N 次）` 对思考预算 marker 产出
 * 「思考预算耗尽**截断**」——「耗尽」与「截断」语义重叠、读感生硬。本函数为该落点提供更顺
 * 的表达：思考预算 marker ⇒「思考预算已到上限，本轮输出被截断（连续第 N 次）」；其余 marker
 * （真重复循环）⇒ 沿用既有「{短语}截断（连续第 N 次）」原句（零行为变化）。
 *
 * ⚠️ **只服务 StreamReset 落点**：不改 [loopFailurePhraseFor] 的返回值 —— 后者同时服务
 * 终止日志 / evidence / 轮内日志，面向**终态**（run 结束）；本处是**非终态**（截断 + 注入
 * 提醒重试），措辞须区分，故单设落点（沿用「单一落点」纪律：marker 只在此判一次）。
 */
internal fun loopTruncationNoticeFor(marker: String?, streak: Int): String =
    if (marker == StreamRepetitionDetector.THINKING_BUDGET_MARKER) {
        "思考预算已到上限，本轮输出被截断（连续第 $streak 次）"
    } else {
        "${loopFailurePhraseFor(marker)}截断（连续第 $streak 次）"
    }
