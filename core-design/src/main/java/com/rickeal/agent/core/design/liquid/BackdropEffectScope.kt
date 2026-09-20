package com.rickeal.agent.core.design.liquid

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection

/**
 * 液态玻璃的效果作用域：在 `effects { ... }` DSL 里取当前节点的尺寸/形状，
 * 注册 RenderEffect / RuntimeShader / lens / blur 等。
 *
 * 实现见 [BackdropEffectScopeImpl]。
 */
sealed interface BackdropEffectScope : Density, RuntimeShaderCache {

    /** 当前节点的尺寸（像素） */
    val size: Size

    val layoutDirection: LayoutDirection

    /** 当前节点的形状（已缓存 outline） */
    val shape: Shape

    /**
     * 因 blur / lens 边缘效果需要额外绘制的留白（像素）。
     * 设置后 DrawBackdropNode 会把背景源扩展 padding 像素后再绘制，保证边缘效果不被截断。
     */
    var padding: Float

    /**
     * 当前累积的 RenderEffect 链。`effects {}` 块里调用 blur/lens/colorFilter 会改写它。
     * 块结束时由 BackdropEffectScopeImpl.apply() 重置，由 DrawBackdropNode 一次性写进 GraphicsLayer。
     */
    var renderEffect: RenderEffect?
}

internal abstract class BackdropEffectScopeImpl : BackdropEffectScope, RuntimeShaderCache {

    override var density: Float = 1f
    override var fontScale: Float = 1f
    override var size: Size = Size.Unspecified
    override var layoutDirection: LayoutDirection = LayoutDirection.Ltr
    override var padding: Float = 0f
    override var renderEffect: RenderEffect? = null

    private val runtimeShaderCache = RuntimeShaderCacheImpl()

    override fun obtainRuntimeShader(key: String, string: String): RuntimeShader {
        return runtimeShaderCache.obtainRuntimeShader(key, string)
    }

    /**
     * 同步当前 DrawScope 的最新状态。返回 true 表示状态变了（调用方应重算 effects）。
     */
    fun update(scope: DrawScope): Boolean {
        val newDensity = scope.density
        val newFontScale = scope.fontScale
        val newSize = scope.size
        val newLayoutDirection = scope.layoutDirection

        val changed = newDensity != density ||
            newFontScale != fontScale ||
            newSize != size ||
            newLayoutDirection != layoutDirection

        if (changed) {
            density = newDensity
            fontScale = newFontScale
            size = newSize
            layoutDirection = newLayoutDirection
        }

        return changed
    }

    /**
     * 进入新的 effects 块：重置 padding / renderEffect，运行用户 lambda。
     */
    fun apply(effects: BackdropEffectScope.() -> Unit) {
        padding = 0f
        renderEffect = null
        effects()
    }

    /**
     * 节点 detach 时清空，释放 shader 缓存。
     */
    fun reset() {
        density = 1f
        fontScale = 1f
        size = Size.Unspecified
        layoutDirection = LayoutDirection.Ltr
        padding = 0f
        renderEffect = null
        runtimeShaderCache.clear()
    }
}
