package com.rickeal.agent.feature.chat

import com.rickeal.agent.core.agent.AgentEvent
import com.rickeal.agent.core.agent.AgentRequest
import com.rickeal.agent.core.agent.approval.ToolApprovalDecision
import com.rickeal.agent.core.agent.approval.ToolApprovalHandler
import com.rickeal.agent.core.agent.journal.AgentRunJournal
import com.rickeal.agent.core.data.AppContainer
import com.rickeal.agent.core.model.AgentLogStore
import com.rickeal.agent.core.model.ChatMessage
import com.rickeal.agent.core.model.InferenceConfig
import com.rickeal.agent.core.model.Role
import com.rickeal.agent.core.model.TokenEstimator
import com.rickeal.agent.core.model.TokenUsage
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 主对话 **run 编排**（Wave 48 真外提，从 [ChatViewModel] 整体迁出）。
 *
 * ## 职责边界
 *
 * 核心判据：**run 生命周期** = 从「用户触发一次生成」到「终态事件处理 + 落库 + 流式状态复位」。
 * 这条链整体归本类；**纯 UI 状态与输入事件**留在 [ChatViewModel]（`uiState` 的 UI 侧字段、
 * `onInputChange` / `toggle*` / `onApprovalResult` 等）。
 *
 * 迁入：run 主链（`onSend` / `onRetry` / `onSendFrom` / `onRecover` / `collectRunWithPerfWindow` /
 * `handleEvent` / `commit` / `commitAssistant` / `ensureConversation` / `firstUserText`）、流式缓冲
 * 与复位（`ensureFlushLoop` / `flushNow` / `updateStreamingUsage` / `resetStreaming*` 与 `_streaming`
 * 及其缓冲字段）、run 级状态（`persistState` / `salvageText` / `runJob` / `conversationId` /
 * `ledgerJob` / `observeTokenLedger`）、run 期设施（`approvalHandler` / `thermalRejection` /
 * `thermallyCappedConfig` / `renderMemoryIndex`）、journal 恢复三函数。
 *
 * ## 依赖注入与状态共享（**不得违反**）
 *
 * - [scope] 由 VM 传 [androidx.lifecycle.viewModelScope] —— **不新建 scope**，否则 VM 销毁时
 *   run 协程不被取消（引擎 native 会话会在后台继续跑 / 泄漏）。
 * - [uiState] 是 VM 的 `_uiState` **同一实例**（共享引用并 `update`）—— 若自持副本，VM 的
 *   `uiState` 与本类更新会**分叉**，UI 看不到流式 / 工具轨迹且无报错。
 * - engine / repository 等一律经 [container] 出口，**不新增 DI**（DI 只走 AppContainer，红线）。
 *
 * ## 🔴 硬约束（W46/W47 教训，原样继承，**禁止改动**）
 *
 * 1. [persistState] 清零**只允许**在 [collectRunWithPerfWindow] 首行 + [resetForNewConversation]；
 *    `resetStreaming` / `resetStreamingText` **严禁**清零（见 `AssistantPersistenceState` 类头 KDoc）。
 * 2. [salvageText] 与 [persistState] **同点清零**。
 * 3. `onStop` 的 thinking 快照**必须在 `resetStreaming` 之前**取（否则读恒空串，静默丢思考）。
 * 4. 三条终态路径与 `resetStreaming` / `resetStreamingText` 的**相对顺序逐条复核**：`Finished` /
 *    `Cancelled` 是 **commit 早于 reset**；`onStop` 是 **reset 早于 commit** —— **两条相反，
 *    禁止「顺手统一」**（W47 踩过的坑）。
 */
internal class ChatRunCoordinator(
    private val container: AppContainer,
    private val scope: CoroutineScope,
    private val uiState: MutableStateFlow<ChatUiState>,
    internal var conversationId: String?,
) {

    /** 流式通道（A 项节流）：高频字段独立流，token 变化不打挂大 StateFlow 的订阅者。 */
    private val _streaming = MutableStateFlow(StreamingState())
    val streaming: StateFlow<StreamingState> = _streaming.asStateFlow()

    /** 字符缓冲：delta 进缓冲，flush 循环 120ms 收敛一次才真正进 [StreamingState]。 */
    private val textBuffer = StringBuilder()
    private val thinkingBuffer = StringBuilder()
    private var flushJob: Job? = null

    /** 实时指标时间戳：run 启动 / 首个可见 token（TTFT 计算的两端）。 */
    private var runStartedAtMillis: Long? = null
    private var firstTokenAtMillis: Long? = null

    private var runJob: Job? = null

    /**
     * 本 run 的「助手回答落库」去重状态（Wave 46 修复「模型回复不落盘」）。
     *
     * 判据与生命周期收在 [AssistantPersistenceState]（纯逻辑，JVM 可测）；这里只持有一个
     * 实例并在**唯一正确清零点**（[collectRunWithPerfWindow] 首行 + [resetForNewConversation]）
     * 调 `beginRun()` 清零。**⛔ 不得放进 resetStreaming / resetStreamingText**（见该类头 KDoc）。
     */
    private var persistState = AssistantPersistenceState()

    /**
     * 熔断救援缓冲（Wave 47 项2）：本 run 内**用户已看到**的最后一段流式正文。
     *
     * 独立于 `_streaming` 的原因：`StreamReset` / `Retrying` 会 `resetStreamingText()` 清掉流式
     * 缓冲（设计意图：被丢弃的乱文不该交付），但**预算 / 外部型**熔断发生时，用户已经看到的那段
     * 正文不该跟着消失（真机 r5）。故本缓冲**跨 `StreamReset` / `Retrying` 存活**。
     *
     * 生命周期（与 [persistState] 同一纪律）：run 起点（[collectRunWithPerfWindow] 首行 +
     * [resetForNewConversation]）清零；**⛔ 严禁放进 resetStreaming / resetStreamingText**（那两处被
     * `Finished` / `Cancelled` 在决策之后调用，且是散点）。更新点唯一：`flushNow()`；消费点唯一：
     * `AgentEvent.Failed` 分支（判据 [shouldSalvageOutput]）。
     */
    private var salvageText: String = ""

    /** token 账本观察作业（Wave 31 流2）：把发送侧估算镜像进 [ChatUiState.sentTokensEstimate]。 */
    private var ledgerJob: Job? = null

    /** token 缓冲收敛进状态流（120ms 一次）。缓冲排空循环自然退出，不常驻。 */
    private fun ensureFlushLoop() {
        if (flushJob?.isActive == true) return
        flushJob = scope.launch {
            try {
                while (textBuffer.isNotEmpty() || thinkingBuffer.isNotEmpty()) {
                    kotlinx.coroutines.delay(STREAM_FLUSH_INTERVAL_MS)
                    flushNow()
                    updateStreamingUsage()
                }
            } finally {
                flushJob = null
            }
        }
    }

    /** 同步排空缓冲到状态流（终态收尾用：保证「半截回答不凭空消失」）。 */
    private fun flushNow() {
        val t = textBuffer.toString()
        val th = thinkingBuffer.toString()
        textBuffer.setLength(0)
        thinkingBuffer.setLength(0)
        if (t.isNotEmpty() || th.isNotEmpty()) {
            _streaming.update { it.copy(text = it.text + t, thinking = it.thinking + th) }
        }
        // 熔断救援缓冲（Wave 47 项2）：记录「用户已看到」的正文（合并后非空才覆盖），
        // 跨 StreamReset / Retrying 存活（见 [salvageText] 的 KDoc）。
        _streaming.value.text.takeIf { it.isNotEmpty() }?.let { salvageText = it }
    }

    /** 流式实时指标（E 项）：TTFT 精确；tps 用字符数粗估（中英混合按 2 字符/token）。 */
    private fun updateStreamingUsage() {
        val started = runStartedAtMillis ?: return
        val first = firstTokenAtMillis ?: return
        val decodeMs = (System.currentTimeMillis() - first).coerceAtLeast(1)
        val estimatedTokens = _streaming.value.text.length / 2
        val tps = estimatedTokens * 1000f / decodeMs
        _streaming.update { it.copy(usage = StreamingUsage(ttftMillis = first - started, tokensPerSecond = tps)) }
        // 通知侧「吐字速度」（Wave 9 需求 5）：notifier 内部有 1s 节流、enabled=false 时纯 no-op，
        // 这里可以无脑喂。这条 flush 循环只在缓冲非空时跑 ⇒ 工具阶段（无 token）自然不报速度。
        container.generationNotifier.onTick(tps, ttftMillis = first - started)
    }

    /** 清空流式文本与缓冲（Retrying/Failed：失败尝试不落库，重新来）。 */
    private fun resetStreamingText() {
        textBuffer.setLength(0)
        thinkingBuffer.setLength(0)
        _streaming.update { it.copy(text = "", thinking = "", usage = null) }
    }

    /** 完全复位流式通道（run 启动/终态收尾）。 */
    private fun resetStreaming(role: Role? = Role.MODEL, isStreaming: Boolean = false) {
        textBuffer.setLength(0)
        thinkingBuffer.setLength(0)
        runStartedAtMillis = null
        firstTokenAtMillis = null
        _streaming.value = StreamingState(role = role, isStreaming = isStreaming)
        // 终态收口（Wave 9 需求 5）：role=null 且不再流式 = **常规终态**（完成 /
        // onNewConversation / TERMINATED / CANCELLED），通知必须撤掉，不能让
        // 「端侧生成中」残留在状态栏。注意失败/异常/ViewModel 清理**不走**这条
        // 条件（Failed 分支走 resetStreamingText；collect 的 onFailure 与 onCleared
        // 不经 resetStreaming）—— 由各自的显式 stop() 覆盖（审查 P1-1 补的 5 处）。
        // run 启动路径（role=MODEL, isStreaming=true）不经过这个分支，不会误撤新通知。
        if (role == null && !isStreaming) {
            container.generationNotifier.stop()
        }
    }

    /**
     * 开始观察某会话的 token 账本（Wave 31 流2 生产接线）。
     *
     * 账本按 cid 池化、跨 run 存活（AppContainer.tokenLedger），而
     * [com.rickeal.agent.core.agent.token.RunTokenSnapshot.sentTokens] 是 **run 级**
     * （新 run 首轮回写即覆盖）。`snapshot` 是 **StateFlow**：订阅会**立即重放当前值**，
     * 若不处理，新 run 首轮回写之前（4B 引擎加载数十秒）UI 显示的是**上一次 run** 的
     * 残留估算（先清 null 只造成一帧闪烁，随即被重放值覆盖）。故用账本**自己的时间戳**
     * 做基线：只接受**严格更新**（`updatedAtWallClockMillis > baseline`）的快照。
     * 账本初值为 0；[com.rickeal.agent.core.agent.token.RunTokenLedger.onSendEstimated] /
     * [com.rickeal.agent.core.agent.token.RunTokenLedger.onEngineUsage] 每次回写都用
     * `System.currentTimeMillis()` 覆盖该时间戳 ⇒ 上一轮残留值时间戳 == baseline（被滤掉），
     * 本轮首个回写必然严格更新（两轮之间隔着用户操作 + 引擎加载，墙钟前进）。
     *
     * ⚠️ 账本实例本身**不重置** —— [com.rickeal.agent.core.agent.token.RunTokenLedger]
     * 无 reset API，且其 KDoc 明确本波不引入；因此
     * [com.rickeal.agent.core.agent.token.RunTokenSnapshot.cumulativeIn] /
     * [com.rickeal.agent.core.agent.token.RunTokenSnapshot.cumulativeOut]
     * （单调累加、跨 run 不归零）在第二次 run 起与本次 run 的估算口径不再可比 ——
     * 本波 UI **只消费 sentTokens（发送侧估算）**，不消费那两个累计口径，从而规避该
     * 生命周期错配（这是「接受现状 + 只取安全口径」的取舍，与 RunTokenLedger KDoc 的
     * 「真正接线前必须先解决生命周期错配」同向）。
     */
    private fun observeTokenLedger(cid: String) {
        ledgerJob?.cancel()
        val ledger = container.tokenLedger(cid)
        // 基线取「订阅前」的快照时间戳：StateFlow 立即重放的正是这一版，`<= baseline` 一律
        // 滤掉；只有本轮 run 的新回写（时间戳严格更大）才会写进 UI。
        val baseline = ledger.snapshot.value.updatedAtWallClockMillis
        uiState.update { it.copy(sentTokensEstimate = null) }
        ledgerJob = scope.launch {
            ledger.snapshot.collect { snap ->
                if (snap.updatedAtWallClockMillis <= baseline) return@collect
                uiState.update { it.copy(sentTokensEstimate = snap.sentTokens.takeIf { v -> v > 0L }) }
            }
        }
    }

    /**
     * 审批通道：把「危险/需确认工具的执行前裁决」挂起到用户点击为止。
     * fail-closed 的另一半在这里——run 取消时 `deferred.await()` 随协程取消，
     * 不需要超时兜底；弹窗未响应期间 run 挂起是**设计行为**（人在回路）。
     */
    private val approvalHandler = ToolApprovalHandler { call, spec ->
        val deferred = CompletableDeferred<ToolApprovalDecision>()
        uiState.update {
            it.copy(
                pendingApproval = PendingApproval(
                    callId = call.id,
                    toolName = spec.name,
                    arguments = call.argumentsJson,
                    decision = deferred,
                ),
            )
        }
        try {
            deferred.await()
        } finally {
            // 身份校验（r1 审查遗留）：取消瞬间可能已有新审批入位，旧协程的
            // finally 不能误清新卡 —— 只清自己的那张（同 deferred 引用比对）。
            uiState.update {
                if (it.pendingApproval?.decision === deferred) {
                    it.copy(pendingApproval = null)
                } else {
                    it
                }
            }
        }
    }

    /**
     * 打开会话后扫描 journal：有「没跑完就被进程死亡打断」的 run 就出恢复卡。
     *
     * 这里的 `runCatching` **不需要**挡 `CancellationException`：`findUnsettled` 不是
     * suspend，取消只可能来自外层 `withContext(Dispatchers.IO)` 的调度边界，而那个
     * 异常抛在 `runCatching` **之外**、会正常向上传播（逐处判断，不机械加判据）。
     */
    internal suspend fun maybeOfferRecovery(cid: String) {
        val dir = File(container.journalRoot, cid)
        val unsettled = withContext(Dispatchers.IO) {
            runCatching { AgentRunJournal.findUnsettled(dir) }.getOrNull()
        } ?: return
        uiState.update {
            it.copy(recovery = RecoveryOffer(runId = unsettled.runId, messageCount = unsettled.messageCount))
        }
    }

    /**
     * 把目录里除 [keepRunId] 之外的所有「未 settled」journal 归档。
     *
     * findUnsettled 只返回最近的一个，其余未完成 run 会永远滞留 —— 每次进会话都
     * 再弹一张恢复卡，用户处置完最新的又来一张。归档（改名）而非删除：过程记录
     * 可能还有排查价值；已 settled 的正常 run 与空文件不动。
     *
     * 三条内层 `runCatching` 都只包**非 suspend** 的盘操作（listFiles / readLines /
     * renameTo），不可能抛 `CancellationException`；本函数是 suspend，取消由调用方
     * [dismissRecovery] 统一透传。
     */
    private suspend fun archiveOtherUnsettled(runDir: File, keepRunId: String) = withContext(Dispatchers.IO) {
        runCatching {
            val files = runDir.listFiles { f -> f.isFile && f.name.endsWith(".jsonl") } ?: return@runCatching
            for (file in files) {
                val name = file.name
                if (name == "$keepRunId.jsonl") continue
                if (name.endsWith(".dismissed.jsonl") || name.endsWith(".jsonl.dismissed")) continue
                val lines = runCatching {
                    AgentRunJournal.open(runDir, name.removeSuffix(".jsonl")).readLines()
                }.getOrDefault(emptyList())
                if (lines.isEmpty() || lines.last().kind == AgentRunJournal.KIND_SETTLED) continue
                runCatching {
                    file.renameTo(File(file.parentFile, file.nameWithoutExtension + ".dismissed.jsonl"))
                }
            }
        }.getOrDefault(Unit)
    }

    /**
     * 处置当前恢复卡（丢弃或被新 run 顶替）：归档目标 journal + 其余未完成 run。
     *
     * 「被新 run 顶替」是 Wave3 补的口子：恢复卡挂着时用户直接发了新消息 = 用行动
     * 表示「不恢复」，卡必须归档 —— 不然它留在状态里，下次进会话又弹出来，而且
     * 那份 journal 的 user_input 已经过时（上下文会拼出新 run 之前的状态）。
     *
     * [archiveOtherUnsettled] 是 suspend，所以外层 `runCatching` 会连
     * `CancellationException` 一起吞掉 —— 这里显式透传，保持与 onSend / onRetry
     * 同一约定（取消不是错误，但取消必须**被看见**）。
     */
    internal fun dismissRecovery(offer: RecoveryOffer, cid: String) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                val dir = File(container.journalRoot, cid)
                AgentRunJournal.open(dir, offer.runId).markDismissed()
                archiveOtherUnsettled(dir, offer.runId)
            }.onFailure { throwable ->
                if (throwable is CancellationException) throw throwable
            }
        }
    }

    /**
     * 长期记忆**标题索引**文本（Wave 34 pull 化后的提示词注入形态）。
     *
     * 读失败按「无记忆」处理 —— 绝不因为记忆读不出来就挡住发送。但**取消必须透传**：
     * `renderIndex()` 是 suspend，`runCatching` 会连 `CancellationException` 一起吞掉，
     * 吞掉之后本 run 会继续组装请求并真的跑起来 —— 用户点了「停止」却仍在生成，
     * 语义错（不崩，但状态机已经不对了）。
     */
    private suspend fun renderMemoryIndex(): String? = runCatching {
        container.agentMemory.renderIndex()
    }
        .onFailure { throwable -> if (throwable is CancellationException) throw throwable }
        .getOrNull()

    /**
     * 热闸（Wave 30 §2.1 引入；Wave 43 计量更换）：拒新 run 判据与文案统一收口到
     * [ThermalGovernor.heatBlockReason] —— 电池温度熔断（主判据，≥44.9℃）与
     * CRITICAL 档位各说各的事实，不再笼统报档位名。只挡本类主入口
     * （onSend / onRetry→onSendFrom / onRecover），子 run 不挡 —— 在跑 run 由
     * 轮头 Abort 兜底，语义闭环（R7-2）。返回 null = 放行；非 null = 拒绝文案。
     */
    private fun thermalRejection(): String? = container.thermalGovernor.heatBlockReason()

    /**
     * LIGHT 降档：新 run 启动时刻的 maxTokens 上限（对 1024 基准减半，保底 256）。
     * 只影响本次启动的取值，在跑 run 不动（方案 §2.1 四档策略表）。降档生效必留日志。
     */
    private fun thermallyCappedConfig(base: InferenceConfig): InferenceConfig {
        val capped = container.thermalGovernor.maxTokensCap(base.maxTokens)
        if (capped == base.maxTokens) return base
        AgentLogStore.info(
            "热降档：新 run maxTokens ${base.maxTokens} → $capped" +
                "（${container.thermalGovernor.tier.value.name} 档）",
        )
        return base.copy(maxTokens = capped)
    }

    /**
     * 从中断处继续（恢复卡「继续」按钮）。
     *
     * Wave3 修复的完整重建：journal 现在记录三类行 —— user_input（任务输入）、
     * message（过程消息：工具调用/结果/中间输出）、settled（终态）。恢复上下文 =
     * 会话可见历史（USER/MODEL 往来，去掉与本 run 任务输入重复的那条）+ journal
     * 过程消息（工具调用与结果**只有 journal 有**，会话文件里没有）按序拼接，任务
     * 输入本身作为 userInput 传入。Wave2 只拼 journal 过程消息：任务描述、此前
     * 会话轮次全部丢失，4B 模型是对着工具残骸盲猜。
     */
    fun onRecover() {
        val state = uiState.value
        if (state.isGenerating) return
        val offer = state.recovery ?: return
        val cid = conversationId ?: return
        // 热闸（Wave 30 §2.1）：SEVERE 及以上拒新 run（含恢复续跑）。放在
        // offer / cid 取值之后 —— 没有卡可恢复时（UI 正常进不来，防御路径）
        // 不该凭空弹出一条热保护错误。
        thermalRejection()?.let { rejection ->
            uiState.update { it.copy(error = rejection) }
            return
        }
        uiState.update {
            it.copy(
                recovery = null,
                error = null,
                toolTraces = emptyList(),
                // 同 onSend / onSendFrom：续跑也是一次新任务，上一次的诊断卡必须撤掉。
                lastReport = null,
                lastTermination = null,
            )
        }
        runJob?.cancel()
        runJob = scope.launch {
            val journal = AgentRunJournal.open(File(container.journalRoot, cid), offer.runId)
            val savedInput = withContext(Dispatchers.IO) { journal.readUserInputSync() }
            val committed = withContext(Dispatchers.IO) { journal.committedMessagesSync() }
            if (committed.isEmpty() && savedInput == null) {
                withContext(Dispatchers.IO) { journal.markDismissed() }
                archiveOtherUnsettled(File(container.journalRoot, cid), offer.runId)
                return@launch
            }
            val userMessage: ChatMessage
            val history: List<ChatMessage>
            if (savedInput != null) {
                userMessage = savedInput
                // 会话可见历史里去掉与本 run 任务输入同 id 的那条（它由 userInput 参数
                // 承担，避免上下文双份）。恢复 run 续写场景（同 journal 文件）里，上一次
                // 合成的「继续」消息也是同一条（同 id），同样被去掉 —— 它会作为 userInput
                // 重新出现在正确位置（所有过程消息之后）。
                val visible = state.messages.filterNot { it.id == savedInput.id }
                // 过程消息去重：恢复 run 曾走完过一次的话，其最终回答同时落在会话文件
                // （commitAssistant 落库）与 journal（message 行）里。判据与 commitAssistant
                // 一致用 role+text —— journal decode 出来的 id 与 UI 侧重新生成的不同，
                // 按 id 去重在这里永远命中不了。只剔 MODEL：TOOL / 中间 toolCall 消息
                // 永远不会出现在会话文件里。
                val visibleKeys = visible.mapTo(HashSet()) { it.role.name + "|" + it.text }
                val proc = committed.filterNot { m ->
                    m.role == Role.MODEL && (m.role.name + "|" + m.text) in visibleKeys
                }
                history = visible + proc
            } else {
                // 升级前的旧 journal（没有 user_input 行）：退化到合成「继续」输入，
                // 尽力而为 —— 至少过程消息（工具调用/结果）还在。
                userMessage = ChatMessage(
                    role = Role.USER,
                    text = "请从上次中断的地方继续未完成的任务，不要重复已完成的工作。",
                )
                history = committed
            }
            uiState.update {
                it.copy(
                    messages = if (it.messages.none { m -> m.id == userMessage.id }) {
                        it.messages + userMessage
                    } else {
                        it.messages
                    },
                    // 既有计划回填（计划已持久化）：恢复 run 不重放历史版本水印，
                    // 时间线必须在这里主动接上，否则磁盘上有计划而 UI 空白。
                    planSteps = container.agentPlanStore.stepsFor(cid),
                    isStreaming = true,
                    isGenerating = true,
                    agentRound = 0,
                )
            }
            resetStreaming(role = Role.MODEL, isStreaming = true)
            runStartedAtMillis = System.currentTimeMillis()
            // 只有「合成的继续输入」（旧 journal 退化路径）才需要落库；savedInput
            // 那条本来就在会话文件里（onSend 落库过 / 上次恢复已落库），不重复写。
            if (savedInput == null) {
                container.conversationRepository.appendMessage(cid, userMessage)
            }
            val config = thermallyCappedConfig(uiState.value.config)
            val request = AgentRequest(
                conversationId = cid,
                // run 级 token 账本（Wave 31 流2 生产接线）：按会话池化的实例，发送侧
                // 估算 / 引擎回报由 AgentRunner 单点回写。子 run（AskSubagentTool）不传
                // （by design：子 run 独立短命，不进父账本）。
                tokenLedger = container.tokenLedger(cid),
                history = history,
                userInput = userMessage,
                config = config,
                model = uiState.value.activeModel,
                policy = chatAgentPolicy(config, uiState.value.activeModel),
                journal = journal,
                // 长期记忆**标题索引**（Wave 34 pull 化）：正文不再进提示词 —— 记忆正文
                // 随 memory_write 变化会让 systemText 变化，而 systemText 是引擎的会话重建
                // 判据（每 run 一次全量 re-prefill，4B 秒级）。正文改由模型按需用
                // memory_search 检索、memory_read 看全文。读失败按无记忆处理，绝不挡发送。
                memoryText = renderMemoryIndex(),
                planStore = container.agentPlanStore,
                approvalHandler = approvalHandler,
                approvalCache = container.toolApprovalCache,
                // AI 能力档位（Wave 26）：由设置页写入 DataStore，此处读快照随请求下发。
                // 档位必须进入执行路径 —— AgentRunner 用它决定 WRITE 效果的工具是否要
                // 追加一次授权（ReadOnly 档）。默认档零行为变化。
                capabilityMode = uiState.value.capabilityMode,
                disclosureMode = uiState.value.disclosureMode,
                // 热闸（Wave 30 §2.1）：轮头热决策。子 run 不传（保持 null，R7-2）。
                thermalGate = container.thermalGovernor.asGate(),
            )
            runCatching {
                collectRunWithPerfWindow(cid, request)
            }.onFailure { throwable ->
                // 取消不是错误（与 onSend/onRetry 同一约定）
                if (throwable is CancellationException) return@onFailure
                uiState.update {
                    it.copy(
                        isStreaming = false,
                        isGenerating = false,
                        error = throwable.message?.let { m -> AgentLogStore.sanitizeUserFacing(m) }
                            ?: "生成失败",
                    )
                }
                // 异常也是终态，必须撤通知（Wave 9 审查修正）：此路径不经过
                // resetStreaming(role=null)，漏掉就会残留 ongoing 通知。stop() 幂等。
                container.generationNotifier.stop()
            }
        }
    }

    /**
     * 用户点「停止」。
     *
     * 顺序很关键：**先取快照，再取消**。`runJob.cancel()` 之后协程进入取消态，流不会再吐
     * 事件、`streamingText` 也不会再更新；先取消再读，用户等了半天的半截回答就凭空消失了。
     *
     * 落库统一走 [commitAssistant]：取消与 `AgentEvent.Cancelled` 谁先到是不确定的竞态，
     * 两条路径都会提交同样的文本，靠它的 **run 级文本判据**（`lastCommittedText`，先到者生效）
     * 去重，不会写两份（见 [commitAssistant] 与 [AssistantPersistenceState] 的判据说明）。
     * 两条路径均取 `_streaming.value.thinking` 作 thinking（Wave 47 项3，与 `Cancelled` 分支
     * 同源）—— 竞态判据仍**只吃 text**（`shouldCommitAssistant` 不看 thinking），故「谁先落库」
     * 与 thinking 无关，不会因竞态胜负存出不同的东西。
     */
    fun onStop() {
        // 保序（A 项节流改造后仍然关键）：先 flushNow 同步排空缓冲 → 取快照 →
        // 再取消 → 再清流式状态。flush 协程独立于 runJob，缓冲排空后自然退出，
        // 终态清空不会与残留 delta 竞争。
        flushNow()
        val partial = _streaming.value.text
        // thinking 必须与 partial 同点取快照：下方 resetStreaming() 会清空 _streaming（含
        // thinking），在其之后读 `_streaming.value.thinking` 恒为空 ⇒ 取消路径会静默丢思考
        // （Wave 47 项3 的核心：取消也要带 thinking 落盘）。
        val thinking = _streaming.value.thinking.ifBlank { null }
        runJob?.cancel()
        runJob = null
        uiState.update {
            it.copy(
                isStreaming = false,
                isGenerating = false,
                notice = null,
            )
        }
        // （history_v2 判死，Wave 30：归档写入调用已摘除，恢复权威仍是 journal，
        // 见 SegmentedHistoryStore 类头的启用条件标注。）
        resetStreaming(role = null, isStreaming = false)
        if (partial.isNotBlank()) {
            // conversationId 为空说明首轮的用户消息都还没落库（极窄窗口），此时没有可写入的
            // 会话，不提交，避免出现「界面有气泡但历史里没有」的假象。
            conversationId?.let { commitAssistant(partial, thinking, null, it) }
        }
    }

    fun onSend() {
        val state = uiState.value
        // 闸门统一用 isGenerating，与 onRetry() 一致（原来这里是 isStreaming）：
        // 工具执行阶段 isStreaming 会回落（没在吐 token）而 isGenerating 仍为 true，
        // 用 isStreaming 当闸门就放行第二次 run —— 两个 run 抢同一个引擎实例，
        // 后一个 close() 掉前一个正在用的 LiteRT Conversation → native SIGSEGV 闪退。
        if (state.isGenerating) return
        val text = state.draftInput
        if (text.isBlank() && state.attachments.isEmpty()) return

        // 热闸（Wave 30 §2.1）：SEVERE 及以上拒新 run。放在状态变更之前 ——
        // 拒绝时 UI 保持原样，只弹错误提示。
        thermalRejection()?.let { rejection ->
            uiState.update { it.copy(error = rejection) }
            return
        }

        val userMessage = ChatMessage(
            role = Role.USER,
            text = text,
            attachments = state.attachments,
        )
        val history = state.messages + userMessage
        // 恢复卡挂着时直接发新消息 = 用行动表示「不恢复」：归档那份 journal，
        // 否则卡会在下次进会话时复活（Wave3 补的口子）。
        state.recovery?.let { offer -> conversationId?.let { cid -> dismissRecovery(offer, cid) } }
        uiState.update {
            it.copy(
                messages = history,
                draftInput = "",
                attachments = emptyList(),
                isStreaming = true,
                isGenerating = true,
                agentRound = 0,
                toolTraces = emptyList(),
                thinkingExpanded = false,
                error = null,
                notice = null,
                recovery = null,
                // 上一次任务的诊断卡 / 终止原因不该粘到这一次（与 error / notice 同一处纪律）。
                lastReport = null,
                lastTermination = null,
            )
        }
        resetStreaming(role = Role.MODEL, isStreaming = true)
        runStartedAtMillis = System.currentTimeMillis()
        // 覆盖 runJob 之前必须先取消旧的：core-agent 侧已有 runMutex 根治并发，
        // 这里是第二层 —— 少这一行就是「两个 run 抢同一个引擎实例」的入口。
        runJob?.cancel()
        runJob = scope.launch {
            val cid = ensureConversation(firstUserText(history))
            container.conversationRepository.appendMessage(cid, userMessage)
            val config = thermallyCappedConfig(uiState.value.config)
            // 每次 run 一个 journal 文件：进程被杀后可从「已完成轮次」继续
            // （core-agent/journal；写入 best-effort，失败不影响 run 本身）。
            val journal = AgentRunJournal.open(
                runDir = File(container.journalRoot, cid),
                runId = "run_" + System.currentTimeMillis(),
            )
            // Wave 41 P2-1：AgentPolicy 构造上提到 engineHistory 计算之前 —— 过程消息
            // 回灌的 token 预算要取 policy.compressThreshold（与 AgentRunner 压缩预算
            // 同源、不写死阈值），预算必须先于 engineHistory 组装算出。
            val policy = chatAgentPolicy(config, uiState.value.activeModel)
            // C3（Wave 40）：会话文件只存 USER + 最终 MODEL 答案（commitAssistant），
            // TOOL / 中间 toolCall 消息只进 journal。重开会话 / 进程重启后引擎重建，
            // initialMessages 只播问答对 —— 模型丢失全部工具执行上下文。发送前把
            // journal 里的过程消息按 run 时间序回灌进 history。本轮任务输入先从
            // visible 里滤掉、拼在结果末尾：过程消息统一排在可见历史之后（照抄
            // onRecover 的拼接语义），任务输入必须在其后再交给引擎 —— 否则新问题
            // 会排在旧工具残骸之前（时序倒挂）。
            // Wave 41 P2-1 预算口径：传给 historyWithProcess 的 visible **不含**本次
            // 新输入（userMessage 先滤、拼在过程消息之后），故按 [processTokenBudget]
            // 的公式减 visible 占用、本次输入经 inputTokens 单独预减 —— 硬不变量：
            // visible + 回灌过程 + 本次输入 ≤ AgentRunner 压缩预算（SYSTEM 段不计入，
            // 由引擎压缩器兜底；复审 P1-1：不预减本次输入则预算吃满时每轮必触发压缩）。
            val visibleWithoutInput = history.filterNot { it.id == userMessage.id }
            val engineHistory = withContext(Dispatchers.IO) {
                historyWithProcess(
                    visibleWithoutInput,
                    container.journalRoot,
                    cid,
                    budget = processTokenBudget(
                        contextLength = config.contextLength,
                        maxTokens = config.maxTokens,
                        visible = visibleWithoutInput,
                        inputTokens = TokenEstimator.estimate(userMessage),
                        compressThreshold = policy.compressThreshold,
                    ),
                ) + userMessage
            }
            val request = AgentRequest(
                conversationId = cid,
                // run 级 token 账本（Wave 31 流2 生产接线）：按会话池化的实例，发送侧
                // 估算 / 引擎回报由 AgentRunner 单点回写。子 run（AskSubagentTool）不传
                // （by design：子 run 独立短命，不进父账本）。
                tokenLedger = container.tokenLedger(cid),
                history = engineHistory,
                userInput = userMessage,
                config = config,
                model = uiState.value.activeModel,
                policy = policy,
                journal = journal,
                // 长期记忆**标题索引**（Wave 34 pull 化，替代此前的全量正文注入）：
                // 正文随 memory_write 变化会让 systemText 变化，而 systemText 是引擎的会话
                // 重建判据（每 run 一次全量 re-prefill，4B 秒级）。正文改由模型按需用
                // memory_search 检索、memory_read 看全文。读失败按无记忆处理，绝不挡发送。
                memoryText = renderMemoryIndex(),
                planStore = container.agentPlanStore,
                approvalHandler = approvalHandler,
                approvalCache = container.toolApprovalCache,
                // AI 能力档位（Wave 26）：由设置页写入 DataStore，此处读快照随请求下发。
                // 档位必须进入执行路径 —— AgentRunner 用它决定 WRITE 效果的工具是否要
                // 追加一次授权（ReadOnly 档）。默认档零行为变化。
                capabilityMode = uiState.value.capabilityMode,
                disclosureMode = uiState.value.disclosureMode,
                // 热闸（Wave 30 §2.1）：轮头热决策。子 run 不传（保持 null，R7-2）。
                thermalGate = container.thermalGovernor.asGate(),
            )
            runCatching {
                collectRunWithPerfWindow(cid, request)
            }.onFailure { throwable ->
                // 取消不是错误：onStop() / onNewConversation() 会 cancel 这个协程，而 runCatching
                // 把 CancellationException 也一起捕获了。不挡掉的话，用户点「停止」或「新对话」
                // 之后会莫名其妙弹出一条「生成失败」—— 明明是他自己主动取消的。
                if (throwable is CancellationException) return@onFailure
                uiState.update {
                    it.copy(
                        isStreaming = false,
                        isGenerating = false,
                        // 异常消息是自由文本，可能整段带上请求头 / 带凭据的 URL
                        // （远程引擎失败时尤其常见），上屏前必须过一遍脱敏。
                        error = throwable.message?.let { AgentLogStore.sanitizeUserFacing(it) }
                            ?: "生成失败",
                    )
                }
                // 异常也是终态，必须撤通知（Wave 9 审查修正）：这两条 onSend/onRetry
                // 的 onFailure 路径不经过 resetStreaming(role=null)，漏掉就会在状态栏
                // 留一条 ongoing、用户划不掉的「端侧生成中」。stop() 幂等。
                container.generationNotifier.stop()
            }
        }
    }

    /** 重跑最后一轮：截掉最后一条用户消息之后的所有内容再生成。 */
    fun onRetry() {
        val state = uiState.value
        if (state.isGenerating) return
        // 热闸（Wave 30 §2.1）：与 onSend 同一纪律 —— 拒绝时 UI 保持原样。判定
        // 必须在裁剪历史**之前**：否则过热时用户看到的是「上一条回答凭空消失 +
        // 一条过热提示」，而这一次重试根本没跑起来（onSendFrom 里还有一道，
        // 那是真正起 runJob 的地方）。
        thermalRejection()?.let { rejection ->
            uiState.update { it.copy(error = rejection) }
            return
        }
        val lastUserIndex = state.messages.indexOfLast { it.role == Role.USER }
        if (lastUserIndex < 0) return
        val trimmed = state.messages.take(lastUserIndex + 1)
        uiState.update {
            it.copy(
                messages = trimmed,
                error = null,
                toolTraces = emptyList(),
            )
        }
        onSendFrom(trimmed[lastUserIndex], trimmed)
    }

    private fun onSendFrom(userMessage: ChatMessage, history: List<ChatMessage>) {
        // 与 onSend() / onRetry() 同一道闸门。它现在是 private、只被 onRetry() 调，
        // 但 onRetry() 自己也在改状态之后才调过来（中间有 uiState.update 的间隙），
        // 而这里才是真正起 runJob 的地方 —— 闸门放在真正启动的那一处才拦得住。
        if (uiState.value.isGenerating) return
        // 热闸（Wave 30 §2.1）：SEVERE 及以上拒新 run（重试同受热保护）。
        thermalRejection()?.let { rejection ->
            uiState.update { it.copy(error = rejection) }
            return
        }
        // 重试也顶替恢复卡（同 onSend：用户行动优先于过时的恢复提示）。
        uiState.value.recovery?.let { offer -> conversationId?.let { cid -> dismissRecovery(offer, cid) } }
        uiState.update {
            it.copy(
                messages = history,
                isStreaming = true,
                isGenerating = true,
                agentRound = 0,
                toolTraces = emptyList(),
                error = null,
                notice = null,
                recovery = null,
                // 同 onSend：重跑是一次新任务，上一次的诊断卡必须撤掉。
                lastReport = null,
                lastTermination = null,
            )
        }
        // 同上：覆盖 runJob 之前先取消旧的，绝不让两个 run 同时活着。
        runJob?.cancel()
        resetStreaming(role = Role.MODEL, isStreaming = true)
        runStartedAtMillis = System.currentTimeMillis()
        runJob = scope.launch {
            val cid = ensureConversation(firstUserText(history))
            val config = thermallyCappedConfig(uiState.value.config)
            // 每次 run 一个 journal 文件：进程被杀后可从「已完成轮次」继续
            // （core-agent/journal；写入 best-effort，失败不影响 run 本身）。
            val journal = AgentRunJournal.open(
                runDir = File(container.journalRoot, cid),
                runId = "run_" + System.currentTimeMillis(),
            )
            // Wave 41 P2-1：AgentPolicy 构造上提到 engineHistory 计算之前（同 onSend）。
            val policy = chatAgentPolicy(config, uiState.value.activeModel)
            // C3（Wave 40）：同 onSend —— 重跑同样要把 journal 过程消息回灌进引擎
            // 上下文（重试恰恰是最需要工具残骸的场景：上一轮失败前已执行的工具
            // 结果全部只在 journal 里）。任务输入先滤后拼，过程消息排在任务输入前。
            // Wave 41 P2-1 预算口径：同 onSend —— visible 不含本次新输入，本次输入
            // 经 inputTokens 单独预减，硬不变量：visible + 回灌过程 + 本次输入 ≤
            // AgentRunner 压缩预算（SYSTEM 段由引擎压缩器兜底）。
            val visibleWithoutInput = history.filterNot { it.id == userMessage.id }
            val engineHistory = withContext(Dispatchers.IO) {
                historyWithProcess(
                    visibleWithoutInput,
                    container.journalRoot,
                    cid,
                    budget = processTokenBudget(
                        contextLength = config.contextLength,
                        maxTokens = config.maxTokens,
                        visible = visibleWithoutInput,
                        inputTokens = TokenEstimator.estimate(userMessage),
                        compressThreshold = policy.compressThreshold,
                    ),
                ) + userMessage
            }
            val request = AgentRequest(
                conversationId = cid,
                // run 级 token 账本（Wave 31 流2 生产接线）：按会话池化的实例，发送侧
                // 估算 / 引擎回报由 AgentRunner 单点回写。子 run（AskSubagentTool）不传
                // （by design：子 run 独立短命，不进父账本）。
                tokenLedger = container.tokenLedger(cid),
                history = engineHistory,
                userInput = userMessage,
                config = config,
                model = uiState.value.activeModel,
                policy = policy,
                journal = journal,
                // 长期记忆**标题索引**（Wave 34 pull 化，替代此前的全量正文注入）：
                // 正文随 memory_write 变化会让 systemText 变化，而 systemText 是引擎的会话
                // 重建判据（每 run 一次全量 re-prefill，4B 秒级）。正文改由模型按需用
                // memory_search 检索、memory_read 看全文。读失败按无记忆处理，绝不挡发送。
                memoryText = renderMemoryIndex(),
                planStore = container.agentPlanStore,
                approvalHandler = approvalHandler,
                approvalCache = container.toolApprovalCache,
                // AI 能力档位（Wave 26）：由设置页写入 DataStore，此处读快照随请求下发。
                // 档位必须进入执行路径 —— AgentRunner 用它决定 WRITE 效果的工具是否要
                // 追加一次授权（ReadOnly 档）。默认档零行为变化。
                capabilityMode = uiState.value.capabilityMode,
                disclosureMode = uiState.value.disclosureMode,
                // 热闸（Wave 30 §2.1）：轮头热决策。子 run 不传（保持 null，R7-2）。
                thermalGate = container.thermalGovernor.asGate(),
            )
            runCatching {
                collectRunWithPerfWindow(cid, request)
            }.onFailure { throwable ->
                // 取消不是错误：onStop() / onNewConversation() 会 cancel 这个协程，而 runCatching
                // 把 CancellationException 也一起捕获了。不挡掉的话，用户点「停止」或「新对话」
                // 之后会莫名其妙弹出一条「生成失败」—— 明明是他自己主动取消的。
                if (throwable is CancellationException) return@onFailure
                uiState.update {
                    it.copy(
                        isStreaming = false,
                        isGenerating = false,
                        // 异常消息是自由文本，可能整段带上请求头 / 带凭据的 URL
                        // （远程引擎失败时尤其常见），上屏前必须过一遍脱敏。
                        error = throwable.message?.let { AgentLogStore.sanitizeUserFacing(it) }
                            ?: "生成失败",
                    )
                }
                // 异常也是终态，必须撤通知（Wave 9 审查修正）：这两条 onSend/onRetry
                // 的 onFailure 路径不经过 resetStreaming(role=null)，漏掉就会在状态栏
                // 留一条 ongoing、用户划不掉的「端侧生成中」。stop() 幂等。
                container.generationNotifier.stop()
            }
        }
    }

    /**
     * run 收集壳（Wave 30 §2.2 acquire 点 ①）：性能采样窗口与 run 窗口精确重合。
     * acquire/release 包在 try/finally 里 —— 取消 / 异常路径必然归还（引用计数
     * 配对面）。刻意**不在** 10 个 generationNotifier.stop() 终态散点逐个加
     * release：散点接线是 Wave 27 以降的已知事故形态（方案 §2.2 裁决）。
     */
    private suspend fun collectRunWithPerfWindow(cid: String, request: AgentRequest) {
        // 本 run 起点清零「助手落库」去重状态（Wave 46）：这是三条 run 路径
        // （onSend / onSendFrom / onRecover）的**唯一共享入口**，每 run 恰调一次、先于任何
        // 事件 ⇒ 是 committedMessageId / lastCommittedText 清零的硬不变量单点。
        // ⛔ 严禁改放进 resetStreaming / resetStreamingText（那两处被 Finished / Cancelled
        // 在决策之后调用，且是散点）—— 详见 AssistantPersistenceState 类头 KDoc。
        persistState = persistState.beginRun()
        // 熔断救援缓冲（Wave 47 项2）与 persistState 同一清零点（run 起点）。
        salvageText = ""
        // token 账本观察（Wave 31 流2）：与 run 窗口同起，把发送侧估算镜像进 UI 状态。
        // 放在这个共享入口 = 三条 run 路径（onSend / onSendFrom / onRecover）一次接线。
        observeTokenLedger(cid)
        container.perfMonitorManager.acquire("chat-run:$cid")
        try {
            container.agentRunner.run(request).collect { event -> handleEvent(event, cid) }
        } finally {
            container.perfMonitorManager.release("chat-run:$cid")
        }
    }

    private fun handleEvent(event: AgentEvent, conversationId: String) {
        when (event) {
            is AgentEvent.RoundStarted -> uiState.update {
                it.copy(agentRound = event.round, agentMaxRounds = event.maxRounds)
            }

            // 引擎损坏后重建并重试本轮：清空流式缓冲（两轮输出不得叠在一起），
            // 并用 notice 告知用户「已自动恢复」（gallery 自愈链可见化，G 项）——
            // 以前这里只清文本，用户唯一感知是内容突然清零重来，零解释。
            // 注意：清缓冲**不落库**（s3 审查修正）——重试=丢弃半截输出重新生成，
            // flush 落库反而会把失败尝试的半截文本存进会话。
            is AgentEvent.Retrying -> {
                resetStreamingText()
                uiState.update { it.copy(notice = "引擎异常，已自动重建并重试") }
            }

            // 主循环丢弃了本轮已流出的文本（轮内重复截断 / 重复回答 / 空输出 nudge）：
            // 必须清流式缓冲。round++ 后的下一轮照常 emit(TextDelta)，但 RoundStarted 不清
            // 缓冲（它只管轮次数字），不清就会出现「被丢弃的乱文 + 新回答」粘在同一个气泡里，
            // 直到终态才消失（复审3 §4-1，P1）。
            // 与 Retrying 同理：清缓冲**不落库** —— 被丢弃的文本本就不该交付。
            is AgentEvent.StreamReset -> resetStreamingText()

            // 工具审批请求：立一条 RUNNING 轨迹占位（授权卡另在 snackbarHost 区渲染）。
            // Wave2 接了真审批 UI 之后，这里还残留着 Wave1 的「未接审批 UI 已按拒绝处理」
            // 文案 + SKIPPED 状态 —— 用户会同时看到矛盾的错误轨迹和待授权卡片，且授权后
            // ToolCallStarted 会再 append 一条同 id 轨迹（见下）。
            is AgentEvent.ApprovalRequested -> uiState.update { state ->
                state.copy(
                    toolTraces = state.toolTraces + ToolTrace(
                        id = event.call.id,
                        name = event.spec.name,
                        arguments = event.call.argumentsJson,
                        status = ToolTraceStatus.RUNNING,
                        result = "等待用户授权…",
                    ),
                )
            }

            // 计划变化（plan_set / plan_update）：UI 渲染时间线。同一轮内逐次更新。
            is AgentEvent.PlanUpdated -> uiState.update {
                it.copy(planSteps = event.steps)
            }

            is AgentEvent.TextDelta -> {
                if (firstTokenAtMillis == null) firstTokenAtMillis = System.currentTimeMillis()
                textBuffer.append(event.text)
                ensureFlushLoop()
            }

            is AgentEvent.ThinkingDelta -> {
                thinkingBuffer.append(event.text)
                ensureFlushLoop()
            }

            is AgentEvent.ToolCallStarted -> {
                val call = event.call
                uiState.update { state ->
                    // 授权路径已由 ApprovalRequested 立过同 id 轨迹（RUNNING 占位），
                    // 这里只补「没有先例」的普通工具调用，否则同一调用出现两条轨迹。
                    val exists = state.toolTraces.any { it.id == call.id }
                    if (exists) {
                        state
                    } else {
                        state.copy(
                            toolTraces = state.toolTraces + ToolTrace(
                                id = call.id,
                                name = call.name,
                                arguments = call.argumentsJson,
                            ),
                        )
                    }
                }
            }

            is AgentEvent.ToolResultReceived -> {
                val result = event.result
                uiState.update { state ->
                    state.copy(
                        toolTraces = state.toolTraces.map { trace ->
                            if (trace.id == result.callId || (trace.result == null && trace.name == result.name)) {
                                trace.copy(
                                    status = if (result.ok) ToolTraceStatus.OK else ToolTraceStatus.FAILED,
                                    result = result.output.ifBlank { result.errorMessage.orEmpty() },
                                    elapsedMillis = result.elapsedMillis,
                                )
                            } else {
                                trace
                            }
                        },
                    )
                }
            }

            is AgentEvent.ToolSkipped -> uiState.update { state ->
                state.copy(
                    toolTraces = state.toolTraces.map { trace ->
                        if (trace.id == event.call.id) {
                            trace.copy(status = ToolTraceStatus.SKIPPED, result = event.reason)
                        } else {
                            trace
                        }
                    },
                )
            }

            is AgentEvent.MessageCommitted -> {
                // 🔴 这是**唯一落库点（正常终态）**：落的是 AgentRunner 装配的**富消息**
                // （usage / finishReason / modelRef / thinking 全保）。AgentRunner 在终态
                // 还会再发一次 Finished，但 Finished 只给 text+usage —— 落库统一在本分支，
                // Finished 靠 persistState 的 id 判据**跳过**，不会双落。
                // ⚠️ 改动此分工前必须先读 AssistantPersistence 的判据说明。
                persistState = persistState.onMessageCommitted(event.message.id)
                if (uiState.value.messages.none { it.id == event.message.id }) {
                    commit(event.message, conversationId)
                }
            }

            is AgentEvent.Finished -> {
                // 终态收尾（A 项节流改造）：先同步排空缓冲再取快照 —— 保证
                // 「半截回答不凭空消失」的既有承诺在节流后仍然成立。
                flushNow()
                val text = event.text.ifBlank { _streaming.value.text }
                val thinking = _streaming.value.thinking.ifBlank { null }
                if (text.isNotBlank() || thinking != null) {
                    commitAssistant(text, thinking, event.usage, conversationId)
                }
                // 本次请求的 prompt 规模 = 当前上下文占用。只有拿到正数才覆盖：
                // 0 / null 表示「这次引擎没给这个字段」，用它覆盖会把上一次的真实占用抹成 0，
                // 状态条会莫名其妙消失 —— 保留旧值比显示 0 更接近事实。
                val promptTokens = event.usage?.promptTokens ?: 0
                uiState.update {
                    it.copy(
                        isStreaming = false,
                        isGenerating = false,
                        toolTraces = emptyList(),
                        contextTokens = if (promptTokens > 0) promptTokens else it.contextTokens,
                        notice = null,
                        // 诊断卡 + 终止原因（Wave 30 §2.8 / Wave 31 流2）：轮次耗尽路径挂
                        // report 走 Finished 而非 Failed；正常结束恒 null，等于没这两个字段
                        // —— 零行为回归。映射收在 applyTerminalEvent（纯函数，可 JVM 单测）。
                    ).applyTerminalEvent(event)
                }
                resetStreaming(role = null, isStreaming = false)
                // （history_v2 判死，Wave 30：COMPLETE 终态不再写回合归档。）
            }

            is AgentEvent.Failed -> {
                // 熔断救援（Wave 47 项2）：预算 / 外部型熔断时把用户**已看到**的正文落库，而非让它随
                // resetStreamingText() 消失（真机 r5）。判据外提为纯函数 shouldSalvageOutput（可 JVM 单测）；
                // 复用 commitAssistant 唯一入口 —— 熔断路径无 MessageCommitted ⇒ id 判据不参与，不会误跳。
                if (shouldSalvageOutput(event.terminatedBy, event.report, salvageText)) {
                    commitAssistant(salvageText, _streaming.value.thinking.ifBlank { null }, null, conversationId)
                } else {
                    // R-E（Wave 49）：熔断类型可救援、但正文命中 ModelHealthCriteria 的 HARD 判据
                    // （保留 token / 单字符退化 run / 周期复读 / 空 / detector 循环）⇒ **放弃落库**
                    // （判据与理由见 shouldSalvageOutput KDoc）。留一条诊断日志，避免静默丢弃
                    // 用户已见正文。仅 HARD 拦截；SOFT（如非白名单通道标记）不拦。
                    salvageDegradationHits(salvageText).takeIf { it.isNotEmpty() }?.let { hits ->
                        AgentLogStore.info(
                            "熔断救援未落库：正文命中模型健康 HARD 判据 " +
                                hits.joinToString(",") { it.id } + "（疑似退化输出，不写入历史）",
                        )
                    }
                }
                // ⚠️ 刻意不 flushNow()（Wave 48 D1）：本分支读的 `salvageText` 由 `flushNow()` 单点产出，
                // 其语义精确等于「已 flush 进 _streaming = 已上屏 = 用户已见」。而此刻未 flush 的
                // textBuffer 尾部（最后一次 TextDelta 后 ≤120ms）**尚未渲染**，不属于「用户已见的正文」；
                // 救它反而会把用户没见过的尾巴灌进历史、破坏判据。故四条终态里唯独 Failed 不 flushNow，
                // 与 onStop/Cancelled/Finished 的「先 flush 再决策」是**有意的不对称**，不是漏写。
                // 失败尝试的半截输出不落库（与 Retrying 同理），但缓冲必须清。
                resetStreamingText()
                uiState.update {
                    // AgentEvent.Failed 的 message 可能带着远程端点的 URL / 请求头。
                    it.copy(
                        isStreaming = false,
                        isGenerating = false,
                        error = AgentLogStore.sanitizeUserFacing(event.message),
                        notice = null,
                        // 诊断卡 + 终止原因（Wave 30 §2.4 / Wave 31 流2）：熔断路径
                        // （热闸 / 墙钟 / 失败连击 / 振荡）挂 report，既有 4 处 emit Failed
                        // 恒 null —— 零回归。数据本身不 sanitize：报告里带工具报错原文与
                        // 熔断证据，脱敏统一在 UI 渲染出口做（ChatScreen 走 sanitizeUserFacing），
                        // 免得同一段文本被脱两次、把证据里的合法字符也吃掉。
                        // 映射收在 applyTerminalEvent（纯函数，可 JVM 单测）。
                    ).applyTerminalEvent(event)
                }
                _streaming.update { it.copy(isStreaming = false) }
                // 失败也是终态，必须撤通知（Wave 9 审查修正）：Failed 分支走的是
                // resetStreamingText() 而不是 resetStreaming(role=null)，终态收口
                // 的 `role == null && !isStreaming` 条件在这里不成立 —— 不补这一句，
                // 一次引擎失败就会在状态栏留一条 ongoing、划不掉的「端侧生成中」，
                // 直到下一次成功终态或重启。stop() 幂等，通知不存在时是 no-op。
                container.generationNotifier.stop()
                // （history_v2 判死，Wave 30：FAILED 终态不再写回合归档。）
            }

            is AgentEvent.Cancelled -> {
                flushNow()
                val partial = event.partialText.ifBlank { _streaming.value.text }
                // thinking 必须与 partial 同点取快照（Wave 48 G1，与 onStop 同形态）：下方
                // resetStreaming() 会清空 _streaming（含 thinking），在其之后读
                // `_streaming.value.thinking` 恒为空 ⇒ 取消路径会静默丢思考（Wave 47 项3）。
                val thinking = _streaming.value.thinking.ifBlank { null }
                if (partial.isNotBlank()) {
                    // thinking 同源（Wave 47 项3）：此处 resetStreaming 尚未执行，缓冲仍在。
                    commitAssistant(partial, thinking, null, conversationId)
                }
                uiState.update {
                    it.copy(
                        isStreaming = false,
                        isGenerating = false,
                        notice = null,
                    )
                }
                resetStreaming(role = null, isStreaming = false)
                // （history_v2 判死，Wave 30：CANCELLED 终态不再写回合归档。）
            }
        }
    }

    private fun commit(message: ChatMessage, conversationId: String) {
        uiState.update { state -> state.copy(messages = state.messages + message) }
        scope.launch {
            container.conversationRepository.appendMessage(conversationId, message)
        }
    }

    /**
     * 提交一条助手回答，并**保证本 run 内同一条回答不会被提交两次**。
     *
     * ## 谁负责落库（Wave 46 重新划清 —— 此前这段 KDoc 失实，正是它让本 bug 被放过）
     *
     * - **正常终态（`ModelStopped`）**：由 [AgentEvent.MessageCommitted] 落库**富消息**
     *   （`usage` / `finishReason` / `modelRef` / `thinking` 全保）；`Finished` 到达时本函数
     *   按 id 判据**跳过**已落库的那条（不重建、不双落）。
     * - **本 run 无 `MessageCommitted`（轮次耗尽 `MaxRounds`）**：由 `Finished` 走本函数落
     *   **text 版**——此路径 AgentRunner 本就没装配富消息，故无额外字段丢失。
     * - **取消路径（`onStop` / `Cancelled`）**：仍落 text 版，靠 run 级文本判据挡竞态。
     *
     * ## 两条去重判据（[AssistantPersistenceState] 逐字同源）
     *
     * 1. **id 判据**（`committedMessageId`，服务正常终态）：`MessageCommitted` 落库时记下富消息
     *    id；本函数若发现 `messages.lastOrNull()?.id` 正是该 id ⇒ 已落库 ⇒ 跳过。id 精确，不依赖
     *    「谁排在最后」的文本巧合。
     * 2. **run 级文本判据**（`lastCommittedText`，服务取消竞态）：`onStop` 与 `Cancelled` 提交的
     *    文本相同、谁先到不确定 ⇒ 先到者落库并记文本，后到者命中该判据跳过。**这是取消竞态的唯一
     *    防线，不可删。**
     *
     * **两条判据都随 run 起点清零**（[collectRunWithPerfWindow] 首行 + [resetForNewConversation]，
     * 见 [AssistantPersistenceState] 类头 KDoc）—— 否则会跨 run 误跳、静默丢回答。
     *
     * ## 语义边界（保留既有约定）
     *
     * 只看**本 run**、不做全局去重：用户完全可能连着两轮拿到同样的回答。方案 A 下每 run 各自由
     * `MessageCommitted` 落库（各自 id 不同）⇒ **连续两轮相同回答不会被吞**。
     *
     * 命中时**不重新落库**：`ConversationRepository.appendMessage` 是无条件追加（不按 id 覆盖），
     * 再 append 一次等于把重复记录写进历史，正是要修的那个问题。
     */
    private fun commitAssistant(
        text: String,
        thinking: String?,
        usage: TokenUsage?,
        conversationId: String,
    ) {
        val lastId = uiState.value.messages.lastOrNull()?.id
        // (1) 本 run 已由 MessageCommitted 落库该条（富消息）→ 不重复落（防双气泡 / 双记录）。
        // (2) 本 run 已落过同样文本（onStop 与 Cancelled 的竞态，先到者生效）→ 不重复落。
        if (!persistState.shouldCommitAssistant(lastId, text)) return
        commit(
            ChatMessage(role = Role.MODEL, text = text, thinking = thinking, usage = usage),
            conversationId,
        )
        persistState = persistState.onCommitted(text)
    }

    private suspend fun ensureConversation(firstUserText: String): String {
        val existing = conversationId
        if (existing != null) return existing
        val title = firstUserText.trim().take(24).ifBlank { "新对话" }
        val created = container.conversationRepository.create(title = title)
        conversationId = created.id
        uiState.update { it.copy(conversationId = created.id, title = created.title) }
        return created.id
    }

    private fun firstUserText(history: List<ChatMessage>): String =
        history.firstOrNull { it.role == Role.USER }?.text.orEmpty()

    /**
     * 新会话复位（[ChatViewModel.onNewConversation] 调）：取消在跑 run / 账本观察、清空
     * [conversationId]、清零 [persistState] / [salvageText]（与 [collectRunWithPerfWindow] 首行
     * 同为唯一清零点）、撤销本会话审批缓存、复位流式通道。**不含** UI 状态整体重建（那在 VM）。
     */
    fun resetForNewConversation() {
        runJob?.cancel()
        runJob = null
        // 账本观察随会话一起停（新会话的观察在 collectRunWithPerfWindow 里按新 cid 重建）。
        ledgerJob?.cancel()
        ledgerJob = null
        conversationId = null
        // 新会话 = 旧 run 的落库去重状态作废（Wave 46）：与 conversationId 同处清零，
        // 防止上一会话的 committedMessageId / lastCommittedText 粘到新会话首轮。
        persistState = persistState.beginRun()
        // 熔断救援缓冲（Wave 47 项2）与 persistState 同一清零点（新会话）。
        salvageText = ""
        // 会话销毁 = 授权作用域消失：审批缓存全清（key 含 cid 本就隔离，这里保超额清）。
        container.toolApprovalCache.revokeAll(null)
        resetStreaming(role = null, isStreaming = false)
    }

    /** VM 销毁时取消在跑 run（[ChatViewModel.onCleared] 调）；不负责撤通知（那是 VM 的事）。 */
    fun cancel() {
        runJob?.cancel()
    }
}
