package com.rickeal.agent.core.data

import androidx.compose.runtime.Immutable
import com.rickeal.agent.core.model.AgentLogStore
import java.io.File
import java.util.LinkedHashMap

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
 * 纯 `java.io.File` + `core-model` 的 [AgentLogStore]（两者都不触任何 Android 类），
 * 沙箱根目录由调用方传入（生产路径是 [AppContainer.sandboxDir]），因此可以直接 JVM 单测
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
     * 单条目的 stat 缓存（W80-04 装饰-排序-去装饰）：scan 中间态，每条目只在装饰
     * 遍历里读一次文件系统，排序与展示全部读这里的缓存值。私有中间态，不出对象。
     */
    private data class DecoratedEntry(
        val name: String,
        val relativePath: String,
        val lastModifiedMillis: Long,
        val isDirectory: Boolean,
        val sizeBytes: Long,
        val extension: String,
        val childCount: Int,
    )

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
     * W80-04：条目 stat 只在装饰阶段读一次（缓存进私有 [DecoratedEntry]），排序用缓存
     * mtime、去装饰后不再二次 stat；目录逃逸剔除因判据只依赖条目自身，前移到排序前
     * （剔除子集不改变余序，`totalEntries` 口径不变）。
     *
     * @param dirPath 相对 [root] 的当前目录路径（`/` 连接；空串 = 根层）。
     * @param limit **当前层**展示条目上限；超出部分只计入 [SandboxScanResult.totalEntries]。
     */
    fun scan(root: File, dirPath: String = "", limit: Int = DEFAULT_LIMIT): SandboxScanResult {
        // fail-closed：路径不安全（逃逸 / 非法段 / 解析异常）一律按空结果处理。
        // W82：这条路径此前**完全静默** —— 「目录真的是空的」与「路径被安全校验拦掉」
        // 在 UI 上长得一模一样，用户无从归因。留一条 warn（不落盘，诊断页内存可见）。
        val dir = resolveWithinSandbox(root, dirPath)
        if (dir == null) {
            ScanEscapeAudit.warn("沙箱扫描：目录未通过沙箱校验（逃逸或非法段），按空结果处理 dirPath=$dirPath")
            return SandboxScanResult(entries = emptyList(), totalEntries = 0, truncated = false)
        }
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
            // W80-04 装饰：每个条目的 stat（mtime/length/isDirectory/childCount 的
            // listFiles）只在**这一次遍历**里读、缓存进 [DecoratedEntry] —— 旧实现把
            // `lastModified()` selector 留在比较子里，排序每次比较都重新 stat，
            // 共 O(N log N) 次；现在降为 O(N) 次。
            .map { entry ->
                val isDir = entry.isDirectory
                DecoratedEntry(
                    name = entry.name,
                    // 结构化拼接，字面 '/'：dirPath 为空时逐字节等于 entry.name（回归锚）。
                    relativePath = if (dirPath.isEmpty()) entry.name else "$dirPath/${entry.name}",
                    lastModifiedMillis = entry.lastModified(),
                    isDirectory = isDir,
                    sizeBytes = if (isDir) 0L else entry.length(),
                    extension = if (isDir) "" else entry.extension.lowercase(),
                    // W81 审查：list() 只分配 String[]（文件名数组），比 listFiles() 的
                    // File[] 轻（少 N 个 File 对象的分配与后续 GC）—— UI 只显示计数，
                    // 不需要 File 实例。
                    childCount = if (isDir) entry.list()?.size ?: 0 else 0,
                )
            }
            // 纵深防御：目录条目若 canonical 解析后逃逸出沙箱，剔除（异常按剔除处理）。
            // 剔除判据只依赖条目自身、与排序无关 ⇒ 前移到排序前结果集等价（比较子
            // 只用缓存 mtime + 名字，剔除一个子集不改变其余条目的相对序，W80-04）。
            // W82：剔除时 warn 留痕（此前静默丢条目，用户只看到「某个目录凭空消失」）。
            .filter { decorated -> isEntryInsideSandbox(root, decorated) }
            .sortedWith(
                compareByDescending<DecoratedEntry> { it.lastModifiedMillis }
                    .thenBy { it.name },
            )
            // 去装饰：从缓存值构造展示条目，不再二次 stat。
            .map { decorated ->
                SandboxFileInfo(
                    name = decorated.name,
                    relativePath = decorated.relativePath,
                    sizeBytes = decorated.sizeBytes,
                    lastModifiedMillis = decorated.lastModifiedMillis,
                    isDirectory = decorated.isDirectory,
                    extension = decorated.extension,
                    childCount = decorated.childCount,
                )
            }
        val shown = if (limit > 0) all.take(limit) else all
        return SandboxScanResult(
            entries = shown,
            totalEntries = all.size,
            truncated = all.size > shown.size,
        )
    }

    /**
     * 条目是否留在 listing 里（W82 可观测化）。
     *
     * 非目录恒放行 —— 「打开」动作由 FileProvider 路径表兜底；目录是**下钻的导航向量**，
     * canonical 解析后落在沙箱外就必须剔除（否则用户能一路点出沙箱）。
     *
     * 剔除**不再静默**：warn 一次（含 relativePath）。目录可能每秒重扫，故走
     * [ScanEscapeAudit] 的 30s 节流 —— 否则一次浏览就能把 200 条内存环刷满。
     */
    private fun isEntryInsideSandbox(root: File, decorated: DecoratedEntry): Boolean {
        if (!decorated.isDirectory) return true
        if (resolveWithinSandbox(root, decorated.relativePath) != null) return true
        ScanEscapeAudit.warn("沙箱扫描：剔除逃逸目录 ${decorated.relativePath}")
        return false
    }

    /**
     * 扫描期剔除日志的节流器（W82）。
     *
     * 与 core-agent `FileTools.kt` 的 `EscapeAudit` 同形态、**独立一份**：两者分属
     * core-agent / core-data，为十来行窗口逻辑造一个跨模块公共件（还要把 `internal`
     * 提成 public）不划算，且两处的级别与语义不同（那边是安全事件 ERROR 落盘，
     * 这边是浏览面 WARN 只进内存环）。
     *
     * **每键独立 30s 窗口**（W83-04，原为全局单键）：键默认取消息本身 —— 两类 warn
     * 的消息都以内置的目录路径收尾，路径不同 ⇒ 消息不同 ⇒ 键不同，天然实现「每路径
     * 一个窗口」。旧全局窗口会把 30s 内**不同路径**的剔除告警互相吞掉，事后答不出
     * 「浏览时剔过哪些目录」；同路径同类告警仍共享窗口（目录可能每秒重扫，防刷环
     * 的本意不变）。浏览面路径多、且 WARN 只进 200 条内存环，键容量 [MAX_KEYS]
     * 比安全事件面（core-agent 的 64）小。
     *
     * `internal` 只为让 JVM 单测钉住 [withinWindow] 的 30s 边界（与
     * `EscapeAudit.withinWindow` 同口径）；分键行为经 [warn] 的**时钟与输出缝**
     * （默认值即生产路径）单测，不碰进程级单例 [AgentLogStore]。
     */
    internal object ScanEscapeAudit {

        private const val WINDOW_MILLIS = 30_000L

        /**
         * 节流键数的防御上限（比 core-agent `EscapeAudit` 的 64 小）：浏览面路径多、
         * 且 WARN 只进内存环，宁可少留痕也不能让它有半点挤占 200 条环的余地。
         * 超限淘汰最旧插入项（[LinkedHashMap] 插入序首项）—— 只让那一个键丢状态
         * 重来，不整体重置（整体重置会一次性放开所有键的窗口，把防刷环的闸门冲掉）。
         */
        private const val MAX_KEYS = 16

        /** 每键上次记日志的时刻。缺键 = 从未记过 ⇒ 首次必然放行。 */
        private val lastLogged: MutableMap<String, Long> = LinkedHashMap()

        /** 每键本窗口内被省略的条数（按键独立计数，互不传染）。 */
        private val suppressed: MutableMap<String, Int> = LinkedHashMap()

        /** 扫描可能在 IO 线程、诊断页在主线程读 ⇒ 状态上锁。 */
        private val lock = Any()

        /** 窗口判定（纯函数，左闭右开：恰好 30s 视为窗口已结束）。 */
        fun withinWindow(nowMillis: Long, windowStartMillis: Long): Boolean =
            nowMillis - windowStartMillis < WINDOW_MILLIS

        /**
         * 记一条扫描告警（调用形态不变：`warn(message)`）。
         *
         * [key] 默认取消息本身（消息内含目录路径 ⇒ 路径即键，不同路径互不抑制）；
         * [nowMillis] / [sink] 是**测试缝**（默认值即生产路径：系统时钟 + 写
         * [AgentLogStore]），单测传固定时钟与收集器即可观察分键节流行为，不碰
         * 进程级单例。
         */
        fun warn(
            message: String,
            key: String = message,
            nowMillis: Long = System.currentTimeMillis(),
            sink: (String) -> Unit = { msg -> AgentLogStore.warn(msg) },
        ) {
            val omitted: Int
            synchronized(lock) {
                val last = lastLogged[key]
                if (last != null && withinWindow(nowMillis, last)) {
                    suppressed[key] = (suppressed[key] ?: 0) + 1
                    return
                }
                omitted = suppressed.remove(key) ?: 0
                lastLogged[key] = nowMillis
                evictOldestIfNeeded()
            }
            val tail = if (omitted > 0) "（上次记日志后另有 $omitted 次已省略）" else ""
            sink(message + tail)
        }

        /** 键数超 [MAX_KEYS] 时淘汰最旧插入项（取舍见 [MAX_KEYS]）。调用方须已持 [lock]。 */
        private fun evictOldestIfNeeded() {
            while (lastLogged.size > MAX_KEYS) {
                // accessOrder=false：首项即最旧插入的键（put 更新已存在的键不换位）。
                val oldest = lastLogged.keys.firstOrNull() ?: break
                lastLogged.remove(oldest)
                suppressed.remove(oldest)
            }
        }
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
