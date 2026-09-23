package com.rickeal.agent.core.agent.plan

import com.rickeal.agent.core.agent.subagent.SubagentRunContext
import com.rickeal.agent.core.agent.Tool
import com.rickeal.agent.core.agent.tools.stringArg
import com.rickeal.agent.core.model.ToolParamType
import com.rickeal.agent.core.model.ToolParameter
import com.rickeal.agent.core.model.ToolResult
import com.rickeal.agent.core.model.ToolSpec
import com.rickeal.agent.core.model.AgentJson
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
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
    val id: String = "",
    val description: String = "",
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
 *
 * 持久化（Wave3 补齐）：persistDir 非空时每个会话一个 JSON 文件，每次变更原子落盘
 * （tmp + ATOMIC_MOVE）；首次访问某会话时惰性回载。进程死亡后计划还在 —— 蓝图里
 * 「长程任务不丢上下文」的承诺原来只覆盖进程内，崩溃即清零（Wave2 遗留），这是
 * 恢复闭环的缺口之一。persistDir 为空 = 纯内存（测试场景，与旧行为一致）。
 */
class AgentPlanStore(
    private val persistDir: File? = null,
    private val maxConversations: Int = 16,
) {

    class TrackedPlan internal constructor() {
        @Volatile
        internal var version: Long = 0L

        @Volatile
        var steps: List<PlanStep> = emptyList()
            internal set
    }

    /**
     * access-order=true 才是真正的 LRU：getOrPut/peek 都会刷新访问位，
     * 长期不被打开的会话才会被淘汰（Wave2 写成 false，实为 FIFO —— 最老创建的
     * 会话先被挤掉，哪怕它一直在用）。
     */
    private val plans = object : LinkedHashMap<String, TrackedPlan>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, TrackedPlan>): Boolean =
            size > maxConversations
    }

    @Synchronized
    internal fun planFor(key: String): TrackedPlan = plans.getOrPut(key) {
        val plan = TrackedPlan()
        // 惰性回载：进程重启后第一次访问该会话，把磁盘上的计划接回来。
        // load 失败按「无计划」处理（计划是辅助数据，fail-open）。
        loadSync(key)?.let { snapshot ->
            plan.version = snapshot.version
            plan.steps = snapshot.steps
        }
        plan
    }

    @Synchronized
    internal fun peek(key: String): TrackedPlan? = plans[key]

    /**
     * 公开只读视图：宿主（ChatViewModel）打开/恢复会话时把既有计划回填进 UI 时间线。
     * 只读，不 touch 访问序（读 UI 不该影响 LRU 的淘汰判断）。
     *
     * Wave4 审查（C-P1-3）：内存未命中时**必须惰性回载** —— 此前直接返回 emptyList()，
     * 冷启动（或该 key 被 LRU 淘汰）后计划时间线永远空白，只有等模型再调一次 plan 工具
     * 才浮现，与调用方「打开会话就要看到时间线」的承诺不符。回载结果**不写回 plans**，
     * 保留「读 UI 不影响 LRU」的原意。
     */
    @Synchronized
    fun stepsFor(key: String): List<PlanStep> {
        plans[key]?.let { return it.steps }
        return loadSync(key)?.steps ?: emptyList()
    }

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
        persistSync(key, plan)
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
        persistSync(key, plan)
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

    // ------------------------------------------------------------------
    // 持久化（每会话一个 JSON；version 一并落盘，恢复后水印语义不变）
    // ------------------------------------------------------------------

    /** 磁盘形态。version 必须持久化：恢复后 lastPlanVersion 水印才不会把旧计划误判成「新变化」。 */
    @Serializable
    private data class PlanSnapshot(val version: Long = 0L, val steps: List<PlanStep> = emptyList())

    private fun fileKey(key: String): String =
        key.replace(Regex("[^A-Za-z0-9_.-]"), "_").take(120).ifBlank { "default" }

    private fun loadSync(key: String): PlanSnapshot? {
        val dir = persistDir ?: return null
        val file = File(dir, fileKey(key) + ".json")
        if (!file.exists()) return null
        return runCatching {
            val raw = file.readText()
            if (raw.isBlank()) null
            else AgentJson.Default.decodeFromString(PlanSnapshot.serializer(), raw)
        }.getOrNull()
    }

    /** 原子写：tmp 唯一名 + ATOMIC_MOVE，写一半崩溃不留半截文件。 */
    private fun persistSync(key: String, plan: TrackedPlan) {
        val dir = persistDir ?: return
        runCatching {
            dir.mkdirs()
            val target = File(dir, fileKey(key) + ".json")
            val tmp = File(dir, fileKey(key) + "." + System.nanoTime() + ".tmp")
            tmp.writeText(
                AgentJson.Default.encodeToString(
                    PlanSnapshot.serializer(),
                    PlanSnapshot(version = plan.version, steps = plan.steps),
                )
            )
            try {
                Files.move(
                    tmp.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (t: Throwable) {
                // 个别文件系统不支持 ATOMIC_MOVE，退化普通 rename（仍是元数据操作）
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }.onFailure {
            com.rickeal.agent.core.model.AgentLogStore.warn(
                "计划落盘失败（忽略，不影响运行）：${it.javaClass.simpleName}"
            )
        }
    }
}

/** 计划工具的公共基座：从协程上下文取父 run 的会话键。 */
/**
 * 计划工具公共基座。Kotlin 禁止 public 类继承 internal 类（超类型会出现在公开
 * 类型层级里），而两个子类必须 public（installPlanTools 的注册入口），
 * 因此基类同为 public —— 它不含任何可变状态，暴露面是安全的。
 */
abstract class PlanToolBase(
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
