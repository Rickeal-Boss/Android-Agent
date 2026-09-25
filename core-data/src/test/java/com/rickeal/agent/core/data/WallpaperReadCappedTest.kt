package com.rickeal.agent.core.data

import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * [readCapped] 的 JVM 纯函数单测：上限边界（这是"静默截断"的唯一风险点）。
 *
 * 上限已参数化 ⇒ 用**小上限**（16 字节）覆盖边界，不必真读 64MB。
 * 只依赖 `java.io`，不触任何 Android 类。
 */
class WallpaperReadCappedTest {

    @Test
    fun `恰好等于上限时完整返回且不抛`() {
        val payload = ByteArray(16) { it.toByte() }
        val out = readCapped(ByteArrayInputStream(payload), maxBytes = 16)
        // 用 assertEquals(List, List) 做内容比较（只依赖 kotlin.test 的基础断言）。
        assertEquals(payload.toList(), out.toList())
        assertEquals(16, out.size)
    }

    @Test
    fun `超过上限一字节时抛 TooLarge`() {
        val payload = ByteArray(17) { it.toByte() }
        assertFailsWith<WallpaperImportFailure.TooLarge> {
            readCapped(ByteArrayInputStream(payload), maxBytes = 16)
        }
    }
}
