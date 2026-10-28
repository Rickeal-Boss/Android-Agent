package com.rickeal.agent.onboarding

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 首启闸门判定面的 JVM 单测（W90 审查修复）。
 *
 * 只测两个**纯函数**——[firstRunStepFor]（续跑点）与 [shouldEnterMainShell]（放行判据）。
 * [FirstRunGate] 本身是 Composable，读 DataStore 的 Flow 并在重组间推进 step，
 * JVM 上不可断（那是 Compose UI 测试 / 真机判据的面）。
 *
 * ## 为什么必须钉这一组（W90 审查修复的病灶）
 *
 * W90 把 TOS 判据从「无版本 boolean」升级成「已同意版本 ≥ 当前版本」，机制的正确性
 * **完全依赖放行判据同时看 tosAccepted**。原实现早退只看 `hasSeenOnboarding`，而该位对
 * 老用户恒 true ⇒ bump `LegalDocuments.CURRENT_TERMS_VERSION` 后 `isTosAccepted` 变 false，
 * 老用户却仍被直接放进主界面 —— 版本化机制形同虚设，
 * `SettingsRepository.isTosAccepted` KDoc 里「未来 bump 版本号即可让老用户重新征求同意」
 * 就成了假陈述。下面 ⑤ 是钉住这条闭环的回归闸。
 */
class FirstRunGateDecisionTest {

    // ---- firstRunStepFor：续跑点（判定顺序即流程顺序，GEMMA 最先判）----

    /** ① TOS 已接受 ⇒ 从最后一步 GEMMA 续跑（前两步都已落盘）。 */
    @Test
    fun firstRunStepForReturnsGemmaWhenTosAccepted() {
        assertEquals(FirstRunStep.GEMMA, firstRunStepFor(tosAccepted = true, hasSeenIntro = true))
        // 引导标记缺失也不影响：TOS 已接受 ⇒ 引导必然看过，不必回退重放。
        assertEquals(FirstRunStep.GEMMA, firstRunStepFor(tosAccepted = true, hasSeenIntro = false))
    }

    /** ② 未接受 TOS 但看过引导 ⇒ 停在 TOS（不把引导重放一遍）。 */
    @Test
    fun firstRunStepForReturnsTosWhenIntroSeenButTosNotAccepted() {
        assertEquals(FirstRunStep.TOS, firstRunStepFor(tosAccepted = false, hasSeenIntro = true))
    }

    /** ③ 两步都没完成 ⇒ 从头走 ONBOARDING。 */
    @Test
    fun firstRunStepForReturnsOnboardingWhenNothingDone() {
        assertEquals(FirstRunStep.ONBOARDING, firstRunStepFor(tosAccepted = false, hasSeenIntro = false))
    }

    // ---- shouldEnterMainShell：放行判据（两个条件缺一不可）----

    /** ④ 首启已走完且 TOS 有效 ⇒ 放行进主界面（v1 下老用户的正常路径，零行为变化）。 */
    @Test
    fun shouldEnterMainShellWhenOnboardingDoneAndTosValid() {
        assertTrue(shouldEnterMainShell(hasSeenOnboarding = true, tosAccepted = true))
    }

    /**
     * ⑤ **版本 bump 后老用户必须被重新征求**（本波 P1 的闭环，关键回归闸）。
     *
     * 场景：老用户 `hasSeenOnboarding == true`（首启早走完），作者 bump 了
     * `LegalDocuments.CURRENT_TERMS_VERSION` ⇒ `isTosAccepted` 变 false。
     * 此时必须**不进**主界面（落回 TOS 步重新征求），否则版本化机制等于没做。
     */
    @Test
    fun shouldNotEnterMainShellWhenOnboardingDoneButTosVersionBumped() {
        assertFalse(
            shouldEnterMainShell(hasSeenOnboarding = true, tosAccepted = false),
            "版本 bump 后老用户必须被重新征求 —— 只看 hasSeenOnboarding 会让他们永远绕过 TOS 硬闸门",
        )
    }

    /** ⑥ 首启流程还没走完 ⇒ 不放行（与 TOS 是否有效无关）。 */
    @Test
    fun shouldNotEnterMainShellWhenOnboardingNotDone() {
        assertFalse(shouldEnterMainShell(hasSeenOnboarding = false, tosAccepted = true))
        assertFalse(shouldEnterMainShell(hasSeenOnboarding = false, tosAccepted = false))
    }
}
