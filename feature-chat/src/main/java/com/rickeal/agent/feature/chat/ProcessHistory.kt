package com.rickeal.agent.feature.chat

import com.rickeal.agent.core.agent.journal.AgentRunJournal
import com.rickeal.agent.core.model.AgentLogStore
import com.rickeal.agent.core.model.ChatMessage
import com.rickeal.agent.core.model.Role
import com.rickeal.agent.core.model.TokenEstimator
import java.io.File

/**
 * 「可见历史 + journal 过程消息」合并的**纯核心**（Wave 40 C3 外提，供 JVM 单测）。
 *
 * 去重口径**逐行照抄** [ChatViewModel.onRecover] 的既有判据：
 * 只剔「role == MODEL 且 `role.name + "|" + text` 已在可见历史」的消息 ——
 * journal 的最终 MODEL 答案会同时落在会话文件（commitAssistant 落库）与
 * journal（message 行）里，按 role+text 去重（id 在两侧各自生成、永远对不上）。
 * 只剔 MODEL：TOOL / 中间 toolCall 消息永远不会出现在会话文件里，不存在误剔；
 * USER 同理不剔（journal 的 user_input 行本就不进 committedMessagesSync，这里
 * 的口径是防御性的 —— 万一未来过程消息里出现 USER 行，也不该被可见历史吞掉）。
 *
 * 顺序取舍（如实记录）：过程消息**统一排在可见历史之后**，不按轮次插回原位 ——
 * 第一版照抄 onRecover 的拼接语义；引擎按 role 播种时不要求严格交替
 * （USER / TOOL 都映射 user，播种时已处理）。调用方负责把本轮任务输入从
 * visible 里滤掉、拼在返回值末尾，保证「新问题在旧工具残骸之后」。
 */
internal fun mergeProcessIntoVisible(
    visible: List<ChatMessage>,
    process: List<ChatMessage>,
): List<ChatMessage> {
    val visibleKeys = visible.mapTo(HashSet()) { it.role.name + "|" + it.text }
    val proc = process.filterNot { m ->
        m.role == Role.MODEL && (m.role.name + "|" + m.text) in visibleKeys
    }
    return visible + proc
}

/**
 * 过程消息回灌的 token 预算（Wave 41 P2-1）—— **与 AgentRunner 压缩预算同源**。
 *
 * ## 公式来源（逐字镜像，不在此硬编码任何阈值）
 *
 * AgentRunner 轮头的压缩预算（AgentRunner.kt，「预算必须显式预留输出额度」处）：
 *
 * ```
 * budget = ((contextLength - maxTokens).coerceAtLeast(512) * policy.compressThreshold).toInt()
 * ```
 *
 * 本函数取**同一算式**得到 `base`，再减去可见历史与本次输入的 token 估算占用，
 * 余量即允许回灌的过程消息预算：
 *
 *  - `coerceAtLeast(512)`：极端配置（maxTokens ≥ contextLength）下保底预算，
 *    与 AgentRunner 同理 —— 压缩器仍能工作而不是把预算算成 0/负数；
 *  - 预留输出额度：litertlm 的 KV cache = 输入 + 输出总和（Wave 28），不预留的话
 *    长回答会越过 KV 顶直接硬报错；
 *  - 减 [visible] 与 [inputTokens]：调用点（onSend / onSendFrom）传给
 *    [historyWithProcess] 的 visible **不含本次新输入**（userMessage 先滤、拼在
 *    过程消息之后），故可见历史占用经 [visible] 减、本次输入占用单独经
 *    [inputTokens] 预减（调用点传 `TokenEstimator.estimate(userMessage)`，与
 *    visible 同构实算）—— 硬不变量为「visible + 回灌过程 + 本次输入 ≤ 压缩预算」。
 *    不预减本次输入的话，预算吃满时 engineHistory 总量会超 base，AgentRunner
 *    轮头判据每轮必触发压缩（全量 re-prefill），开窗的收益被完全吐回（复审 P1-1）；
 *  - SYSTEM 段（buildSystemInstruction）不计入本预算，由引擎压缩器兜底；
 *  - `coerceAtLeast(0)`：可见历史已超压缩预算时余量为负，钳到 0 —— 过程消息预算
 *    归零，但 [historyWithProcess] 仍按「最新 run 保底」语义运行。
 *
 * `compressThreshold` 必须取自调用点真实下发进 [AgentRequest] 的 [AgentPolicy]
 * 实例（两处调用点已把 AgentPolicy 构造上提到 engineHistory 计算之前），保证本
 * 预算与引擎侧压缩门禁永远同一口径。纯函数，JVM 可测。
 */
internal fun processTokenBudget(
    contextLength: Int,
    maxTokens: Int,
    visible: List<ChatMessage>,
    inputTokens: Int,
    compressThreshold: Float,
): Int {
    val base = ((contextLength - maxTokens).coerceAtLeast(512) * compressThreshold).toInt()
    return (base - TokenEstimator.estimate(visible) - inputTokens).coerceAtLeast(0)
}

/**
 * 组装发送给引擎的完整 history（Wave 40 C3）：可见历史 + 本会话 journal 过程消息
 * （Wave 41 P2-1 起：按 [processTokenBudget] 的 token 预算**开窗**，不再无上限全量回灌）。
 *
 * ## 为什么需要它（上下文丢失根因）
 *
 * 会话文件只存 USER + 最终 MODEL 答案（[ChatViewModel.commitAssistant]），TOOL /
 * 中间 toolCall 消息只进 journal。重开会话 / 进程重启后引擎重建，initialMessages
 * 只播问答对 —— 模型丢失全部工具执行上下文（当场续聊不丢：引擎不重建；
 * 重启 / 切会话后丢：重建播种缩水）。修复方向：发送时让引擎拿到过程历史。
 *
 * ## token 预算开窗（Wave 41 P2-1：新者优先 + 最新 run 保底 + run 粒度原子裁剪）
 *
 * 预算由调用方按 [processTokenBudget] 算出后传入（与 AgentRunner 压缩预算同源）。
 * 本函数按 **run 粒度**裁剪：
 *  - runId 字典序**降序**（= 时间序降序，runId = `run_` + currentTimeMillis，
 *    排序口径实证见下节）逐 run 读取，按 [TokenEstimator] 累计（Long 防溢出 ——
 *    estimate 返回 Int，Wave 5 教训）；
 *  - 累计超预算即停止读更旧 run：**旧 run 文件根本不读** —— 这既是 token 治理
 *    也是 IO 治理，读取成本不再随会话年龄线性涨；
 *  - 只按 run 边界裁剪，绝不切开单个 run：M(toolCalls) 与 TOOL 结果必须成对存活；
 *  - **最新 run 无条件保底**：即使它单独超预算也完整保留 —— 最新的工具残骸是当前
 *    任务最相关的上下文。预算 = 0 也只意味着「不再读任何更旧 run」，不吞最新 run；
 *  - 拼回时反转恢复时间序；`mergeProcessIntoVisible` 的去重口径原样保留 ——
 *    开窗发生在 merge 之前的**读取层**；
 *  - 发生裁剪时打一条 INFO 日志（对齐 AgentRunner 压缩日志纪律：只在真的发生
 *    决策时记一条）。文案「过程消息开窗：保留 N/M 个 run（预算 X tok）」是
 *    **真机验收关键字，不要改动措辞**；其中 M 是「本次已读到的非空 run 数（含
 *    触发 break 的那个）」，不含更旧未读 run 与空 run —— 验收时勿把它当
 *    「会话全部 run 数」解读。
 *
 * ## 为什么结果**不进** uiState.messages
 *
 * 过程消息只属于**引擎上下文**，不改变可见历史：UI 不重复渲染过程气泡
 * （工具轨迹另有 toolTraces 呈现），会话文件也不落第二份（journal 已是权威）。
 * 所以本函数只服务 AgentRequest.history 的组装，返回值不回写任何 UI 状态。
 *
 * ## run 目录结构与排序依据（实证）
 *
 * journal 目录 = `<journalRoot>/<conversationId>/`（[AgentRunJournal.open] 的
 * runDir 约定），每个 run 一个文件，runId = `run_` + `System.currentTimeMillis()`
 * （ChatViewModel 两处 open 调用点的实参）。排序依据 = **文件名内的时间戳**：
 * 同为 13 位毫秒前缀，字典序即时间序（同 run 内行序由 seq 保证，跨 run 由
 * 文件名保证）。
 *
 * ## 归档形态（哪些文件读、哪些跳过）
 *
 *  - `<runId>.jsonl` —— 活跃 / 已 settled 未归档（**读**）；
 *  - `<runId>.dismissed.jsonl` —— 用户丢弃 / 未完成归档（markDismissed 与
 *    archiveOtherUnsettled 的产物，**读** —— 里面的工具执行都是真实发生过的）。
 *    两者都以 `.jsonl` 结尾，`open(id.removeSuffix(".jsonl"))` 恰好还原文件名
 *    （dismissed 文件去后缀后 id 带 `.dismissed`，open 拼回去一字不差）；
 *  - `<runId>.jsonl.archived` —— archiveAsSettled 的产物，但该写入路径在
 *    Wave 30（history_v2 判死）已摘除调用，且 `.jsonl.archived` 结尾无法经
 *    open()（恒追加 `.jsonl`）还原文件名 —— **跳过**（存量只可能来自极旧版本）；
 *  - `<runId>.jsonl.dismissed` —— Wave2 旧命名，同上**跳过**。
 *
 * ## 损坏与读失败的纪律
 *
 * journal 损坏行由 [AgentRunJournal.committedMessagesSync] 的既有 decode 纪律
 * （runCatching + mapNotNull）静默跳过；目录不存在 / listFiles 失败按「无过程
 * 消息」处理，本函数绝不抛异常、绝不挡发送（journal 永远不是失败源）。
 */
internal fun historyWithProcess(
    visible: List<ChatMessage>,
    journalRoot: File,
    conversationId: String,
    budget: Int,
): List<ChatMessage> {
    val runDir = File(journalRoot, conversationId)
    val runIds = runDir.listFiles { f -> f.isFile && f.name.endsWith(".jsonl") }
        ?.map { it.name.removeSuffix(".jsonl") }
        ?.sorted()
        ?: return visible
    if (runIds.isEmpty()) return visible
    // 新者优先：字典序降序 = 时间序降序（run_ + currentTimeMillis，见类头实证）。
    var usedTokens = 0L
    val keptRuns = mutableListOf<List<ChatMessage>>()
    var totalRuns = 0
    for (runId in runIds.asReversed()) {
        val messages = AgentRunJournal.open(runDir, runId).committedMessagesSync()
        if (messages.isEmpty()) continue
        totalRuns++
        val runTokens = messages.sumOf { TokenEstimator.estimate(it).toLong() }
        // totalRuns == 1 即最新 run：无条件保底，不参与预算判定。
        if (totalRuns > 1 && usedTokens + runTokens > budget) break
        usedTokens += runTokens
        keptRuns.add(messages)
    }
    if (keptRuns.size < totalRuns) {
        // 只在真的发生决策时记一条（对齐 AgentRunner 压缩日志纪律）。这条文案同时
        // 是真机验收关键字，不要改动措辞。
        AgentLogStore.info(
            "过程消息开窗：保留 ${keptRuns.size}/$totalRuns 个 run（预算 $budget tok）",
        )
    }
    // keptRuns 是新→旧序，反转恢复时间序后交给 merge（去重口径不变，开窗只发生在
    // 读取层）。
    return mergeProcessIntoVisible(visible, keptRuns.asReversed().flatten())
}
