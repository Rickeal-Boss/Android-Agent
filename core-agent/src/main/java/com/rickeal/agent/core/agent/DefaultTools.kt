package com.rickeal.agent.core.agent

import com.rickeal.agent.core.agent.tools.CalculatorTool
import com.rickeal.agent.core.agent.tools.ClipboardTool
import com.rickeal.agent.core.agent.tools.DateTimeTool
import com.rickeal.agent.core.agent.tools.FileListTool
import com.rickeal.agent.core.agent.tools.FileReadTool
import com.rickeal.agent.core.agent.tools.FileWriteTool

/**
 * 内置工具装配（架构文档 §4.7 / §4.8）。
 *
 * 沙箱策略（纯端侧收敛后的工具面）：**没有任何网络类工具**（web_search /
 * image_describe 占位已随远程通道一并移除 —— 模型执行任务的 I/O 边界 =
 * 沙箱目录 + 剪贴板 + 记忆，全部有审批/参数闸门，不存在数据外发通道）。
 */
fun ToolRegistry.installBuiltInTools(context: ToolContext) {
    register(CalculatorTool())
    register(DateTimeTool(context))
    register(FileReadTool(context))
    register(FileWriteTool(context))
    register(FileListTool(context))
    register(ClipboardTool(context))
}
