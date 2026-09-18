package com.rickeal.agent.core.model

import java.util.UUID

/** 统一 ID 生成入口。默认参数里可以直接调用。 */
fun newId(): String = UUID.randomUUID().toString()

fun newShortId(): String = UUID.randomUUID().toString().substring(0, 8)
