package com.rickeal.agent.core.data.background

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [OemBackgroundPolicy] 的 JVM 单测（W77）。
 *
 * 为什么这张表值得被测试钉死：分类写错不会崩溃、不会报错，只会让用户照着
 * **另一套系统的路径**去找菜单，然后得出「这 App 的功能是假的」的结论。
 * 真机取证也证明了各家 ROM 的后台管控是独立于 AOSP 的第二套系统
 * （ColorOS 的 `OplusProxyWakeLock` 会在灭屏后强制回收第三方 WakeLock）。
 */
class OemBackgroundPolicyTest {

    @Test
    fun colorOsFamilyCoversOppoRealmeOnePlus() {
        assertEquals(
            OemBackgroundPolicy.OemFamily.COLOR_OS,
            OemBackgroundPolicy.classify("OPPO", "OPPO"),
        )
        assertEquals(
            OemBackgroundPolicy.OemFamily.COLOR_OS,
            OemBackgroundPolicy.classify("realme", "realme"),
        )
        assertEquals(
            OemBackgroundPolicy.OemFamily.COLOR_OS,
            OemBackgroundPolicy.classify("OnePlus", "OnePlus"),
        )
    }

    @Test
    fun xiaomiFamilyCoversSubBrands() {
        // Redmi / POCO 的 manufacturer 仍是 Xiaomi ⇒ 必须靠 brand 或 manufacturer 双命中。
        assertEquals(
            OemBackgroundPolicy.OemFamily.HYPER_OS,
            OemBackgroundPolicy.classify("Xiaomi", "Redmi"),
        )
        assertEquals(
            OemBackgroundPolicy.OemFamily.HYPER_OS,
            OemBackgroundPolicy.classify("Xiaomi", "POCO"),
        )
        assertEquals(
            OemBackgroundPolicy.OemFamily.HYPER_OS,
            OemBackgroundPolicy.classify("Xiaomi", "Xiaomi"),
        )
    }

    @Test
    fun huaweiAndHonorAreSplit() {
        assertEquals(
            OemBackgroundPolicy.OemFamily.EMUI_HARMONY,
            OemBackgroundPolicy.classify("HUAWEI", "HUAWEI"),
        )
        // 荣耀独立后走 MagicOS，与华为 EMUI 的菜单不同 —— 不能合并。
        assertEquals(
            OemBackgroundPolicy.OemFamily.MAGIC_OS,
            OemBackgroundPolicy.classify("HONOR", "HONOR"),
        )
    }

    @Test
    fun vivoFamilyCoversIqoo() {
        assertEquals(
            OemBackgroundPolicy.OemFamily.ORIGIN_OS,
            OemBackgroundPolicy.classify("vivo", "iQOO"),
        )
        assertEquals(
            OemBackgroundPolicy.OemFamily.ORIGIN_OS,
            OemBackgroundPolicy.classify("vivo", "vivo"),
        )
    }

    @Test
    fun samsungAndMeizuAndUnknown() {
        assertEquals(
            OemBackgroundPolicy.OemFamily.ONE_UI,
            OemBackgroundPolicy.classify("samsung", "samsung"),
        )
        assertEquals(
            OemBackgroundPolicy.OemFamily.FLYME,
            OemBackgroundPolicy.classify("Meizu", "Meizu"),
        )
        assertEquals(
            OemBackgroundPolicy.OemFamily.AOSP,
            OemBackgroundPolicy.classify("Google", "google"),
        )
        // 空值（Build 字段异常）不能抛异常，落 AOSP 安全档。
        assertEquals(
            OemBackgroundPolicy.OemFamily.AOSP,
            OemBackgroundPolicy.classify("", ""),
        )
    }

    @Test
    fun everyFamilyHasNavigableGuideText() {
        OemBackgroundPolicy.OemFamily.values().forEach { family ->
            val text = OemBackgroundPolicy.guideText(family)
            assertTrue("$family 的引导文案为空", text.isNotBlank())
            assertTrue("$family 的引导文案缺少路径箭头：$text", text.contains("→"))
            assertTrue("$family 的厂商名为空", OemBackgroundPolicy.displayName(family).isNotBlank())
        }
    }

    @Test
    fun colorOsGuidePointsToAppPowerManagement() {
        // 真机（OPPO PDRM00）实测路径：电池 → 应用耗电管理。
        val text = OemBackgroundPolicy.guideText(OemBackgroundPolicy.OemFamily.COLOR_OS)
        assertTrue(text, text.contains("应用耗电管理"))
        assertTrue(text, text.contains("允许完全后台行为"))
    }

    @Test
    fun recentsLockOnlyForFamiliesThatSupportIt() {
        assertTrue(OemBackgroundPolicy.supportsRecentsLock(OemBackgroundPolicy.OemFamily.COLOR_OS))
        assertTrue(OemBackgroundPolicy.supportsRecentsLock(OemBackgroundPolicy.OemFamily.HYPER_OS))
        assertTrue(OemBackgroundPolicy.supportsRecentsLock(OemBackgroundPolicy.OemFamily.ORIGIN_OS))
        assertFalse(OemBackgroundPolicy.supportsRecentsLock(OemBackgroundPolicy.OemFamily.AOSP))
        assertFalse(OemBackgroundPolicy.supportsRecentsLock(OemBackgroundPolicy.OemFamily.ONE_UI))
    }
}
