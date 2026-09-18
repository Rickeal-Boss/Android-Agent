package com.rickeal.agent.core.agent.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 从 argumentsJson 里安全地取一个字符串参数。 */
internal fun stringArg(argumentsJson: String, key: String, default: String = ""): String {
    val obj = try {
        Json.parseToJsonElement(argumentsJson) as? JsonObject
    } catch (t: Throwable) {
        null
    } ?: return default
    val value = obj[key] ?: return default
    return (value as? JsonPrimitive)?.content ?: value.toString()
}

internal fun json(argumentsJson: String): JsonObject =
    try {
        Json.parseToJsonElement(argumentsJson) as? JsonObject ?: JsonObject(emptyMap())
    } catch (t: Throwable) {
        JsonObject(emptyMap())
    }
