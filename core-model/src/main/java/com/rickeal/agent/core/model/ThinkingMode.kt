package com.rickeal.agent.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class ThinkingMode {
    /** 关闭思考通道 */
    OFF,

    /** 强制开启（extraContext["enable_thinking"] = true） */
    ON,

    /** 由模型/端点能力决定：能开就开 */
    AUTO,
}
