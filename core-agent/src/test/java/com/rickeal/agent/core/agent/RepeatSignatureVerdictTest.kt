package com.rickeal.agent.core.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [repeatSignatureVerdict] 的纯逻辑测试（Wave 40 B1 外提，语义逐行等价于原
 * handlePostStreamSignals 无进展检测块）。
 *
 * 钉住跨轮重复签名判定的四条路径（B1 收口的控制流自查锚）：
 *  1. **首次出现** → FirstSight：只入 seen，不动 reminded，放行；
 *  2. **第二次出现**（提醒前）→ Remind：入 reminded 并排队提醒（既有行为）；
 *  3. **第三次及以后出现**（已提醒过仍复发）→ RemindedRepeat：B1 新增 ——
 *     调用方按失败收尾，不再把复读当最终答案交付给用户；
 *  4. **提醒后模型纠正**（输出不同签名）→ 新签名走 FirstSight：正常路径必须放行，
 *     这是「不误杀」的回归锚。
 *
 * 纯 JVM：入参是 MutableSet + String，零协程 / 零 Android 依赖。
 */
class RepeatSignatureVerdictTest {

    @Test fun `首次出现判 FirstSight 且只入 seen 账本`() {
        val seen = HashSet<String>()
        val reminded = HashSet<String>()
        val verdict = repeatSignatureVerdict(seen, reminded, "签名甲甲甲甲甲甲甲甲")
        assertEquals(RepeatSignatureVerdict.FirstSight, verdict)
        assertTrue("签名甲甲甲甲甲甲甲甲" in seen, "首次出现必须入 seen 账本")
        assertTrue(reminded.isEmpty(), "首次出现不得动 reminded 账本")
    }

    @Test fun `第二次出现判 Remind 且入 reminded 账本`() {
        val seen = HashSet<String>()
        val reminded = HashSet<String>()
        repeatSignatureVerdict(seen, reminded, "签名乙乙乙乙乙乙乙乙")
        val verdict = repeatSignatureVerdict(seen, reminded, "签名乙乙乙乙乙乙乙乙")
        assertEquals(RepeatSignatureVerdict.Remind, verdict)
        assertTrue("签名乙乙乙乙乙乙乙乙" in reminded, "第二次出现必须入 reminded 账本（先置位再排队的既有纪律）")
    }

    @Test fun `已提醒过的签名再次出现判 RemindedRepeat 且集合状态幂等`() {
        val seen = HashSet<String>()
        val reminded = HashSet<String>()
        repeatSignatureVerdict(seen, reminded, "签名丙丙丙丙丙丙丙丙")
        repeatSignatureVerdict(seen, reminded, "签名丙丙丙丙丙丙丙丙")
        // 第三次出现：B1 收口的目标路径。
        assertEquals(
            RepeatSignatureVerdict.RemindedRepeat,
            repeatSignatureVerdict(seen, reminded, "签名丙丙丙丙丙丙丙丙"),
        )
        // 第四、五次……同样判 RemindedRepeat（幂等，不会退回 Remind 造成二次提醒）。
        assertEquals(
            RepeatSignatureVerdict.RemindedRepeat,
            repeatSignatureVerdict(seen, reminded, "签名丙丙丙丙丙丙丙丙"),
        )
        assertEquals(setOf("签名丙丙丙丙丙丙丙丙"), seen, "seen 账本不受重复判定影响")
        assertEquals(setOf("签名丙丙丙丙丙丙丙丙"), reminded, "reminded 账本不得被重复置位改写")
    }

    @Test fun `提醒后模型纠正输出不同签名 —— 新签名走 FirstSight 不误杀`() {
        val seen = HashSet<String>()
        val reminded = HashSet<String>()
        val first = repeatSignatureVerdict(seen, reminded, "签名丁丁丁丁丁丁丁丁")
        assertEquals(RepeatSignatureVerdict.FirstSight, first)
        assertEquals(RepeatSignatureVerdict.Remind, repeatSignatureVerdict(seen, reminded, "签名丁丁丁丁丁丁丁丁"))
        // 模型收到提醒后给出了实质不同的输出：正常路径，必须放行。
        assertEquals(
            RepeatSignatureVerdict.FirstSight,
            repeatSignatureVerdict(seen, reminded, "签名戊戊戊戊戊戊戊戊"),
        )
        assertFalse("签名戊戊戊戊戊戊戊戊" in reminded, "新签名的首次出现不得被判为已提醒")
    }
}
