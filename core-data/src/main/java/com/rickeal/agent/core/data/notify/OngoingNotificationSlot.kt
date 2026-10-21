package com.rickeal.agent.core.data.notify

/**
 * 「FGS 常驻通知」的进程内借道槽（W78 D2）。
 *
 * ## 它解决什么
 * FGS 必须有一条用户可见的常驻通知；速度通知每秒刷新。若两者共用 id，
 * `GenerationNotifier.stop()`（8 处调用点）会把 FGS 通知一起 cancel ⇒ 第二轮起
 * 「已前台化但用户不可见」。若两者各自发一条，通知栏出现两条划不掉的常驻通知。
 *
 * 解法 = **单写者 + 借道**：FGS 通知只由 `AgentForegroundService` 写（id 1002）；
 * FGS 活跃期间，速度文案经本槽交给服务刷新**同一条**，notifier 不再自己发。
 *
 * ## 依赖方向（硬约束）
 * 本对象在 `notify` 包、被 `background` 包使用 ⇒ 方向恒为 `background → notify`。
 * **禁止**让本对象反向 import `background` 包的任何类型（会形成包循环）。
 *
 * ## 线程模型
 * `attach`/`detach` 在服务主线程调用；`publish` 可能在任意线程（onTick 来自流收集）。
 * 故内部加锁 + sink 调用包 runCatching：**通知失败绝不能打崩推理**。
 */
internal object OngoingNotificationSlot {

    /** 速度通知的固定 id。与 `RunForegroundPolicy.ONGOING_NOTIFICATION_ID` 必须不同（单测钉死）。 */
    const val SPEED_NOTIFICATION_ID: Int = 1001

    private val lock = Any()
    private var owner: Any? = null
    private var sink: ((String) -> Boolean)? = null

    /**
     * 服务进入前台后登记刷新口。
     *
     * `sink` 的返回值语义（W78 硬点 1）= **「已受理」而非「已上屏」**：`true` = 已受理（可能仍在
     * 服务的主线程队列里）；`false` **只表示**「无活跃 FGS / 服务已销毁」⇒ 调用方（notifier）才
     * 回落自己发 1001。**刷新本身失败不回 false** —— 否则 notifier 会再发一条 1001 ⇒ 通知栏两条
     * 常驻通知，比丢一帧速度文案糟得多（1s 后下一 tick 自愈）。
     */
    fun attach(owner: Any, sink: (String) -> Boolean) {
        synchronized(lock) {
            this.owner = owner
            this.sink = sink
        }
    }

    /** 服务销毁时注销。**按 owner 身份校验**：新旧服务交错时，旧服务不会清掉新服务的 sink。 */
    fun detach(owner: Any) {
        synchronized(lock) {
            if (this.owner === owner) {
                this.owner = null
                this.sink = null
            }
        }
    }

    /** FGS 是否活跃（= 有服务登记了刷新口）。 */
    fun isActive(): Boolean = synchronized(lock) { sink != null }

    /**
     * 把速度文案交给 FGS 通知。`false` = **无活跃 FGS**（无 sink）⇒ 调用方自行发 1001。
     *
     * 下面的 `runCatching{}.getOrDefault(false)` 是**最后兜底**而非常态：sink 自己已把刷新失败
     * 吞成 warn（见 `AgentForegroundService.refreshOngoing`），所以正常路径上不会走到这里；
     * 真走到（sink 自身抛异常）时回 false 是唯一安全选择 —— 总比让 onTick 把推理打崩好。
     */
    fun publish(text: String): Boolean {
        val target = synchronized(lock) { sink } ?: return false
        return runCatching { target(text) }.getOrDefault(false)
    }
}
