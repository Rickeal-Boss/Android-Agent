package com.rickeal.agent.core.data

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
