package com.rickeal.agent.core.engine.local

import com.rickeal.agent.core.engine.CONCURRENT_GENERATION_BUSY_MESSAGE
import com.rickeal.agent.core.engine.EngineException
import com.rickeal.agent.core.engine.isConcurrentGenerationBusy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 在途生成互斥闸门的纯函数单测（W84-02）。
 *
 * 为什么值得写：[concurrentGenerationRejection] 是「同一引擎实例只能有一条流在跑」的
 * **唯一判据**，而它守的两条不变量都是「错了会静默烂掉」的类型 ——
 *  - 漏判（active>0 却返回 null）⇒ 两条流并发打同一个 native Conversation，输出串台、
 *    会话水印错乱，且没有任何异常可查；
 *  - 误判（active==0 却返回非 null）⇒ 每次生成都被自己拒掉，App 完全不能用。
 *
 * 纯函数、零 native：不构造引擎（`LiteRtLmEngine` 依赖 litertlm native，JVM 不可实例化），
 * 只验证判据语义本身。
 *
 * ⚠️ 覆盖边界（如实申报）：本测试钉**判据语义**，钉不住**抛点位置** —— 「必须在
 * `activeGenerations.incrementAndGet()` 之前、且不进 `generationCleanup` 的 try/finally」
 * 这条不变量（漏减 ⇒ 永久 busy，W50 真机前科）需要可实例化的引擎 + 真实流生命周期，
 * JVM 单测无法覆盖，只由 `concurrentGenerationRejection` 的 KDoc 与守卫链承担。
 */
class ConcurrentGenerationGateTest {

    @Test
    fun `空闲时可开始_0 返回 null`() {
        assertNull(concurrentGenerationRejection(0), "active=0 表示没有在途生成，闸门必须放行")
    }

    @Test
    fun `单条在途即拒斥_1 返回用户可读文案`() {
        val message = concurrentGenerationRejection(1)
        assertNotNull(message, "active=1 必须拒斥：native 侧只有一个 Conversation")
        assertEquals(CONCURRENT_GENERATION_BUSY_MESSAGE, message, "文案必须取自契约层单源常量")
        assertTrue(message.isNotBlank(), "拒斥文案是用户可见的，不得为空")
    }

    @Test
    fun `多条在途同样拒斥_3 返回同一文案`() {
        val message = concurrentGenerationRejection(3)
        assertNotNull(message, "active>1 同样必须拒斥（不能因为「已经乱了」就放行）")
        assertEquals(CONCURRENT_GENERATION_BUSY_MESSAGE, message, "文案与 active=1 时逐字一致")
    }

    // ── isConcurrentGenerationBusy：上层分类的判据 ───────────────────────

    @Test
    fun `闸门异常被识别为在途互斥`() {
        assertTrue(isConcurrentGenerationBusy(EngineException(CONCURRENT_GENERATION_BUSY_MESSAGE)))
    }

    @Test
    fun `load 与 unload 的等待超时不算在途互斥`() {
        // 这两句是 load()/unload() 等不到在途生成归零时的**真故障**文案（要重试 / 要重建），
        // 与闸门拒斥语义不同：探针若把它归类成 ENGINE_BUSY 会掩盖真正的卡死。
        assertFalse(isConcurrentGenerationBusy(EngineException("LiteRT-LM：上一次生成仍在继续，请稍候重试")))
        assertFalse(isConcurrentGenerationBusy(EngineException("LiteRT-LM: 引擎未加载，请先 load()")))
    }

    @Test
    fun `非 EngineException 一律不算在途互斥`() {
        // 只有引擎自己抛的拒斥才走「跳过自检 / 不重建」的分流；其它异常（含 null message
        // 的 EngineException）必须落回常规失败路径，不能借道闸门被静默降级。
        assertFalse(isConcurrentGenerationBusy(IllegalStateException(CONCURRENT_GENERATION_BUSY_MESSAGE)))
        assertFalse(isConcurrentGenerationBusy(EngineException("")))
    }
}
