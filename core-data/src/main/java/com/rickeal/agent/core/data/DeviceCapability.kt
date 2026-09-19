package com.rickeal.agent.core.data

import android.os.Build

/**
 * NPU 设备能力门控 —— **只读的纯字符串判断**，不初始化任何推理后端。
 *
 * ## 为什么需要
 *
 * LiteRT-LM 的 NPU 后端在不支持的设备上是在 **native 层** 初始化失败的，
 * 用户看到的就是"选了 NPU、点加载、App 直接闪退"，既没有堆栈也没有提示。
 * 提前判断并给出警告，是这条路径上唯一能做的兜底。
 *
 * ## 两条硬约束（改这个文件前务必读完）
 *
 * 1. **只警告、不禁用。**
 *    `MIN_PART_NUMBER = 8650`（骁龙 8 Gen 3）是**估计值，不是实测**；
 *    做成 disabled 硬墙会误伤其实能跑的设备。local-dream 的成熟做法就是"允许使用但警告"。
 *
 * 2. **绝对不要引入"指定 NPU 架构 / HTP 版本"的参数或环境变量。**
 *    override 与真实硬件不一致会加载错误的 skel（直接崩），
 *    而且每出新一代芯片都要跟着改一次 —— 等于把一次性判断变成长期维护负债。
 *
 * ## 为什么放在 :core-data 而不是 :core-model
 *
 * `core-model/build.gradle.kts` 与 docs/01-architecture.md §1.3 都写明该模块
 * **无 Android 依赖**（只用 java.util / java.io），而 `android.os.Build` 是 Android API。
 * 本模块已经承载同类设备能力查询（`AppContainer.availableMemoryBytes` /
 * `availableStorageBytes`），所以门禁放这里。
 */
object DeviceCapability {

    /** 高通 SoC 的**现代**命名前缀（型号数字可直接与阈值比较）。 */
    private val MODERN_QUALCOMM_PREFIXES = listOf("SM", "QCS", "QCM", "CQ", "SC")

    /**
     * 高通**旧**命名前缀（SDM / MSM，骁龙 855 及更早）。
     *
     * 必须单独列出来并直接判为不支持：这套命名的数字**不能**和 [MIN_PART_NUMBER] 比大小 ——
     * 例如 `MSM8998` 是骁龙 835（2017 年），数字上 8998 > 8650 会误判成"支持"。
     */
    private val LEGACY_QUALCOMM_PREFIXES = listOf("SDM", "MSM")

    /**
     * 认为"大概率支持 NPU"的最低型号数字：**8650 = 骁龙 8 Gen 3**。
     *
     * 参考 local-dream：`setSpillFillGroup` 等 HTP 特性从这一代起才完整可用。
     * 这是估计值，所以本类只用于警告。
     */
    private const val MIN_PART_NUMBER = 8650

    /**
     * 设备 SoC 型号（大写）。拿不到时返回空串。
     *
     * `Build.SOC_MODEL` 是 API 31（S）才有的；我们 minSdk 31，所以版本判断
     * 只是为了在**万一**有人下调 minSdk 时不至于编译失败/崩溃。
     */
    fun socModel(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Build.SOC_MODEL.orEmpty().uppercase()
        } else {
            ""
        }

    /** 是否高通平台（新旧两套命名都算）。 */
    fun isQualcomm(socModel: String = socModel()): Boolean =
        MODERN_QUALCOMM_PREFIXES.any { socModel.startsWith(it) } ||
            LEGACY_QUALCOMM_PREFIXES.any { socModel.startsWith(it) }

    /**
     * 从 `SM8750P` 这类型号里取出数字部分（`8750`）。
     *
     * 做法：**先用已知前缀把字母头剥掉，再取剩下的连续数字** —— 两步都不能省：
     *  - 先剥前缀，是为了**拒绝非高通型号**。若写成"跳过所有非数字再取数字"，
     *    三星 Exynos 的 `S5E8825` 会被解析成 8825 这个看似合理的数字。
     *  - 只取连续数字，是为了让 `SM8750P` 的尾部 `P`、`SM8650-AB` 的 `-AB` 不干扰结果。
     *
     * @return 型号数字；前缀或数字解析不出（如 `unknown`、空串、非高通）返回 null。
     */
    fun partNumber(socModel: String = socModel()): Int? {
        // 先按前缀剥掉字母，避免 "SC8180X" 这类里夹着的数字被误取
        val prefix = (MODERN_QUALCOMM_PREFIXES + LEGACY_QUALCOMM_PREFIXES)
            .firstOrNull { socModel.startsWith(it) }
            ?: return null
        val rest = socModel.removePrefix(prefix)
        val digits = rest.takeWhile { it.isDigit() }
        return digits.toIntOrNull()
    }

    /**
     * 该设备是否"**可能**支持"NPU 后端。
     *
     * 只用于给 UI 一个警告信号，**不可**用来禁用选项：
     * 判断依据是型号字符串 + 一个估计阈值，误报的代价（少警告一次）
     * 必须小于误伤的代价（把能用的设备挡在门外）。
     */
    fun supportsNpu(): Boolean {
        val soc = socModel()
        if (LEGACY_QUALCOMM_PREFIXES.any { soc.startsWith(it) }) return false
        if (!MODERN_QUALCOMM_PREFIXES.any { soc.startsWith(it) }) return false
        val part = partNumber(soc) ?: return false
        return part >= MIN_PART_NUMBER
    }
}
