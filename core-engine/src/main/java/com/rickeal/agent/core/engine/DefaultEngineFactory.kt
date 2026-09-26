package com.rickeal.agent.core.engine

import com.rickeal.agent.core.engine.local.LiteRtLmEngine
import com.rickeal.agent.core.model.AgentLogStore
import com.rickeal.agent.core.model.EngineKind
import com.rickeal.agent.core.model.InferenceBackend
import com.rickeal.agent.core.model.InferenceConfig
import com.rickeal.agent.core.model.ModelDescriptor
import com.rickeal.agent.core.model.ModelGpuSupport
import java.util.concurrent.ConcurrentHashMap

/**
 * 手写 DI（简报 §6：禁 Hilt/Koin）。由 :core-data 的 AppContainer 构造。
 * 按 kind 缓存引擎实例 —— 加载 4B 模型很贵，绝不能每次请求都重建。
 * 本应用已收敛为**纯端侧运行**：只有 LOCAL 一种引擎（远程 OpenAI 兼容通道
 * 已整体移除 —— 网络安全面与凭据管理成本不值）。
 */
class DefaultEngineFactory : EngineFactory {

    private val engines = ConcurrentHashMap<EngineKind, LlmEngine>()

    override fun create(kind: EngineKind): LlmEngine = engines.getOrPut(kind) {
        when (kind) {
            EngineKind.LOCAL -> LiteRtLmEngine()
        }
    }

    /**
     * 丢弃某个 kind 的缓存实例，让下一次 create() 拿到全新实例。
     *
     * 线程安全：ConcurrentHashMap.remove 是原子的，因此并发 evict 同一 kind 时
     * 只有一个调用方能拿到旧实例去 close()，不会出现「同一个实例被关两次」或
     * 「关了别人正在用的实例」。remove 与 create 之间仍可能竞态（极端情况下多创建一个
     * 实例），但多出来的那个会被正常 close()，不会泄漏，也不影响正确性。
     */
    override fun evict(kind: EngineKind) {
        engines.remove(kind)?.let { stale -> runCatching { stale.close() } }
    }

    /** 完整的安全约束见 [EngineFactory.closeAll] 的 KDoc —— 那里写明了为什么不能随便挪调用点。 */
    override fun closeAll() {
        for (engine in engines.values) runCatching { engine.close() }
        engines.clear()
    }
}

/** 组装 EngineLoadConfig 的便捷入口，避免每个调用点重复传 4 个目录。 */
class EngineEnvironment(
    val cacheDir: String?,
    val nativeLibraryDir: String?,
    val externalFilesDir: String?,
    val sandboxDir: String,
) {
    fun loadConfig(
        model: ModelDescriptor?,
        config: InferenceConfig,
    ): EngineLoadConfig {
        // GPU 白名单兜底（2026-09-26，核心防闪退闸门）：GPU 路径不支持时是 native
        // 崩溃（SIGSEGV），Kotlin 层 catch 不住 —— 这里是**所有加载路径的唯一汇聚点**
        // （模型库 ModelsViewModel 与对话链路 AgentRunner 都经此组装），统一拦：
        // 文件名不在 [ModelGpuSupport] 白名单（官方无 Android GPU 验证证据 / 无预设
        // 元数据的导入模型）而请求 GPU 的一律落回 CPU 并记日志。
        // UI 层（模型卡选择拦截 + ModelsViewModel 记忆改写）是第一道；这里是保命道。
        var effective = config
        if (config.backend == InferenceBackend.GPU && !ModelGpuSupport.isGpuVerified(model?.fileName)) {
            AgentLogStore.warn(
                "GPU 已回落 CPU：${model?.fileName ?: "未知模型"} 未列入 GPU 白名单" +
                    "（native 路径未经验证，强行加载会闪退）"
            )
            effective = config.copy(backend = InferenceBackend.CPU)
        }
        return EngineLoadConfig(
            model = model,
            config = effective,
            cacheDir = cacheDir,
            nativeLibraryDir = nativeLibraryDir,
            externalFilesDir = externalFilesDir,
            sandboxDir = sandboxDir,
        )
    }
}
