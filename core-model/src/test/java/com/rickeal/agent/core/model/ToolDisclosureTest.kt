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
 *
 * ⚠️ 本文件只钉住 coverage 的**形状不变量**（值域 / 上界可达 / 单调性 / keywords 零回归），
 * **不含命中阈值**。
 *
 * 阈值（cut）校准**被有意推迟**：单 token 精确命中（如 query「utility」→ current_time）
 * 的原始分天然很低，任何有意义的阈值都会误删这类正确命中；而用合成语料定出的阈值
 * 没有外部效度，却会伪装成「已校准」。⇒ 开工前提是取得**真机 ON_DEMAND 的真实 query
 * 语料 ≥ N 条**，见交接文档挂账。
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

    // ── 中文分词（bigram，深度评审召回悬崖修复）───────────────────────────

    @Test
    fun tokenizeSplitsCjkRunsIntoBigrams() {
        // 「读取文件」→ {读取, 取文, 文件}：与描述的公共子串必然相交。
        assertEquals(listOf("读取", "取文", "文件"), HiddenToolCatalog.tokenize("读取文件"))
        // 两字 CJK 段 = 单个 bigram，与旧 split 行为等价（既有用例不回归的根据）。
        assertEquals(listOf("文件"), HiddenToolCatalog.tokenize("文件"))
        // 单字 CJK 段保留原字（score 侧 length<2 跳过的既有口径不变）。
        assertEquals(listOf("写"), HiddenToolCatalog.tokenize("写"))
    }

    @Test
    fun tokenizeKeepsNonCjkWholeWords() {
        // 英文整词不拆字符（拆了会产生单字符误命中）。
        assertEquals(listOf("file"), HiddenToolCatalog.tokenize("file"))
        // 混排：CJK bigram 与英文整词分段互不污染。
        assertEquals(listOf("读取", "取文", "文件", "file"), HiddenToolCatalog.tokenize("读取文件file"))
        // 大小写归一化在 normalize 层：tokenize 只管切分、直通原大小写；
        // 生产路径（search）先 normalize 再 tokenize，两段串联后等价于小写。
        assertEquals(listOf("FILE"), HiddenToolCatalog.tokenize("FILE"))
        assertEquals(listOf("file"), HiddenToolCatalog.tokenize(HiddenToolCatalog.normalize("FILE")))
    }

    @Test
    fun searchHitsChinesePhraseWithoutSpaces() {
        // 深度评审复现实验的零命中代表用例：「读取文件」此前是一个 term，
        // contains("读取文件") 对该描述 = false → 全项 0 分 → 零命中。
        val catalog = catalogOf(
            spec("file_read", "读取沙箱目录内的文本文件", category = "file"),
            spec("current_time", "获取当前日期与时间（现在几点、今天几号）", category = "utility"),
            spec("memory_write", "把一条应当长期记住的信息（用户偏好、项目事实、长期约定）写入持久记忆", category = "memory"),
            spec("clipboard", "读取或写入系统剪贴板。action 取 get 或 set；set 时需要 text", category = "system"),
        )
        assertTrue(catalog.search("读取文件").any { it.name == "file_read" })
        assertTrue(catalog.search("帮我读取文件").any { it.name == "file_read" })
        assertTrue(catalog.search("现在几点了").any { it.name == "current_time" })
        assertTrue(catalog.search("我想记住用户的偏好").any { it.name == "memory_write" })
        assertTrue(catalog.search("把这个复制到剪贴板").any { it.name == "clipboard" })
    }

    @Test
    fun searchKeepsSpacedAndEnglishQueryBehavior() {
        // 既有行为不回归：带空格 / 纯英文 query 与旧 split 分词等价。
        val catalog = catalogOf(
            spec("file_write", "把文本写入沙箱目录内的文件", category = "file"),
        )
        assertTrue(catalog.search("文件").any { it.name == "file_write" })
        assertTrue(catalog.search("写入 文件").any { it.name == "file_write" })
        assertTrue(catalog.search("file").any { it.name == "file_write" })
    }

    // ── 检索别名 keywords（Wave 31）───────────────────────────────────────────

    private fun specWithKeywords(
        name: String,
        description: String = "",
        category: String = "general",
        keywords: List<String> = emptyList(),
    ) = ToolSpec(name = name, description = description, category = category, keywords = keywords)

    @Test
    fun keywordsDefaultToEmptyForZeroRegression() {
        // 字段默认空 = 评分与引入本字段前逐字节一致（既有用例全部基于此默认值）。
        assertTrue(spec("file_read", "读取文件").keywords.isEmpty())
    }

    @Test
    fun keywordsBridgeVocabGapForOralQuery() {
        // 「算一下」既不在 name、也不在 description/category —— 仅靠 keywords 命中。
        val catalog = catalogOf(
            specWithKeywords(
                name = "calculator",
                description = "计算数学表达式",
                category = "utility",
                keywords = listOf("算一下", "计算", "算数"),
            ),
            spec("file_read", "读取文件", category = "file"),
        )
        assertEquals(listOf("calculator"), catalog.search("算一下").map { it.name })
    }

    @Test
    fun keywordsMatchByBigramTerms() {
        // 口语长句「帮我算一下」：整句不命中别名，但 bigram「算一/一下」命中别名 → 召回。
        val catalog = catalogOf(
            specWithKeywords(
                name = "calculator",
                description = "计算数学表达式",
                category = "utility",
                keywords = listOf("算一下"),
            ),
        )
        assertEquals(listOf("calculator"), catalog.search("帮我算一下").map { it.name })
    }

    @Test
    fun keywordsExactMatchOutranksKeywordContains() {
        // 别名完全匹配（+80）应排在仅别名包含（+70）之前。
        val catalog = catalogOf(
            specWithKeywords("a_tool", "", keywords = listOf("算一下")),
            specWithKeywords("b_tool", "", keywords = listOf("帮我算一下哦")),
        )
        assertEquals("a_tool", catalog.search("算一下").first().name)
    }

    // ── 覆盖率 coverage（Wave 31，仅信息面、不改命中门）─────────────────────

    @Test
    fun coverageIsZeroForBlankOrNonMatch() {
        val catalog = standardCatalog()
        val entry = catalog.search("file").first()
        assertEquals(0.0, catalog.coverage(entry, ""))
        assertEquals(0.0, catalog.coverage(entry, "   "))
        assertEquals(0.0, catalog.coverage(entry, "量子纠缠"))
    }

    @Test
    fun coverageStaysWithinUnitInterval() {
        val catalog = standardCatalog()
        catalog.search("file").forEach { entry ->
            val c = catalog.coverage(entry, "file")
            assertTrue(c > 0.0 && c <= 1.0, "coverage 应落在 (0,1]：$c")
        }
    }

    @Test
    fun coverageRanksExactNameAboveDescriptionOnly() {
        val catalog = catalogOf(
            spec("read", "通用读取", category = "general"),
            spec("file_read", "读取沙箱目录内的文本文件", category = "file"),
        )
        val exact = catalog.search("read").first { it.name == "read" }
        val weak = catalog.search("read").first { it.name == "file_read" }
        assertTrue(catalog.coverage(exact, "read") > catalog.coverage(weak, "read"))
    }

    @Test
    fun weakOrUnrelatedQueriesStillReturnEmpty() {
        // 零命中回归钉（门仍是 score > 0，本 Wave 未改判据）：这些查询与语料无公共子串。
        val catalog = standardCatalog()
        listOf("量子纠缠", "股票行情", "翻译成英文", "播放音乐", "打开相机", "天气预报", "发送邮件")
            .forEach { q -> assertTrue(catalog.search(q).isEmpty(), "「$q」应零命中") }
    }

    // ── coverage 形状不变量（Wave 32）───────────────────────────────────────
    //
    // 本组用例钉的是**归一化函数的形状**（值域 / 上界可达 / 无关词恒零 / 单维单调 /
    // keywords 零回归 / keywords 正向生效），**不是命中阈值** —— 阈值校准已推迟，
    // 理由见类 KDoc。形状是纯函数性质、可在 JVM 单测里判；阈值不是。
    //
    // 可测性前提（已核对 main 源码，非推断）：`coverage(entry, query)` 是 **public**
    // 实例方法，`maxScore()` / `score()` 是 private；而 `coverage` 只读 (entry, query)、
    // 不读目录条目本身，故可以把夹具条目直接喂给任一 catalog 实例求 coverage。

    /**
     * 形状不变量语料夹具。真实内置工具表在 `core-agent`（BuiltinTools），`core-model`
     * 不得反向依赖（模块边界），故这里用**同形语料**：中英混排名称 / 中文描述 /
     * 四类 category / 一条带检索别名的条目。
     */
    private val shapeSpecs: List<ToolSpec> = listOf(
        spec("file_read", "读取沙箱目录内的文本文件", category = "file"),
        spec("file_write", "把文本写入沙箱目录内的文件", category = "file"),
        spec("current_time", "获取当前日期与时间（现在几点、今天几号）", category = "utility"),
        spec("memory_write", "把一条应当长期记住的信息写入持久记忆", category = "memory"),
        spec("clipboard", "读取或写入系统剪贴板", category = "system"),
        specWithKeywords("calculator", "执行四则运算", category = "utility", keywords = listOf("算一下")),
    )

    /** [HiddenToolCatalog.from] 的同款映射（省掉 parameterHints：它不参与评分）。 */
    private fun shapeEntries(): List<HiddenToolEntry> = shapeSpecs.map {
        HiddenToolEntry(
            name = it.name,
            description = it.description,
            category = it.category,
            parameterHints = emptyList(),
            keywords = it.keywords,
        )
    }

    /** 构造单条评分夹具（无菌：描述/分类/别名默认不掺入与 query 相关的字符）。 */
    private fun entry(
        name: String,
        description: String = "",
        category: String = "general",
        keywords: List<String> = emptyList(),
    ) = HiddenToolEntry(
        name = name,
        description = description,
        category = category,
        parameterHints = emptyList(),
        keywords = keywords,
    )

    /** 代表性 query 形状：空 / 空白 / 单字符 / ASCII / 中文 / 长句 / 混排 / 超长 / 纯标点 / 空白字符。 */
    private val shapeQueries: List<String> = listOf(
        "",
        "   ",
        "f",
        "file_read",
        "FILE_READ",
        "文件",
        "读取文件",
        "帮我读取文件，然后算一下现在几点了？",
        "file,read;write|memory 文件 读取",
        "x".repeat(200),
        "file_read ".repeat(20).trim(),
        "!!!???",
        "文件\t读取\n写入",
    )

    /** 断言消息里截断超长 query，避免失败时刷屏。 */
    private fun String.abbrev(): String = "${take(12)}(len=$length)"

    @Test
    fun coverageStaysWithinUnitIntervalAcrossQueryShapes() {
        // 值域是最要紧的一条形状不变量：它等价于「maxScore 没有低估任何一条真实得分路径」。
        // 一旦有人在 score() 里加了新维度却忘了同步 maxScore()，coverage 会越出 1.0，
        // 症状是 renderHits 打出「相关度 137%」—— 这条断言就是那个哨兵。
        // ✅ 实测（20 万组随机 (条目, query) 暴力枚举 + 本夹具全矩阵）：无一越界，
        //    coverage 恒 ∈ [0,1]。未发现 maxScore 低估问题。
        val catalog = HiddenToolCatalog.from(shapeSpecs)
        val entries = shapeEntries()
        var sawPositive = false
        for (q in shapeQueries) {
            for (e in entries) {
                val c = catalog.coverage(e, q)
                assertTrue(c >= 0.0, "coverage 不得为负：q=${q.abbrev()} tool=${e.name} c=$c")
                assertTrue(c <= 1.0, "coverage 越界 >1（maxScore 被低估）：q=${q.abbrev()} tool=${e.name} c=$c")
                if (c > 0.0) sawPositive = true
            }
        }
        // 非重言式补强：整批里必须真有 >0 样本，否则上面的区间断言可能在全 0 上空转。
        assertTrue(sawPositive, "代表性 query 里应至少有一个非零 coverage 样本")
    }

    @Test
    fun coverageReachesOneOnlyWhenEveryScoredDimensionMatches() {
        // 上界可达 = maxScore 没有被**高估**的反向检验：若高估，1.0 永远到不了。
        // maxScore 是「该 query 下任何条目理论上能拿到的上界」（名称完全+包含、别名完全、
        // 描述包含、分类包含、逐词项全中），故把全部维度同时打满的条目必须正好 = 1.0。
        val catalog = HiddenToolCatalog.from(shapeSpecs)
        val q = "calculator"
        val saturated = entry(name = q, description = q, category = q, keywords = listOf(q))
        assertEquals(1.0, catalog.coverage(saturated, q))

        // 反向诚实钉：只靠「名称完全相同」**打不满**上界（描述/分类/别名都不含该 query）。
        // ⇒ coverage 度量的是「相对理论上限」，不是「这个名字有多像」；真实语料里
        // 工具名精确命中停在 ~0.6（实测 0.6154）是设计口径，不是缺陷。
        val nameOnly = entry(name = q, description = "执行四则运算", category = "utility")
        val c = catalog.coverage(nameOnly, q)
        assertTrue(c > 0.0, "名称精确命中必须 >0：$c")
        assertTrue(c < 1.0, "仅名称精确命中不应打满上界（否则上界定义失效）：$c")
    }

    @Test
    fun coverageIsExactlyZeroForEveryUnrelatedQuery() {
        // score() 没有 baseline 分（每一项都要求真实子串 / bigram 命中），故无关词恒为
        // **精确 0.0**，而不是「接近 0」。这既是不变量，也是「命中门 score > 0 等价于
        // 存在真实命中」这条前提的根据。
        val catalog = HiddenToolCatalog.from(shapeSpecs)
        val entries = shapeEntries()
        val unrelated = listOf("量子纠缠", "股票行情", "翻译成英文", "播放音乐", "打开相机", "天气预报", "发送邮件")
        for (q in unrelated) {
            for (e in entries) {
                assertEquals(0.0, catalog.coverage(e, q), "无关词必须恒 0：q=$q tool=${e.name}")
            }
            assertTrue(catalog.search(q).isEmpty(), "「$q」应零命中（门仍是 score > 0，本 Wave 未改）")
        }
    }

    @Test
    fun coverageIsNonDecreasingAlongNamePrefixChain() {
        // ⚠️ 单调性**只在「单一评分维度 + 延伸后仍是子串」**这一族 query 上成立。
        // 已申报的口径边界（非本用例目标）：分母 maxScore 每多一个 length≥2 的词项就 +65，
        // 而分子在**只有描述维度**命中时只 +8 —— 同条目「读」→「读取」→「读取文件」
        // 实测 0.1319 → 0.1308 → 0.1292，是**递减**的。故本用例刻意把条目做成无菌：
        // 描述与分类不含任何拉丁字符，只允许**名称维度**参与，隔离掉跨维度分母漂移。
        val catalog = HiddenToolCatalog.from(shapeSpecs)
        val e = entry(name = "calculator", description = "执行四则运算", category = "工具")
        val chain = listOf("c", "ca", "cal", "calc", "calcul", "calcula", "calculator")
        val cov = chain.map { catalog.coverage(e, it) }
        for (i in 0 until cov.size - 1) {
            assertTrue(
                cov[i + 1] >= cov[i],
                "名称前缀链上非单调：${chain[i]}(${cov[i]}) → ${chain[i + 1]}(${cov[i + 1]})",
            )
        }
        // 非重言式补强：端点必须有真增长，否则「全链相等」也能通过上面的断言。
        assertTrue(cov.last() > cov.first(), "前缀链末端（精确命中）应高于首端：${cov.first()} → ${cov.last()}")
    }

    @Test
    fun keywordsOmittedAndExplicitEmptyAreIdenticalForScoring() {
        // 「keywords 默认空 = 零回归」这条申报的回归钉：**不传**该参数与**显式
        // emptyList()** 必须在检索结果、coverage 数值、渲染文本三面上逐字节一致。
        // （既有用例只断言了 `.keywords.isEmpty()`，没钉住「空别名确实不进评分语料」。）
        val queries = shapeQueries + listOf("算一下", "帮我算一下", "utility", "current_time")
        for (s in shapeSpecs) {
            val omitted = ToolSpec(name = s.name, description = s.description, category = s.category)
            val explicit = ToolSpec(
                name = s.name,
                description = s.description,
                category = s.category,
                keywords = emptyList(),
            )
            assertEquals(omitted.keywords, explicit.keywords, "默认值必须是 emptyList()")
            val catA = HiddenToolCatalog.from(listOf(omitted))
            val catB = HiddenToolCatalog.from(listOf(explicit))
            for (q in queries) {
                val hitsA = catA.search(q)
                val hitsB = catB.search(q)
                assertEquals(hitsA.map { it.name }, hitsB.map { it.name }, "检索结果应一致：q=${q.abbrev()} tool=${s.name}")
                for (i in hitsA.indices) {
                    val a = catA.coverage(hitsA[i], q)
                    val b = catB.coverage(hitsB[i], q)
                    assertTrue(a == b, "coverage 必须逐位相同：q=${q.abbrev()} tool=${s.name} $a vs $b")
                }
                assertEquals(catA.renderHits(hitsA, q), catB.renderHits(hitsB, q), "渲染文本应一致：q=${q.abbrev()} tool=${s.name}")
            }
        }
    }

    @Test
    fun keywordsRaiseCoverageForOralQueryOnlyViaAlias() {
        // 别名起作用的正向用例。先钉住「不配别名时该口语 query 与这条目无任何公共子串」，
        // 否则下面的增量可能来自描述/分类，这条用例就失去了指向性（变成重言式）。
        val catalog = HiddenToolCatalog.from(shapeSpecs)
        val without = entry(name = "calculator", description = "执行四则运算", category = "utility")
        val withAlias = entry(
            name = "calculator",
            description = "执行四则运算",
            category = "utility",
            keywords = listOf("算一下"),
        )
        assertEquals(0.0, catalog.coverage(without, "算一下"), "无别名时「算一下」应完全不得分")
        assertEquals(0.0, catalog.coverage(without, "帮我算一下"), "无别名时「帮我算一下」应完全不得分")

        val exact = catalog.coverage(withAlias, "算一下")
        val oral = catalog.coverage(withAlias, "帮我算一下")
        assertTrue(exact > 0.0, "别名完全命中必须 >0：$exact")
        assertTrue(oral > 0.0, "别名 bigram 部分命中必须 >0：$oral")
        // 完全命中别名应高于「口语长句只撞上 bigram」的部分命中 —— 信息量顺序的形状钉。
        assertTrue(exact > oral, "别名完全命中应高于 bigram 部分命中：$exact vs $oral")
    }

    @Test
    fun renderHitsCoveragePercentageStaysWithin0To100() {
        // renderHits 是 coverage 唯一的对外可见面（「（相关度 X%）」）。值域不变量必须
        // 一路钉到这一层：coverage 越界时它是模型最先看到的症状。
        val catalog = HiddenToolCatalog.from(shapeSpecs)
        val percent = Regex("""（相关度 (-?\d+)%）""")
        val queries = shapeQueries + listOf("算一下", "帮我算一下现在几点", "utility", "current_time", "中".repeat(40), "!@#\$%^&*()")
        var sawPercent = false
        for (q in queries) {
            val text = catalog.renderHits(catalog.search(q), q)
            for (m in percent.findAll(text)) {
                sawPercent = true
                val pct = m.groupValues[1].toInt()
                assertTrue(pct in 0..100, "相关度必须落在 0..100：q=${q.abbrev()} pct=$pct")
            }
        }
        assertTrue(sawPercent, "这批 query 里应至少渲染出一条相关度百分比")
    }
}
