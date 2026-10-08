package com.rickeal.agent.core.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 墙钟预算 evidence 文案的**口径**锁定（Wave 35 B2）。
 *
 * 钉住的是「诊断卡上的事实陈述不能自相矛盾」：Wave 31 6d 把判据换成「距继承来的
 * 绝对硬截止还剩多少」，evidence 却逐字留着 run 相对口径，于是子 run 会产出
 * 「已运行 2 秒，达到 300 秒硬预算」—— 两个数字互相打架，用户只能读成「断路器乱熔断」，
 * 真正的成因（预算被父 run 烧掉）反而被藏起来。
 *
 * 因此这里锁两条：
 *  ① **父 run / 独立 run 文案逐字不变**（用户可见文案，非必要不动）；
 *  ② **子 run 报「run 总运行 N 秒（本子 run M 秒）」**，且 N 与判据同源
 *     （= 硬预算 − 剩余），故「总运行 300 秒 ⇒ 达到 300 秒硬预算」自洽。
 *
 * Wave 52 B1 追加：本类同时锁定**停表算术** `effectiveElapsedMillis(elapsed, paused)`
 * （文件级纯函数）。只测 evidence 文案模板（入参为字面量）覆盖不到「墙钟 − 审批挂起」
 * 这一步 —— B1 的新口径会变成 vacuous 未覆盖。故在此直接钉边界：paused=0 / 正常扣减 / 钳到 0。
 *
 * Wave 53 追加：B1 有**两条独立表达式** —— evidence 侧 `effectiveElapsedMillis`（已测）与
 * 判据侧 `wallClockRemainingMillis`（`gateRoundHead` 的 `remaining`，含 `+ pausedNanos`）。
 * 后者误删 `+ pausedNanos` 会退回纯墙钟口径（长授权后下一轮立即 HARD 熔断的 W51 现象复发），
 * 而 evidence 那条测试**照样绿**。故补「判据接线」两例：① 无挂起必 HARD / 挂 60s 不 HARD；
 * ② remaining 与 effectiveElapsedMillis 同源（= 硬预算 − 有效时长）。
 *
 * 纯 JVM：`wallClock*Evidence` / `effectiveElapsedMillis` / `wallClockRemainingMillis` 都是
 * 顶层纯函数（零 Android / 零引擎依赖）。
 */
class WallClockEvidenceTest {

    @Test
    fun `父 run 口径 —— 逐字保持「已运行 N 秒」`() {
        assertEquals(
            "已运行 180 秒，超过 180 秒软预算（继续，等待收敛）",
            wallClockSoftEvidence(inheritedDeadline = false, ownElapsedMillis = 180_000L, totalElapsedMillis = 180_000L),
        )
        assertEquals(
            "已运行 300 秒，达到 300 秒硬预算",
            wallClockHardEvidence(inheritedDeadline = false, ownElapsedMillis = 300_000L, totalElapsedMillis = 300_000L),
        )
    }

    @Test
    fun `子 run 口径 —— 报总运行并带上本子 run 自己的表`() {
        // 子 run 只跑了 2 秒就撞上父 run 继承下来的硬截止：
        // 报「已运行 2 秒，达到 300 秒硬预算」是自相矛盾的事实陈述。
        assertEquals(
            "run 总运行 300 秒（本子 run 2 秒），达到 300 秒硬预算",
            wallClockHardEvidence(inheritedDeadline = true, ownElapsedMillis = 2_000L, totalElapsedMillis = 300_000L),
        )
        assertEquals(
            "run 总运行 180 秒（本子 run 2 秒），超过 180 秒软预算（继续，等待收敛）",
            wallClockSoftEvidence(inheritedDeadline = true, ownElapsedMillis = 2_000L, totalElapsedMillis = 180_000L),
        )
    }

    @Test
    fun `子 run 不得沿用 run 相对口径 —— 这是 B2 的回归锚`() {
        val soft = wallClockSoftEvidence(true, 2_000L, 180_000L)
        val hard = wallClockHardEvidence(true, 2_000L, 300_000L)
        assertTrue(!soft.startsWith("已运行"), "子 run 的 SOFT 文案不得用 run 相对口径：$soft")
        assertTrue(!hard.startsWith("已运行"), "子 run 的 HARD 文案不得用 run 相对口径：$hard")
        assertTrue(hard.contains("run 总运行"), "子 run 必须显式标注口径：$hard")
    }

    @Test
    fun `子 run 的两个数字与判据同源 —— 总运行等于硬预算时必然撞 HARD`() {
        // 判据是 remaining <= 0；总运行由同一堵墙反推（硬预算 − 剩余），
        // 故 remaining = 0 ⇒ 总运行 300 秒 ⇒ 文案里的「达到 300 秒硬预算」成立。
        val remaining = 0L
        val totalElapsedMillis = 300_000L - remaining
        val text = wallClockHardEvidence(inheritedDeadline = true, ownElapsedMillis = 2_000L, totalElapsedMillis)
        assertTrue(text.startsWith("run 总运行 300 秒"), "总运行必须与判据同源：$text")
        assertTrue(text.endsWith("达到 300 秒硬预算"), "两个数字必须自洽：$text")
    }

    @Test
    fun `有效执行时长 = 墙钟 − 审批挂起（B1 停表算术）`() {
        // paused = 0 ⇒ 与墙钟同值（无审批的 run 逐字等价，零回归）。
        assertEquals(180_000L, effectiveElapsedMillis(elapsedMillis = 180_000L, pausedNanos = 0L))
        // 正常扣减：挂起 60 秒 ⇒ 有效 = 墙钟 − 60 秒。
        assertEquals(
            240_000L,
            effectiveElapsedMillis(elapsedMillis = 300_000L, pausedNanos = 60_000L * 1_000_000L),
        )
        // 亚毫秒挂起被整数截断（≤1ms 误差，不向上取整）。
        assertEquals(1_000L, effectiveElapsedMillis(elapsedMillis = 1_000L, pausedNanos = 500_000L))
    }

    @Test
    fun `有效执行时长钳到 0 —— 挂起大于墙钟不得为负`() {
        // 越界输入（挂起 > 墙钟）钳到 0：负耗时会同时污染「已运行 -N 秒」文案与判据。
        assertEquals(0L, effectiveElapsedMillis(elapsedMillis = 1_000L, pausedNanos = 5_000L * 1_000_000L))
        assertEquals(0L, effectiveElapsedMillis(elapsedMillis = 0L, pausedNanos = 1L))
    }

    @Test
    fun `判据接线 —— remaining 必须含 pausedNanos（B1 停表）`() {
        // 场景：硬截止已过 1 秒（墙钟口径下必 HARD 熔断）。
        val deadline = 300_000L * 1_000_000L // 硬截止 = T0 + 300s（nanoTime 刻度，取 T0 = 0）
        val nowPastDeadline = 301_000L * 1_000_000L // now = T0 + 301s

        // ① 无审批挂起 ⇒ remaining ≤ 0 ⇒ 判据会 HARD 熔断（基线行为）。
        val noPause = wallClockRemainingMillis(deadline, pausedNanos = 0L, nowNanos = nowPastDeadline)
        assertEquals(-1_000L, noPause)
        assertTrue(noPause <= 0L, "无挂起时应越界（HARD 熔断）：$noPause")

        // ② 挂了 60 秒审批 ⇒ remaining 加回 60s ⇒ 不熔断（B1 停表接线的核心断言：
        //    若 `+ pausedNanos` 被误删，本断言立刻红 —— 这正是既有 evidence 测试测不到的接线）。
        val withPause = wallClockRemainingMillis(
            deadline,
            pausedNanos = 60_000L * 1_000_000L,
            nowNanos = nowPastDeadline,
        )
        assertEquals(59_000L, withPause)
        assertTrue(withPause > 0L, "挂了 60s 审批后不得再判 HARD：$withPause")
        // 仍在 SOFT 区间（≤ 硬预算 − 软预算 = 300s − 180s = 120s）⇒ 留痕但不中断，与停表语义一致。
        assertTrue(withPause <= 300_000L - 180_000L, "应落入 SOFT 区间：$withPause")
    }

    @Test
    fun `判据与 evidence 同源 —— remaining = 硬预算 − 有效执行时长`() {
        // 判据侧 [wallClockRemainingMillis] 与 evidence 侧 [effectiveElapsedMillis] 是同一停表
        // 算术的两种表达：无继承时硬截止 = T0 + 硬预算，故 remaining 恒等于「硬预算 − 有效时长」。
        // 这条把「两条独立表达式」锁成同源，防止日后只改一处造成判据/文案口径分叉。
        val hardBudgetNanos = 300_000L * 1_000_000L
        val elapsedMillis = 200_000L // 墙钟已跑 200s
        val pausedNanos = 30_000L * 1_000_000L // 其中 30s 是审批挂起
        val remaining = wallClockRemainingMillis(
            hardDeadlineNanos = hardBudgetNanos,
            pausedNanos = pausedNanos,
            nowNanos = elapsedMillis * 1_000_000L,
        )
        val effective = effectiveElapsedMillis(elapsedMillis, pausedNanos)
        assertEquals(170_000L, effective) // 200s 墙钟 − 30s 挂起 = 170s 有效执行
        assertEquals(130_000L, remaining) // 300s 硬预算 − 170s 有效 = 剩 130s
        assertEquals(300_000L - effective, remaining, "remaining 必须等于硬预算 − 有效执行时长")
    }
}
