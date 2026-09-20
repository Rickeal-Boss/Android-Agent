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

    val tier: Tier = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> Tier.FULL
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> Tier.BLUR_ONLY
        else -> Tier.NONE
    }

    /** Compose RenderEffect（BlurEffect 等）：API 31+ */
    @ChecksSdkIntAtLeast(Build.VERSION_CODES.S)
    val hasBlurEffect: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    /** Android AGSL RuntimeShader：API 33+ */
    @ChecksSdkIntAtLeast(Build.VERSION_CODES.TIRAMISU)
    val hasRuntimeShader: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
}
