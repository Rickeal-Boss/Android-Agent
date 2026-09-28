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
 * Wave 31 流2 起并列两个口径（**不做换算也不做对账**，见 `RunTokenLedger` KDoc 红线）：
 * - 估算（发送前）：账本 `sentTokens`，由 AgentRunner 发送侧记账块回写 —— 首轮即可见；
 * - 实测（引擎回报）：`TokenUsage.promptTokens` —— 要等引擎回报才有。
 * 两者用 `估算≈` / `实测` 前缀区分。零回归：估算为 null 时退回原行为（只显示实测）；
 * 两者都无数据时不渲染任何东西。
 *
 * @param usedTokens 引擎回报的实测上下文规模（`TokenUsage.promptTokens`）。
 *   `null` 或 `<= 0` 表示**该口径**还没有数据。
 * @param limitTokens 上下文预算，取 `InferenceConfig.contextLength`
 *   （与 `AgentRunner` 的压缩预算同源，不是另猜的上限）。
 * @param sentTokensEstimate 账本发送侧估算（`RunTokenSnapshot.sentTokens`，Wave 31 流2）。
 *   `null` 或 `<= 0` 表示该口径还没有数据。两个口径都无数据时不渲染任何东西 ——
 *   显示「0 / 4K」比不显示更误导。
 */
@Composable
fun ChatContextMeter(
    usedTokens: Int?,
    limitTokens: Int,
    sentTokensEstimate: Long? = null,
    modifier: Modifier = Modifier,
) {
    val estimate = sentTokensEstimate?.takeIf { it > 0L }
    val measured = usedTokens?.takeIf { it > 0 }
    if (limitTokens > 0 && (estimate != null || measured != null)) {
        val colors = LocalGlassColors.current
        // 主口径：优先账本发送侧估算（发送前即可见）；无估算时退回引擎实测（原行为）。
        val primary = estimate ?: (measured ?: 0).toLong()
        val nearFull = primary.toFloat() / limitTokens.toFloat() >= CONTEXT_WARN_RATIO

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
                text = contextLabel(estimate, measured, limitTokens),
                style = MaterialTheme.typography.labelSmall,
                color = if (nearFull) colors.warning else colors.onGlassMuted,
            )
        }
    }
}

/**
 * 口径标签（Wave 31 流2）：估算（发送前）与实测（引擎回报）**并列且不换算**。
 * - 双口径都有：`上下文 估算≈2.1K · 实测 2.0K / 4K`
 * - 仅估算：`上下文 估算≈2.1K / 4K`
 * - 仅实测（原行为）：`上下文 2.0K / 4K`
 */
private fun contextLabel(estimate: Long?, measured: Int?, limit: Int): String {
    val limitText = formatTokenCount(limit.toLong())
    return when {
        estimate != null && measured != null ->
            "上下文 估算≈${formatTokenCount(estimate)} · 实测 ${formatTokenCount(measured.toLong())} / $limitText"
        estimate != null -> "上下文 估算≈${formatTokenCount(estimate)} / $limitText"
        else -> "上下文 ${formatTokenCount((measured ?: 0).toLong())} / $limitText"
    }
}

/**
 * 4096 → "4.1K"，2100 → "2.1K"，800 → "800"。
 * 纯整数运算，避开 `String.format` 在部分 Locale 下把小数点写成逗号的问题。
 *
 * ⚠️ 入参是 `Long`（Wave 31 起，为接收 token 账本的发送侧估算 `Long`）。**改宽类型时
 * 必须同步把函数体里的整数字面量加上 `L`** —— `fraction == 0` 在 `fraction: Long` 下
 * 是 `Long == Int`，Kotlin 直接编译报错（Wave 31 首轮 CI 唯一一处红点，本仓无本地 JDK
 * 无法预演，代价是一整轮 CI 往返）。
 */
private fun formatTokenCount(value: Long): String {
    if (value < 1000L) return value.toString()
    val tenths = (value * 10 + 500) / 1000
    val whole = tenths / 10
    val fraction = tenths % 10
    return if (fraction == 0L) "${whole}K" else "$whole.${fraction}K"
}
