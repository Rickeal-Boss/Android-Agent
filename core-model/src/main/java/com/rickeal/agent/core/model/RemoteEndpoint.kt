package com.rickeal.agent.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class RemotePreset {
    OPENAI,
    DEEPSEEK,
    OLLAMA,
    VLLM,
    SILICONFLOW,
    CUSTOM,
}

/**
 * 不同后端「开启思考」的字段名不一样，这里用枚举收敛差异，避免在引擎里写 if-else 面条。
 */
@Serializable
enum class ThinkingParamStyle {
    /** 不支持 */
    NONE,

    /** body: enable_thinking = true（Qwen3 / vLLM 部分模型） */
    ENABLE_THINKING_BOOL,

    /** body: reasoning_effort = "low|medium|high"（OpenAI o 系列） */
    REASONING_EFFORT,

    /** body: chat_template_kwargs = { enable_thinking: true }（vLLM 另一派） */
    CHAT_TEMPLATE_KWARGS,
}

@Serializable
data class RemoteEndpoint(
    val id: String = newId(),
    val name: String = "",
    val baseUrl: String = "",
    val apiKey: String = "",
    val modelId: String = "",
    val preset: RemotePreset = RemotePreset.CUSTOM,
    val supportsThinking: Boolean = false,
    val thinkingParam: ThinkingParamStyle = ThinkingParamStyle.NONE,
    val thinkingEffort: String = "medium",
    val supportsTools: Boolean = false,
    val supportsVision: Boolean = false,
    val contextLength: Int = 32768,
    val extraHeaders: Map<String, String> = emptyMap(),
    val addedAtMillis: Long = System.currentTimeMillis(),
) {
    fun chatCompletionsUrl(): String {
        val base = baseUrl.trim().trimEnd('/')
        return if (base.endsWith("/chat/completions")) base else "$base/chat/completions"
    }

    val requiresApiKey: Boolean
        get() = preset != RemotePreset.OLLAMA
}

/** 预置端点，首次启动写入，用户可改。 */
object RemotePresets {
    fun openai(): RemoteEndpoint = RemoteEndpoint(
        id = "preset-openai",
        name = "OpenAI",
        baseUrl = "https://api.openai.com/v1",
        preset = RemotePreset.OPENAI,
        modelId = "gpt-4o-mini",
        supportsTools = true,
        supportsVision = true,
        contextLength = 128000,
    )

    fun deepseek(): RemoteEndpoint = RemoteEndpoint(
        id = "preset-deepseek",
        name = "DeepSeek",
        baseUrl = "https://api.deepseek.com/v1",
        preset = RemotePreset.DEEPSEEK,
        modelId = "deepseek-chat",
        supportsThinking = true,
        thinkingParam = ThinkingParamStyle.ENABLE_THINKING_BOOL,
        supportsTools = true,
        contextLength = 65536,
    )

    fun ollamaLocal(): RemoteEndpoint = RemoteEndpoint(
        id = "preset-ollama",
        name = "Ollama（本机）",
        baseUrl = "http://10.0.2.2:11434/v1",
        preset = RemotePreset.OLLAMA,
        modelId = "qwen3:4b",
        supportsThinking = true,
        thinkingParam = ThinkingParamStyle.ENABLE_THINKING_BOOL,
        contextLength = 32768,
    )

    fun all(): List<RemoteEndpoint> = listOf(openai(), deepseek(), ollamaLocal())
}
