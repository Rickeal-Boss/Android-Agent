package com.rickeal.agent.core.agent.tools

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
            // 必须流式只读前 N 个字符：`readText().take()` 会先把整个文件读成 String。
            // 100MB 的文件 → UTF-16 下约 200MB 字符数组 → 直接 OOM（端侧可用内存本就被 4B 模型吃掉大半）。
            val text = file.bufferedReader().use { reader ->
                val buffer = CharArray(200_000)
                val read = reader.read(buffer)
                if (read <= 0) "" else String(buffer, 0, read)
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
