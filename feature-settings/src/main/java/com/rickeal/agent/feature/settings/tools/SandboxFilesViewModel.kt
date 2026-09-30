package com.rickeal.agent.feature.settings.tools

import android.webkit.MimeTypeMap
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rickeal.agent.core.agent.AgentPolicy
import com.rickeal.agent.core.data.AppContainer
import com.rickeal.agent.core.data.SandboxFileInfo
import com.rickeal.agent.core.data.SandboxFileScanner
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 文件预览的文本读取上限。
 *
 * **与 [AgentPolicy.maxToolOutputChars] 同源**（取其默认值，与 core-agent 侧
 * FileReadPager 的读取限量同一口径）—— 预览不是工具输出，但「预览一万字符把
 * 内存与弹窗撑爆」是同一类问题。UI 的「仅预览前 N 字符」提示也取自它，
 * 常量与文案不会漂移。若将来改 AgentPolicy 默认值，这里自动跟随；
 * 若需要独立口径，再显式拆常量。
 *
 * `internal`：SandboxFilesScreen 的截断提示文案与 VM 的读取限量必须取同一个值。
 */
internal val SANDBOX_PREVIEW_LIMIT_CHARS: Int = AgentPolicy().maxToolOutputChars

/**
 * 文本类扩展名白名单：命中才提供应用内文本预览，其余按二进制处理
 * （**白名单制** —— 沙箱是 agent 产出落点，扩展名不可信，黑名单必然漏）。
 * 空扩展名（README / LICENSE / Makefile 类）也按文本尝试读取。
 */
private val TEXT_EXTENSIONS: Set<String> = setOf(
    "txt", "md", "markdown", "log", "csv", "tsv",
    "json", "xml", "yaml", "yml", "toml", "properties", "ini", "cfg",
    "html", "htm", "css", "js", "ts", "kt", "kts", "java", "py", "sh",
    "c", "h", "cpp", "hpp", "rs", "go", "gradle", "pro", "sql",
)

/** 预览弹层的状态载体。 */
@Immutable
data class SandboxFilePreview(
    /** 被预览的条目（含名称 / 大小 / 时间，弹层标题与摘要直接取用）。 */
    val info: SandboxFileInfo,
    /**
     * 文本内容；null = 二进制文件（未按文本读取）。
     * [loading] 为 true 期间也可能是「尚未读到」，两者由 [loading] 区分。
     */
    val text: String?,
    /** 文本是否因超过 [SANDBOX_PREVIEW_LIMIT_CHARS] 被截断。 */
    val truncated: Boolean,
    /** 文本读取进行中（弹层先开、内容后到）。 */
    val loading: Boolean,
)

/** 沙箱文件子页的 UI 状态。 */
@Immutable
data class SandboxFilesUiState(
    /**
     * **当前层**（[currentDirPath] 这一层）的条目（已按最后修改时间降序排好，直接渲染）。
     *
     * ⚠️ 这是**被展示上限截断过**的列表，计数口径必须用 [totalEntries] —— 直接用
     * `entries.size` 会在超限时显示「共 200 项」，而工具页入口卡（同源 Scanner）
     * 显示「200+ 项」，同一时刻两个数字对不上会被当成 bug（A7）。
     */
    val entries: List<SandboxFileInfo> = emptyList(),
    /**
     * 扫描到的**全部**有效条目数（含因超限未进入 [entries] 的部分）。
     * 与 [SandboxFileScanner] 的 `totalEntries` 同源，UI 据此显示「N+ 项」。
     */
    val totalEntries: Int = 0,
    /** 是否发生了截断（[totalEntries] 超过扫描上限）。 */
    val truncated: Boolean = false,
    /**
     * 当前所在目录（**root-relative**，`/` 连接；空串 = 沙箱根层）。
     *
     * 与 [entries] 是**同一层**的关系 —— [entries] 恒为 [currentDirPath] 这一层的条目，
     * [totalEntries] / [truncated] 也按这一层计（计数口径不变，见 A7）。默认空串 ⇒
     * 既有具名构造与纯逻辑测试不受影响。
     */
    val currentDirPath: String = "",
    val loading: Boolean = true,
    /** 非空表示扫描失败（磁盘 IO 异常等），UI 呈现错误态 + 重试。 */
    val error: String? = null,
    /** 非空表示一个文件预览弹层正在展示。 */
    val selectedPreview: SandboxFilePreview? = null,
)

/**
 * 沙箱工作区文件子页（Wave 33；Wave 36 起支持逐层下钻）。
 *
 * 数据面唯一来源是 [SandboxFileScanner]（与工具页入口卡同一实现）；删除 / 重命名 /
 * 写入**刻意不做** —— 清理走存储页的「沙箱工作区」分桶，职责不混。
 * 下钻 = 换当前目录再扫一层（[navigateInto] / [navigateUp]），**不做递归扫描**：
 * [SandboxFilesUiState.totalEntries] 恒为「当前层」计数，与工具页入口卡的根层口径不冲突。
 * VM 只产 [SandboxFileInfo] 与文本内容；「打开」的 Intent 组装在 UI 层
 * （需要 Context 与 FileProvider，不属于 VM 职责）。
 */
class SandboxFilesViewModel(
    private val container: AppContainer,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SandboxFilesUiState())
    val uiState: StateFlow<SandboxFilesUiState> = _uiState.asStateFlow()

    /**
     * 当前预览的读取协程（同时最多一单）。
     *
     * 用户在读取期间关掉弹层、或快连点 A→B 时，旧的读盘结果已经**与画面对不上**，
     * 必须作废（关闭的弹层被重新弹出 / 标题是 B 内容是 A，都是这一处漏管的后果）。
     */
    private var previewJob: Job? = null

    init {
        refresh()
    }

    /** 重新扫描**当前目录**（首次进入 / 下钻 / 返回上一层时手动触发）。 */
    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, error = null) }
            // 在切 IO 之前把当前目录读出来（避免在 withContext 里读状态）。
            val dirPath = _uiState.value.currentDirPath
            val result = withContext(Dispatchers.IO) {
                runCatching { SandboxFileScanner.scan(container.sandboxDir, dirPath = dirPath) }
            }
            result.fold(
                onSuccess = { scan ->
                    _uiState.update {
                        it.copy(
                            loading = false,
                            entries = scan.entries,
                            totalEntries = scan.totalEntries,
                            truncated = scan.truncated,
                            error = null,
                        )
                    }
                },
                onFailure = { t ->
                    _uiState.update {
                        it.copy(
                            loading = false,
                            entries = emptyList(),
                            totalEntries = 0,
                            truncated = false,
                            error = t.message ?: "扫描失败",
                        )
                    }
                },
            )
        }
    }

    /**
     * 进入 [info] 这个目录（逐层下钻）。
     *
     * 只应由 UI 在目录行上调用（[SandboxFileInfo.isDirectory] 为 true）。新的当前目录
     * 路径由 [joinRelativePath] 从「当前目录 + 条目名」拼出 —— 与 [SandboxFileScanner]
     * 拼 `relativePath` 的公式同源（字面 `/`），保证两处口径一致。
     *
     * **扫描在途时忽略本次导航**（[SandboxFilesUiState.loading] 为 true）：此时 [SandboxFilesUiState.entries]
     * 仍是**上一层**的旧列表，行点击会拿过期的 `currentDirPath` 拼出幻影路径（如点两次
     * 「sub」得到 `sub/sub`，扫到不存在目录 → 空列表 + 面包屑显示 `sub/sub`，看起来像坏了）。
     * 宁可丢掉一次极快的连点，也不要拼出不存在的一层。
     */
    fun navigateInto(info: SandboxFileInfo) {
        // 读真源 _uiState.value（不用 collect 快照）—— 本仓既有纪律。
        if (_uiState.value.loading) return
        _uiState.update { it.copy(currentDirPath = joinRelativePath(it.currentDirPath, info.name)) }
        refresh()
    }

    /**
     * 返回上一层目录并重扫。
     *
     * 已在根层时 [parentRelativePath] 仍返回空串（等于重扫根层）—— UI 在根层不应调本方法，
     * 而应走 `onBack`（退出子页），见 SandboxFilesScreen 的上下文返回逻辑。
     *
     * 与 [navigateInto] 同款**扫描在途守卫**：在途时忽略，避免用过期 `currentDirPath` 连点
     * 上溯出错误层级。
     */
    fun navigateUp() {
        if (_uiState.value.loading) return
        _uiState.update { it.copy(currentDirPath = parentRelativePath(it.currentDirPath)) }
        refresh()
    }

    /**
     * 点了一个文件 → 打开预览弹层。
     *
     * 文本白名单内的扩展名才读内容（限 [SANDBOX_PREVIEW_LIMIT_CHARS] 字符，超出截断）；
     * 二进制直接给出「不支持预览」的弹层，只留「打开」出口。读取在 [Dispatchers.IO]。
     *
     * 并发：新的一单会先 `Job.cancel()` 掉上一单（见 previewJob），落结果时再经
     * [commitPreview] 对一次账 —— 取消是「少做无用功」，对账是「保证结果与画面
     * 一致」，两层都不能省（取消不及时会让旧结果晚到，对账兜住这种晚到）。
     *
     * 🔒 信任边界（Wave 39 补记，与 `SandboxFilesScreen.openSandboxFile` 同口径）：
     * 下方 `File(container.sandboxDir, info.relativePath)` **没有**调用
     * `SandboxFileScanner.resolveWithinSandbox`，这不是漏写 —— [info] 来自
     * `SandboxFileScanner.scan` 的 listing：目录段在 `scan` 入口已过
     * `resolveWithinSandbox`（不通过即空结果，fail-closed），名字段来自 `listFiles()`
     * （Android/Linux 文件名不可能含 `/`，`.` / `..` 已被隐藏文件规则剔除）；
     * 目录条目另过 canonical 逃逸过滤，**文件条目不经该检查**（`scan` 的有意取舍）。
     * ⇒ 实害≈0，但前提是「[info] 只会来自 scan 的 listing」：将来若新增未经
     * `resolveWithinSandbox` 的入口（外部 Intent / 深度链接 / 工具回传路径），
     * **必须在那个新入口补校验**，不能沿用本注解。另注：读盘本身走
     * `runCatching` + 弹层内报错，路径不成立只会是「读取失败」，不会崩。
     */
    fun onPreview(info: SandboxFileInfo) {
        previewJob?.cancel()
        _uiState.update {
            it.copy(selectedPreview = SandboxFilePreview(info = info, text = null, truncated = false, loading = true))
        }
        previewJob = viewModelScope.launch {
            val outcome = if (!sandboxTextPreviewEligible(info.extension)) {
                // 二进制：不读盘，弹层直接给「不支持预览」终态。
                SandboxFilePreview(info = info, text = null, truncated = false, loading = false)
            } else {
                withContext(Dispatchers.IO) {
                    // 🔒 信任边界：`info.relativePath` 未经 resolveWithinSandbox —— 安全前提
                    // 见本函数 KDoc（入参来自 scan 的 listing，目录段已过校验、名字段来自
                    // listFiles()；新增非 listing 入口时必须在新入口补校验）。
                    runCatching { readPreviewHead(File(container.sandboxDir, info.relativePath)) }
                        .fold(
                            onSuccess = { (text, truncated) ->
                                SandboxFilePreview(info = info, text = text, truncated = truncated, loading = false)
                            },
                            onFailure = { t ->
                                // 读取失败（文件可能刚被删）：弹层内给一句可读的错误，
                                // 不崩、不静默 —— 用户知道该返回列表刷新了。
                                SandboxFilePreview(
                                    info = info,
                                    text = "读取失败：${t.message ?: "未知错误"}",
                                    truncated = false,
                                    loading = false,
                                )
                            },
                        )
                }
            }
            _uiState.update { it.commitPreview(outcome) }
        }
    }

    fun onDismissPreview() {
        // 读盘协程也要一起取消：否则它完成后虽被 [commitPreview] 挡住不弹窗，
        // 却仍会白跑一次 IO（大文件预览的读盘不是零成本）。
        previewJob?.cancel()
        previewJob = null
        _uiState.update { it.copy(selectedPreview = null) }
    }
}

/**
 * 把一次预览读取的结果落进状态；**对不上账就丢弃**。
 *
 * 对账判据 = 状态里正在预览的条目仍是 [SandboxFilePreview.info] 那一个。挡掉两类
 * 竞态（两层防护里负责「结果与画面一致」的那一层，另一层是 ViewModel 的 [Job.cancel]）：
 *  - 读取期间用户点了关闭 → 状态里已无预览，旧结果不能把弹层**重新弹出**；
 *  - 快连点 A→B，A 更慢完成 → 状态里已是 B，A 的结果不能把「标题 B、内容 A」
 *    这种错配写进画面。
 *
 * `internal`：纯函数（data class copy + 等值判定），改可见性只为让 JVM 单测能
 * 直接钉住这两条语义（本模块测试源集无 coroutines-test，VM 的协程时序无法在
 * JVM 上驱动，判定逻辑必须能脱离协程直测）。
 */
internal fun SandboxFilesUiState.commitPreview(outcome: SandboxFilePreview): SandboxFilesUiState {
    val current = selectedPreview ?: return this
    if (current.info != outcome.info) return this
    return copy(selectedPreview = outcome)
}

/**
 * 把子项名 [name] 接到父目录 [parent]（root-relative）之后，得到新的 root-relative 路径。
 *
 * 分隔符恒为字面 `'/'`（**不用 `File.separator`**）—— 与 [SandboxFileScanner] 拼
 * `relativePath` 的公式同源；CI 在 Linux、本地在 Windows，用 separator 会让路径串
 * 跨平台不一致。[parent] 为空串（根层）时直接返回 [name]（不带前导 `/`）。
 *
 * `internal`：纯函数，下钻导航的路径拼接逻辑（本模块测试源集无 coroutines-test，
 * VM 的协程时序无法在 JVM 上驱动，导航路径的判定逻辑必须能脱离协程直测 —— 同
 * [commitPreview] 的既定惯例）。
 */
internal fun joinRelativePath(parent: String, name: String): String =
    if (parent.isEmpty()) name else "$parent/$name"

/**
 * 取 root-relative 路径 [path] 的父目录（上溯一层）。
 *
 * 无 `/` 时返回空串（已在根层 → 上溯仍是根层）；空串入参返回空串。与 [joinRelativePath]
 * 互为逆运算（`parentRelativePath(joinRelativePath(p, n)) == p`，当 `n` 不含 `/`）。
 *
 * `internal`：纯函数，返回上一层导航的判定逻辑，理由同 [joinRelativePath]。
 */
internal fun parentRelativePath(path: String): String = path.substringBeforeLast('/', "")

/**
 * 流式读取文件头部，最多 [SANDBOX_PREVIEW_LIMIT_CHARS] 字符。
 *
 * 返回「内容 + 是否被截断」：缓冲读满后**再探一个字符**定性（文件恰好等于限量时
 * 不算截断），剩余部分不读 —— 与 core-agent FileReadPager.readHead 同款三态思路。
 *
 * `private`：VM 的实现细节不外泄；截断提示与限量同源自 [SANDBOX_PREVIEW_LIMIT_CHARS]，
 * 测试面由下方的格式化纯函数与 core-data 侧 ScannerTest 覆盖。
 */
private fun readPreviewHead(file: File): Pair<String, Boolean> {
    val limit = SANDBOX_PREVIEW_LIMIT_CHARS
    val sb = StringBuilder(limit)
    file.bufferedReader(Charsets.UTF_8).use { reader ->
        val buffer = CharArray(1024)
        while (sb.length < limit) {
            val read = reader.read(buffer, 0, minOf(buffer.size, limit - sb.length))
            if (read < 0) break
            sb.append(buffer, 0, read)
        }
        if (sb.length >= limit && reader.read() >= 0) {
            return sb.toString() to true
        }
    }
    return sb.toString() to false
}

/**
 * 该扩展名是否允许文本预览（白名单制，口径见 [TEXT_EXTENSIONS]）。
 *
 * `internal`：纯函数（集合成员判定），是「二进制文件，不支持预览」这条文案的
 * 唯一判定入口 —— 改可见性只为 JVM 单测能钉住「空扩展名按文本尝试」的语义。
 */
internal fun sandboxTextPreviewEligible(extension: String): Boolean =
    extension.isEmpty() || extension.lowercase() in TEXT_EXTENSIONS

/**
 * 字节数的人类可读文本（B / KB / MB / GB，保留 1 位小数）。
 *
 * **必须显式给 [Locale.US]**：默认 Locale 在部分欧洲语区把小数点输出成逗号 ——
 * 与同仓 StorageScreen / ModelsViewModel 口径一致。
 *
 * `internal`：纯函数，工具页入口卡与沙箱文件子页共用（同一格式化，两处数字
 * 才对得上）；改可见性只为 JVM 单测能直接钉住各档边界。
 */
internal fun formatSandboxBytes(bytes: Long): String = when {
    bytes >= 1_073_741_824L -> "%.1f GB".format(Locale.US, bytes / 1_073_741_824.0)
    bytes >= 1_048_576L -> "%.1f MB".format(Locale.US, bytes / 1_048_576.0)
    bytes >= 1024L -> "%.1f KB".format(Locale.US, bytes / 1024.0)
    else -> "$bytes B"
}

/**
 * 条目数的展示文案：截断过就带 `+`（「N+ 项」）。
 *
 * 工具页入口卡与沙箱文件子页**共用这一个实现**（A7）—— 两处的数字来自同一个
 * [SandboxFileScanner]，若各写一遍 `if (truncated)` 迟早会漂移成「一个 200+
 * 项、一个 200 项」的口径打架。
 *
 * `internal`：纯函数，改可见性只为 JVM 单测能钉住两档形态。
 */
internal fun sandboxEntryCountText(totalEntries: Int, truncated: Boolean): String =
    if (truncated) "$totalEntries+ 项" else "$totalEntries 项"

/** 绝对时间兜底档的格式（人类可读、排序友好的形态）。 */
private const val SANDBOX_TIME_PATTERN = "yyyy-MM-dd HH:mm"

/**
 * 修改时间的人类可读文本：1 小时内用相对时间（刚刚 / N 分钟前 / N 小时前），
 * 更早退回 `yyyy-MM-dd HH:mm` 绝对时间。
 *
 * [nowMillis] 参数化（默认当前时刻）—— 纯函数化之后 JVM 单测不用造时钟；
 * 文件时间来自本地文件系统 mtime，展示一律用本地时区。
 *
 * `internal`：同 [formatSandboxBytes] 的理由 —— 纯函数 + 单测直测。
 */
internal fun formatSandboxTime(millis: Long, nowMillis: Long = System.currentTimeMillis()): String {
    val delta = nowMillis - millis
    return when {
        // 未来时间（系统时钟被回拨 / mtime 异常）与 1 分钟内统一呈现「刚刚」。
        delta < 60_000L -> "刚刚"
        delta < 3_600_000L -> "${delta / 60_000L} 分钟前"
        delta < 86_400_000L -> "${delta / 3_600_000L} 小时前"
        else -> SimpleDateFormat(SANDBOX_TIME_PATTERN, Locale.US).format(java.util.Date(millis))
    }
}

/**
 * 由扩展名解析 MIME 类型。
 *
 * [lookup] 注入 `MimeTypeMap.getMimeTypeFromExtension` 这类平台查询 ——
 * 查询本身触 Android 类无法 JVM 单测，把「查不到就兜底 octet-stream」这条
 * **决策逻辑**抽成纯函数后即可单测。扩展名先归一小写再查（Windows 侧传来的
 * `report.MD` 与 `report.md` 必须同判）。
 *
 * `internal`：纯函数，UI 层「打开」动作与单测共用。
 */
internal fun resolveMimeType(extension: String, lookup: (String) -> String?): String {
    val mapped = if (extension.isEmpty()) null else lookup(extension.lowercase())
    return mapped ?: "application/octet-stream"
}

/** UI 层「打开」动作的真实查询：平台 MimeTypeMap（注意 getSingleton 是 Android API）。 */
internal fun platformMimeLookup(): (String) -> String? =
    { extension -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) }
