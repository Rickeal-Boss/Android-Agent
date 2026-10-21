package com.rickeal.agent.core.data.background

/**
 * 「run 期间后台保活」的**纯策略**（无 Android 依赖 ⇒ 可在 JVM 上直接单测）。
 *
 * ## 为什么把策略抽出来
 *
 * 本仓的判据纪律要求「凡形态判断必须可离线复现」。FGS 启停的三件事实里，
 * 两件是**纯算术/纯枚举**（宽限窗、wake lock 超时、类型分派阈值），把它们从
 * Service / Controller 里剥出来，就能在没有真机的情况下锁死回归面；
 * 剩下那件（平台是否真的冻结进程）才交给真机取证。
 *
 * ## 取值依据（逐条可回源）
 *
 * - [HARD_WALL_CLOCK_MILLIS] = 300_000：`AgentRunner` 的单 run 墙钟**硬预算**。
 *   FGS / WakeLock 的持有期必须由它推导，而不是拍一个「大概几分钟」。
 * - [WAKE_LOCK_MARGIN_MILLIS] = 90_000：审批停表（B1）与终态落库的余量。
 * - [WAKE_LOCK_TIMEOUT_MILLIS]：物理断路器思想（W30）—— 即使控制器全部失灵、
 *   连 `onDestroy` 都没走到，锁也会自行释放，不会变成耗电事故。
 * - [GRACE_MILLIS] = 10_000：`isBusy` 会在「引擎重建 / ask_actor 子 run / 连续发送」
 *   时短暂翻转；没有宽限窗就会出现 FGS 启停风暴（真机可见通知闪烁）。
 */
internal object RunForegroundPolicy {

    /** run 起点到终态的墙钟硬预算（与 `AgentRunner` 同源）。 */
    const val HARD_WALL_CLOCK_MILLIS: Long = 300_000L

    /** 终态落库 / 审批停表的余量。 */
    const val WAKE_LOCK_MARGIN_MILLIS: Long = 90_000L

    /** WakeLock 的物理兜底超时 = 硬预算 + 余量（6.5 分钟）。 */
    const val WAKE_LOCK_TIMEOUT_MILLIS: Long =
        HARD_WALL_CLOCK_MILLIS + WAKE_LOCK_MARGIN_MILLIS

    /** `isBusy` 转闲后的宽限窗：窗口内新 run 直接续用同一个前台服务。 */
    const val GRACE_MILLIS: Long = 10_000L

    /** WakeLock tag：`dumpsys power` 里靠它定位持有者。 */
    const val WAKE_LOCK_TAG: String = "LiquidAgent::Run"

    /**
     * FGS 常驻通知 id。**必须与速度通知 id（1001）不同** —— 后者会被
     * `GenerationNotifier.stop()` cancel（全仓 8 处调用点）。
     *
     * W78 D2 的裁决：id 共用 = 互相 cancel。run1 终态 `stop()` 会把 FGS 通知一起撤掉，
     * 而控制器在宽限窗内不会重新 `startForeground()` ⇒ 第二轮起「已前台化但用户不可见」。
     * 去重由「**单写者 + 借道刷新**」解决（FGS 通知只由服务写，速度文案经
     * `OngoingNotificationSlot` 交给服务刷新同一条），**不由 id 共用解决**。
     */
    const val ONGOING_NOTIFICATION_ID: Int = 1002

    /**
     * WakeLock 续期周期（W78 D9）。
     *
     * 取值依据 = **容错比 ≥3**：`WAKE_LOCK_TIMEOUT_MILLIS(390s) / 120s = 3.25`
     * ⇒ 连续错过 2 次续期（Doze 让 delay 推迟 / OEM 抖动）仍不会断锁；
     * 同时 120s ≫ ColorOS 灭屏 ~10s 的回收阈值 ⇒ 不会变成「每秒抢锁」的耗电循环。
     *
     * 下界约束：常规 run 生命期 ≈ 生成 30–63s + [GRACE_MILLIS] 10s ⇒ ≤73s 的周期会让
     * 每一次普通 run 都触发续期（纯噪声）⇒ 周期必须 > 73s；上界 = `超时 / 3 = 130s`。
     */
    const val WAKE_LOCK_RENEW_INTERVAL_MILLIS: Long = 120_000L

    /**
     * 单次服务生命期内 WakeLock 的**总持有上限**（含续期）= 30 分钟（W78 D9）。
     *
     * 为什么必须有这条：续期会把「390s 物理断路器」的兜底效果取消（锁可以被无限续下去），
     * 所以必须另立一个上界。30min = 6 个完整墙钟预算：
     *  - 合法侧：审批挂起无超时上限，用户放下手机半小时再批准是真实场景；
     *  - 故障侧：控制器卡死时最坏 30min PARTIAL_WAKE_LOCK ≈ 1–2% 电量，且有界。
     * 达上限后停止续期 ⇒ **行为退回今天**（锁自然到期），并留一行日志。
     */
    const val WAKE_LOCK_MAX_HOLD_MILLIS: Long = 1_800_000L

    /** Service Intent 里携带状态栏小图标的 extra（app 层传 `R.drawable.ic_stat_generation`）。 */
    const val EXTRA_SMALL_ICON: String =
        "com.rickeal.agent.core.data.background.EXTRA_SMALL_ICON"

    /**
     * `specialUse` 的 subtype 声明值。
     *
     * 必须与 `core-data/src/main/AndroidManifest.xml` 里
     * `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` 的 value **逐字一致**（守卫 #43 钉死）。
     */
    const val SPECIAL_USE_SUBTYPE: String = "on_device_llm_inference_completion"

    /**
     * 是否需要给 `startForeground` 传类型位。
     *
     * Android 14（API 34）起 targetSdk≥34 的应用**必须**声明并传入 FGS 类型；
     * 平台 33 及以下不认识 `specialUse` 位，传了反而有被拒风险 ⇒ 只在 34+ 传。
     */
    fun usesTypedForeground(sdkInt: Int): Boolean = sdkInt >= 34

    /** WakeLock 超时（单测直接钉死 390_000，防有人「顺手调大」）。 */
    fun wakeLockTimeoutMillis(
        hardBudgetMillis: Long = HARD_WALL_CLOCK_MILLIS,
        marginMillis: Long = WAKE_LOCK_MARGIN_MILLIS,
    ): Long = hardBudgetMillis + marginMillis

    /** 服务是否应当启动：忙且当前未启动。 */
    fun shouldStart(isBusy: Boolean, started: Boolean): Boolean = isBusy && !started

    /** 服务是否应当进入宽限窗倒计时：已启动且已转闲。 */
    fun shouldScheduleStop(isBusy: Boolean, started: Boolean): Boolean = !isBusy && started

    /**
     * 是否还应续期 WakeLock（纯函数，JVM 可测）。
     *
     * 到达 [WAKE_LOCK_MAX_HOLD_MILLIS] 即返回 false ⇒ 续期协程 `break`，锁退回「自然到期」
     * 的物理断路器语义（W30）—— 续期**不得**把断路器架空成「无限持有」。
     */
    fun shouldRenewWakeLock(heldMillis: Long): Boolean = heldMillis < WAKE_LOCK_MAX_HOLD_MILLIS
}
