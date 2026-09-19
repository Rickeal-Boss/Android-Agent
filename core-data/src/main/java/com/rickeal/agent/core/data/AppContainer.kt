package com.rickeal.agent.core.data

import android.app.ActivityManager
import android.net.Uri
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

    // ---- 命名别名：两种叫法都能用，避免 UI 层因为叫错名字编译不过 ----
    val modelsRepository: ModelRepository get() = modelRepository
    val remoteEndpointsRepository: EndpointRepository get() = endpointRepository
    val conversationsRepository: ConversationRepository get() = conversationRepository

    val sandboxDir: File = File(context.filesDir, "agent_sandbox").apply { mkdirs() }

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
            info.availMem
        }.getOrDefault(Long.MAX_VALUE)
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
