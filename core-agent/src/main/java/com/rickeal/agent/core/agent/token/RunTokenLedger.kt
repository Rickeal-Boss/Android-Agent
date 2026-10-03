package com.rickeal.agent.core.agent.token

import com.rickeal.agent.core.model.TokenUsage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * run 级 token 账本快照（Wave 30 RunTokenLedger 的读侧投影）。
 *
 * 两个口径**刻意分开**，不做换算也不做对账：
 * - [sentTokens]：发送侧估算累计（[com.rickeal.agent.core.model.TokenEstimator] 口径），
 *   由 AgentRunner 发送侧记账块回写 —— 是「我们打算给模型多少上下文」的预估；
 * - [cumulativeIn] / [cumulativeOut]：**本仓自算**的 prompt / completion 累计，来自
 *   `LiteRtLmEngine` 构造的 [TokenUsage]（本仓唯一产出点）—— `promptTokens` 是
 *   [com.rickeal.agent.core.model.TokenEstimator] 的**估算**，`completionTokens` 是
 *   **内容 chunk 帧计数**（流式回调里非空正文增量的帧数，**不是 token**）。
 *   **引擎从不回报 usage**（native 侧无此出口），故它不是「引擎真实吃了多少」。
 *   两者的偏差（估算 vs 帧计数）仍是观测信号，但其观测力**弱于**「估算 vs 真实」——
 *   帧数与 token 数在字节级 BPE 下并不等价；在这里合并或校准只会制造第三种口径。
 *
 * 「只增不减」是语义目标而非实现约束：压缩触发全量重记时 sentTokens 会如实回落，
 * 投影方不做二次加工（见 [RunTokenLedger.onSendEstimated] KDoc）。
 */
data class RunTokenSnapshot(
    val sentTokens: Long = 0L,
    val cumulativeIn: Long = 0L,
    val cumulativeOut: Long = 0L,
    /**
     * 最后一次回写的**进程墙钟**（[System.currentTimeMillis]）。
     *
     * ⚠️ 它是墙钟、不是流逝耗时：不要拿它与 `RunState.elapsedMillis()`（run 级相对毫秒）
     * 做差值或比较 —— 两者原点不同、量级也不同。字段原名 `updatedAtWallClockMillis`
     * 沿用了方案 §2.3 的命名（Wave 30 复审 2 改名），那个名字会诱导上述误用。
     *
     * 0 表示「该账本从未被回写过」。语义细节见 [InMemoryRunTokenLedger] 类头。
     */
    val updatedAtWallClockMillis: Long = 0L,
) {
    /**
     * 引擎回报侧的真实消耗（进+出），与估算口径的 [sentTokens] 并列呈现。
     *
     * ⚠️ 它是 `cumulativeIn + cumulativeOut` 的**派生口径**，不是引擎自己报的总量：
     * [TokenUsage.totalTokens] 由引擎自算，可能含 cached / reasoning 等不计入
     * prompt+completion 的部分，二者**未必相等**。因此 UI 上不要把本值与
     * `usage.totalTokens` 并列展示或相减对比 —— 那会制造第四种口径。要对比就
     * 固定用本派生口径（口径纯净优先于与引擎对齐）。
     */
    val engineTotalTokens: Long get() = cumulativeIn + cumulativeOut
}

/**
 * run 级 token 账本（Wave 30）。纯读侧投影：主循环的 [com.rickeal.agent.core.agent.AgentRunner]
 * [com.rickeal.agent.core.agent.RunState.sentTokens] 记账块**保持不变**（Wave 29 A1 刚
 * 终审的 24 字段结构不动），账本只在其后镜像回写 —— 防止重开 A1 Step 2 的 review 面。
 *
 * 接线纪律（KDoc 红线）：AgentRunner 中 sentTokens → 账本的回写点是**全仓唯一**的；
 * 今后任何新的 sentTokens 写点必须同步回写，否则 UI / 后续断路器（TokenBudget）消费的
 * 口径会静默漂移。
 *
 * 消费顺序约定（Wave 30 方案 §2.3）：本波账本只做读侧投影，摘要触发（B.4 双阈值）与
 * 压缩门控口径**不接** —— 摘要门控架构上不能安全接线（runMutex 不可重入），压缩门控
 * 的挂账口径见 AgentRunner 记账块 KDoc 的「只写不读」自认。
 *
 * 纯 Kotlin：StateFlow 来自 kotlinx.coroutines（core-agent 既有依赖），TokenUsage 来自
 * core-model（@Serializable 纯 Kotlin）—— 无任何 Android 类型，可 JVM 直接单测
 * （arch-guard 第 10 条：core-agent 不依赖 core-data / framework）。
 */
interface RunTokenLedger {
    /** 账本快照流。每次回写后 value 立即更新（同线程读无需等待）。 */
    val snapshot: StateFlow<RunTokenSnapshot>

    /**
     * 发送侧估算回写。AgentRunner 发送侧记账块是**唯一调用点**。
     *
     * 参数语义是「当前累计总量」而非本轮增量：实现方**覆盖写**（镜像口径），
     * 不做累加 —— 压缩触发全量重记时调用方给的值可能小于旧累计，投影必须如实
     * 镜像而不是被「只增不减」钳住（否则账本会与 sentTokens 永久分叉）。
     */
    fun onSendEstimated(totalSentTokens: Long)

    /**
     * 引擎回报回写。AgentRunner 生成完成分诊段（accumulator.usage 落 state.lastUsage
     * 处）调用；[usage] 为 null（引擎未回报）时跳过，不产生任何写入。
     */
    fun onEngineUsage(usage: TokenUsage?)
}

/**
 * [RunTokenLedger] 的进程内实现：[MutableStateFlow] + 两个写方法。
 *
 * 线程安全：[kotlinx.coroutines.flow.update] 的 CAS 循环保证两个回写入口并发时
 * 各自的增量不丢（发送侧回写与引擎回报回写可能来自不同协程）。
 *
 * 时钟采样：[update] 的 lambda 在 CAS 重试下**会被求值多次**（其 KDoc 明示
 * "function may be evaluated multiple times"），因此 [clock] 一律在 [update] **之外**
 * 采样一次再传入 —— 否则带副作用的时钟（自增/序列钟）会在并发下被多采样，
 * 落库的时间戳不再是「本次回写的时刻」。
 *
 * 重复值不刷下游：[MutableStateFlow] 自带 **Strong equality-based conflation**
 * （[Any.equals] 比较，等价于一层的 `distinctUntilChanged`），
 * 同值回写既不换实例也不发射 —— 因此读侧**不需要**再套一层 distinctUntilChanged。
 * 注意 [RunTokenSnapshot] 含时间戳字段：只要时钟前进，即便 token 数完全没变也会发射
 * （这是「最后活跃时刻」语义的应有之义，不是噪音）。
 *
 * ⚠️ 生命周期错配（run 级 vs 会话级）—— **已按「第三条路」接线解决，原红线作废**：
 * 本实现被 AppContainer 按 **conversationId** 池化、跨 run 存活，而
 * [RunTokenSnapshot.sentTokens] 镜像的 RunState.sentTokens 是**run 级**
 * （每轮新建、从 0 起）。后果是两条字段的时间窗不一致：sentTokens 靠覆盖写天然
 * 跟得住（新 run 首轮回写即归零重来），而 [RunTokenSnapshot.cumulativeIn] /
 * [RunTokenSnapshot.cumulativeOut] 单调累加、跨 run 永不归零 —— 自第二次 run 起，
 * 「估算 vs 真实」的双口径**对比**就不再可比（估算侧是本次 run，引擎侧是历史全部 run）。
 *
 * **当初为什么挂这条红线**：写注解时账本**没有消费方**（只有只读侧投影），一旦接线
 * 就会立刻把「跨 run 累计」当成本轮值读出去，故要求接线前先解决错配，并提出两个方案
 * —— ① 账本改按 run 实例化；② 新增 run 起点重置入口、由 AgentRunner 在 run 头调用。
 *
 * ✅ **实况：接线走的是第三条路，上面两个方案均已作废**（Wave 31 流2 落地，
 * Wave 39 复核仍在用）。消费方（Wave 39 时位于
 * `feature-chat/src/main/java/com/rickeal/agent/feature/chat/ChatViewModel.kt`
 * 的 `observeTokenLedger`）**只消费 `sentTokens` 这一个 run 级口径**，从不消费
 * cumulativeIn / cumulativeOut；双口径在 UI 上只是**并列展示、不换算不对账**
 * （`ChatContextMeter` 的「估算≈ / 实测」）。错配因此**没有暴露面**。
 * 至于 StateFlow 订阅立即重放上一轮残留值的问题，消费方也是用账本自己的时间戳做基线
 * 滤掉的（`snap.updatedAtWallClockMillis > baseline`，baseline 取订阅前的
 * `ledger.snapshot.value.updatedAtWallClockMillis`），同样**没有**给账本加 reset API。
 * ⇒ 本实现**不需要** reset API、也**不需要**改成按 run 实例化；方案 ①② 不要再捡起来做。
 *
 * ⚠️ **这条结论的前提是「消费方不读累计口径」**：将来任何人要拿 cumulativeIn /
 * cumulativeOut 当「本轮引擎用量」、或做「估算 vs 真实」对账，错配会**立刻复活** ——
 * 届时必须重新引入 run 级锚点（按 run 实例化，或 run 头清零），不要沿用本注解的结论。
 *
 * 时间戳口径：账本按会话池化（AppContainer），**没有 run 起点锚**，因此
 * [RunTokenSnapshot.updatedAtWallClockMillis] 记录的是最后一次写入的进程墙钟
 * （[System.currentTimeMillis]）——语义是「这个账本最后活跃在什么时候」，不参与
 * 任何预算/耗时计算（字段名已由方案 §2.3 的 `updatedAtWallClockMillis` 改为如实申报的
 * 现名：它装的是墙钟，不是流逝耗时）。
 * 测试可注入 [clock] 钉死时间行为。
 */
class InMemoryRunTokenLedger(
    private val clock: () -> Long = System::currentTimeMillis,
) : RunTokenLedger {

    private val _snapshot = MutableStateFlow(RunTokenSnapshot())
    override val snapshot: StateFlow<RunTokenSnapshot> = _snapshot.asStateFlow()

    override fun onSendEstimated(totalSentTokens: Long) {
        val now = clock()
        _snapshot.update { it.copy(sentTokens = totalSentTokens, updatedAtWallClockMillis = now) }
    }

    override fun onEngineUsage(usage: TokenUsage?) {
        if (usage == null) return
        val now = clock()
        _snapshot.update {
            it.copy(
                cumulativeIn = it.cumulativeIn + usage.promptTokens,
                cumulativeOut = it.cumulativeOut + usage.completionTokens,
                updatedAtWallClockMillis = now,
            )
        }
    }
}
