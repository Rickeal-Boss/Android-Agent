package com.rickeal.agent.core.data.notify

/** 「通知发不出去」的三类互不隶属的原因。 */
enum class NotifyWarnReason { PERMISSION, CHANNEL_DISABLED, NOTIFY_FAILED }

/**
 * 每个原因在**同一进程内最多放行一次**的闩（永不复位）。
 *
 * 为什么不是单个布尔：三个出口共用一把闩时，第一条命中的出口置闩后另外两条永不落痕，
 * 用户「开了开关却没有通知」时诊断页只剩一条可能已经过时的解释。
 * 为什么按枚举而不是按原始 reason 串：`NOTIFY_FAILED` 的 reason 含动态异常类名，
 * 按串分闩会让闩集合无界、语义模糊；按固定三类分闩上界恒为 3 行/进程。
 * onTick 是每秒一次的节流流，靠本闩保证不刷屏。
 */
class NotificationWarnLatch {
    private var warnedMask: Int = 0

    @Synchronized
    fun shouldWarn(reason: NotifyWarnReason): Boolean {
        val bit = 1 shl reason.ordinal
        // 显式括号：infix 函数优先级高于 `!=`，写法上不靠优先级知识也能读对。
        if ((warnedMask and bit) != 0) return false
        warnedMask = warnedMask or bit
        return true
    }
}
