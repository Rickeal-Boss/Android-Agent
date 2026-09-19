package com.rickeal.agent.core.model

/**
 * 应用内诊断日志级别。
 *
 * 刻意只保留三档：端侧诊断的目标是「出事后能回看决策点」，不是做分级运维体系。
 * 三档的语义边界：
 *  - [INFO]  —— 正常流程里的**决策点**（为什么这轮这样收尾、压缩有没有生效）。
 *  - [WARN]  —— 已经偏离预期但被兜住/恢复了（工具超时、未注册工具、引擎重建、流被截断）。
 *  - [ERROR] —— 用户可见的失败（目前只有极少数路径直接记 ERROR）。
 */
enum class AgentLogLevel {
    INFO,
    WARN,
    ERROR,
}

/**
 * 一条进程内诊断日志（纯数据）。
 *
 * 为什么放在 `:core-model`：产出方横跨引擎（`:core-engine`）、Agent 循环（`:core-agent`）、
 * 展示方在设置页（`:feature-settings`），而本模块是三者共同依赖、且不依赖任何框架的纯 Kotlin 模块。
 *
 * 注意：本类型**不参与会话 JSON 序列化**，也**不落盘**。会话文件是用户资产，
 * 日志是诊断副产品；把日志混进会话文件会把文件撑大、且难以清理（本项目已有
 * 「注释承诺了却没兑现」的教训，所以这里不做任何持久化承诺）。
 */
data class AgentLog(
    val level: AgentLogLevel,
    /** 人类可读的一行描述。**绝不**包含 API Key / 请求头 / 带凭据的 URL。 */
    val message: String,
    /** 记录时刻（`System.currentTimeMillis()`）。展示层负责按本地时区格式化。 */
    val atMillis: Long,
)
