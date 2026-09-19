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
import kotlinx.coroutines.CancellationException
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
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
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

    /**
     * 当前正在进行的流式请求句柄。
     *
     * `stop()` 需要主动中断阻塞中的 `readLine()`：SSE 冷流只有在**协程被取消**时才会断开，
     * 而 `stop()` 是显式挂起方法，若拿不到句柄，用户点「停止」在 readTimeout 触发前不会生效。
     * 用 `@Volatile` 保证跨线程可见（请求跑在 ioDispatcher，`stop()` 可能来自主线程）。
     */
    @Volatile
    private var activeCall: Call? = null

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

        // 必须设一个**有限**的 readTimeout，不能设 0（永不超时）。
        // 理由：正常 SSE 流会持续有数据到达（内容 chunk / 心跳 / 空白行），60 秒一个字节都没有
        // 基本可判定连接已死；而首 token 前的等待通常远小于此。若设 0，一旦服务端接受连接后
        // 不再返回任何字节（弱网、服务端卡死、代理挂起、进程被切后台冻结），readLine() 会
        // 永久阻塞，UI 表现为「一直转圈、不报错、点停止也没用」。
        // connectTimeout 沿用 baseClient 的 15s；callTimeout 保持 0（一次长回答本身可能很久）。
        val client = baseClient.newBuilder()
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
        val call = client.newCall(httpRequest)
        activeCall = call
        val startNs = System.nanoTime()
        var firstTokenNs = 0L
        var chunkCount = 0
        // 是否已发出过带 finishReason 的终帧。用于「连接中途断开」时补发终帧，
        // 避免上层把「有内容但 finishReason 为空」当成正常结束、把半截回答落库。
        var sentTerminal = false
        // 是否收到了服务端显式的结束标记 [DONE]。
        var sawDone = false

        val response = try {
            call.execute()
        } catch (t: Throwable) {
            if (activeCall === call) activeCall = null
            if (isCancellation(t)) throw CancellationException("远程引擎：请求已取消")
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
                if (data == "[DONE]") {
                    sawDone = true
                    break
                }

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
                    sentTerminal = true
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

            // 循环正常退出（EOF / [DONE]）后补终帧，避免「有内容但 finishReason 为空」被上层当成正常结束：
            //  - 一个内容 chunk 都没收到 → STOP（服务端根本没给内容，保持原语义）
            //  - 收到了 [DONE] → STOP（服务端显式宣告结束，视为正常完成）
            //  - 有内容但既没有 finish_reason 也没有 [DONE] → 连接被中途掐断，用 LENGTH 标记「被截断」
            if (!sentTerminal) {
                val reason = when {
                    chunkCount == 0 -> FinishReason.STOP
                    sawDone -> FinishReason.STOP
                    else -> FinishReason.LENGTH
                }
                emit(GenerationChunk(finishReason = reason))
                sentTerminal = true
            }
        } catch (t: Throwable) {
            // 连接中断 / 用户停止 / 读取超时都会走到这里（阻塞中的 readLine() 抛 IOException）。
            if (isCancellation(t)) {
                // 用户主动 stop()（call.cancel() 抛 IOException("Canceled")）或协程被取消。
                // 不能当成错误上报，否则「用户停止」会显示成「出错了」。
                if (!sentTerminal) {
                    emit(GenerationChunk(finishReason = FinishReason.CANCELLED))
                    sentTerminal = true
                }
                // 真正的协程取消必须原样上抛，让结构化并发正常收敛；
                // 仅由 stop() 触发的 OkHttp cancel 则正常结束，上层据 finishReason == CANCELLED 判定。
                if (t is CancellationException) throw t
            } else {
                // 真实网络错误（socket 重置 / readTimeout 触发的 SocketTimeoutException 等）：
                // 已产出的内容属于「被截断」，补一个 LENGTH 终帧再上抛，由 AgentRunner 决定重试或报错。
                if (!sentTerminal && chunkCount > 0) {
                    emit(GenerationChunk(finishReason = FinishReason.LENGTH))
                    sentTerminal = true
                }
                throw EngineException("远程引擎：流式读取中断 (${t.message})", t)
            }
        } finally {
            // 只清理自己这一个句柄：避免极端情况下（同一实例并发两条流）误清新流的句柄。
            if (activeCall === call) activeCall = null
            runCatching { response.close() }
            runCatching { call.cancel() }
        }
    }
        .flowOn(ioDispatcher)
        .cancellable()

    override suspend fun stop() {
        // SSE 是冷流：取消 collect 也会断开连接。但 collect 的取消依赖协程被取消，
        // 而 stop() 是显式调用，必须主动 cancel 当前请求句柄，
        // 否则在 readTimeout 触发前 readLine() 会一直阻塞，用户点「停止」毫无反应。
        activeCall?.cancel()
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

    /**
     * 判断异常是「取消」还是真实错误。
     *  - 协程取消 → [CancellationException]
     *  - OkHttp 的 `Call.cancel()` 会让阻塞中的读取抛 `IOException("Canceled")`
     * 其余（socket 重置、readTimeout 的 SocketTimeoutException 等）都算真实错误。
     */
    private fun isCancellation(t: Throwable): Boolean {
        if (t is CancellationException) return true
        return t is IOException && t.message?.contains("Canceled", ignoreCase = true) == true
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
        // TOOL 消息：内容就是工具执行结果（AgentRunner 只填 toolResults，不填 text），
        // 不处理的话发出去的是 {"role":"tool","content":""}，模型永远看不到工具输出。
        if (message.role == Role.TOOL) {
            val result = message.toolResults.firstOrNull()
            val payload = result?.output?.takeIf { it.isNotBlank() }
                ?: result?.errorMessage
                ?: ""
            return JsonPrimitive(payload)
        }
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

    /**
     * 图片转 data URI。
     *
     * 必须先降采样：手机原图动辄 4000×3000，直接 base64 后是 **十几 MB 的字符串**，
     * 会打爆请求体（HTTP 413）并极易 OOM。这里统一缩到最长边 1024px 再压 JPEG。
     */
    private fun imageToDataUri(uri: String): String? {
        return try {
            val path = if (uri.startsWith("file://")) uri.removePrefix("file://") else uri
            val file = java.io.File(path)
            if (!file.exists()) return null

            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(path, bounds)
            val maxDim = maxOf(bounds.outWidth, bounds.outHeight)
            val sample = if (maxDim > 1024) {
                var s = 1
                while (maxDim / (s * 2) >= 1024) s *= 2
                s
            } else {
                1
            }
            val options = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
            val bitmap = android.graphics.BitmapFactory.decodeFile(path, options) ?: return null
            val scaled = if (maxOf(bitmap.width, bitmap.height) > 1024) {
                val ratio = 1024f / maxOf(bitmap.width, bitmap.height)
                android.graphics.Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * ratio).toInt().coerceAtLeast(1),
                    (bitmap.height * ratio).toInt().coerceAtLeast(1),
                    true,
                )
            } else {
                bitmap
            }
            val out = java.io.ByteArrayOutputStream()
            scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out)
            if (scaled !== bitmap) scaled.recycle()
            bitmap.recycle()
            val base64 = Base64.getEncoder().encodeToString(out.toByteArray())
            "data:image/jpeg;base64,$base64"
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

        /** SSE 流两次字节到达之间的最大间隔；超过即判定连接已死（详见 readTimeout 处注释）。 */
        const val READ_TIMEOUT_SECONDS = 60L
    }
}
