package com.rickeal.agent.feature.models

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `isDeleteBlockedByBusy` 回归测试（W84-21 / W84-P2-12）：删除忙闸门纯函数，JVM 直测。
 *
 *  - 非忙（loadingModelId=null 且 engineBusy=false）⇒ 不阻塞（false）；
 *  - 加载中（loadingModelId!=null）⇒ 阻塞（true）；
 *  - 引擎忙（engineBusy=true）⇒ 阻塞（true）。
 * 与 [isDeleteBlockedByBusy] 实现逐字节一致（同模块 `internal`，故可直测）。
 */
class DeleteBusyGateTest {

    @Test
    fun `idle_returnsFalse`() {
        assertEquals(false, isDeleteBlockedByBusy(null, false))
    }

    @Test
    fun `loadingInProgress_returnsTrue`() {
        assertEquals(true, isDeleteBlockedByBusy("m1", false))
    }

    @Test
    fun `engineBusy_returnsTrue`() {
        assertEquals(true, isDeleteBlockedByBusy(null, true))
    }
}
