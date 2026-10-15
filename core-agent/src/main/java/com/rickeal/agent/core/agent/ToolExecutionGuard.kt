package com.rickeal.agent.core.agent

import com.rickeal.agent.core.agent.breaker.elapsedMillisSince
import com.rickeal.agent.core.model.AgentLogStore
import com.rickeal.agent.core.model.ToolCall
import com.rickeal.agent.core.model.ToolResult
import com.rickeal.agent.core.model.truncateSafe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * 单次工具执行的护栏：超时 + IO 调度 + 输出截断 + callId 回填 + 取消语义。
 *
 * W69 C8（外部审查「深审 G4 具体化」/ `docs/15` W69-01）：自 `AgentRunner.executeWithGuard`
 * 原样抽出为顶层函数 —— 除下方 🟡-5 外**零行为变化**。抽出的理由：
 *  · 本段是「工具超时 / 竞态」语义的落点，值得有独立、可审、可测的家（原为 AgentRunner
 *    的私有方法，与近 2800 行的主循环混居）；
 *  · 外部审查 🟡-1/🟡-4/🟡-5 的修法都以本段为宿主，独立文件让后续改动有明确边界。
 *
 * ⚠️ **取消语义（不可动）**：协程取消必须原样上抛，绝不能被吞成一条「工具执行异常」
 * 的失败结果。吞掉的话主循环不知道该停：`round++` 之后再跑一整轮 4B 推理（几十秒、
 * 持续烧电占 GPU），UI 显示已停而后台继续跑，日志还记成「工具异常」—— 停止按钮在
 * 工具执行阶段 100% 失效。
 * 顺序关键：[TimeoutCancellationException] 是 [CancellationException] 的**子类**，
 * 必须先把它排除掉，否则「工具超时」会从「可恢复错误」变成「整个 run 被取消」。
 *
 * ⚠️ **callId 回填（不可动）**：内置工具（Calculator / File / System / DateTime）只填
 * name/output，[ToolResult.callId] 默认空串 ⇒ 空 callId 的 TOOL 消息会被
 * `sanitizeForProvider` 当「孤儿结果」整条丢弃，破坏 tool call/result 配对协议。
 * 成功 / 失败两条路径都必须带真实 callId。
 *
 * 🟡-5（**W69 已实施**）：耗时口径由墙钟 `System.currentTimeMillis()` 改为**单调时钟**
 * `System.nanoTime()` 差值（复用 `elapsedMillisSince`）。墙钟会因 NITZ / 手动改时跳变，
 * 使 `elapsedMillis` 为负或虚大，污染 BreakerLedger 诊断卡与「慢工具」归因。
 * 同一口径的其余残留（审批缓存 TTL）不在本文件，见外部审查 🟡-5 的清单。
 *
 * 🟡-4（**设计草案，W69 未实施，parked**）：超时调用的副作用在「同参防连发」计数之外
 * 异步发生（孤儿写可能晚于重试才落地）⇒ 双写窗口。建议修法：超时分支把该次调用标记为
 * 「结果不确定（indeterminate）」，同参签名对 indeterminate 按「可能已生效」记账，并在
 * 重试时提示模型「上次调用超时但可能已生效，请先验证（如 file_read）再写」。实施需
 * [ToolResult] 加 indeterminate 位（core-model）+ 护栏判据 + 提示词面变化（须真机验证）
 * ⇒ 留独立波次，不夹带在 W69 的零行为纪律里。
 *
 * 🟡-1（**设计草案，W69 未实施，parked**）：超时「假失败 + 真副作用」的 last-writer 竞态
 * —— 被取消的协程不打断 `File.writeText` 这类阻塞调用，孤儿写仍可能 `ATOMIC_MOVE` 覆盖新内容。
 * 建议修法（轻）：run 代次号（`RunState.execSeq`）+ 工具写文件前（tmp→move 之间）比对代次，
 * 已被超时 / 终态作废的执行丢弃 move 并删 tmp；代价是把「当前 run 有效性」传进工具层
 * （`ToolContext.isCurrent: () -> Boolean`）⇒ 侵入 FileTools / ContentAddressedPool，
 * 留独立波次。
 */
internal suspend fun executeWithGuard(call: ToolCall, tool: Tool, policy: AgentPolicy): ToolResult {
    // 🟡-5：单调时钟起点（墙钟跳变不污染 elapsedMillis）
    val startedNanos = System.nanoTime()
    // 超时值必须在 try 外声明：catch 分支（超时文案）要用它，而 catch 看不见 try 体内的局部量
    val timeoutMillis = tool.spec.timeoutMillisOverride ?: policy.toolTimeoutMillis
    return try {
        // 工具会做文件读写 / 剪贴板 / 进程外调用，必须离开调用方线程（Default/Main）跑在 IO 上
        val raw = withTimeout(timeoutMillis) {
            withContext(Dispatchers.IO) { tool.invoke(call.argumentsJson) }
        }
        val output = raw.output
        val truncated = output.length > policy.maxToolOutputChars
        raw.copy(
            // 内置工具只填 name/output ⇒ 必须补真实 callId（见文件 KDoc 的不可动说明）。
            callId = raw.callId.ifBlank { call.id },
            // 代理对安全截断（Wave 35 D1）：裸 take 会停在半个代理对上，尾部 emoji
            // 变成 U+FFFD 乱码 —— 这是全仓最高频的用户可见截断路径（每轮几乎都有）。
            output = if (truncated) output.truncateSafe(policy.maxToolOutputChars) + "\n…(已截断)" else output,
            elapsedMillis = elapsedMillisSince(startedNanos),
            truncated = truncated,
        )
    } catch (t: Throwable) {
        // 协程取消原样上抛（见文件 KDoc 的不可动说明）；顺序关键：先排除超时子类。
        if (t is CancellationException && t !is TimeoutCancellationException) throw t
        val message = if (t is TimeoutCancellationException) {
            AgentLogStore.warn("工具执行超时：${call.name}（${timeoutMillis}ms）")
            "工具执行超时（${timeoutMillis}ms）"
        } else {
            t.message ?: "工具执行异常"
        }
        ToolResult(
            callId = call.id,
            name = call.name,
            ok = false,
            output = "",
            errorMessage = message,
            elapsedMillis = elapsedMillisSince(startedNanos),
        )
    }
}
