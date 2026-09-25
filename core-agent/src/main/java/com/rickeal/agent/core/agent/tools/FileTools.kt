package com.rickeal.agent.core.agent.tools

import com.rickeal.agent.core.agent.AgentPolicy
import com.rickeal.agent.core.agent.Tool
import com.rickeal.agent.core.agent.ToolContext
import com.rickeal.agent.core.model.ToolParameter
import com.rickeal.agent.core.model.ToolParamType
import com.rickeal.agent.core.model.ToolResult
import com.rickeal.agent.core.model.ToolSpec
import java.io.File

/**
 * 所有文件工具的基类：做沙箱路径逃逸检查。
 * 这是安全护栏的第一道，任何新增文件工具都必须继承它。
 *
 * 沙箱三层防线（ZCode 权限三元组的端侧落地：工具白名单 × 审批策略 × 沙箱目录）：
 *  1. 语义层 —— 只收相对路径，绝对路径/超长路径直接拒（fail-closed）；
 *  2. 规范化层 —— canonicalFile 解析 `..` 与符号链接后做前缀比对，逃逸必拦；
 *  3. 审批层 —— file_write 是 dangerous + requiresConfirmation 双标，
 *     执行前必须过 [com.rickeal.agent.core.agent.approval.ToolApprovalHandler]。
 */
abstract class SandboxedFileTool(protected val context: ToolContext) : Tool {

    companion object {
        /** 路径长度护栏：超长路径基本不是正常诉求，直接拒。 */
        private const val MAX_PATH_CHARS = 512
    }

    protected fun resolveSafe(relativePath: String): File {
        val trimmed = relativePath.trim()
        if (trimmed.isEmpty()) throw SecurityException("路径为空")
        if (trimmed.length > MAX_PATH_CHARS) {
            throw SecurityException("路径过长（超过 $MAX_PATH_CHARS 字符）")
        }
        // 拒绝绝对路径：本沙箱 API 的语义就是「相对沙箱根」。File(base, "/abs") 在
        // JVM 语义下会直接得到 /abs（绝对 child 覆盖 base），虽然后面的 canonical
        // 前缀比对也能拦住，但在语义层先拒掉更清晰，也给错误信息留出指导空间。
        if (trimmed.startsWith("/")) {
            throw SecurityException("拒绝绝对路径：请使用相对沙箱根目录的路径")
        }
        val base = context.sandboxDir.canonicalFile
        val target = File(base, trimmed).canonicalFile
        val basePath = base.path
        if (target.path != basePath && !target.path.startsWith(basePath + File.separator)) {
            throw SecurityException("拒绝访问沙箱之外的路径：$trimmed")
        }
        return target
    }

    protected fun param(name: String, description: String, required: Boolean = true): ToolParameter =
        ToolParameter(name, ToolParamType.STRING, description, required)
}

class FileReadTool(context: ToolContext) : SandboxedFileTool(context) {

    companion object {
        /**
         * 读取限量：与 [AgentPolicy.maxToolOutputChars] **同源**（取其默认值）。
         * Runner 对工具输出的截断上限就是它 —— file_read 在源头只读这么多，
         * 省掉「读 20 万字符进内存、再被 Runner 砍到 4000」的纯浪费（三线审查 Wave10）。
         * 顺带保证本工具输出恒 ≤ 限量：Runner 的「…(已截断)」标记对 file_read
         * **永不触发**，两层截断标记不会叠加出现（见 invoke 内的标记预算注释）。
         */
        private val READ_LIMIT_CHARS = AgentPolicy().maxToolOutputChars

        /** 剩余字符计数上限：超过就放弃精确计数、改报「超过 N」，不再继续读盘。 */
        private const val COUNT_CAP_CHARS = 5_000_000L
    }

    override val spec: ToolSpec = ToolSpec(
        name = "file_read",
        description = "读取沙箱目录内的文本文件",
        parameters = listOf(param("path", "相对于沙箱目录的文件路径")),
        category = "file",
    )

    override suspend fun invoke(argumentsJson: String): ToolResult {
        val path = stringArg(argumentsJson, "path")
        return try {
            val file = resolveSafe(path)
            if (!file.exists()) return ToolResult(name = spec.name, ok = false, errorMessage = "文件不存在：$path")
            if (!file.isFile) return ToolResult(name = spec.name, ok = false, errorMessage = "不是文件：$path")
            // 流式限量读，三态输出（三线审查 Wave10）：
            //  1. 完整读毕（EOF 在限量内达到）→ 原文，无标记；
            //  2. 因限量截断 → 内容 + 「共约 N / 仅载入前 M」显式标记 —— 4000 字符外的
            //     信息不再在 file_read 层静默丢失，模型至少知道文件有多大、读到了哪；
            //  3. 空文件 → 空输出，与旧行为一致。
            // 循环填缓冲而非单次 read(buffer)：单次 read 不保证填满（流式语义允许
            // 提前返回），旧实现会把「网络盘 / 大文件慢读」误判成 EOF。
            val limit = READ_LIMIT_CHARS
            val head = CharArray(limit)
            var filled = 0
            // -1 = 未截断；否则 = 全文件字符总数（超出计数上限时走 overCountCap 分支）
            var totalChars = -1L
            var overCountCap = false
            file.bufferedReader().use { reader ->
                while (filled < limit) {
                    val r = reader.read(head, filled, limit - filled)
                    if (r < 0) break
                    filled += r
                }
                if (filled == limit) {
                    // 缓冲填满 ≠ 一定还有剩余（文件恰好等于限量）：再探一个字符定性。
                    if (reader.read() >= 0) {
                        // 截断成立。剩余字符只计数不保留（O(1) 内存）—— 「共约 N 字符」
                        // 的规模感对模型决定「换工具 / 分批 / 放弃」至关重要。
                        var rest = 1L
                        val sink = CharArray(8192)
                        count@ while (true) {
                            val r = reader.read(sink)
                            if (r < 0) break
                            rest += r
                            if (rest > COUNT_CAP_CHARS) {
                                overCountCap = true
                                break@count
                            }
                        }
                        totalChars = limit + rest
                    }
                }
            }
            val text = if (totalChars < 0L) {
                String(head, 0, filled)
            } else {
                val scale = if (overCountCap) "超过 $COUNT_CAP_CHARS" else "约 $totalChars"
                val marker = "…（文件共$scale 字符，仅载入前 $limit 字符，其余未读）"
                // 标记预算：内容只保留「限量 − 标记长度 − 1（拼接换行）」，保证总长恒 ≤ 限量 ——
                // 否则 Runner 会按 maxToolOutputChars 把标记本身砍掉，两层标记语义打架。
                // （keep 落点可能切开 UTF-16 代理对，尾部个别 emoji 显示为占位乱码，纯修饰性，不处理。）
                val keep = (limit - marker.length - 1).coerceAtLeast(0)
                String(head, 0, keep) + "\n" + marker
            }
            ToolResult(name = spec.name, ok = true, output = text)
        } catch (t: Throwable) {
            ToolResult(name = spec.name, ok = false, errorMessage = t.message ?: "读取失败")
        }
    }
}

class FileWriteTool(context: ToolContext) : SandboxedFileTool(context) {

    companion object {
        /**
         * 单次写入字符上限。模型失控时的「无限写」是最廉价的资源耗尽攻击：
         * 一次 writeText 可以把磁盘填满、把其它组件挤死。256K 字符（≈512KB~768KB
         * UTF-8）对沙箱笔记/代码片段绰绰有余，超出就该让模型分批或自行收敛。
         */
        private const val MAX_WRITE_CHARS = 262_144
    }

    override val spec: ToolSpec = ToolSpec(
        name = "file_write",
        description = "把内容写入沙箱目录内的文件（会覆盖）",
        parameters = listOf(
            param("path", "相对于沙箱目录的文件路径"),
            param("content", "要写入的文本内容"),
        ),
        dangerous = true,
        requiresConfirmation = true,
        category = "file",
    )

    override suspend fun invoke(argumentsJson: String): ToolResult {
        val path = stringArg(argumentsJson, "path")
        val content = stringArg(argumentsJson, "content")
        return try {
            if (content.length > MAX_WRITE_CHARS) {
                return ToolResult(
                    name = spec.name,
                    ok = false,
                    errorMessage = "内容过长（${content.length} 字符，上限 $MAX_WRITE_CHARS）：请拆分多次写入或压缩内容",
                )
            }
            val file = resolveSafe(path)
            if (file.isDirectory) {
                return ToolResult(name = spec.name, ok = false, errorMessage = "目标是目录，不能作为文件写入：$path")
            }
            file.parentFile?.mkdirs()
            // 原子写（tmp + rename，对齐仓库内 JsonFileStore 的纪律）：
            // 直接 writeText 若进程死在写一半，会留下半截文件污染沙箱内容。
            val tmp = File(file.parentFile, file.name + ".tmp_" + System.nanoTime())
            tmp.writeText(content)
            if (!tmp.renameTo(file)) {
                tmp.delete()
                return ToolResult(name = spec.name, ok = false, errorMessage = "写入失败：无法落盘（目标被占用？）")
            }
            ToolResult(name = spec.name, ok = true, output = "已写入 ${file.absolutePath}（${content.length} 字符）")
        } catch (t: Throwable) {
            ToolResult(name = spec.name, ok = false, errorMessage = t.message ?: "写入失败")
        }
    }
}

class FileListTool(context: ToolContext) : SandboxedFileTool(context) {

    companion object {
        /** 列目录条数上限：万级条目的目录一次列完会把上下文窗口挤爆。 */
        private const val MAX_LIST_ENTRIES = 200
    }

    override val spec: ToolSpec = ToolSpec(
        name = "file_list",
        description = "列出沙箱目录内的文件",
        parameters = listOf(param("path", "相对于沙箱目录的子目录，默认根目录", required = false)),
        category = "file",
    )

    override suspend fun invoke(argumentsJson: String): ToolResult {
        val path = stringArg(argumentsJson, "path")
        return try {
            val dir = resolveSafe(path)
            if (!dir.exists()) return ToolResult(name = spec.name, ok = false, errorMessage = "目录不存在：$path")
            val all = (dir.listFiles() ?: emptyArray()).sortedBy { it.name }
            val listed = all.take(MAX_LIST_ENTRIES)
            val listing = listed
                .joinToString("\n") { if (it.isDirectory) "[DIR] ${it.name}" else "[FILE] ${it.name} (${it.length()} B)" }
            val suffix = if (all.size > listed.size) {
                "\n…（共 ${all.size} 项，仅显示前 ${listed.size} 项；请用更具体的子目录缩小范围）"
            } else {
                ""
            }
            ToolResult(name = spec.name, ok = true, output = (listing + suffix).ifBlank { "（空目录）" })
        } catch (t: Throwable) {
            ToolResult(name = spec.name, ok = false, errorMessage = t.message ?: "列目录失败")
        }
    }
}
