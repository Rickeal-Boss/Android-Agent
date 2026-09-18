package com.rickeal.agent.core.agent.tools

import android.content.ClipData
import android.content.ClipboardManager
import com.rickeal.agent.core.agent.Tool
import com.rickeal.agent.core.agent.ToolContext
import com.rickeal.agent.core.model.ToolParameter
import com.rickeal.agent.core.model.ToolParamType
import com.rickeal.agent.core.model.ToolResult
import com.rickeal.agent.core.model.ToolSpec

class ClipboardTool(private val context: ToolContext) : Tool {
    override val spec: ToolSpec = ToolSpec(
        name = "clipboard",
        description = "读取或写入系统剪贴板。action 取 get 或 set；set 时需要 text",
        parameters = listOf(
            ToolParameter("action", ToolParamType.STRING, "get / set", required = true, enumValues = listOf("get", "set")),
            ToolParameter("text", ToolParamType.STRING, "set 时要写入的文本", required = false),
        ),
        category = "system",
    )

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

/**
 * 网络搜索占位。真实实现需要外部 API Key，第一版不引入任何 SDK。
 * 通过构造参数注入 fetcher，未来接任何搜索 API 都不需要改 Agent 层。
 */
class WebSearchTool(
    private val fetcher: suspend (query: String) -> String? = { null },
) : Tool {
    override val spec: ToolSpec = ToolSpec(
        name = "web_search",
        description = "联网搜索（需先在设置中配置搜索服务，未配置时不可用）",
        parameters = listOf(ToolParameter("query", ToolParamType.STRING, "搜索关键词", required = true)),
        category = "network",
    )

    override suspend fun invoke(argumentsJson: String): ToolResult {
        val query = stringArg(argumentsJson, "query")
        if (query.isBlank()) return ToolResult(name = spec.name, ok = false, errorMessage = "缺少 query")
        val result = try {
            fetcher(query)
        } catch (t: Throwable) {
            null
        }
        return if (result.isNullOrBlank()) {
            ToolResult(
                name = spec.name,
                ok = false,
                errorMessage = "网络搜索未配置：请在「设置 → 远程与搜索」中填写搜索服务后再使用",
            )
        } else {
            ToolResult(name = spec.name, ok = true, output = result)
        }
    }
}

/**
 * 图片理解占位。预留注入点：把 (图片uri, 问题) 交给本地 VLM 或远程多模态端点。
 */
class ImageDescribeTool(
    private val describer: suspend (uri: String, question: String) -> String? = { _, _ -> null },
) : Tool {
    override val spec: ToolSpec = ToolSpec(
        name = "image_describe",
        description = "描述一张图片的内容（需要模型或端点支持视觉输入）",
        parameters = listOf(
            ToolParameter("uri", ToolParamType.STRING, "图片路径或 uri", required = true),
            ToolParameter("question", ToolParamType.STRING, "关于这张图的问题", required = false),
        ),
        category = "vision",
    )

    override suspend fun invoke(argumentsJson: String): ToolResult {
        val uri = stringArg(argumentsJson, "uri")
        val question = stringArg(argumentsJson, "question").ifBlank { "请描述这张图片" }
        val result = try {
            describer(uri, question)
        } catch (t: Throwable) {
            null
        }
        return if (result.isNullOrBlank()) {
            ToolResult(name = spec.name, ok = false, errorMessage = "图片理解不可用：当前模型或端点不支持视觉输入")
        } else {
            ToolResult(name = spec.name, ok = true, output = result)
        }
    }
}
