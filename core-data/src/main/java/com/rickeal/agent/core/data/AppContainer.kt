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
import com.rickeal.agent.core.model.AgentLogStore
import com.rickeal.agent.core.agent.ToolContext
import com.rickeal.agent.core.agent.ToolRegistry
import com.rickeal.agent.core.agent.installBuiltInTools
import com.rickeal.agent.core.agent.approval.InMemoryToolApprovalCache
import com.rickeal.agent.core.agent.history.SegmentedHistoryStore
import com.rickeal.agent.core.agent.memory.AgentMemory
import com.rickeal.agent.core.agent.memory.installMemoryTools
import com.rickeal.agent.core.agent.plan.AgentPlanStore
import com.rickeal.agent.core.agent.plan.installPlanTools
import com.rickeal.agent.core.agent.subagent.AskSubagentTool
import com.rickeal.agent.core.agent.subagent.BuiltInSubagents
import com.rickeal.agent.core.agent.subagent.SubagentRegistry
import com.rickeal.agent.core.agent.subagent.SubagentSessionStore
import com.rickeal.agent.core.engine.DefaultEngineFactory
import com.rickeal.agent.core.engine.EngineEnvironment
import com.rickeal.agent.core.engine.EngineFactory
import com.rickeal.agent.core.engine.EngineInitStatus
import com.rickeal.agent.core.engine.EngineLoadCoordinator
import com.rickeal.agent.core.data.notify.AndroidGenerationNotifier
import com.rickeal.agent.core.data.notify.GenerationNotifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File

/** 附件拷贝缓冲（1MB）：默认 8KB 拷几十 MB 的高清原图要走上万次循环。 */
private const val ATTACHMENT_COPY_BUFFER_BYTES = 1024 * 1024

/**
 * 手写 DI 容器（简报 §6：禁 Hilt/Koin）。
 *
 * 由 `LiquidAgentApplication` 持有，通过 `LocalAppContainer` 提供给整个 Compose 树。
 */
class AppContainer(
    private val context: Context,
    /**
     * 「生成速度通知」的状态栏小图标 res id。由 app 层传入（`R.drawable.ic_stat_generation`）
     * —— core-data 不能引 app 的 R 类，而状态栏小图标又必须是 app 自己的资源。
     * 默认 0 = 不配通知图标，此时 notifier 的 notify 会静默失败（通知是观测窗口不是能力）。
     */
    val generationIconRes: Int = 0,
) {

    val settingsRepository: SettingsRepository = SettingsRepository(context)
    val modelRepository: ModelRepository = ModelRepository(context, settingsRepository)
    val conversationRepository: ConversationRepository = ConversationRepository(context)

    /**
     * 附件存储器。**同时是附件目录的唯一事实来源** —— [importAttachment] 与
     * [StorageUsageStore] 都从这里取目录，不再各自拼 `filesDir/attachments`
     *（此前 [AttachmentStore] 与 [AppContainer.importAttachment] 各拼了一次，
     * overview.md DAT-B3 记为两处几乎逐行相同的重复实现）。
     */
    val attachmentStore: AttachmentStore = AttachmentStore(context)

    /** 诊断日志目录（`filesDir/diagnostics`）：[agentLogFileStore] 的落点，也是存储用量分桶之一。 */
    val diagnosticsDir: File = File(context.filesDir, "diagnostics")

    /** 设置（DataStore）落点目录（`filesDir/datastore`）：存储用量分桶之一。 */
    private val settingsDataStoreDir: File = File(context.filesDir, "datastore")

    /**
     * ERROR 级日志的「崩溃幸存」落盘（见 [AgentLogFileStore]）。
     *
     * 放在 filesDir 而不是 cacheDir：cacheDir 会被系统在存储紧张时清掉，
     * 而崩溃现场恰恰可能在系统刚清理过之后才被查看。隐私上的代价由写入前的脱敏承担
     * （见 AgentLogStore.sanitize）。
     */
    val agentLogFileStore: AgentLogFileStore =
        AgentLogFileStore(File(diagnosticsDir, "last_errors.log"))

    init {
        // 尽早装上报错落盘：崩溃前最后一条 ERROR 必须已经写到磁盘上，
        // 否则「重启后诊断页一片空白」这个最要命的场景依然存在。
        agentLogFileStore.install()
    }

    // ---- 命名别名：两种叫法都能用，避免 UI 层因为叫错名字编译不过 ----
    val modelsRepository: ModelRepository get() = modelRepository
    val conversationsRepository: ConversationRepository get() = conversationRepository

    val sandboxDir: File = File(context.filesDir, "agent_sandbox").apply { mkdirs() }

    /**
     * Agent run journal 根目录（`<filesDir>/journal/<conversationId>/<runId>.jsonl`）。
     * 每次 run 由 ChatViewModel 打开一个新文件；进程被杀后可从这里恢复已完成的
     * 推理轮与工具结果（core-agent/journal/AgentRunJournal）。
     */
    val journalRoot: File = File(context.filesDir, "journal")

    /**
     * 回合归档根目录（Wave3 history_v2：`<filesDir>/history/<conversationId>/`）。
     * run 终态后由宿主把 journal 折叠成 TurnRecord 归档（正文进内容寻址池），
     * journal 本体随后改名 .jsonl.archived 退出恢复扫描 —— AgentRunner 零感知。
     */
    val historyRoot: File = File(context.filesDir, "history")

    /**
     * 按会话缓存的回合归档存储池（外部审查报告2 §4.1，B3 锁失效根治）。
     *
     * [SegmentedHistoryStore] 的 commitMutex 是**实例级** Mutex：此前宿主每次
     * archiveTurn 都 `open()` 一个新实例，四个并发归档入口各拿各的锁，互斥形同虚设。
     * 按 conversationId 池化后，同一会话的所有归档路径共享同一实例 ——
     * commitMutex 因此真正生效。
     *
     * 生命周期：实例与会话同生命周期，随 AppContainer 存活；会话删除后目录条目
     * 残留（一个空 store 对象 + 已删目录的引用），无泄漏风险，不值得为此加失效回调。
     */
    private val historyStores = java.util.concurrent.ConcurrentHashMap<String, SegmentedHistoryStore>()

    fun historyStore(conversationId: String): SegmentedHistoryStore =
        historyStores.getOrPut(conversationId) {
            SegmentedHistoryStore.open(historyRoot, conversationId)
        }

    /** 模型下载目录（`externalFilesDir/Download`，DownloadManager 的落盘处）。 */
    private val downloadsDir: File? = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)

    /** 模型下载（系统 DownloadManager，落盘到 externalFilesDir/Download）。 */
    val downloadDirPath: String?
        get() = downloadsDir?.absolutePath

    /** 模型下载（系统 DownloadManager，落盘到 externalFilesDir/Download）。 */
    val modelDownloader: ModelDownloader = ModelDownloader(context)

    /**
     * 「生成速度通知」出口（Wave 9 需求 5）。默认 disabled：由设置页的开关
     * （collect 到 [SettingsRepository.generationNotification]）驱动 [GenerationNotifier.enabled]。
     * 先例：[ModelDownloader] 同样在 core-data 里用系统服务（DownloadManager）。
     */
    val generationNotifier: GenerationNotifier = AndroidGenerationNotifier(context, generationIconRes)

    /**
     * 自定义壁纸（Wave 9 需求 3b）的导入 / 解码 / 删除。设置页选图走它，
     * LiquidAgentApp 按路径解码后经 `LocalWallpaperImage` 下发给玻璃层。
     */
    val wallpaperStore: WallpaperStore = WallpaperStore(context)

    val engineEnvironment: EngineEnvironment = EngineEnvironment(
        cacheDir = context.cacheDir?.absolutePath,
        nativeLibraryDir = context.applicationInfo.nativeLibraryDir,
        externalFilesDir = context.getExternalFilesDir(null)?.absolutePath,
        sandboxDir = sandboxDir.absolutePath,
    )

    // 引擎加载状态机（Wave3，gallery 竞态防护语义移植）：装饰 DefaultEngineFactory，
    // create() 返回的实例在 load 时向 engineInitStatus 状态流上报 Initializing/Initialized/Failed；
    // 加载中收到的 evict 延迟到 load 收尾消化（native load 不可中断）。AgentRunner 零改动。
    private val engineLoadCoordinator: EngineLoadCoordinator =
        EngineLoadCoordinator(DefaultEngineFactory())

    val engineFactory: EngineFactory get() = engineLoadCoordinator

    /** 引擎加载状态流（本轮仅供观察/日志，不接 UI）。 */
    val engineInitStatus: StateFlow<EngineInitStatus> get() = engineLoadCoordinator.status

    /** 长期记忆目录（`filesDir/agent_memory`）：[agentMemory] 的落点，也是存储用量分桶之一。 */
    private val agentMemoryDir: File = File(context.filesDir, "agent_memory")

    /** 长期记忆（harness-memory 移植）：filesDir/agent_memory/memory.json */
    val agentMemory: AgentMemory = AgentMemory(File(agentMemoryDir, "memory.json"))

    /** 执行计划目录（`filesDir/agent_plans`）：[agentPlanStore] 的落点，也是存储用量分桶之一。 */
    private val agentPlansDir: File = File(context.filesDir, "agent_plans")

    /** 会话级执行计划（ZCode Phase Graph 降级移植）：plan_set / plan_update 工具的落点。 */
    val agentPlanStore: AgentPlanStore = AgentPlanStore(
        // Wave3 起持久化：进程死亡后计划还在（蓝图「长程任务不丢上下文」的恢复闭环）。
        persistDir = agentPlansDir,
    )

    /**
     * 审批缓存（Wave3「计划级授权」轻量降级）：用户显式授权的
     * 「会话 × 工具 × 参数摘要」TTL 30min 内免再弹卡。进程级单例是安全的 ——
     * 纯运行态（不序列化）、key 含会话 id 天然隔离、拒绝永不缓存。
     */
    val toolApprovalCache: InMemoryToolApprovalCache = InMemoryToolApprovalCache()

    val toolContext: ToolContext = ToolContext(
        sandboxDir = sandboxDir,
        appContext = context.applicationContext,
        clipboard = context.applicationContext
            .getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager,
        agentMemory = agentMemory,
    )

    val toolRegistry: ToolRegistry = ToolRegistry().apply {
        installBuiltInTools(toolContext)
        installMemoryTools(this, agentMemory)
        installPlanTools(this, agentPlanStore)
    }

    val agentRunner: AgentRunner = AgentRunner(
        engineFactory = engineFactory,
        toolRegistry = toolRegistry,
        environment = engineEnvironment,
    )

    // ---- 子代理框架（ZCode Actor / Octop ask_agent 移植）----
    // 顺序有讲究：先建 runner，再把 ask_actor 注册进 registry（工具内部引用 runner）。
    val subagentRegistry: SubagentRegistry = SubagentRegistry().also { BuiltInSubagents.registerAll(it) }
    /** 子代理会话目录（`filesDir/subagent_sessions`）：[subagentSessions] 的落点，也是存储用量分桶之一。 */
    private val subagentSessionsDir: File = File(context.filesDir, "subagent_sessions")

    // Actor 会话持久化目录：App 被杀后子代理上下文仍在（Wave 2 对齐 ZCode 持久化 Actor）
    val subagentSessions: SubagentSessionStore = SubagentSessionStore(
        persistDir = subagentSessionsDir,
    )
    val subagentTool: AskSubagentTool = AskSubagentTool(
        subagentRegistry,
        subagentSessions,
        agentRunner,
        // Wave3：白名单兜底用真实工具注册表解析「继承全部」（曾误用 Actor 注册表
        // 的名字当工具名，Markdown 自定义 Actor 实际零工具可用）。
        toolRegistry = toolRegistry,
    )

    /**
     * 存储空间用量统计（Wave 10 Phase 2b C-2）。
     *
     * **路径全部来自各 Store 的 `directory` 属性**（唯一事实来源），这里只做汇总与分档：
     * 前 7 个是**可再生成**（可清除），后 7 个是**用户资产**（只显示大小、不提供一键清）——
     * 端侧没有云端副本，一键清资产 = 不可恢复的数据销毁（见 [StorageUsageStore] KDoc）。
     */
    val storageUsageStore: StorageUsageStore = StorageUsageStore(
        listOf(
            // ── 可再生成（可清除）────────────────────────────────────────────
            StorageBucketSource(
                id = "cache",
                title = "缓存",
                description = "临时文件，清除后相关功能会按需重建",
                targets = listOfNotNull(context.cacheDir),
                clearable = true,
            ),
            StorageBucketSource(
                id = "journal",
                title = "运行日志",
                description = "推理过程的临时记录（用于进程被杀后恢复）",
                targets = listOf(journalRoot),
                clearable = true,
            ),
            StorageBucketSource(
                id = "history",
                title = "回合归档",
                // 副文案要讲清「归档 ≠ 会话内容」：用户看到"归档"两个字容易以为
                // 对话历史没了而不敢清。归档可从会话正文重建（见 SegmentedHistoryStore）。
                description = "已完成回合的分段归档。删除后可由会话正文重建，不影响会话内容本身。",
                targets = listOf(historyRoot),
                clearable = true,
            ),
            StorageBucketSource(
                id = "agent_plans",
                title = "执行计划",
                description = "长程任务的计划快照",
                targets = listOf(agentPlansDir),
                clearable = true,
            ),
            StorageBucketSource(
                id = "subagent_sessions",
                title = "子代理会话",
                description = "子代理（Actor）的会话上下文",
                targets = listOf(subagentSessionsDir),
                clearable = true,
            ),
            StorageBucketSource(
                id = "diagnostics",
                title = "诊断日志",
                description = "崩溃幸存的错误日志",
                targets = listOf(diagnosticsDir),
                clearable = true,
            ),
            StorageBucketSource(
                id = "agent_sandbox",
                title = "沙箱工作区",
                // 这里必须写明后果：沙箱是**工具调用的产出落点**（用户可能让 agent 在
                // 那里生成过报告 / 文件），清掉就是真丢，不能只说"沙箱数据"这种抽象词。
                description = "Agent 执行工具时写入的工作区文件。清除后这些文件不可恢复。",
                targets = listOf(sandboxDir),
                clearable = true,
            ),
            // ── 用户资产（只显示大小，不提供一键清）──────────────────────────
            StorageBucketSource(
                id = "models",
                title = "模型文件",
                description = "已下载 / 导入的模型；请在模型库中逐个删除",
                targets = listOfNotNull(
                    modelRepository.directory,
                    modelRepository.externalDirectory,
                    downloadsDir,
                ),
                clearable = false,
            ),
            StorageBucketSource(
                id = "model_index",
                title = "模型索引",
                description = "模型清单（记录已登记模型）",
                targets = listOf(modelRepository.indexDirectory),
                clearable = false,
            ),
            StorageBucketSource(
                id = "conversations",
                title = "会话记录",
                description = "全部对话内容；请在对话中删除",
                targets = listOf(conversationRepository.directory),
                clearable = false,
            ),
            StorageBucketSource(
                id = "attachments",
                title = "附件",
                description = "随消息发送的图片 / 音频",
                targets = listOf(attachmentStore.directory),
                clearable = false,
            ),
            StorageBucketSource(
                id = "agent_memory",
                title = "长期记忆",
                description = "模型沉淀的记忆条目；可在记忆页维护",
                targets = listOf(agentMemoryDir),
                clearable = false,
            ),
            StorageBucketSource(
                id = "wallpaper",
                title = "壁纸",
                description = "自定义背景图片",
                targets = listOf(wallpaperStore.directory),
                clearable = false,
            ),
            StorageBucketSource(
                id = "settings",
                title = "设置数据",
                description = "主题、推理参数等偏好设置",
                targets = listOf(settingsDataStoreDir),
                clearable = false,
            ),
        ),
    )

    init {
        toolRegistry.register(subagentTool)
    }

    /**
     * 冷启动预热：把两个仓库的内存快照拉起来。
     *
     * 两个 refresh **各自独立 runCatching**：串行直调时前一个抛异常，后一个就彻底不执行 ——
     * 一次 `models.json` 解析失败（或存储满导致写失败）就会让用户存的模型清单、会话列表
     * 全看不见，而日志里一条记录都没有。
     * 两者互相没有依赖（各自的 JSON 文件、各自的 StateFlow），独立隔离是安全的。
     * 每个失败都记 ERROR：这是唯一能让"冷启动静默失败"变得可诊断的手段。
     */
    suspend fun bootstrap() {
        runCatching { modelRepository.refresh() }
            .onFailure { AgentLogStore.error("模型清单加载失败（${it.javaClass.simpleName}）") }
        runCatching { conversationRepository.refresh() }
            .onFailure { AgentLogStore.error("会话索引加载失败（${it.javaClass.simpleName}）") }
    }

    /**
     * 把用户通过 SAF 选中的附件（content://）复制进内部目录，返回**真实文件路径**。
     *
     * 必须做这一步：本地引擎与远程引擎都按「文件路径」读取附件字节，
     * 直接存 content:// Uri 会导致图片/音频 100% 读取失败（多模态形同虚设）。
     *
     * **必须是 suspend + Dispatchers.IO**：这里是几十 MB 的阻塞拷贝（高清原图 / 长录音），
     * 调用方 `ChatViewModel.onAttachImage/onAttachAudio` 在 `viewModelScope`（默认主线程）里
     * 直接调用，同步版本会把"选一张图"变成 ANR + StrictMode 违规。
     */
    suspend fun importAttachment(uriString: String, fileName: String): String? =
        withContext(Dispatchers.IO) {
            val raw = uriString.trim()
            if (raw.isBlank()) return@withContext null
            // 已经是真实路径的情况（部分设备 / 自定义来源）直接用
            val direct = if (raw.startsWith("file://")) raw.removePrefix("file://") else raw
            if (direct.startsWith("/") && File(direct).exists()) return@withContext direct
            runCatching {
                val uri = Uri.parse(raw)
                // 目录从 [attachmentStore] 取（唯一事实来源），不再就地拼 filesDir/attachments。
                // ⚠️ `mkdirs()` 必须在这里显式调用，**不能依赖 AttachmentStore 构造器里的
                // `.apply { mkdirs() }` 副作用**：那个类现在只为暴露 directory 而存在（其自带的
                // importAttachment 已是死代码），一旦被当作死代码清理掉，attachments 目录将永不
                // 创建，而 `target.outputStream()` 抛出的异常会被下面的 runCatching 吞掉 ⇒
                // **附件导入静默失败（返回 null）**。mkdirs() 幂等、零成本。
                val dir = attachmentStore.directory.apply { mkdirs() }
                val safeName =
                    fileName.substringAfterLast('/').ifBlank { "attachment_${System.currentTimeMillis()}" }
                val target = File(dir, "${System.currentTimeMillis()}_$safeName")
                val input = context.contentResolver.openInputStream(uri)
                    ?: return@runCatching null
                // 1MB 缓冲：默认 8KB 拷几十 MB 要走上万次循环
                input.use { source ->
                    target.outputStream().use { output -> source.copyTo(output, ATTACHMENT_COPY_BUFFER_BYTES) }
                }
                if (target.length() <= 0L) return@runCatching null
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
        // 硬闸门（外部审查报告3-A2 修正项）：closeAll 是同步的、无法等待在途生成收敛，
        // 引擎忙时强关 = native use-after-free（SIGSEGV，runCatching 抓不住）。
        // isBusy 与 AgentRunner.runMutex 严格同源（Wave4 C-P0-1 建立）：读到 false
        // 才意味着此刻没有任何 run 持有引擎。调用点 LiquidAgentApplication.onTerminate
        // 是尽力而为语义，跳过比崩掉好。
        if (agentRunner.isBusy.value) {
            AgentLogStore.warn("引擎忙，跳过 closeAll（防 native use-after-free）")
            return
        }
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
