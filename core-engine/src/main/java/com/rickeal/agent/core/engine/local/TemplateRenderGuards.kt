package com.rickeal.agent.core.engine.local

/** 上游 `Failed to apply template` 文案**随 litertlm 版本漂移**（耦合 litertlm 0.17.1），集中在此。 */
internal const val TEMPLATE_FAILURE_MARKER: String = "Failed to apply template"

/**
 * 判据：本次生成失败是否为**模板渲染失败**（chat template 拼接 content 失败）。
 *
 * 真机文案（Wave 51 P1 取证）：`INTERNAL: Failed to apply template: invalid operation:
 * tried to use + operator on unsupported types string and sequence (in template:23)` ——
 * 模板 `:23` / `:27` 用 `'…' + message.content + '…'` 拼接 content，而 content 为 JSON
 * 数组时 minijinja 的 `+` 不支持（string + sequence）。大小写不敏感，防上游改大小写。
 *
 * 纯函数（文件级 internal，可被同模块 JVM 单测直接调）：不构造引擎、不触 native。
 */
internal fun isTemplateRenderFailure(raw: String): Boolean =
    raw.contains(TEMPLATE_FAILURE_MARKER, ignoreCase = true)
