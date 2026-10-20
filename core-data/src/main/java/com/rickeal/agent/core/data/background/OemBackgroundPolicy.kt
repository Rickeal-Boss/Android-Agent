package com.rickeal.agent.core.data.background

/**
 * 国产 OEM 的「后台保活」差异矩阵（W77）。
 *
 * ## 为什么需要一张独立的表
 *
 * Android 官方只给了 `isIgnoringBatteryOptimizations` 这一条**AOSP 口径**的豁免。
 * 真机实测（OPPO PDRM00 / ColorOS，2026-10-20）证明：即使把应用加入 AOSP doze 白名单，
 * ColorOS 的 `OplusProxyWakeLock` 仍在灭屏约 10 秒后把第三方 `PARTIAL_WAKE_LOCK`
 * 强制回收（`adb logcat -b system` 可见 `... to FroceReleaseWakeLock list`）。
 *
 * 也就是说：**各家 ROM 的后台管控是独立于 AOSP 的第二套系统**，路径、粒度、是否
 * 允许自启动/关联启动/后台高耗电，全都不一样。应用无法程序化设置它们（无公开 API），
 * 唯一能做的是「按厂商给准确的引导文案 + 诚实说明不确定性」。
 *
 * ## 设计约束
 *
 * 本文件**不含任何 Android 依赖**（厂商名以字符串传入）⇒ 可纯 JVM 单测。
 * 分类与文案是「跨机型契约」，写错了用户会照着找不到路径 —— 这比崩溃更难发现。
 */
internal object OemBackgroundPolicy {

    /** 后台管控形态相近的厂商家族（不是厂商清单，而是「同一套设置路径」的归类）。 */
    enum class OemFamily {
        /** OPPO / realme / OnePlus：ColorOS 系。 */
        COLOR_OS,

        /** 小米 / Redmi / POCO：HyperOS / MIUI 系。 */
        HYPER_OS,

        /** 华为 / 荣耀旧机型：EMUI / HarmonyOS 4 及以下（HarmonyOS NEXT 不适用本路径）。 */
        EMUI_HARMONY,

        /** 荣耀独立后：MagicOS。 */
        MAGIC_OS,

        /** vivo / iQOO：OriginOS / FuntouchOS。 */
        ORIGIN_OS,

        /** 三星：One UI。 */
        ONE_UI,

        /** 魅族：Flyme。 */
        FLYME,

        /** AOSP / Pixel / 其它未识别厂商：只走系统电池优化页。 */
        AOSP,
    }

    /**
     * 由 `Build.MANUFACTURER` / `Build.BRAND` 归类。
     *
     * 判定顺序有讲究：先看 brand（子品牌信息更具体，如 Redmi 的 manufacturer 仍是 Xiaomi），
     * 再看 manufacturer。两边都小写比较 —— ROM 厂商的大小写并不统一（`realme` / `HONOR`）。
     */
    fun classify(manufacturer: String, brand: String): OemFamily {
        val m = manufacturer.trim().lowercase()
        val b = brand.trim().lowercase()
        val both = "$m|$b"

        return when {
            // 一加 / realme 与 OPPO 共用 ColorOS 的后台管理（一加在部分版本叫 OxygenOS，
            // 但国内版路径与 ColorOS 一致）。
            containsAny(both, "oppo", "realme", "oneplus") -> OemFamily.COLOR_OS
            containsAny(both, "xiaomi", "redmi", "poco") -> OemFamily.HYPER_OS
            // 荣耀独立后 manufacturer 为 HONOR；老荣耀机型仍是 HUAWEI（按 EMUI 路径）。
            containsAny(m, "honor") || containsAny(b, "honor") -> OemFamily.MAGIC_OS
            containsAny(both, "huawei", "harmony") -> OemFamily.EMUI_HARMONY
            containsAny(both, "vivo", "iqoo") -> OemFamily.ORIGIN_OS
            containsAny(both, "samsung") -> OemFamily.ONE_UI
            containsAny(both, "meizu") -> OemFamily.FLYME
            else -> OemFamily.AOSP
        }
    }

    /**
     * 该家族「让应用在后台/锁屏继续跑」的设置路径（用户可照着点）。
     *
     * 文案纪律：
     * - 只写**路径**，不承诺效果（「设置后永不被杀」是假话，各家仍可能回收）；
     * - 末尾统一提示「路径可能因系统版本略有差异」——ROM 每年都在改菜单层级；
     * - 能加「最近任务下拉加锁」的厂商一并写（这是 MIUI/ColorOS/OriginOS 的通用增强）。
     */
    fun guideText(family: OemFamily): String = when (family) {
        OemFamily.COLOR_OS ->
            "设置 → 电池 → 应用耗电管理 → 本应用 → 允许后台活动 + 允许完全后台行为；" +
                "最近任务里下拉卡片加锁"
        OemFamily.HYPER_OS ->
            "设置 → 应用设置 → 应用管理 → 本应用 → 省电策略 → 无限制；" +
                "最近任务里下拉卡片加锁"
        OemFamily.EMUI_HARMONY ->
            "手机管家 → 应用启动管理 → 本应用 → 关闭「自动管理」，" +
                "允许自启动 / 关联启动 / 后台活动"
        OemFamily.MAGIC_OS ->
            "手机管家 → 应用启动管理 → 本应用 → 手动管理，" +
                "允许自启动 / 关联启动 / 后台活动"
        OemFamily.ORIGIN_OS ->
            "设置 → 电池 → 后台耗电管理 → 本应用 → 允许后台高耗电；" +
                "i 管家 → 自启动管理 → 允许自启动"
        OemFamily.ONE_UI ->
            "设置 → 电池 → 后台使用限制 → 把本应用加入「从不休眠的应用」"
        OemFamily.FLYME ->
            "手机管家 → 权限管理 → 后台管理 → 本应用 → 允许后台运行"
        OemFamily.AOSP ->
            "设置 → 电池 → 电池优化 → 本应用 → 不优化"
    }

    /** 该家族是否支持「最近任务下拉加锁」这一通用增强（用于文案是否提示）。 */
    fun supportsRecentsLock(family: OemFamily): Boolean = when (family) {
        OemFamily.COLOR_OS, OemFamily.HYPER_OS, OemFamily.ORIGIN_OS -> true
        else -> false
    }

    /** 厂商家族 → 用户可读名（设置卡标题后缀用）。 */
    fun displayName(family: OemFamily): String = when (family) {
        OemFamily.COLOR_OS -> "ColorOS / realme UI"
        OemFamily.HYPER_OS -> "HyperOS / MIUI"
        OemFamily.EMUI_HARMONY -> "EMUI / HarmonyOS"
        OemFamily.MAGIC_OS -> "MagicOS"
        OemFamily.ORIGIN_OS -> "OriginOS / FuntouchOS"
        OemFamily.ONE_UI -> "One UI"
        OemFamily.FLYME -> "Flyme"
        OemFamily.AOSP -> "Android"
    }

    private fun containsAny(haystack: String, vararg needles: String): Boolean =
        needles.any { haystack.contains(it) }
}
