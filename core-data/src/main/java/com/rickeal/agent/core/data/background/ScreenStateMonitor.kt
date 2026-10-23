package com.rickeal.agent.core.data.background

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.rickeal.agent.core.agent.FreezeAccumulator
import com.rickeal.agent.core.agent.FreezeClock

/**
 * 屏幕状态监测 —— Android 广播适配器（W78-12；W79 收敛为纯适配）。
 *
 * 通过监听 `ACTION_SCREEN_ON` / `ACTION_SCREEN_OFF` 近似 OEM 冻结窗口：ColorOS 在灭屏约
 * 10s 后才冻结、亮屏约 3s 后解冻。把整段灭屏期都算作「冻结」是**安全的过近似** ——
 * 只让计时更宽松，绝不会误掐一个在跑的生成（语义与 [RunWakeLockKeeper] 的「续期不能对抗
 * 冻结」结论一致：冻结期间协程不执行，解冻后无缝续跑）。
 *
 * 累加逻辑（含 `offAt`/`accum` 同锁读写的双计竞态处置）上提到 `:core-agent` 的
 * [FreezeAccumulator]（纯 JVM、`nowNanos` 注入 ⇒ 可 JVM 单测）；本类只做广播 → 累加器
 * 的转接，并把 [FreezeClock.frozenNanos] 委托出去。
 *
 * 累计是**进程级事实**（进程被杀重启才归零）；**基线由每次 run 自己在起点取**
 * （[FreezeClock.runnableNanosSince] 的 `frozenBaselineNanos`）⇒ 历史冻结不会被从本轮预算
 * 扣掉，**跨 run 不污染**。
 *
 * ⚠️ 监听注册必须在持有 Looper 的线程（Application 主线程）执行，否则收不到广播。
 * 生命周期与 AppContainer 同终（进程级单例，不注销），与 [RunWakeLockKeeper] / [ThermalGovernor]
 * 同口径。
 */
internal class ScreenStateMonitor(context: Context) : FreezeClock {

    private val accumulator = FreezeAccumulator(System::nanoTime)

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    accumulator.onScreenOff()
                    Log.d(TAG, "screen OFF — freeze window started")
                }
                Intent.ACTION_SCREEN_ON -> {
                    // W80-05：total 由 onScreenOn 在同一把锁内带回，免去第二次 frozenNanos() 取锁。
                    val (delta, total) = accumulator.onScreenOn()
                    Log.d(TAG, "screen ON — froze for ${delta / 1_000_000}ms (accum=${total / 1_000_000}ms)")
                }
            }
        }
    }

    init {
        // 进程在「屏幕已灭」时冷启（如被冻结期间被杀后重启）：把当前灭屏段也计入冻结窗口，
        // 否则首段冻结会被误算作可运行（offAt 默认 -1，SCREEN_ON 不触发累加 ⇒ 该段空白）。
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (powerManager?.isInteractive == false) {
            accumulator.onScreenOff()
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        // 进程内接收，不被外部唤起；API 33+ 必须显式 RECEIVER_NOT_EXPORTED（ContextCompat 跨版本安全）。
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun nowNanos(): Long = System.nanoTime()

    override fun frozenNanos(): Long = accumulator.frozenNanos()

    private companion object {
        const val TAG = "LiquidAgent/Freeze"
    }
}
