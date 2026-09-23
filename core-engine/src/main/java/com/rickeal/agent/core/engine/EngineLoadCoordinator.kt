package com.rickeal.agent.core.engine

import com.rickeal.agent.core.model.AgentLogStore
import com.rickeal.agent.core.model.EngineKind
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
 * 并发边界（Wave4 六路审查 A/C 后重写）：
 *  - `create` / `evict` / `closeAll` 三者 `@Synchronized` 互斥 —— 三个入口分属主线程
 *    （feature-models）与 Default 线程（AgentRunner.rebuildEngine），此前无屏障地交错
 *    改共享字段，能排出「延迟 evict 关掉新实例」的致命序列；
 *  - `load()` 本身不持类锁（native 加载可能长达数十秒，不能让 evict 排队等它），
 *    依赖 [@Volatile loading/loadingInstance] 与上游闸门（AgentRunner.runMutex +
 *    feature-models 的 isEngineBusy）保证「同一时刻只应有一个 load 活动」；
 *  - 延迟 evict 记**实例**而非 kind，收尾只 close 那一个实例 —— 记 kind 会把收尾前
 *    新 create 进缓存的实例误杀（native use-after-free）。
 */
class EngineLoadCoordinator(
    private val delegate: EngineFactory,
) : EngineFactory {

    private val _status = MutableStateFlow<EngineInitStatus>(EngineInitStatus.Idle)
    val status: StateFlow<EngineInitStatus> = _status.asStateFlow()

    /**
     * 延迟 evict 标记：load 进行中收到的清理请求，收尾（成功/失败）时消化。
     *
     * **必须是实例归属而不是 kind 归属**（Wave4 六路审查 A-P0-1）：
     * 旧实现存 `EngineKind`，收尾时执行 `delegate.evict(kind)` —— 关的是**当下缓存里那一个**。
     * 于是这条真实时序会误杀：[A 加载中] → [A 被 evict，延迟] → [重建路径 create(A) 拿到
     * **新实例 B** 并 load] → [A 的 native load 终于返回，收尾 evict(A)] → **B 被关闭**，
     * 而 B 正是刚重建好要用的那个。后续 generateStream 打在已 close 的 Conversation 上，
     * native use-after-free，SIGSEGV，runCatching 抓不住。
     * 改为记「发起 evict 时正在加载的那个实例」，收尾只关它自己。
     */
    @Volatile
    private var pendingEvictInstance: LlmEngine? = null

    /** 加载中标记（与 _status 配合，避免每次读状态做类型判断）。 */
    @Volatile
    private var loading: EngineKind? = null

    /** 当前正在 load 的那个实例（供 [evict] 记录归属）。load() 不持类锁，须 volatile 保可见性。 */
    @Volatile
    private var loadingInstance: LlmEngine? = null

    /**
     * `create` / `evict` / `closeAll` 三者互斥（Wave4 六路审查 C-P0-2）。
     *
     * 加载前台的两个入口不在同一线程：`ModelsViewModel.onLoad` 在主线程发起，
     * `AgentRunner.rebuildEngine` 在 `Dispatchers.Default` 上跑。此前三个方法各改各的字段，
     * 交错出「半成品序列」：create → evict → load → create → load，第二次 create 与第二次
     * load 之间没有任何屏障，`pendingEvict` 还留在"有值"状态 —— 新实例的加载一收尾就被
     * 上一次的延迟 evict 关掉。加锁后这个序列整体串行，收尾钩子看到的实例归属是确定的。
     */
    @Synchronized
    override fun create(kind: EngineKind): LlmEngine = LoadObservedEngine(delegate.create(kind), kind)

    @Synchronized
    override fun evict(kind: EngineKind) {
        if (loading == kind) {
            // gallery cleanUpAfterInit：加载是 native 黑盒不可打断，延迟到终态收尾。
            // 记实例而非 kind —— 见 [pendingEvictInstance] 的注解，记 kind 会误杀新实例。
            pendingEvictInstance = loadingInstance
            return
        }
        delegate.evict(kind)
    }

    @Synchronized
    override fun closeAll() {
        pendingEvictInstance = null
        loadingInstance = null
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
            val modelRef = config.model?.id
            loading = observedKind
            loadingInstance = inner
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
                //
                // 只关「发起 evict 时正在加载的那个实例」（=== 比对），绝不 delegate.evict(kind)：
                // 后者在收尾前若有新实例 create 进缓存，会误杀新实例（A-P0-1 的 SIGSEGV 面）。
                // 直接 close 目标实例等价于旧 evict 的 remove+close，且不触碰缓存里的其他成员。
                // `doomed === inner` 兜底：若 pendingEvict 指向别的实例（理论不可达，双 load
                // 已被上游闸门串行化），宁可留着也不关错 —— close 错对象就是 native 崩溃。
                loading = null
                if (loadingInstance === inner) loadingInstance = null
                val doomed = pendingEvictInstance
                pendingEvictInstance = null
                if (doomed != null && doomed === inner) {
                    runCatching { doomed.close() }
                        .onFailure { AgentLogStore.warn("延迟 evict：关闭加载中的旧实例失败（${it.message}）") }
                }
            }
        }
    }
}
