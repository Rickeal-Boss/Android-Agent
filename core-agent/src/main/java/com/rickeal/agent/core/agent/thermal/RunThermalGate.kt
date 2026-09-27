package com.rickeal.agent.core.agent.thermal

/**
 * 热状态对 run 的**每轮决策**（Wave 30 §2.1 方案 C 裁决的接口层）。
 *
 * 为什么接口在 core-agent 而实现在 core-data：PowerManager 是 Android API，
 * core-agent 的注释纪律「JVM 可直接单测」不能被破；arch-guard 第 10 条要求
 * core-agent 不依赖 core-data / framework —— core-agent 只见本文件的纯 Kotlin
 * 决策类型，PowerManager 档位数值到 [ThermalDecision] 的映射全部留在 core-data。
 *
 * 消费点：AgentRunner 主循环轮头（RoundStarted 事件之后、墙钟检查之后、
 * RoundStarted journal 之前 —— 失败语义与墙钟一致：都是「轮头预算检查 →
 * emitBreakerFailed 收口」，先到先生效）。`AgentRequest.thermalGate = null`
 * （默认）= 零行为变化；子 run 恒 null（R7-2：子 run 由父 run 的轮头 Abort 兜底）。
 */
interface RunThermalGate {
    /**
     * 每轮主循环开始前调用一次。
     *
     * @param round 轮序号（`RunState.round`，**0 起**，首轮为 0）—— 判据可以据此
     *   区分首轮与后续轮（例：MODERATE 的轮间冷却不作用于首轮，见 core-data 的
     *   `ThermalGovernor.asGate`）。
     */
    fun beforeRound(round: Int): ThermalDecision
}

/** [RunThermalGate.beforeRound] 的三态决策。 */
sealed interface ThermalDecision {
    /** 正常继续。 */
    data object Proceed : ThermalDecision

    /**
     * 轮间冷却：AgentRunner 轮头 `delay([millis])`。delay 在 flow 内可取消 ——
     * 用户点停止立即生效，无需 NonCancellable（R7-3）。
     */
    data class Cooldown(val millis: Long) : ThermalDecision

    /**
     * 热熔断：AgentRunner 按 Failed 终态收口（C7 先走既有失败路径，C9 把
     * ThermalThrottle trip 回灌 BreakerLedger + 诊断卡报告）。[evidence] 是给
     * 用户看的事实（含热档位名），不是给模型的提示。
     */
    data class Abort(val evidence: String) : ThermalDecision
}
