package com.rickeal.agent.core.agent

/**
 * 单调时钟 + 「可运行（未冻结）时长」视角。
 *
 * 国产 OEM（ColorOS / HyperOS / OriginOS 等）在灭屏约 10s 后对进程做 cgroup 级冻结：
 * 线程全停，但内核单调时钟（[System.nanoTime] / uptimeMillis）继续走。因此基于单调钟的
 * `withTimeout` 会在「冻结时长 > 剩余预算」时于解冻瞬间误触发取消 —— 表现为
 * 「模型思考中锁屏/灭屏后 run 被掐断」（W78-12）。
 *
 * [FreezeClock] 把冻结时段从计时中剔除：[runnableElapsedNanos] 只数进程真正在跑的时间，
 * 让长时间后台 / 锁屏下的生成本质上「暂停计时」，解冻后无缝续跑。
 */
interface FreezeClock {
    /** 单调刻度（纳秒），语义同 [System.nanoTime]。 */
    fun nowNanos(): Long

    /**
     * 自 [startedNanos] 起、**剔除冻结时段**后的可运行时长（纳秒）。
     * 默认实现（无冻结感知）直接返回 `nowNanos - startedNanos`。
     */
    fun runnableElapsedNanos(startedNanos: Long): Long = nowNanos() - startedNanos
}

/** 无冻结感知的默认实现（JVM 单测 / 无 Android 环境 / 默认注入值）。 */
object MonotonicFreezeClock : FreezeClock {
    override fun nowNanos(): Long = System.nanoTime()
}
