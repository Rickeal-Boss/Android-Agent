package com.rickeal.agent.core.agent.approval

import com.rickeal.agent.core.model.ToolCall
import com.rickeal.agent.core.model.ToolSpec

/** 审批裁决。fail-closed：任何异常/超时都按 [DENIED] 处理，绝不默认放行。 */
enum class ToolApprovalDecision {
    /** 用户（或宿主策略）放行，立即执行工具。 */
    APPROVED,

    /** 用户拒绝 / 审批通道异常。工具不执行，拒绝原因写回上下文供模型调整策略。 */
    DENIED,
}

/**
 * 工具审批通道（Octop tool_guard + ZCode 命令审批语义移植）。
 *
 * ZCode 的 `world.run` 只允许编译期字符串字面量命令，从静态结构上把
 * 「模型要跑什么命令」变成用户可预审的清单。端侧 Android 没有编译期通道，
 * 退而求其次：把同一审批点放到**运行时执行前** ——
 * `dangerous` / `requiresConfirmation` 的工具在真正 invoke 之前，
 * 主循环挂起等待宿主裁决。
 *
 * 实现约定（宿主侧，如 ChatViewModel）：
 *  - `onApprovalRequested` 在挂起点等待 UI 的用户决定（如
 *    `suspendCancellableCoroutine` + 确认弹窗），超时或弹窗被取消按 DENIED；
 *  - **绝不在这里做耗时 IO**：它挂在工具执行路径上，阻塞它等于阻塞整轮；
 *  - run 被取消（用户点停止）时挂起点会随协程一起取消，无需额外清理。
 */
fun interface ToolApprovalHandler {
    suspend fun onApprovalRequested(call: ToolCall, spec: ToolSpec): ToolApprovalDecision
}
