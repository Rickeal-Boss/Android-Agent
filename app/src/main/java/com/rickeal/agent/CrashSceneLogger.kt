package com.rickeal.agent

import android.os.Process
import com.rickeal.agent.core.model.AgentLog
import com.rickeal.agent.core.model.AgentLogStore
import com.rickeal.agent.core.model.truncateSafe
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
 * ## 内容口径（W84-07 / A-5 订正：名义必须等于实效）
 *
 * 线程名 + 异常类与消息 + 堆栈帧（上限 [STACK_FRAME_LIMIT] 帧，实取几帧由
 * [LOG_LINE_BUDGET] 反推）+ 崩溃前最近 [RECENT_LOG_LIMIT] 条进程内日志（平时只落
 * ERROR，崩溃时全落——现场价值大于噪音成本）。全部走 `AgentLogStore.error`，
 * 脱敏在出口侧统一生效。
 *
 * 这里的两条纪律都是为了修「名义 vs 实效」的失算（W84-07 外审实证，别再留同类矛盾）：
 *  - **一条日志一行**：旧实现 4 条打一行，业务日志单条 100~150 字符 ⇒ 拼接行
 *    450~650 字符 ⇒ 出口的 400 字符截断把行尾 2~3 条**整条切掉**，名义 20 条实际
 *    只落 8~12 条。逐条 `error()` 的成本可忽略（`AgentLogFileStore` 本就逐行 append）。
 *  - **帧数由预算反推**：旧实现按 10 帧取值，而头行 + 10 帧远超 400 字符 ⇒ 截断后
 *    实际只剩 3~4 帧，常量却写着 10，后来者按 10 帧估排障深度必然落空。
 *
 * 承诺：**本对象产出的每一行都不超过 [LOG_LINE_BUDGET]**，因此出口的截断永不生效，
 * 「名义 = 实效」由构造保证（`CrashSceneLoggerTest` 有对应断言）。
 */
internal object CrashSceneLogger {

    /** 崩溃现场带入的最近日志条数。 */
    private const val RECENT_LOG_LIMIT = 20

    /**
     * 堆栈帧数**上限** —— 实际取几帧由 [LOG_LINE_BUDGET] 反推（见 [stackLine]）。
     *
     * 为什么只能当上限：一帧 `at a.b.C.m(C.kt:123)` 的长度取决于包名深度，本仓
     * `com.rickeal.agent.core.engine.local.` 这种前缀下单帧可达 ~90 字符 ⇒ 6 帧
     * ≈ 540 字符，已经越过 400 的落盘预算。「保留几帧」因此不能靠常数拍定，
     * 只能由预算反推；这个 6 是「再多对定位也没增量价值」的封顶。
     */
    private const val STACK_FRAME_LIMIT = 6

    /**
     * 单行落盘预算（字符）。
     *
     * 出口 `AgentLogStore` 的 `MAX_MESSAGE_CHARS` = 400，且它的顺序是**先脱敏再截断**
     * （`sanitize`：`secretPattern.replace` → 再比 400）。脱敏会让行**变长**
     * （`?key=x`(7) → `?key=***`(8)，每处 +1 字符）⇒ 若本对象按 400 组装，
     * 一条含 1 处 secret 的行就会在出口被截掉尾部 1 字符 —— 「本对象产出的每行都
     * 永不触发截断」这个承诺**不成立**（W84 审查 P3-2 实证）。
     *
     * 故取 **396 = 400 − 4**，把 4 字符余量留给脱敏增长（覆盖同一行 ≤4 处 secret 的
     * 常见形态）。⚠️ 如实申报边界：一行含 **>4 处** secret 的极端形态仍可能在出口被
     * 截尾 —— 那是脱敏降级后的行，可接受，且不影响「条数承诺」（一条日志始终只占一行）。
     *
     * `MAX_MESSAGE_CHARS` 是 `private`，跨模块取不到，故在此复写：**两边必须同步改**。
     */
    private const val LOG_LINE_BUDGET = 396

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
     * 行数与「名义条数」严格对应：`2 + recent.size`（头行 + 堆栈行 + 每条日志一行）。
     *
     * @param recent 崩溃前最近 N 条进程内日志（时间正序）。
     */
    internal fun crashSceneLines(
        threadName: String,
        throwable: Throwable,
        recent: List<AgentLog>,
    ): List<String> {
        val lines = ArrayList<String>(recent.size + 2)
        // cause 链进**头行**（不靠堆栈行）：printStackTrace 的 "Caused by:" 落在
        // 帧预算之外（JUnit/生产调用栈都比它深）⇒ 堆栈行里断不到它。头行带
        // 链对归因也更快（异常类 + 根因一眼看完）。take(3) 防病态环链。
        val causeChain = generateSequence(throwable.cause) { it.cause }
            .take(3)
            .joinToString(" <- ") { "${it.javaClass.name}: ${it.message}" }
        lines.add(
            fitBudget(
                "未捕获崩溃：线程=$threadName 异常=${throwable.javaClass.name}：${throwable.message}" +
                    if (causeChain.isNotEmpty()) "（cause 链：$causeChain）" else "",
            ),
        )
        lines.add(stackLine(throwable))
        // 一条日志一行（见类 KDoc「内容口径」）：换掉旧的「4 条拼一行」。
        for (log in recent) {
            lines.add(fitBudget("崩溃前日志：${log.level.name} ${log.message}"))
        }
        return lines
    }

    /**
     * 堆栈行：异常头行之外的帧，取到「再加一帧就越过 [LOG_LINE_BUDGET]」为止。
     *
     * 两处与旧实现的差别：
     *  - `drop(1)`：`stackTraceToString()` 的首行是「异常类: 消息」，与 [crashSceneLines]
     *    的头行**重复**；旧实现 `take(STACK_FRAME_LIMIT + 1)` 把它算进了帧预算，等于
     *    白花 ~30 字符买一行重复内容。丢掉后 [STACK_FRAME_LIMIT] 才真的等于「帧数」。
     *  - 行内**自报实取帧数**（「前 N 帧」）：N 是预算算出来的、不是配置值，自报实际值
     *    才不会让读日志的人按配置值估错排障深度。
     */
    private fun stackLine(throwable: Throwable): String {
        val frames = throwable.stackTraceToString()
            .lineSequence()
            .drop(1)
            // 只留真帧。`stackTraceToString()` 会把 cause / suppressed 链一起打出来，
            // 其中的 `Caused by: …` / `Suppressed: …` / `… N more` 不是栈帧 ——
            // 计进「前 N 帧」会让自报帧数虚高，且挤掉真正的顶部帧（W84 审查 P3-3）。
            // cause 的**异常类与消息**已进头行的 cause 链，这里丢掉不损失归因信息。
            .filterNot { line ->
                val body = line.trimStart()
                body.startsWith("Caused by:") ||
                    body.startsWith("Suppressed:") ||
                    body.startsWith("...")
            }
            .filter { it.isNotBlank() }
            .take(STACK_FRAME_LIMIT)
            .toList()
        // 头部预留按「上限帧数」算（≤2 位数时与实际头部等长或更长），只会更保守。
        var used = "崩溃堆栈（前 $STACK_FRAME_LIMIT 帧）：".length
        val kept = ArrayList<String>(frames.size)
        for (frame in frames) {
            val cost = frame.length + 1 // +1 = 帧间换行
            if (used + cost > LOG_LINE_BUDGET) break
            kept.add(frame)
            used += cost
        }
        // 极端情况：连第一帧都装不下（超长 lambda / 匿名类名）。宁可截一帧，也别交出
        // 一行只有表头的堆栈 —— 那对排障毫无价值。
        if (kept.isEmpty() && frames.isNotEmpty()) kept.add(frames.first())
        return fitBudget("崩溃堆栈（前 ${kept.size} 帧）：" + kept.joinToString("\n"))
    }

    /**
     * 把一行压进 [LOG_LINE_BUDGET]（代理对安全，走全仓唯一口径 [truncateSafe]）。
     *
     * 正常情况下 [stackLine] 与逐条日志已经自己控住长度，这里是最后一道硬保证：
     * 单条日志消息本身就可能接近 400 字符（出口上限），加上「崩溃前日志：ERROR 」
     * 前缀必然越线 —— 此时牺牲**这一条消息的尾部**，保住「一条一行」的条数承诺
     * （旧实现是牺牲掉整条后面的日志，性质完全不同）。
     */
    private fun fitBudget(line: String): String =
        if (line.length <= LOG_LINE_BUDGET) {
            line
        } else {
            line.truncateSafe(LOG_LINE_BUDGET - 1) + "…"
        }
}
