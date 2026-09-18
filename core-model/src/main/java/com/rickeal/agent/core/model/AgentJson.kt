package com.rickeal.agent.core.model

import kotlinx.serialization.json.Json

/**
 * 全局 Json 实例。
 * - ignoreUnknownKeys：远程引擎/旧版本会话文件向前兼容
 * - explicitNulls=false：不写 null，文件更小、跨版本更稳
 * - encodeDefaults=true：保证旧字段不丢失
 */
object AgentJson {
    val Default: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        isLenient = true
        prettyPrint = false
    }

    val Pretty: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        prettyPrint = true
    }
}
