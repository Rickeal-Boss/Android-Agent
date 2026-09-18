package com.rickeal.agent.core.engine

import com.rickeal.agent.core.engine.local.LiteRtLmEngine
import com.rickeal.agent.core.engine.remote.OpenAiCompatibleEngine
import com.rickeal.agent.core.model.EngineKind
import com.rickeal.agent.core.model.InferenceConfig
import com.rickeal.agent.core.model.ModelDescriptor
import com.rickeal.agent.core.model.RemoteEndpoint
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 手写 DI（简报 §6：禁 Hilt/Koin）。由 :core-data 的 AppContainer 构造。
 * 按 kind 缓存引擎实例 —— 加载 4B 模型很贵，绝不能每次请求都重建。
 */
class DefaultEngineFactory(
    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build(),
) : EngineFactory {

    private val engines = ConcurrentHashMap<EngineKind, LlmEngine>()

    override fun create(kind: EngineKind): LlmEngine = engines.getOrPut(kind) {
        when (kind) {
            EngineKind.LOCAL -> LiteRtLmEngine()
            EngineKind.REMOTE -> OpenAiCompatibleEngine(baseClient = okHttpClient)
        }
    }

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
        remote: RemoteEndpoint?,
        config: InferenceConfig,
    ): EngineLoadConfig = EngineLoadConfig(
        model = model,
        remote = remote,
        config = config,
        cacheDir = cacheDir,
        nativeLibraryDir = nativeLibraryDir,
        externalFilesDir = externalFilesDir,
        sandboxDir = sandboxDir,
    )
}
