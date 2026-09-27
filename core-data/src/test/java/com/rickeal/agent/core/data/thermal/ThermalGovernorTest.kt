package com.rickeal.agent.core.data.thermal

import com.rickeal.agent.core.agent.thermal.ThermalDecision
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
        val governor = ThermalGovernor { releases++; release() }
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
}
