package com.rickeal.agent.core.engine.remote

import com.rickeal.agent.core.engine.EngineCapabilities
import com.rickeal.agent.core.engine.EngineException
import com.rickeal.agent.core.engine.EngineLoadConfig
import com.rickeal.agent.core.engine.GenerationRequest
import com.rickeal.agent.core.engine.LlmEngine
import com.rickeal.agent.core.model.Attachment
import com.rickeal.agent.core.model.ChatMessage
import com.rickeal.agent.core.model.EngineKind
import com.rickeal.agent.core.model.FinishReason
import com.rickeal.agent.core.model.GenerationChunk
import com.rickeal.agent.core.model.InferenceConfig
import com.rickeal.agent.core.model.RemoteEndpoint
import com.rickeal.agent.core.model.Role
import com.rickeal.agent.core.model.ThinkingMode
import com.rickeal.agent.core.model.ThinkingParamStyle
import com.rickeal.agent.core.model.ToolCallDelta
import com.rickeal.agent.core.model.ToolSpec
import com.rickeal.agent.core.model.TokenUsage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.cancellable
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * OpenAI 兼容后端引擎（DeepSeek / OpenAI / Ollama / vLLM / SiliconFlow）。
 * SSE 手写解析，不用 Retrofit（简报 §6）。
 *
 * 实现偏差（相对架构文档 §3.6，原因见回报）：
 *  - SSE 行读取改用 `ResponseBody.charStream().buffered()`（标准 BufferedReader）而不是
 *    okio 的 `BufferedSource.readUtf8Line()`，避开 okio 扩展函数解析的不确定性。
 *  - 补了 `kotlinx.serialization.decodeFromString / encodeToString` 两个 reified 扩展的 import
 *    （文档片段里漏了）。
 */
class OpenAiCompatibleEngine(
    private val baseClient: OkHttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : LlmEngine {

    override val kind: EngineKind = EngineKind.REMOTE

    private var endpoint: RemoteEndpoint? = null

    @Volatile
    private var loaded: Boolean = false

    override val isLoaded: Boolean
        get() = loaded

    override suspend fun load(config: EngineLoadConfig) {
        val remote = config.remote ?: throw EngineException("远程引擎：未配置 RemoteEndpoint")
        if (remote.baseUrl.isBlank()) throw EngineException("远程引擎：baseUrl 为空")
        if (remote.requiresApiKey && remote.apiKey.isBlank()) {
            throw EngineException("远程引擎：${remote.name} 需要填写 API Key")
        }
        endpoint = remote
        loaded = true
    }

    override suspend fun unload() {
        loaded = false
    }

    override suspend fun capabilities(): EngineCapabilities {
        val remote = endpoint
        return EngineCapabilities(
            supportsText = true,
            supportsImage = remote?.supportsVision ?: false,
            supportsAudio = false,
            supportsTools = remote?.supportsTools ?: false,
            supportsThinking = remote?.supportsThinking ?: false,
            supportedBackends = emptySet(),
            maxContextTokens = remote?.contextLength ?: 32768,
            nativeToolChannel = remote?.supportsTools ?: false,
            nativeThinkingChannel = remote?.supportsThinking ?: false,
            engineLabel = remote?.name.orEmpty(),
        )
    }

    override fun generateStream(request: GenerationRequest): Flow<GenerationChunk> = flow {
        val remote = request.remote ?: endpoint
            ?: throw EngineException("远程引擎：未配置 RemoteEndpoint")
        val config = request.config
        val payload = ChatRequestDto(
            model = remote.modelId,
            messages = buildMessages(request.messages),
            stream = true,
            temperature = config.sampling.temperature,
            topP = config.sampling.topP,
            maxTokens = config.maxTokens,
            seed = config.sampling.seed,
            // OpenAI 无 repetition penalty，用 frequency_penalty 近似
            frequencyPenalty = ((config.sampling.repetitionPenalty - 1f) * 2f)
                .coerceIn(-2f, 2f).takeIf { config.sampling.repetitionPenalty != 1f },
            tools = if (remote.supportsTools && request.tools.isNotEmpty()) {
                request.tools.map { it.toDto() }
            } else {
                null
            },
            toolChoice = if (remote.supportsTools && request.tools.isNotEmpty()) "auto" else null,
            enableThinking = if (remote.thinkingParam == ThinkingParamStyle.ENABLE_THINKING_BOOL) {
                thinkingEnabled(config, remote)
            } else {
                null
            },
            reasoningEffort = if (remote.thinkingParam == ThinkingParamStyle.REASONING_EFFORT) {
                remote.thinkingEffort
            } else {
                null
            },
            chatTemplateKwargs = if (remote.thinkingParam == ThinkingParamStyle.CHAT_TEMPLATE_KWARGS) {
                buildJsonObject {
                    put("enable_thinking", JsonPrimitive(thinkingEnabled(config, remote) ?: false))
                }
            } else {
                null
            },
        )

        val requestBody = json.encodeToString(payload)
        val httpRequest = Request.Builder()
            .url(remote.chatCompletionsUrl())
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "text/event-stream")
            .apply {
                if (remote.apiKey.isNotBlank()) {
                    addHeader("Authorization", "Bearer ${remote.apiKey}")
                }
                for ((key, value) in remote.extraHeaders) addHeader(key, value)
            }
            .post(requestBody.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        // 流式读取不能设 readTimeout
        val client = baseClient.newBuilder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
        val call = client.newCall(httpRequest)
        val startNs = System.nanoTime()
        var firstTokenNs = 0L
        var chunkCount = 0

        val response = try {
            call.execute()
        } catch (t: Throwable) {
            throw EngineException("远程引擎：请求失败 (${t.message})", t)
        }

        try {
            if (!response.isSuccessful) {
                val errorBody = runCatching { response.body?.string() }.getOrNull().orEmpty()
                throw EngineException("远程引擎：HTTP ${response.code} ${errorBody.take(300)}")
            }
            // charStream() 返回的是标准 java.io.Reader，不依赖 okio 扩展函数
            val reader = response.body?.charStream()?.buffered()
                ?: throw EngineException("远程引擎：响应体为空")

            while (true) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                if (!line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                if (data.isEmpty()) continue
                if (data == "[DONE]") break

                val dto = try {
                    json.decodeFromString<StreamChunkDto>(data)
                } catch (t: Throwable) {
                    continue // 心跳 / 非法行，跳过
                }

                val choice = dto.choices.firstOrNull()
                val delta = choice?.delta
                if (delta != null) {
                    if (firstTokenNs == 0L) firstTokenNs = System.nanoTime()
                    val text = delta.content.orEmpty()
                    val thinking = delta.reasoningContent ?: delta.reasoning.orEmpty()
                    if (text.isNotEmpty() || thinking.isNotEmpty()) {
                        chunkCount++
                        emit(GenerationChunk(textDelta = text, thinkingDelta = thinking))
                    }
                    val toolDeltas = delta.toolCalls
                    if (!toolDeltas.isNullOrEmpty()) {
                        for (toolDelta in toolDeltas) {
                            emit(
                                GenerationChunk(
                                    toolCallDelta = ToolCallDelta(
                                        index = toolDelta.index,
                                        id = toolDelta.id,
                                        name = toolDelta.function?.name,
                                        argumentsFragment = toolDelta.function?.arguments.orEmpty(),
                                    )
                                )
                            )
                        }
                    }
                }

                val reason = choice?.finishReason
                if (reason != null) {
                    emit(GenerationChunk(finishReason = mapFinishReason(reason)))
                }
                if (dto.usage != null) {
                    val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
                    emit(
                        GenerationChunk(
                            usage = TokenUsage(
                                promptTokens = dto.usage.promptTokens,
                                completionTokens = dto.usage.completionTokens,
                                totalTokens = dto.usage.totalTokens,
                                tokensPerSecond = if (elapsedMs > 0) {
                                    dto.usage.completionTokens * 1000f / elapsedMs
                                } else {
                                    0f
                                },
                                firstTokenLatencyMillis = if (firstTokenNs == 0L) {
                                    0L
                                } else {
                                    (firstTokenNs - startNs) / 1_000_000L
                                },
                                decodeMillis = elapsedMs,
                            )
                        )
                    )
                }
            }

            if (chunkCount == 0) {
                emit(GenerationChunk(finishReason = FinishReason.STOP))
            }
        } finally {
            runCatching { response.close() }
            runCatching { call.cancel() }
        }
    }
        .flowOn(ioDispatcher)
        .cancellable()

    override suspend fun stop() {
        // SSE 是冷流：取消 collect 即断开连接，没有额外句柄。这里只做状态复位。
    }

    override suspend fun tokenCount(text: String): Int {
        val remote = endpoint ?: return 0
        return (text.length * 1000 / remote.contextLength.coerceAtLeast(1)).coerceAtLeast(1)
    }

    override fun close() {
        loaded = false
        endpoint = null
    }

    private fun thinkingEnabled(config: InferenceConfig, remote: RemoteEndpoint): Boolean? = when (config.thinking) {
        ThinkingMode.ON -> true
        ThinkingMode.OFF -> false
        ThinkingMode.AUTO -> if (remote.supportsThinking) true else null
    }

    private fun mapFinishReason(reason: String): FinishReason = when (reason) {
        "stop" -> FinishReason.STOP
        "length" -> FinishReason.LENGTH
        "tool_calls", "function_call" -> FinishReason.TOOL_CALLS
        "content_filter" -> FinishReason.FILTER
        else -> FinishReason.STOP
    }

    private fun buildMessages(messages: List<ChatMessage>): List<MessageDto> = messages.map { message ->
        val roleName = when (message.role) {
            Role.SYSTEM -> "system"
            Role.USER -> "user"
            Role.MODEL -> "assistant"
            Role.TOOL -> "tool"
        }
        val toolResults = message.toolResults
        MessageDto(
            role = roleName,
            content = buildContent(message),
            toolCalls = message.toolCalls.takeIf { it.isNotEmpty() }?.map { call ->
                ToolCallDto(
                    id = call.id,
                    function = ToolFunctionDto(name = call.name, arguments = call.argumentsJson),
                )
            },
            toolCallId = toolResults.firstOrNull()?.callId?.takeIf { roleName == "tool" },
        )
    }

    private fun buildContent(message: ChatMessage): JsonElement {
        val parts = ArrayList<JsonElement>()
        if (message.text.isNotEmpty()) parts.add(JsonPrimitive(message.text))
        for (attachment in message.attachments) {
            when (attachment) {
                is Attachment.Image -> {
                    val dataUri = imageToDataUri(attachment.uri)
                    if (dataUri != null) {
                        parts.add(
                            buildJsonObject {
                                put("type", JsonPrimitive("image_url"))
                                put(
                                    "image_url",
                                    buildJsonObject { put("url", JsonPrimitive(dataUri)) }
                                )
                            }
                        )
                    }
                }
                is Attachment.Audio -> Unit
                is Attachment.File -> Unit
                is Attachment.Text -> if (attachment.text.isNotBlank()) {
                    parts.add(
                        buildJsonObject {
                            put("type", JsonPrimitive("text"))
                            put("text", JsonPrimitive(attachment.text))
                        }
                    )
                }
            }
        }
        if (parts.isEmpty()) return JsonPrimitive("")
        if (parts.size == 1 && message.attachments.isEmpty() && message.text.isNotEmpty()) {
            return JsonPrimitive(message.text)
        }
        return JsonArray(parts)
    }

    private fun imageToDataUri(uri: String): String? {
        return try {
            val file = java.io.File(if (uri.startsWith("file://")) uri.removePrefix("file://") else uri)
            if (!file.exists()) return null
            val bytes = file.readBytes()
            val base64 = Base64.getEncoder().encodeToString(bytes)
            val mime = when {
                uri.endsWith(".jpg", true) || uri.endsWith(".jpeg", true) -> "image/jpeg"
                uri.endsWith(".webp", true) -> "image/webp"
                else -> "image/png"
            }
            "data:$mime;base64,$base64"
        } catch (t: Throwable) {
            null
        }
    }

    private fun ToolSpec.toDto(): ToolDto = ToolDto(
        function = FunctionDto(
            name = name,
            description = description,
            parameters = buildJsonObject {
                put("type", JsonPrimitive("object"))
                put(
                    "properties",
                    buildJsonObject {
                        for (parameter in parameters) {
                            put(
                                parameter.name,
                                buildJsonObject {
                                    put("type", JsonPrimitive(parameter.type.toJsonType()))
                                    put("description", JsonPrimitive(parameter.description))
                                }
                            )
                        }
                    }
                )
                put(
                    "required",
                    buildJsonArray {
                        for (parameter in parameters.filter { it.required }) {
                            add(JsonPrimitive(parameter.name))
                        }
                    }
                )
            },
        )
    )

    private fun com.rickeal.agent.core.model.ToolParamType.toJsonType(): String = when (this) {
        com.rickeal.agent.core.model.ToolParamType.STRING -> "string"
        com.rickeal.agent.core.model.ToolParamType.NUMBER -> "number"
        com.rickeal.agent.core.model.ToolParamType.INTEGER -> "integer"
        com.rickeal.agent.core.model.ToolParamType.BOOLEAN -> "boolean"
        com.rickeal.agent.core.model.ToolParamType.ARRAY -> "array"
        com.rickeal.agent.core.model.ToolParamType.OBJECT -> "object"
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
