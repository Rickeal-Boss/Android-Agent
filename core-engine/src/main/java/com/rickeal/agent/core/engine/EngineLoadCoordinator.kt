package com.rickeal.agent.core.engine

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

/**
 * 引擎初始化状态（gallery Model.InitializationStatus 的端侧同构）。
 *
 * 转移图：Idle → Initializing → { Initialized | Failed }；
 * Failed 后再 load 回到 Initializing；取消（CancellationException）回 Idle ——
 * 用户主动停止不算失败，否则 UI 会把「我停的」渲染成「引擎坏了」。
 * Idle 之外的状态都带 kind，UI 据此区分「正在加载哪个引擎/换哪个模型」。
 *
 * 本轮状态流不接 UI（未来 feature-models 可读它做加载进度/失败详情展示）；
 * 设计上它只观测 load() 活动 —— create() 纯透传，不触发任何状态转移。
 */
sealed interface EngineInitStatus {
    data object Idle : EngineInitStatus
    data class Initializing(val kind: EngineKind, val modelRef: String?) : EngineInitStatus
    data class Initialized(val kind: EngineKind, val elapsedMillis: Long) : EngineInitStatus
    data class Failed(
        val kind: EngineKind,
        val message: String,
        /** 当前所有加载失败都可通过「换新实例重建」重试（AgentRunner rebuildEngine 语义）。 */
        val canRetry: Boolean = true,
    ) : EngineInitStatus
}

/**
 * 引擎加载状态机 —— gallery（google-ai-edge/gallery）ModelManagerViewModel 的
 * 竞态防护语义移植，做成 [EngineFactory] 的**装饰器**：
 *
 *  - `create()` 透传 delegate，但把返回实例包进 [LoadObservedEngine]（Kotlin 接口委托
 *    `by inner`，只重写 `load()`）—— load 的开始/成功/失败由此进入 [status] 状态流；
 *  - **延迟 evict（gallery cleanUpAfterInit 语义）**：加载进行中收到的 [evict] 不中断
 *    加载，只置标记；load 到达终态（成功或失败）后由收尾钩子消化 —— native load 是
 *    不可中断黑盒，硬打断等于对半初始化句柄 close（use-after-free 同族）；
 *  - delegate 仍是缓存/evict 语义的**唯一权威**（DefaultEngineFactory），本类只加观测
 *    与竞态闸门，不持有引擎实例。
 *
 * AgentRunner 拿到的仍是普通 EngineFactory —— 主循环零改动；它失败后的
 * evict→create→load 重载路径自动进入状态流（gallery「自愈链可见化」的 core 侧落点）。
 *
 * 并发边界（诚实声明）：同一时刻只应有一个 load 活动。既有闸门已保证 —— runMutex
 * 串行化 Agent 内的 run；feature-models 的加载入口有 isEngineBusy UI 闸门 + 引擎
 * `waitForGenerationsToFinish()` 硬闸门。本类的 pendingEvict 是**单槽**（不是队列）：
 * 加载中来了多个 evict 只保留语义（都等于「加载完关掉它」），单槽足够。
 */
class EngineLoadCoordinator(
    private val delegate: EngineFactory,
) : EngineFactory {

    private val _status = MutableStateFlow<EngineInitStatus>(EngineInitStatus.Idle)
    val status: StateFlow<EngineInitStatus> = _status.asStateFlow()

    /** 延迟 evict 标记：load 进行中收到的清理请求，收尾（成功/失败）时消化。 */
    @Volatile
    private var pendingEvict: EngineKind? = null

    /** 加载中标记（与 _status 配合，避免每次读状态做类型判断）。 */
    @Volatile
    private var loading: EngineKind? = null

    override fun create(kind: EngineKind): LlmEngine = LoadObservedEngine(delegate.create(kind), kind)

    override fun evict(kind: EngineKind) {
        if (loading == kind) {
            // gallery cleanUpAfterInit：加载是 native 黑盒不可打断，延迟到终态收尾。
            pendingEvict = kind
            return
        }
        delegate.evict(kind)
    }

    override fun closeAll() {
        pendingEvict = null
        loading = null
        delegate.closeAll()
    }

    /**
     * gallery awaitInitialization 的对应物：等待在途加载收敛（无在途立即返回）。
     * 终态是 Failed 则重抛引擎异常 —— 调用方走既有的 rebuildEngine 恢复路径。
     */
    suspend fun awaitInitialization(kind: EngineKind = EngineKind.LOCAL): LlmEngine {
        val s = _status.value
        if (s !is EngineInitStatus.Initializing || s.kind != kind) return delegate.create(kind)
        return delegate.create(kind).also {
            _status.first { it !is EngineInitStatus.Initializing }
        }
    }

    /**
     * load 观测包装：除 `load()` 外全部透传 inner（Kotlin 接口委托，零样板）。
     * 每次包装是无状态轻对象 —— load 收尾后状态机不再引用它，GC 即清。
     */
    private inner class LoadObservedEngine(
        private val inner: LlmEngine,
        private val observedKind: EngineKind,
    ) : LlmEngine by inner {

        override suspend fun load(config: EngineLoadConfig) {
            val startedAt = System.currentTimeMillis()
            val modelRef = config.model?.id ?: config.remote?.id
            loading = observedKind
            _status.value = EngineInitStatus.Initializing(observedKind, modelRef)
            try {
                inner.load(config)
                // 幂等 load 直接返回的路径也落 Initialized（状态机穷尽性要求）。
                _status.value = EngineInitStatus.Initialized(
                    observedKind,
                    System.currentTimeMillis() - startedAt,
                )
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) {
                    // 取消回 Idle：用户主动停止不是引擎失败（否则恢复卡/错误卡误弹）。
                    _status.value = EngineInitStatus.Idle
                } else {
                    _status.value = EngineInitStatus.Failed(
                        observedKind,
                        t.message ?: t.javaClass.simpleName,
                    )
                }
                throw t
            } finally {
                // 收尾钩子：消化加载期间被延迟的 evict（gallery cleanUpAfterInit）。
                // 无论成功失败都要消化 —— 失败场景（markFailed 后清理）语义一致。
                loading = null
                pendingEvict?.takeIf { it == observedKind }?.let {
                    pendingEvict = null
                    delegate.evict(it)
                }
            }
        }
    }
}
