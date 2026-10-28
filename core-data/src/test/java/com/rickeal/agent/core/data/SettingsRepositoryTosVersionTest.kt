package com.rickeal.agent.core.data

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [SettingsRepository] 的 termsVersion 版本化口径单测（W90 · C1 落地）。
 *
 * 被测对象是**纯函数** [acceptedTermsVersionFrom] / [isTosAcceptedFromVersion] —— 不依赖
 * Android `Context` / DataStore，可在 JVM 上直接跑（与本仓既有 `ActiveModelKnownBrokenTest`
 * 同款零 Android 依赖姿态）。DataStore 的 `Flow` 属性只是委托到这两个纯函数。
 *
 * 钉住迁移语义（docs/15 W68-08 裁决 A）：
 *  - 缺省 + legacy boolean=true ⇒ 迁移为 1（老用户视为已同意第 1 版）；
 *  - 缺省 + legacy boolean=false ⇒ 0（新用户 / 未同意）；
 *  - 已存版本号 ⇒ 直接用（不被 legacy 覆盖）；
 *  - 接受判据 = 已同意版本 ≥ 当前版本（v1=CURRENT ⇒ 与旧 boolean 行为一致；bump 后老用户重新征求）。
 */
class SettingsRepositoryTosVersionTest {

    @Test
    fun `缺省且无 legacy boolean 时版本为 0`() {
        assertEquals(0, acceptedTermsVersionFrom(null, false))
    }

    @Test
    fun `缺省且 legacy boolean true 迁移为第 1 版`() {
        assertEquals(1, acceptedTermsVersionFrom(null, true))
    }

    @Test
    fun `已存版本号优先于 legacy boolean`() {
        assertEquals(2, acceptedTermsVersionFrom(2, true))
        assertEquals(0, acceptedTermsVersionFrom(0, true)) // 显式拒绝已存 0，不被 legacy 覆盖
    }

    @Test
    fun `接受判据：版本达到当前版本即通过`() {
        assertEquals(false, isTosAcceptedFromVersion(0, 1))
        assertEquals(true, isTosAcceptedFromVersion(1, 1))
    }

    @Test
    fun `bump 当前版本后老用户需重新征求`() {
        // 老用户迁移版本 = 1，当前版本已 bump 到 2 ⇒ 未通过，首启应重新征求
        val legacyUserVersion = acceptedTermsVersionFrom(null, true)
        assertEquals(1, legacyUserVersion)
        assertEquals(false, isTosAcceptedFromVersion(legacyUserVersion, 2))
        assertEquals(true, isTosAcceptedFromVersion(2, 2))
    }
}
