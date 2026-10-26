package com.rickeal.agent.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import com.rickeal.agent.core.design.GlassCard
import com.rickeal.agent.core.design.LocalGlassColors
import com.rickeal.agent.core.model.AgentLog
import com.rickeal.agent.core.model.AgentLogLevel

/**
 * 单条日志行的玻璃卡片呈现。
 *
 * W85-20 从 [DiagnosticsScreen] 内联实现抽出，缓解该文件行数闸门（#50 ≤1150）。
 * [LogRow] 仍需被 [DiagnosticsScreen] 调用，故为 `internal`；其私有辅助
 * [levelColor] / [LOG_MESSAGE_MAX_LINES] 仅本文件使用。[formatLogTime] 沿用
 * [DiagnosticsScreen] 内的 `internal` 实现（与纯文本导出共用同一时钟格式）。
 */
@Composable
internal fun LogRow(log: AgentLog) {
    val colors = LocalGlassColors.current
    GlassCard(contentPadding = PaddingValues(12.dp)) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = log.level.name,
                    style = MaterialTheme.typography.labelMedium,
                    color = levelColor(log.level),
                )
                Text(
                    text = formatLogTime(log.atMillis),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onGlassSubtle,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            Text(
                text = log.message,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = colors.onGlass,
                // W84-07②：正文封顶 6 行 + 溢出省略。单条已被截到 400 字符，但一行
                // 400 字符在窄屏上是几百行的排版量，且这类超长日志往往整批出现（崩溃
                // 现场 / 引擎报错）—— 不封顶既掉帧、又把后面的日志全顶出屏幕。
                maxLines = LOG_MESSAGE_MAX_LINES,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun levelColor(level: AgentLogLevel): Color {
    val colors = LocalGlassColors.current
    return when (level) {
        AgentLogLevel.INFO -> colors.onGlassMuted
        AgentLogLevel.WARN -> colors.warning
        AgentLogLevel.ERROR -> colors.danger
    }
}

/** 单条日志正文的最大展示行数（W84-07②，详见 [DiagnosticsScreen] 原注释）。 */
private const val LOG_MESSAGE_MAX_LINES = 6
