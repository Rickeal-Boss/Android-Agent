package com.rickeal.agent.core.agent.tools

import android.content.ClipData
import android.content.ClipboardManager
import com.rickeal.agent.core.agent.ParamGatedTool
import com.rickeal.agent.core.agent.Tool
import com.rickeal.agent.core.agent.ToolContext
import com.rickeal.agent.core.model.ToolParameter
import com.rickeal.agent.core.model.ToolParamType
import com.rickeal.agent.core.model.ToolResult
import com.rickeal.agent.core.model.ToolSpec

class ClipboardTool(private val context: ToolContext) : ParamGatedTool {
    override val spec: ToolSpec = ToolSpec(
        name = "clipboard",
        description = "读取或写入系统剪贴板。action 取 get 或 set；set 时需要 text",
        parameters = listOf(
            ToolParameter("action", ToolParamType.STRING, "get / set", required = true, enumValues = listOf("get", "set")),
            ToolParameter("text", ToolParamType.STRING, "set 时要写入的文本", required = false),
        ),
        category = "system",
    )

    /**
     * 参数级审批（r6 审查 P2-2）：写剪贴板是敏感副作用（可能覆写用户复制中的
     * 密码/验证码），读取则无害 —— 整工具标 requiresConfirmation 会连 get 一起
     * 弹卡，所以按参数判。解析失败按 true 兜底（fail-closed，ParamGatedTool 约定）。
     */
    override fun requiresConfirmationFor(argumentsJson: String): Boolean =
        runCatching { stringArg(argumentsJson, "action") == "set" }.getOrDefault(true)

    override suspend fun invoke(argumentsJson: String): ToolResult {
        val manager: ClipboardManager? = context.clipboard
        if (manager == null) {
            return ToolResult(name = spec.name, ok = false, errorMessage = "剪贴板不可用")
        }
        return when (stringArg(argumentsJson, "action")) {
            "set" -> {
                val text = stringArg(argumentsJson, "text")
                manager.setPrimaryClip(ClipData.newPlainText("LiquidAgent", text))
                ToolResult(name = spec.name, ok = true, output = "已写入剪贴板（${text.length} 字符）")
            }
            else -> {
                val clip = manager.primaryClip
                val text = if (clip != null && clip.itemCount > 0) {
                    clip.getItemAt(0).coerceToText(context.appContext).toString()
                } else {
                    ""
                }
                ToolResult(name = spec.name, ok = true, output = text.ifBlank { "（剪贴板为空）" })
            }
        }
    }
}
