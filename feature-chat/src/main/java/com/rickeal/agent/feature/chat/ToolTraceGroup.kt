package com.rickeal.agent.feature.chat

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rickeal.agent.core.design.LocalGlassColors

/**
 * 工具过程折叠组（F 项，CodPattern 式过程可见性的端侧降级；正当性来自 r4 审查
 * P2-4 —— 连续工具调用把流式区刷得很长，逐条卡片淹没正文）。纯 UI 层：core-agent
 * 零改动，toolTraces 仍是唯一数据源。
 *
 * 分组规则（toGroups）：RUNNING（含审批占位）与 FAILED **永不折叠**（当前活动与
 * 错误必须醒目独立）；连续 ≥ [MIN_GROUP_SIZE] 条已完结（OK/SKIPPED）折叠为一组。
 * 组 id = 首成员 trace.id（稳定：后续成员追加不换 id，展开状态不丢）。
 */
@Immutable
data class ToolTraceGroup(
    val id: String,
    val title: String,
    val traces: List<ToolTrace>,
    val okCount: Int,
    val skippedCount: Int,
    val failedCount: Int,
)

/** 连续完结轨迹折叠成组的阈值。 */
private const val MIN_GROUP_SIZE = 3

/** 折叠组头行的中文动词映射（工具名 → 过程动词；未命中回退原工具名）。 */
private fun toolVerb(name: String): String = when (name) {
    "file_read" -> "读取文件"
    "file_write" -> "写入文件"
    "file_list" -> "浏览目录"
    "calculator" -> "计算"
    "datetime" -> "查询时间"
    "clipboard" -> "剪贴板"
    "memory_read" -> "翻记忆"
    "ask_actor" -> "询问子代理"
    else -> name
}

/**
 * 把流式区轨迹折叠成渲染序列：可折叠组与必须独立展示的轨迹交错排列。
 * 组内顺序保持原序；RUNNING/FAILED 截断分组（它们前后的完结段各自成组）。
 */
internal fun List<ToolTrace>.toGroups(): List<ToolTraceOrGroup> {
    val out = ArrayList<ToolTraceOrGroup>()
    var buffer = ArrayList<ToolTrace>()
    fun flush() {
        if (buffer.isEmpty()) return
        if (buffer.size >= MIN_GROUP_SIZE) {
            out += ToolTraceOrGroup.Group(
                ToolTraceGroup(
                    id = buffer.first().id,
                    title = toolVerb(buffer.first().name),
                    traces = buffer.toList(),
                    okCount = buffer.count { it.status == ToolTraceStatus.OK },
                    skippedCount = buffer.count { it.status == ToolTraceStatus.SKIPPED },
                    failedCount = buffer.count { it.status == ToolTraceStatus.FAILED },
                ),
            )
        } else {
            for (trace in buffer) out += ToolTraceOrGroup.Single(trace)
        }
        buffer = ArrayList()
    }
    for (trace in this) {
        val foldable = trace.status == ToolTraceStatus.OK || trace.status == ToolTraceStatus.SKIPPED
        if (foldable) {
            buffer += trace
        } else {
            flush()
            out += ToolTraceOrGroup.Single(trace)
        }
    }
    flush()
    return out
}

/** 渲染序列元素：折叠组或独立轨迹。 */
@Immutable
internal sealed interface ToolTraceOrGroup {
    data class Group(val group: ToolTraceGroup) : ToolTraceOrGroup
    data class Single(val trace: ToolTrace) : ToolTraceOrGroup
}

/**
 * 折叠组卡片：头行 = 图标 + 任务动词 + 完成计数 + 展开/收起符号；
 * 展开体逐条复用现有 [ChatToolCard]。animateContentSize 让展开/收起平滑，
 * 不用 graphicsLayer 时钟（纯尺寸动画，无零重组纪律风险）。
 */
@Composable
internal fun ChatTraceGroupCard(
    group: ToolTraceGroup,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalGlassColors.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize()
            .clickable(onClick = onToggle),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Build,
                contentDescription = null,
                tint = colors.onGlassMuted,
                modifier = Modifier.padding(2.dp).size(14.dp),
            )
            Text(
                text = group.title,
                style = MaterialTheme.typography.bodySmall,
                color = colors.onGlassMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = buildString {
                    append("${group.okCount}/${group.traces.size}")
                    if (group.skippedCount > 0) append(" · ${group.skippedCount} 跳过")
                    if (group.failedCount > 0) append(" · ${group.failedCount} 失败")
                },
                style = MaterialTheme.typography.labelSmall,
                color = colors.onGlassSubtle,
            )
            Text(
                text = if (expanded) "▾" else "▸",
                style = MaterialTheme.typography.labelSmall,
                color = colors.onGlassSubtle,
            )
        }
        if (expanded) {
            // 展开体：复用逐条卡片（remember 不需要 —— 组展开是低频交互）。
            for (trace in group.traces) {
                ChatToolCard(
                    name = trace.name,
                    argumentsJson = trace.arguments,
                    output = trace.result,
                    elapsedMillis = trace.elapsedMillis,
                    ok = trace.status == ToolTraceStatus.OK,
                    running = trace.status == ToolTraceStatus.RUNNING,
                )
            }
        }
    }
}

/** 流式区轨迹渲染序列（remember 包裹：toolTraces 引用不变时跳过分组计算）。 */
@Composable
internal fun rememberTraceRenderItems(toolTraces: List<ToolTrace>): List<ToolTraceOrGroup> =
    remember(toolTraces) { toolTraces.toGroups() }
