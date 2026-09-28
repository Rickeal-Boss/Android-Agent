package com.rickeal.agent.core.engine

import com.rickeal.agent.core.model.EngineKind
import com.rickeal.agent.core.model.GenerationChunk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * [EngineLoadCoordinator] 状态机与「延迟 evict」竞态语义的 JVM 单测（Wave 31 · 流 4）。
 *
 * 为什么这些用例值得写（而不是凑数）：本类是整个引擎加载路径上**唯一**的竞态防护层，
 * 它的两处核心契约都是「错了不报错、只在真机上偶发 SIGSEGV」的类型 ——
 *  1. 状态机三态迁移（Idle / Initializing / Initialized / Failed，取消回 Idle）——
 *     UI 恢复卡、错误卡的判据全在这里；
 *  2. **延迟 evict**：加载中收到的清理请求必须延到终态收尾、且**只关发起时那个实例**
 *     （记 kind 会误杀重建路径刚 create 的新实例 → native use-after-free）。
 *
 * 全程纯 JVM：delegate 是 [FakeFactory] 注入的假实现，**不构造任何 native 引擎**
 * （[LlmEngine] 是接口，测试替身零 litertlm 依赖）。零 Android 类、零 IO。
 */
class EngineLoadCoordinatorTest {

    private fun loadConfig() = EngineLoadConfig()

    // ── 状态机迁移 ────────────────────────────────────────────────────────────

    @Test
    fun `初始状态为 Idle`() {
        val coordinator = EngineLoadCoordinator(FakeFactory())
        assertEquals(EngineInitStatus.Idle, coordinator.status.value)
    }

    @Test
    fun `create 透传 delegate 且不触发状态转移`() {
        // 类 KDoc 契约：状态机只观测 load() 活动，create() 纯透传。
        val factory = FakeFactory()
        val coordinator = EngineLoadCoordinator(factory)
        val engine = coordinator.create(EngineKind.LOCAL)
        assertNotNull(engine)
        assertEquals(1, factory.created.size)
        assertEquals(EngineInitStatus.Idle, coordinator.status.value)
    }

    @Test
    fun `load 期间状态为 Initializing 且带 kind`() {
        val factory = FakeFactory()
        val coordinator = EngineLoadCoordinator(factory)
        val engine = coordinator.create(EngineKind.LOCAL)
        var during: EngineInitStatus? = null
        factory.created[0].onLoad = { during = coordinator.status.value }
        runBlocking { engine.load(loadConfig()) }
        assertTrue(during is EngineInitStatus.Initializing)
        assertEquals(EngineKind.LOCAL, (during as EngineInitStatus.Initializing).kind)
    }

    @Test
    fun `load 成功落 Initialized 且带 kind`() {
        val factory = FakeFactory()
        val coordinator = EngineLoadCoordinator(factory)
        val engine = coordinator.create(EngineKind.LOCAL)
        runBlocking { engine.load(loadConfig()) }
        val status = coordinator.status.value
        assertTrue(status is EngineInitStatus.Initialized)
        assertEquals(EngineKind.LOCAL, (status as EngineInitStatus.Initialized).kind)
    }

    @Test
    fun `load 失败落 Failed 且重抛异常`() {
        val factory = FakeFactory()
        val coordinator = EngineLoadCoordinator(factory)
        val engine = coordinator.create(EngineKind.LOCAL)
        factory.created[0].loadFailure = IllegalStateException("boom")
        val thrown = runCatching { runBlocking { engine.load(loadConfig()) } }.exceptionOrNull()
        assertTrue(thrown is IllegalStateException)
        val status = coordinator.status.value
        assertTrue(status is EngineInitStatus.Failed)
        val failed = status as EngineInitStatus.Failed
        assertEquals(EngineKind.LOCAL, failed.kind)
        assertEquals("boom", failed.message)
        // 所有加载失败都可通过「换新实例重建」重试（AgentRunner rebuildEngine 语义）。
        assertTrue(failed.canRetry)
    }

    @Test
    fun `load 被取消落回 Idle 而非 Failed`() {
        // 用户主动停止不是引擎失败 —— 否则 UI 会把「我停的」渲染成「引擎坏了」。
        val factory = FakeFactory()
        val coordinator = EngineLoadCoordinator(factory)
        val engine = coordinator.create(EngineKind.LOCAL)
        factory.created[0].loadFailure = CancellationException("用户停止")
        val thrown = runCatching { runBlocking { engine.load(loadConfig()) } }.exceptionOrNull()
        assertTrue(thrown is CancellationException)
        assertEquals(EngineInitStatus.Idle, coordinator.status.value)
    }

    // ── 延迟 evict（gallery cleanUpAfterInit 语义）────────────────────────────

    @Test
    fun `加载中的 evict 被延迟到终态收尾且只关该实例`() {
        val factory = FakeFactory()
        val coordinator = EngineLoadCoordinator(factory)
        val engine = coordinator.create(EngineKind.LOCAL)
        val inner = factory.created[0]
        // load 进行中收到 evict：native load 是不可中断黑盒，必须延到终态。
        inner.onLoad = { coordinator.evict(EngineKind.LOCAL) }
        runBlocking { engine.load(loadConfig()) }
        // 延迟期间不打断：delegate.evict 一次都不应被调用。
        assertTrue(factory.evicted.isEmpty())
        // 收尾钩子关闭「发起 evict 时正在加载的那个实例」。
        assertTrue(inner.closed)
    }

    @Test
    fun `空闲时 evict 立即透传 delegate`() {
        val factory = FakeFactory()
        val coordinator = EngineLoadCoordinator(factory)
        coordinator.create(EngineKind.LOCAL)
        coordinator.evict(EngineKind.LOCAL)
        assertEquals(listOf(EngineKind.LOCAL), factory.evicted)
    }

    @Test
    fun `closeAll 透传 delegate`() {
        val factory = FakeFactory()
        val coordinator = EngineLoadCoordinator(factory)
        coordinator.closeAll()
        assertEquals(1, factory.closeAllCount)
    }

    // ── 测试替身（零 native / 零 Android）──────────────────────────────────────

    private class FakeEngine(override val kind: EngineKind) : LlmEngine {

        var closed = false

        /** load() 执行体：供用例在「加载中」这一瞬间触发并发动作（如 evict）。 */
        var onLoad: (() -> Unit)? = null

        /** 非 null 时 load() 抛出它。 */
        var loadFailure: Throwable? = null

        override val isLoaded: Boolean get() = false

        override suspend fun load(config: EngineLoadConfig) {
            onLoad?.invoke()
            loadFailure?.let { throw it }
        }

        override suspend fun unload() = Unit

        override suspend fun capabilities(): EngineCapabilities = EngineCapabilities()

        override fun generateStream(request: GenerationRequest): Flow<GenerationChunk> = emptyFlow()

        override suspend fun stop() = Unit

        override suspend fun tokenCount(text: String): Int = text.length

        override fun close() {
            closed = true
        }
    }

    private class FakeFactory : EngineFactory {

        val created = mutableListOf<FakeEngine>()
        val evicted = mutableListOf<EngineKind>()
        var closeAllCount = 0

        override fun create(kind: EngineKind): LlmEngine = FakeEngine(kind).also { created.add(it) }

        override fun evict(kind: EngineKind) {
            evicted.add(kind)
        }

        override fun closeAll() {
            closeAllCount++
        }
    }
}
