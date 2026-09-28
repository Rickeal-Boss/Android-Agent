package com.rickeal.agent.core.agent.tools

import com.rickeal.agent.core.agent.AgentPolicy
import com.rickeal.agent.core.agent.Tool
import com.rickeal.agent.core.agent.ToolContext
import com.rickeal.agent.core.model.ToolEffect
import com.rickeal.agent.core.model.ToolParameter
import com.rickeal.agent.core.model.ToolParamType
import com.rickeal.agent.core.model.ToolResult
import com.rickeal.agent.core.model.ToolSpec
import java.io.File
import kotlinx.serialization.json.JsonPrimitive

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
        description = "读取沙箱目录内的文本文件；大文件可用 offset/limit 分页续读",
        parameters = listOf(
            param("path", "相对于沙箱目录的文件路径"),
            ToolParameter(
                name = "offset",
                type = ToolParamType.INTEGER,
                description = "可选，从第几个字符开始读（0 起），默认 0；负数按 0 处理",
                required = false,
            ),
            ToolParameter(
                name = "limit",
                type = ToolParamType.INTEGER,
                description = "可选，本次最多读取的字符数，默认 ${FileReadPager.READ_LIMIT_CHARS}、" +
                    "最大 ${FileReadPager.READ_LIMIT_CHARS}",
                required = false,
            ),
        ),
        category = "file",
        effect = ToolEffect.READ,
        // 检索别名（Wave 31）：用户口语里「打开/看一下文件」都指读文件。
        keywords = listOf("读文件", "看一下文件", "打开文件", "读取内容"),
    )

    override suspend fun invoke(argumentsJson: String): ToolResult {
        val path = stringArg(argumentsJson, "path")
        return try {
            val file = resolveSafe(path)
            if (!file.exists()) return ToolResult(name = spec.name, ok = false, errorMessage = "文件不存在：$path")
            if (!file.isFile) return ToolResult(name = spec.name, ok = false, errorMessage = "不是文件：$path")

            // Wave 31：分页参数。offset 负数按 0；limit 非正按默认、超上限钳到 READ_LIMIT_CHARS。
            val offset = FileReadPager.normalizeOffset(intArg(argumentsJson, "offset", 0))
            val limit = FileReadPager.normalizeLimit(
                intArg(argumentsJson, "limit", FileReadPager.READ_LIMIT_CHARS),
            )

            // 零回归闸门：未分页（offset=0 且 limit 取默认值）时走 readHead()，
            // 其输出与引入分页前**逐字节一致**（含截断提示文案与标记预算）。
            val text = if (offset == 0 && limit == FileReadPager.READ_LIMIT_CHARS) {
                FileReadPager.readHead(file)
            } else {
                FileReadPager.readRange(file, offset, limit)
            }
            ToolResult(name = spec.name, ok = true, output = text)
        } catch (t: Throwable) {
            ToolResult(name = spec.name, ok = false, errorMessage = t.message ?: "读取失败")
        }
    }
}

/**
 * file_read 的读取核心（Wave 31 抽出为纯逻辑对象）。
 *
 * 为什么抽出来：本仓铁律是「JVM 单测只覆盖纯函数」——`FileReadTool` 需要
 * [ToolContext]（含 android `Context`），在纯 JVM 测试里无法构造；把读取/分页/
 * 钳制逻辑下沉到这个零依赖对象，就能用 `File` 直接单测（见 FileReadPagerTest），
 * 而工具类只留参数解析与 `ToolResult` 组装。
 */
internal object FileReadPager {

    /**
     * 读取限量：与 [AgentPolicy.maxToolOutputChars] **同源**（取其默认值）。
     * Runner 对工具输出的截断上限就是它 —— file_read 在源头只读这么多，
     * 省掉「读 20 万字符进内存、再被 Runner 砍到 4000」的纯浪费（三线审查 Wave10）。
     * 顺带保证本工具输出恒 ≤ 限量：Runner 的「…(已截断)」标记对 file_read
     * **永不触发**，两层截断标记不会叠加出现（见 [readHead] / [readRange] 内的预算注释）。
     */
    val READ_LIMIT_CHARS: Int = AgentPolicy().maxToolOutputChars

    /** 剩余字符计数上限：超过就放弃精确计数、改报「超过 N」，不再继续读盘。 */
    private const val COUNT_CAP_CHARS = 5_000_000L

    /**
     * 分页标记的预算字符数（Wave 31）。分页路径尾部拼接的续读指引长度上界 ——
     * 正文含 offset / 总数 / 「继续读请传 offset=」等，实测最长约 60 字符，
     * 取 80 留足余量，保证 `内容 + 换行 + 标记 ≤ READ_LIMIT_CHARS`，
     * 从而 Runner 的二次截断（会砍掉标记本身）永不触发。
     */
    private const val MARKER_BUDGET_CHARS = 80

    /** offset 归一化：负数按 0（真正的越界由 [readRange] 处理）。 */
    fun normalizeOffset(raw: Int): Int = raw.coerceAtLeast(0)

    /** limit 归一化：非正按默认、超上限钳到 [READ_LIMIT_CHARS]。 */
    fun normalizeLimit(raw: Int): Int =
        if (raw <= 0) READ_LIMIT_CHARS else raw.coerceAtMost(READ_LIMIT_CHARS)

    /**
     * 默认路径（不分页）：与引入 offset/limit 前**逐字节一致**的读法。
     *
     * 流式限量读，三态输出（三线审查 Wave10）：
     *  1. 完整读毕（EOF 在限量内达到）→ 原文，无标记；
     *  2. 因限量截断 → 内容 + 「共约 N / 仅载入前 M」显式标记 —— 4000 字符外的
     *     信息不再在 file_read 层静默丢失，模型至少知道文件有多大、读到了哪；
     *  3. 空文件 → 空输出，与旧行为一致。
     * 循环填缓冲而非单次 read(buffer)：单次 read 不保证填满（流式语义允许
     * 提前返回），旧实现会把「网络盘 / 大文件慢读」误判成 EOF。
     */
    fun readHead(file: File): String {
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
        return if (totalChars < 0L) {
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
    }

    /**
     * 分页路径（Wave 31）：从 [offset] 起读至多 [limit] 个字符，并在结果尾部给出
     * **一步可接上的续读指引**（含下一段 offset）。动机：此前模型要读大文件后半段，
     * 唯一手段是重复 `file_read(path)` —— 参数完全相同，正被「同工具同参」护栏误杀。
     *
     * 前置条件（由 [normalizeOffset] / [normalizeLimit] 保证）：`offset >= 0`、
     * `1 <= limit <= READ_LIMIT_CHARS`。
     *
     * 边界：
     *  - offset 越界（≥ 文件长度）→ 空内容 + 明确提示「已超出文件长度」；
     *  - 输出长度恒 ≤ [READ_LIMIT_CHARS]：截断时按 [MARKER_BUDGET_CHARS] 预留标记空间；
     *    EOF 时优先保内容（放不下标记则省略标记），绝不因预算裁剪而丢失尾部内容。
     */
    fun readRange(file: File, offset: Int, limit: Int): String {
        val buffer = CharArray(limit)
        var filled = 0
        var startPos = offset.toLong()
        var beyondEnd = false
        var totalChars = -1L
        var overCountCap = false
        file.bufferedReader().use { reader ->
            // 跳过 offset 个字符：BufferedReader.skip 不保证一次到位，循环补齐。
            var remaining = offset.toLong()
            val sink = CharArray(8192)
            while (remaining > 0) {
                val want = minOf(sink.size.toLong(), remaining).toInt()
                val r = reader.read(sink, 0, want)
                if (r < 0) break
                remaining -= r
            }
            if (remaining > 0) {
                // offset 超出文件长度：实际停在文件末尾，已无内容可读。
                startPos = offset - remaining
                beyondEnd = true
            }
            while (filled < limit) {
                val r = reader.read(buffer, filled, limit - filled)
                if (r < 0) break
                filled += r
            }
            if (!beyondEnd && filled == limit) {
                // 缓冲填满 ≠ 一定还有剩余：再探一个字符定性（与 [readHead] 同口径）。
                if (reader.read() >= 0) {
                    var rest = 1L
                    val countSink = CharArray(8192)
                    count@ while (true) {
                        val r = reader.read(countSink)
                        if (r < 0) break
                        rest += r
                        if (rest > COUNT_CAP_CHARS) {
                            overCountCap = true
                            break@count
                        }
                    }
                    totalChars = startPos + limit + rest
                }
            }
        }
        val truncated = totalChars >= 0L
        val keep = (limit - MARKER_BUDGET_CHARS - 1).coerceAtLeast(0)
        // 截断时按预算裁剪内容（指引里的下一段 offset 随裁剪后的位置给出，保证续读不跳字）；
        // EOF 时必须给全，绝不裁剪。
        val contentLen = when {
            beyondEnd -> 0
            truncated -> if (keep > 0) minOf(filled, keep) else filled
            else -> filled
        }
        val shownEnd = startPos + contentLen
        val content = String(buffer, 0, contentLen)
        val marker = when {
            beyondEnd -> "…（文件共 $startPos 字符，offset=$offset 已超出文件长度）"
            truncated -> {
                val scale = if (overCountCap) "超过 $COUNT_CAP_CHARS" else "约 $totalChars"
                "…（已读 [$startPos, $shownEnd)，文件共$scale 字符，继续读请传 offset=$shownEnd）"
            }
            else -> "…（已读 [$startPos, $shownEnd)，文件共 $shownEnd 字符，已读到末尾）"
        }
        // 输出恒 ≤ READ_LIMIT_CHARS：EOF 且标记放不下时优先保内容（标记纯提示，可省）。
        return when {
            content.isEmpty() -> marker
            !truncated && content.length + 1 + marker.length > limit -> content
            else -> content + "\n" + marker
        }
    }
}

/**
 * 从 argumentsJson 里安全地取一个整型参数（Wave 31，file_read 分页用）。
 * 解析口径与 [stringArg] 对齐：非对象 / 缺键 / 类型不符一律回退 [default]；
 * 数字与数字字符串都接受（模型常把 5 写成 "5"）。
 */
private fun intArg(argumentsJson: String, key: String, default: Int): Int {
    val element = json(argumentsJson)[key] ?: return default
    val primitive = element as? JsonPrimitive ?: return default
    return primitive.content.toIntOrNull() ?: default
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
        effect = ToolEffect.WRITE,
        // 检索别名（Wave 31）。
        keywords = listOf("写文件", "保存文件", "记录到文件"),
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
        effect = ToolEffect.READ,
        // 检索别名（Wave 31）。
        keywords = listOf("列目录", "查看文件列表", "有哪些文件"),
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
