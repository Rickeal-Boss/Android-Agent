package com.rickeal.agent.core.data.thermal

import com.rickeal.agent.core.agent.thermal.ThermalDecision
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// PowerManager.THERMAL_STATUS_*（API 29+）的数值。测试侧用字面量，避免单测
// 直接引用 android.os.PowerManager（与被测代码里的常量值对应关系由
// `七档映射` 用例守住：一旦平台改值，那条用例会红）。
private const val STATUS_NONE = 0
private const val STATUS_LIGHT = 1
private const val STATUS_MODERATE = 2
private const val STATUS_SEVERE = 3
private const val STATUS_CRITICAL = 4
private const val STATUS_EMERGENCY = 5
private const val STATUS_SHUTDOWN = 6
/** 厂商扩展/未来新增值：必须回落 NONE，不得猜档。 */
private const val STATUS_UNKNOWN = 99

/**
 * [ThermalGovernor] 的档位映射与四档策略判据（Wave 30 §2.1 热治理）。
 *
 * 为什么能跑在 JVM 上：被测代码只用了 `PowerManager.THERMAL_STATUS_*` 这几个
 * `static final int`（Kotlin 编译期按常量内联，不会去加载 android.jar 里的类），
 * 其余是 StateFlow + 纯判定；日志走 AgentLogStore（core-model，纯 Kotlin）。
 * 所以这里不需要 Robolectric / returnDefaultValues。
 *
 * 钉住的是四条最容易在后续改动里被悄悄改坏的语义：
 * 1. **未知档位回落 NONE**（厂商扩展值不猜档 —— 猜档比无干预危险）；
 * 2. **降档只往小压**（maxTokensCap 不得反超基准，否则热档期反而放大输出）；
 * 3. **CRITICAL 释放只在跃迁时触发一次**（重复释放 = 与 onTrimMemory 抢同一条
 *    释放路径，幂等但会刷日志；漏触发才是真问题）；
 * 4. **首轮不冷却**（MODERATE 的 2s 是「轮间」冷却 —— 首轮 round==0 必须放行，
 *    否则 maxRounds 越大越白吃掉墙钟硬预算）。
 */
class ThermalGovernorTest {

    private class Harness(release: () -> Unit = {}) {
        var releases = 0
        // 具名实参：Wave 31 流2 给构造函数新增了 isBusy / scope 两个默认参数后，
        // releaseEngineIfIdle 不再是最后一个参数，尾随 lambda 语法失效（会去绑 scope），
        // 故必须具名。isBusy / scope 保持 null = 不启用补释放（旧行为）。
        val governor = ThermalGovernor(releaseEngineIfIdle = { releases++; release() })
        fun on(status: Int) = governor.onThermalStatus(status)
    }

    /**
     * Wave 43 电池温度源接线版 Harness。[tenths] 可变 —— 用例内可模拟推理过程中
     * 温度爬升（读数是每次决策点实时取的，不是构造时快照）。
     */
    private class BatteryHarness(release: () -> Unit = {}, tenths: Int = 350) {
        var releases = 0
        var tenths: Int = tenths
        val governor = ThermalGovernor(
            releaseEngineIfIdle = { releases++; release() },
            batteryTempTenths = { this@BatteryHarness.tenths },
        )
        fun on(status: Int) = governor.onThermalStatus(status)
    }

    // ── 档位映射 ─────────────────────────────────────────────────────────────

    @Test
    fun `七档映射：NONE 到 SHUTDOWN 全覆盖`() {
        assertEquals(ThermalTier.NONE, Harness().apply { on(STATUS_NONE) }.governor.tier.value)
        assertEquals(ThermalTier.LIGHT, Harness().apply { on(STATUS_LIGHT) }.governor.tier.value)
        assertEquals(ThermalTier.MODERATE, Harness().apply { on(STATUS_MODERATE) }.governor.tier.value)
        assertEquals(ThermalTier.SEVERE, Harness().apply { on(STATUS_SEVERE) }.governor.tier.value)
        assertEquals(ThermalTier.CRITICAL, Harness().apply { on(STATUS_CRITICAL) }.governor.tier.value)
        // EMERGENCY / SHUTDOWN 比 CRITICAL 更极端，一并落 CRITICAL（释放语义相同）
        assertEquals(ThermalTier.CRITICAL, Harness().apply { on(STATUS_EMERGENCY) }.governor.tier.value)
        assertEquals(ThermalTier.CRITICAL, Harness().apply { on(STATUS_SHUTDOWN) }.governor.tier.value)
    }

    @Test
    fun `未知状态回落 NONE，且能覆盖此前的高档位`() {
        val h = Harness()
        h.on(STATUS_SEVERE)
        assertEquals(ThermalTier.SEVERE, h.governor.tier.value)
        h.on(STATUS_UNKNOWN)
        // 没有已校准策略的值按 NONE 处理：宁可无干预，也不猜档位
        assertEquals(ThermalTier.NONE, h.governor.tier.value)
        assertTrue(h.governor.canStartRun())
    }

    // ── 拒新 run（SEVERE 门槛）───────────────────────────────────────────────

    @Test
    fun `SEVERE 及以上拒新 run，MODERATE 及以下放行`() {
        val h = Harness()
        assertTrue(h.governor.canStartRun()) // 初始 NONE
        h.on(STATUS_LIGHT)
        assertTrue(h.governor.canStartRun())
        h.on(STATUS_MODERATE)
        assertTrue(h.governor.canStartRun())
        h.on(STATUS_SEVERE)
        assertFalse(h.governor.canStartRun())
        h.on(STATUS_CRITICAL)
        assertFalse(h.governor.canStartRun())
    }

    // ── 轮间冷却 ─────────────────────────────────────────────────────────────

    @Test
    fun `MODERATE 及以上 2s 冷却，LIGHT 及以下无冷却`() {
        val h = Harness()
        assertEquals(0L, h.governor.roundCooldownMillis())
        h.on(STATUS_LIGHT)
        assertEquals(0L, h.governor.roundCooldownMillis())
        h.on(STATUS_MODERATE)
        assertEquals(2_000L, h.governor.roundCooldownMillis())
        h.on(STATUS_CRITICAL)
        assertEquals(2_000L, h.governor.roundCooldownMillis())
    }

    // ── LIGHT 降 maxTokens ───────────────────────────────────────────────────

    @Test
    fun `LIGHT 降档对基准减半`() {
        val h = Harness()
        assertEquals(1024, h.governor.maxTokensCap(1024)) // NONE：不动
        h.on(STATUS_LIGHT)
        assertEquals(512, h.governor.maxTokensCap(1024))
        assertEquals(2048, h.governor.maxTokensCap(4096))
    }

    @Test
    fun `保底 256：基准小于 512 时减半不再往下压`() {
        val h = Harness()
        h.on(STATUS_LIGHT)
        assertEquals(256, h.governor.maxTokensCap(512))
        assertEquals(256, h.governor.maxTokensCap(300))
    }

    @Test
    fun `降档只往小压：基准低于保底 256 时不得反超`() {
        val h = Harness()
        h.on(STATUS_LIGHT)
        // 滑块下限 64：减半 32 → 保底 256 → 若不夹上界，热档期反而把输出上限抬 4 倍
        assertEquals(64, h.governor.maxTokensCap(64))
        assertEquals(128, h.governor.maxTokensCap(128))
    }

    // ── 轮头决策门 ───────────────────────────────────────────────────────────

    @Test
    fun `NONE 与 LIGHT 放行（LIGHT 的降 maxTokens 在启动时刻生效，轮头无事可做）`() {
        val h = Harness()
        assertEquals(ThermalDecision.Proceed, h.governor.asGate().beforeRound(1))
        h.on(STATUS_LIGHT)
        assertEquals(ThermalDecision.Proceed, h.governor.asGate().beforeRound(1))
    }

    @Test
    fun `MODERATE 轮间冷却 2s，但首轮（round 0）不冷却`() {
        val h = Harness()
        h.on(STATUS_MODERATE)
        // 首轮不冷却：「轮间」冷却的定义是两轮之间，run 刚起来还没产生热量，
        // 白等 2s 只拖慢首字，还占墙钟硬预算（5min）的份额。
        assertEquals(ThermalDecision.Proceed, h.governor.asGate().beforeRound(0))
        assertEquals(ThermalDecision.Cooldown(2_000L), h.governor.asGate().beforeRound(1))
        assertEquals(ThermalDecision.Cooldown(2_000L), h.governor.asGate().beforeRound(3))
    }

    @Test
    fun `SEVERE 首轮照样熔断（首轮豁免只针对冷却，不针对熔断）`() {
        val h = Harness()
        h.on(STATUS_SEVERE)
        assertEquals(ThermalDecision.Abort("设备热状态已达 SEVERE 档"), h.governor.asGate().beforeRound(0))
    }

    @Test
    fun `SEVERE 与 CRITICAL 轮头 Abort，且 evidence 带档位名`() {
        val h = Harness()
        h.on(STATUS_SEVERE)
        assertEquals(ThermalDecision.Abort("设备热状态已达 SEVERE 档"), h.governor.asGate().beforeRound(2))
        h.on(STATUS_SHUTDOWN)
        assertEquals(ThermalDecision.Abort("设备热状态已达 CRITICAL 档"), h.governor.asGate().beforeRound(2))
    }

    // ── Wave 43：电池温度熔断（主判据）──────────────────────────────────────

    @Test
    fun `电池温度达 449 熔断，evidence 带实时温度且优先于档位`() {
        val h = BatteryHarness(tenths = 452)
        // 档位 NONE 也不例外：电池熔断是主判据，档位（跨设备不可比）只是降速信号
        assertEquals(
            ThermalDecision.Abort("电池温度 45.2℃ 已达 44.9℃ 保护线"),
            h.governor.asGate().beforeRound(0),
        )
        // 拒新 run 同判据
        assertFalse(h.governor.canStartRun())
        // 文案同源（ChatRunCoordinator 热闸）
        assertEquals(
            "电池温度 45.2℃ 已达 44.9℃ 保护线，请等待设备降温后再试",
            h.governor.heatBlockReason(),
        )
    }

    @Test
    fun `电池 448 是保护线下的最后一格：放行`() {
        val h = BatteryHarness(tenths = 448)
        assertEquals(ThermalDecision.Proceed, h.governor.asGate().beforeRound(0))
        assertTrue(h.governor.canStartRun())
        assertEquals(null, h.governor.heatBlockReason())
    }

    @Test
    fun `接线后 SEVERE 降级为轮间冷却 10s，首轮豁免（旧行为是熔断）`() {
        val h = BatteryHarness(tenths = 350)
        h.on(STATUS_SEVERE)
        // 真机实测依据：SEVERE 在部分 ROM 上 NONE→SEVERE 仅 15s、90s 回落，
        // 电池 35℃ 即报 —— 挂在它上面熔断会让长任务永远跑不完
        assertEquals(ThermalDecision.Proceed, h.governor.asGate().beforeRound(0))
        assertEquals(ThermalDecision.Cooldown(10_000L), h.governor.asGate().beforeRound(1))
        assertEquals(ThermalDecision.Cooldown(10_000L), h.governor.asGate().beforeRound(5))
        // SEVERE 放行新 run（降速不拦截）
        assertTrue(h.governor.canStartRun())
    }

    @Test
    fun `接线后 CRITICAL 仍熔断（OS 紧急兜底不撤），引擎释放照常`() {
        val h = BatteryHarness(tenths = 300)
        h.on(STATUS_CRITICAL)
        assertEquals(
            ThermalDecision.Abort("设备热状态已达 CRITICAL 档"),
            h.governor.asGate().beforeRound(0),
        )
        assertFalse(h.governor.canStartRun())
        assertEquals("设备热状态已达 CRITICAL 档，请等待设备降温后再试", h.governor.heatBlockReason())
        assertEquals(1, h.releases) // CRITICAL 跃迁释放路径原样
    }

    @Test
    fun `电池熔断与档位无关：NONE 也能熔断、温度回落即解除`() {
        val h = BatteryHarness(tenths = 449) // 恰好压线
        assertTrue(h.governor.canStartRun().not())
        h.tenths = 431 // 推理间歇降温
        assertTrue(h.governor.canStartRun())
        assertEquals(ThermalDecision.Proceed, h.governor.asGate().beforeRound(1))
        h.tenths = 470 // 下一轮再爬升 → 轮头熔断（实时读取，非构造快照）
        assertEquals(
            ThermalDecision.Abort("电池温度 47.0℃ 已达 44.9℃ 保护线"),
            h.governor.asGate().beforeRound(2),
        )
    }

    @Test
    fun `读取失败（MIN_VALUE）不误熔断，退回档位判据`() {
        val h = BatteryHarness(tenths = Int.MIN_VALUE)
        h.on(STATUS_SEVERE)
        // 温度不可得 → 电池熔断恒 false → SEVERE 走冷却（不是 Wave 30 的 Abort）
        assertEquals(ThermalDecision.Cooldown(10_000L), h.governor.asGate().beforeRound(1))
        assertTrue(h.governor.canStartRun())
    }

    @Test
    fun `未接线（null 源）时行为与 Wave 30 逐字节一致：SEVERE 仍 Abort`() {
        val h = Harness()
        h.on(STATUS_SEVERE)
        assertFalse(h.governor.canStartRun())
        assertEquals(ThermalDecision.Abort("设备热状态已达 SEVERE 档"), h.governor.asGate().beforeRound(0))
        assertEquals("设备过热保护中（SEVERE 档），请等待设备降温后再试", h.governor.heatBlockReason())
    }

    // ── CRITICAL 释放引擎 ────────────────────────────────────────────────────

    @Test
    fun `释放只在跃迁进 CRITICAL 时触发一次`() {
        val h = Harness()
        h.on(STATUS_CRITICAL)
        assertEquals(1, h.releases)
        // EMERGENCY/SHUTDOWN 仍映射 CRITICAL，档位没变 → 不重复释放
        h.on(STATUS_EMERGENCY)
        h.on(STATUS_SHUTDOWN)
        assertEquals(1, h.releases)
        // 降温后再进 CRITICAL → 触发第二次
        h.on(STATUS_NONE)
        h.on(STATUS_CRITICAL)
        assertEquals(2, h.releases)
    }

    @Test
    fun `非 CRITICAL 档位不触发释放`() {
        val h = Harness()
        h.on(STATUS_LIGHT)
        h.on(STATUS_MODERATE)
        h.on(STATUS_SEVERE)
        assertEquals(0, h.releases)
    }

    @Test
    fun `释放回调抛异常不冒泡（主线程回调不得崩进程），档位照常落定`() {
        val h = Harness(release = { error("closeAll 炸了") })
        h.on(STATUS_CRITICAL)
        assertEquals(1, h.releases)
        assertEquals(ThermalTier.CRITICAL, h.governor.tier.value)
    }

    // ── CRITICAL 补释放（Wave 31 流2：isBusy 闸门跳过后的补偿）─────────────────

    /**
     * 观察协程用 `Dispatchers.Unconfined`：`scope.launch` 同步执行到首次挂起（StateFlow
     * 订阅建立），此后每次 `busy.value = x` 同步驱动 collect 体 —— 无需 coroutines-test
     * 即可**确定性**断言（本仓禁用未声明的测试依赖）。`releaseEngineIfIdle` 内模拟
     * AppContainer 的真实硬闸门（忙时跳过），以验证「跳过 → 转闲补释放」的闭环。
     */
    private class DeferredHarness {
        val busy = MutableStateFlow(false)
        var releases = 0

        /**
         * 每次「真正释放」（`busy == false` 时进入 `releaseEngineIfIdle` 的那次）之后回调。
         * 测试可在构造后赋值，用它模拟「释放调用窗口内新 run 起来」（`busy` 被置回 true）。
         */
        var onRelease: () -> Unit = {}

        val governor = ThermalGovernor(
            releaseEngineIfIdle = {
                if (!busy.value) {
                    releases++
                    onRelease()
                }
            },
            isBusy = busy,
            scope = CoroutineScope(Dispatchers.Unconfined),
        )

        fun on(status: Int) = governor.onThermalStatus(status)
    }

    @Test
    fun `isBusy 为 null 时不启用补释放：行为与引入本机制前一致`() {
        // Harness 用默认的 isBusy = null / scope = null：CRITICAL 跃迁照常释放一次，
        // 降温回落不产生任何补偿。
        val h = Harness()
        h.on(STATUS_CRITICAL)
        assertEquals(1, h.releases)
        h.on(STATUS_NONE)
        assertEquals(1, h.releases)
    }

    @Test
    fun `CRITICAL 时引擎忙则本次释放被跳过、引擎转闲后补释放一次`() {
        val h = DeferredHarness()
        h.busy.value = true
        h.on(STATUS_CRITICAL)
        // 忙：闸门跳过 → 0 次；但 releaseDeferred 已置位。
        assertEquals(0, h.releases)
        h.busy.value = false
        // 转闲且档位仍 CRITICAL → 补释放一次。
        assertEquals(1, h.releases)
        // 同值再写不产生新发射 → 不重复释放。
        h.busy.value = false
        assertEquals(1, h.releases)
    }

    @Test
    fun `CRITICAL 时引擎闲则立即释放、且不留下待补标志`() {
        val h = DeferredHarness()
        h.on(STATUS_CRITICAL)
        assertEquals(1, h.releases) // 立即释放
        h.busy.value = false
        assertEquals(1, h.releases) // 无待补 → 不重复
    }

    @Test
    fun `降温回落撤销待补释放：转闲后不再补释放`() {
        val h = DeferredHarness()
        h.busy.value = true
        h.on(STATUS_CRITICAL) // 忙 → 跳过，置 releaseDeferred
        assertEquals(0, h.releases)
        h.on(STATUS_NONE) // 降温回落 → releaseDeferred 复位
        h.busy.value = false // 转闲，但档位非 CRITICAL → 不补释放
        assertEquals(0, h.releases)
    }

    @Test
    fun `补释放调用窗口内新 run 起来则保留待补标志、转闲后再次补释放`() {
        val h = DeferredHarness()
        h.busy.value = true
        h.on(STATUS_CRITICAL) // 忙 → 跳过，置 releaseDeferred
        assertEquals(0, h.releases)
        // 模拟「补释放调用窗口内新 run 起来」：仅第一次真正释放期间把 busy 置回 true。
        var occupiedOnce = false
        h.onRelease = {
            if (!occupiedOnce) {
                occupiedOnce = true
                h.busy.value = true
            }
        }
        h.busy.value = false // 转闲 → 触发补释放；释放期间 busy 被置回 true（内部闸门会跳过）
        assertEquals(1, h.releases)
        // 若此时误清 releaseDeferred，则档位仍 CRITICAL、busy 不会再触发释放 ⇒ 永不补。
        // 下方再次转闲能触发第二次释放，即证明 releaseDeferred 被正确保留。
        h.busy.value = false
        assertEquals(2, h.releases)
    }
}
