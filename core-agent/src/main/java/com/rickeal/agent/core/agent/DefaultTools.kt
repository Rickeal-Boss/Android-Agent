package com.rickeal.agent.core.agent

import com.rickeal.agent.core.agent.tools.CalculatorTool
import com.rickeal.agent.core.agent.tools.ClipboardTool
import com.rickeal.agent.core.agent.tools.DateTimeTool
import com.rickeal.agent.core.agent.tools.FileListTool
import com.rickeal.agent.core.agent.tools.FileReadTool
import com.rickeal.agent.core.agent.tools.FileWriteTool
import com.rickeal.agent.core.agent.tools.ImageDescribeTool
import com.rickeal.agent.core.agent.tools.WebSearchTool

/** 内置工具装配（架构文档 §4.7 / §4.8）。 */
fun ToolRegistry.installBuiltInTools(context: ToolContext) {
    register(CalculatorTool())
    register(DateTimeTool(context))
    register(FileReadTool(context))
    register(FileWriteTool(context))
    register(FileListTool(context))
    register(ClipboardTool(context))
    register(WebSearchTool())
    register(ImageDescribeTool())
}
