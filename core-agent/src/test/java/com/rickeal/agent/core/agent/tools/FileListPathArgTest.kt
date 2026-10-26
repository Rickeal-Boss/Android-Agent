package com.rickeal.agent.core.agent.tools

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `file_list` 省略 path 的归一化单测（W84-06a）。
 *
 * 为什么值得写：spec 把 `path` 声明为 `required = false` 且描述写「默认根目录」，但
 * invoke 原先直接取 [stringArg] 的默认空串，而 [SandboxedFileTool.resolveSafe] 对空路径
 * 是 **fail-closed 拒绝** ⇒ 模型只要省略 path 就 100% 失败，报错还是「路径为空」这种
 * 不给下一步的形态。本测试钉住「省略 ⇒ `"."`」这条归一化语义。
 *
 * ⚠️ 覆盖边界（如实申报）：本测试**不**直接跑 `FileListTool.invoke` —— 它需要
 * [com.rickeal.agent.core.agent.ToolContext]（含 android `Context`），本模块单测无
 * Robolectric，纯 JVM 无法构造。故「invoke 层省略 path 能成功列出根目录」这一点由
 * ① 本测试的归一化断言 + ② `File(base, ".").canonicalFile == base` 的根语义断言
 * 共同间接覆盖，没有端到端断言。
 */
class FileListPathArgTest {

    @Test
    fun `省略 path 回落为当前目录`() {
        // stringArg 缺键时返回空串 —— 这正是「模型没传 path」在 invoke 里的实际形态。
        assertEquals(".", fileListPathArg(""))
    }

    @Test
    fun `纯空白 path 同样回落`() {
        // 小模型常传 " " / "\n"：trim 后为空，必须与「省略」同待遇，否则又是一次
        // 无下一步的「路径为空」拒绝。
        assertEquals(".", fileListPathArg("   "))
        assertEquals(".", fileListPathArg("\n\t "))
    }

    @Test
    fun `显式传入的子目录原样保留`() {
        assertEquals("notes", fileListPathArg("notes"))
        assertEquals("docs/a.md", fileListPathArg("docs/a.md"))
        // 前后空白被 trim（与 resolveSafe 同口径），中间不动。
        assertEquals("notes", fileListPathArg("  notes  "))
    }

    @Test
    fun `回落值在文件语义上就是沙箱根目录`() {
        // resolveSafe 的通过判据是 `target.path == basePath`：只要 "." 规范化后等于
        // 沙箱根，归一化结果就能走既有安全分支（不需要给 resolveSafe 开空路径特例）。
        val base = Files.createTempDirectory("file-list-root").toFile()
        try {
            val resolved = File(base, fileListPathArg("")).canonicalFile
            assertEquals(base.canonicalFile, resolved, "省略 path 必须解析成沙箱根目录本身")
            assertTrue(resolved.isDirectory, "沙箱根目录必须是可列目录")
        } finally {
            base.deleteRecursively()
        }
    }
}
