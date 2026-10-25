package com.rickeal.agent.core.data.perf

/**
 * 帧统计的**会话聚合段**（W82 随 FrameStatsCollector 引入；W83-05 因守卫 #49 余 1 外提）。
 *
 * ## 为什么外提
 *
 * 守卫 #49 给 FrameStatsCollector.kt 划了 250 行上限，搬迁前 249 行（余 1）—— 再加任何
 * 一行（哪怕一条 KDoc）即触顶，而触顶 = 启动拆分评审（不是改阈值）。会话聚合（[FrameStatsSession]
 * 数据类 + newFrameStatsSession / accumulateFrameStatsSession 两个纯函数 + 掉帧阈值）与采集器
 * 本体（Choreographer 接线、2s 滑窗、4Hz 发布节流）是两段关注点 ⇒ 整段平级搬至本文件，
 * 采集器改为调用，**行为零变化**（纯搬移，git diff 呈删除 + 新增对称块）。
 *
 * 搬迁面逐字节保留；唯一的结构变化是 companion 嵌套成员改顶层声明、互相引用改用新函数名。
 */

/**
 * 整次采集会话的累计统计（W82）。与 [FrameStatsCollector.FrameStats] 并列：窗口答「现在」，会话答「这一整段」。
 *
 * @param totalFrames 会话内帧数（= 帧间隔样本数，Long 计数不设上限）。
 * @param totalJankyFrames 会话内掉帧数（帧间隔 > [JANK_THRESHOLD_NANOS]）。
 * @param sessionNanos 会话**帧覆盖**时长（Σ 帧间隔）。刻意不取墙钟差：冻结 / 灭屏期间
 *   根本没有帧，取墙钟差会让 FPS 假性暴跌（W78 实测 ColorOS 单次冻结 416s）。
 * @param startedAtElapsedMillis 会话起点（elapsedRealtime 毫秒，仅展示口径用）。
 */
data class FrameStatsSession(
    val totalFrames: Long,
    val totalJankyFrames: Long,
    val sessionNanos: Long,
    val startedAtElapsedMillis: Long,
) {
    /** 会话平均 FPS（sessionNanos <= 0 返回 0，不出 NaN/Inf）。 */
    val fps: Float get() = if (sessionNanos > 0) totalFrames * 1_000_000_000f / sessionNanos else 0f

    /** 会话掉帧率百分比（totalFrames <= 0 时返回 0，不出 NaN）。 */
    val jankyPercent: Float get() = if (totalFrames > 0) totalJankyFrames * 100f / totalFrames else 0f
}

/**
 * 掉帧阈值（纳秒）：20ms = 60fps 单帧预算 16.7ms + 余量。**恰等于** 20ms 不算
 * 掉帧（判据是严格大于）—— 边界钉在 [FrameStatsCollector.computeFrameStats] 单测里。
 *
 * ⚠️ 必须保持**顶层 public**：feature-settings 的 DiagnosticsScreen 跨模块消费（`internal`
 * 跨模块不可见）。原为 FrameStatsCollector companion 常量，W83-05 随会话聚合段外提到本文件，
 * 可见性不许掉级。
 */
const val JANK_THRESHOLD_NANOS = 20_000_000L

/**
 * 新会话（全 0，起点 = [nowElapsedMillis]）。**纯函数**，JVM 可测。
 *
 * W83-05 从 FrameStatsCollector companion 外提（#49 余 1）。`internal` 而非 `private`：
 * 私有顶层声明只在本文件可见，而 [FrameStatsCollector]（同包另一文件）的 start / resetSession
 * 要调 ⇒ `internal` 是跨文件同包的最低必要可见性（原 companion 内 `private` 即可，外提后必须
 * 放开一级）。
 */
internal fun newFrameStatsSession(nowElapsedMillis: Long): FrameStatsSession =
    FrameStatsSession(0L, 0L, 0L, nowElapsedMillis)

/**
 * 会话累计一步（**纯函数**）：帧数 +1、超阈则掉帧 +1、时长加该帧间隔（判据同上）。
 * `internal` 而非 `private` 的理由见 [newFrameStatsSession]。
 */
internal fun accumulateFrameStatsSession(prev: FrameStatsSession, deltaNanos: Long): FrameStatsSession =
    prev.copy(
        totalFrames = prev.totalFrames + 1,
        totalJankyFrames = prev.totalJankyFrames +
            if (deltaNanos > JANK_THRESHOLD_NANOS) 1 else 0,
        sessionNanos = prev.sessionNanos + deltaNanos,
    )
