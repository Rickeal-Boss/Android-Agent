package com.rickeal.agent.core.agent

import android.content.ClipboardManager
import android.content.Context
import com.rickeal.agent.core.model.ToolEffect
import com.rickeal.agent.core.model.ToolResult
import com.rickeal.agent.core.model.ToolSpec
import java.io.File

/** 工具执行环境。所有 Android 侧能力都从这里注入，方便测试。 */
data class ToolContext(
    val sandboxDir: File,
    /** Application Context，避免持有 Activity */
    val appContext: Context,
    val clipboard: ClipboardManager? = null,
    val nowMillis: () -> Long = { System.currentTimeMillis() },
    /** 长期记忆（harness-memory 移植）；null = 不装配记忆工具。 */
    val agentMemory: com.rickeal.agent.core.agent.memory.AgentMemory? = null,
)

interface Tool {
    val spec: ToolSpec
    suspend fun invoke(argumentsJson: String): ToolResult
}

/**
 * 参数级审批：同一工具的读写参数风险不同（clipboard get/set 是典型）时的细粒度闸门。
 *
 * AgentRunner 在静态判据（`spec.dangerous || spec.requiresConfirmation`）之外，
 * 对实现本接口的工具追加一次参数探测 —— 实现必须 **fail-closed**：参数解析失败
 * 返回 true（宁可多弹卡，不可漏弹）。纯函数约定：实现不得有状态、不得做 IO。
 */
interface ParamGatedTool : Tool {
    fun requiresConfirmationFor(argumentsJson: String): Boolean
}

/**
 * **本次调用**的效果声明（Wave 26 / Operit2 `accessSpec(tool)` 语义移植）。
 *
 * 为什么不能只靠 `ToolSpec.effect` 静态字段：同一个工具在不同参数下效果不同 ——
 * 剪贴板 `get` 是读、`set` 是写；将来若接入终端，`get_terminal_info` 是读而
 * `execute_*` 是写。静态字段只能是保守默认，**按参数判定必须发生在调用时**。
 *
 * 实现纪律（与 [ParamGatedTool] 同款）：
 * - **纯函数**：不得有状态、不得做 IO、不得有副作用（它只是「声明」，不执行）。
 * - **fail-closed**：参数解析失败必须返回 [ToolEffect.WRITE]（宁可多要一次授权，
 *   不可把写操作误判成读而在只读档位下静默放行）。
 */
interface EffectAwareTool : Tool {
    fun effectFor(argumentsJson: String): ToolEffect
}

/** 扩展点：第三方可注册自定义工具（无需注解处理器、无需 ServiceLoader）。 */
fun interface ToolContributor {
    fun contribute(context: ToolContext): List<Tool>
}
