package com.rickeal.agent.core.data.perf

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 零依赖帧统计采集器（W81，外部审查 P0-1 的 07a 档；W82 加会话级聚合）。
 *
 * ## 为什么帧级数据只剩这一条路
 *
 * W80-01 真机取证：PDRM00 上 `dumpsys gfxinfo <pkg>` 的帧统计段**恒 0**（设备级不可用，
 * W80-07 复核仍 0）⇒ 系统帧统计出口在这台现役设备上等于没有。帧级数据唯一现成路径原是
 * `JankStats`（androidx.metrics），但引它要新增依赖 —— 本项目「版本锁死」纪律防的正是
 * 随意加依赖，等用户批。而 JankStats 底层就是 [Choreographer.FrameCallback] 的封装，
 * 滚动 / 生成期的掉帧率只需要 ~30 行零依赖采集即可覆盖（本项目唯一消费场景，见诊断页
 * 「帧统计」卡）。零依赖方案不触发版本锁死纪律。
 *
 * ## 为什么 debug 门控
 *
 * 帧统计是**观测面不是能力**：release 包恒零行为（[start] no-op、无卡、无回调）。
 * 门控口径由构造方传入（AppContainer 用 `ApplicationInfo.FLAG_DEBUGGABLE`，不用
 * `BuildConfig.DEBUG` —— core-data 没开 buildConfig feature，不为它改 gradle）。
 *
 * ## 两套数：2s 滑动窗口 vs 整次会话（W82）
 *
 * [stats] 窗口答「**现在**多少 fps」；[session] 答「**这一整段**总体掉多少帧」。为什么
 * 必须两套：窗口只装得下 120 帧 ≈ 2s，装不下「导航到对话页 + 发一条 + 返回」这种跨页
 * 操作 —— 用户回到诊断页时那 2s 早被返回动画冲掉，永远看不到刚才那段。
 *
 * ## Choreographer 主线程纪律（有实摔教训）
 *
 * `Choreographer.getInstance()` **必须在主线程获取**，跨线程获取会崩（本项目同源资产
 * Cyber-Android-Monitor-Pro 踩过）⇒ Choreographer 那半段经 [postToMain] 投递后再动。
 * FrameCallback 的 doFrame 本身就跑在主线程，末尾自行 repost（FrameCallback 是一次性的，
 * 不 repost 采集就停在一帧）。但**采集态与会话累计的翻转是同步的**（不投递）⇒
 * [start] / [stop] / [resetSession] **必须主线程调用**（申报见 [_active] / [sessionAcc]）。
 *
 * ## 会话聚合段已外提（W83-05）
 *
 * 守卫 #49 给本文件划了 250 行上限，搬迁前 249 行（余 1）⇒ 加任何一行即触顶（触顶 =
 * 启动拆分评审，非改阈值）。会话聚合面（[FrameStatsSession] 数据类、newFrameStatsSession /
 * accumulateFrameStatsSession 两个纯函数、JANK_THRESHOLD_NANOS 阈值）整段搬至同包
 * `FrameStatsSession.kt`：本文件只留采集接线与 2s 窗口，行为零变化（纯搬移）。
 *
 * 数据出口：[stats] 窗口快照 + [session] 会话累计，两者**同一次 post** 发布、限 4Hz
 * （250ms）—— 防观测者效应（W81 真机取证：每帧发布把诊断页自己拖到 ~5fps，见 [onFrameDelta]）。
 * @param postToMain 主线程投递。**构造缝**：默认值即生产路径（真 Handler）；JVM 单测传
 *   空实现，就能在不触 Choreographer 的前提下验证 [start] / [stop] 的会话语义。
 * @param elapsedMillis 单调时钟。同上构造缝 —— 单测传假时钟才能确定性验证会话起点。
 */
class FrameStatsCollector(
    val isDebuggable: Boolean,
    private val postToMain: (() -> Unit) -> Unit = { Handler(Looper.getMainLooper()).post(it) },
    private val elapsedMillis: () -> Long = { SystemClock.elapsedRealtime() },
) {

    /** 一个滑动窗口的帧统计快照（frames = 帧间隔样本数；janky = 超阈个数）。 */
    data class FrameStats(val frames: Long, val jankyFrames: Long, val windowNanos: Long) {
        /** 窗口平均 FPS（windowNanos <= 0 的防御形态返回 0，不出 NaN/Inf）。 */
        val fps: Float get() = if (windowNanos > 0) frames * 1_000_000_000f / windowNanos else 0f

        /** 掉帧率百分比（frames <= 0 时返回 0，不出 NaN）。 */
        val jankyPercent: Float get() = if (frames > 0) jankyFrames * 100f / frames else 0f
    }

    private val _stats = MutableStateFlow<FrameStats?>(null)

    /** 帧统计快照（null = 未采集 / 已停止）。 */
    val stats: StateFlow<FrameStats?> = _stats.asStateFlow()

    private val _session = MutableStateFlow<FrameStatsSession?>(null)

    /** 会话累计（null = 进程还没 [start] 过）。[stop] 后**保留** —— 停下后仍要能读总数。 */
    val session: StateFlow<FrameStatsSession?> = _session.asStateFlow()

    private val _active = MutableStateFlow(false)

    /** 采集态（[start] / [stop] 同步翻转；release 恒 false）。诊断页卡片据此切按钮文案。
     * ⚠️ **已知放宽**：`if (_active.value) return` 是**非原子**读-判-写（非 compareAndSet）；
     * 当前两个调用点都在主线程 ⇒ 安全，**将来出现后台调用点必须改 compareAndSet**（否则并发
     * start 会双重 post 回调 ⇒ 帧间隔重复入账、统计虚高）。 */
    val active: StateFlow<Boolean> = _active.asStateFlow()

    /** 上一帧 vsync / 上次发布 vsync / 帧间隔滑窗（纳秒）：均仅主线程读写。 */
    private var lastFrameNanos = 0L
    private var lastPublishNanos = 0L
    private val deltas = ArrayDeque<Long>()

    /** 会话累计器（主线程独占，无锁）。与 [deltas] 同生命周期：start 重置、stop 不动。
     * 每帧 `copy()` 一次小对象（≈48B/帧，ART 年轻代无感）—— 已知取舍，非漏掉的优化。 */
    private var sessionAcc: FrameStatsSession = newFrameStatsSession(0L)

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!_active.value) return
            if (lastFrameNanos != 0L) {
                val delta = frameTimeNanos - lastFrameNanos
                // 负 delta 理论上不可能（frameTimeNanos 单调递增），防御性丢弃，防污染统计。
                if (delta > 0) onFrameDelta(delta, frameTimeNanos)
            }
            lastFrameNanos = frameTimeNanos
            // 自行 repost：一次性回调，不 repost 采集只活一帧。
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    /**
     * 一帧间隔入账：滑窗 + 会话累计 + 发布节流。**主线程独占**（[deltas] / [sessionAcc] 都
     * 无锁）。抽成方法是为了让 JVM 单测走**同一条**入账路径（不必起 Choreographer）。
     *
     * 为什么发布限 4Hz（W81 真机取证修正）：无条件 `_stats.value = …` 会让诊断页按帧重组，
     * 实测把页面帧率自己拖到 ~5fps —— 帧率工具被自己的观测动作压死（观测者效应）。W82 又把
     * 订阅下沉到卡片（重组范围从整页收窄到一张卡）：节流与收窄是两道互补的闸。
     */
    internal fun onFrameDelta(deltaNanos: Long, nowNanos: Long) {
        deltas.addLast(deltaNanos)
        while (deltas.size > WINDOW_FRAMES) deltas.removeFirst()
        // 会话累计每帧都加（纯加法、无锁、不设上限），但发布同样限 4Hz。
        sessionAcc = accumulateFrameStatsSession(sessionAcc, deltaNanos)
        if (nowNanos - lastPublishNanos >= PUBLISH_INTERVAL_NANOS) {
            lastPublishNanos = nowNanos
            _stats.value = computeFrameStats(deltas.toList())
            // 窗口与会话在**同一次** post 里发布：分两次发 ⇒ 卡片重组翻倍。
            _session.value = sessionAcc
        }
    }

    /**
     * 开始采集。非 debuggable ⇒ no-op（release 零行为）；重复调用幂等。**必须主线程调用**
     * （采集态与会话同步翻转）；只有 Choreographer 那半段 post 到主线程（见类 KDoc）。
     */
    fun start() {
        if (!isDebuggable) return
        if (_active.value) return
        _active.value = true
        _stats.value = null
        // 会话随窗口一起重置（新一次采集 = 新会话）。同步做、不等 posted 块：按钮点下就要立刻看到「0 帧」。
        sessionAcc = newFrameStatsSession(elapsedMillis())
        _session.value = sessionAcc
        postToMain {
            if (!_active.value) return@postToMain
            lastFrameNanos = 0L
            lastPublishNanos = 0L
            deltas.clear()
            Choreographer.getInstance().postFrameCallback(frameCallback)
        }
    }

    /**
     * 停止采集（幂等，**必须主线程调用**）。移除可能已在队列里的回调 + doFrame 开头的采集态
     * 检查双保险；窗口快照清空（[stats] 回 null = 「未采集」语义），但**会话保留** —— 用户
     * 停下后还要能读这次会话的总帧数与总掉帧率（否则「停止」= 数据蒸发）。
     */
    fun stop() {
        if (!_active.value) return
        _active.value = false
        _stats.value = null
        postToMain {
            lastFrameNanos = 0L
            deltas.clear()
            Choreographer.getInstance().removeFrameCallback(frameCallback)
        }
    }

    /**
     * 重置会话累计（诊断页「重置会话」按钮）。**必须主线程调用**（直接写 [sessionAcc]）。
     * 窗口**不动**：窗口答「现在」、会话答「这一整段」，清会话不该抹掉即时读数。立刻发布
     * （不等 4Hz），否则按钮点下去看不出任何反应 —— 与本项目「失败/无反应都要可见」同口径。
     */
    fun resetSession() {
        if (!isDebuggable) return
        sessionAcc = newFrameStatsSession(elapsedMillis())
        _session.value = sessionAcc
    }

    companion object {
        /** 滑动窗口帧数：120 帧 ≈ 2s @60fps。 */
        private const val WINDOW_FRAMES = 120

        /**
         * 快照发布间隔（纳秒）：250ms = 4Hz。窗口 / 会话每帧累积、发布限 4Hz —— 防观测者
         * 效应（每帧发布 ⇒ 诊断页每帧重组，实测 ~5fps，见 [onFrameDelta]）。
         */
        private const val PUBLISH_INTERVAL_NANOS = 250_000_000L

        /**
         * 帧间隔表 → 帧统计（**纯函数**，可 JVM 单测：不触 Android、不做 IO）。
         *
         * @param deltas 相邻帧的 vsync 间隔（纳秒），时序无关，只取数量/合计/超阈计数。
         * @return 空表 ⇒ null（「没有帧间隔样本」≠ 「0 帧」，UI 侧据此区分未采集）；
         *   frames = deltas.size；windowNanos = 合计；jankyFrames = 间隔严格大于
         *   [JANK_THRESHOLD_NANOS] 的个数。
         */
        fun computeFrameStats(deltas: List<Long>): FrameStats? {
            if (deltas.isEmpty()) return null
            return FrameStats(
                frames = deltas.size.toLong(),
                jankyFrames = deltas.count { it > JANK_THRESHOLD_NANOS }.toLong(),
                windowNanos = deltas.sum(),
            )
        }
    }
}
