package com.rickeal.agent.feature.models

import com.rickeal.agent.core.model.InferenceBackend
import com.rickeal.agent.core.model.ModelDescriptor
import com.rickeal.agent.core.model.ModelFamily
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [formatBytes] / [sizeHint] / [estimateRequiredRamBytes] / [ModelsUiState.refiltered]
 * 的 JVM 纯函数单测（Wave 32 · 流 B：为 :feature-models 点亮测试源集）。
 *
 * 这四个函数是模型库里**唯一不依赖 Android / Compose / ViewModel 运行时**的判据，
 * 却各自守着一个真机才看得见代价的错误方向：
 *  1. [estimateRequiredRamBytes]：写低 = 内存闸门放行 → native OOM **闪退**（Kotlin 层
 *     抓不到）；写高 = 「我的手机不行」的误拦。
 *  2. [sizeHint]：判据写严 = 「下完 2~4GB 被判不完整 → 删 → 再下再删」的死循环
 *     （历史上真实发生过，见函数 KDoc）。
 *  3. [formatBytes]：量纲串档会把「需要 5.6 GB」印成「5632 MB」。
 *  4. [refiltered]：搜索 / 分类筛选的唯一实现；家族 chip 顺序漂移会让用户记错位置。
 *
 * 刻意**不构造** [ModelsViewModel]：它依赖 AndroidX ViewModel / 协程 / DownloadManager，
 * JVM 上不可测，也不在本波范围内。这里只测被它调用的文件级纯函数。
 */
class ModelsPureLogicTest {

    // ── formatBytes：三档量纲 + 档位边界 ────────────────────────────────────

    @Test
    fun formatBytesCoversKbMbGbTiers() {
        // 999_999 B = 976.5625 KB（本函数按二进制 1024 进位）→ %.0f 四舍五入 977
        assertEquals("977 KB", formatBytes(999_999L))
        assertEquals("512 MB", formatBytes(512L * 1024 * 1024))
        assertEquals("2.0 GB", formatBytes(2L * 1024 * 1024 * 1024))
        assertEquals("1.5 GB", formatBytes(1536L * 1024 * 1024))
    }

    @Test
    fun formatBytesTierBoundariesAreInclusive() {
        // 恰好 1 GiB：进 GB 档（>= 判据），印 1.0 而不是 1024 MB
        assertEquals("1.0 GB", formatBytes(1_073_741_824L))
        // 恰好 1 MiB：进 MB 档（>= 判据），印 1 而不是 1024 KB
        assertEquals("1 MB", formatBytes(1_048_576L))
        // 1 MiB - 1：仍是 KB 档
        assertEquals("1024 KB", formatBytes(1_048_575L))
    }

    // ── sizeHint：判据三件套（0.70 截断 / ±2% 偏离 / DM 账本）──────────────

    private fun presetOf(expectedBytes: Long) = ModelPreset(
        label = "t", url = "https://example.invalid/t.bin", sizeText = "", ramText = "",
        requiredRamBytes = 0L, sizeBytes = expectedBytes, memBasis = "", note = "",
    )

    @Test
    fun sizeHintReturnsNullWithoutPresetOrNonPositiveSizes() {
        val descriptor = ModelDescriptor(sizeBytes = 500L)
        assertNull(sizeHint(descriptor, preset = null, downloadedBytes = 0L, totalBytes = 0L))
        // 预设给了 0（未填）⇒ 没有可比基准，宁可不提示也不误报
        assertNull(sizeHint(descriptor, presetOf(0L), downloadedBytes = 0L, totalBytes = 0L))
        // 实际体积 0（登记前）⇒ 同样不比
        assertNull(sizeHint(ModelDescriptor(sizeBytes = 0L), presetOf(1000L), 0L, 0L))
    }

    @Test
    fun sizeHintWarnsTruncationWhenBelowSeventyPercent() {
        // 600 < 1000 × 0.70 ⇒ 截断警告（与 DM 账本无关：截断是更强的信号）
        val hint = sizeHint(
            ModelDescriptor(sizeBytes = 600L), presetOf(1000L),
            downloadedBytes = 0L, totalBytes = 0L,
        )
        assertTrue(hint != null && hint.contains("明显小于预期"), "实际输出：$hint")
        assertTrue(hint!!.contains("重新下载"))
    }

    @Test
    fun sizeHintOnlyHintsUpstreamChangeWhenDownloadManagerSaysComplete() {
        val descriptor = ModelDescriptor(sizeBytes = 950L) // 950 ∈ [0.70, 0.98) × 1000
        val preset = presetOf(1000L)
        // DM 说下完了 ⇒ 提示「上游可能换过文件」
        val complete = sizeHint(descriptor, preset, downloadedBytes = 1000L, totalBytes = 1000L)
        assertTrue(complete != null && complete.contains("上游仓库可能换过文件"), "实际输出：$complete")
        // DM 没说下完（拿不到总数）⇒ ±2% 内的小偏差不足以下结论 ⇒ 不提示
        assertNull(sizeHint(descriptor, preset, downloadedBytes = 950L, totalBytes = 0L))
    }

    @Test
    fun sizeHintStaysSilentWithinTolerance() {
        // 恰好在 ±2% 内（980 ≤ x ≤ 1020）且不触 0.70 截断线 ⇒ null
        val descriptor = ModelDescriptor(sizeBytes = 990L)
        assertNull(sizeHint(descriptor, presetOf(1000L), downloadedBytes = 1000L, totalBytes = 1000L))
        // 完全一致 ⇒ null
        assertNull(sizeHint(ModelDescriptor(sizeBytes = 1000L), presetOf(1000L), 1000L, 1000L))
    }

    // ── estimateRequiredRamBytes：方向性 + 下限 + 尾部余量 ─────────────────

    @Test
    fun estimateNeverGoesBelowTheFixedFloor() {
        // 0 权重：raw = (0 + 200MB) × 1.25 = 250MB << 2GiB ⇒ 下限兜底
        assertEquals(2L * 1024 * 1024 * 1024, estimateRequiredRamBytes(0L, InferenceBackend.CPU))
        // 1 GiB 权重（CPU 1.05）：raw ≈ 1.56 GiB，仍被 2 GiB 下限兜住
        assertEquals(2L * 1024 * 1024 * 1024, estimateRequiredRamBytes(1L shl 30, InferenceBackend.CPU))
    }

    @Test
    fun estimateGpuDemandIsStrictlyAboveCpu() {
        // 4 GiB 权重：raw 已远超下限，两支差异只来自 backend 系数（1.25 vs 1.05）
        val weights = 4L shl 30
        val cpu = estimateRequiredRamBytes(weights, InferenceBackend.CPU)
        val gpu = estimateRequiredRamBytes(weights, InferenceBackend.GPU)
        assertTrue(gpu > cpu, "GPU($gpu) 必须高于 CPU($cpu)：低估的方向是 native OOM 闪退")
    }

    @Test
    fun estimateNpuIsConservativePlaceholderEqualToGpu() {
        // NPU 无实测数据 ⇒ 刻意取「不低于 GPU」的保守占位（见函数内注释），
        // 这里钉住它不许被「优化」回 1.15 那个危险方向
        val weights = 4L shl 30
        assertEquals(
            estimateRequiredRamBytes(weights, InferenceBackend.GPU),
            estimateRequiredRamBytes(weights, InferenceBackend.NPU),
        )
    }

    @Test
    fun estimateHonoursCallerSuppliedTail() {
        // 同权重下放大 tail ⇒ 估算单调不降（KV 补偿支路靠它抬闸门）
        val weights = 4L shl 30
        val base = estimateRequiredRamBytes(weights, InferenceBackend.CPU, tail = 1.25)
        val compensated = estimateRequiredRamBytes(weights, InferenceBackend.CPU, tail = 1.41)
        assertTrue(compensated > base, "tail=1.41($compensated) 必须高于 tail=1.25($base)")
    }

    // ── refiltered：家族声明顺序 + 大小写不敏感搜索 + 分类过滤 ──────────────

    private fun modelOf(
        family: ModelFamily,
        displayName: String,
        fileName: String = "model.litertlm",
    ) = ModelDescriptor(family = family, displayName = displayName, fileName = fileName)

    @Test
    fun refilteredKeepsDeclarationOrderAndShowsAllWithoutFilters() {
        // 故意乱序插入：families 必须仍按 ModelFamily 声明顺序（QWEN_3 < PHI 在枚举里靠前）
        val state = ModelsUiState(
            models = listOf(
                modelOf(ModelFamily.PHI, "Phi mini"),
                modelOf(ModelFamily.QWEN_3, "Qwen 3"),
            ),
        )
        val out = state.refiltered()
        assertEquals(listOf(ModelFamily.QWEN_3, ModelFamily.PHI), out.families)
        assertEquals(2, out.visibleModels.size)
    }

    @Test
    fun refilteredMatchesKeywordCaseInsensitivelyOnDisplayNameAndFileName() {
        val state = ModelsUiState(
            models = listOf(
                modelOf(ModelFamily.GEMMA_3N, "Gemma 3n E2B", fileName = "a.litertlm"),
                modelOf(ModelFamily.OTHER, "本地微调", fileName = "Gemma-Tuned.litertlm"),
                modelOf(ModelFamily.LLAMA, "Llama", fileName = "b.litertlm"),
            ),
            query = "gemma",
        )
        val out = state.refiltered()
        // displayName 命中第 1 条（大小写不敏感），fileName 命中第 2 条；第 3 条两处都不含
        assertEquals(2, out.visibleModels.size)
        assertTrue(out.visibleModels.any { it.fileName == "Gemma-Tuned.litertlm" })
    }

    @Test
    fun refilteredCombinesFamilyFilterWithKeyword() {
        val models = listOf(
            modelOf(ModelFamily.GEMMA_4, "Gemma 4b"),
            modelOf(ModelFamily.GEMMA_4, "Gemma 4n"),
            modelOf(ModelFamily.QWEN_3, "Qwen 3 4b"),
        )
        // 家族 = GEMMA_4 + 关键词 4n ⇒ 只剩中间那条
        val out = ModelsUiState(models = models, query = "4N", family = ModelFamily.GEMMA_4).refiltered()
        assertEquals(1, out.visibleModels.size)
        assertEquals("Gemma 4n", out.visibleModels.single().displayName)
        // families 仍反映**全集**（筛选不改 chip 集合 —— chips 是从 models 全集算的）
        assertEquals(listOf(ModelFamily.GEMMA_4, ModelFamily.QWEN_3), out.families)
    }
}
