package com.rickeal.agent.core.data.background

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.rickeal.agent.core.model.AgentLogStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 「run 忙 → 起前台服务，run 闲 → 宽限后停」的控制器（W77）。
 *
 * ## 为什么观察 `isBusy` 而不是改 run 的宿主
 *
 * `AgentRunner.isBusy` 是既有**单一事实源**（与 runMutex 同源，W4 C-P0-1 定案），
 * 且 `AppContainer` 里已有「应用级低频 collect 它」的成熟范式（`ThermalGovernor`
 * 的补释放接线）。复用这条范式意味着：
 * - `feature-chat` / `core-agent` / `core-engine` **零改动**（内核冻结面不碰）；
 * - 引擎重建、`ask_actor` 子 run、连续发送这些会让 `isBusy` 短暂翻转的路径，
 *   被 [RunForegroundPolicy.GRACE_MILLIS] 统一吸收，不需要各自打补丁。
 *
 * ## 启动时机的合规性
 *
 * FGS 只在 `isBusy` **由假转真**的那一刻启动，而这一刻必然发生在用户点「发送」之后、
 * Activity 仍在前台 ⇒ 不触发 Android 12+ 的「后台启动 FGS」限制。
 * 万一仍抛 `ForegroundServiceStartNotAllowedException`（极窄竞态），降级为
 * 「本次 run 没有保活」并留痕 —— 不重试、不把 App 打崩。
 */
class AgentRunForegroundController(
    private val context: Context,
    private val isBusy: StateFlow<Boolean>,
    private val scope: CoroutineScope,
    private val smallIconRes: Int,
) {

    private var observeJob: Job? = null

    /**
     * 「服务当前应当在跑」。
     *
     * 只在 collect 协程里写、在 [stop] 里读（可能来自主线程）⇒ `@Volatile`。
     * 它**不用于**判断服务进程是否真的活着 —— 那种判断要靠 `ActivityManager`，
     * 而且没有意义：服务被系统杀掉时，下一次 busy 翻转会重新拉起。
     */
    @Volatile
    private var started: Boolean = false

    /** 幂等：重复调用不会挂两个观察协程。 */
    fun start() {
        if (observeJob != null) return
        observeJob = scope.launch {
            isBusy.collectLatest { busy ->
                if (RunForegroundPolicy.shouldStart(busy, started)) {
                    startService()
                } else if (RunForegroundPolicy.shouldScheduleStop(busy, started)) {
                    // 宽限窗：窗口内新 run 会把 collectLatest 的块取消掉 ⇒ 服务续用，
                    // 不出现「停一下又起」的启停风暴（真机表现为通知闪烁 + 声音）。
                    delay(RunForegroundPolicy.GRACE_MILLIS)
                    stopService()
                }
            }
        }
    }

    /** 进程级容器没有「关闭」语义，本方法只给测试与显式收尾用。 */
    fun stop() {
        observeJob?.cancel()
        observeJob = null
        if (started) stopService()
    }

    private fun startService() {
        started = true
        val intent = Intent(context, AgentForegroundService::class.java)
            .putExtra(RunForegroundPolicy.EXTRA_SMALL_ICON, smallIconRes)
        runCatching { ContextCompat.startForegroundService(context, intent) }
            .onFailure { throwable ->
                started = false
                // 降级 = 回到 W77 之前的现状（无保活），不是错误；但必须可查，
                // 否则「切后台就停」会变成没有解释的玄学。
                AgentLogStore.warn(
                    "后台保活服务启动被拒（${throwable.javaClass.simpleName}）：本次 run 切后台后可能被冻结",
                )
            }
    }

    private fun stopService() {
        started = false
        runCatching { context.stopService(Intent(context, AgentForegroundService::class.java)) }
    }
}
