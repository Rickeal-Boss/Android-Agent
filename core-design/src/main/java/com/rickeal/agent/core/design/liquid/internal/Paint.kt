package com.rickeal.agent.core.design.liquid.internal

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import com.rickeal.agent.core.design.liquid.platform.RuntimeShader
import com.rickeal.agent.core.design.liquid.platform.asAndroidRuntimeShader

/**
 * `Paint.blur` —— 在 Compose Paint 上应用 Skia 模糊 mask filter。
 * Compose Paint 没有公开 maskFilter，需要走 nativeCanvas 拿到底层 Skia paint。
 *
 * 端口自 Kyant0 backdrop 库（Apache-2.0）。
 */
internal fun Paint.blur(radius: Float) {
    if (radius <= 0f) return
    if (style != PaintingStyle.Stroke && style != PaintingStyle.Fill) return
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    val blurMaskFilter = android.graphics.BlurMaskFilter(
        radius,
        android.graphics.BlurMaskFilter.Blur.NORMAL
    )
    this.asFrameworkPaint().maskFilter = blurMaskFilter
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal fun Paint.setRuntimeShader(runtimeShader: RuntimeShader?) {
    val frameworkPaint = this.asFrameworkPaint()
    if (runtimeShader == null) {
        frameworkPaint.setShader(null)
        return
    }
    frameworkPaint.setShader(runtimeShader.asAndroidRuntimeShader())
}

@Suppress("unused")
private fun DrawScope.ensureNativeCanvas() = drawContext.canvas.nativeCanvas
