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
 *
 * 另钉两组回归（W68 B2-R1，严质衡 B2 复审）：
 * 5. **空文件名防御**：`fileName == ""` 不得因「∉ 白名单」恒真而误报（第二子句按
 *    `isNullOrEmpty` 判空）；
 * 6. **新会话保活**：新会话整体重建 [ChatUiState] 时必须保留 `activeModelKnownBroken`
 *    （否则派生流不重发 ⇒ 提示静默失效），且清掉上一次的 `activeModelWarning`。
 *
 * 另钉一组（W69 C2）：GPU 子句改用引擎侧组合子 `gpuInvolved(backend, visionBackend)`
 * 后，主后端 CPU 而视觉后端 GPU 仍命中（同源），主/视觉后端均 CPU 时不命中。
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
            visionBackend = null,
        )
        assertNotNull(notice)
        // 原文条件词不得丢（用户据此判断是哪一种已知失败）。
        assertContains(notice, brokenSignature)
        assertContains(notice, "仍可继续")
        // C6（clause-aware）：knownBroken-only（后端非 GPU）没有「被自动回落 CPU」语义
        // （那是引擎保命道 GPU→CPU 兜底）⇒ 该文案不得出现（防尾句退回三子句共享）。
        assertFalse(notice.contains("回落 CPU"))
    }

    @Test
    fun `GPU 非白名单命中时给出提示（文件维度健康但后端不可用）`() {
        val notice = activationWarningFor(
            fileName = "gemma-4-E4B-it.litertlm",
            knownBrokenOn = null,
            backend = InferenceBackend.GPU,
            visionBackend = null,
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
                visionBackend = null,
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
                visionBackend = null,
            ),
        )
    }

    @Test
    fun `无激活模型时不提示`() {
        assertNull(
            activationWarningFor(
                fileName = null,
                knownBrokenOn = null,
                backend = InferenceBackend.GPU,
                visionBackend = null,
            ),
        )
    }

    @Test
    fun `双命中仍为单一形态（两子句不叠成两条提示）`() {
        // E4B·GPU：既登记 knownBrokenOn，又不在 GPU 白名单。
        val notice = activationWarningFor(
            fileName = "gemma-4-E4B-it-gpu.litertlm",
            knownBrokenOn = brokenSignature,
            backend = InferenceBackend.GPU,
            visionBackend = null,
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
            visionBackend = null,
        )
        assertNotNull(notice)
        // 加载前确认框（ModelCard.kt:342/:362，模型库页）以「仍要尝试」为决策动词、
        // 拦下加载；激活态提示是「告知 + run 照跑」，两者不得同框 —— 断言提示不携带
        // 该决策动词（若日后有人把两形态合并，本断言判红）。
        assertFalse(notice.contains("仍要尝试"), "激活态提示不得复用加载前确认框的决策动词")
        assertEquals(false, notice.endsWith("？"), "激活态提示是陈述式告知，不是确认问句")
    }

    @Test
    fun `空文件名不误报（防御式判空，B2-R1）`() {
        // 空串 `""` 满足 `∉ gpuVerifiedFileNames`（恒真）⇒ 若第二子句只判 `!= null`，
        // GPU 后端 + 空文件名会误报「未列入 GPU 白名单」。空文件名 = 无有效判定键 ⇒ 不提示。
        assertNull(
            activationWarningFor(
                fileName = "",
                knownBrokenOn = null,
                backend = InferenceBackend.GPU,
                visionBackend = null,
            ),
            "空文件名不得触发 GPU 白名单提示",
        )
    }

    @Test
    fun `新会话重建状态时保留已知坏签名且清掉旧提示（B2-R1 回归）`() {
        // 严质衡 B2 复审 Finding 1：onNewConversation 整体重建 ChatUiState，若丢掉
        // activeModelKnownBroken，则 E2B·GPU 这类「已知坏 ∧ GPU 白名单内」模型在新会话
        // run 里两子句双 false ⇒ 静默不再提示，抵消 gate.reset()。本断言钉住重建后字段存活。
        val kept = ChatUiState(
            activeModelKnownBroken = brokenSignature,
            activeModelWarning = "上一会话的旧提示",
        ).forNewConversation()
        assertEquals(
            brokenSignature,
            kept.activeModelKnownBroken,
            "新会话必须保留已知坏签名（否则派生流不重发 ⇒ 提示静默失效）",
        )
        assertNull(kept.activeModelWarning, "新会话必须清掉上一次的提示卡")
    }

    @Test
    fun `主后端 CPU 而视觉后端 GPU 且非白名单时命中（W69 C2）`() {
        // C2：GPU 子句改用引擎侧组合子 gpuInvolved(backend, visionBackend)
        // （= backend == GPU || visionBackend == GPU）。主后端 CPU、视觉后端 GPU、
        // 文件非白名单 ⇒ 仍应命中（与引擎「GPU 文案门控」同源）。
        val notice = activationWarningFor(
            fileName = "gemma-4-E4B-it.litertlm",
            knownBrokenOn = null,
            backend = InferenceBackend.CPU,
            visionBackend = InferenceBackend.GPU,
        )
        assertNotNull(notice)
        assertContains(notice, "GPU")
        assertContains(notice, "仍可继续")
        // C6（clause-aware）：GPU 相关子句须带「被自动回落 CPU」（引擎保命道存在性告知）。
        assertContains(notice, "被自动回落 CPU")
    }

    @Test
    fun `主后端与视觉后端均为 CPU 且非白名单时不命中（W69 C2）`() {
        // C2 负例：backend=CPU ∧ visionBackend=CPU ⇒ gpuInvolved=false ⇒
        // 即便文件非白名单也不得拼 GPU 文案（不误报）。
        assertNull(
            activationWarningFor(
                fileName = "gemma-4-E4B-it.litertlm",
                knownBrokenOn = null,
                backend = InferenceBackend.CPU,
                visionBackend = InferenceBackend.CPU,
            ),
        )
    }
}
