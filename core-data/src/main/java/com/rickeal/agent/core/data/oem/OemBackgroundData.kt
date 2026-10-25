package com.rickeal.agent.core.data.oem

import com.rickeal.agent.core.data.background.OemBackgroundPolicy.OemEvidence
import com.rickeal.agent.core.data.background.OemBackgroundPolicy.OemFamily

/**
 * 国产 OEM「后台保活」差异矩阵的**数据面**（W79-07 / W83-05 从 background/ 包外提）。
 *
 * ## 为什么数据在兄弟目录而不留在 background/ 包内
 *
 * 守卫 #45 给 background/ 包划了 1000 行包级上限：搬迁前该包合计 996 行（余 4）——
 * 下波动该包（哪怕只加一行 KDoc）即触顶，而 W79-07 定下的「重启前先拆」前提已实质
 * 逼近。包内可移的面只有两张数据表（[GUIDES] / [EVIDENCE]）：分类逻辑、枚举与访问器
 * 都是行为面，动了就是回归风险。守卫 #45 只扫 background/ 目录且禁子目录 ⇒ 唯一有效解
 * = 平级移出到兄弟目录 `core/data/oem/`。
 *
 * 为什么不提 public：两张表的全部消费者都在 core-data 模块内（[OemBackgroundPolicy]
 * 同模块委托读取，单测在 background 测试包）⇒ `internal object` 即最小可见面。
 *
 * ⚠️ [EVIDENCE] **一行一族**、行首形态固定，守卫 #46 用行首正则从本表机械提取（枚举 ↔
 * 表双向一致 + 档位合法 + MEASURED 带 W## 锚点）⇒ **禁止在 KDoc / 注释里写完整示例行**
 * （会污染提取，这是该守卫自带的已知面）。同理 [GUIDES] 表用 `to Guide(` 形态，与 #46
 * 正则不交叉（两表互不污染）。
 */
internal object OemBackgroundData {

    data class Guide(val displayName: String, val path: String)

    val GUIDES: Map<OemFamily, Guide> = mapOf(
        OemFamily.COLOR_OS to Guide(
            displayName = "ColorOS / realme UI / OxygenOS",
            path = "设置 → 电池 → 应用耗电管理 → 本应用 → 允许后台活动 + 允许应用自启动 + 允许关联启动 + 允许完全后台行为；最近任务下拉加锁",
        ),
        OemFamily.HYPER_OS to Guide(
            displayName = "HyperOS / MIUI",
            path = "设置 → 应用设置 → 应用管理 → 本应用 → 省电策略 / 应用智能省电 → 无限制；应用权限 → 后台自启动 → 允许；最近任务下拉加锁",
        ),
        OemFamily.EMUI_HARMONY to Guide(
            displayName = "EMUI / HarmonyOS",
            path = "手机管家 → 应用启动管理 → 本应用 → 关闭「自动管理」，允许自启动/关联启动/后台活动；HarmonyOS 5+ 改用下拉加锁 + 关闭省电模式",
        ),
        OemFamily.MAGIC_OS to Guide(
            displayName = "MagicOS",
            path = "手机管家 → 应用启动管理 → 本应用 → 关闭「自动管理」，允许自启动/关联启动/后台活动；电池优化 → 本应用 → 不允许；最近任务下拉加锁",
        ),
        OemFamily.ORIGIN_OS to Guide(
            displayName = "OriginOS / FuntouchOS",
            path = "设置 → 电池 → 后台耗电管理 → 允许后台高耗电；i 管家 → 自启动管理 → 允许；i 管家 → 省电管理 → 加速白名单 → 加入；最近任务下拉加锁",
        ),
        OemFamily.NUBIA to Guide(
            displayName = "nubia / RedMagic（中兴系）",
            path = "设置 → 电池 → 智能耗电保护 → 关闭，或自定义 → 允许后台运行；最近任务下拉加锁",
        ),
        OemFamily.ONE_UI to Guide(
            displayName = "One UI",
            path = "设置 → 电池 → 后台使用限制 → 从不休眠的应用 → 添加本应用；或：应用信息 → 电池 → 不受限制",
        ),
        OemFamily.FLYME to Guide(
            displayName = "Flyme",
            path = "手机管家 → 隐私权限（部分版本叫「权限管理」）→ 后台管理 → 允许后台运行；自启动 → 允许",
        ),
        OemFamily.AOSP to Guide(
            displayName = "Android",
            path = "设置 → 电池 → 电池优化 → 本应用 → 不优化",
        ),
    )

    /** 家族 × 证据档 × 取证锚点（#46 的数据面；一行一族，供机械解析）。 */
    val EVIDENCE: Map<OemFamily, Pair<OemEvidence, String>> = mapOf(
        OemFamily.COLOR_OS to (OemEvidence.MEASURED to "W77-04 四轮灭屏 A/B：允许完全后台行为后 run 均结算（锁态判据后被 W78-08 推翻，段位检测假绿；未测 TTFT）"),
        OemFamily.HYPER_OS to (OemEvidence.OFFICIAL to "小米开发者文档 pId=1628/1624（PowerKeeper / 自启动权限）"),
        OemFamily.EMUI_HARMONY to (OemEvidence.OFFICIAL to "华为支持页：启动管理三开关；HarmonyOS 5+ 另有一套"),
        OemFamily.MAGIC_OS to (OemEvidence.OFFICIAL to "荣耀官方支持页 zh-cn00406916：启动管理 + 电池优化两步"),
        OemFamily.ORIGIN_OS to (OemEvidence.OFFICIAL to "vivo 官方适配指南 + 支持文档：后台高耗电 + 自启动 + 加速白名单"),
        OemFamily.NUBIA to (OemEvidence.OFFICIAL to "红魔装备库官方协议页：智能耗电保护 + 最近任务加锁"),
        OemFamily.ONE_UI to (OemEvidence.OFFICIAL to "三星开发者文档 app-management：四列表 + 应用信息→电池→不受限制"),
        OemFamily.FLYME to (OemEvidence.COMMUNITY to "魅族无官方后台管控文档；社区三方口径（手机管家→隐私权限→后台管理）"),
        OemFamily.AOSP to (OemEvidence.OFFICIAL to "Android 官方：电池优化豁免（AOSP 标准路径）"),
    )
}
