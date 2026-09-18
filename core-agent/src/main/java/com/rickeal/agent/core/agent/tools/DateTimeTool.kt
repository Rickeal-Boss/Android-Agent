package com.rickeal.agent.core.agent.tools

import com.rickeal.agent.core.agent.Tool
import com.rickeal.agent.core.agent.ToolContext
import com.rickeal.agent.core.model.ToolParameter
import com.rickeal.agent.core.model.ToolParamType
import com.rickeal.agent.core.model.ToolResult
import com.rickeal.agent.core.model.ToolSpec
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class DateTimeTool(private val context: ToolContext) : Tool {
    override val spec: ToolSpec = ToolSpec(
        name = "current_time",
        description = "获取当前日期与时间。可选参数 timeZone（如 Asia/Shanghai）、pattern（如 yyyy-MM-dd HH:mm:ss）",
        parameters = listOf(
            ToolParameter("timeZone", ToolParamType.STRING, "时区 ID，默认系统时区", required = false),
            ToolParameter("pattern", ToolParamType.STRING, "时间格式，默认 yyyy-MM-dd HH:mm:ss", required = false),
        ),
        category = "utility",
    )

    override suspend fun invoke(argumentsJson: String): ToolResult {
        val zoneId = runCatching { ZoneId.of(stringArg(argumentsJson, "timeZone")) }
            .getOrElse { ZoneId.systemDefault() }
        val pattern = stringArg(argumentsJson, "pattern").ifBlank { "yyyy-MM-dd HH:mm:ss" }
        val formatter = runCatching { DateTimeFormatter.ofPattern(pattern) }
            .getOrElse { DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss") }
        val instant = Instant.ofEpochMilli(context.nowMillis())
        val formatted = formatter.withZone(zoneId).format(instant)
        return ToolResult(
            name = spec.name,
            ok = true,
            output = "$formatted（时区：$zoneId，epochMillis=${context.nowMillis()}）",
        )
    }
}
