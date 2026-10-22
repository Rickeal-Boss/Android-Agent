package com.rickeal.agent.core.data.background

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.rickeal.agent.core.agent.FreezeClock
import java.util.concurrent.atomic.AtomicLong

/**
 * 屏幕状态监测 + 冻结时段累计（W78-12）。
 *
 * 通过监听 `ACTION_SCREEN_ON` / `ACTION_SCREEN_OFF` 近似 OEM 冻结窗口：ColorOS 在灭屏约
 * 10s 后才冻结、亮屏约 3s 后解冻。把整段灭屏期都算作「冻结」是**安全的过近似** ——
 * 只让计时更宽松，绝不会误掐一个在跑的生成（语义与 [RunWakeLockKeeper] 的「续期不能对抗
 * 冻结」结论一致：冻结期间协程不执行，解冻后无缝续跑）。
 *
 * 累计用加法：灭屏记下 `offAtNanos`，亮屏时把 `(now - offAt)` 累加进 `screenOffAccumNanos`。
 * 进程被杀重启时累计归零，但新 run 的 `startedNanos` 也重新起算，口径自洽。
 *
 * ⚠️ 监听注册必须在持有 Looper 的线程（Application 主线程）执行，否则收不到广播。
 * 生命周期与 AppContainer 同终（进程级单例，不注销），与 [RunWakeLockKeeper] / [ThermalGovernor]
 * 同口径。
 */
internal class ScreenStateMonitor(context: Context) : FreezeClock {

    private val screenOffAccumNanos = AtomicLong(0L)
    private val offAtNanos = AtomicLong(-1L)

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    offAtNanos.set(System.nanoTime())
                    Log.d(TAG, "screen OFF — freeze window started")
                }
                Intent.ACTION_SCREEN_ON -> {
                    val off = offAtNanos.getAndSet(-1L)
                    if (off >= 0) {
                        val delta = System.nanoTime() - off
                        screenOffAccumNanos.addAndGet(delta)
                        Log.d(TAG, "screen ON — froze for ${delta / 1_000_000}ms (accum=${screenOffAccumNanos.get() / 1_000_000}ms)")
                    }
                }
            }
        }
    }

    init {
        // 进程在「屏幕已灭」时冷启（如被冻结期间被杀后重启）：把当前灭屏段也计入冻结窗口，
        // 否则首段冻结会被误算作可运行（offAtNanos 默认 -1，SCREEN_ON 不触发累加 ⇒ 该段空白）。
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (powerManager?.isInteractive == false) {
            offAtNanos.set(System.nanoTime())
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        // 进程内接收，不被外部唤起；API 33+ 必须显式 RECEIVER_NOT_EXPORTED（ContextCompat 跨版本安全）。
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun nowNanos(): Long = System.nanoTime()

    override fun runnableElapsedNanos(startedNanos: Long): Long =
        nowNanos() - startedNanos - screenOffAccumNanos.get()

    private companion object {
        const val TAG = "LiquidAgent/Freeze"
    }
}
