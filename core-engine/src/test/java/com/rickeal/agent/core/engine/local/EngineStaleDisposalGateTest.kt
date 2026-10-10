package com.rickeal.agent.core.engine.local

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [shouldDropStaleDisposal] 的判据单测（W60 外提纯函数后补齐，W59 A4 唯一可 JVM 覆盖面）。
 *
 * 为什么值得写：W59 A4 的幂等单源 = [LiteRtLmEngine.handleTemplateRenderFailure] 首行按
 * 生成世代令牌校验，迟到 / 异世代的处置必须整批丢弃，否则旧代会污染新世代刚复位 / 刚建好的
 * 会话态（关 P3#6 / P3#7）。该判据内联在 native 重类方法体内时 **JVM 不可实例化** ⇒ 无法
 * 直接单测（W59 审查 P3-4 已申报「无直接单测」）；外提为文件级纯函数后即可断言。判错方向
 * 各有代价：
 *  - 该丢弃却不丢（异代误判为同代）⇒ 旧代污染新世代，幂等单源失效；
 *  - 不该丢弃却丢（同代误判为异代）⇒ 当前代的证伪置位 / 计数 / persist 被吞 ⇒ 模板失败
 *    处置静默丢失。
 *
 * ⚠️ 边界申报（与产品侧 KDoc 一致）：本例只钉**判据语义**（相等 / 不等两方向），钉不住
 * **调用点存在性 / 传参正确性** —— 门控被删（纯函数变死码）或调用点少传 token，本例仍绿；
 * 该缺口由守卫 #33（世代门控接线）互补钉死。
 *
 * 纯 JVM、零 native：只调纯函数，不构造引擎、不触 litertlm。
 * ⚠️ JUnit4 纪律：所有 `@Test` 方法以返回 **void** 的断言收尾（`assertTrue`/`assertFalse`
 * 均返回 `Unit`）；反引号名禁 `. ; [ / < >`。
 */
class EngineStaleDisposalGateTest {

    @Test
    fun `同代令牌不判丢弃`() {
        // token == current（当前世代）：既有路径 —— 处置照常执行，不得丢弃。
        assertFalse(shouldDropStaleDisposal(token = 5L, current = 5L))
    }

    @Test
    fun `异代令牌判丢弃`() {
        // token != current（迟到 / 异世代）：整个处置必须丢弃，防旧代污染新世代。
        assertTrue(shouldDropStaleDisposal(token = 4L, current = 5L))
    }

    @Test
    fun `初始世代负一不判丢弃`() {
        // 边界：currentGenerationToken 初值 = -1（尚未自增，见引擎字段 KDoc）⇒ token 亦为
        // -1 时属**同代**，不丢弃。若把该边界误判为「异代」，首次生成前的处置会被静默吞掉。
        assertFalse(shouldDropStaleDisposal(token = -1L, current = -1L))
    }
}
