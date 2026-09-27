package com.rickeal.agent.core.data.perf

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import com.rickeal.agent.core.model.AgentLogStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** [PerformanceMonitorManager] 的 CLK_TCK 获取来源（不出静默错数 —— 诊断页必须可见）。 */
enum class ClkTckSource {
    /** `sysconf(_SC_CLK_TCK)` syscall 成功。 */
    SYSCALL,

    /** syscall 失败（个别 OEM SELinux 策略收紧），回退 Linux 用户态默认常量 100。 */
    FALLBACK,
}

/**
 * 一次性能采样（Wave 30 §2.2 端侧裁剪版，只采 APP 一类 —— 本项目无插件无终端，
 * 网络计费字段与 UID 级流量在全仓禁网络栈的前提下无消费场景）。
 *
 * @param cpuSeconds 进程累计 CPU 时间（秒）= (utime + stime) / CLK_TCK（/proc/self/stat
 *   第 14+15 列，1-based）。单调递增，不依赖 /proc/stat。
 * @param cpuPercent 进程 jiffies 增量占整机 jiffies 增量的百分比（上限 100，与系统负载
 *   口径可比）。/proc/stat 首行格式异常时该样本跳过 percent（保留 cpuSeconds）—— null。
 * @param pssKb 主进程 PSS（Debug.getPss() 进程内自取，不走 deprecated 且有 binder 开销的
 *   ActivityManager.getProcessMemoryInfo）。5s 粒度（每 5 次采样取 1 次，两次之间沿用旧值
 *   —— Debug.getPss() 自身 ~ms 级 GC 触发开销，1s 一次太密，R8-3）。
 * @param availMemBytes 当前可用内存（ActivityManager.MemoryInfo.availMem —— 刻意不用
 *   advertisedMem，口径纪律与 AppContainer.availableMemoryBytes 同款）。
 * @param atElapsedMillis 采样时刻（SystemClock.elapsedRealtime，开机单调钟，不受 NTP 影响）。
 */
data class PerfSample(
    val cpuSeconds: Double,
    val cpuPercent: Float?,
    val pssKb: Long,
    val availMemBytes: Long,
    val atElapsedMillis: Long,
)

/**
 * 性能采样管理器（Wave 30 §2.2）。
 *
 * ## 采样线程生命周期（防泄漏的关键决断 —— 方案 B：引用计数）
 *
 * 采样窗口 = 「有观测者」的精确交集：诊断页打开（DisposableEffect）或 run 活跃
 * （ChatViewModel try/finally）。空闲零线程 —— 「物理断路器省电」的立意不允许
 * App 启动即常驻 1s 采样线程（方案 A 被否的理由）。
 *
 * - [acquire] 幂等（计数 0→1 时创建线程）；[release] 归零即停（interrupt + join(1000)，
 *   绝不用已废弃的 `Thread.stop()`）。
 * - 线程为 **daemon** + 命名 `perf-sampler`：进程退出不被拖延，泄漏判定可用
 *   `adb shell ps -T <pid>` 验证（真机验收项）。
 * - [reason] 落日志：谁拿了没放，事后可查（引用计数的经典病灶）。
 * - 归一化防御：release 过计数（<0）按 0 钳制并记 warn —— 多 release 不崩，但要留痕。
 *
 * 数据出口：[samples] StateFlow（环形 180 个 = 3 分钟 @1s，超限 removeFirst —— 与
 * BLOCK_CYCLE_HISTORY 同款环形纪律）+ [latest]（GenerationNotifier 通知文案的低开销读点）。
 */
class PerformanceMonitorManager(private val context: Context) {

    private val clkTckSource: ClkTckSource
    private val clkTck: Long

    init {
        // CLK_TCK 口径（本波唯一权威）：syscall 失败兜底 100（Linux 用户态默认，
        // `getconf CLK_TCK` 在全部主流 Android 内核上为 100），并标注来源 —— 不出静默错数。
        val fromSyscall = runCatching { Os.sysconf(OsConstants._SC_CLK_TCK) }
            .getOrNull()?.takeIf { it > 0 }
        if (fromSyscall != null) {
            clkTck = fromSyscall
            clkTckSource = ClkTckSource.SYSCALL
        } else {
            clkTck = 100L
            clkTckSource = ClkTckSource.FALLBACK
            AgentLogStore.warn("CLK_TCK syscall 失败，回退默认 100（采样将标注 FALLBACK）")
        }
    }

    /** CLK_TCK 数值（诊断页展示用）。 */
    val clkTckValue: Long get() = clkTck

    /** CLK_TCK 来源（诊断页展示用 —— FALLBACK 时用户能看到数字是怎么来的）。 */
    val clkTckSourceLabel: String get() = "${clkTckSource.name} ($clkTck)"

    private val interests = AtomicInteger(0)
    private var worker: Thread? = null

    private val _samples = MutableStateFlow<List<PerfSample>>(emptyList())
    val samples: StateFlow<List<PerfSample>> = _samples.asStateFlow()

    /** 最新样本（GenerationNotifier 文案读点；无观测窗口时为 null，调用方零开销）。 */
    val latest: PerfSample? get() = _samples.value.lastOrNull()

    /** 上一次成功采样的进程 CPU 秒 / 时刻（增量分母）。非 volatile：仅采样线程读写。 */
    private var lastCpuSeconds = 0.0
    private var lastAtElapsedMillis = 0L
    private var lastMachineJiffies: Long? = null
    private var lastPssKb = 0L
    private var sampleCount = 0L

    /** 进入观测窗口（幂等）。首 acquiring 启动 daemon 采样线程。 */
    fun acquire(reason: String) {
        val n = interests.incrementAndGet()
        if (n == 1) {
            AgentLogStore.info("性能采样窗口开启（$reason）：启动 perf-sampler 线程")
            startWorker()
        }
    }

    /** 退出观测窗口（幂等）。归零即 interrupt + join 停线程。 */
    fun release(reason: String) {
        val n = interests.decrementAndGet()
        if (n < 0) {
            // 多 release：钳回 0 留痕，不崩 —— 但这说明调用方的配对写错了，必须可查。
            interests.set(0)
            AgentLogStore.warn("性能采样计数归负（release \"$reason\"），已钳回 0 —— 调用方 acquire/release 未配对")
            return
        }
        if (n == 0) {
            stopWorker()
            AgentLogStore.info("性能采样窗口关闭（$reason）：perf-sampler 已停止")
        }
    }

    private fun startWorker() {
        // 清空上一窗口的旧样本：观测窗口是「现在」，不是「上次的回放」。
        _samples.value = emptyList()
        lastCpuSeconds = 0.0
        lastAtElapsedMillis = 0L
        lastMachineJiffies = null
        sampleCount = 0
        val thread = Thread({ sampleLoop() }, "perf-sampler")
        thread.isDaemon = true
        thread.start()
        worker = thread
    }

    private fun stopWorker() {
        worker?.interrupt()
        runCatching { worker?.join(JOIN_TIMEOUT_MILLIS) }
        worker = null
    }

    private fun sampleLoop() {
        try {
            while (!Thread.currentThread().isInterrupted) {
                // sleep 是循环的心跳：interrupt 即退出（InterruptedException 穿出 while）。
                Thread.sleep(SAMPLE_INTERVAL_MILLIS)
                takeSample()
            }
        } catch (_: InterruptedException) {
            // 正常停止路径：interrupt 语义，静默退出。
        } catch (t: Throwable) {
            // 采样是观测窗口不是能力：任何未预期异常都终结线程并留 warn，
            // 绝不影响推理主流程。计数归零交由下一次 release / acquire 恢复。
            AgentLogStore.warn("性能采样线程异常终止（${t.javaClass.simpleName}: ${t.message}）")
            interests.set(0)
            worker = null
        }
    }

    private fun takeSample() {
        val cpuSeconds = parseProcSelfStatCpuSeconds(nextLine("/proc/self/stat") ?: return, clkTck)
            ?: return // 解析失败（SELinux 收紧 / 格式变更）：跳过本样本，不终止采样。
        val at = SystemClock.elapsedRealtime()

        // CPU% = Δ(进程 jiffies) / Δ(整机 jiffies) × 100，上限 100（评审 B.2 口径）。
        // /proc/stat 首行列数异常时跳过该样本的 percent（保留 cpuSeconds，它不依赖 /proc/stat）。
        val machineJiffies = parseProcStatCpuJiffies(nextLine("/proc/stat") ?: "")
        val cpuPercent = if (machineJiffies != null && lastMachineJiffies != null &&
            lastAtElapsedMillis > 0L
        ) {
            val machineDelta = machineJiffies - lastMachineJiffies!!
            val procDelta = ((cpuSeconds - lastCpuSeconds) * clkTck).toLong()
            if (machineDelta > 0 && procDelta >= 0) {
                (procDelta.toFloat() / machineDelta * 100f).coerceIn(0f, 100f)
            } else {
                null
            }
        } else {
            null
        }

        // PSS 5s 粒度（R8-3）：每 PSS_EVERY_N 次采样取 1 次，两次之间沿用旧值。
        if (sampleCount % PSS_EVERY_N == 0L) {
            lastPssKb = runCatching { Debug.getPss().toLong() }.getOrDefault(lastPssKb)
        }
        val availMem = runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val info = ActivityManager.MemoryInfo()
            am?.getMemoryInfo(info)
            // availMem（当前可用），不用 advertisedMem（标称内存）—— 口径纪律同
            // AppContainer.availableMemoryBytes 的既有注释。
            info.availMem
        }.getOrDefault(0L)

        val sample = PerfSample(
            cpuSeconds = cpuSeconds,
            cpuPercent = cpuPercent,
            pssKb = lastPssKb,
            availMemBytes = availMem,
            atElapsedMillis = at,
        )
        lastCpuSeconds = cpuSeconds
        lastAtElapsedMillis = at
        lastMachineJiffies = machineJiffies
        sampleCount++

        val next = _samples.value.toMutableList()
        next.add(sample)
        while (next.size > HISTORY_CAPACITY) next.removeFirst()
        _samples.value = next
    }

    private fun nextLine(path: String): String? = runCatching {
        File(path).bufferedReader().use { it.readLine() }
    }.getOrNull()

    companion object {
        /** 采样间隔 1s（纯文件读）。 */
        const val SAMPLE_INTERVAL_MILLIS = 1_000L

        /** 环形容量 180 = 3 分钟 @1s（与 BLOCK_CYCLE_HISTORY 的环形纪律同款）。 */
        const val HISTORY_CAPACITY = 180

        /** PSS 采样降频：每 5 次采样取 1 次（5s 粒度，R8-3）。 */
        const val PSS_EVERY_N = 5L

        private const val JOIN_TIMEOUT_MILLIS = 1_000L

        /**
         * /proc/self/stat 首行 → 进程累计 CPU 秒（纯函数，可 JVM 单测）。
         *
         * 解析坑（方案 §2.2 决断 2，/proc 解析头号错位点）：第 2 列 comm 是括号包裹的
         * 进程名，**可能含空格甚至括号** —— 必须先 `substringAfterLast(')')` 再按空白
         * 切分，切分结果的第 12 列（0-based 11）才是 stat 的第 14 列 utime，第 13 列
         * （0-based 12）是 stime。
         *
         * @return (utime + stime) / clkTck；格式异常返回 null（调用方跳过该样本）。
         */
        fun parseProcSelfStatCpuSeconds(line: String, clkTck: Long): Double? {
            if (clkTck <= 0) return null
            // comm 在第一对括号内但可能自身含括号：以**最后一个** ')' 为界。
            val afterComm = line.substringAfterLast(')', "").trim()
            val cols = afterComm.split(WHITESPACE_REGEX)
            val utime = cols.getOrNull(11)?.toLongOrNull() ?: return null
            val stime = cols.getOrNull(12)?.toLongOrNull() ?: return null
            return (utime + stime) / clkTck.toDouble()
        }

        /**
         * /proc/stat 首行（`cpu  u n s i iow irq softirq steal ...`）→ 整机 jiffies 合计
         * （纯函数，可 JVM 单测）。防御断言：首行按空白切分后列数 = 1 + 10，异常返回
         * null（调用方跳过该样本的 percent；不同内核导出列数随 CPU 数变化，这里只认
         * 10 列标准形态，宁可缺 percent 也不出错数）。
         */
        fun parseProcStatCpuJiffies(line: String): Long? {
            val parts = line.trim().split(WHITESPACE_REGEX)
            if (parts.size != 11 || parts[0] != "cpu") return null
            // sumOf 失败（非数字列）整行判废。
            return runCatching { parts.drop(1).sumOf { it.toLong() } }.getOrNull()
        }

        private val WHITESPACE_REGEX = Regex("\\s+")
    }
}
