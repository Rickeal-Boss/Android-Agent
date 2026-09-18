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
 * 存在意义：LiteRT-LM 的 onMessage(message) 到底给的是「本帧增量」还是「到目前为止的全文」，
 * 简报 §3.1 无法确定（gallery 里两种用法都见过）。这个启发式对两种情况都正确：
 *  - 若 incoming 以 last 开头且更长 → 认为累积式，取后缀
 *  - 否则 → 认为增量式，整体作为 delta
 */
class DeltaTracker {
    private var last: String = ""

    fun next(incoming: String): String {
        if (incoming.isEmpty()) return ""
        val delta = if (incoming.length > last.length && incoming.startsWith(last)) {
            incoming.substring(last.length)
        } else {
            incoming
        }
        last = incoming
        return delta
    }

    fun reset() {
        last = ""
    }
}
