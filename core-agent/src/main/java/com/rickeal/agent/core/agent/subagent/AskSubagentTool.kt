package com.rickeal.agent.core.agent.subagent

import com.rickeal.agent.core.agent.AgentEvent
import com.rickeal.agent.core.agent.AgentPolicy
import com.rickeal.agent.core.agent.AgentRequest
import com.rickeal.agent.core.agent.AgentRunner
import com.rickeal.agent.core.agent.Tool
import com.rickeal.agent.core.agent.ToolRegistry
import com.rickeal.agent.core.model.AgentJson
import com.rickeal.agent.core.model.AgentLogStore
import com.rickeal.agent.core.model.ChatMessage
import com.rickeal.agent.core.model.InferenceConfig
import com.rickeal.agent.core.model.ModelDescriptor
import com.rickeal.agent.core.model.Role
import com.rickeal.agent.core.model.ToolParamType
import com.rickeal.agent.core.model.ToolParameter
import com.rickeal.agent.core.model.ToolResult
import com.rickeal.agent.core.model.ToolSpec
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.coroutines.AbstractCoroutineContextElement

/**
 * 父 run 注入给工具的执行上下文（走协程上下文，不是可变全局：
 * AgentRunner 在工具执行段 withContext 挂载，工具在协程内随时可读）。
 */
class SubagentRunContext(
    val parent: AskSubagentTool.ParentContext,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<SubagentRunContext>
}

/** 一次子代理调用的结果。 */
data class SubagentAnswer(
    val actor: String,
    val text: String,
    val rounds: Int,
    /** 子 run 的失败原因（Failed 事件透传）；null = 正常结束。 */
    val failure: String? = null,
)

/**
 * `ask_actor` 工具：把子任务交给具名 Actor 执行并等待结果。
 *
 * 对应 ZCode 的 `agent("名字", {system}).ask("任务")` / Octop 的 `ask_agent` 工具。
 * 三条移植过来的语义契约：
 *
 *  1. **串行**：端侧只有一个引擎实例，Actor 的 ask 在父 run 的工具阶段内同步完成
 *     （通过 [AgentRunner.runUnlocked]，锁由父 run 持有）。这与 Octop「inbox 全局单
 *     worker 串行」的决策一致 —— 端侧没有并发推理的资源，串行是正确而非妥协。
 *  2. **上下文累积**：同一（会话 × Actor）的多次 ask 共享一份会话历史
 *     （[SubagentSessionStore]），Actor 因此有「长期记忆」的雏形。
 *  3. **身份隔离**：Actor 有独立系统提示词与独立 conversationId；本地引擎上表现为
 *     KV cache 切换（父 run 下一轮全量重发历史重建，见 AgentRunner.runUnlocked 注释），
 *     远程引擎上天然隔离（每次请求全量 messages）。
 *
 * 取消语义：父 run 被用户停止时，withTimeout/collect 随协程一起取消，
 * 子 run 的生成随之取消（collect 的取消传播），无需特殊处理。
 */
class AskSubagentTool(
    private val registry: SubagentRegistry,
    private val sessions: SubagentSessionStore,
    private val agentRunner: AgentRunner,
    /**
     * 真实工具注册表（用于「未声明 allowedTools = 继承全部」的兜底解析）。
     * Wave2 没注入它，兜底误用了 [registry]（SubagentRegistry）的 Actor 名当工具名
     * —— Markdown 自定义 Actor 不写 tools 字段时子 run 实际零工具可用，与注释宣称
     * 的「继承全部」矛盾。null = 兜底为零工具（保守）。
     */
    private val toolRegistry: ToolRegistry? = null,
) : Tool {

    override val spec: ToolSpec = ToolSpec(
        name = TOOL_NAME,
        description = "把一个子任务交给具名子代理（Actor）执行并等待其结果。" +
            "适合：拆解计划、审查/挑错、压缩摘要这类需要独立视角或独立上下文的辅助工作。" +
            "不要把「调用工具获取信息」这类主任务自己能做的事委派出去。",
        parameters = listOf(
            ToolParameter(
                name = "actor",
                type = ToolParamType.STRING,
                required = true,
                description = "子代理名字，必须是可用名单中的一个",
            ),
            ToolParameter(
                name = "task",
                type = ToolParamType.STRING,
                required = true,
                description = "交给子代理的完整任务描述，要自包含（子代理看不到当前对话）",
            ),
        ),
        category = "agent",
        // 子 run 是多轮推理，远超默认 15s 工具超时；单独放宽（executeWithGuard 尊重此值）
        timeoutMillisOverride = DEFAULT_TIMEOUT_MILLIS,
    )

    override suspend fun invoke(argumentsJson: String): ToolResult {
        val args = runCatching {
            AgentJson.Default.parseToJsonElement(argumentsJson)
        }.getOrNull() as? JsonObject
            ?: return failure("参数必须是 JSON 对象")
        val actorName = (args["actor"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim().orEmpty()
        val task = (args["task"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim().orEmpty()
        if (actorName.isEmpty()) return failure("缺少 actor 参数")
        if (task.isEmpty()) return failure("缺少 task 参数")

        val definition = registry.get(actorName)
            ?: return failure("不存在名为「$actorName」的子代理。可用：${registry.names().joinToString(", ")}")

        val parent = coroutineContext[SubagentRunContext]?.parent
            ?: return failure("子代理缺少父 run 上下文（装配错误），请反馈开发者")

        val answer = ask(definition, parent, task)
        // 失败透传：ok=false + 失败原因（脱敏），父模型据此改走其它路径或说明阻塞。
        // 只在「文本为空且确有失败」时降级 —— 有文本时失败信息反而会稀释可用结果。
        if (answer.text.isBlank() && answer.failure != null) {
            return ToolResult(
                callId = "",
                name = TOOL_NAME,
                ok = false,
                output = "",
                errorMessage = "子代理 ${definition.name} 执行失败：" +
                    AgentLogStore.sanitizeUserFacing(answer.failure),
            )
        }
        return ToolResult(
            callId = "",
            name = TOOL_NAME,
            ok = true,
            output = if (answer.text.isBlank()) {
                "子代理 ${definition.name} 没有产出可见文本（rounds=${answer.rounds}）；" +
                    "请基于其已完成的部分继续，或换一种任务描述再试。"
            } else {
                answer.text
            },
        )
    }

    // ------------------------------------------------------------------

    /** 父 run 上下文：子 run 继承采样配置与模型选择，只换系统提示词与工具面。 */
    data class ParentContext(
        val conversationId: String?,
        val config: InferenceConfig,
        val model: ModelDescriptor?,
    )

    private suspend fun ask(
        definition: SubagentDefinition,
        parent: ParentContext,
        task: String,
    ): SubagentAnswer {
        val sessionKey = SubagentSessionStore.key(parent.conversationId, definition.name)
        val history = sessions.snapshot(sessionKey)
        val userInput = ChatMessage(role = Role.USER, text = task)

        // 工具白名单：未声明 allowedTools = 继承全部**已注册工具**（ask_actor 自身
        // 永远排除，防递归自我委派）。继承面必须从真实 ToolRegistry 取 —— Wave2
        // 误用 SubagentRegistry 的 Actor 名当工具名，子 run 实际零工具。
        val toolNames = (definition.allowedTools
            ?: toolRegistry?.specs()?.map { it.name }.orEmpty())
            .filter { it != TOOL_NAME }
            .toSet()

        val request = AgentRequest(
            conversationId = "subagent::$sessionKey",
            history = history,
            userInput = userInput,
            config = parent.config.copy(
                systemInstruction = definition.systemPrompt,
                enableTools = toolNames.isNotEmpty(),
            ),
            model = parent.model,
            toolNames = toolNames,
            // 辅助任务：更少的轮次上限；journal/approval 不接（子 run 不写盘、不弹审批，
            // 危险工具在子 run 中因无审批通道被拒 —— fail-closed）
            policy = AgentPolicy(maxRounds = definition.maxRounds),
        )

        // Actor 上下文累积：任务本身 + 子 run 提交的所有消息（含工具调用与结果）
        sessions.append(sessionKey, userInput)
        var rounds = 0
        var finalText = ""
        var failureMessage: String? = null
        agentRunner.runUnlocked(request).collect { event ->
            when (event) {
                is AgentEvent.MessageCommitted -> sessions.append(sessionKey, event.message)
                is AgentEvent.Finished -> {
                    finalText = event.text
                    rounds = event.rounds
                }
                // 子 run 失败不能静默吞掉：Wave2 只处理了 MessageCommitted/Finished，
                // Failed 落进 else 被丢 —— 父模型只看到「没有产出可见文本」，无从区分
                // 「任务确实无输出」和「引擎加载失败」，更没法调整策略。
                is AgentEvent.Failed -> failureMessage = event.message
                else -> Unit
            }
        }
        AgentLogStore.info("子代理 ${definition.name} 完成：$rounds 轮，输出 ${finalText.length} 字符")
        return SubagentAnswer(definition.name, finalText, rounds, failureMessage)
    }

    private fun failure(message: String): ToolResult = ToolResult(
        callId = "",
        name = TOOL_NAME,
        ok = false,
        output = "",
        errorMessage = message,
    )

    companion object {
        const val TOOL_NAME = "ask_actor"
        const val DEFAULT_TIMEOUT_MILLIS = 240_000L
    }
}
