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
import kotlin.math.roundToLong

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
 *   advertisedMem，口径纪律与 AppContainer.availableMemoryBytes 同款）。5s 粒度
 *   （W81 审查 P2-1：getMemoryInfo 是跨进程 binder，与 PSS 同款降频，两次之间沿用旧值）。
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
 * （ChatRunCoordinator try/finally）。空闲零线程 —— 「物理断路器省电」的立意不允许
 * App 启动即常驻 1s 采样线程（方案 A 被否的理由）。
 *
 * - [acquire] 幂等（计数 0→1 时创建线程）；[release] 归零即停（interrupt + join(1000)，
 *   绝不用已废弃的 `Thread.stop()`）。join 在后台 reaper 线程里做 —— [release] 常从
 *   UI 线程调用（诊断页 DisposableEffect），就地 join 有钉住主线程的风险（详见
 *   stopWorker 的注释）。
 * - 窗口清空语义由 [acquire] 的 `clearSamples` 决定（W81 审查 P1-1）：run 窗口
 *   （true）每次 acquire 都重置，页面窗口（false）不清空 —— 详见 [acquire] KDoc。
 * - 线程为 **daemon** + 命名 `perf-sampler`：进程退出不被拖延，泄漏判定可用
 *   `adb shell ps -T <pid>` 验证（真机验收项）。
 * - [reason] 落日志：谁拿了没放，事后可查（引用计数的经典病灶）。
 * - 归一化防御：release 过计数（<0）按 0 钳制并记 warn —— 多 release 不崩，但要留痕。
 * - 已知微小竞态（可接受，不加锁）：release 归零（旧线程 join 中未死）与 acquire 到 1
 *   同时发生时，两个采样线程会短暂共存，旧线程可能在 startWorker 清空之后再写一个
 *   样本进新窗口。影响上限是一个多写样本，不值得为此给整条路径加锁。同款竞态对
 *   clearSamples=true 的重置同样成立（W81）：页面开着时新 run 的 acquire 重置与
 *   运行中采样线程交叠，重置后、下一帧采样前旧线程可能补写一个上一窗口的样本 ——
 *   上限仍是一个样本，不加锁。
 *
 * 数据出口：[samples] StateFlow（环形 180 个 = 3 分钟 @1s，超限 removeAt(0) 移除最旧 —— 与
 * BLOCK_CYCLE_HISTORY 同款环形纪律）+ [latest]（GenerationNotifier 通知文案的低开销读点）。
 *
 * @param context 仅用于取 [ActivityManager.MemoryInfo]。可空是**测试缝**：JVM 单测无
 *   Robolectric、构造不出 Context，故允许 `null`（此时可用内存恒为 0，采样其余部分照跑）；
 *   生产路径（AppContainer）恒传非 null。
 */
class PerformanceMonitorManager(private val context: Context?) {

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

    /**
     * 采样线程句柄。@Volatile：主线程 [startWorker]/[stopWorker] 与采样线程异常自清
     * （[sampleLoop] catch 分支置 null）三方跨线程读写，缺可见性会让下一次 acquire
     * 读到陈旧句柄（进而漏 join 旧线程）。
     */
    @Volatile
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
    /** 上一次成功读取的可用内存（W81 P2-1：5s 粒度，两次采样之间沿用）。仅采样线程读写。 */
    private var lastAvailMemBytes = 0L
    private var sampleCount = 0L

    /** /proc 失败是否已在**本窗口**内留过 warn（防 1s 一次刷日志）。仅采样线程读写。 */
    private var procWarned = false

    /**
     * 采样线程句柄观测口 —— **仅供 JVM 单测**（`PerfLifecycleTest`），生产代码禁止依赖。
     *
     * 单测只断言确定性不变量：句柄的有/无、是否为同一实例（acquire 幂等、release 归零）。
     * **不**断言「线程已死亡」：reaper 是异步 join，等死亡必然偶发失败；线程是否真的
     * 退出由真机 `adb shell ps -T <pid>` 验收。
     */
    internal fun workerHandleForTest(): Thread? = worker

    /**
     * 进入观测窗口（幂等）。首 acquiring 启动 daemon 采样线程。
     *
     * ## 窗口清空语义（W81，审查 P1-1）
     *
     * 旧实现无条件清空样本（在 [startWorker] 里），时序缺陷：run 结束 release → 用户开
     * 诊断页 acquire → 0→1 → 再次清空 ⇒「刚跑完想看这次性能」看到几乎空的曲线，要等
     * 3 分钟才填满。故清空责任移交给本参数：
     *
     * @param reason 窗口持有者标识（落日志：谁拿了没放，事后可查）。
     * @param clearSamples true = **run 窗口**：清空旧样本 + 重置采样基线（见
     *   [resetWindow]），且**每次** acquire 都生效（不只是 0→1）—— 页面开着时新 run
     *   开始也必须是「这次的」新鲜窗口，否则用户看到的是上一个窗口的尾巴；
     *   false = **页面窗口**：不清空，靠 180 环形自然淘汰 —— 「run 结束后开诊断页」
     *   的数据不允许被 0→1 的线程重建抹掉。
     *   签名**不加默认值**（本项目纪律：签名显式即契约）：新调用点必须显式表态，
     *   防静默踩错窗口语义。
     */
    fun acquire(reason: String, clearSamples: Boolean) {
        val n = interests.incrementAndGet()
        if (n == 1) {
            AgentLogStore.info("性能采样窗口开启（$reason）：启动 perf-sampler 线程")
            startWorker(clearSamples)
        } else if (clearSamples) {
            // 线程已在跑（页面窗口等其它观测者在先）：不重建线程，但窗口必须是「这次
            // run 的」—— 与 startWorker 的 clear 路径同款重置（见 [resetWindow]）。
            AgentLogStore.info("性能采样窗口重置（$reason）：沿用运行中的 perf-sampler 线程")
            resetWindow()
        }
    }

    /** 退出观测窗口（幂等）。归零即停线程：UI 线程调用也立即返回（join 走后台，见 stopWorker 注释）。 */
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

    /**
     * 重置采样窗口：清空样本 + 采样基线归零（[startWorker] 与 [acquire] 的
     * clearSamples 路径共用）。
     *
     * 基线必须与样本一起重置：lastCpuSeconds / lastAtElapsedMillis / lastMachineJiffies
     * 是增量分母，旧窗口的尾巴会让新窗口第一个样本算出巨大的 Δ（cpuPercent 冲到 100 的
     * 假象）；sampleCount 归零让 PSS 降频计数重新对齐（每 5 次采 1 次从新窗口头开始）；
     * procWarned 归零让「本窗口首错留痕」对新窗口重新生效。
     */
    private fun resetWindow() {
        _samples.value = emptyList()
        lastCpuSeconds = 0.0
        lastAtElapsedMillis = 0L
        lastMachineJiffies = null
        lastPssKb = 0L
        lastAvailMemBytes = 0L
        sampleCount = 0
        procWarned = false
    }

    /**
     * 创建并启动采样线程。窗口清空完全由调用方的 [acquire] clearSamples 决定 ——
     * 旧实现在本方法里无条件清空，会把「run 结束 → 开诊断页」时 0→1 重建前刚采完的
     * 数据抹成空曲线（W81 审查 P1-1），故清空责任只保留 clearSamples=true 分支。
     */
    private fun startWorker(clearSamples: Boolean) {
        if (clearSamples) resetWindow()
        val thread = Thread({ sampleLoop() }, "perf-sampler")
        thread.isDaemon = true
        // 先登记再 start：若线程瞬时就异常自清（catch 分支置 worker=null），
        // start 之后回填会把已死线程写回句柄，导致后续漏停。
        worker = thread
        thread.start()
    }

    /**
     * 停线程：句柄就地摘除，interrupt + join 挪到后台 reaper 线程做。
     *
     * 为什么不就地 join：release 常从 UI 线程调用（诊断页 DisposableEffect 的
     * onDispose），一旦采样线程卡在 /proc 读（OEM SELinux 异常等），join(1000) 会把
     * 主线程钉住 1s。采样线程是 daemon 且循环每次都查 isInterrupted，interrupt 后
     * 必然自行退出 —— 不需要调用方等它。
     *
     * 超时语义不变：join 超时（线程仍存活）记 warn 留痕，不重试、不升级（daemon 线程
     * 随进程回收，不会拖住退出）。
     */
    private fun stopWorker() {
        val thread = worker ?: return
        worker = null
        Thread({
            thread.interrupt()
            runCatching { thread.join(JOIN_TIMEOUT_MILLIS) }
            if (thread.isAlive) {
                AgentLogStore.warn("perf-sampler 未在 ${JOIN_TIMEOUT_MILLIS}ms 内退出（daemon 线程，随进程回收）")
            }
        }, "perf-sampler-reaper").apply { isDaemon = true }.start()
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
            // 只清自己的句柄：本线程异常退出与新窗口已 startWorker 登记了新线程可能
            // 交叠，无条件置 null 会抹掉新线程的句柄（进而漏停新线程）。
            if (worker === Thread.currentThread()) worker = null
        }
    }

    private fun takeSample() {
        // 读取失败（API 31+ /proc 收紧、OEM SELinux 策略）与解析失败（格式变更）都只
        // 跳过本样本、不终止采样；但**首错必须留痕** —— 与 clkTck 同款纪律：不出静默
        // 错数（否则诊断页 CPU 区永远空白而无从追因）。
        val statLine = nextLine(PROC_SELF_STAT)
        if (statLine == null) {
            warnProcOnce("读取 $PROC_SELF_STAT 失败")
            return
        }
        val cpuSeconds = parseProcSelfStatCpuSeconds(statLine, clkTck)
        if (cpuSeconds == null) {
            warnProcOnce("$PROC_SELF_STAT 格式异常（字段不足或非数字）")
            return
        }
        procWarned = false // 恢复正常：清标记，后续再失败仍会留痕。
        val at = SystemClock.elapsedRealtime()

        // CPU% = Δ(进程 jiffies) / Δ(整机 jiffies) × 100，上限 100（评审 B.2 口径）。
        // /proc/stat 首行列数异常时跳过该样本的 percent（保留 cpuSeconds，它不依赖 /proc/stat）。
        val machineJiffies = parseProcStatCpuJiffies(nextLine(PROC_STAT) ?: "")
        val cpuPercent = if (machineJiffies != null && lastMachineJiffies != null &&
            lastAtElapsedMillis > 0L
        ) {
            val machineDelta = machineJiffies - lastMachineJiffies!!
            // 秒→jiffies 的浮点往返（utime+stime 曾除过 clkTck）会引入 <1 jiffy 的误差，
            // 直接截断会把 3.9999 判成 3 —— roundToLong 保住口径。
            val procDelta = ((cpuSeconds - lastCpuSeconds) * clkTck).roundToLong()
            if (machineDelta > 0 && procDelta >= 0) {
                (procDelta.toFloat() / machineDelta * 100f).coerceIn(0f, 100f)
            } else {
                null
            }
        } else {
            null
        }

        // PSS 与可用内存 5s 粒度（R8-3 / W81 审查 P2-1）：Debug.getPss() 自身 ~ms 级 GC
        // 触发开销、getMemoryInfo 是跨进程 binder —— 两者 1s 一次太密（旧实现两个口径
        // 自相矛盾：PSS 降频、availMem 不降）。现同款每 PSS_EVERY_N 次采样取 1 次，
        // 两次之间沿用旧值。
        if (sampleCount % PSS_EVERY_N == 0L) {
            lastPssKb = runCatching { Debug.getPss().toLong() }.getOrDefault(lastPssKb)
            lastAvailMemBytes = runCatching {
                // `context?` 的 null 分支**只**来自 JVM 单测构造（本模块无 Robolectric，
                // 构造不出 Context）；生产路径恒非 null（AppContainer 注入）—— 可空是测试缝，
                // 不是生产语义。null 时 am 为 null、可用内存记 0，其余采样照跑。
                val am = context?.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                val info = ActivityManager.MemoryInfo()
                am?.getMemoryInfo(info)
                // availMem（当前可用），不用 advertisedMem（标称内存）—— 口径纪律同
                // AppContainer.availableMemoryBytes 的既有注释。
                info.availMem
            }.getOrDefault(lastAvailMemBytes)
        }
        val availMem = lastAvailMemBytes

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
        while (next.size > HISTORY_CAPACITY) next.removeAt(0)
        _samples.value = next
    }

    /**
     * /proc 失败首错留痕（自身纪律：不出静默错数）。之后每秒重试保持静默 —— 连续失败
     * 不刷日志；恢复正常（[takeSample] 成功路径）后清标记，下次失败仍可见。
     */
    private fun warnProcOnce(detail: String) {
        if (procWarned) return
        procWarned = true
        AgentLogStore.warn("性能采样跳过 CPU 指标（$detail）；PSS/可用内存仍采，诊断页 CPU 区可能为空")
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

        private const val PROC_SELF_STAT = "/proc/self/stat"

        private const val PROC_STAT = "/proc/stat"

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
