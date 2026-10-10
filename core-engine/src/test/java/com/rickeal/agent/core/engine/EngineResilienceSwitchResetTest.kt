package com.rickeal.agent.core.engine

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [shouldResetResilienceOnSwitchFlip] 的判据单测（W58 修补 B）。
 *
 * 为什么值得写：引擎「用户重开原生工具通道 ⇒ 韧性复位」的唯一合法触发面 =
 * **同实例观察到的 OFF→ON 跳变**。判错方向各有代价：
 *  - 该复位不复位 ⇒ 用户的显式重开被证伪/计数永久压制（用户显式设置优先被架空）；
 *  - 不该复位乱复位 ⇒ null（首 run / 实例刚换）被误判成翻转，evict 后重走必炸路径
 *    （W55 审查 P2#1 复发）。
 * 六个用例穷尽 (lastSeen, current) 的判据面（W59 补齐 (null,false) / (true,false) 两例，
 * 与复审 18 P4① 六组合穷进口径对齐）。纯 JVM、零 native、真调纯函数（非 vacuous）。
 */
class EngineResilienceSwitchResetTest {

    @Test
    fun `首 run 的 null 不判翻转`() {
        // lastSeen == null（首 run / 实例刚换）⇒ 不复位：保 evict 场景的 store 防护。
        assertFalse(shouldResetResilienceOnSwitchFlip(lastSeen = null, current = true))
    }

    @Test
    fun `同实例 OFF 转 ON 判翻转`() {
        // 唯一复位面：用户显式重开原生工具通道 ⇒ 清证伪与计数、重新给机会。
        assertTrue(shouldResetResilienceOnSwitchFlip(lastSeen = false, current = true))
    }

    @Test
    fun `持续开启不判翻转`() {
        // lastSeen == true 且 current == true：开关没动过，证伪与计数维持原状。
        assertFalse(shouldResetResilienceOnSwitchFlip(lastSeen = true, current = true))
    }

    @Test
    fun `持续关闭不判翻转`() {
        // (false, false) = 持续关闭（W60 改名，原名「ON 转 OFF 不判翻转」名实不符）：
        // 通道一直关着，无「跳变」可言 ⇒ 无需复位（文本协议路径不消费证伪/计数）。
        // 真正的 ON→OFF 由例 6（持续开启转 OFF）覆盖。
        assertFalse(shouldResetResilienceOnSwitchFlip(lastSeen = false, current = false))
    }

    @Test
    fun `首 run 的 null 配关闭不判翻转`() {
        // (null, false)（W59 补例）：首 run 即关（或实例刚换且用户此刻是关）——
        // 无「跳变」可言，不得复位（否则 evict 后首 run 即清 store，破坏 P2#1 防护）。
        assertFalse(shouldResetResilienceOnSwitchFlip(lastSeen = null, current = false))
    }

    @Test
    fun `持续开启转 OFF 不判翻转`() {
        // (true, false)（W59 补例）：持续开启后用户关闭 —— 关闭方向无复位面
        //（复位只认 OFF→ON 跳变），证伪/计数原样留待用户重开时处置。
        assertFalse(shouldResetResilienceOnSwitchFlip(lastSeen = true, current = false))
    }
}
