package com.rickeal.agent.core.model

/**
 * 把一串 GenerationChunk 合并成「完整文本 + 思考文本 + 完整工具调用列表」。
 * 关键：ToolCallDelta 是 JSON 碎片，必须按 index 累积后再整体解析。
 */
class StreamAccumulator {
    private val textBuilder = StringBuilder()
    private val thinkingBuilder = StringBuilder()
    private val partials = LinkedHashMap<Int, PartialToolCall>()

    var finishReason: FinishReason? = null
        private set

    var usage: TokenUsage? = null
        private set

    val text: String get() = textBuilder.toString()
    val thinking: String get() = thinkingBuilder.toString()

    fun append(chunk: GenerationChunk) {
        if (chunk.textDelta.isNotEmpty()) textBuilder.append(chunk.textDelta)
        if (chunk.thinkingDelta.isNotEmpty()) thinkingBuilder.append(chunk.thinkingDelta)
        val delta = chunk.toolCallDelta
        if (delta != null) {
            val partial = partials.getOrPut(delta.index) { PartialToolCall() }
            if (delta.id != null) partial.id = delta.id
            if (delta.name != null) partial.name = delta.name
            if (delta.argumentsFragment.isNotEmpty()) partial.arguments.append(delta.argumentsFragment)
        }
        if (chunk.finishReason != null) finishReason = chunk.finishReason
        if (chunk.usage != null) usage = chunk.usage
    }

    fun toolCalls(): List<ToolCall> = partials.entries
        .sortedBy { it.key }
        .filter { it.value.name.isNotBlank() }
        .map {
            ToolCall(
                id = it.value.id,
                name = it.value.name,
                argumentsJson = it.value.arguments.toString().ifBlank { "{}" },
            )
        }

    private class PartialToolCall(
        var id: String = newId(),
        var name: String = "",
        val arguments: StringBuilder = StringBuilder(),
    )
}

/**
 * 增量提取器。
 *
 * 存在意义：LiteRT-LM 的 onMessage(message) 到底给的是「本帧增量」还是「到目前为止的全文」，
 * 简报 §3.1 无法确定（gallery 里两种用法都见过）。所以这里用**模式锁定**：先用前几帧判断
 * 本次流是哪种形态，一旦判定就锁定，之后按该形态解释所有帧。
 *
 * ## 为什么不能只靠「单帧形状」判断（这是本类曾经出 P1 的地方）
 *
 * 单纯看一帧是**判不出来**的：累积式的「重复帧」（生成结束时又把同样的全文发一遍）
 * 与增量式的「连续两个相同分片」（"！！"、"11"、两个连续的相同 token）
 * 在这一帧上长得**一模一样** —— 都是 `incoming == last`。
 *
 *  - 旧判据 `incoming.length > last.length`：`4 > 4` 不成立 → 重复帧被当成新分片
 *    → 整段回答再拼一遍 → **回答翻倍**（P1，只在本地 LiteRT 引擎出现）。
 *  - 若直接改成 `>=`：上面的翻倍是修好了，但增量式流里连续两个相同分片会产出空增量
 *    （`substring(len)` 是空串），**那一片就静默丢了** —— 是把 P1 换了个方向重犯。
 *
 * 所以要锁定模式：判定只能发生在「`last` 非空的第一帧」上，且**只有「更长 + 以 last 开头」
 * 才认定累积式**。增量式下第一帧之后出现同形分片 ⇒ 判为增量式，之后永远按分片处理。
 *
 * ## 已锁定为累积式后
 * 用 `>=`：长度相等且内容相同 ⇒ 是重复帧 ⇒ 产出空增量（调用方会跳过空增量）。
 */
class DeltaTracker {
    private var last: String = ""

    /** 已锁定的模式：`true` = 累积式全文，`false` = 增量式分片，`null` = 尚未能判定。 */
    private var cumulative: Boolean? = null

    fun next(incoming: String): String {
        if (incoming.isEmpty()) return ""
        val mode = cumulative
        val delta = when {
            mode == true -> {
                // 累积式：incoming 是「到目前为止的全文」。
                // 用 >= 是这里的关键 —— 重复帧（长度相等、内容相同）必须产出空增量。
                if (incoming.length >= last.length && incoming.startsWith(last)) {
                    incoming.substring(last.length)
                } else {
                    incoming
                }
            }
            mode == false -> incoming
            else -> {
                // 首帧（last 为空）**不参与判定**：任何非空串都满足 "以空串开头且更长"，
                // 拿它判定会把两种模式一律判成累积式，等于没判。
                if (last.isNotEmpty() && incoming.length > last.length && incoming.startsWith(last)) {
                    cumulative = true
                    incoming.substring(last.length)
                } else {
                    // 关键：长度相等（incoming == last）**不**判成累积式 ——
                    // 增量式流里连续两个相同分片正是这个形状，判错就会把第二个分片丢掉。
                    if (last.isNotEmpty()) cumulative = false
                    incoming
                }
            }
        }
        last = incoming
        return delta
    }

    fun reset() {
        last = ""
        cumulative = null
    }
}
