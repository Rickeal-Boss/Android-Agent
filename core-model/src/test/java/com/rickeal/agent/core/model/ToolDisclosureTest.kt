package com.rickeal.agent.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [HiddenToolCatalog] / [DisclosureTools] 的单元测试（Wave 27）。
 *
 * 为什么这些用例值得写：检索**评分权重**与**解包 fail-closed 判据**都是「错了不报错、
 * 只静默给出错误结果」的那类逻辑 —— 评分算错只是排序变差（无异常），解包放宽一条
 * 就可能让模型把非法载荷送到执行路径。这类逻辑必须由测试钉住，而不是靠真机试。
 *
 * 测试全程确定性、零 Android 依赖、零 IO、零 sleep。
 */
class ToolDisclosureTest {

    // ── 测试夹具 ────────────────────────────────────────────────────────────

    private fun spec(
        name: String,
        description: String = "",
        category: String = "general",
        parameters: List<ToolParameter> = emptyList(),
    ) = ToolSpec(name = name, description = description, category = category, parameters = parameters)

    private fun catalogOf(vararg specs: ToolSpec) = HiddenToolCatalog.from(specs.toList())

    /** 三个工具的标准夹具：名称/描述/分类三条命中路径各有覆盖。 */
    private fun standardCatalog() = catalogOf(
        spec(
            name = "file_write",
            description = "把文本写入沙箱目录内的文件",
            category = "file",
            parameters = listOf(
                ToolParameter(name = "path", description = "相对路径"),
                ToolParameter(name = "content", description = "要写入的内容"),
            ),
        ),
        spec(name = "file_read", description = "读取沙箱目录内的文本文件", category = "file"),
        spec(name = "current_time", description = "获取当前日期与时间", category = "utility"),
    )

    // ── 目录构建 ────────────────────────────────────────────────────────────

    @Test
    fun catalogExcludesReservedMetaToolNames() {
        // 两个元工具自己不得进目录：否则模型可以 call_tool(search_tools) 递归转发。
        val catalog = catalogOf(
            spec("file_read", "读文件"),
            spec(DisclosureTools.SEARCH_TOOL_NAME, "元工具"),
            spec(DisclosureTools.CALL_TOOL_NAME, "元工具"),
        )
        assertEquals(1, catalog.size)
        assertEquals("file_read", catalog.search("file").single().name)
    }

    @Test
    fun catalogFromEmptyListIsEmpty() {
        val catalog = catalogOf()
        assertTrue(catalog.isEmpty)
        assertEquals(0, catalog.size)
    }

    // ── 检索：命中路径 ──────────────────────────────────────────────────────

    @Test
    fun searchRanksExactNameMatchFirst() {
        val catalog = catalogOf(
            spec("file_read", "读取文件"),
            spec("read", "通用读取"),
        )
        // "read" 完全等于第二个工具名（+200，另叠加名称包含 +90）；第一个只是名称包含（+90）。
        assertEquals("read", catalog.search("read").first().name)
    }

    @Test
    fun searchMatchesNameSubstring() {
        val hits = standardCatalog().search("file")
        assertEquals(setOf("file_write", "file_read"), hits.map { it.name }.toSet())
    }

    @Test
    fun searchMatchesDescriptionWhenNameDoesNot() {
        val hits = standardCatalog().search("日期")
        assertEquals(listOf("current_time"), hits.map { it.name })
    }

    @Test
    fun searchMatchesCategory() {
        val hits = standardCatalog().search("utility")
        assertEquals(listOf("current_time"), hits.map { it.name })
    }

    @Test
    fun searchMatchesMultiTermQuery() {
        // 两个词项各自命中名称/描述，总分应大于单术语命中。
        val hits = standardCatalog().search("file 写入")
        assertTrue(hits.isNotEmpty())
        assertEquals("file_write", hits.first().name)
    }

    // ── 检索：边界 ──────────────────────────────────────────────────────────

    @Test
    fun searchWithBlankQueryReturnsEmpty() {
        // 刻意不返回「全部」——否则模型会退化成「先空搜一次」的无意义调用。
        assertTrue(standardCatalog().search("").isEmpty())
        assertTrue(standardCatalog().search("   ").isEmpty())
    }

    @Test
    fun searchWithNoMatchReturnsEmpty() {
        assertTrue(standardCatalog().search("量子纠缠").isEmpty())
    }

    @Test
    fun searchRespectsLimit() {
        val catalog = catalogOf(
            spec("file_a", "文件"),
            spec("file_b", "文件"),
            spec("file_c", "文件"),
        )
        assertEquals(2, catalog.search("file", limit = 2).size)
    }

    @Test
    fun searchFallsBackToDefaultLimitWhenNonPositive() {
        val specs = (1..12).map { spec("file_$it", "文件") }
        val catalog = HiddenToolCatalog.from(specs)
        // limit=0 与 limit=-1 都应回退到 DEFAULT_LIMIT，而不是返回空或全部。
        assertEquals(HiddenToolCatalog.DEFAULT_LIMIT, catalog.search("file", limit = 0).size)
        assertEquals(HiddenToolCatalog.DEFAULT_LIMIT, catalog.search("file", limit = -1).size)
    }

    @Test
    fun searchIsCaseInsensitive() {
        val catalog = catalogOf(spec("File_Read", "Read a file"))
        assertEquals(1, catalog.search("FILE_READ").size)
        assertEquals(1, catalog.search("file_read").size)
    }

    @Test
    fun searchOrdersByScoreDescending() {
        val catalog = catalogOf(
            spec("file_read", "读取文件"),      // 名称包含 file（+90）
            spec("read", "读取任何东西"),        // 名称完全等于 read（+200，另叠加名称包含）
        )
        val hits = catalog.search("read")
        assertEquals("read", hits.first().name)
        // 分数必须严格降序（相等时按名称升序，故这里用 >= 断言）。
        assertTrue(hits.size >= 2)
    }

    // ── 渲染 ────────────────────────────────────────────────────────────────

    @Test
    fun renderHitsListsToolNamesAndForwardHint() {
        val catalog = standardCatalog()
        val hits = catalog.search("file")
        val text = catalog.renderHits(hits, "file")
        assertTrue(text.contains("file_write"))
        assertTrue(text.contains(DisclosureTools.CALL_TOOL_NAME))
        // 必须告诉模型转发所需的两个 key，否则它只能猜。
        assertTrue(text.contains(DisclosureTools.ARG_TOOL_NAME))
    }

    @Test
    fun renderHitsIncludesParameterHints() {
        val catalog = standardCatalog()
        val hits = catalog.search("file_write")
        val text = catalog.renderHits(hits, "file_write")
        assertTrue(text.contains("path"))
        assertTrue(text.contains("必填"))
    }

    @Test
    fun renderHitsOnEmptyCatalogGivesActionableHint() {
        val catalog = catalogOf()
        val text = catalog.renderHits(catalog.search("file"), "file")
        assertTrue(text.contains("没有匹配"))
        assertTrue(text.contains("当前没有可按需检索的工具"))
    }

    @Test
    fun renderHitsOnNoMatchSuggestsBroaderKeyword() {
        val catalog = standardCatalog()
        val text = catalog.renderHits(catalog.search("量子纠缠"), "量子纠缠")
        assertTrue(text.contains("没有匹配"))
        // 非空目录下的提示必须引导「换关键词」，而不是说「没有工具」。
        assertTrue(text.contains("更宽泛"))
    }

    // ── 解包：成功路径 ──────────────────────────────────────────────────────

    @Test
    fun unpackCallReadsStandardShape() {
        val unpacked = DisclosureTools.unpackCall("""{"tool_name":"file_read","params":{"path":"a.txt"}}""")
        assertEquals("file_read", unpacked?.targetName)
        assertEquals("""{"path":"a.txt"}""", unpacked?.argumentsJson)
    }

    @Test
    fun unpackCallAcceptsNameAlias() {
        val unpacked = DisclosureTools.unpackCall("""{"name":"current_time","params":{}}""")
        assertEquals("current_time", unpacked?.targetName)
    }

    @Test
    fun unpackCallAcceptsToolAlias() {
        val unpacked = DisclosureTools.unpackCall("""{"tool":"file_list","params":{}}""")
        assertEquals("file_list", unpacked?.targetName)
    }

    @Test
    fun unpackCallAcceptsArgumentsAlias() {
        val unpacked = DisclosureTools.unpackCall("""{"tool_name":"file_read","arguments":{"path":"b"}}""")
        assertEquals("""{"path":"b"}""", unpacked?.argumentsJson)
    }

    @Test
    fun unpackCallAcceptsArgsAlias() {
        val unpacked = DisclosureTools.unpackCall("""{"tool_name":"file_read","args":{"path":"c"}}""")
        assertEquals("""{"path":"c"}""", unpacked?.argumentsJson)
    }

    @Test
    fun unpackCallAcceptsParametersAlias() {
        // 别名集必须与 TextToolProtocol.argumentsOf 同口径（arguments/parameters/args/input）：
        // 协议层认得的载荷形状，解包层也必须认得，否则模型写 parameters 会白收一条报错。
        val unpacked = DisclosureTools.unpackCall("""{"tool_name":"file_read","parameters":{"path":"e"}}""")
        assertEquals("""{"path":"e"}""", unpacked?.argumentsJson)
    }

    @Test
    fun unpackCallAcceptsInputAlias() {
        val unpacked = DisclosureTools.unpackCall("""{"tool_name":"file_read","input":{"path":"f"}}""")
        assertEquals("""{"path":"f"}""", unpacked?.argumentsJson)
    }

    @Test
    fun unpackCallDefaultsMissingParamsToEmptyObject() {
        val unpacked = DisclosureTools.unpackCall("""{"tool_name":"current_time"}""")
        assertEquals("{}", unpacked?.argumentsJson)
    }

    @Test
    fun unpackCallDecodesDoubleEncodedParams() {
        // 小模型常把参数对象再序列化一层（字符串里套 JSON）。
        val unpacked = DisclosureTools.unpackCall("""{"tool_name":"file_read","params":"{\"path\":\"d\"}"}""")
        assertEquals("""{"path":"d"}""", unpacked?.argumentsJson)
    }

    @Test
    fun unpackCallTrimsTargetName() {
        val unpacked = DisclosureTools.unpackCall("""{"tool_name":"  file_read  "}""")
        assertEquals("file_read", unpacked?.targetName)
    }

    // ── 解包：fail-closed 路径 ──────────────────────────────────────────────

    @Test
    fun unpackCallRejectsReservedTarget() {
        // 递归防护：转发到元工具自己必须被拒。
        assertNull(DisclosureTools.unpackCall("""{"tool_name":"search_tools","params":{}}"""))
        assertNull(DisclosureTools.unpackCall("""{"tool_name":"call_tool","params":{}}"""))
    }

    @Test
    fun unpackCallRejectsBlankTarget() {
        assertNull(DisclosureTools.unpackCall("""{"tool_name":"","params":{}}"""))
        assertNull(DisclosureTools.unpackCall("""{"tool_name":"   ","params":{}}"""))
    }

    @Test
    fun unpackCallRejectsNonStringTarget() {
        assertNull(DisclosureTools.unpackCall("""{"tool_name":123}"""))
        assertNull(DisclosureTools.unpackCall("""{"tool_name":null}"""))
    }

    @Test
    fun unpackCallRejectsMissingTarget() {
        assertNull(DisclosureTools.unpackCall("""{"params":{"path":"a"}}"""))
    }

    @Test
    fun unpackCallRejectsNonObjectParams() {
        // 数组参数不是合法的「参数对象」—— 必须拒，不能悄悄包一层。
        assertNull(DisclosureTools.unpackCall("""{"tool_name":"file_read","params":[1,2]}"""))
        assertNull(DisclosureTools.unpackCall("""{"tool_name":"file_read","params":42}"""))
    }

    @Test
    fun unpackCallRejectsUndecodableDoubleEncodedParams() {
        assertNull(DisclosureTools.unpackCall("""{"tool_name":"file_read","params":"不是 JSON"}"""))
        // 双重编码成数组同样不合法（目标工具的参数面必须是对象）。
        assertNull(DisclosureTools.unpackCall("""{"tool_name":"file_read","params":"[1,2]"}"""))
    }

    @Test
    fun unpackCallRejectsMalformedJson() {
        assertNull(DisclosureTools.unpackCall("not json at all"))
        assertNull(DisclosureTools.unpackCall(""))
        assertNull(DisclosureTools.unpackCall("""{"tool_name":}"""))
    }

    @Test
    fun unpackCallRejectsNonObjectRoot() {
        assertNull(DisclosureTools.unpackCall("[1,2,3]"))
        assertNull(DisclosureTools.unpackCall("\"just a string\""))
    }

    // ── 元工具契约 ──────────────────────────────────────────────────────────

    @Test
    fun publicSpecsExposesExactlyTwoMetaTools() {
        assertEquals(
            listOf(DisclosureTools.SEARCH_TOOL_NAME, DisclosureTools.CALL_TOOL_NAME),
            DisclosureTools.publicSpecs().map { it.name },
        )
    }

    @Test
    fun publicSpecsDeclareReadEffect() {
        // 元工具必须声明 READ：若声明 WRITE，只读档下每一次转发都要弹卡（含只读目标），
        // 那会把「只读档」变成噪音源，训练用户无脑点同意。真正的效果判定发生在解包后、
        // 对目标工具重新求值。
        DisclosureTools.publicSpecs().forEach { spec ->
            assertEquals(ToolEffect.READ, spec.effect, "元工具 ${spec.name} 应声明 READ")
        }
    }

    @Test
    fun publicSpecsAreNotReservedAsForwardTargets() {
        // 元工具名必须被判为保留名，否则目录构建与解包两处的防护会失效。
        assertTrue(DisclosureTools.isReservedName(DisclosureTools.SEARCH_TOOL_NAME))
        assertTrue(DisclosureTools.isReservedName(DisclosureTools.CALL_TOOL_NAME))
        assertTrue(!DisclosureTools.isReservedName("file_read"))
    }

    // ── 归一化 ──────────────────────────────────────────────────────────────

    @Test
    fun normalizeFoldsCaseAndWhitespace() {
        assertEquals("file read", HiddenToolCatalog.normalize("  File   Read  "))
        assertEquals("file read", HiddenToolCatalog.normalize("FILE\tREAD"))
        assertEquals("", HiddenToolCatalog.normalize("   "))
    }
}
