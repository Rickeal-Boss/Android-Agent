package com.rickeal.agent.core.agent.breaker

/**
 * 诊断卡卡点归因（评审 §3.4 的 Blocker 枚举原样收编）。
 *
 * [template] 是编译期常量，占位符 {tools} / {missing} / {rounds} / {elapsed} 由
 * [buildBottleneckReport] **单趟扫描**填值 —— **禁止任何模型调用、禁止运行时
 * 拼 LLM 语句**（诊断卡的内容必须可复现、可测试）。
 */
enum class Blocker(val title: String, val template: String) {
    PermissionDenied(
        "工具使用被拒绝",
        "关键工具调用被多次拒绝。建议：换一种不需要该工具的做法，或直接向用户说明需要的授权。",
    ),
    ToolUnavailable(
        "工具不可用",
        "模型调用了当前不可用的工具（当前可用：{tools}）。建议：从可用工具中重新选择，或直接用文字说明做不到。",
    ),
    ModelDegraded(
        "模型输出退化",
        "模型输出退化（空输出 / 复述提示词 / 重复循环 / 同参死锁 / 调用打转）。建议：把任务拆小、换一种问法，或重启会话后重试。",
    ),
    BudgetExhausted(
        "任务预算耗尽",
        "本次任务已运行 {rounds} 轮、耗时 {elapsed} 秒，触达预算判据。建议：把任务拆成多个小任务分次完成。",
    ),
    EngineFailure(
        "引擎故障",
        "引擎加载或生成失败导致任务中断。建议：检查模型文件与设备资源后重试。",
    ),
    MissingInput(
        "缺少必要信息",
        "任务缺少继续所需的信息（最近失败：{missing}）。建议：向用户补充说明缺少什么，或换一种获取方式。",
    ),
}

/** 「未注册工具」报错的特征前缀（与 AgentRunner.emitUnregisteredTool 的两态文案对应；改文案必须同步此处）。 */
private val TOOL_UNAVAILABLE_CLUES = listOf("未注册的工具：", "不能直接调用工具")

/** 输出退化族（方案 §2.5 归因表第 3 行）。ToolFailureStreak（换参瞎试不收敛）与
 *  ToolCallOscillation（输出打转）同族 —— 都是「模型不会换路径」的退化形态。 */
private val MODEL_DEGRADED_KINDS =
    setOf(
        BreakerKind.EmptyOutput, BreakerKind.StreamLoop, BreakerKind.PromptEcho,
        BreakerKind.SameParamDeadlock, BreakerKind.ToolCallOscillation,
        BreakerKind.ToolFailureStreak,
    )

/** 预算族（方案 §2.5 归因表第 4 行）。 */
private val BUDGET_KINDS =
    setOf(
        BreakerKind.WallClockBudget, BreakerKind.TokenBudget,
        BreakerKind.RoundBudget, BreakerKind.ThermalThrottle,
    )

/**
 * 卡点归因 = 优先级 if 链（方案 §2.5 归因表，纯函数可单测）：
 * DenialCircuit trip → PermissionDenied；未注册工具线索 → ToolUnavailable；
 * 输出退化族 trip → ModelDegraded；预算族 trip → BudgetExhausted；
 * 引擎故障（cause != null）→ EngineFailure；兜底 → MissingInput。
 */
fun resolveBlocker(
    trips: List<Trip>,
    engineCause: Throwable?,
    lastToolError: String?,
): Blocker = when {
    trips.any { it.kind == BreakerKind.DenialCircuit } -> Blocker.PermissionDenied
    lastToolError != null && TOOL_UNAVAILABLE_CLUES.any { lastToolError.contains(it) } ->
        Blocker.ToolUnavailable
    trips.any { it.kind in MODEL_DEGRADED_KINDS } -> Blocker.ModelDegraded
    trips.any { it.kind in BUDGET_KINDS } -> Blocker.BudgetExhausted
    engineCause != null -> Blocker.EngineFailure
    else -> Blocker.MissingInput
}

/**
 * BottleneckReport：熔断 / 轮次耗尽时随 AgentEvent 交付给 UI 的诊断卡数据
 * （Wave 30 §2.4/§2.5）。全部字段来自既有事实（trip 账 / attempt 账 / journal），
 * 装配是纯函数，绝不再问模型。
 */
data class BottleneckReport(
    val task: String,
    val rounds: Int,
    val elapsedMillis: Long,
    val triedTools: List<ToolAttemptSummary>,
    val tripped: List<Trip>,
    val blocker: Blocker,
    val suggestions: List<String>,
)

/** 模板占位符（单趟扫描用）。改动占位符名必须同步 [Blocker] 各 template 与其 KDoc。 */
private val BLOCKER_PLACEHOLDERS = Regex("\\{(?:tools|missing|rounds|elapsed)\\}")

/** [missing] 最长 char 数 —— 工具报错是自由文本，超长会淹没诊断卡。 */
private const val MISSING_MAX_CHARS = 80

/**
 * 装配诊断卡（纯函数，可 JVM 单测）。
 *
 * 与方案 §2.5 原签名的差异申报：原签名 `buildBottleneckReport(state: RunState, journal)` —
 * RunState 是 AgentRunner 的 private 嵌套类，进不了 breaker 包。故拆成「本纯函数收编
 * 全部装配逻辑 + AgentRunner 内 3 行胶水从 state/journal 提取原语」（C6 落地），
 * 归因与渲染逻辑的可测性不损失。
 *
 * @param task 任务原文（胶水侧：journal.readUserInputSync 优先，journal null 时
 *   state.finalText.ifBlank { "（任务原文不可用）" }）
 * @param ledger run 的断路器账本（trip 账 + attempt 账的唯一定位来源）
 * @param engineCause 引擎加载 / 生成失败的异常（两条既有 Failed 路径携带；熔断路径为 null）
 * @param registeredToolNames 当前可用工具面（ToolUnavailable 的 {tools} 渲染）
 */
fun buildBottleneckReport(
    task: String,
    rounds: Int,
    elapsedMillis: Long,
    ledger: BreakerLedger,
    registeredToolNames: Set<String>,
    engineCause: Throwable? = null,
): BottleneckReport {
    val lastToolError = ledger.lastFailureError()
    val blocker = resolveBlocker(ledger.trips, engineCause, lastToolError)
    val tools = registeredToolNames.joinToString("、").ifBlank { "（无）" }
    val missing = lastToolError?.take(MISSING_MAX_CHARS) ?: "未知阻塞（无失败工具记录）"
    // 单趟扫描：占位符只认模板自带的那些。链式 replace 会让先填进去的运行时数据
    // （尤其 {missing} 的工具报错）再被后续 replace 扫一遍 —— 报错里若自带
    // "{rounds}" 字面量就会被二次展开。运行时数据只能填坑，不得参与模板解析。
    val suggestion = BLOCKER_PLACEHOLDERS.replace(blocker.template) {
        when (it.value) {
            "{tools}" -> tools
            "{missing}" -> missing
            "{rounds}" -> rounds.toString()
            else -> (elapsedMillis / 1000).toString()
        }
    }
    return BottleneckReport(
        task = task,
        rounds = rounds,
        elapsedMillis = elapsedMillis,
        triedTools = ledger.attemptSummary(),
        tripped = ledger.trips,
        blocker = blocker,
        suggestions = listOf(suggestion),
    )
}

/**
 * 文本渲染出口（评审 §3.4 分节文本版）。UI 本波「文本渲染即达标」（方案 §2.5：
 * 内容完整优先于形态，可展开诊断卡属加分项不在本波范围）。
 */
fun BottleneckReport.render(): String = buildString {
    appendLine("【任务诊断】")
    appendLine("任务：$task")
    appendLine("轮次：$rounds｜耗时：${elapsedMillis / 1000} 秒")
    if (triedTools.isEmpty()) {
        appendLine("尝试过的工具：（无工具调用记录）")
    } else {
        appendLine(
            "尝试过的工具：" + triedTools.joinToString("、") {
                "${it.tool} ×${it.calls}（成功 ${it.successes}）"
            },
        )
    }
    if (tripped.isEmpty()) {
        appendLine("熔断记录：（无）")
    } else {
        appendLine("熔断记录：")
        tripped.forEach { appendLine("- [${it.kind.name}] ${it.evidence}") }
    }
    appendLine("卡点：${blocker.title}")
    suggestions.forEach { appendLine("建议：$it") }
}
