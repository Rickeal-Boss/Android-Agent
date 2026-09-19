package com.rickeal.agent.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rickeal.agent.core.agent.AgentPolicy
import com.rickeal.agent.core.design.LocalGlassColors

/**
 * 「上下文将满」的提示阈值。
 *
 * 直接取 `AgentRunner` 触发上下文压缩的同一比例（`AgentPolicy.compressThreshold`），
 * 而不是另写一个魔数：这样状态条一变色，就精确对应「压缩已经开始、模型即将开始忘事」。
 * 若阈值比压缩点还晚，用户看到变色时其实已经晚了。
 */
private val CONTEXT_WARN_RATIO: Float = AgentPolicy().compressThreshold

/**
 * 一行上下文占用状态：`上下文 2.1K / 4K`。
 *
 * 端侧模型窗口只有 4K 量级，超出会被 `AgentRunner` **静默压缩**。用户看到的只是
 * 「模型怎么突然变傻了 / 忘了前面说过的」，却完全无从理解。把占用量显式摆出来，
 * 就是给「变傻」一个可解释的前兆。
 *
 * @param usedTokens 最近一次请求实际送进模型的上下文规模（`TokenUsage.promptTokens`）。
 *   `null` 或 `<= 0` 表示**还没有数据**，此时不渲染任何东西 —— 显示「0 / 4K」比不显示更误导。
 * @param limitTokens 上下文预算，取 `InferenceConfig.contextLength`
 *   （与 `AgentRunner` 的压缩预算同源，不是另猜的上限）。
 */
@Composable
fun ChatContextMeter(
    usedTokens: Int?,
    limitTokens: Int,
    modifier: Modifier = Modifier,
) {
    if (usedTokens != null && usedTokens > 0 && limitTokens > 0) {
        val colors = LocalGlassColors.current
        val nearFull = usedTokens.toFloat() / limitTokens.toFloat() >= CONTEXT_WARN_RATIO

        Row(
            modifier = modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, top = 2.dp, bottom = 2.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (nearFull) {
                Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = "上下文将满",
                    tint = colors.warning,
                    modifier = Modifier
                        .padding(end = 4.dp)
                        .size(12.dp),
                )
            }
            Text(
                text = "上下文 ${formatTokenCount(usedTokens)} / ${formatTokenCount(limitTokens)}",
                style = MaterialTheme.typography.labelSmall,
                color = if (nearFull) colors.warning else colors.onGlassMuted,
            )
        }
    }
}

/**
 * 4096 → "4.1K"，2100 → "2.1K"，800 → "800"。
 * 纯整数运算，避开 `String.format` 在部分 Locale 下把小数点写成逗号的问题。
 */
private fun formatTokenCount(value: Int): String {
    if (value < 1000) return value.toString()
    val tenths = (value * 10 + 500) / 1000
    val whole = tenths / 10
    val fraction = tenths % 10
    return if (fraction == 0) "${whole}K" else "$whole.${fraction}K"
}
