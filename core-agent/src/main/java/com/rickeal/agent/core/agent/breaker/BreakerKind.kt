package com.rickeal.agent.core.agent.breaker

/**
 * 物理断路器判据登记表（Wave 30，评审 §3.4 原样收编 + 三处调整，见各类目 KDoc）。
 *
 * ⚠️ 本波（C3/C4/C5/C6）只**登记 + 接线新增 5 判据**：既有 6 判据（EmptyOutput /
 * StreamLoop / PromptEcho / SameParamDeadlock / DenialCircuit / RoundBudget）的行为
 * **保持原样**（提醒 / 忽略 / 熔断各归各的既有路径），在这里登记只为统一呈现口径
 * （诊断卡 / report 的 kind 词表）—— 不改判既有行为是本波红线（方案 §四 7）。
 *
 * [userLabel] 全部是编译期常量：给用户看的一句话事实，绝不拼运行时状态。
 */
enum class BreakerKind(val severity: Severity, val userLabel: String) {
    // ── 既有 6 判据登记（本波只登记不改变其行为）──
    EmptyOutput(HARD, "模型连续多轮没有输出任何内容"),
    StreamLoop(HARD, "模型输出陷入重复循环"),
    PromptEcho(HARD, "模型在逐字复述系统提示词"),
    SameParamDeadlock(HARD, "同一个工具以完全相同的参数被反复调用"),
    DenialCircuit(HARD, "该工具已被你多次拒绝"),
    RoundBudget(HARD, "达到轮次上限"),
    // ── 新增 5 判据（本波接线）──
    /** §3.2(c)：工具调用在几个选项之间来回打转（周期距离 + 窗口塌缩双判据）。 */
    ToolCallOscillation(HARD, "工具调用在几个选项之间来回打转"),
    /** §3.2(d)：同一个工具连续执行失败（含换参），成功即清零。 */
    ToolFailureStreak(HARD, "同一个工具连续失败"),
    /**
     * §3.2(a)：墙钟预算 3min SOFT → 5min HARD。落成单一 kind（方案 §2.4 调整①）：
     * [severity] 取 HARD，但**首次 SOFT trip 只进 ledger 不中断**、HARD 再 trip 一次
     * —— 档位语义由 record 点控制（evidence 区分 3min/5min），不拆两个 kind，
     * 避免「同一预算两个名字」的口径分叉（对齐 §3.5 对 maxRounds 重复设判据的否决理由）。
     */
    WallClockBudget(HARD, "本次任务耗时超出预算"),
    /** §3.2(b)：上下文 token 消耗超出预算（接发送侧 sentTokens 累计）。只 SOFT 登记，不中断。 */
    TokenBudget(SOFT, "上下文 token 消耗超出预算"),
    /** B.2 ②：设备热状态触发熔断（B3 ThermalGovernor 接线）。 */
    ThermalThrottle(HARD, "设备热状态触发熔断"),
    ;

    /** SOFT = 只登记不中断；HARD = trip 后熔断终态。中断语义在 record 点控制，不在枚举上分支。 */
    enum class Severity { SOFT, HARD }
}

/**
 * 一次断路器触发记录。[evidence] 是给用户看的事实陈述（进诊断卡），不是给模型的提示。
 *
 * [atElapsedMillis] 是相对 run 起点的墙钟（System.nanoTime 差，[elapsedMillisSince]
 * 换算）：刻意不落绝对时刻 —— 墙钟受 NTP 跳变影响且对用户无意义，相对时长单调、可测。
 */
data class Trip(
    val kind: BreakerKind,
    val round: Int,
    val tool: String? = null,
    val argsDigest: String? = null,
    val evidence: String,
    val atElapsedMillis: Long = 0L,
)
