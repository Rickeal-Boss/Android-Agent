package com.rickeal.agent.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `gemma-4-E4B-it.litertlm`（Gemma 4 E4B **单文件** CPU 变体）采样档案的固化单测
 * （W68 A2，复检 Q-2）。
 *
 * 为什么补：补档前它是 14 预设中**唯一无档案钳制**的一条 —— `contextLength` 可被拉到
 * 32768（参数滑条值）⇒ KV 超预算约 4×；且漏掉 temperatureRange 下限 0.3 的防贪心循环
 * 保护（Wave 18/19 实锤）。本测试钉三件事：
 *  ① 该文件名命中档案（`forFileName` 非 null，精确匹配口径）；
 *  ② 钳制生效（`appliedTo` 把越界 contextLength 压到 8192）；
 *  ③ 值与 gemma 家族档逐字段相同（同族同证据口径），负例钉未知文件名零行为变化。
 *
 * 纯 JVM：`appliedTo` 只碰 data class 与 AgentLogStore（core-model 内），零协程 / 零
 * Android / 零 native。安全收尾用返回 Unit 的断言（JUnit4 方法签名纪律，同
 * `ModelSamplingProfileThinkingBudgetTest` 约定）。
 */
class ModelSamplingProfileE4BTest {

    /** 本测试的对象文件名：E4B 单文件 CPU 变体（`ModelPresets.kt` 同款下载文件名）。 */
    private val e4bSingle = "gemma-4-E4B-it.litertlm"

    /** 家族档任一成员（E2B·CPU）—— 用于「同值」对照。 */
    private val e2bCpu = "gemma-4-E2B-it.litertlm"

    /** ① 命中：E4B 单文件不再是无档案的漏网之鱼。 */
    @Test
    fun `E4B单文件命中采样档案`() {
        val profile = assertProfileHit(e4bSingle)
        assertEquals(8192, profile.maxContextLength, "E4B 单文件上下文必须封顶 8192（防 32k 拉爆 KV）")
        assertEquals(1.0f, profile.recommendedTemperature, "须与 gemma 家族档同值")
        assertEquals(0.3f, profile.temperatureRange.start, "温度下限 0.3 = 防贪心循环保护（Wave 18/19）")
        assertEquals(1.5f, profile.temperatureRange.endInclusive, "须与 gemma 家族档同值")
        assertEquals(64, profile.recommendedTopK, "须与 gemma 家族档同值")
        assertEquals(128, profile.maxTopK, "须与 gemma 家族档同值")
        assertEquals(0.95f, profile.recommendedTopP, "须与 gemma 家族档同值")
    }

    /** ② 钳制生效：滑条拉到 32768 ⇒ appliedTo 压到 8192（复检 Q-2 的核心断言）。 */
    @Test
    fun `appliedTo把E4B单文件contextLength钳到8192`() {
        val config = InferenceConfig(contextLength = 32768)
        val clamped = ModelSamplingProfiles.appliedTo(e4bSingle, config)
        assertEquals(8192, clamped.contextLength, "contextLength 必须被档案钳到 8192（无钳制 = Q-2 复发）")
        assertTrue(clamped.contextLength < config.contextLength, "钳制必须真实发生（32768 → 8192）")
    }

    /** ③ 同值对照：E4B 单文件档与家族档（除 evidence 文本外）逐字段相同。 */
    @Test
    fun `E4B单文件档与家族档同值不同证据`() {
        val e4b = assertProfileHit(e4bSingle)
        val family = assertProfileHit(e2bCpu)
        assertEquals(family.recommendedTemperature, e4b.recommendedTemperature, "同族同值：推荐温度")
        assertEquals(family.temperatureRange, e4b.temperatureRange, "同族同值：温度区间")
        assertEquals(family.recommendedTopK, e4b.recommendedTopK, "同族同值：推荐 topK")
        assertEquals(family.maxTopK, e4b.maxTopK, "同族同值：topK 上限")
        assertEquals(family.recommendedTopP, e4b.recommendedTopP, "同族同值：topP 上限")
        assertEquals(family.maxContextLength, e4b.maxContextLength, "同族同值：上下文封顶")
        assertEquals(0, e4b.minMaxTokens, "E4B 单文件不设 maxTokens 下限（家族档同）")
        assertEquals(0, e4b.minThinkingTokenBudget, "E4B 单文件不设 thinking 预算下限（家族档同）")
        assertTrue(
            e4b.evidence.contains("W66") && e4b.evidence.contains("W67"),
            "E4B 单文件的 evidence 须带本仓 CPU 臂实测锚点（W66/W67），实际：${e4b.evidence}",
        )
    }

    /** 负例：档案表没有变成 catch-all —— 未知文件名零钳制（精确匹配口径不变）。 */
    @Test
    fun `未知文件名不受E4B档钳制`() {
        val config = InferenceConfig(contextLength = 32768)
        val clamped = ModelSamplingProfiles.appliedTo("name-1.litertlm", config)
        assertEquals(32768, clamped.contextLength, "未知文件名（SAF 导入 / 重名落盘）必须原样返回")
        assertNull(ModelSamplingProfiles.forFileName("name-1.litertlm"), "forFileName 对未知文件名必须返回 null")
    }

    private fun assertProfileHit(fileName: String): ModelSamplingProfile =
        requireNotNull(ModelSamplingProfiles.forFileName(fileName)) {
            "$fileName 必须命中采样档案（从严精确匹配）"
        }
}
