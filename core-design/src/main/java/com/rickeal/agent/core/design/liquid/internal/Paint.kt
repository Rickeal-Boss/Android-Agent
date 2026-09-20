package com.rickeal.agent.core.design.liquid.internal

import android.graphics.BlurMaskFilter
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.Paint
import com.rickeal.agent.core.design.liquid.platform.RuntimeShader
import com.rickeal.agent.core.design.liquid.platform.asAndroidRuntimeShader

/**
 * `Paint.blur` —— 在 Compose Paint 上应用 Skia 模糊 mask filter。
 * Compose Paint 没有公开 maskFilter，需要经 `asFrameworkPaint()` 拿到底层 Skia paint。
 *
 * 端口自 Kyant0 backdrop 库（Apache-2.0）。
 */
internal fun Paint.blur(radius: Float) {
    this.asFrameworkPaint().maskFilter =
        if (radius > 0f) {
            BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL)
        } else {
            null
        }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal fun Paint.setRuntimeShader(runtimeShader: RuntimeShader?) {
    this.asFrameworkPaint().shader = runtimeShader?.asAndroidRuntimeShader()
}
