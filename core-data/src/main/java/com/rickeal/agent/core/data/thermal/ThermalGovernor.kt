package com.rickeal.agent.core.data.thermal

import android.os.PowerManager
import com.rickeal.agent.core.agent.thermal.RunThermalGate
import com.rickeal.agent.core.agent.thermal.ThermalDecision
import com.rickeal.agent.core.model.AgentLogStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 热档位（core-agent 只见决策类型，不见本档位数值 —— 方案 §2.1 的层次边界）。 */
enum class ThermalTier { NONE, LIGHT, MODERATE, SEVERE, CRITICAL }

/**
 * 热状态治理器（Wave 30 §2.1）：PowerManager 热档位 → 四档策略。
 *
 * 放 core-data 的理由（方案 §2.1 方案 C）：PowerManager / ActivityManager 在
 * core-data 已有同款先例（ModelDownloader 用 DownloadManager、availableMemoryBytes
 * 用 ActivityManager）；消费者（ChatViewModel / AppContainer）也都在 core-data /
 * feature 侧；core-agent 只经 [RunThermalGate] 接口消费决策 —— arch-guard 第 10 条全绿。
 *
 * 线程模型（R7-1）：`addThermalStatusListener` 回调在主线程 —— [onThermalStatus]
 * 只做映射 + StateFlow 赋值 + 档位跃迁日志，绝不阻塞；CRITICAL 跃迁触发的
 * [releaseEngineIfIdle] 内部有 isBusy 硬闸门防 native use-after-free（AppContainer
 * 既有注释），closeAll 快速幂等 —— 按既有 onTrimMemory 同款 runCatching + 主线程
 * 容忍执行，真机发现卡顿再切 withContext（真机验收项）。
 *
 * 幂等性：`addThermalStatusListener` 重复注册会重复回调；AppContainer 进程级
 * 单例天然只注册一次。
 *
 * 生命周期：监听在 AppContainer.init 注册、**不注销** —— AppContainer 只由
 * LiquidAgentApplication.onCreate 构造一次（进程级单例，与应用进程同终），
 * PowerManager 持有的回调引用不会跨构造点泄漏，因此没有第二个注册面。注册失败
 * （个别 ROM 收紧）降级为「无热干预」并 warn，与引入本功能前的行为一致。
 *
 * 降温回落（CRITICAL → NONE）**不需要配套的「重载引擎」动作**：引擎是按需懒加载
 * 的，下一次 run 触发 engineFactory 重新装载 —— 与 onTrimMemory(TRIM_MEMORY_UI_HIDDEN)
 * 释放后回前台的路径完全同一条，用户无感（AppContainer.releaseEngineIfIdle KDoc
 * 同款结论）。反过来，CRITICAL 跃迁时若有 run 在跑，isBusy 闸门会跳过本次释放
 * （防 native use-after-free），等下一次跃迁或下一次 onTrimMemory 再试。
 *
 * @param releaseEngineIfIdle CRITICAL 跃迁时的引擎释放路径（AppContainer 传入，
 *   与 onTrimMemory 共用同一条；isBusy 硬闸门在其内部）。
 */
class ThermalGovernor(
    private val releaseEngineIfIdle: () -> Unit,
) {
    private val _tier = MutableStateFlow(ThermalTier.NONE)
    val tier: StateFlow<ThermalTier> = _tier.asStateFlow()

    /** PowerManager.addThermalStatusListener 的回调入口。 */
    fun onThermalStatus(status: Int) {
        // EMERGENCY / SHUTDOWN 比 CRITICAL 更极端，一并落 CRITICAL 档
        //（release 语义相同：立即释放引擎；再细分档位没有对应的策略分支）。
        val new = when (status) {
            PowerManager.THERMAL_STATUS_LIGHT -> ThermalTier.LIGHT
            PowerManager.THERMAL_STATUS_MODERATE -> ThermalTier.MODERATE
            PowerManager.THERMAL_STATUS_SEVERE -> ThermalTier.SEVERE
            PowerManager.THERMAL_STATUS_CRITICAL,
            PowerManager.THERMAL_STATUS_EMERGENCY,
            PowerManager.THERMAL_STATUS_SHUTDOWN,
            -> ThermalTier.CRITICAL
            // THERMAL_STATUS_NONE 与未知值（厂商扩展）都按 NONE 处理：未知值没有
            // 已校准的策略，回落到无干预比猜档位安全。
            else -> ThermalTier.NONE
        }
        // 单写者（listener 回调恒在主线程）：读-写两步不需要 CAS。
        val old = _tier.value
        _tier.value = new
        if (old == new) return
        AgentLogStore.info("热状态：$old → $new（PowerManager status=$status）")
        if (new == ThermalTier.CRITICAL) {
            runCatching { releaseEngineIfIdle() }
                .onFailure { AgentLogStore.warn("CRITICAL 释放引擎失败（${it.javaClass.simpleName}）") }
        }
    }

    /** SEVERE 拒新 run（含 CRITICAL）。只挡 ChatViewModel 主入口，子 run 不挡（R7-2）。 */
    fun canStartRun(): Boolean = tier.value < ThermalTier.SEVERE

    /** MODERATE 及以上的轮间冷却（毫秒）。轮头 Cooldown 决策的取值。 */
    fun roundCooldownMillis(): Long = if (tier.value >= ThermalTier.MODERATE) 2_000L else 0L

    /**
     * LIGHT 降档：新 run 的 maxTokens 上限（对基准减半，保底 256）。
     * 只影响 run 启动时刻的取值 —— 在跑 run 不动（方案 §2.1 四档策略表）。
     *
     * `coerceAtMost(base)`：降档只允许往小压。InferenceConfig 把 maxTokens 钳在
     * [64, 32768]（设置滑块下限 64），base < 512 时「减半再保底 256」会反超基准
     * （64 → 32 → 256），热档期反而把输出上限抬高 4 倍，与降热目标相反。
     */
    fun maxTokensCap(base: Int): Int =
        if (tier.value >= ThermalTier.LIGHT) {
            (base / 2).coerceAtLeast(256).coerceAtMost(base)
        } else {
            base
        }

    /**
     * AgentRunner 轮头消费的门视图。档位 → 决策：
     * SEVERE/CRITICAL → Abort（拒新 run 在 ChatViewModel，在跑 run 由这里兜底）；
     * MODERATE → Cooldown(2s)；LIGHT/NONE → Proceed（LIGHT 的降 maxTokens 在
     * ChatViewModel 新 run 启动时刻生效，轮头无事可做）。
     */
    fun asGate(): RunThermalGate = object : RunThermalGate {
        override fun beforeRound(round: Int): ThermalDecision {
            val current = tier.value
            return when {
                current >= ThermalTier.SEVERE -> ThermalDecision.Abort(
                    "设备热状态已达 ${current.name} 档",
                )
                current >= ThermalTier.MODERATE -> ThermalDecision.Cooldown(roundCooldownMillis())
                else -> ThermalDecision.Proceed
            }
        }
    }
}
