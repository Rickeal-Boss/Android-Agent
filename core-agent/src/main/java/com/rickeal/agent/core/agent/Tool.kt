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
)

interface Tool {
    val spec: ToolSpec
    suspend fun invoke(argumentsJson: String): ToolResult
}

/** 扩展点：第三方可注册自定义工具（无需注解处理器、无需 ServiceLoader）。 */
fun interface ToolContributor {
    fun contribute(context: ToolContext): List<Tool>
}
