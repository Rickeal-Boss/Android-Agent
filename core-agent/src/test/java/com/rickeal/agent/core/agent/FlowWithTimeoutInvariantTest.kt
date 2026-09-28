package com.rickeal.agent.core.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * 固化「`withTimeout { emit() }` 在 `flow {}` 内合法」这一不变量（Wave 31）。
 *
 * 背景：[AgentRunner] 的 `runGenerationRound` 用
 * `withTimeout(policy.generationTimeoutMillis) { engine.generateStream(req).collect { emit(...) } }`
 * 包住生成流。若 kotlinx-coroutines 的 SafeCollector 上下文不变量不认 TimeoutCoroutine，
 * 每次 run 的第一个 TextDelta 就会抛 `IllegalStateException: Flow invariant is violated`。
 *
 * 结论（已逐行核验 kotlinx-coroutines 1.9.0 源码）：合法、不会抛 ——
 * `TimeoutCoroutine : ScopeCoroutine<T>(uCont.context, uCont)`（Timeout.kt:151-154），
 * `SafeCollector.transitiveCoroutineParent` 遇到 ScopeCoroutine 会继续沿 parent 上溯
 * （SafeCollector.common.kt:92-97），故发射 Job 的传递父 = collect job，判据
 * `emissionParentJob !== collectJob` 不成立；withTimeout 只加 Job、不换调度器，非 Job
 * 上下文元素（含 ContinuationInterceptor）亦一致。
 *
 * 本测试是该不变量的回归锚：若将来升级 coroutines 让该穿透失效，本测试会先红 ——
 * 而不是等到真机上「每次 run 第一个 TextDelta 就崩」。
 */
class FlowWithTimeoutInvariantTest {

    @Test
    fun withTimeoutInsideFlowBuilderIsAllowed() = runBlocking {
        val got = mutableListOf<Int>()
        flow {
            withTimeout(5_000L) { emit(1) }
            emit(2)
        }.collect { got += it }
        assertEquals(listOf(1, 2), got)
    }
}
