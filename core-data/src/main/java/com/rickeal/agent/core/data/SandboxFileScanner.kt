package com.rickeal.agent.core.data

import androidx.compose.runtime.Immutable
import java.io.File

/**
 * 沙箱工作区的一个根层条目（[SandboxFileScanner] 的产出，Wave 33）。
 *
 * [relativePath] 是相对沙箱根目录的路径（首版只扫根层，恒等于 [name]；
 * 保留独立字段是为了将来支持下钻时 UI 侧无需换算）。
 *
 * @param name 文件 / 目录名（不含路径）。
 * @param relativePath 相对沙箱根目录的路径。
 * @param sizeBytes 文件字节数；目录恒为 0（大小语义对目录无意义，目录的规模感由
 *   [childCount] 承担）。
 * @param lastModifiedMillis 最后修改时间（epoch 毫秒）。
 * @param isDirectory 是否为目录。
 * @param extension 小写扩展名（不含点；无扩展名时为空串；目录恒为空串）。
 * @param childCount 目录的直接子项个数（仅 [isDirectory] 为 true 时有意义；
 *   文件恒为 0）。
 */
@Immutable
data class SandboxFileInfo(
    val name: String,
    val relativePath: String,
    val sizeBytes: Long,
    val lastModifiedMillis: Long,
    val isDirectory: Boolean,
    val extension: String,
    val childCount: Int = 0,
)

/**
 * 一次沙箱扫描的结果。
 *
 * @param entries 展示条目（已按最后修改时间降序排好、已按 [SandboxFileScanner] 的
 *   默认上限截断）。
 * @param totalEntries 扫描到的**全部**有效条目数 —— 含因超限未进入 [entries] 的部分，
 *   UI 据此显示「N+ 项」而不是低估。
 * @param truncated 是否发生了截断（[totalEntries] 超过扫描上限）。
 */
@Immutable
data class SandboxScanResult(
    val entries: List<SandboxFileInfo>,
    val totalEntries: Int,
    val truncated: Boolean,
)

/**
 * 沙箱工作区文件扫描器（Wave 33「沙箱文件可视」的数据面）。
 *
 * 纯 `java.io.File` 的 JVM 逻辑（不触任何 Android 类），沙箱根目录由调用方传入
 * （生产路径是 [AppContainer.sandboxDir]），因此可以直接 JVM 单测
 * （见 SandboxFileScannerTest）。
 *
 * 扫描口径：
 *  - **仅根层**，不递归（首版无下钻；递归扫会撞上 journal 大目录级的时间预算问题）；
 *  - 隐藏文件（`.` 开头）排除 —— 与常见文件管理器口径一致；
 *  - **原子写临时文件排除**：FileWriteTool 落盘用的是「原名 + `.tmp_` + 纳秒」
 *    的临时名、写完 rename 成正式名，因此名字里含 `.tmp_` 的条目是崩溃残留的
 *    半成品，不该出现在用户眼前（也不该被「打开」动作暴露出去）；
 *  - 最后修改时间降序，同毫秒按名字典序兜底（保证测试与展示的确定性）。
 */
object SandboxFileScanner {

    /**
     * 默认扫描上限。沙箱是工具产出落点，正常使用不会有几百个根层条目；
     * 上限只为防御「agent 疯狂产出」的极端情形拖死 UI 列表。
     */
    const val DEFAULT_LIMIT: Int = 200

    /**
     * 扫描 [root] 的根层条目。
     *
     * [root] 不存在 / 不是目录时返回**空结果**（首次启动沙箱还没被创建是常态，
     * 不是错误态，UI 层按空态呈现即可）。
     *
     * @param limit 展示条目上限；超出部分只计入 [SandboxScanResult.totalEntries]。
     */
    fun scan(root: File, limit: Int = DEFAULT_LIMIT): SandboxScanResult {
        val children = if (root.isDirectory) root.listFiles() else null
        if (children == null) {
            // listFiles() 对不存在 / 无权限的目录返回 null —— 统一按空结果处理。
            return SandboxScanResult(entries = emptyList(), totalEntries = 0, truncated = false)
        }
        val all = children
            .filter { entry ->
                val name = entry.name
                !name.startsWith(".") && !name.contains(".tmp_")
            }
            .sortedWith(
                compareByDescending<File> { it.lastModified() }
                    .thenBy { it.name },
            )
            .map { entry ->
                SandboxFileInfo(
                    name = entry.name,
                    relativePath = entry.name,
                    sizeBytes = if (entry.isDirectory) 0L else entry.length(),
                    lastModifiedMillis = entry.lastModified(),
                    isDirectory = entry.isDirectory,
                    extension = if (entry.isDirectory) "" else entry.extension.lowercase(),
                    childCount = if (entry.isDirectory) entry.listFiles()?.size ?: 0 else 0,
                )
            }
        val shown = if (limit > 0) all.take(limit) else all
        return SandboxScanResult(
            entries = shown,
            totalEntries = all.size,
            truncated = all.size > shown.size,
        )
    }
}
