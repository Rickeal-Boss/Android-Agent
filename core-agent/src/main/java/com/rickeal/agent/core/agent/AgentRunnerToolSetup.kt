package com.rickeal.agent.core.agent

import com.rickeal.agent.core.engine.EngineCapabilities
import com.rickeal.agent.core.model.DisclosureTools
import com.rickeal.agent.core.model.HiddenToolCatalog
import com.rickeal.agent.core.model.InferenceConfig
import com.rickeal.agent.core.model.ToolSpec

/**
 * 工具装配簇的产出（docs/13 接缝①，W63 刀F 自 [AgentRunner] `executeBodyUnchecked`
 * 外提）。
 *
 * **字段顺序 = 装配顺序 = 调用点解构顺序**（component1..7）：调用点用解构声明
 * `val (useNativeTools, …, allToolNames) = buildToolSetup(…)` 保持下游全部局部名
 * 不变，逐字节行为等价。新增字段时三处必须同步（字段声明 / 装配 / 调用点解构）。
 */
internal data class ToolSetup(
    val useNativeTools: Boolean,
    val allToolSpecs: List<ToolSpec>,
    val disclosureActive: Boolean,
    val hiddenToolCatalog: HiddenToolCatalog,
    val availableTools: List<ToolSpec>,
    val registeredToolNames: Set<String>,
    val allToolNames: Set<String>,
)

/**
 * 工具装配簇（docs/13 接缝①，W63 刀F 自 [AgentRunner] `executeBodyUnchecked` 外提）。
 *
 * 近纯计算：只依赖 [config] / [request] / [toolRegistry] / [capabilities]，无 emit、
 * 无实例状态、无 IO（[HiddenToolCatalog.from] 是纯内存映射）。求值顺序与外提前
 * **逐字节一致**（`useNativeTools` 先于 `allToolSpecs`；`disclosureActive` /
 * `hiddenToolCatalog` / `availableTools` 依赖 `allToolSpecs`）—— 簇整体搬移而非
 * 打乱重排，这是 docs/13 §三的「共同纪律」。设计注释随外提走，调用点只留转发说明。
 */
internal fun buildToolSetup(
    config: InferenceConfig,
    request: AgentRequest,
    toolRegistry: ToolRegistry,
    capabilities: EngineCapabilities?,
): ToolSetup {
    // ── 工具通道三重门（Wave 34 题 A）──────────────────────────────────
    // ① 引擎真的有原生通道（capabilities）；② 用户开了工具（config.enableTools）；
    // ③ 用户开了「原生工具通道」这个实验开关（config.nativeToolChannel，默认 false）。
    //
    // ③ 是**本波新增的显式门**：原生通道是未经验证的新通路，默认关闭意味着
    // 默认路径与 Wave 33 逐字节一致（文本协议 + 全量工具清单）。开启后工具改走
    // 模型原生通道、提示词里的工具清单段整体删除（见 buildSystemSections 的
    // nativeTools 分支）—— 这是根治「模型复述工具提示词」的取舍，代价是提示词
    // 侧的回显指纹覆盖变小（申报见该处 KDoc）。
    //
    // ⚠️ **红线：原生通道不引入第二条执行路径。** 走 native 时引擎把工具调用
    // 结构化回吐成 ToolCall，之后**复用同一条**执行管线 —— 审批
    // （ToolApprovalHandler，fail-closed）、沙箱、同参守卫、失败连击熔断、
    // 披露转发、结果截断与回灌全部是既有实现，没有为 native 复制任何一份。
    // 引入第二套执行路径会让「危险工具必过审批」这类不变量出现两处漂移源，
    // 是本仓历史上出过事故的模式，绝不为通道差异破例。
    val useNativeTools = (capabilities?.nativeToolChannel == true) &&
        config.enableTools &&
        config.nativeToolChannel

    // 用户白名单过滤后的**全部**真实工具（披露模式之前的口径）。
    val allToolSpecs: List<ToolSpec> = if (config.enableTools) {
        toolRegistry.specs().filter { request.toolNames?.contains(it.name) ?: true }
    } else {
        emptyList()
    }
    // ── 工具披露模式（Wave 27 / Operit「CLI 工具模式」裁剪移植）─────────
    // ON_DEMAND 时把「面向模型的工具面」收窄成两个元工具，真实工具进隐藏
    // 目录按需检索。这样提示词长度与工具总数**解耦** —— 既省端侧预算，也
    // 缩小 Wave 21 真机复现过的「工具清单段被当模板逐字复述」的面积。
    //
    // 目录构建是纯内存映射（无 IO、无副作用），run 开头算一次即可。
    // 注意 `disclosureActive` 还要求 enableTools —— 关掉工具时不该凭空
    // 冒出两个元工具（那会让「无工具」的语义失效）；也要求目录非空 ——
    // 隐藏目录为空（工具全被白名单排除）时，披露模式只会让提示词教模型
    // 「去检索真实工具」而实际一个都没有，白白浪费轮次，不如退回全量模式
    // （空工具清单本来就不会注入工具段）。
    val disclosureActive = config.enableTools &&
        allToolSpecs.isNotEmpty() &&
        request.disclosureMode.hidesToolCatalog()
    val hiddenToolCatalog =
        if (disclosureActive) HiddenToolCatalog.from(allToolSpecs) else HiddenToolCatalog.EMPTY
    // 面向模型的工具清单：ON_DEMAND 时只有元工具，因此下面三处自动一致 ——
    // ① 系统提示词工具段；② 原生通道 tools；③ registeredToolNames 白名单。
    // ③ 是关键：TextToolProtocol 只承认白名单内的工具名，所以模型**在协议层
    // 就无法直接调用隐藏工具**（未注册名降级为最终答案，而不是执行）。
    val availableTools: List<ToolSpec> =
        if (disclosureActive) DisclosureTools.publicSpecs() else allToolSpecs
    // 文本协议模式的「可执行」判据：工具名必须真的在当前可用集合里。
    // 名字不认识的 JSON 一律按最终答案处理（见 TextToolProtocol.parse 注释），
    // 否则模型输出普通 JSON（如 {"name":"张三"}）时会被误判成工具调用而反复重试。
    val registeredToolNames: Set<String> = availableTools.map { it.name }.toSet()
    // 披露之前的「用户启用集合」口径（allToolSpecs 已按 toolNames 白名单过滤）。
    // ON_DEMAND 转发放行用它做判据：比元工具白名单宽（否则合法转发
    // 全被误杀，审查4 P0）、比 registry 全量严（编造未启用名经转发通道同样被拒，
    // 与直达封堵同口径）。FULL 模式下与 registeredToolNames 是同一集合。
    val allToolNames: Set<String> = allToolSpecs.map { it.name }.toSet()
    return ToolSetup(
        useNativeTools = useNativeTools,
        allToolSpecs = allToolSpecs,
        disclosureActive = disclosureActive,
        hiddenToolCatalog = hiddenToolCatalog,
        availableTools = availableTools,
        registeredToolNames = registeredToolNames,
        allToolNames = allToolNames,
    )
}
