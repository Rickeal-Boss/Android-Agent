package com.rickeal.agent

import android.os.Process
import com.rickeal.agent.core.model.AgentLog
import com.rickeal.agent.core.model.AgentLogStore
import java.io.PrintWriter
import java.io.StringWriter
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

/**
 * 未捕获崩溃的现场留痕（W83-08；外审 P2-7 的收窄版）。
 *
 * ## 为什么需要它
 * `AgentLogStore` 的 ERROR 级已经能经 `AgentLogFileStore` 落盘（杀进程可查），
 * 但那只覆盖**主动埋点**——崩溃瞬间的堆栈本身没有任何采集点：线程炸死前没人把它
 * `error()` 进日志，200 条内存环随进程一起消失。症状 = 用户报「闪退」，诊断页
 * 只有崩溃前几条业务日志，**没有堆栈**，归因只能靠猜。
 *
 * ## 设计要点
 *  - **装在 Application.onCreate 顶部**（instance 赋值后）：再早的窗口（类加载 /
 *    字段初始化）本仓无可崩代码（container 是惰性的），框架级崩溃也管不了。
 *  - **同步落盘**：在链式转交原 handler **之前**写完。`AgentLogFileStore.append`
 *    是 appendText + close（数据进内核页缓存），进程崩溃不丢；异步写会把
 *    「崩溃前最后一条」一起丢掉。
 *  - **链式转交**：装 handler 不是替换系统行为——记完必须转交原默认 handler
 *    （弹「已停止运行」+ 杀进程）。previous 为 null 时（有人先卸掉了默认 handler）
 *    自行杀进程，**绝不能吞**：吞了崩溃线程静默死掉而进程活着，比崩溃更难查。
 *  - **防重入**：留痕路径自身再炸（如落盘 IO）时不能再进一次，否则无限递归。
 *  - **sink 兜底**：container 是惰性的，崩溃若发生在容器初始化之前，
 *    `AgentLogStore` 的 sink 还是 null ⇒ `error()` 只进内存环、进程一死即失。
 *    故 record 前先 `runCatching { containerOrNull() }` 触碰容器（其 init 会
 *    `agentLogFileStore.install()`）；构造容器本身也炸时照常走链式转交。
 *
 * ## 内容口径
 * 线程名 + 异常类与消息 + 堆栈前 [STACK_FRAME_LIMIT] 帧 + 崩溃前最近
 * [RECENT_LOG_LIMIT] 条进程内日志（平时只落 ERROR，崩溃时全落——现场价值
 * 大于噪音成本）。全部走 `AgentLogStore.error`：sanitize（脱敏 + 400 字符
 * 截断）在出口侧统一生效，堆栈因此天然只保留最有定位价值的顶部帧。
 */
internal object CrashSceneLogger {

    /** 崩溃现场带入的最近日志条数。 */
    private const val RECENT_LOG_LIMIT = 20

    /** 每条日志行打包几条（20 条 ÷ 4 = 5 行，单条经 sanitize 截断后仍在量级内）。 */
    private const val RECENT_LOGS_PER_LINE = 4

    /** 堆栈保留帧数（异常头行 + 前 10 帧；全栈会被 sanitize 的 400 字符截断，无增量价值）。 */
    private const val STACK_FRAME_LIMIT = 10

    /** 防重入闸：留痕路径自身异常时短路（进程本就活不了，别无限递归）。 */
    private val recording = AtomicBoolean(false)

    fun install() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            if (recording.compareAndSet(false, true)) {
                runCatching { record(thread, throwable) }
            }
            if (previous != null) {
                previous.uncaughtException(thread, throwable)
            } else {
                Process.killProcess(Process.myPid())
                exitProcess(10)
            }
        }
    }

    private fun record(thread: Thread, throwable: Throwable) {
        // sink 兜底（见类 KDoc）：触碰惰性容器以确保落盘出口在位。
        runCatching { LiquidAgentApplication.containerOrNull() }
        val lines = crashSceneLines(thread.name, throwable, AgentLogStore.recent(RECENT_LOG_LIMIT))
        for (line in lines) {
            AgentLogStore.error(line)
        }
    }

    /**
     * 崩溃现场 → 待落盘行（**纯函数**：不碰 `AgentLogStore` 单例、不碰 Android，
     * JVM 单测直接钉格式与截断）。[record] 只是把每行 `error()` 出去。
     *
     * @param recent 崩溃前最近 N 条进程内日志（时间正序）。
     */
    internal fun crashSceneLines(
        threadName: String,
        throwable: Throwable,
        recent: List<AgentLog>,
    ): List<String> {
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        val frames = sw.toString().lineSequence().take(STACK_FRAME_LIMIT + 1).joinToString("\n")
        val lines = ArrayList<String>()
        // cause 链进**头行**（不靠堆栈行）：printStackTrace 的 "Caused by:" 落在
        // 前 10 帧之外（JUnit/生产调用栈都比 10 帧深）⇒ 堆栈行里断不到它。头行带
        // 链对归因也更快（异常类 + 根因一眼看完）。take(3) 防病态环链。
        val causeChain = generateSequence(throwable.cause) { it.cause }
            .take(3)
            .joinToString(" <- ") { "${it.javaClass.name}: ${it.message}" }
        lines.add(
            "未捕获崩溃：线程=$threadName 异常=${throwable.javaClass.name}：${throwable.message}" +
                if (causeChain.isNotEmpty()) "（cause 链：$causeChain）" else "",
        )
        lines.add("崩溃堆栈（前 $STACK_FRAME_LIMIT 帧）：$frames")
        if (recent.isNotEmpty()) {
            recent.chunked(RECENT_LOGS_PER_LINE).forEach { chunk ->
                lines.add("崩溃前日志：" + chunk.joinToString(" ‖ ") { "${it.level.name} ${it.message}" })
            }
        }
        return lines
    }
}
