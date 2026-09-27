package com.rickeal.agent.core.model

import kotlinx.serialization.Serializable

/**
 * 用户给 AI 的整体能力档位（Wave 26 / Operit2「四层能力模型」裁剪移植）。
 *
 * ## 为什么需要它
 * 在引入本档位之前，「AI 今天能走多远」这件事只能靠**逐工具**的 `dangerous` /
 * `requiresConfirmation` 标记来表达 —— 用户无法一次性把 AI 收窄到「只看不动」，
 * 也无法在信任时整体放开。逐工具标记回答的是「这个工具危不危险」，而用户真正
 * 关心的是「我允许 AI 做什么」——**这两件事必须解耦**（Operit2 的核心判断）。
 *
 * ## 分层位置（自外向内收紧，上层没有的能力下层变不出来）
 * ```
 * 0. Android 应用沙盒        —— 恒定存在，不由此枚举表达
 * 1. 系统授权（运行时权限）   —— Host 真实能力，本 App 无法变出来
 * 2. AI 能力档位（本枚举）   —— 用户选择，进入 AgentRunner 执行路径
 * 3. 单次工具审批            —— 见 ToolApprovalHandler / ToolSpec.requiresConfirmation
 * ```
 * **第 2 层不是「提权」**：它只能继续限制，永远不能创造系统没有的能力。
 *
 * ## 与审批的关系
 * 档位**不替代**审批：它是审批的上游判据。判定顺序为
 * `静态标志（dangerous/requiresConfirmation） ∪ 参数门控 ∪ 档位门控 → 审批通道`。
 * 也就是档位命中时**走审批**（用户可单次放行），而不是硬拒绝 —— 这样既收窄了
 * 默认行为，又保留了「这一次我确实需要它写」的出口。
 *
 * ⚠️ 纪律：档位必须进入 core-agent 执行路径，**不得只活在设置页**（那是「看起来
 * 有权限控制、实际不生效」的典型假象，Operit2 明令禁止）。
 */
@Serializable
enum class AiCapabilityMode {
    /**
     * 只读：WRITE 效果的工具一律要求用户授权后才执行。
     * 适合「让 AI 看看我的文件、答个问题」这类不信任场景。
     */
    READ_ONLY,

    /**
     * 工作区读写（**默认档**）：与引入档位之前的行为完全一致 ——
     * 只由工具自身的危险标记与参数门控决定是否弹卡。
     * 选它作默认是刻意的：本档位的引入必须做到**零行为回归**。
     */
    WORKSPACE_WRITE,

    /**
     * 完整权限：与 [WORKSPACE_WRITE] 在当前实现下等价，保留给后续「工作区之外写入」
     * 放开时使用。**注意它不是 root/管理员提权** —— 系统没给的能力它一样拿不到。
     */
    FULL,
    ;

    /** 本档位是否要求对 WRITE 效果的工具追加一次授权（fail-closed 判据）。 */
    fun requiresApprovalFor(effect: ToolEffect): Boolean =
        this == READ_ONLY && effect == ToolEffect.WRITE
}
