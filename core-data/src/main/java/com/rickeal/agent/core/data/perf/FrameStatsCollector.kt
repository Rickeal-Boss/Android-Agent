package com.rickeal.agent.core.data.perf

import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 零依赖帧统计采集器（W81，外部审查 P0-1 的 07a 档）。
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
 * ## Choreographer 主线程纪律（有实摔教训）
 *
 * `Choreographer.getInstance()` **必须在主线程获取**，跨线程获取会崩（本项目同源资产
 * Cyber-Android-Monitor-Pro 踩过）。故 [start] / [stop] 内部都
 * `Handler(Looper.getMainLooper()).post { … }` 再动 Choreographer；FrameCallback 的
 * doFrame 本身就跑在主线程，末尾自行 repost（FrameCallback 是一次性的，不 repost
 * 采集就停在一帧）。
 *
 * 数据出口：[stats] StateFlow（null = 未采集）。滑动窗口保留最近 [WINDOW_FRAMES] 个
 * 帧间隔（2s @60fps）；窗口每帧累积，快照**发布限 4Hz**（250ms）—— 防观测者效应，
 * 详见 [doFrame] 内注释（W81 真机取证：每帧发布会把诊断页自己拖到 ~5fps）。
 *
 * @param isDebuggable 是否采集（debug 构建为 true；release 恒 false ⇒ 全类零行为）。
 */
class FrameStatsCollector(val isDebuggable: Boolean) {

    /**
     * 一个滑动窗口的帧统计快照。
     *
     * @param frames 窗口内帧数（= 帧间隔样本数）。
     * @param jankyFrames 窗口内掉帧数（帧间隔 > [JANK_THRESHOLD_NANOS]）。
     * @param windowNanos 窗口时长（纳秒，= 帧间隔合计）。
     */
    data class FrameStats(val frames: Long, val jankyFrames: Long, val windowNanos: Long) {
        /** 窗口平均 FPS（windowNanos <= 0 的防御形态返回 0，不出 NaN/Inf）。 */
        val fps: Float get() = if (windowNanos > 0) frames * 1_000_000_000f / windowNanos else 0f

        /** 掉帧率百分比（frames <= 0 时返回 0，不出 NaN）。 */
        val jankyPercent: Float get() = if (frames > 0) jankyFrames * 100f / frames else 0f
    }

    private val _stats = MutableStateFlow<FrameStats?>(null)

    /** 帧统计快照（null = 未采集 / 已停止）。 */
    val stats: StateFlow<FrameStats?> = _stats.asStateFlow()

    /**
     * 采集态。start/stop 可能从任意线程调用（诊断页按钮 = 主线程，但不假设），
     * doFrame 与 start/stop 的 posted 块都只在主线程跑 —— volatile 只负责
     * 「调用方立即可见」。
     */
    @Volatile
    private var running = false

    /** 上一帧的 vsync 时间戳（纳秒）。仅主线程读写（doFrame / posted 块）。 */
    private var lastFrameNanos = 0L

    /** 上一次发布快照的 vsync 时间戳（纳秒，发布节流用）。仅主线程读写。 */
    private var lastPublishNanos = 0L

    /** 帧间隔滑动窗口（纳秒）。仅主线程读写。 */
    private val deltas = ArrayDeque<Long>()

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            if (lastFrameNanos != 0L) {
                val delta = frameTimeNanos - lastFrameNanos
                // 负 delta 理论上不可能（frameTimeNanos 单调递增），防御性丢弃，
                // 防污染窗口统计（掉帧计数 / windowNanos）。
                if (delta > 0) {
                    deltas.addLast(delta)
                    while (deltas.size > WINDOW_FRAMES) deltas.removeFirst()
                    // 窗口每帧累积，但**发布节流**（W81 真机取证修正）：doFrame 里
                    // 无条件 _stats.value = … 会让诊断页整页按帧重组（frameStats 在
                    // 路由级 collect ⇒ DiagnosticsScreen 全量重组），实测把页面帧率
                    // 自己拖到 ~5fps —— 帧率测量工具被自己的观测动作压死（观测者
                    // 效应）。发布限 4Hz：人眼读数 4Hz 足够，帧间重组归零。
                    if (frameTimeNanos - lastPublishNanos >= PUBLISH_INTERVAL_NANOS) {
                        lastPublishNanos = frameTimeNanos
                        _stats.value = computeFrameStats(deltas.toList())
                    }
                }
            }
            lastFrameNanos = frameTimeNanos
            // 自行 repost：一次性回调，不 repost 采集只活一帧。
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    /**
     * 开始采集。非 debuggable ⇒ no-op（release 零行为）；重复调用幂等。
     * 内部 post 到主线程后才动 Choreographer（主线程纪律，见类 KDoc）。
     */
    fun start() {
        if (!isDebuggable) return
        if (running) return
        running = true
        _stats.value = null
        Handler(Looper.getMainLooper()).post {
            if (!running) return@post
            lastFrameNanos = 0L
            lastPublishNanos = 0L
            deltas.clear()
            Choreographer.getInstance().postFrameCallback(frameCallback)
        }
    }

    /**
     * 停止采集（幂等）。移除可能已在队列里的回调 + doFrame 开头的 running 检查双保险；
     * 窗口与快照清空（[stats] 回 null = 「未采集」语义）。
     */
    fun stop() {
        if (!running) return
        running = false
        _stats.value = null
        Handler(Looper.getMainLooper()).post {
            lastFrameNanos = 0L
            deltas.clear()
            Choreographer.getInstance().removeFrameCallback(frameCallback)
        }
    }

    companion object {
        /**
         * 掉帧阈值（纳秒）：20ms = 60fps 单帧预算 16.7ms + 余量。**恰等于** 20ms 不算
         * 掉帧（判据是严格大于）—— 边界钉在 [computeFrameStats] 单测里。
         */
        const val JANK_THRESHOLD_NANOS = 20_000_000L

        /** 滑动窗口帧数：120 帧 ≈ 2s @60fps。 */
        private const val WINDOW_FRAMES = 120

        /**
         * 快照发布间隔（纳秒）：250ms = 4Hz。窗口每帧累积、发布限 4Hz —— 防观测者效应
         * （每帧发布 ⇒ 路由级 collect ⇒ 诊断页整页每帧重组，实测 ~5fps，见 [doFrame] 注释）。
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
