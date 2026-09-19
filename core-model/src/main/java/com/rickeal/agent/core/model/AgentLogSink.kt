package com.rickeal.agent.core.model

/**
 * 日志出口：把日志转存到**进程之外**（磁盘）。
 *
 * ## 为什么需要它
 * [AgentLogStore] 只在内存里保留最近 [AgentLogStore.DEFAULT_CAPACITY] 条，进程一死就全没了。
 * 而端侧最常见的故障恰恰是**崩溃**（模型加载 OOM 是典型）：崩溃 = 进程死 = 缓冲清空 =
 * 重启后诊断页一片空白。也就是说，**最需要日志的场景，正好是日志必定不存在的场景**。
 * 让上层把日志（目前只需要 ERROR 级）转存到磁盘，崩溃才有迹可循。
 *
 * ## 为什么是回调，而不是让 AgentLogStore 自己写文件
 * `:core-model` 是纯 Kotlin 模块（CI 用 `scripts/arch-guard.sh` 强制它不得出现
 * `android.` / `androidx.` 导入），不能碰文件系统；而且日志设施不该让被诊断的对象承担 IO 风险。
 * 所以这里只留一个抽象出口，落盘实现放在有 `Context` 的 `:core-data`，由 AppContainer 启动时注入。
 *
 * ## 实现方约定（重要）
 *  - **同步调用**：`onLog` 由「写入日志的那个线程」直接调用，可能来自 Agent 循环线程。
 *    实现方必须保证耗时可控，并且**绝不抛异常**（`AgentLogStore` 也做了兜底 try/catch，
 *    但不要依赖它）。之所以不做异步派发：崩溃可能就发生在下一行代码，异步写会把
 *    「崩溃前最后一条」一起丢掉，那正是我们要保住的东西。
 *  - **[message] 已经过 `sanitize()`**：脱敏在 [AgentLogStore.record] 里完成，出口拿到的就是
 *    脱敏后的文本。实现方**不要**再去拼装原始未脱敏的内容 —— 防护在出口，而不是让业务侧少说话。
 */
fun interface AgentLogSink {
    fun onLog(level: AgentLogLevel, message: String, atMillis: Long)
}
