package com.rickeal.agent.core.agent

import com.rickeal.agent.core.engine.EngineEnvironment
import com.rickeal.agent.core.engine.EngineFactory
import com.rickeal.agent.core.engine.GenerationRequest
import com.rickeal.agent.core.model.ChatMessage
import com.rickeal.agent.core.model.EngineKind
import com.rickeal.agent.core.model.FinishReason
import com.rickeal.agent.core.model.InferenceConfig
import com.rickeal.agent.core.model.Role
import com.rickeal.agent.core.model.StreamAccumulator
import com.rickeal.agent.core.model.ToolCall
import com.rickeal.agent.core.model.ToolResult
import com.rickeal.agent.core.model.ToolSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.cancellable
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withTimeout

/**
 * 停止条件段（6 行）。端侧 4B 模型的上下文极宝贵，这里刻意保持最短：
 * 只说「什么时候必须停」，不复述大项目那套长契约。
 */
private val STOP_CONDITIONS: String = """
    【停止条件】目标是尽快完成并停止，而不是持续工作：
    1. 目标已达成：确认完成证据后立即停止；标记完成后不得再继续工作。
    2. 已无可执行动作、只能等用户下一条消息时：把「等待」当作停止条件，直接给出结论，不要发占位等待消息。
    3. 本轮必须拒绝（安全或策略边界）时：立即停止，不要重试同样的拒绝；安全拒绝是终态。
    4. 重复同一份摘要、或反复回到同一个「下车点」，都不算进展。
    5. 同一阻塞条件连续出现 3 轮才可报告「无法完成」；困难、缓慢、不确定都不算 blocked。
""".trimIndent()

/** 命中重复时的提醒（每轮最多注入一次，且每个签名只提醒一次）。 */
private const val REPEAT_REMINDER: String =
    "你的最新回复重复了先前的回复。不要重复同一份摘要或同一下车点，重新检视证据，选择一个实质不同的下一步。"

/** 连续多轮零工具调用时的提醒。 */
private const val NO_TOOL_REMINDER: String =
    "已经连续多轮没有执行任何工具。复述计划、状态或意图都不算进展：要么调用工具去获取证据，要么给出结论并停止。"

/** 归一化签名的最小长度：过短的口头语（「好的」「完成」）不算下车点。 */
private const val MIN_SIGNATURE_CHARS = 8

/** 连续零工具调用的告警阈值。 */
private const val NO_TOOL_STREAK_LIMIT = 3

/**
 * Agent 主循环（架构文档 §4.1 / §4.6）。
 *
 * 兼容策略（重点）：优先用「模型原生 tool 通道」（EngineCapabilities.nativeToolChannel == true，
 * 即 OpenAI 兼容后端）；否则（LiteRT-LM 本地，nativeToolChannel=false）走文本协议。
 * 两者结果统一成 ToolCall，后续流程完全一致 —— 这样即便 LiteRT-LM 的 ToolProvider API
 * 我们不敢用，工具能力也不会缺失。
 *
 * 停止策略：让模型**自己会停**（系统提示词里的停止条件 + 重复检测提醒），
 * maxRounds 只作为异常兜底，不再是常态退出路径。
 */
class AgentRunner(
    private val engineFactory: EngineFactory,
    private val toolRegistry: ToolRegistry,
    private val environment: EngineEnvironment,
    private val compressor: ContextCompressor = WindowContextCompressor(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {

    fun run(request: AgentRequest): Flow<AgentEvent> = flow {
        val policy = request.policy
        val config: InferenceConfig = request.config.coerce()
        val kind: EngineKind = if (request.endpoint != null) EngineKind.REMOTE else EngineKind.LOCAL
        val engine = engineFactory.create(kind)

        try {
            engine.load(environment.loadConfig(request.model, request.endpoint, config))
        } catch (t: Throwable) {
            emit(AgentEvent.Failed("引擎加载失败：${t.message}", t))
            return@flow
        }

        val capabilities = try {
            engine.capabilities()
        } catch (t: Throwable) {
            null
        }
        val useNativeTools = (capabilities?.nativeToolChannel == true) && config.enableTools

        val availableTools: List<ToolSpec> = if (config.enableTools) {
            toolRegistry.specs().filter { request.toolNames?.contains(it.name) ?: true }
        } else {
            emptyList()
        }
        // 文本协议模式的「可执行」判据：工具名必须真的在当前可用集合里。
        // 名字不认识的 JSON 一律按最终答案处理（见 TextToolProtocol.parse 注释），
        // 否则模型输出普通 JSON（如 {"name":"张三"}）时会被误判成工具调用而反复重试。
        val registeredToolNames: Set<String> = availableTools.map { it.name }.toSet()

        val working = ArrayList<ChatMessage>()
        // 只要「有系统指令」或「有可用工具」就必须带系统消息：停止条件段要靠它下发，
        // 文本协议模式下模型也才能从里面读到工具清单（systemInstruction 默认是空串，
        // 旧写法会让这两样都永远送不到模型）。
        if (config.systemInstruction.isNotBlank() || availableTools.isNotEmpty()) {
            working.add(ChatMessage(role = Role.SYSTEM, text = buildSystemInstruction(config, availableTools)))
        }
        working.addAll(request.history)
        // history 可能已经把本轮用户输入拼在末尾（调用方常见写法：messages + userInput），
        // 无条件再 add 一次会让用户消息在上下文里出现两遍，既浪费 token 也会干扰模型。
        if (request.history.none { it.id == request.userInput.id }) {
            working.add(request.userInput)
        }

        var round = 0
        var finalText = ""
        var lastUsage = request.history.firstOrNull()?.usage
        var lastModelText = ""

        // ── 「不会停」的防线 ───────────────────────────────────────────────
        // 端侧 4B 最常见的失败不是不会做，而是不会停：重复同一段摘要、反复回到同一个
        // 「下车点」。按轮记录可见文本的归一化签名，命中历史就注入一次提醒。
        val seenSignatures = HashSet<String>()
        val remindedSignatures = HashSet<String>()
        var noToolStreak = 0
        var noToolReminderSent = false
        var pendingReminder: String? = null

        while (round < policy.maxRounds) {
            emit(AgentEvent.RoundStarted(round, policy.maxRounds))

            val budget = (config.contextLength * policy.compressThreshold).toInt()
            val window = if (policy.compressContext) {
                compressor.compress(working, budget)
            } else {
                working
            }

            val accumulator = StreamAccumulator()
            val generationRequest = GenerationRequest(
                // 发出去之前做一次配对清洗：压缩可能切掉工具组的一半，这里补上最后一道保险，
                // 避免 provider 收到「有 tool 结果没 tool_call」而报 400。
                messages = sanitizeForProvider(window),
                config = config,
                model = request.model,
                remote = request.endpoint,
                tools = if (useNativeTools) availableTools else emptyList(),
                conversationId = request.conversationId,
            )

            try {
                engine.generateStream(generationRequest).collect { chunk ->
                    accumulator.append(chunk)
                    if (chunk.textDelta.isNotEmpty()) emit(AgentEvent.TextDelta(chunk.textDelta))
                    if (chunk.thinkingDelta.isNotEmpty()) emit(AgentEvent.ThinkingDelta(chunk.thinkingDelta))
                }
            } catch (t: Throwable) {
                if (t is CancellationException) {
                    emit(AgentEvent.Cancelled(accumulator.text))
                    throw t
                }
                emit(AgentEvent.Failed("生成失败：${t.message}", t))
                return@flow
            }

            if (accumulator.finishReason == FinishReason.CANCELLED) {
                emit(AgentEvent.Cancelled(accumulator.text))
                return@flow
            }
            if (accumulator.usage != null) lastUsage = accumulator.usage
            lastModelText = accumulator.text

            val nativeCalls = accumulator.toolCalls()
            val protocol: ProtocolResult = if (nativeCalls.isEmpty() && policy.enableTextProtocol) {
                TextToolProtocol.parse(accumulator.text, registeredToolNames)
            } else {
                ProtocolResult.NoProtocol
            }
            val calls: List<ToolCall> = when {
                nativeCalls.isNotEmpty() -> nativeCalls
                protocol is ProtocolResult.Calls -> protocol.calls
                else -> emptyList()
            }
            // 「形状像工具调用但工具名没注册 / 参数不合法」→ 直接当最终答案收尾，绝不重试解析，
            // 否则模型把用户要的 JSON 当答案输出时会无限循环。此处保留原文（不 strip），
            // 因为用户可能就是要这段 JSON。
            val protocolFinalAnswer: String? = (protocol as? ProtocolResult.FinalAnswer)?.text
            val visibleText = if (policy.enableTextProtocol) TextToolProtocol.strip(accumulator.text) else accumulator.text

            // 无进展检测：拿本轮「可见文本」的归一化签名比对历史。
            val signature = progressSignature(visibleText)
            if (signature != null) {
                val firstSight = seenSignatures.add(signature)
                // 纪律：先把「已提醒」标记置位，再排队提醒 —— 即使后续注入失败也不会重试，
                // 从而杜绝提醒风暴。每个签名至多提醒一次。
                if (!firstSight && remindedSignatures.add(signature)) {
                    pendingReminder = REPEAT_REMINDER
                }
            }
            // 连续零工具调用计数。正常情况下这种轮次就是终局（下面会 break），
            // 只有「重复提醒」把循环续上时才会累加 —— 正好覆盖「只复述计划不干活」的病态循环。
            if (calls.isEmpty()) {
                noToolStreak++
                if (noToolStreak >= NO_TOOL_STREAK_LIMIT && !noToolReminderSent && pendingReminder == null) {
                    noToolReminderSent = true        // 同样是先置位、再排队
                    pendingReminder = NO_TOOL_REMINDER
                }
            } else {
                noToolStreak = 0
            }

            if (calls.isEmpty()) {
                val cleanText = protocolFinalAnswer ?: visibleText
                val reminder = pendingReminder
                if (reminder != null) {
                    // 本轮是「重复的下车点」：不把它当答案交付，注入一次提醒后再给模型一轮机会。
                    // 每个签名只会被提醒一次（标记已在检测处前置位），叠加 maxRounds 兜底，不会形成新循环。
                    working.add(
                        ChatMessage(
                            role = Role.MODEL,
                            text = cleanText,
                            thinking = accumulator.thinking.takeIf { it.isNotBlank() },
                            finishReason = accumulator.finishReason ?: FinishReason.STOP,
                        )
                    )
                    working.add(ChatMessage(role = Role.USER, text = reminder))
                    pendingReminder = null
                    round++
                    continue
                }
                finalText = cleanText
                val committed = ChatMessage(
                    role = Role.MODEL,
                    text = cleanText,
                    thinking = accumulator.thinking.takeIf { it.isNotBlank() },
                    usage = accumulator.usage,
                    finishReason = accumulator.finishReason ?: FinishReason.STOP,
                    modelRef = request.model?.id ?: request.endpoint?.id,
                )
                working.add(committed)
                emit(AgentEvent.MessageCommitted(committed))
                break
            }

            working.add(
                ChatMessage(
                    role = Role.MODEL,
                    text = accumulator.text,
                    thinking = accumulator.thinking.takeIf { it.isNotBlank() },
                    toolCalls = calls,
                    finishReason = FinishReason.TOOL_CALLS,
                )
            )

            for (call in calls) {
                val tool = toolRegistry.get(call.name)
                if (tool == null) {
                    val result = ToolResult(
                        callId = call.id,
                        name = call.name,
                        ok = false,
                        output = "",
                        errorMessage = "未注册的工具：${call.name}",
                    )
                    emit(AgentEvent.ToolResultReceived(result))
                    working.add(ChatMessage(role = Role.TOOL, toolResults = listOf(result)))
                    continue
                }
                if (tool.spec.dangerous && !policy.autoApproveDangerous) {
                    emit(AgentEvent.ToolSkipped(call, "危险工具需用户授权"))
                    val result = ToolResult(
                        callId = call.id,
                        name = call.name,
                        ok = false,
                        output = "",
                        errorMessage = "该工具需要用户授权后才会执行",
                    )
                    working.add(ChatMessage(role = Role.TOOL, toolResults = listOf(result)))
                    continue
                }

                emit(AgentEvent.ToolCallStarted(call))
                val result = executeWithGuard(call, tool, policy)
                emit(AgentEvent.ToolResultReceived(result))
                working.add(ChatMessage(role = Role.TOOL, toolResults = listOf(result)))
            }

            // 提醒作为下一轮 messages 里的合成 user 消息（槽位是单值，所以每轮最多注入一次）。
            val reminder = pendingReminder
            if (reminder != null) {
                working.add(ChatMessage(role = Role.USER, text = reminder))
                pendingReminder = null
            }

            round++
        }

        // 循环退出只有两种可能：① 模型自己给出最终答案（finalText 非空）；② 轮次耗尽兜底。
        // 注意：这里**绝不**注入「请现在直接回答」之类的收尾提示再进循环 —— 那句话会被模型
        // 回显成工具调用形状的 JSON，又被文本协议解析成工具调用，正是我们要避免的死循环。
        val exhausted = finalText.isBlank()
        val outgoing = if (!exhausted) {
            finalText
        } else {
            // 轮次耗尽时不能把「带工具 JSON 的原始输出」当答案，先剥掉协议片段再交付；
            // 若连可见文本都没有，就合成一条用户可见的收尾说明（否则 UI 收到空串会静默结束）。
            val visible = if (policy.enableTextProtocol) TextToolProtocol.strip(lastModelText) else lastModelText
            visible.ifBlank {
                "本轮因达到轮次上限（${policy.maxRounds} 轮）而结束。可以让我继续，或换一种说法再试。"
            }
        }
        emit(
            AgentEvent.Finished(
                text = outgoing,
                rounds = round,
                usage = lastUsage,
                terminatedBy = if (exhausted) TerminationReason.MaxRounds else TerminationReason.ModelStopped,
            )
        )
    }
        .flowOn(dispatcher)
        .cancellable()

    private suspend fun executeWithGuard(call: ToolCall, tool: Tool, policy: AgentPolicy): ToolResult {
        val started = System.currentTimeMillis()
        return try {
            // 工具会做文件读写 / 剪贴板 / 进程外调用，必须离开调用方线程（Default/Main）跑在 IO 上
            val raw = withTimeout(policy.toolTimeoutMillis) {
                withContext(Dispatchers.IO) { tool.invoke(call.argumentsJson) }
            }
            val output = raw.output
            val truncated = output.length > policy.maxToolOutputChars
            raw.copy(
                output = if (truncated) output.take(policy.maxToolOutputChars) + "\n…(已截断)" else output,
                elapsedMillis = System.currentTimeMillis() - started,
                truncated = truncated,
            )
        } catch (t: Throwable) {
            val message = if (t is kotlinx.coroutines.TimeoutCancellationException) {
                "工具执行超时（${policy.toolTimeoutMillis}ms）"
            } else {
                t.message ?: "工具执行异常"
            }
            ToolResult(
                callId = call.id,
                name = call.name,
                ok = false,
                output = "",
                errorMessage = message,
                elapsedMillis = System.currentTimeMillis() - started,
            )
        }
    }

    private fun buildSystemInstruction(config: InferenceConfig, tools: List<ToolSpec>): String {
        val sections = ArrayList<String>(3)
        if (config.systemInstruction.isNotBlank()) sections.add(config.systemInstruction)
        if (tools.isNotEmpty()) {
            sections.add(
                "你可以使用以下工具。当需要调用工具时，请只输出一个 ```json 代码块，格式为：" +
                    "[{\"tool\": \"工具名\", \"arguments\": {\"参数名\": 值}}]，不要输出其它文字。\n可用工具：\n" +
                    tools.joinToString("\n") { it.toPromptLine() }
            )
        }
        // 停止条件始终下发：这是让 4B 模型「自己会停」的主要手段。
        sections.add(STOP_CONDITIONS)
        return sections.joinToString("\n\n")
    }

    /**
     * 「同一段话」的归一化签名：去空白、去标点、小写。
     * 直接用归一化后的字符串做集合键 —— 等价于哈希，但不会因为哈希碰撞把不同文本误判成重复。
     * 过短的口头语（「好的」「完成」）不算下车点，返回 null 直接跳过，避免无谓多跑一轮。
     */
    private fun progressSignature(text: String): String? {
        val normalized = text.lowercase().filter { it.isLetterOrDigit() }
        return normalized.takeIf { it.length >= MIN_SIGNATURE_CHARS }
    }
}
