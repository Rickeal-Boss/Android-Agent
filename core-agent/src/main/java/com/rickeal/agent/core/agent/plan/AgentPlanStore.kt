package com.rickeal.agent.core.agent.plan

import com.rickeal.agent.core.agent.subagent.SubagentRunContext
import com.rickeal.agent.core.agent.Tool
import com.rickeal.agent.core.agent.tools.stringArg
import com.rickeal.agent.core.model.ToolParamType
import com.rickeal.agent.core.model.ToolParameter
import com.rickeal.agent.core.model.ToolResult
import com.rickeal.agent.core.model.ToolSpec
import kotlinx.serialization.Serializable
import kotlin.coroutines.coroutineContext

@Serializable
enum class PlanStepStatus {
    PENDING,
    IN_PROGRESS,
    COMPLETED,
}

@Serializable
data class PlanStep(
    val id: String,
    val description: String,
    val status: PlanStepStatus = PlanStepStatus.PENDING,
)

/**
 * 会话级计划仓库 —— ZCode Phase Graph 的端侧降级移植。
 *
 * ZCode 的 `phase("检查测试")` 由编译器归约成人可读的阶段图供审批与展示；端侧没有
 * 编译期通道，把同一「结构可见性」下沉到**运行期显式计划**：模型用 plan_set/plan_update
 * 工具维护一份带状态的步骤清单，宿主通过 [AgentEvent.PlanUpdated]（见 AgentEvents）
 * 实时渲染。计划按会话隔离（键 = conversationId），跨 run 存活 —— 一个长任务被打断后
 * 恢复，计划还在，这正是长程任务不丢上下文的关键一块。
 *
 * 状态推进约定（对模型可执行）：markStep COMPLETED 时自动把下一个 PENDING 步骤置为
 * IN_PROGRESS —— 4B 模型经常忘记这一步，替它做掉，避免出现「全部 pending 但其实
 * 已经做了一半」的僵死计划。
 */
class AgentPlanStore(private val maxConversations: Int = 16) {

    class TrackedPlan internal constructor() {
        @Volatile
        internal var version: Long = 0L

        @Volatile
        var steps: List<PlanStep> = emptyList()
            internal set
    }

    private val plans = object : LinkedHashMap<String, TrackedPlan>(16, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, TrackedPlan>): Boolean =
            size > maxConversations
    }

    @Synchronized
    internal fun planFor(key: String): TrackedPlan = plans.getOrPut(key) { TrackedPlan() }

    @Synchronized
    internal fun peek(key: String): TrackedPlan? = plans[key]

    /** 整表替换（plan_set）。首步自动置 IN_PROGRESS。 */
    @Synchronized
    fun setPlan(key: String, descriptions: List<String>) {
        val plan = planFor(key)
        plan.steps = descriptions.mapIndexed { index, text ->
            PlanStep(
                id = "s${index + 1}",
                description = text.trim(),
                status = if (index == 0) PlanStepStatus.IN_PROGRESS else PlanStepStatus.PENDING,
            )
        }
        plan.version++
    }

    /**
     * 更新某一步状态（plan_update）。@param stepOrdinal 1 起的步骤序号。
     * COMPLETED 时自动推进下一个 PENDING → IN_PROGRESS。
     */
    @Synchronized
    fun markStep(key: String, stepOrdinal: Int, status: PlanStepStatus): Boolean {
        val plan = planFor(key)
        val index = stepOrdinal - 1
        if (index < 0 || index >= plan.steps.size) return false
        val mutable = plan.steps.toMutableList()
        mutable[index] = mutable[index].copy(status = status)
        if (status == PlanStepStatus.COMPLETED) {
            val next = mutable.indexOfFirst { it.status == PlanStepStatus.PENDING }
            if (next >= 0) {
                mutable[next] = mutable[next].copy(status = PlanStepStatus.IN_PROGRESS)
            }
        }
        plan.steps = mutable.toList()
        plan.version++
        return true
    }

    companion object {
        /** 渲染给模型/日志看的紧凑视图。 */
        fun render(steps: List<PlanStep>): String {
            if (steps.isEmpty()) return "（当前无计划）"
            return steps.mapIndexed { index, step ->
                val mark = when (step.status) {
                    PlanStepStatus.COMPLETED -> "✓"
                    PlanStepStatus.IN_PROGRESS -> "▶"
                    PlanStepStatus.PENDING -> "·"
                }
                "${index + 1}. [$mark] ${step.description}"
            }.joinToString("\n")
        }
    }
}

/** 计划工具的公共基座：从协程上下文取父 run 的会话键。 */
internal abstract class PlanToolBase(
    private val store: AgentPlanStore,
    name: String,
    description: String,
    parameters: List<ToolParameter>,
) : Tool {
    final override val spec: ToolSpec = ToolSpec(
        name = name,
        description = description,
        parameters = parameters,
        category = "plan",
    )

    protected suspend fun conversationKey(): String? {
        // 上下文缺失 → null（装配错误）；有上下文但无会话 → ""（全局键）
        val parent = coroutineContext[SubagentRunContext]?.parent ?: return null
        return parent.conversationId ?: ""
    }

    protected fun emitResult(ok: Boolean, message: String): ToolResult =
        ToolResult(name = spec.name, ok = ok, output = if (ok) message else "", errorMessage = if (ok) null else message)

    protected fun renderCurrent(key: String): String {
        val steps = store.planFor(key).steps
        return AgentPlanStore.render(steps)
    }
}

/** 创建/整体替换当前会话的执行计划。 */
class PlanSetTool(private val store: AgentPlanStore) : PlanToolBase(
    store = store,
    name = "plan_set",
    description = "为当前任务创建执行计划（整体替换旧计划）。多步任务必须先建计划再干活；" +
        "每完成一步就用 plan_update 标记。单步即可完成的小任务不要建计划。",
    parameters = listOf(
        ToolParameter(
            name = "steps",
            type = ToolParamType.ARRAY,
            description = "步骤描述数组，每项一句话、以动词开头，不超过 6 步",
        ),
    ),
) {
    override suspend fun invoke(argumentsJson: String): ToolResult {
        val key = conversationKey()
            ?: return emitResult(false, "缺少运行上下文（装配错误），请反馈开发者")
        val steps = runCatching {
            com.rickeal.agent.core.model.AgentJson.Default
                .parseToJsonElement(argumentsJson)
        }.getOrNull() as? kotlinx.serialization.json.JsonObject
            ?: return emitResult(false, "参数必须是 JSON 对象")
        val array = steps["steps"] as? kotlinx.serialization.json.JsonArray
            ?: return emitResult(false, "缺少 steps 数组参数")
        val items = array.mapNotNull { element ->
            (element as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
        }.filter { it.isNotEmpty() }
        if (items.isEmpty()) return emitResult(false, "steps 不能为空")
        if (items.size > 8) return emitResult(false, "步骤过多（${items.size} 个），计划不要超过 8 步")
        store.setPlan(key, items)
        return emitResult(true, "计划已建立：\n${renderCurrent(key)}\n开始执行第 1 步。")
    }
}

/** 更新计划中某一步的状态。 */
class PlanUpdateTool(private val store: AgentPlanStore) : PlanToolBase(
    store = store,
    name = "plan_update",
    description = "更新执行计划中某一步的状态。完成一步就标记一次；不要等到全部结束才补标记。",
    parameters = listOf(
        ToolParameter(
            name = "step",
            type = ToolParamType.INTEGER,
            description = "步骤序号（从 1 开始）",
        ),
        ToolParameter(
            name = "status",
            type = ToolParamType.STRING,
            description = "新状态",
            required = true,
            enumValues = listOf("pending", "in_progress", "completed"),
        ),
    ),
) {
    override suspend fun invoke(argumentsJson: String): ToolResult {
        val key = conversationKey()
            ?: return emitResult(false, "缺少运行上下文（装配错误），请反馈开发者")
        val args = runCatching {
            com.rickeal.agent.core.model.AgentJson.Default.parseToJsonElement(argumentsJson)
        }.getOrNull() as? kotlinx.serialization.json.JsonObject
            ?: return emitResult(false, "参数必须是 JSON 对象")
        val ordinal = ((args["step"] as? kotlinx.serialization.json.JsonPrimitive)?.content)
            ?.trim()?.toIntOrNull()
            ?: return emitResult(false, "step 必须是整数序号（从 1 开始）")
        val statusText = stringArg(argumentsJson, "status").trim()
        val status = when (statusText) {
            "pending" -> PlanStepStatus.PENDING
            "in_progress" -> PlanStepStatus.IN_PROGRESS
            "completed" -> PlanStepStatus.COMPLETED
            else -> return emitResult(false, "status 只支持 pending / in_progress / completed")
        }
        val ok = store.markStep(key, ordinal, status)
        if (!ok) return emitResult(false, "步骤 $ordinal 不存在（当前计划共 ${store.planFor(key).steps.size} 步）")
        val plan = store.planFor(key)
        val done = plan.steps.count { it.status == PlanStepStatus.COMPLETED }
        return emitResult(true, "计划已更新（$done/${plan.steps.size} 完成）：\n${AgentPlanStore.render(plan.steps)}")
    }
}

/** 计划工具装配。 */
fun installPlanTools(registry: com.rickeal.agent.core.agent.ToolRegistry, store: AgentPlanStore) {
    registry.register(PlanSetTool(store))
    registry.register(PlanUpdateTool(store))
}
