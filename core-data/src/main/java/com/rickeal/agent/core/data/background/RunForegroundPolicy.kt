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
     * FGS 通知 id —— **与 `AndroidGenerationNotifier` 的 1001 共用**。
     *
     * 同 id 覆盖语义：速度 tick 刷新的是同一条通知，不会在通知栏堆两条
     * （「生成中」+「后台运行中」）。见 `docs/w77-bg-run-design.md` §五。
     */
    const val ONGOING_NOTIFICATION_ID: Int = 1001

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
}
