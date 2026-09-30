package com.rickeal.agent.core.agent.memory

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [AgentMemory.upsertFailureReason]（Wave 36 E5）行为锁定。
 *
 * 这条纯函数是「core 回 false → 面向人的失败原因」的唯一翻译点：`AgentMemory.upsert`
 * 的 false 出口只有两条且互斥穷尽（正文超 [AgentMemory.MAX_CONTENT_CHARS]、文件解析失败），
 * 故按「去空白后的正文长度」即可无歧义还原原因。设置页据此把失败上屏（经 `MemoryUiState.editError`，
 * 内联显示在编辑对话框内且失败不关对话框），消灭了此前「对话框关闭却无任何提示」的静默失效。
 *
 * 本测试钉住三件事：
 *  ① 超限分支：长度 > 上限 ⇒ 文案含「内容过长」；
 *  ② 边界与同口径：长度**恰等于**上限必须落到「解析失败」分支 —— `upsert` 的判据是
 *     `>` 而非 `>=`，两处若不同口径，一条恰好 2000 字符的正文会被误报成「解析失败」；
 *  ③ 常量不漂移：文案里必须出现上限数值本身（2000），改常量而不改文案会立刻红。
 *
 * 全程纯 JVM：不碰文件、不碰 Android 类，只调纯函数。
 */
class MemoryFailureReasonTest {

    @Test
    fun `超限一个字符 —— 走内容过长分支`() {
        val reason = AgentMemory.upsertFailureReason(AgentMemory.MAX_CONTENT_CHARS + 1)
        assertTrue(reason.contains("内容过长"), "超限必须报「内容过长」：$reason")
    }

    @Test
    fun `恰好等于上限 —— 走解析失败分支（判据是大于而非大于等于）`() {
        val reason = AgentMemory.upsertFailureReason(AgentMemory.MAX_CONTENT_CHARS)
        assertTrue(reason.contains("解析失败"), "恰好等于上限不算超限（upsert 用 > 判据）：$reason")
        assertTrue(!reason.contains("内容过长"), "恰好等于上限不得报「内容过长」：$reason")
    }

    @Test
    fun `长度为零 —— 走解析失败分支`() {
        val reason = AgentMemory.upsertFailureReason(0)
        assertTrue(reason.contains("解析失败"), "空正文不是超限，只能是解析失败：$reason")
        assertTrue(!reason.contains("内容过长"), "空正文不得报「内容过长」：$reason")
    }

    @Test
    fun `文案含上限数值 2000 —— 防常量漂移`() {
        val over = AgentMemory.upsertFailureReason(AgentMemory.MAX_CONTENT_CHARS + 1)
        assertTrue(over.contains("2000"), "超限文案必须写出上限数值，防常量与文案漂移：$over")
        // 同时锁死上限常量本身：改它就必须同步改本断言（那正是「漂移」要暴露的时刻）
        assertEquals(2000, AgentMemory.MAX_CONTENT_CHARS)
    }
}
