package com.rickeal.agent.core.engine

import com.rickeal.agent.core.model.InferenceBackend
import com.rickeal.agent.core.model.InferenceConfig
import com.rickeal.agent.core.model.ModelDescriptor
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [EngineEnvironment.loadConfig] 的 GPU 白名单回落单测（Wave 31 · 流 4）。
 *
 * 为什么值得写：这里是**所有加载路径的唯一汇聚点**（模型库 ModelsViewModel 与对话链路
 * AgentRunner 都经此组装 EngineLoadConfig），而它做的 GPU→CPU 回落是一道**防闪退闸门** ——
 * 判据写反（该回落时没回落）的表现是 native SIGSEGV，Kotlin 层 catch 不住、日志也记不下来。
 * 纯函数、零 native：只构造 EngineLoadConfig 数据对象，不触碰任何引擎。
 */
class EngineEnvironmentTest {

    private val env = EngineEnvironment(
        cacheDir = "/cache",
        nativeLibraryDir = "/libs",
        externalFilesDir = "/ext",
        sandboxDir = "/sandbox",
    )

    @Test
    fun `GPU 白名单外的模型回落 CPU`() {
        val load = env.loadConfig(
            ModelDescriptor(fileName = "imported-unknown.litertlm"),
            InferenceConfig(backend = InferenceBackend.GPU),
        )
        assertEquals(InferenceBackend.CPU, load.config.backend)
    }

    @Test
    fun `GPU 白名单内的模型保留 GPU`() {
        val load = env.loadConfig(
            ModelDescriptor(fileName = "gemma-4-E2B-it-gpu.litertlm"),
            InferenceConfig(backend = InferenceBackend.GPU),
        )
        assertEquals(InferenceBackend.GPU, load.config.backend)
    }

    @Test
    fun `无模型时请求 GPU 回落 CPU`() {
        // model == null 时 isGpuVerified(null) = false，从严防闪退：一律 CPU。
        val load = env.loadConfig(null, InferenceConfig(backend = InferenceBackend.GPU))
        assertEquals(InferenceBackend.CPU, load.config.backend)
    }

    @Test
    fun `CPU 请求不被改写`() {
        val load = env.loadConfig(
            ModelDescriptor(fileName = "whatever.litertlm"),
            InferenceConfig(backend = InferenceBackend.CPU),
        )
        assertEquals(InferenceBackend.CPU, load.config.backend)
    }

    @Test
    fun `模型与四个目录原样透传`() {
        val model = ModelDescriptor(fileName = "m.litertlm")
        val load = env.loadConfig(model, InferenceConfig())
        assertEquals(model, load.model)
        assertEquals("/cache", load.cacheDir)
        assertEquals("/libs", load.nativeLibraryDir)
        assertEquals("/ext", load.externalFilesDir)
        assertEquals("/sandbox", load.sandboxDir)
    }
}
