package com.rickeal.agent.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class ToolParamType {
    STRING,
    NUMBER,
    INTEGER,
    BOOLEAN,
    ARRAY,
    OBJECT,
}

@Serializable
data class ToolParameter(
    val name: String,
    val type: ToolParamType = ToolParamType.STRING,
    val description: String = "",
    val required: Boolean = true,
    val enumValues: List<String> = emptyList(),
)

/**
 * 工具的「声明」。用于：
 * 1) 传给原生 tool 通道（LiteRT-LM ToolProvider / OpenAI tools）
 * 2) 拼进系统提示词（文本协议模式）
 * 3) UI 展示与开关
 */
@Serializable
data class ToolSpec(
    val name: String,
    val description: String,
    val parameters: List<ToolParameter> = emptyList(),
    val requiresConfirmation: Boolean = false,
    val dangerous: Boolean = false,
    val category: String = "general",
    /**
     * 单工具超时覆盖（毫秒）；null = 用 AgentPolicy.toolTimeoutMillis。
     * 存在理由：个别工具天然长耗时（如 ask_actor 是一次多轮子推理，默认 15s 必然超时），
     * 全局放宽 policy 又会放过真正卡死的普通工具。可序列化的新字段带默认值，
     * 旧 JSON 前后兼容（ignoreUnknownKeys + explicitNulls=false）。
     */
    val timeoutMillisOverride: Long? = null,
) {
    /** 生成文本协议模式下写进 system prompt 的一行描述。 */
    fun toPromptLine(): String =
        "- " + name + "：" + description +
            (if (parameters.isEmpty()) "" else " 参数(" + parameters.joinToString(",") { it.name } + ")")
}

/** 一次工具调用请求。arguments 保持 JSON 字符串，避免嵌套 Any 的序列化地狱。 */
@Serializable
data class ToolCall(
    val id: String = newId(),
    val name: String = "",
    val argumentsJson: String = "{}",
    val raw: String = "",
)

@Serializable
data class ToolResult(
    val callId: String = "",
    val name: String = "",
    val ok: Boolean = true,
    val output: String = "",
    val errorMessage: String? = null,
    val elapsedMillis: Long = 0L,
    val truncated: Boolean = false,
)
