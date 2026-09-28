package com.rickeal.agent.core.agent.breaker

/**
 * 一次工具执行的事实账（[BreakerLedger.recordAttempt] 的入参收编）。
 * 只收「真的跑过」的调用：未注册 / 已忽略 / 被拒 / Schema 违规不执行的工具调用
 * **不算 attempt**（与 §3.2(d)「失败计数针对执行失败」的口径一致）。
 */
data class ToolAttempt(
    val tool: String,
    val argsDigest: String,
    val ok: Boolean,
    val error: String?,
    val elapsedMillis: Long,
)

/** [ToolAttempt] 按工具名聚合的诊断视图（BottleneckReport.triedTools 的数据源）。 */
data class ToolAttemptSummary(
    val tool: String,
    val calls: Int,
    val successes: Int,
    val lastError: String?,
)

/**
 * run 内断路器账本（Wave 30 §2.4，评审 §3.4 原文收编 + 两点补充）：
 * - [Trip.atMillis] 语义改为 [Trip.atElapsedMillis]（run 相对时长，nanoTime 差）；
 * - [recordAttempt] 数据源零新计算：executeWithGuard 返回的 ToolResult 已有
 *   ok / errorMessage / elapsedMillis，argsDigest 用 ToolApprovalCache.argsDigest
 *   （执行路径上审批缓存命中时已算过同款，重复计算 ~µs 级，不做缓存 —— 机械保守）。
 *
 * 线程模型：账本挂在 RunState（每 run 局部对象）上，只在 run 协程内读写，无需加锁；
 * 生命周期纪律继承 RunState（绝不能上提为 AgentRunner 字段 —— ask_actor 子 run
 * 嵌套覆写的历史坑，见 RunState 类头注释）。
 */
class BreakerLedger {

    private val _trips = mutableListOf<Trip>()
    val trips: List<Trip> get() = _trips.toList()

    private val attempts = mutableListOf<ToolAttempt>()

    /** 工具失败连击状态（tool → 连续失败计数），成功即清零。收在 ledger 内部，不外泄可变引用。 */
    private val failureStreaks = HashMap<String, Int>()

    /**
     * 登记一次断路器触发。**只记账，不做任何中断决策** —— 中断（emit Failed / return）
     * 由调用点的既有控制流完成（A1 的结构化返回值映射，不抛异常）。
     */
    fun trip(
        kind: BreakerKind,
        round: Int,
        tool: String? = null,
        argsDigest: String? = null,
        evidence: String,
        atElapsedMillis: Long = 0L,
    ): Trip {
        val t = Trip(kind, round, tool, argsDigest, evidence, atElapsedMillis)
        _trips.add(t)
        return t
    }

    /**
     * 工具执行事实入账（成败 / 错误 / 耗时）。仅 AgentRunner 的 executeWithGuard
     * 返回处一处调用 —— 未执行的工具调用（被拒 / 忽略 / 未注册 / Schema 违规）不进来。
     */
    fun recordAttempt(
        tool: String,
        argsDigest: String,
        ok: Boolean,
        error: String?,
        elapsedMillis: Long,
    ) {
        attempts.add(ToolAttempt(tool, argsDigest, ok, error, elapsedMillis))
        if (ok) {
            // 成功即清零：「连续失败」语义 —— 任何一轮成功都打断连击。
            failureStreaks.remove(tool)
        } else {
            failureStreaks.merge(tool, 1, Int::plus)
        }
    }

    /**
     * 第一条 HARD 档触发；纯 SOFT 账本返回 null。
     *
     * ⚠️ 仅表示**第一条 HARD 档 trip**，**不表示终止者** —— 3 分钟 SOFT 墙钟留痕的
     * kind 是 WallClockBudget，而该 kind 的 severity 恒为 HARD（档位语义由 record 点
     * 控制，见 BreakerKind KDoc）⇒ 它可能是第一条 HARD 却从未终止 run。终止者用 [terminator]。
     */
    fun firstHard(): Trip? = _trips.firstOrNull { it.kind.severity == BreakerKind.Severity.HARD }

    /**
     * 实际终止 run 的那条 trip = **最后一条** HARD 档 trip。
     *
     * ⚠️ 不能用 [firstHard]：3 分钟 SOFT 墙钟留痕的 kind 是 WallClockBudget，而该 kind 的
     * severity 恒为 HARD（档位语义由 record 点控制，见 BreakerKind KDoc）⇒ firstHard 可能
     * 返回一条从未终止 run 的 trip。熔断终态总是「先 trip 再 emit」，故取最后一条。
     */
    fun terminator(): Trip? = _trips.lastOrNull { it.kind.severity == BreakerKind.Severity.HARD }

    /** 指定工具的当前失败连击数（0 = 无连击）。 */
    fun failureStreak(tool: String): Int = failureStreaks[tool] ?: 0

    /** 按工具聚合的尝试账（诊断卡「尝试清单」）。 */
    fun attemptSummary(): List<ToolAttemptSummary> =
        attempts.groupBy { it.tool }.map { (tool, list) ->
            ToolAttemptSummary(
                tool = tool,
                calls = list.size,
                successes = list.count { it.ok },
                lastError = list.lastOrNull { !it.ok }?.error,
            )
        }

    /** 最近一次失败 attempt 的错误摘要（Blocker 兜底归因 MissingInput 的 {missing} 占位）。 */
    fun lastFailureError(): String? = attempts.lastOrNull { !it.ok }?.error
}

/**
 * run 相对时长换算（Wave 30 §2.7：nanoTime 差 → millis）。纯函数可单测。
 * nanoTime 是单调刻度非墙钟，减法比较在 292 年内安全；**禁止**拿它与 Date 互转。
 */
fun elapsedMillisSince(startedElapsedNanos: Long): Long =
    (System.nanoTime() - startedElapsedNanos) / 1_000_000
