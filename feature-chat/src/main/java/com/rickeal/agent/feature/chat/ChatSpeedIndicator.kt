package com.rickeal.agent.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rickeal.agent.core.design.LocalGlassColors
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

/**
 * 「生成中」实时速度常驻状态行（Wave 47 项4）：`生成中 · ≈ 12.3 tok/s · 首字 0.4s`。
 *
 * ## 定位（为什么不复用气泡里的 usage）
 *
 * 流式气泡**已经**在底部渲染 `tok/s` / 首字（[ChatMessageList] 的 `streaming.usage` → 气泡），
 * 但它字号 `labelSmall`、色 `onGlassSubtle`、藏在气泡内部底部，等待时容易被忽略。本行把它提到
 * 输入区上方的常驻状态行（与 [ChatContextMeter] 同区、同右对齐轴），让用户在等待时**始终可见**。
 *
 * 终态**不重复**：`isStreaming == false` 时本行隐藏，精确值仍由气泡承担（`GlassSurface.usageText`）
 * —— 两处都显示同一精确值属冗余。
 *
 * ## ⚠️ 性能红线（设计 §5.3）
 *
 * **必须自收集**（内部 `collectAsState(streamingFlow)`），**不得**让 [ChatScreen] 顶层订阅
 * `streaming` —— 否则每 120ms 一次 flush 会让整个 `ChatScreen` 重组，推翻 Wave3 的 A 项节流
 * （`ChatMessageList` 的 `streamingFlow` 参数正是为隔离重组而设，本组件沿用同一模式）。
 *
 * ## 口径（设计 §5.4）
 *
 * 数据源 = `_streaming.usage`（与通知渠道 `GenerationNotifier.onTick` **同在
 * `updateStreamingUsage()` 单点**产出 ⇒ 天然同源，不新增第二个计算点）。流式中是**粗估**
 * （`text.length/2`，与引擎终态 chunk 数口径不同）⇒ 文案带 `≈` 前缀区分，**不做对账也不换算**。
 *
 * @param streamingFlow 流式状态独立流（自收集；勿在调用方顶层订阅）。
 */
@Composable
fun ChatSpeedIndicator(
    streamingFlow: StateFlow<StreamingState>,
    modifier: Modifier = Modifier,
) {
    val streaming by streamingFlow.collectAsState()
    val usage = streaming.usage
    // 零数据不渲染（对齐 ChatMessageList「避免 in 0 / out 0」纪律）；终态隐藏（精确值归气泡）。
    if (!streaming.isStreaming || usage == null) return
    if (usage.ttftMillis <= 0L && usage.tokensPerSecond <= 0f) return
    val colors = LocalGlassColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 2.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = speedLabel(usage),
            style = MaterialTheme.typography.labelSmall,
            color = colors.onGlassMuted,
        )
    }
}

/**
 * 状态行文案（纯函数，可 JVM 单测）。
 *
 * - 有速度：`生成中 · ≈ 12.3 tok/s`；有首字：`生成中 · 首字 0.4s`；两者都有则拼接。
 * - `≈` 前缀是**必须**的：这是流式粗估（字符数推算），与气泡终态精确值口径不同。
 * - 首字用秒（保留 1 位）比毫秒更适合常驻行；`decodeMillis` 不显示（终态专有且与 tok/s 同源）。
 */
internal fun speedLabel(usage: StreamingUsage): String {
    val tps = if (usage.tokensPerSecond > 0f) "≈ %.1f tok/s".format(Locale.US, usage.tokensPerSecond) else ""
    val ttft = if (usage.ttftMillis > 0L) "首字 %.1fs".format(Locale.US, usage.ttftMillis / 1000.0) else ""
    return listOf("生成中", tps, ttft).filter { it.isNotEmpty() }.joinToString(" · ")
}
