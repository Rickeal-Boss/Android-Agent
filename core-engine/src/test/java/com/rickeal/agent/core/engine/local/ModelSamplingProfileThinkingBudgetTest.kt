package com.rickeal.agent.core.engine.local

import com.rickeal.agent.core.model.InferenceConfig
import com.rickeal.agent.core.model.ModelSamplingProfile
import com.rickeal.agent.core.model.ModelSamplingProfiles
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [ModelSamplingProfile.minThinkingTokenBudget] 轴的固化单测（W65 M-1 治本）。
 *
 * 为什么放在 core-engine 测试源集：core-model **不可见** core-engine（零引擎依赖硬约束），
 * 而下限存活判据必须经 [resolveThinkingBudget] 的 `1..maxTokens-1` 钳制运算；core-engine
 * 同时可见 `ModelSamplingProfiles`（core-model）与本模块钳制函数 ⇒ 与
 * `ThinkingBudgetInvariantTest` 同一既有先例。
 *
 * 治本背景（M-1 / 外部审查 P1）：`minMaxTokens` 只把 maxTokens 顶到 2048 档，用户不动滑条时
 * native 思考预算 = 半预算 = 1024 token，而 R1 / MiniCPM5 两份档案 evidence 的官方口径是
 * 「思维链需完整（≈≥2048）」—— 差 2× 的结构性缺口；`maxContextLength=4096` 堵死抬
 * minMaxTokens 的路，唯一旋钮是显式 thinking 预算下限（本轴）。取 1536 而非 2048：
 * 2048 在 `maxTokens=2048` 档会被钳到只剩 1 token 给正文，不可行；1536 留 ≥512 正文余量，
 * 且仍大于旧半预算 1024。
 *
 * 纯 JVM：零协程 / 零 Android / 零 native。断言收尾一律用返回 `Unit` 的函数
 * （`assertEquals` / `assertTrue` / `assertFailsWith` 的 block 体恒 `Unit`）—— 避免 JUnit 4
 * 误判方法签名非 `void`；方法名不含 `. ; [ /` 字符（同文件既有约定）。
 */
class ModelSamplingProfileThinkingBudgetTest {

    /** 两个思考模型档案的文件名（R1 一个 + MiniCPM5 两档）。 */
    private val thinkingProfiles = listOf(
        "DeepSeek-R1-Distill-Qwen-1.5B_multi-prefill-seq_q8_ekv4096.litertlm",
        "MiniCPM5-2B_int4.litertlm",
        "MiniCPM5-2B_int8.litertlm",
    )

    /** 不变的档案值：下限恰为 minMaxTokens - 512（正文余量 512 token）。 */
    private val expectedFloor = 1536

    /** 测试夹具：只覆盖本轴关心的两个参数，其余取任意合法值。 */
    private fun profileWith(minMaxTokens: Int, minThinkingTokenBudget: Int): ModelSamplingProfile =
        ModelSamplingProfile(
            recommendedTemperature = 0.6f,
            temperatureRange = 0.5f..0.7f,
            recommendedTopK = 40,
            maxTopK = 100,
            maxContextLength = 4096,
            evidence = "test fixture",
            minMaxTokens = minMaxTokens,
            minThinkingTokenBudget = minThinkingTokenBudget,
        )

    /** ① 下限存活：档案登记的 1536 经 resolveThinkingBudget 钳制后原样存活，且 < minMaxTokens。 */
    @Test
    fun `档案下限经钳制后存活且小于minMaxTokens`() {
        for (fileName in thinkingProfiles) {
            val profile = requireNotNull(ModelSamplingProfiles.forFileName(fileName)) {
                "未命中档案：$fileName"
            }
            assertEquals(
                expectedFloor,
                profile.minThinkingTokenBudget,
                "$fileName：档案登记的 thinking 预算下限应为 $expectedFloor",
            )
            val resolved = resolveThinkingBudget(profile.minThinkingTokenBudget, profile.minMaxTokens)
            assertEquals(
                profile.minThinkingTokenBudget,
                resolved,
                "$fileName：下限必须经 resolveThinkingBudget 钳制后原样存活（不得被钳小）",
            )
            assertTrue(
                resolved < profile.minMaxTokens,
                "$fileName：thinking 预算 $resolved 必须 < maxTokens 下限 ${profile.minMaxTokens}（给正文留余量）",
            )
        }
    }

    /** ② 行为测：R1 文件名 + 默认档（thinkingTokenBudget=0, maxTokens=1024）⇒ 两轴同时抬到下限。 */
    @Test
    fun `appliedTo把R1默认档抬到预算下限`() {
        val config = InferenceConfig(maxTokens = 1024, thinkingTokenBudget = 0)
        val clamped = ModelSamplingProfiles.appliedTo(
            "DeepSeek-R1-Distill-Qwen-1.5B_multi-prefill-seq_q8_ekv4096.litertlm",
            config,
        )
        assertEquals(2048, clamped.maxTokens, "maxTokens 应被 minMaxTokens 抬到 2048")
        assertEquals(
            1536,
            clamped.thinkingTokenBudget,
            "thinkingTokenBudget 应被 minThinkingTokenBudget 抬到 1536（而非半预算 1024）",
        )
    }

    /** 下限语义：max() 同口径 —— 用户显式设更大预算时不被压低。 */
    @Test
    fun `appliedTo不压低用户显式更大的thinking预算`() {
        val config = InferenceConfig(maxTokens = 4096, thinkingTokenBudget = 1800)
        val clamped = ModelSamplingProfiles.appliedTo(
            "DeepSeek-R1-Distill-Qwen-1.5B_multi-prefill-seq_q8_ekv4096.litertlm",
            config,
        )
        assertEquals(1800, clamped.thinkingTokenBudget, "用户显式 1800 必须原样保留（maxOf 下限口径）")
    }

    /** ③ 负例：下限触及或超过 minMaxTokens ⇒ 构造期 IllegalArgumentException。 */
    @Test
    fun `构造期不变式拒绝触及或超过minMaxTokens的下限`() {
        assertFailsWith<IllegalArgumentException>("下限 == minMaxTokens 必须拒绝（正文零余量）") {
            profileWith(minMaxTokens = 2048, minThinkingTokenBudget = 2048)
        }
        assertFailsWith<IllegalArgumentException>("下限 > minMaxTokens 必须拒绝") {
            profileWith(minMaxTokens = 2048, minThinkingTokenBudget = 4096)
        }
    }

    /** 边界存活：下限恰为 minMaxTokens - 1（构造放行且钳制后存活）。 */
    @Test
    fun `构造期不变式接受恰为minMaxTokens减一的下限`() {
        val profile = profileWith(minMaxTokens = 2048, minThinkingTokenBudget = 2047)
        assertEquals(
            2047,
            resolveThinkingBudget(profile.minThinkingTokenBudget, profile.minMaxTokens),
            "恰为 minMaxTokens-1 的下限应构造放行且钳制后存活",
        )
    }
}
