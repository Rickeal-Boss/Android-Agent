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
 * 一次工具调用的**效果类别**（Wave 26 / Operit2 四层能力模型裁剪移植）。
 *
 * 与 [ToolSpec.dangerous] 是两件不同的事，不要混：
 * - `dangerous` 是**工具属性** —— 「这个工具本身危不危险」。
 * - `effect` 是**能力面** —— 「它读还是写」，供 [AiCapabilityMode] 整体收窄 AI 的能力范围。
 *
 * 同一工具在不同参数下可能是 READ 也可能是 WRITE（典型：剪贴板 get/set）。静态字段只作
 * **保守默认**；需要按参数区分时实现 `EffectAwareTool` 动态声明（对齐 Operit2 的
 * 「effect 必须是本次调用的结果，不能是注册期静态元数据」）。
 */
@Serializable
enum class ToolEffect {
    /** 读取状态或内容，不产生副作用。 */
    READ,

    /** 修改状态或内容。 */
    WRITE,
}

/**
 * 工具的「声明」。用于：
 * 1) 传给原生 tool 通道（LiteRT-LM ToolProvider / OpenAI tools）
 * 2) 拼进系统提示词（文本协议模式）
 * 3) UI 展示与开关
 * 4) 能力档位判定（[effect] × [AiCapabilityMode]）
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
     * 效果类别，供能力档位判定。**默认 WRITE 是刻意的 fail-closed**：
     * 新增工具若忘了声明，在 ReadOnly 档位下会被要求授权，而不是被静默放行。
     * 加字段带默认值，旧 JSON 前后兼容（ignoreUnknownKeys + explicitNulls=false）。
     */
    val effect: ToolEffect = ToolEffect.WRITE,
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
