package com.rickeal.agent.core.data

import androidx.compose.runtime.Immutable
import java.io.File

/**
 * 沙箱工作区的一个条目（[SandboxFileScanner] 的产出，Wave 33；Wave 36 起支持下钻）。
 *
 * [relativePath] 是**相对沙箱根目录**的路径：根层条目恒等于 [name]，下钻一层后含
 * 目录前缀（如 `sub/inner.txt`）—— 分隔符恒为字面 `'/'`（不随平台变化），UI 侧据此
 * 直接拼 `File(sandboxDir, relativePath)` 或交给 FileProvider，无需再换算。
 *
 * @param name 文件 / 目录名（不含路径）。
 * @param relativePath 相对沙箱根目录的路径（`/` 连接，根层等于 [name]）。
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
 * 扫描口径（**每次只扫一层，逐层下钻**，Wave 36）：
 *  - **只扫当前层**，不递归：下钻 = 换一个目录再调一次本函数（`dirPath` 指定），
 *    递归会把 [SandboxScanResult.totalEntries] / `truncated` 的语义从「本层条目数」
 *    改写成「整棵子树条目数」，与工具页入口卡的「根层」口径打架（A7），且会撞上
 *    journal 大目录级的时间预算问题；
 *  - 隐藏文件（`.` 开头）排除 —— 与常见文件管理器口径一致；
 *  - **原子写临时文件排除**：FileWriteTool 落盘用的是「原名 + `.tmp_` + 纳秒」
 *    的临时名、写完 rename 成正式名，因此**形态符合该命名规则**的条目是崩溃残留的
 *    半成品，不该出现在用户眼前（也不该被「打开」动作暴露出去）。判据是**后缀形态**
 *    而不是子串匹配 —— 子串匹配会把 `report.tmp_backup.txt` 这类合法名静默藏掉；
 *  - **符号链接目录逃逸剔除**：当前层里 `isDirectory` 的条目若 canonical 解析后落在
 *    沙箱根之外，从 listing 剔除（见 [resolveWithinSandbox]）—— 这是**有意过滤**，
 *    不是 bug（详见 [scan]）；
 *  - 最后修改时间降序，同毫秒按名字典序兜底（保证测试与展示的确定性）。
 */
object SandboxFileScanner {

    /**
     * 默认扫描上限。沙箱是工具产出落点，正常使用不会有几百个**当前层**条目；
     * 上限只为防御「agent 疯狂产出」的极端情形拖死 UI 列表。
     */
    const val DEFAULT_LIMIT: Int = 200

    /**
     * 扫描 [root] 下 [dirPath] 指定的**这一层**条目（[dirPath] 为空串 = 根层）。
     *
     * [root] 不存在 / 不是目录、或 [dirPath] 未通过 [resolveWithinSandbox] 的路径安全
     * 校验（逃逸 / 非法段）时返回**空结果**（fail-closed）—— 首次启动沙箱还没被创建
     * 是常态，不是错误态，UI 层按空态呈现即可。
     *
     * 条目 [SandboxFileInfo.relativePath] 由 [dirPath] 与名字结构化拼接（分隔符恒为
     * 字面 `'/'`，**不用 `File.separator`** —— CI 在 Linux、本地在 Windows，用 separator
     * 会让路径串跨平台不一致）。`dirPath == ""` 时与 Wave 33 的根层行为**逐字节一致**。
     *
     * **纵深防御（有意过滤）**：对当前层里 `isDirectory` 的条目再做一次 canonical 包含性
     * 检查（[resolveWithinSandbox]），把「指向沙箱外的符号链接目录」从 listing 剔除 ——
     * 目录是**下钻的导航向量**，放行会让用户一路点出沙箱。非目录条目不经此检查（「打开」
     * 动作由 FileProvider 路径表兜底）。
     *
     * @param dirPath 相对 [root] 的当前目录路径（`/` 连接；空串 = 根层）。
     * @param limit **当前层**展示条目上限；超出部分只计入 [SandboxScanResult.totalEntries]。
     */
    fun scan(root: File, dirPath: String = "", limit: Int = DEFAULT_LIMIT): SandboxScanResult {
        // fail-closed：路径不安全（逃逸 / 非法段 / 解析异常）一律按空结果处理。
        val dir = resolveWithinSandbox(root, dirPath)
            ?: return SandboxScanResult(entries = emptyList(), totalEntries = 0, truncated = false)
        val children = if (dir.isDirectory) dir.listFiles() else null
        if (children == null) {
            // listFiles() 对不存在 / 无权限的目录返回 null —— 统一按空结果处理。
            return SandboxScanResult(entries = emptyList(), totalEntries = 0, truncated = false)
        }
        val all = children
            .filter { entry ->
                val name = entry.name
                !name.startsWith(".") && !isAtomicWriteTemp(name)
            }
            .sortedWith(
                compareByDescending<File> { it.lastModified() }
                    .thenBy { it.name },
            )
            .map { entry ->
                SandboxFileInfo(
                    name = entry.name,
                    // 结构化拼接，字面 '/'：dirPath 为空时逐字节等于 entry.name（回归锚）。
                    relativePath = if (dirPath.isEmpty()) entry.name else "$dirPath/${entry.name}",
                    sizeBytes = if (entry.isDirectory) 0L else entry.length(),
                    lastModifiedMillis = entry.lastModified(),
                    isDirectory = entry.isDirectory,
                    extension = if (entry.isDirectory) "" else entry.extension.lowercase(),
                    childCount = if (entry.isDirectory) entry.listFiles()?.size ?: 0 else 0,
                )
            }
            // 纵深防御：目录条目若 canonical 解析后逃逸出沙箱，剔除（异常按剔除处理）。
            .filter { info -> !info.isDirectory || resolveWithinSandbox(root, info.relativePath) != null }
        val shown = if (limit > 0) all.take(limit) else all
        return SandboxScanResult(
            entries = shown,
            totalEntries = all.size,
            truncated = all.size > shown.size,
        )
    }

    /**
     * 把 root-relative 的 [relativePath] 解析成沙箱内的绝对 [File]；**不安全则返回 null**。
     *
     * 结构化判定，三层叠加（与 core-agent `SandboxedFileTool.resolveSafe` 同口径，
     * 见 FileTools.kt）：
     *  1. **语义层**：拒绝对路径（以 `/` 或 `\` 开头）—— 本 API 的语义就是「相对沙箱根」，
     *     `File(base, "/abs")` 在 JVM 语义下会直接得到 `/abs`（绝对 child 覆盖 base）；
     *  2. **段白名单**：`'/'` 与 `'\'` 都当分隔符，切段后拒绝空段 / `.` / `..` ——
     *     **结构化判定，不用字符串 `contains("..")`**（后者被 Windows 反斜杠、编码变体
     *     `..%2f` 之类的写法绕过；段白名单是廉价的确定性预过滤，不依赖 `canonicalFile`
     *     的实现细节）；
     *  3. **规范化层**：`canonicalFile` 解析 `..` 与符号链接后做前缀比对 —— 兜住段白名单
     *     覆盖不到的**符号链接逃逸**（如 `link` → `/etc`，段本身合法但落点在沙箱外）。
     *
     * 两层（段白名单 + canonical 比对）**必须叠加**：任一层单独都不充分 —— 只有 canonical
     * 会因平台实现差异漏判，只有段白名单挡不住符号链接。
     *
     * **已知取舍（有意过度拒绝，非 bug）**：把 `'\'` 当分隔符 ⇒ 在 POSIX / Android 上，
     * 名字里真的含反斜杠的合法条目（如 `a\b`）会被拆成 `a/b` 重组成另一个路径。
     *  - 事实：`\` 在 POSIX 上是合法文件名字符，这里当成分隔符属**有意的过度拒绝**；
     *  - 为什么无害：重组只把 `\` 换成 `/`、段里又不可能含 `..`，落点必在 root 之内
     *    （通常是一个不存在的路径），**不可能逃逸**；符号链接逃逸另由 canonical 层兜住；
     *  - 为什么保留：Android 运行时 `File.separatorChar == '/'`，`\` 处理纯属纵深防御，
     *    且该行为已被单测钉住（`"a\\..\\b"` 断言 null）—— 去掉要同时改实现与断言，得不偿失。
     *
     * `internal`：纯 `java.io.File` 函数（不触 Android），改可见性只为让 JVM 单测直接钉住
     * 各边界（空串 / 合法相对路径 / `..` / 绝对路径 / 符号链接逃逸）。
     *
     * @return 通过校验的绝对 [File]（canonical）；[relativePath] 为空串时返回 [root] 本身。
     */
    internal fun resolveWithinSandbox(root: File, relativePath: String): File? {
        if (relativePath.isEmpty()) return root
        // ① 语义层：拒绝对路径。
        if (relativePath.startsWith("/") || relativePath.startsWith("\\")) return null
        // ② 段白名单：'/' 与 '\' 都当分隔符；拒绝空段 / "." / ".."。
        val segments = relativePath.split('/', '\\')
        if (segments.any { it.isEmpty() || it == "." || it == ".." }) return null
        // ③ 规范化层：canonical 解析符号链接后做前缀比对。
        return runCatching {
            val base = root.canonicalFile
            val target = File(base, segments.joinToString("/")).canonicalFile
            val basePath = base.path
            if (target.path != basePath && !target.path.startsWith(basePath + File.separator)) null
            else target
        }.getOrNull()
    }

    /**
     * 该名字是否为 `FileWriteTool`（core-agent 的写文件工具）原子写留下的
     * **半成品临时文件**（原名 + `.tmp_` + 纳秒时间戳）。
     *
     * 判据是**后缀形态**而非子串匹配：子串 `contains(".tmp_")` 会把
     * `report.tmp_backup.txt` 这类用户/agent 起的合法名一起静默藏掉 —— 那是
     * 「误伤条件可穷举」的确定性缺陷，文件凭空消失且无任何日志。这里改为
     * 「取最后一个 `.tmp_`、其后必须全是数字」，既排掉真临时文件，又放行合法名。
     *
     * `nanoTime()` 的原点未定义（理论上可为负），故允许一个前导负号。
     *
     * `internal`：纯函数（`String` 判定，不触 Android），改可见性只为让 JVM 单测
     * 直接钉住「`report.tmp_backup.txt` 保留 / `x.tmp_<数字>` 排除」这两条边界。
     */
    internal fun isAtomicWriteTemp(name: String): Boolean {
        val marker = ".tmp_"
        val markerIndex = name.lastIndexOf(marker)
        if (markerIndex < 0) return false
        val stamp = name.substring(markerIndex + marker.length)
        if (stamp.isEmpty()) return false
        val digits = if (stamp.first() == '-') stamp.substring(1) else stamp
        return digits.isNotEmpty() && digits.all { it in '0'..'9' }
    }
}
