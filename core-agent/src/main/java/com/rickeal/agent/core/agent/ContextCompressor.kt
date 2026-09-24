package com.rickeal.agent.core.agent

import com.rickeal.agent.core.model.AgentLogStore
import com.rickeal.agent.core.model.ChatMessage
import com.rickeal.agent.core.model.Role
import com.rickeal.agent.core.model.TokenEstimator
import com.rickeal.agent.core.model.ToolResult

interface ContextCompressor {
    suspend fun compress(messages: List<ChatMessage>, budgetTokens: Int): List<ChatMessage>
}

/**
 * 朴素滑动窗口：
 *  - 系统消息与「最近若干条」优先保留
 *  - 从最新往回贪心装填，装不下就丢最老的
 *  - **切点必须落在「工具调用原子组」的边界上**，找不到安全切点就放弃压缩
 *
 * 为什么必须这么严：OpenAI 兼容协议要求每条 `role=tool` 的结果都能对应到声明它的
 * `assistant.tool_calls`。旧实现只按 token 贪心切，会把「assistant(toolCalls) + 紧随其后的
 * toolResult」从中间截断，发出去就是 400。
 * 因此压缩失败必须**无损**（原样返回），绝不「尽力而为地删一半」。
 */
class WindowContextCompressor(
    /**
     * 保留最近 N 条的旧语义。现在切点完全由「预算贪心 + 安全切点调整」决定，该参数不再影响
     * 结果，仅作为普通构造参数保留，以免破坏既有调用方的具名参数写法。
     */
    keepRecent: Int = 8,
) : ContextCompressor {

    override suspend fun compress(messages: List<ChatMessage>, budgetTokens: Int): List<ChatMessage> {
        if (messages.isEmpty()) return messages
        if (TokenEstimator.estimate(messages) <= budgetTokens) return messages

        val system = messages.filter { it.role == Role.SYSTEM }
        val rest = messages.filter { it.role != Role.SYSTEM }
        if (rest.isEmpty()) return messages

        // 1) 从最新往回贪心，求「不超预算的最长后缀」的起点下标。
        // 先把每条的 cost 预算一次存起来：原来在贪心循环里逐条 estimate，等于把整段历史
        // 全量字符遍历两遍（O(n²)）。maxRounds=32 + 关掉压缩 + 长会话时，每轮「思考停顿」
        // 会明显变长，用户感知为「卡住」。
        val costs = IntArray(rest.size) { TokenEstimator.estimate(rest[it]) }
        var used = TokenEstimator.estimate(system)
        var candidate = rest.size
        for (index in rest.indices.reversed()) {
            val cost = costs[index]
            if (used + cost > budgetTokens) break
            used += cost
            candidate = index
        }
        // 连最近一条都装不下：没有任何可保留的东西，无损放弃
        if (candidate >= rest.size) return messages

        // 2) 把候选切点调整到工具组边界；调不到安全位置就无损放弃
        val safe = safeCutIndex(rest, candidate) ?: return messages
        return system + rest.subList(safe, rest.size)
    }

    /**
     * 把候选切点 [candidate] 调整到安全的「工具组边界」，返回调整后的下标；无法保证安全时返回 null。
     *
     * 规则：
     *  - 落点是 toolResult（[Role.TOOL]）→ 按 `toolCallId` 回溯到声明它的 assistant(toolCalls)，
     *    让整组一起进保留区。
     *  - 落点是其它任何消息（USER / 普通 MODEL / SYSTEM）→ 天然安全，直接采用。
     *    这里**选择允许 USER 作为硬边界**：工具组的结构固定为「assistant(toolCalls) 紧跟若干
     *    TOOL」，USER 不可能出现在组内部，截在它之前不会拆散配对，比一律回溯更省 token。
     *  - 回溯不到宿主，或回溯到下标 0 后首条仍是 TOOL → 返回 null，让调用方放弃压缩。
     */
    private fun safeCutIndex(messages: List<ChatMessage>, candidate: Int): Int? {
        var index = candidate
        while (true) {
            if (index <= 0) {
                // 保留区首条仍是 toolResult 说明这段历史缺宿主，满足不了不变量 → 放弃
                return if (messages.firstOrNull()?.role == Role.TOOL) null else 0
            }
            val message = messages[index]
            if (message.role != Role.TOOL) return index
            index = ownerIndex(messages, index) ?: return null
        }
    }

    /** 回溯查找声明了 `messages[at]` 那条 toolResult 的 assistant(toolCalls) 下标。 */
    private fun ownerIndex(messages: List<ChatMessage>, at: Int): Int? {
        val callId = messages[at].toolResults.firstOrNull()?.callId
        if (callId.isNullOrEmpty()) return null
        for (index in at - 1 downTo 0) {
            if (messages[index].toolCalls.any { it.id == callId }) return index
        }
        return null
    }
}

/**
 * 摘要版：先滑窗，若仍超预算，把「被丢弃的中间段」交给 summarizer 压缩成一条 SYSTEM 消息。
 * summarizer 由上层注入（通常就是引擎本身跑一次"请总结以下对话"）。
 * 这是一个**可实现**的朴素方案，不追求最优。
 *
 * 注意：滑窗放弃压缩时（找不到安全切点）本类也只会原样返回，不会为了省 token 破坏工具配对；
 * 工具配对问题的最后一道保险是 [sanitizeForProvider]。
 */
class SummarizingContextCompressor(
    private val window: ContextCompressor = WindowContextCompressor(),
    private val summarizer: suspend (String) -> String?,
) : ContextCompressor {

    override suspend fun compress(messages: List<ChatMessage>, budgetTokens: Int): List<ChatMessage> {
        val windowed = window.compress(messages, budgetTokens)
        if (TokenEstimator.estimate(windowed) <= budgetTokens) return windowed
        val keptIds = windowed.map { it.id }.toSet()
        val dropped = messages.filter { it.id !in keptIds }
        if (dropped.isEmpty()) return windowed
        val digest = dropped.joinToString("\n") { "${it.role}: ${it.text.take(300)}" }
        val summary = try {
            summarizer(digest)
        } catch (t: Throwable) {
            null
        }
        if (summary.isNullOrBlank()) return windowed
        val summaryMessage = ChatMessage(
            role = Role.SYSTEM,
            text = "以下是较早对话的摘要，请参考：\n$summary",
        )
        return listOf(summaryMessage) + windowed
    }
}

/**
 * 发送前的最后一道清洗：保证 `assistant.toolCalls` 与 `toolResult` 严格成对。
 *
 *  - 孤儿 toolResult（其 `toolCallId` 找不到任何 assistant 声明）→ **丢弃**。
 *    OpenAI 兼容端点遇到「有 tool 消息但没有对应 tool_call」会直接 400。
 *  - assistant 声明的 toolCall 没有任何结果 → **补一条合成的错误结果**。
 *    端点对「有 tool_call 却没结果」的容忍度更低，通常直接拒绝整个请求。
 *
 * 该函数在 `AgentRunner` 组装 `GenerationRequest` 时调用，即「发给 provider 之前」，
 * 因此无论走原生工具通道还是文本协议、无论压缩是否发生，发出的上下文都是成对的。
 */
fun sanitizeForProvider(messages: List<ChatMessage>): List<ChatMessage> {
    if (messages.isEmpty()) return messages

    // 先整体扫描：一次拿到「声明过的 id」与「已被回应的 id」，
    // 这样补合成结果时不会把「结果其实在更后面」的调用误判成缺失。
    val declared = HashSet<String>()
    for (message in messages) {
        for (call in message.toolCalls) declared.add(call.id)
    }
    val answered = HashSet<String>()
    for (message in messages) {
        if (message.role != Role.TOOL) continue
        for (result in message.toolResults) answered.add(result.callId)
    }

    var changed = false
    val out = ArrayList<ChatMessage>(messages.size)
    for (message in messages) {
        if (message.role == Role.TOOL) {
            val kept = message.toolResults.filter { it.callId in declared }
            if (kept.isEmpty()) {
                changed = true
                continue                    // 整条都是孤儿结果 → 丢弃
            }
            if (kept.size != message.toolResults.size) {
                changed = true
                out.add(message.copy(toolResults = kept))
            } else {
                out.add(message)
            }
            continue
        }

        out.add(message)
        val missing = message.toolCalls.filter { it.id !in answered }
        if (missing.isNotEmpty()) {
            changed = true
            val patch = missing.map { call ->
                ToolResult(
                    callId = call.id,
                    name = call.name,
                    ok = false,
                    output = "",
                    errorMessage = "工具结果缺失（上下文被压缩或执行被中断），请基于已有信息继续。",
                )
            }
            // 补丁消息必须用**稳定派生 id**（外部审查报告2 §3.1，消 B2 补丁放大）：
            // 默认 newId() 每轮生成新 UUID，本地引擎的增量水印（sentMessageIds）会把
            // 同一条补丁当成「新消息」逐轮重发 —— 上下文里补丁越积越多，模型失焦。
            // 以缺失调用的 callId 派生：同一组缺失补丁在引擎水印下天然去重。
            out.add(
                ChatMessage(
                    id = "patch:" + missing.joinToString("_") { it.id },
                    role = Role.TOOL,
                    toolResults = patch,
                )
            )
            AgentLogStore.warn("工具结果缺失，已补合成结果：${missing.joinToString { it.name }}")
        }
    }
    return if (changed) out else messages
}
