package com.rickeal.agent.core.engine.local

import com.google.ai.edge.litertlm.Capabilities

/**
 * 模型能力探测入口（架构文档 §5.3）。
 *
 * 单独拆一个文件的理由：`litertlm-android` 在 :core-engine 里是 `implementation` 依赖，
 * :core-data 直接引用不到 `Capabilities`。这里做一层薄封装，
 * 让 :core-data 的 ModelsRepository 能探测，同时把 LiteRT-LM 类型继续关在 :core-engine 内。
 *
 * 探测是「尽力而为」：任何异常一律吞掉并返回 false，绝不让探测失败影响主流程。
 */
object ModelCapabilityProbe {

    fun hasSpeculativeDecoding(modelPath: String): Boolean {
        if (modelPath.isBlank()) return false
        return try {
            Capabilities(modelPath).use { it.hasSpeculativeDecodingSupport() }
        } catch (t: Throwable) {
            false
        }
    }
}
