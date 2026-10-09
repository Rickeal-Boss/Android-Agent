package com.rickeal.agent.core.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [mergeResilienceState] 的合并语义单测（W57，治 W56 审查 P2「引擎侧读回接线零 JVM 覆盖」）。
 *
 * 为什么值得写：`adoptResilienceFromStore` 把 store 快照按**单调合并**（OR / max）读回实例
 * 字段 —— 它若被删 / OR 方向被改 / max 换成 min，P2#1（证伪随 evict 清零）就会静默复发。
 * `LiteRtLmEngine` 是 native 重类、JVM 里不能实例化，故 W57 把合并语义外提为纯函数
 * [mergeResilienceState]，用本类直接钉住。纯 JVM、零 native。
 *
 * 口径申报（关键）：M1–M8 钉的是**合并语义**（`adopt = merge(实例, store.read)`、
 * `persist = store.write(实例快照)` 的数据流），**不是**引擎的**字面调用序列**
 *（那需例化引擎）。⇒ 本类钉「语义」，接线调用点存在性由架构守卫另钉，二者互补。
 */
class EngineResilienceMergeTest {

    // ─────────────────────────── OR 分支：证伪不回退 ───────────────────────────

    @Test
    fun `M1 采纳方向 实例未证伪且 store 已证伪时读回置位`() {
        val merged = mergeResilienceState(
            EngineResilienceState(nativeToolsRejected = false, templateRebuildCount = 0),
            EngineResilienceState(nativeToolsRejected = true, templateRebuildCount = 0),
        )
        assertTrue(merged.nativeToolsRejected)
    }

    @Test
    fun `M2 反向单调 实例已证伪且 store 未证伪时不拉退`() {
        val merged = mergeResilienceState(
            EngineResilienceState(nativeToolsRejected = true, templateRebuildCount = 0),
            EngineResilienceState(nativeToolsRejected = false, templateRebuildCount = 0),
        )
        assertTrue(merged.nativeToolsRejected)
    }

    // ─────────────────────────── max 分支：计数只增不减 ───────────────────────────

    @Test
    fun `M3 采纳方向 实例计数小且 store 计数大时取大`() {
        val merged = mergeResilienceState(
            EngineResilienceState(nativeToolsRejected = false, templateRebuildCount = 0),
            EngineResilienceState(nativeToolsRejected = false, templateRebuildCount = 3),
        )
        assertEquals(3, merged.templateRebuildCount)
    }

    @Test
    fun `M4 反向单调 实例计数大且 store 计数小时不回退`() {
        val merged = mergeResilienceState(
            EngineResilienceState(nativeToolsRejected = false, templateRebuildCount = 3),
            EngineResilienceState(nativeToolsRejected = false, templateRebuildCount = 1),
        )
        assertEquals(3, merged.templateRebuildCount)
    }

    // ─────────────────────────── 双字段独立 ───────────────────────────

    @Test
    fun `M5 双字段各自方向独立 互不污染`() {
        val merged = mergeResilienceState(
            EngineResilienceState(nativeToolsRejected = false, templateRebuildCount = 5),
            EngineResilienceState(nativeToolsRejected = true, templateRebuildCount = 2),
        )
        assertEquals(EngineResilienceState(nativeToolsRejected = true, templateRebuildCount = 5), merged)
    }

    // ─────────────────────────── 空 store 不拉退实例 ───────────────────────────

    @Test
    fun `M6 store 无记录时默认快照不拉退实例`() {
        // 等价于「首次 adopt 时 store.read 返回默认值」：实例已置位/有计数，读回空快照不得清零。
        val merged = mergeResilienceState(
            EngineResilienceState(nativeToolsRejected = true, templateRebuildCount = 2),
            EngineResilienceState(),
        )
        assertEquals(EngineResilienceState(nativeToolsRejected = true, templateRebuildCount = 2), merged)
    }

    // ─────────────────────────── 幂等 ───────────────────────────

    @Test
    fun `M7 重复合并同一快照结果幂等`() {
        val instance = EngineResilienceState(nativeToolsRejected = true, templateRebuildCount = 3)
        val snapshot = EngineResilienceState(nativeToolsRejected = false, templateRebuildCount = 1)
        val once = mergeResilienceState(instance, snapshot)
        val twice = mergeResilienceState(once, snapshot)
        assertEquals(once, twice)
    }

    // ─────────────────────────── 同 store 跨引擎实例（组合表达） ───────────────────────────

    @Test
    fun `M8 同 store 跨引擎实例读回证伪与计数`() {
        val store = ProcessEngineResilienceStore()
        val cid = "cid-a"

        // ── 引擎实例 A（fresh）：adopt = merge(空实例, store.read) ──
        var a = mergeResilienceState(EngineResilienceState(), store.read(cid))
        assertFalse(a.nativeToolsRejected)
        assertEquals(0, a.templateRebuildCount)

        // ── 引擎 A 模板失败：handleTemplateRenderFailure 里 count++ / 证伪，然后 persist ──
        //    persistResilienceToStore 的语义 = store.write(cid, 实例快照)。
        a = EngineResilienceState(
            nativeToolsRejected = true,
            templateRebuildCount = a.templateRebuildCount + 1,
        )
        store.write(cid, a)

        // ── 引擎 A 被 evict；引擎实例 B（fresh，实例字段 = 默认）adopt ──
        val b = mergeResilienceState(EngineResilienceState(), store.read(cid))
        assertTrue(b.nativeToolsRejected)      // 证伪跨实例存活（P2#1 的核心）
        assertEquals(1, b.templateRebuildCount) // 计数跨实例存活（软熔断数据面）
    }
}
