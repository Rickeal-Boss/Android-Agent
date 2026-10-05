package com.rickeal.agent.core.engine.local

import com.rickeal.agent.core.model.InferenceConfig
import com.rickeal.agent.core.model.SamplingParams
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [probeSamplingConfig] 的 JVM 纯逻辑测试（Wave 49 E1 探针采样两处缺陷的离线锚）。
 *
 * 为什么能跑在 JVM 上：被测的是纯 Kotlin `internal` 顶层函数，**无任何引擎 / Android 依赖**
 * （探针本体 `ModelHealthProbe.run` 需要真引擎，只能真机验收）。生产路径 `ModelHealthProbe`
 * 的 `probeRequest` 真实调用它，故测试覆盖的是**真实逻辑而非镜像**。
 *
 * 断言收尾一律用返回 `Unit` 的函数（`assertEquals` / `assertTrue`）—— 避免 JUnit 4 误判非 `void`。
 */
class ModelHealthProbeConfigTest {

    /** 命中档案的模型：采样被钳进档案区间（gemma-4-E2B-it 温度区间 0.3..1.5）⇒ 0.1 抬到 0.3。 */
    @Test
    fun 探针配置过模型档案钳制低温() {
        val cold = InferenceConfig(sampling = SamplingParams(temperature = 0.1f))
        val out = probeSamplingConfig("gemma-4-E2B-it.litertlm", cold)
        assertEquals(0.3f, out.sampling.temperature)
    }

    /** 钉 `maxTokens` 后必须**重新归一** `thinkingTokenBudget`（不变量 `< maxTokens`）。 */
    @Test
    fun 探针配置钉maxTokens并归一思考预算() {
        val cfg = InferenceConfig(maxTokens = 8192, thinkingTokenBudget = 2048)
        val out = probeSamplingConfig("gemma-4-E2B-it.litertlm", cfg)
        assertEquals(ModelHealthProbe.PROBE_MAX_TOKENS, out.maxTokens)
        // 若不复归，2048 > 96 ⇒ 引擎钳到 95 ⇒ 思考吃光 96 token、正文为空。
        assertTrue(out.thinkingTokenBudget < out.maxTokens)
    }

    /** 未命中档案的模型：采样原值透传，但仍钉 `maxTokens`（成本上界与档案无关）。 */
    @Test
    fun 未命中档案的模型仅钉maxTokens() {
        val cfg = InferenceConfig(sampling = SamplingParams(temperature = 0.42f))
        val out = probeSamplingConfig("unknown-model.litertlm", cfg)
        assertEquals(0.42f, out.sampling.temperature)
        assertEquals(ModelHealthProbe.PROBE_MAX_TOKENS, out.maxTokens)
    }

    /** 档案的 `minMaxTokens`（思考模型 2048）不得把探针的 96 token 成本上界顶开。 */
    @Test
    fun 档案minMaxTokens不顶开探针成本上界() {
        val out = probeSamplingConfig("MiniCPM5-2B_int4.litertlm", InferenceConfig())
        assertEquals(ModelHealthProbe.PROBE_MAX_TOKENS, out.maxTokens)
    }
}
