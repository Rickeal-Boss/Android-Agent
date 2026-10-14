package com.rickeal.agent.feature.chat

import com.rickeal.agent.core.model.InferenceBackend
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 「激活态重校验」提示的纯逻辑测试（W68 B2，第十二审 R-2/R-3）。
 *
 * 被测 = 生产路径实际调用的纯函数 [activationWarningFor] 与频控闸 [ActivationWarningGate]
 * （`ChatRunCoordinator.maybeAnnounceActivationWarning` 直接调用）。纯 JVM：入参全是
 * core-model 的纯类型，无 Android / Compose 依赖。
 *
 * 钉住四组语义：
 * 1. **判据式三值**：knownBroken 命中 / GPU 非白名单命中 / 双不命中 ⇒ 无提示；
 * 2. **文案完整性**：命中时原样携带 `knownBrokenOn` 条件词 + 「仍可继续，但可能失败」；
 * 3. **频控**：同会话二次 run 不再现（`consumeOnce`），换会话复位（`reset`）；
 * 4. **互斥**：提示是「激活态告知」形态，不携带加载前确认框的决策动词（不叠成双弹）。
 */
class ActivationWarningTest {

    // 已知坏容器（W43/W60 真机实测，与预设表登记一致）。
    private val brokenSignature =
        "0.17.1 引擎初始化 NOT_FOUND（容器缺 TF_LITE_PREFILL_DECODE section；W43 另实测输出退化）"
    private val brokenFile = "gemma-4-E2B-it-gpu.litertlm"

    @Test
    fun `已知坏命中时给出含原文条件词的提示`() {
        val notice = activationWarningFor(
            fileName = brokenFile,
            knownBrokenOn = brokenSignature,
            backend = InferenceBackend.CPU,
        )
        assertNotNull(notice)
        // 原文条件词不得丢（用户据此判断是哪一种已知失败）。
        assertContains(notice, brokenSignature)
        assertContains(notice, "仍可继续")
    }

    @Test
    fun `GPU 非白名单命中时给出提示（文件维度健康但后端不可用）`() {
        val notice = activationWarningFor(
            fileName = "gemma-4-E4B-it.litertlm",
            knownBrokenOn = null,
            backend = InferenceBackend.GPU,
        )
        assertNotNull(notice)
        assertContains(notice, "GPU")
        assertContains(notice, "仍可继续")
    }

    @Test
    fun `GPU 白名单文件不触发提示`() {
        assertNull(
            activationWarningFor(
                fileName = "MiniCPM5-2B_int4.litertlm",
                knownBrokenOn = null,
                backend = InferenceBackend.GPU,
            ),
        )
    }

    @Test
    fun `双不命中时不提示`() {
        assertNull(
            activationWarningFor(
                fileName = "gemma-4-E2B-it.litertlm",
                knownBrokenOn = null,
                backend = InferenceBackend.CPU,
            ),
        )
    }

    @Test
    fun `无激活模型时不提示`() {
        assertNull(activationWarningFor(fileName = null, knownBrokenOn = null, backend = InferenceBackend.GPU))
    }

    @Test
    fun `双命中仍为单一形态（两子句不叠成两条提示）`() {
        // E4B·GPU：既登记 knownBrokenOn，又不在 GPU 白名单。
        val notice = activationWarningFor(
            fileName = "gemma-4-E4B-it-gpu.litertlm",
            knownBrokenOn = brokenSignature,
            backend = InferenceBackend.GPU,
        )
        assertNotNull(notice)
        // 单一字符串（非列表）：双命中合并为一条，不产生第二条提示。
        assertContains(notice, brokenSignature)
    }

    @Test
    fun `频控 同会话至多一次 换会话复位`() {
        val gate = ActivationWarningGate()
        assertTrue(gate.consumeOnce(), "首次 run 应展示")
        assertFalse(gate.consumeOnce(), "同会话二次 run 不再展示")
        assertFalse(gate.consumeOnce(), "同会话后续 run 恒不再展示")
        gate.reset()
        assertTrue(gate.consumeOnce(), "换会话复位后可再展示一次")
    }

    @Test
    fun `互斥 提示形态不含加载前确认框的决策动词`() {
        val notice = activationWarningFor(
            fileName = brokenFile,
            knownBrokenOn = brokenSignature,
            backend = InferenceBackend.CPU,
        )
        assertNotNull(notice)
        // 加载前确认框（ModelCard.kt:342/:362，模型库页）以「仍要尝试」为决策动词、
        // 拦下加载；激活态提示是「告知 + run 照跑」，两者不得同框 —— 断言提示不携带
        // 该决策动词（若日后有人把两形态合并，本断言判红）。
        assertFalse(notice.contains("仍要尝试"), "激活态提示不得复用加载前确认框的决策动词")
        assertEquals(false, notice.endsWith("？"), "激活态提示是陈述式告知，不是确认问句")
    }
}
