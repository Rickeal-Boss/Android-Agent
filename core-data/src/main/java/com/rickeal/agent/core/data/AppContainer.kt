package com.rickeal.agent.core.data

import android.app.ActivityManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Environment
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import com.rickeal.agent.core.agent.AgentRunner
import com.rickeal.agent.core.agent.ToolContext
import com.rickeal.agent.core.agent.ToolRegistry
import com.rickeal.agent.core.agent.installBuiltInTools
import com.rickeal.agent.core.engine.DefaultEngineFactory
import com.rickeal.agent.core.engine.EngineEnvironment
import com.rickeal.agent.core.engine.EngineFactory
import java.io.File

/**
 * 手写 DI 容器（简报 §6：禁 Hilt/Koin）。
 *
 * 由 `LiquidAgentApplication` 持有，通过 `LocalAppContainer` 提供给整个 Compose 树。
 */
class AppContainer(private val context: Context) {

    val settingsRepository: SettingsRepository = SettingsRepository(context)
    val modelRepository: ModelRepository = ModelRepository(context, settingsRepository)
    val endpointRepository: EndpointRepository = EndpointRepository(context)
    val conversationRepository: ConversationRepository = ConversationRepository(context)

    /**
     * ERROR 级日志的「崩溃幸存」落盘（见 [AgentLogFileStore]）。
     *
     * 放在 filesDir 而不是 cacheDir：cacheDir 会被系统在存储紧张时清掉，
     * 而崩溃现场恰恰可能在系统刚清理过之后才被查看。隐私上的代价由写入前的脱敏承担
     * （见 AgentLogStore.sanitize）。
     */
    val agentLogFileStore: AgentLogFileStore =
        AgentLogFileStore(File(context.filesDir, "diagnostics/last_errors.log"))

    init {
        // 尽早装上报错落盘：崩溃前最后一条 ERROR 必须已经写到磁盘上，
        // 否则「重启后诊断页一片空白」这个最要命的场景依然存在。
        agentLogFileStore.install()
    }

    // ---- 命名别名：两种叫法都能用，避免 UI 层因为叫错名字编译不过 ----
    val modelsRepository: ModelRepository get() = modelRepository
    val remoteEndpointsRepository: EndpointRepository get() = endpointRepository
    val conversationsRepository: ConversationRepository get() = conversationRepository

    val sandboxDir: File = File(context.filesDir, "agent_sandbox").apply { mkdirs() }

    /** 模型下载（系统 DownloadManager，落盘到 externalFilesDir/Download）。 */
    val downloadDirPath: String?
        get() = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.absolutePath

    /** 模型下载（系统 DownloadManager，落盘到 externalFilesDir/Download）。 */
    val modelDownloader: ModelDownloader = ModelDownloader(context)

    val engineEnvironment: EngineEnvironment = EngineEnvironment(
        cacheDir = context.cacheDir?.absolutePath,
        nativeLibraryDir = context.applicationInfo.nativeLibraryDir,
        externalFilesDir = context.getExternalFilesDir(null)?.absolutePath,
        sandboxDir = sandboxDir.absolutePath,
    )

    val engineFactory: EngineFactory = DefaultEngineFactory()

    val toolContext: ToolContext = ToolContext(
        sandboxDir = sandboxDir,
        appContext = context.applicationContext,
        clipboard = context.applicationContext
            .getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager,
    )

    val toolRegistry: ToolRegistry = ToolRegistry().apply { installBuiltInTools(toolContext) }

    val agentRunner: AgentRunner = AgentRunner(
        engineFactory = engineFactory,
        toolRegistry = toolRegistry,
        environment = engineEnvironment,
    )

    /** 冷启动预热：把三个仓库的内存快照拉起来。 */
    suspend fun bootstrap() {
        modelRepository.refresh()
        endpointRepository.refresh()
        conversationRepository.refresh()
    }

    /**
     * 把用户通过 SAF 选中的附件（content://）复制进内部目录，返回**真实文件路径**。
     *
     * 必须做这一步：本地引擎与远程引擎都按「文件路径」读取附件字节，
     * 直接存 content:// Uri 会导致图片/音频 100% 读取失败（多模态形同虚设）。
     */
    fun importAttachment(uriString: String, fileName: String): String? {
        val raw = uriString.trim()
        if (raw.isBlank()) return null
        // 已经是真实路径的情况（部分设备 / 自定义来源）直接用
        val direct = if (raw.startsWith("file://")) raw.removePrefix("file://") else raw
        if (direct.startsWith("/") && File(direct).exists()) return direct
        return runCatching {
            val uri = Uri.parse(raw)
            val dir = File(context.filesDir, "attachments").apply { mkdirs() }
            val safeName = fileName.substringAfterLast('/').ifBlank { "attachment_${System.currentTimeMillis()}" }
            val target = File(dir, "${System.currentTimeMillis()}_$safeName")
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            if (target.length() <= 0L) return null
            target.absolutePath
        }.getOrNull()
    }

    /**
     * 当前可用内存（字节）。用于「加载模型前的内存闸门」：
     * 4B 模型加载失败在 native 层表现为 SIGSEGV / OOM，用户感知是「闪退」，
     * 与其等几十秒后崩溃，不如提前拦下并给出可执行的建议。
     */
    fun availableMemoryBytes(): Long {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return Long.MAX_VALUE
        val info = ActivityManager.MemoryInfo()
        return runCatching {
            manager.getMemoryInfo(info)
            // 不要改用 advertisedMem（API 34+）：它是「设备标称内存」（营销档位的 8GB / 12GB），
            // 不是当前可用内存。用它做闸门会让闸门几乎永远通过 —— 该拦的拦不住，
            // 用户点加载就直接 native 崩溃。
            // 失败模式的取舍很明确：误报只是「拦下、用户换个更小的模型」，
            // 而闸门失效是崩溃。宁可偏保守。
            info.availMem
        }.getOrDefault(Long.MAX_VALUE)
    }

    /**
     * 可用存储空间（字节）。用于「下载模型前」的检查：
     * 4B 模型 1~4GB，下到一半空间不足会浪费用户大量时间和流量，必须提前拦下。
     *
     * 口径：两种导入方式落盘位置不同，所以取两者的**较小值**：
     *  - 直链下载的模型落在 `externalFilesDir/Download`（DownloadManager 的落盘位置），
     *    完成后就地登记、不复制，占的是外置分区；
     *  - SAF 选文件导入的模型会被复制进内部 `filesDir/models`，占的是内部数据分区。
     * 只查内部目录会在「外置分区更小」的机型上低估风险。
     *
     * 一个目录都读不到时返回 [Long.MAX_VALUE]（视为不限制）：宁可放行，
     * 也不要因为读不到就误拦，让用户下不了模型。
     */
    fun availableStorageBytes(): Long {
        val dirs = listOfNotNull(context.getExternalFilesDir(null), context.filesDir)
        var min = Long.MAX_VALUE
        for (dir in dirs) {
            val bytes = runCatching {
                android.os.StatFs(dir.absolutePath).availableBytes
            }.getOrNull() ?: continue
            if (bytes < min) min = bytes
        }
        return min
    }

    /**
     * 当前网络是否为「按流量计费」（通常是移动数据）。
     *
     * 用于下载 GB 级模型前的二次确认：用户稀里糊涂用流量下 4GB 的代价太高，
     * 而我们在 UI 上写的「建议连 Wi-Fi」只是一句文案，不检查等于没有。
     *
     * 判断依据：活动网络是否具备 NET_CAPABILITY_NOT_METERED（Wi-Fi 通常具备）。
     * **无法判断时返回 true** —— 宁可多问一句，也不要让用户白白花掉流量。
     */
    fun isMeteredNetwork(): Boolean {
        val manager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return true
        return runCatching {
            val network = manager.activeNetwork ?: return true
            val caps = manager.getNetworkCapabilities(network) ?: return true
            !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        }.getOrDefault(true)
    }

    fun close() {
        engineFactory.closeAll()
    }
}

/**
 * UI 层唯一的取依赖入口。放在 :core-data 是为了让 feature 模块能引用类型。
 *
 * 注意：AppContainer 必须的是 **Application Context**（构造函数里已用 applicationContext 兜底），
 * 否则 Activity 重建会导致泄漏。
 */
val LocalAppContainer: ProvidableCompositionLocal<AppContainer> =
    staticCompositionLocalOf { error("LocalAppContainer 未提供：请在 LiquidAgentTheme 外层 CompositionLocalProvider") }
