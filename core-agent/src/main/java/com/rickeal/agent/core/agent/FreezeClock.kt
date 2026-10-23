package com.rickeal.agent.core.agent

/**
 * 单调时钟 + 「可运行（未冻结）时长」视角。
 *
 * 国产 OEM（ColorOS / HyperOS / OriginOS 等）在灭屏约 10s 后对进程做 cgroup 级冻结：
 * 线程全停，但内核单调时钟（[System.nanoTime] / uptimeMillis）继续走。因此基于单调钟的
 * `withTimeout` 会在「冻结时长 > 剩余预算」时于解冻瞬间误触发取消 —— 表现为
 * 「模型思考中锁屏/灭屏后 run 被掐断」（W78-12）。
 *
 * [FreezeClock] 把冻结时段从计时中剔除：[runnableNanosSince] 只数**本次 run** 真正在跑的
 * 时间，让长时间后台 / 锁屏下的生成本质上「暂停计时」，解冻后无缝续跑。
 *
 * ## W79 口径修正：累计量是「绝对事实」，基线是「本次 run 的局部变量」
 *
 * 旧契约 `runnableElapsedNanos(startedNanos)` 只能用**进程级累计**做减法，而累计含
 * `startedNanos` **之前**的灭屏 ⇒ 历史冻结被从本轮预算里扣掉：灭屏 416s 后开跑，预算当场
 * 变负 ⇒ 解冻瞬间立即取消（比不剔除更糟）。故拆成 [frozenNanos]（进程级、单调不减、永不
 * 清零）+ [runnableNanosSince]（调用方在起点自取基线）：历史与本次 run 在**类型层面**分离。
 * 单参入口已删除 —— 保留它 = 保留一条「语义必然错误」的复发通道。
 */
interface FreezeClock {
    /** 单调刻度（纳秒），语义同 [System.nanoTime]。 */
    fun nowNanos(): Long

    /**
     * **进程生命周期内累计**冻结纳秒：单调不减、永不清零。默认 0 = 无冻结感知。
     *
     * 🔴 必须包含**在途灭屏窗口**（`offAt >= 0` 时 = `accum + (now - offAt)`）：否则 off→on
     * 瞬间该段一次性跳变，[runnableNanosSince] 的 delta 过减 ⇒ 解冻瞬间误掐；且起点落在
     * 灭屏中时会跨 off→on 跳变。live 累加保证**任意时刻读数连续**（亮屏前最后一次读数 ==
     * 亮屏后第一次读数）。
     */
    fun frozenNanos(): Long = 0L

    /**
     * 自 [startedNanos] 起、**剔除本次 run 冻结**后的可运行时长（纳秒）。
     *
     * [frozenBaselineNanos] 必须由调用方在 run **起点取一次**。
     * 🔴 **刻意不给默认值**：默认值只能调用时现取 ⇒ 每轮 delta 恒 0 ⇒ 冻结剔除静默失效
     * （灭屏 416s 后解冻立即取消），比原缺陷更糟。签名缺席即契约。
     */
    fun runnableNanosSince(startedNanos: Long, frozenBaselineNanos: Long): Long =
        (nowNanos() - startedNanos) - (frozenNanos() - frozenBaselineNanos)
}

/** 无冻结感知的默认实现（JVM 单测 / 无 Android 环境 / 默认注入值）。 */
object MonotonicFreezeClock : FreezeClock {
    override fun nowNanos(): Long = System.nanoTime()
}

/**
 * 灭屏时段累加器（W79）：纯 JVM、[nowNanos] 注入 ⇒ 累加逻辑可在 JVM 单测里覆盖
 * 「跨窗口 / 起点落在窗口内 / 同锁读写」这些唯一有竞态语义的地方。
 *
 * `:core-data` 的 `ScreenStateMonitor` 退化为广播适配器，只把 on/off 转接进来并把
 * [FreezeClock.frozenNanos] 委托给本类（`core-data` 依赖 Android，不可 JVM 测）。
 *
 * 🔴 **[offAtNanos] 与 [accumNanos] 的读写必须在同一把锁内**：若 `offAt` 与 `accum` 分两次
 * 读、中间亮屏广播落地，整段灭屏会被**双计** ⇒ [frozenNanos] 偏大 ⇒ delta 过减 ⇒ 误掐，
 * 且该竞态恰好在「解冻瞬间看门狗正在读」时最易命中。不接受「概率低」——失效方向是误掐。
 *
 * 累计是**进程级事实**（进程被杀重启才归零），**基线由每次 run 自己取**（见
 * [FreezeClock.runnableNanosSince]）⇒ 历史冻结不进本轮预算，跨 run 不污染。
 */
class FreezeAccumulator(private val nowNanos: () -> Long) {

    private val lock = Any()
    private var accumNanos = 0L
    private var offAtNanos = -1L   // -1 = 当前亮屏

    /**
     * 灭屏：记下窗口起点。
     *
     * **重复 off 幂等 —— 保留最早起点**：若窗口起点被后移（连续两次 SCREEN_OFF，或冷启已置
     * 起点后又来一条 off 广播），冻结时长会**少计** ⇒ `runnable` 偏大 ⇒ **误掐**。
     * 少计会误掐、多计只放宽 —— 故方向必须取「保留最早」。
     */
    fun onScreenOff() {
        synchronized(lock) { if (offAtNanos < 0) offAtNanos = nowNanos() }
    }

    /**
     * 亮屏：把整段灭屏窗口累加进 [accumNanos] 并清窗口。
     * @return `(delta, total)` 对（W80-05）：`delta` 为本次累加的纳秒（无在途窗口时 0，
     *   仅供日志取证）；`total` 为**含在途窗口语义**的进程级绝对累计 —— 与 [frozenNanos]
     *   取自同一把锁内的同一状态，供调用方免去第二次取锁的额外往返。
     */
    fun onScreenOn(): Pair<Long, Long> = synchronized(lock) {
        val off = offAtNanos
        val delta = if (off >= 0) nowNanos() - off else 0L
        if (off >= 0) {
            accumNanos += delta
            offAtNanos = -1L
        }
        // 本锁内 offAt 已清零 ⇒ 含在途窗口的 total 恰为 accum（含刚累加的这段窗口）。
        delta to accumNanos
    }

    /**
     * 累计冻结纳秒，**含在途灭屏窗口**：`offAt >= 0` 时 = `accum + (now - offAt)`，
     * 保证 off→on 时刻数值连续。
     */
    fun frozenNanos(): Long = synchronized(lock) {
        val off = offAtNanos
        accumNanos + if (off >= 0) nowNanos() - off else 0L
    }
}
