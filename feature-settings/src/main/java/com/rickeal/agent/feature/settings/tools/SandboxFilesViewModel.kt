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
    /** 根层条目（已按最后修改时间降序排好，直接渲染）。 */
    val entries: List<SandboxFileInfo> = emptyList(),
    val loading: Boolean = true,
    /** 非空表示扫描失败（磁盘 IO 异常等），UI 呈现错误态 + 重试。 */
    val error: String? = null,
    /** 非空表示一个文件预览弹层正在展示。 */
    val selectedPreview: SandboxFilePreview? = null,
)

/**
 * 沙箱工作区文件子页（Wave 33）。
 *
 * 数据面唯一来源是 [SandboxFileScanner]（与工具页入口卡同一实现）；删除 / 重命名 /
 * 写入**刻意不做** —— 清理走存储页的「沙箱工作区」分桶，职责不混。
 * VM 只产 [SandboxFileInfo] 与文本内容；「打开」的 Intent 组装在 UI 层
 * （需要 Context 与 FileProvider，不属于 VM 职责）。
 */
class SandboxFilesViewModel(
    private val container: AppContainer,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SandboxFilesUiState())
    val uiState: StateFlow<SandboxFilesUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    /** 重新扫描沙箱根层（首次进入 / 返回本页时手动触发）。 */
    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, error = null) }
            val result = withContext(Dispatchers.IO) {
                runCatching { SandboxFileScanner.scan(container.sandboxDir) }
            }
            result.fold(
                onSuccess = { scan ->
                    _uiState.update {
                        it.copy(loading = false, entries = scan.entries, error = null)
                    }
                },
                onFailure = { t ->
                    _uiState.update {
                        it.copy(loading = false, entries = emptyList(), error = t.message ?: "扫描失败")
                    }
                },
            )
        }
    }

    /**
     * 点了一个文件 → 打开预览弹层。
     *
     * 文本白名单内的扩展名才读内容（限 [SANDBOX_PREVIEW_LIMIT_CHARS] 字符，超出截断）；
     * 二进制直接给出「不支持预览」的弹层，只留「打开」出口。读取在 [Dispatchers.IO]。
     */
    fun onPreview(info: SandboxFileInfo) {
        _uiState.update {
            it.copy(selectedPreview = SandboxFilePreview(info = info, text = null, truncated = false, loading = true))
        }
        viewModelScope.launch {
            val outcome = if (!sandboxTextPreviewEligible(info.extension)) {
                // 二进制：不读盘，弹层直接给「不支持预览」终态。
                SandboxFilePreview(info = info, text = null, truncated = false, loading = false)
            } else {
                withContext(Dispatchers.IO) {
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
            _uiState.update { it.copy(selectedPreview = outcome) }
        }
    }

    fun onDismissPreview() {
        _uiState.update { it.copy(selectedPreview = null) }
    }
}

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
