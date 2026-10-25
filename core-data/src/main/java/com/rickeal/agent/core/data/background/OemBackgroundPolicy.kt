package com.rickeal.agent.core.data.background

import com.rickeal.agent.core.data.oem.OemBackgroundData

/**
 * 国产 OEM 的「后台保活」差异矩阵（W77 / W78）。
 *
 * ## 为什么需要一张独立的表
 *
 * Android 官方只给了 `isIgnoringBatteryOptimizations` 这一条**AOSP 口径**的豁免。
 * 真机实测（OPPO PDRM00 / ColorOS，2026-10-20 两轮 + W77-04 四轮 A/B）：
 * ① 仅加 AOSP doze 白名单：ColorOS 的 `OplusProxyWakeLock` 仍在灭屏约 10 秒后把
 *    第三方 `PARTIAL_WAKE_LOCK` 强制回收（`adb logcat -b system` 可见
 *    `... to FroceReleaseWakeLock list`）⇒ TTFT 从秒级恶化到 200.9s。
 * ② 手动开启 ColorOS「耗电行为控制 → 允许完全后台行为」后：路径**确实引导到正确页面**
 *    （有截图为证），W77-04 四轮灭屏 A/B 的 run 均结算。
 * ③ 2026-10-22 复测（`docs/w78-bg-run-design.md` R15，六项控制变量）：**即使开着「允许
 *    完全后台行为」，灭屏约 10s 仍会冻结本进程**（cgroup 级：`OplusHansManager` →
 *    `SM_EVENT_LCD_OFF_UNIFIED_FREEZE` → `freeze uid`），且**锁被强制回收的时刻 =
 *    进程被冻结的时刻**，不受 FGS / WakeLock（390s + 120s 续期）/ AOSP 豁免 / 飞行模式
 *    影响 ⇒ W77-04 当时依据的「锁态判据」是段位检测的假绿，故 [OemBackgroundData.EVIDENCE] 的 COLOR_OS
 *    锚点已按此修订：路径有效 ≠ 开启后不被冻结。
 *
 * 也就是说：**各家 ROM 的后台管控是独立于 AOSP 的第二套系统**，路径、粒度、是否
 * 允许自启动/关联启动/后台高耗电，全都不一样。应用无法程序化设置它们（无公开 API），
 * 唯一能做的是「按厂商给准确的引导文案 + 诚实说明不确定性」。
 *
 * ## W78 的两处结构性变化
 *
 * ① 「最近任务下拉加锁」这条知识**回到文案本身**：9 族里加锁的**位置**不同 ——
 *    ColorOS/HyperOS/OriginOS/MagicOS/NUBIA 是末尾**增强**，EMUI_HARMONY 在
 *    HarmonyOS 5+ 是**替代路径**（不是增强）。统一后缀会与 5+ 分叉文案冲突，
 *    所以删掉了那个「该族是否支持加锁」的布尔访问器（它曾是「第二事实源」，与文案漂移
 *    就是 bug 的形态）。
 * ② 每族带**一档证据强度 + 一行取证锚点**，落在 [OemBackgroundData.EVIDENCE] 数据表里（不是注释）。
 *    两个消费者：卡片路径行的括注（用户可见的人话）与守卫 #46（机械判据）。
 *    `evidenceAnchor` 零生产读点但**不是死代码** —— 它的消费者是判据，与
 *    `ModelPresets.evidenceLevel` 的现状同构。
 *
 * ## 设计约束
 *
 * 本文件**不含任何 Android 依赖**（厂商名以字符串传入）⇒ 可纯 JVM 单测。
 * 分类与文案是「跨机型契约」，写错了用户会照着找不到路径 —— 这比崩溃更难发现。
 *
 * ## 数据表已外提（W79-07 / W83-05）
 *
 * [OemBackgroundData.GUIDES]（族 → 路径）与 [OemBackgroundData.EVIDENCE]（族 → 证据档
 * + 锚点）两张表已搬至兄弟目录 `core/data/oem/OemBackgroundData.kt`：守卫 #45 给
 * background/ 包划了 1000 行上限，搬迁前余 4 ⇒ 下波动该包即触顶；两张表是包内唯一可移面
 * （枚举 / [classify] / 访问器是行为面）。本文件只留分类与访问器，表经 [OemBackgroundData]
 * 委托读取（getValue 缺失即抛的 fail-closed 语义不变）。
 *
 * ⚠️ [OemBackgroundData.EVIDENCE] **一行一族**、行首形态固定，守卫 #46 用行首正则机械提取 ⇒
 * **禁止在注释 / KDoc 里写完整示例行**（会污染提取，这是该守卫自带的已知面）。
 */
internal object OemBackgroundPolicy {

    /** 后台管控形态相近的厂商家族（不是厂商清单，而是「同一套设置路径」的归类）。 */
    enum class OemFamily {
        /** OPPO / realme / OnePlus：ColorOS 系。 */
        COLOR_OS,

        /** 小米 / Redmi / POCO：HyperOS / MIUI 系。 */
        HYPER_OS,

        /** 华为 / 荣耀旧机型：EMUI / HarmonyOS（5+ 菜单已改版，文案里半句分叉）。 */
        EMUI_HARMONY,

        /** 荣耀独立后：MagicOS。 */
        MAGIC_OS,

        /** vivo / iQOO：OriginOS / FuntouchOS。 */
        ORIGIN_OS,

        /** nubia / RedMagic / ZTE 系（W78 新增）：官方协议页背书的独立「智能耗电保护」。 */
        NUBIA,

        /** 三星：One UI。 */
        ONE_UI,

        /** 魅族：Flyme。 */
        FLYME,

        /** AOSP / Pixel / 其它未识别厂商：只走系统电池优化页。 */
        AOSP,
    }

    /** 每族引导文案的证据强度（W78 D5）。3 档，边界按「来源是否厂商官方」切分。 */
    enum class OemEvidence {
        /** 本仓真机实测：锚点必须指向本仓波次（波次号形态）。 */
        MEASURED,

        /** 厂商官方文档 / 官方支持页 / 官方协议页背书，**未在本机实测**。 */
        OFFICIAL,

        /** 仅社区 / 三方资料口径。 */
        COMMUNITY,
    }

    /**
     * 由 `Build.MANUFACTURER` / `Build.BRAND` 归类。
     *
     * 判定顺序有讲究：先看 brand（子品牌信息更具体，如 Redmi 的 manufacturer 仍是 Xiaomi），
     * 再看 manufacturer。两边都小写比较 —— ROM 厂商的大小写并不统一（`realme` / `HONOR`）。
     *
     * ⚠️ 已知面（v22-F3）：[containsAny] 是子串匹配，`zte` 理论上可命中含该子串的怪厂商名，
     * 实测影响 ≈0。改匹配算法要动全部 9 族判定，回归面远大于收益 ⇒ 本波不修。
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
            // W78：中兴系（nubia / 红魔）有独立于 AOSP 的「智能耗电保护」，
            // 归到 AOSP 等于给出**已知不足**的建议。红魔是游戏向机型，恰是端侧 AI 目标用户。
            containsAny(m, "nubia", "zte") || containsAny(b, "nubia", "redmagic") -> OemFamily.NUBIA
            else -> OemFamily.AOSP
        }
    }

    /**
     * 该家族「让应用在后台/锁屏继续跑」的设置路径（用户可照着点）。
     *
     * 只返回**路径本身**（不含 [displayName] 前缀）：卡片自己拼「族名（证据括注）：路径」，
     * 免得两处各自拼前缀。文案纪律：只写路径，不承诺效果；「路径可能因系统版本略有差异」
     * 与通用搜索兜底由设置卡统一渲染（不塞进 9 族文案，否则每族各长 45 字符）。
     */
    fun guideText(family: OemFamily): String = guide(family).path

    /** 厂商家族 → 用户可读名（设置卡路径行的前缀）。 */
    fun displayName(family: OemFamily): String = guide(family).displayName

    /** 该族引导文案的证据强度（W78 D5；卡片括注的取值来源）。 */
    fun evidence(family: OemFamily): OemEvidence = evidenceRow(family).first

    /** 该族证据的取证锚点（守卫 #46 的机械判据：`MEASURED` 必须带本仓波次号）。 */
    fun evidenceAnchor(family: OemFamily): String = evidenceRow(family).second

    /** 该族是否有独立于 AOSP 的厂商后台管控（AOSP = false ⇒ 卡片限定行换一句）。 */
    fun isVendorManaged(family: OemFamily): Boolean = family != OemFamily.AOSP

    /** 缺失即抛（fail-closed）：漏填家族的后果是「设置卡点开就崩」，不是「静默给错路径」。 */
    private fun guide(family: OemFamily): OemBackgroundData.Guide = OemBackgroundData.GUIDES.getValue(family)

    private fun evidenceRow(family: OemFamily): Pair<OemEvidence, String> =
        OemBackgroundData.EVIDENCE.getValue(family)

    private fun containsAny(haystack: String, vararg needles: String): Boolean =
        needles.any { haystack.contains(it) }
}
