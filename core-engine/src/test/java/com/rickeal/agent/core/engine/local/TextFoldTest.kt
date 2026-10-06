package com.rickeal.agent.core.engine.local

import com.google.ai.edge.litertlm.Content
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * [foldAdjacentText] 与 [isTemplateRenderFailure] 的 JVM 纯逻辑单测（Wave 51 P1 的离线锚）。
 *
 * ## 为什么值得写
 *
 * 真机报错（Wave 51 P1 取证）：工具结果回灌轮生成期 `INTERNAL: Failed to apply template:
 * invalid operation: tried to use + operator on unsupported types string and sequence
 * (in template:23)`。根因是 Qwen2.5 容器的 chat_template 用 `'…' + message.content + '…'`
 * 拼接 content（`:23` / `:27`），而 `Contents.toJson()` 恒返回 **JSON 数组** —— minijinja 的
 * `+` 不支持 string + sequence。修法是把每条下发 Message 的**相邻 Text 折成单段** ⇒ content
 * 恰好 1 个元素 ⇒ native 侧塌缩成 string 下发。
 *
 * 折叠语义（相邻才折、非 Text 是硬边界、空 Text 不丢、绝不返回空列表）**全部可在 JVM 上钉死**，
 * 无需 native —— 这是本波唯一能离线验证的一环（native 是否真的把单元素塌缩成 string 必须真机验收）。
 *
 * 纯函数、零 native：不构造引擎、不加载 litertlm（[Content] 是纯 data class，构造不触 Android/native）。
 *
 * ⚠️ JUnit4 纪律：所有 `@Test` 方法以返回 **void** 的断言收尾（`assertEquals`/`assertTrue`/
 * `assertFalse`/`assertSame` 均返回 `Unit`）—— `assertNotNull`/`assertIs` 会返回值，以它们收尾
 * 会让整类 `initializationError`。
 */
class TextFoldTest {

    private fun image() = Content.ImageBytes(byteArrayOf(1, 2, 3))

    // ───────────────────────── foldAdjacentText：相邻 Text 合并 ─────────────────────────

    @Test
    fun `相邻两段文本合并为一段`() {
        val folded = foldAdjacentText(listOf(Content.Text("a"), Content.Text("b")))
        assertEquals(1, folded.size)
        assertEquals("a\n\nb", (folded[0] as Content.Text).text)
    }

    @Test
    fun `非 Text 是硬边界_不相邻的 Text 各自独立`() {
        val img = image()
        val folded = foldAdjacentText(listOf(Content.Text("a"), img, Content.Text("b")))
        assertEquals(3, folded.size)
        // 顺序不变：Text / Image / Text。
        assertEquals("a", (folded[0] as Content.Text).text)
        assertSame(img, folded[1])
        assertEquals("b", (folded[2] as Content.Text).text)
    }

    @Test
    fun `前置非 Text 后跟相邻 Text 仍合并`() {
        val img = image()
        val folded = foldAdjacentText(listOf(img, Content.Text("a"), Content.Text("b")))
        assertEquals(2, folded.size)
        assertSame(img, folded[0])
        assertEquals("a\n\nb", (folded[1] as Content.Text).text)
    }

    @Test
    fun `多个非 Text 分隔的相邻文本段各自合并`() {
        val img1 = image()
        val img2 = image()
        val folded = foldAdjacentText(
            listOf(Content.Text("a"), img1, img2, Content.Text("b"), Content.Text("c"))
        )
        assertEquals(4, folded.size)
        assertEquals("a", (folded[0] as Content.Text).text)
        assertSame(img1, folded[1])
        assertSame(img2, folded[2])
        assertEquals("b\n\nc", (folded[3] as Content.Text).text)
    }

    @Test
    fun `单元素短路返回同一实例`() {
        val input = listOf<Content>(Content.Text(""))
        assertSame(input, foldAdjacentText(input))
    }

    @Test
    fun `两个空文本折成一个空文本而不是空列表`() {
        // 🔴 关键：不得丢失空 Text，更不能折成空列表（空 Contents ⇒ Message.toJson 不加 content 键）。
        val folded = foldAdjacentText(listOf(Content.Text(""), Content.Text("")))
        assertEquals(1, folded.size)
        assertEquals("", (folded[0] as Content.Text).text)
    }

    @Test
    fun `空文本打头不产生前导空行`() {
        val folded = foldAdjacentText(listOf(Content.Text(""), Content.Text("x")))
        assertEquals(1, folded.size)
        assertEquals("x", (folded[0] as Content.Text).text)
    }

    @Test
    fun `尾随空文本产生尾随空行`() {
        // 如实申报的行为边界（见 foldAdjacentText KDoc）：空 Text 照常写入分隔符 ⇒ 尾随空行。
        // 生产不可达（空 Text 被上游 isNotBlank() 过滤），此处把当前行为钉死防意外漂移。
        val input = listOf<Content>(Content.Text("a"), Content.Text(""))
        val folded = foldAdjacentText(input)
        assertEquals(1, folded.size)
        assertEquals("a\n\n", (folded[0] as Content.Text).text)
        // size>=2 恒返回**新**列表（不与入参共享实例）。
        assertTrue(folded !== input)
    }

    @Test
    fun `空文本夹在中间产生双分隔符`() {
        val folded = foldAdjacentText(
            listOf(Content.Text("a"), Content.Text(""), Content.Text("b"))
        )
        assertEquals(1, folded.size)
        assertEquals("a\n\n\n\nb", (folded[0] as Content.Text).text)
    }

    @Test
    fun `空列表短路返回同一实例`() {
        val input = emptyList<Content>()
        assertSame(input, foldAdjacentText(input))
    }

    @Test
    fun `全为非 Text 时原样保留`() {
        val img1 = image()
        val img2 = image()
        val folded = foldAdjacentText(listOf(img1, img2))
        assertEquals(2, folded.size)
        assertSame(img1, folded[0])
        assertSame(img2, folded[1])
    }

    @Test
    fun `单个 ToolResponse 短路原样返回`() {
        val input = listOf<Content>(Content.ToolResponse("read_file", "ok"))
        assertSame(input, foldAdjacentText(input))
    }

    @Test
    fun `ToolResponse 是硬边界不参与合并`() {
        val response = Content.ToolResponse("read_file", "ok")
        val folded = foldAdjacentText(listOf(Content.Text("a"), response, Content.Text("b")))
        assertEquals(3, folded.size)
        assertEquals("a", (folded[0] as Content.Text).text)
        assertSame(response, folded[1])
        assertEquals("b", (folded[2] as Content.Text).text)
    }

    // ───────────────────────── isTemplateRenderFailure：真机文案判据 ─────────────────────────

    @Test
    fun `真机模板失败原文命中`() {
        val raw = "INTERNAL: Failed to apply template: invalid operation: tried to use " +
            "+ operator on unsupported types string and sequence (in template:23)"
        assertTrue(isTemplateRenderFailure(raw))
    }

    @Test
    fun `模板失败判据大小写不敏感`() {
        assertTrue(isTemplateRenderFailure("failed to apply template"))
        assertTrue(isTemplateRenderFailure("FAILED TO APPLY TEMPLATE"))
    }

    @Test
    fun `超容文案不命中模板失败`() {
        assertFalse(isTemplateRenderFailure("Input token ids are too long"))
    }

    @Test
    fun `空串不命中模板失败`() {
        assertFalse(isTemplateRenderFailure(""))
    }
}
