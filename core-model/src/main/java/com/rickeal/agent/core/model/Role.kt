package com.rickeal.agent.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class Role {
    SYSTEM,
    USER,
    MODEL,
    TOOL,
}
