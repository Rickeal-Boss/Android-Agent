package com.rickeal.agent.core.data

import com.rickeal.agent.core.model.AgentLogStore
import kotlinx.coroutines.CancellationException

/**
 * 落盘写点的**单点异常边界**（W86-01）。
 *
 * ## 为什么需要它
 * 全仓 `*.launch` 落盘点远多于 `try/catch` 点，而 `DataStore.edit` 在磁盘满、
 * 目录不可写、SELinux 拒绝时会抛 [java.io.IOException]。异常一旦逃出 `launch`
 * 就直达默认 handler ⇒ 进程被打死；逃出 suspend 调用则直接顶到调用方，
 * 让「设置没存上」升级成「整页崩溃」。仓库侧兜底比 UI 侧逐点包 catch 便宜一个
 * 数量级：3/90 覆盖 → 全兜，且新增写点自动继承。
 *
 * ## 为什么不用 `runCatching`
 * `runCatching` 捕获的是 `Throwable`，**包括 [CancellationException]**。协程里
 * 「吞掉取消异常」不是小事：Job 会从 cancelled 变成 completed，`joinAll` 会提前
 * 返回、`isCancelled` 变 false、结构化并发里的父协程再也等不到真实的取消信号 ——
 * 本仓 W84 已经实摔过一次（取消后仍被判定为正常完成，收尾逻辑照跑）。
 * 所以这里必须**先**把 [CancellationException] 原样 rethrow，只兜真正的落盘失败。
 *
 * ## 失败之后的语义（既有纪律，不要改）
 * 落盘失败**不回滚 UI**：调用方（ViewModel）通常已经先改内存态再落盘，此刻回滚
 * 会把界面抖回旧值；正确做法是让它自然错位，由 DataStore 的 Flow 重新发射磁盘
 * 真值把 UI 校准回一致状态。本函数只负责「不让异常逃逸」，不负责补偿。
 *
 * ## 日志出口的健壮性
 * [AgentLogStore] 位于 `:core-model`，是纯 JVM 实现（无 `android.util.Log` 依赖），
 * 因此本函数可以直接在 JVM 单测里跑。即便如此，日志调用仍被单独 try/catch 包住：
 * 日志设施绝不能反过来把一次「可恢复的落盘失败」变成新的崩溃源。
 *
 * @param tag 写点标识，约定为 `类名.方法名`，用于在诊断面板里定位是哪一次落盘失败。
 * @param block 真正的写操作。抛 [CancellationException] 时原样向外传播。
 * @return block 的返回值；落盘失败返回 `null`。返回 Unit 的调用方直接忽略该返回值即可
 *   —— 刻意**不**再提供一个 Unit 重载，避免重载解析歧义与双份日志。
 */
internal suspend fun <T> persistOrWarn(tag: String, block: suspend () -> T): T? {
    return try {
        block()
    } catch (cancel: CancellationException) {
        // 顺序不可调换：CancellationException 必须排在 Throwable 之前，绝不吞。
        throw cancel
    } catch (t: Throwable) {
        warnSafely(
            "$tag 落盘失败（已拦截，进程存活，本次写入丢弃）：" +
                "${t::class.java.simpleName}: ${t.message}",
        )
        null
    }
}

/**
 * 写日志时再兜一层（W86-01）。
 *
 * 理由见 [persistOrWarn] 的 KDoc：诊断设施不允许成为新的崩溃源。这里只吞日志
 * 自身的异常，与上面「取消必须外传」的规则不冲突 —— 此时原始异常已经被处理掉了。
 */
private fun warnSafely(message: String) {
    try {
        AgentLogStore.warn(message)
    } catch (_: Throwable) {
        // 日志失败即静默丢弃：此刻没有任何更低一级的出口可用。
    }
}
