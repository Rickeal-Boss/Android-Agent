package com.rickeal.agent.core.design.liquid.platform

import android.os.Build
import androidx.annotation.ChecksSdkIntAtLeast

/**
 * 液态玻璃效果按 API 等级门控的集中入口。
 *
 * 三档：
 *  - [FULL]：API ≥ 33。BlurEffect + RuntimeShader（lens + 色散 + AGSL 高光）全部可用。
 *  - [BLUR_ONLY]：API 31~32。BlurEffect 可用，AGSL 不可用；高光退化为静态 LinearGradient。
 *  - [NONE]：理论上不会出现（minSdk = 31 保证 BLUR_ONLY 起步）；保留兜底。
 *
 * 调用方应使用 [hasRuntimeShader] / [hasBlurEffect] 二元门控，
 * 不要直接写 `Build.VERSION.SDK_INT >= 33`，否则与版本策略脱钩。
 */
object LiquidGlassCapabilities {

    enum class Tier {
        /** API ≥ 33：完整液态玻璃（lens + 色散 + AGSL 高光） */
        FULL,
        /** API 31~32：BlurEffect 可用，AGSL 不可用 */
        BLUR_ONLY,
        /** API < 31：理论上不可能，保留兜底 */
        NONE,
    }

    // minSdk = 31 ⇒ `SDK_INT >= S` 恒真（lint ObsoleteSdkInt 据此报出旧写法）。
    // 因此 Tier.NONE 不可达，仅按类头 KDoc「理论上不会出现…保留兜底」保留为声明；
    // 二元分支改写为 if/else，去掉恒真的版本比较。
    // Wave 38 全仓核查：`tier` / `Tier` 当前无任何调用点（只有 KDoc 互引）。保留理由：
    // 它们与 hasBlurEffect 共同构成类头 KDoc 描述的能力分档词汇表，删 `tier` 会连带删掉
    // `Tier` 枚举与类头说明。属可清理项：若确认长期无消费者，可连同 `Tier` 一并删除。
    val tier: Tier = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Tier.FULL
    } else {
        Tier.BLUR_ONLY
    }

    // Compose RenderEffect（BlurEffect 等）在 minSdk 31 起恒可用 ⇒ 恒 true（Wave 38 如实化）。
    // 原为 `SDK_INT >= S` + @ChecksSdkIntAtLeast(S)：minSdk 31 下该判断恒真，注解成了
    // 空契约，违反本仓「诚实性」纪律，故一并去掉。
    // Wave 38 全仓核查：`hasBlurEffect` 当前无任何调用点（只有 KDoc 互引）。保留理由：
    // 与 `tier` / `Tier` 共同构成类头 KDoc 描述的能力分档词汇表。属可清理项：
    // 若确认长期无消费者，可连同 `tier` / `Tier` 一并删除。
    val hasBlurEffect: Boolean = true

    /** Android AGSL RuntimeShader：API 33+ */
    @ChecksSdkIntAtLeast(Build.VERSION_CODES.TIRAMISU)
    val hasRuntimeShader: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
}
