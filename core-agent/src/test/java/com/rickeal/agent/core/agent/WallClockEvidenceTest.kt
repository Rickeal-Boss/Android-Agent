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
 * 纯 JVM：`wallClock*Evidence` 是顶层纯函数（零 Android / 零引擎依赖）。
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
}
