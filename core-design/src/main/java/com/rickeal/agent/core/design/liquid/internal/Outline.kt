package com.rickeal.agent.core.design.liquid.internal

import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path

/**
 * 把 outline 裁剪到当前 canvas。
 * Rounded 用 Path 缓存避免每帧重建（Outline.Rounded.roundRect 是新对象）。
 *
 * 端口自 Kyant0 backdrop 库（Apache-2.0）。
 */
internal fun Canvas.clipOutline(outline: Outline, path: Path?) {
    when (outline) {
        is Outline.Rectangle -> clipRect(outline.rect)
        is Outline.Rounded -> {
            path!!.rewind()
            path.addRoundRect(outline.roundRect)
            clipPath(path)
        }

        is Outline.Generic -> clipPath(outline.path)
    }
}
