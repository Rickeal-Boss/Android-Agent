package com.rickeal.agent.core.data.background

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.net.toUri

/**
 * 「后台运行保障」的**只读检测 + 免权限引导**（W77）。
 *
 * ## 为什么只做「引导」而不做「申请」
 *
 * 两条不同的系统能力，代价完全不同：
 *
 * | 手段 | 是否需要额外权限 | 政策代价 | 本仓选择 |
 * |---|---|---|---|
 * | `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`（系统全列表页） | 否 | 无 | ✅ 本波采用 |
 * | `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`（直接弹系统对话框） | 需 `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Play 限定「核心功能强依赖」类应用，需申报 | ❌ 不采用 |
 *
 * 本项目的分发形态是 gallery / 开源，暂时没有上架收益，却要背上一个受限权限的
 * 审核面 —— 不划算。检测本身**免权限**（`isIgnoringBatteryOptimizations`），
 * 所以「三态展示 + 一键跳系统页」这套体验零权限成本。
 *
 * ## 诚实口径
 *
 * 白名单**不保证**不被杀（它放宽 Doze，不防 LMK）。设置卡上的文案必须照这个写，
 * 不能写「设置后永不被杀」—— 这与 knownBroken 红字是同一套设计哲学：
 * 讲清代价、用户自己决定。
 */
class BackgroundRestrictionStatus(context: Context) {

    private val appContext: Context = context.applicationContext

    /** 是否已被系统电池优化豁免（免权限检测）。 */
    fun isBatteryOptimizationIgnored(): Boolean {
        val powerManager = appContext.getSystemService(PowerManager::class.java) ?: return false
        return powerManager.isIgnoringBatteryOptimizations(appContext.packageName)
    }

    /**
     * 当前设备的 OEM 后台管控家族（OPPO / 小米 / 华为 / 荣耀 / vivo / 三星 / 魅族 / AOSP）。
     *
     * 只用于**换一段准确的引导路径**，不做任何自动化设置 —— 这些 ROM 的后台白名单
     * 没有公开 API，任何「一键设置」都是不可靠的伪能力。
     * 归类规则与文案见 [OemBackgroundPolicy]（纯函数，可 JVM 单测）。
     */
    internal fun oemFamily(): OemBackgroundPolicy.OemFamily =
        OemBackgroundPolicy.classify(
            manufacturer = Build.MANUFACTURER.orEmpty(),
            brand = Build.BRAND.orEmpty(),
        )

    /** 当前设备的后台保活引导路径（逐厂商不同，见 [OemBackgroundPolicy.guideText]）。 */
    fun backgroundGuideText(): String = OemBackgroundPolicy.guideText(oemFamily())

    /** 当前厂商家族的用户可读名（卡片副标题用）。 */
    fun oemDisplayName(): String = OemBackgroundPolicy.displayName(oemFamily())

    /**
     * 当前设备是否有独立于 AOSP 的厂商后台管控（W78 D3）。
     *
     * 卡片限定行用：有厂商层时只能说「本项只覆盖 Android 系统层面」，不能说效果承诺 ——
     * 厂商层**不可程序化检测**（无公开 API），所以这是「是否有第二套系统」的静态事实，
     * 不是二态状态。
     */
    fun hasVendorBackgroundLayer(): Boolean =
        OemBackgroundPolicy.isVendorManaged(oemFamily())

    /**
     * 当前族引导路径的证据强度，转成一句用户可读的括注（W78 D5；不含枚举名）。
     *
     * 用户不需要知道我们的内部分级，需要知道「这条路可信到什么程度」。
     * `OFFICIAL` 的措辞刻意**不带「厂商」二字** —— AOSP 族的官方源是 Google，
     * 写「厂商官方说明」在那里读不通。
     */
    fun backgroundEvidenceNote(): String =
        when (OemBackgroundPolicy.evidence(oemFamily())) {
            OemBackgroundPolicy.OemEvidence.MEASURED -> "本机已实测"
            OemBackgroundPolicy.OemEvidence.OFFICIAL -> "官方说明，未在本机实测"
            OemBackgroundPolicy.OemEvidence.COMMUNITY -> "社区经验，未在本机实测"
        }

    /**
     * 打开系统的「电池优化」列表页（**免权限**）。
     *
     * 失败（部分 ROM 裁剪了该页面）时落到应用详情页，保证按钮永远有反馈。
     */
    fun openBatteryOptimizationSettings() {
        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { appContext.startActivity(intent) }
            .onFailure { openAppDetails() }
    }

    /** 打开本应用的应用详情页（所有 ROM 都有，作为最终兜底）。 */
    fun openAppDetails() {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            "package:${appContext.packageName}".toUri(),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { appContext.startActivity(intent) }
    }
}
