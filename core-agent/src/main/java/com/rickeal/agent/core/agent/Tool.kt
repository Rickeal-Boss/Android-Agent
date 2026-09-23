package com.rickeal.agent.core.agent

import android.content.ClipboardManager
import android.content.Context
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

/** 扩展点：第三方可注册自定义工具（无需注解处理器、无需 ServiceLoader）。 */
fun interface ToolContributor {
    fun contribute(context: ToolContext): List<Tool>
}
