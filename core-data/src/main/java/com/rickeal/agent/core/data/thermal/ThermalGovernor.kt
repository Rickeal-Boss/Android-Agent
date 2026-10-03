package com.rickeal.agent.core.data.thermal

import android.os.PowerManager
import com.rickeal.agent.core.agent.thermal.RunThermalGate
import com.rickeal.agent.core.agent.thermal.ThermalDecision
import com.rickeal.agent.core.model.AgentLogStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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
 * 同款结论）。
 *
 * ⚠️ CRITICAL 跃迁时若有 run 在跑，isBusy 硬闸门会跳过本次释放（防 native
 * use-after-free）—— Wave 31 流2 起，这次**被跳过的释放由 [releaseDeferred] 补上**：
 * 观察 [isBusy]，在引擎转闲（!busy）且档位仍为 CRITICAL 时补释放一次。此前该场景
 * 是「设备持续 CRITICAL → 不再有跃迁回调 → 释放永远不发生」的保护失效点。
 * [isBusy] == null（未接线）时 releaseDeferred 恒 false，[onThermalStatus] 行为与
 * 引入本机制前**逐字节一致**。
 *
 * **Wave 43 熔断计量更换：PowerManager 档位 → 电池温度**（2026-10-01 真机实测裁决）。
 * 实测证据（OPPO PDRM00 / 骁龙 8s Gen3）：① SEVERE 与可用性严重脱钩 —— NONE→SEVERE
 * 仅 15s、90s 后回落，反复振荡，电池 35℃ 即报 SEVERE；② SEVERE 瞬间 SoC 结温 83℃
 * （NPU 推理发热在芯片内部，外部散热压得住外壳压不住结温）。根因：PowerManager 档位
 * 反映的是 **SoC 结温**，而结温阈值是厂商各自调教的 —— **跨设备完全不可比**，挂在
 * SEVERE 上熔断会让 NPU 推理设备的长任务永远跑不完（每轮轮头必熔断，已实证）。
 * Wave 43 裁决（产品决策）：
 *  - **应用层熔断主判据 = 电池温度 ≥ [Companion.BATTERY_FUSE_CELSIUS]℃**：跨设备
 *    可比、贴近用户可感知（外壳）温度、贴近锂电安全线（~45℃）。每次决策点经
 *    [batteryTempTenths]（ACTION_BATTERY_CHANGED sticky broadcast，同步、零阻塞）
 *    实时读取。SoC 结温保护**让还给 OS 热框架**（系统自然限频，应用不重复保护）。
 *  - **CRITICAL 及以上保留 Abort**：OS 判定真正紧急时的兜底不撤（引擎释放路径原样）。
 *  - **SEVERE 从 Abort 降为轮间冷却**（[Companion.SEVERE_COOLDOWN_MILLIS]，首轮豁免
 *    同 MODERATE）：档位降级为「降速信号」而非「死刑」。
 *  - **[batteryTempTenths] 未接线（null）= Wave 30 旧行为逐字节保留**（SEVERE+ 仍
 *    Abort）：退化安全 —— 判据换新不强制所有构造点同步升级。
 *
 * @param releaseEngineIfIdle CRITICAL 跃迁时的引擎释放路径（AppContainer 传入，
 *   与 onTrimMemory 共用同一条；isBusy 硬闸门在其内部）。
 * @param isBusy 全应用唯一的「引擎忙」真值源（AppContainer.agentRunner.isBusy）。
 *   null = 不启用补释放（与引入本机制前的行为逐字节一致）。
 * @param scope 观察 [isBusy] 的协程作用域（应用级）。null = 不观察。
 * @param batteryTempTenths 电池温度读取源（十分之一℃，即 BatteryManager 的
 *   EXTRA_TEMPERATURE 口径；返回 Int.MIN_VALUE = 读取失败，熔断不触发）。null =
 *   未接线，判定全部回落 Wave 30 旧行为（SEVERE+ Abort）。
 */
class ThermalGovernor(
    private val releaseEngineIfIdle: () -> Unit,
    private val isBusy: StateFlow<Boolean>? = null,
    private val scope: CoroutineScope? = null,
    private val batteryTempTenths: (() -> Int)? = null,
) {
    private val _tier = MutableStateFlow(ThermalTier.NONE)
    val tier: StateFlow<ThermalTier> = _tier.asStateFlow()

    /**
     * 「CRITICAL 释放被 isBusy 闸门跳过、待补释放」标志（Wave 31 流2）。
     *
     * 置位：CRITICAL 跃迁且此刻引擎忙（`isBusy?.value == true`）。
     * 复位：① 观察者看到引擎转闲且档位仍为 CRITICAL → 尝试补释放，**补释放后复查
     * isBusy**，只有确认本次释放没有被内部硬闸门跳过（仍不忙）才复位（若调用窗口内
     * 新 run 起来导致释放被跳过，保留标志，下一次 busy=false 再补 —— 否则该次回调被
     * 消费后档位仍 CRITICAL、`onThermalStatus` 因 old==new 早退不再回调，补释放永不
     * 发生）；② 降温回落（new < CRITICAL）→ 复位（引擎按需懒加载，无需再补释放）。
     *
     * 跨线程：listener 回调（主线程）写、观察协程（应用级 scope）读写 —— @Volatile
     * 保证可见性。`isBusy == null`（未接线）时恒 false，机制整体不激活。
     */
    @Volatile
    private var releaseDeferred = false

    init {
        // 仅当 isBusy 与 scope 都接线时才观察（两者任一为 null ⇒ 不启动协程、
        // releaseDeferred 恒 false、行为与引入本机制前逐字节一致）。
        val observedBusy = isBusy
        val observerScope = scope
        if (observedBusy != null && observerScope != null) {
            observerScope.launch {
                observedBusy.collect { busy ->
                    // 只在「引擎转闲 + 此前被跳过 + 档位仍是 CRITICAL」三者同时成立时动作；
                    // 其余情况零开销（无日志、无副作用）。
                    if (!busy && releaseDeferred && _tier.value == ThermalTier.CRITICAL) {
                        runCatching { releaseEngineIfIdle() }
                            .onFailure {
                                AgentLogStore.warn("热 CRITICAL 补释放引擎失败（${it.javaClass.simpleName}）")
                            }
                        // ⚠️ 只在「调用后仍不忙」时清位。releaseEngineIfIdle 内部有 isBusy
                        // 硬闸门（防 native use-after-free，不能动）：若在本次调用窗口内新
                        // run 起来（busy 回 true），释放会被闸门跳过。此时若清位，「run 结束
                        // → busy=false」这唯一一次回调已被本次消费掉，而档位仍 CRITICAL
                        // （onThermalStatus 的 old == new 早退不再回调）⇒ 补释放永不发生，
                        // 正是本波要修的失效点。保留标志则下一次 busy=false 会再补一次。
                        if (!observedBusy.value) releaseDeferred = false
                    }
                }
            }
        }
    }

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
            // 双判据（tier + isBusy），**不依赖 releaseEngineIfIdle() 的返回值** ——
            // 后者的语义是「是否至少删掉一个 target」，不表达「被 isBusy 闸门跳过」。
            // isBusy == null ⇒ releaseDeferred 恒 false ⇒ 行为与今天一致。
            releaseDeferred = isBusy?.value == true
            runCatching { releaseEngineIfIdle() }
                .onFailure { AgentLogStore.warn("CRITICAL 释放引擎失败（${it.javaClass.simpleName}）") }
        } else if (new < ThermalTier.CRITICAL) {
            // 降温回落：引擎按需懒加载，无需补释放；撤销待补标志。
            releaseDeferred = false
        }
    }

    /**
     * 电池温度熔断判据（Wave 43）。接线后每次决策点实时读取：
     * 返回 tripped 与否；[Companion.BATTERY_FUSE_TENTHS] = 44.9℃（十分之一℃ 口径）。
     * 未接线（null）或读取失败（Int.MIN_VALUE）恒 false —— 熔断退回档位判据。
     */
    private fun batteryFuseTripped(): Boolean {
        val source = batteryTempTenths ?: return false
        return source() >= Companion.BATTERY_FUSE_TENTHS
    }

    /**
     * 电池熔断的 evidence 文案（给用户看的事实，含实时温度）。
     *
     * Wave 48 F2：`tenths` 由调用方**传入**（判据已读过一次），避免与判据在同一决策点内
     * **二次读取** `batteryTempTenths()` —— 毫秒窗口内温度在 44.9℃ 边界抖动会让
     * 「tripped=true 但 evidence 显示 <44.9℃」自相矛盾。
     */
    private fun batteryFuseEvidence(tenths: Int): String =
        "电池温度 ${tenths / 10.0}℃ 已达 ${Companion.BATTERY_FUSE_CELSIUS}℃ 保护线"

    /**
     * 拒新 run 判据（Wave 43 重定义）：
     *  - 接线 [batteryTempTenths]：电池温度熔断 **或** 档位 CRITICAL+ 拒（SEVERE 放行
     *    —— 降级为冷却信号后，热设备上新 run 由轮头冷却兜底）；
     *  - 未接线（null）：Wave 30 旧行为逐字节保留（SEVERE+ 拒）。
     */
    fun canStartRun(): Boolean {
        if (batteryTempTenths != null) {
            return !batteryFuseTripped() && tier.value < ThermalTier.CRITICAL
        }
        return tier.value < ThermalTier.SEVERE
    }

    /**
     * 拒新 run 的用户文案（Wave 43）：null = 放行；非 null = 拒绝理由（含实时事实）。
     * ChatViewModel 的热闸文案统一从这里出 —— 电池熔断与档位熔断各说各的事实，
     * 不再笼统报档位名（电池熔断时档位可能只是 NONE，报档位反而误导）。
     */
    fun heatBlockReason(): String? {
        if (batteryTempTenths != null) {
            // Wave 48 F2：本决策点**只读一次** batteryTempTenths，判据与 evidence 共用同一 tenths
            // —— 否则同一决策点内两次 invoke 可能取到不同值，产出「tripped 但显示未达线」的矛盾文案。
            val tenths = batteryTempTenths?.invoke() ?: Companion.BATTERY_FUSE_TENTHS
            if (tenths >= Companion.BATTERY_FUSE_TENTHS) {
                return "${batteryFuseEvidence(tenths)}，请等待设备降温后再试"
            }
            if (tier.value >= ThermalTier.CRITICAL) {
                return "设备热状态已达 ${tier.value.name} 档，请等待设备降温后再试"
            }
            return null
        }
        if (tier.value >= ThermalTier.SEVERE) {
            return "设备过热保护中（${tier.value.name} 档），请等待设备降温后再试"
        }
        return null
    }

    /** MODERATE 及以上的轮间冷却（毫秒）。轮头 Cooldown 决策的取值（首轮不取，见 [asGate]）。 */
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
     * AgentRunner 轮头消费的门视图。Wave 43 映射（接线 [batteryTempTenths] 时）：
     * 电池温度 ≥ 44.9℃ → Abort（主判据，优先于档位）；CRITICAL+ → Abort（OS 紧急
     * 兜底）；SEVERE → Cooldown(10s)（**首轮豁免**，与 MODERATE 同理由 —— 见下）；
     * MODERATE → Cooldown(2s)（首轮豁免）；LIGHT/NONE → Proceed。
     *
     * 未接线（null）= Wave 30 旧行为逐字节保留：SEVERE/CRITICAL → Abort（拒新 run
     * 在 ChatViewModel，在跑 run 由这里兜底）；MODERATE → Cooldown(2s)，首轮除外。
     *
     * 首轮不冷却的理由：「轮间」冷却的定义是两轮之间 —— run 刚起来还没产生热量，
     * 白等 2s 只拖慢首字；而按 maxRounds 累计（8 轮 = 14s、20 轮 = 38s）是实打实
     * 吃掉墙钟硬预算（5min）的份额，换不到任何降温收益。（SEVERE 的 10s 同理：
     * 首轮豁免让热设备立即开跑，若真过热，电池温度会随推理上升并在轮头被主判据熔断。）
     */
    fun asGate(): RunThermalGate = object : RunThermalGate {
        override fun beforeRound(round: Int): ThermalDecision {
            // 电池温度熔断（Wave 43）：主判据，优先于档位 —— 档位在部分 ROM 上跨设备
            // 不可比（SEVERE ≠ 可用性），电池温度是唯一可比口径。
            if (batteryTempTenths != null) {
                if (batteryFuseTripped()) return ThermalDecision.Abort(batteryFuseEvidence())
            }
            val current = tier.value
            return when {
                current >= ThermalTier.CRITICAL -> ThermalDecision.Abort(
                    "设备热状态已达 ${current.name} 档",
                )
                // 未接线 = Wave 30 旧行为：SEVERE+ 直接 Abort（退化安全）。
                batteryTempTenths == null && current >= ThermalTier.SEVERE -> ThermalDecision.Abort(
                    "设备热状态已达 ${current.name} 档",
                )
                // round 由 AgentRunner 的 RunState.round 传入（0 起，首轮为 0）。
                current >= ThermalTier.SEVERE && round > 0 ->
                    ThermalDecision.Cooldown(Companion.SEVERE_COOLDOWN_MILLIS)
                current >= ThermalTier.MODERATE && round > 0 -> ThermalDecision.Cooldown(roundCooldownMillis())
                else -> ThermalDecision.Proceed
            }
        }
    }

    companion object {
        /** 电池温度熔断线：44.9℃（十分之一℃ 口径 449）。Wave 43 产品裁决值。 */
        const val BATTERY_FUSE_CELSIUS = "44.9"
        const val BATTERY_FUSE_TENTHS = 449

        /** SEVERE 档轮间冷却（Wave 43：档位从熔断降级为降速信号后的新冷却时长）。 */
        const val SEVERE_COOLDOWN_MILLIS = 10_000L
    }
}
