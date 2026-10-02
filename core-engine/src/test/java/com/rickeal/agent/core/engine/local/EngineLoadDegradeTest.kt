package com.rickeal.agent.core.engine.local

import com.rickeal.agent.core.engine.EngineSessionDiagnostics
import com.rickeal.agent.core.model.InferenceBackend
import com.rickeal.agent.core.model.ModelModality
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [EngineLoadDegrade] 的 JVM 纯逻辑单测（Wave 44 P0-2）。
 *
 * 降级决策抽成纯函数（无 native、无状态）正是为了这里可离线覆盖全部分支：
 * NOT_FOUND 触发模态降级、INTERNAL 只走 GPU 二段、文本级 NOT_FOUND 直接终态、上限 4 等。
 *
 * ⚠️ JUnit4 纪律：所有 `@Test` 方法以返回 **void** 的断言收尾。
 */
class EngineLoadDegradeTest {

    private fun notFound() = RuntimeException("NOT_FOUND: section AUDIO_ENCODER_HW not found")
    private fun internalError() = RuntimeException("INTERNAL: dlopen failed for libOpenCL.so")

    /** 命名视觉缺失（Wave 45 会话路径：真机消息含 section 名）。 */
    private fun notFoundVision() = RuntimeException("NOT_FOUND: VISION_ENCODER not found in the model")

    /** NOT_FOUND 但**不含** section 名（Wave 45 会话路径的盲降兜底入口）。 */
    private fun notFoundUnnamed() = RuntimeException("NOT_FOUND: text decoder missing")

    /**
     * 复刻 `ensureConversationWithDegrade` 的驱动循环（Wave 45，不含 native 重建）：每轮问
     * [EngineLoadDegrade.modalityToDegradeOnSessionError] 是否要去模态，是则计一次重建并
     * **模拟重建后状态**（degraded 累积、current 减去该模态），否则终止。
     *
     * @param signals 注入的「第 i 次建会话失败」错误；null / 越界 = 成功（终止）。
     * @return 实际发生的重建次数。
     */
    private fun simulateSessionDegrade(
        initialModalities: Set<ModelModality>,
        signals: List<Throwable?>,
    ): Int {
        var currentModalities = initialModalities
        var degraded = emptySet<ModelModality>()
        var reloads = 0
        var index = 0
        while (true) {
            val error = signals.getOrNull(index) ?: return reloads
            index++
            val degrade = EngineLoadDegrade.modalityToDegradeOnSessionError(
                error = error,
                currentModalities = currentModalities,
                degradedModality = degraded,
            ) ?: return reloads
            if (reloads >= EngineLoadDegrade.MAX_SESSION_DEGRADE_ATTEMPTS) return reloads
            reloads++
            degraded = degraded + degrade
            currentModalities = currentModalities - degrade
        }
    }

    /** 复刻 `load()` 的驱动循环（含 visited 去重 + 上限）：`errors[i]` = 第 i 次尝试的失败，null = 成功。 */
    private fun simulate(
        initial: EngineAttempt,
        gpuInvolved: Boolean,
        errors: List<Throwable?>,
    ): List<EngineAttempt> {
        var current = initial
        val visited = mutableSetOf(current)
        val seq = mutableListOf(current)
        var attemptIndex = 0
        while (true) {
            val error = errors.getOrNull(attemptIndex) ?: return seq
            val next = EngineLoadDegrade.next(current, error, gpuInvolved)
            attemptIndex++
            if (next == null ||
                attemptIndex >= EngineLoadDegrade.MAX_LOAD_ATTEMPTS ||
                !visited.add(next)
            ) {
                return seq
            }
            current = next
            seq += current
        }
    }

    // ───────────────────────────── NOT_FOUND 判定 ─────────────────────────────

    @Test
    fun `NOT_FOUND判定只认确定性缺section`() {
        assertTrue(EngineLoadDegrade.isDeterministicMissingSection(notFound()))
        assertFalse(EngineLoadDegrade.isDeterministicMissingSection(internalError()))
        assertFalse(EngineLoadDegrade.isDeterministicMissingSection(RuntimeException("timeout")))
        assertFalse(EngineLoadDegrade.isDeterministicMissingSection(RuntimeException()))
    }

    // ───────────────────────────── 去模态（先音频后视觉）─────────────────────────────

    @Test
    fun `去模态先音频后视觉`() {
        val a = EngineLoadDegrade.initial(InferenceBackend.CPU, InferenceBackend.CPU, InferenceBackend.CPU)
        val afterAudio = EngineLoadDegrade.dropOneModality(a)
        assertEquals(
            EngineAttempt(InferenceBackend.CPU, InferenceBackend.CPU, null, setOf(ModelModality.AUDIO)),
            afterAudio,
        )
        val afterVision = EngineLoadDegrade.dropOneModality(afterAudio!!)
        assertEquals(
            EngineAttempt(InferenceBackend.CPU, null, null, setOf(ModelModality.AUDIO, ModelModality.VISION)),
            afterVision,
        )
        assertNull(EngineLoadDegrade.dropOneModality(afterVision!!))
    }

    @Test
    fun `每模态最多降一次`() {
        val exhausted = EngineAttempt(
            InferenceBackend.CPU,
            null,
            null,
            setOf(ModelModality.AUDIO, ModelModality.VISION),
        )
        assertNull(EngineLoadDegrade.dropOneModality(exhausted))
    }

    // ───────────────────────────── next() 分支 ─────────────────────────────

    @Test
    fun `INTERNAL不触发模态降级走GPU二段`() {
        val a = EngineLoadDegrade.initial(InferenceBackend.GPU, InferenceBackend.GPU, InferenceBackend.CPU)
        val next = EngineLoadDegrade.next(a, internalError(), gpuInvolved = true)
        // 保持模态：视觉跟随主后端落 CPU；audio 与主后端正交，保持 CPU。
        assertEquals(
            EngineAttempt(InferenceBackend.CPU, InferenceBackend.CPU, InferenceBackend.CPU, emptySet()),
            next,
        )
    }

    @Test
    fun `非GPU请求INTERNAL直接终态`() {
        val a = EngineLoadDegrade.initial(InferenceBackend.CPU, InferenceBackend.CPU, InferenceBackend.CPU)
        assertNull(EngineLoadDegrade.next(a, internalError(), gpuInvolved = false))
    }

    @Test
    fun `文本级NOT_FOUND无模态可降直接终态`() {
        val a = EngineLoadDegrade.initial(InferenceBackend.CPU, null, null)
        assertNull(EngineLoadDegrade.next(a, notFound(), gpuInvolved = false))
    }

    @Test
    fun `GPU请求文本级NOT_FOUND也不降后端`() {
        val a = EngineAttempt(
            InferenceBackend.GPU,
            null,
            null,
            setOf(ModelModality.AUDIO, ModelModality.VISION),
        )
        assertNull(EngineLoadDegrade.next(a, notFound(), gpuInvolved = true))
    }

    @Test
    fun `视觉后端GPU跟随主后端落CPU`() {
        val a = EngineLoadDegrade.initial(InferenceBackend.CPU, InferenceBackend.GPU, null)
        val next = EngineLoadDegrade.next(a, internalError(), gpuInvolved = true)
        assertEquals(
            EngineAttempt(InferenceBackend.CPU, InferenceBackend.CPU, null, emptySet()),
            next,
        )
    }

    // ───────────────────────────── 降级序列 ─────────────────────────────

    @Test
    fun `降级序列CPU去音频`() {
        val seq = simulate(
            EngineLoadDegrade.initial(InferenceBackend.CPU, null, InferenceBackend.CPU),
            gpuInvolved = false,
            errors = listOf(notFound(), null),
        )
        assertEquals(2, seq.size)
        assertEquals(setOf(ModelModality.AUDIO), seq.last().degraded)
        assertEquals(InferenceBackend.CPU, seq.last().backend)
    }

    @Test
    fun `降级序列GPU全链去音频视觉并落CPU`() {
        val seq = simulate(
            EngineLoadDegrade.initial(InferenceBackend.GPU, InferenceBackend.GPU, InferenceBackend.CPU),
            gpuInvolved = true,
            errors = listOf(notFound(), notFound(), internalError(), null),
        )
        assertEquals(
            listOf(
                EngineAttempt(InferenceBackend.GPU, InferenceBackend.GPU, InferenceBackend.CPU, emptySet()),
                EngineAttempt(InferenceBackend.GPU, InferenceBackend.GPU, null, setOf(ModelModality.AUDIO)),
                EngineAttempt(
                    InferenceBackend.GPU,
                    null,
                    null,
                    setOf(ModelModality.AUDIO, ModelModality.VISION),
                ),
                EngineAttempt(
                    InferenceBackend.CPU,
                    null,
                    null,
                    setOf(ModelModality.AUDIO, ModelModality.VISION),
                ),
            ),
            seq,
        )
    }

    // ───────────────────────────── 回归 ─────────────────────────────

    @Test
    fun `回归好容器attempts序列单段`() {
        val initial = EngineLoadDegrade.initial(
            InferenceBackend.CPU,
            InferenceBackend.CPU,
            InferenceBackend.CPU,
        )
        val seq = simulate(initial, gpuInvolved = false, errors = listOf(null))
        assertEquals(1, seq.size)
        assertEquals(initial, seq.single())
    }

    @Test
    fun `回归GPU二段降级序列与旧实现等价`() {
        val seq = simulate(
            EngineLoadDegrade.initial(InferenceBackend.GPU, InferenceBackend.GPU, InferenceBackend.CPU),
            gpuInvolved = true,
            errors = listOf(internalError(), null),
        )
        // 旧实现：[(GPU, GPUvision), (CPU, CPUvision)]，audio 在元组外恒取原值（CPU）。
        assertEquals(2, seq.size)
        assertEquals(
            EngineAttempt(InferenceBackend.GPU, InferenceBackend.GPU, InferenceBackend.CPU),
            seq[0],
        )
        assertEquals(
            EngineAttempt(InferenceBackend.CPU, InferenceBackend.CPU, InferenceBackend.CPU),
            seq[1],
        )
    }

    @Test
    fun `回归hadGpuAttempt门控与请求GPU等价`() {
        assertTrue(EngineLoadDegrade.gpuInvolved(InferenceBackend.GPU, InferenceBackend.GPU))
        assertTrue(EngineLoadDegrade.gpuInvolved(InferenceBackend.CPU, InferenceBackend.GPU))
        assertFalse(EngineLoadDegrade.gpuInvolved(InferenceBackend.CPU, InferenceBackend.CPU))
        assertFalse(EngineLoadDegrade.gpuInvolved(InferenceBackend.CPU, null))
    }

    @Test
    fun `上限四次且不超`() {
        assertEquals(4, EngineLoadDegrade.MAX_LOAD_ATTEMPTS)
        val seq = simulate(
            EngineLoadDegrade.initial(InferenceBackend.GPU, InferenceBackend.GPU, InferenceBackend.CPU),
            gpuInvolved = true,
            errors = listOf(notFound(), notFound(), internalError(), internalError(), internalError()),
        )
        assertTrue(seq.size <= EngineLoadDegrade.MAX_LOAD_ATTEMPTS)
        assertEquals(4, seq.size)
    }

    @Test
    fun `诊断默认空集旧消费方零变化`() {
        assertEquals(emptySet<ModelModality>(), EngineSessionDiagnostics().degradedModality)
    }

    // ─────────────── Wave 45：会话创建路径降级（missingModality，纯函数）───────────────

    @Test
    fun `会话错误解析缺失模态`() {
        assertEquals(ModelModality.AUDIO, EngineLoadDegrade.missingModality(notFound()))
        assertEquals(ModelModality.VISION, EngineLoadDegrade.missingModality(notFoundVision()))
        assertNull(
            EngineLoadDegrade.missingModality(RuntimeException("INVALID_ARGUMENT: Unsupported model type")),
        )
        assertNull(EngineLoadDegrade.missingModality(notFoundUnnamed()))
        assertNull(EngineLoadDegrade.missingModality(RuntimeException()))
    }

    // ─────── Wave 45：modalityToDegradeOnSessionError（纯函数，核心决策）───────

    @Test
    fun `会话降级命名且可用去命名模态`() {
        assertEquals(
            ModelModality.AUDIO,
            EngineLoadDegrade.modalityToDegradeOnSessionError(
                error = notFound(),
                currentModalities = setOf(ModelModality.VISION, ModelModality.AUDIO),
                degradedModality = emptySet(),
            ),
        )
    }

    @Test
    fun `会话降级命名但已降过不重复`() {
        assertNull(
            EngineLoadDegrade.modalityToDegradeOnSessionError(
                error = notFound(),
                currentModalities = setOf(ModelModality.VISION, ModelModality.AUDIO),
                degradedModality = setOf(ModelModality.AUDIO),
            ),
        )
    }

    @Test
    fun `会话降级命名但未启用不触发`() {
        assertNull(
            EngineLoadDegrade.modalityToDegradeOnSessionError(
                error = notFound(),
                currentModalities = setOf(ModelModality.VISION),
                degradedModality = emptySet(),
            ),
        )
    }

    @Test
    fun `会话降级未命名盲降优先音频`() {
        assertEquals(
            ModelModality.AUDIO,
            EngineLoadDegrade.modalityToDegradeOnSessionError(
                error = notFoundUnnamed(),
                currentModalities = setOf(ModelModality.VISION, ModelModality.AUDIO),
                degradedModality = emptySet(),
            ),
        )
    }

    @Test
    fun `会话降级未命名仅视觉可用降视觉`() {
        assertEquals(
            ModelModality.VISION,
            EngineLoadDegrade.modalityToDegradeOnSessionError(
                error = notFoundUnnamed(),
                currentModalities = setOf(ModelModality.VISION),
                degradedModality = emptySet(),
            ),
        )
    }

    @Test
    fun `会话降级无可降模态不触发`() {
        assertNull(
            EngineLoadDegrade.modalityToDegradeOnSessionError(
                error = notFoundUnnamed(),
                currentModalities = setOf(ModelModality.VISION, ModelModality.AUDIO),
                degradedModality = setOf(ModelModality.VISION, ModelModality.AUDIO),
            ),
        )
    }

    @Test
    fun `会话降级非NOT_FOUND不触发`() {
        assertNull(
            EngineLoadDegrade.modalityToDegradeOnSessionError(
                error = RuntimeException("INVALID_ARGUMENT: Unsupported model type"),
                currentModalities = setOf(ModelModality.VISION, ModelModality.AUDIO),
                degradedModality = emptySet(),
            ),
        )
    }

    // ───────────────────── Wave 45：会话降级上限与幂等 ─────────────────────

    @Test
    fun `会话降级上限为模态数`() {
        assertEquals(2, EngineLoadDegrade.MAX_SESSION_DEGRADE_ATTEMPTS)
    }

    @Test
    fun `会话降级重试循环每模态一次后停止`() {
        // 连续「命名 AUDIO」→「命名 VISION」两个信号 ⇒ 重建恰 2 次后成功（第三个信号 = 成功）。
        val reloads = simulateSessionDegrade(
            initialModalities = setOf(ModelModality.AUDIO, ModelModality.VISION),
            signals = listOf(notFound(), notFoundVision(), null),
        )
        assertEquals(2, reloads)
    }

    @Test
    fun `会话降级幂等失效被上限截断`() {
        // 人为让「重建后状态不收敛」（current 不缩小、degraded 不累积）模拟幂等失效：
        // 每轮都判定可去 AUDIO，但重试上限 MAX_SESSION_DEGRADE_ATTEMPTS 必须截断，不死循环。
        val current = setOf(ModelModality.AUDIO, ModelModality.VISION)
        val degraded = emptySet<ModelModality>()
        var reloads = 0
        var index = 0
        while (true) {
            val error = List(5) { notFoundUnnamed() }.getOrNull(index) ?: break
            index++
            if (EngineLoadDegrade.modalityToDegradeOnSessionError(error, current, degraded) == null) break
            if (reloads >= EngineLoadDegrade.MAX_SESSION_DEGRADE_ATTEMPTS) break
            reloads++
        }
        assertEquals(EngineLoadDegrade.MAX_SESSION_DEGRADE_ATTEMPTS, reloads)
    }

    // ───────────────────── Wave 45：initial 默认参回归 ─────────────────────

    @Test
    fun `initial默认参回归与旧行为一致`() {
        // 不传 degraded（Wave 44 逐字调用）⇒ degraded 空集。
        assertEquals(
            EngineAttempt(
                InferenceBackend.CPU,
                InferenceBackend.CPU,
                InferenceBackend.CPU,
                emptySet(),
            ),
            EngineLoadDegrade.initial(InferenceBackend.CPU, InferenceBackend.CPU, InferenceBackend.CPU),
        )
        // 传 degraded（Wave 45 会话期重建的 seed）⇒ 原样记录。
        assertEquals(
            EngineAttempt(InferenceBackend.CPU, null, InferenceBackend.CPU, setOf(ModelModality.AUDIO)),
            EngineLoadDegrade.initial(
                InferenceBackend.CPU,
                null,
                InferenceBackend.CPU,
                degraded = setOf(ModelModality.AUDIO),
            ),
        )
    }
}
