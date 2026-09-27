package com.rickeal.agent.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [ToolDisclosureMode] 的契约测试（Wave 27）。
 *
 * 与 `AiCapabilityModeTest` 同一动机：本枚举有两个「错了不报错、只静默改变行为」的
 * 风险面 —— ① [ToolDisclosureMode.hidesToolCatalog] 的真值表（判反了会让全量模式
 * 悄悄变成按需模式，或反之）；② **枚举名**（落盘契约：`SettingsRepository` 存
 * `mode.name`，改名等于静默重置所有用户的设置）。
 */
class ToolDisclosureModeTest {

    @Test
    fun hidesToolCatalogTruthTable() {
        assertFalse(ToolDisclosureMode.FULL.hidesToolCatalog())
        assertTrue(ToolDisclosureMode.ON_DEMAND.hidesToolCatalog())
    }

    @Test
    fun defaultModeIsFullForZeroRegression() {
        // 默认档必须与「引入披露模式之前」的行为一致（工具清单完整进提示词）。
        // 断言的是 AgentRequest 的默认值口径 —— 这里用枚举顺序 + 显式常量双重锁定。
        assertEquals(ToolDisclosureMode.FULL, ToolDisclosureMode.entries.first())
    }

    @Test
    fun enumNamesAreStableStorageContract() {
        // ⚠️ 这两个字符串是落盘格式（DataStore 里存枚举名）。改动等于静默重置用户设置，
        // 因此必须由测试钉住 —— 改名要同时提供迁移逻辑，而不是改这里。
        assertEquals("FULL", ToolDisclosureMode.FULL.name)
        assertEquals("ON_DEMAND", ToolDisclosureMode.ON_DEMAND.name)
    }

    @Test
    fun valueOfRoundTripsForBothModes() {
        // SettingsRepository 用 valueOf 语义解析（when + name 匹配），改名或删除枚举值
        // 会让已存的值落进 else 分支 → 回退 FULL。这里锁定往返可用性。
        assertEquals(ToolDisclosureMode.FULL, ToolDisclosureMode.valueOf("FULL"))
        assertEquals(ToolDisclosureMode.ON_DEMAND, ToolDisclosureMode.valueOf("ON_DEMAND"))
    }
}
