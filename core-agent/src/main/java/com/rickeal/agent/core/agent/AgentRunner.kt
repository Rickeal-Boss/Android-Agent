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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.cancellable
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withTimeout

/**
 * Agent 主循环（架构文档 §4.1 / §4.6）。
 *
 * 兼容策略（重点）：优先用「模型原生 tool 通道」（EngineCapabilities.nativeToolChannel == true，
 * 即 OpenAI 兼容后端）；否则（LiteRT-LM 本地，nativeToolChannel=false）走文本协议。
 * 两者结果统一成 ToolCall，后续流程完全一致 —— 这样即便 LiteRT-LM 的 ToolProvider API
 * 我们不敢用，工具能力也不会缺失。
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

        val working = ArrayList<ChatMessage>()
        if (config.systemInstruction.isNotBlank()) {
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
                messages = window,
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
            val calls: List<ToolCall> = if (nativeCalls.isNotEmpty()) {
                nativeCalls
            } else if (policy.enableTextProtocol) {
                TextToolProtocol.parse(accumulator.text)
            } else {
                emptyList()
            }

            if (calls.isEmpty()) {
                val cleanText = if (policy.enableTextProtocol) {
                    TextToolProtocol.strip(accumulator.text)
                } else {
                    accumulator.text
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

            round++
        }

        // 轮次耗尽时 finalText 仍为空：此时不能把「上一轮带工具 JSON 的原始输出」当答案，
        // 而应回退到最后一轮的可见文本并剥掉工具协议片段。
        val outgoing = finalText.ifBlank {
            if (policy.enableTextProtocol) TextToolProtocol.strip(lastModelText) else lastModelText
        }
        emit(AgentEvent.Finished(outgoing, round, lastUsage))
    }
        .flowOn(dispatcher)
        .cancellable()

    private suspend fun executeWithGuard(call: ToolCall, tool: Tool, policy: AgentPolicy): ToolResult {
        val started = System.currentTimeMillis()
        return try {
            val raw = withTimeout(policy.toolTimeoutMillis) { tool.invoke(call.argumentsJson) }
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
        if (tools.isEmpty()) return config.systemInstruction
        val header = "你可以使用以下工具。当需要调用工具时，请只输出一个 ```json 代码块，格式为：" +
            "[{\"tool\": \"工具名\", \"arguments\": {\"参数名\": 值}}]，不要输出其它文字。\n可用工具：\n"
        return config.systemInstruction + "\n\n" + header + tools.joinToString("\n") { it.toPromptLine() }
    }
}
