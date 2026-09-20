package com.rickeal.agent.core.design.liquid.platform

import android.graphics.RuntimeShader as AndroidRuntimeShaderNative
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.toArgb

/**
 * AGSL 着色器抽象（项目 Android-only，不走 KMP expect/actual）。
 *
 * - AGSL 仅在 Android 13 (API 33) 起官方支持。
 * - 项目 minSdk = 31：API 31~32 上 [obtainRuntimeShader] 永不返回非 null
 *   （见 [LiquidGlassCapabilities.hasRuntimeShader]），调用方必须先判门控。
 *
 * 端口自 Kyant0 backdrop 库（Apache-2.0，详见 [com.rickeal.agent.core.design.liquid.Shaders]）。
 */
interface RuntimeShader {

    fun setFloatUniform(name: String, value: Float)
    fun setFloatUniform(name: String, value1: Float, value2: Float)
    fun setFloatUniform(name: String, value1: Float, value2: Float, value3: Float)
    fun setFloatUniform(name: String, value1: Float, value2: Float, value3: Float, value4: Float)
    fun setFloatUniform(name: String, values: FloatArray)

    fun setIntUniform(name: String, value: Int)
    fun setIntUniform(name: String, value1: Int, value2: Int)
    fun setIntUniform(name: String, value1: Int, value2: Int, value3: Int)
    fun setIntUniform(name: String, value1: Int, value2: Int, value3: Int, value4: Int)
    fun setIntUniform(name: String, values: IntArray)

    fun setColorUniform(name: String, color: Color)
}

/**
 * 平台入口：构造一个新的 AGSL 着色器实例。仅 API 33+ 可用，调用方需先门控。
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal fun liquidRuntimeShader(shaderString: String): RuntimeShader {
    return AndroidRuntimeShaderImpl(AndroidRuntimeShaderNative(shaderString))
}

/**
 * 把抽象 RuntimeShader 暴露为 Compose Shader（用于 RenderEffect.createRuntimeShaderEffect）。
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal fun RuntimeShader.asComposeShader(): Shader {
    return (this as AndroidRuntimeShaderImpl).shader
}

/**
 * 把抽象 RuntimeShader 暴露为底层 android.graphics.RuntimeShader（用于 Framework Paint.setShader）。
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal fun RuntimeShader.asAndroidRuntimeShader(): android.graphics.RuntimeShader {
    return (this as AndroidRuntimeShaderImpl).shader
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private class AndroidRuntimeShaderImpl(val shader: AndroidRuntimeShaderNative) : RuntimeShader {

    override fun setFloatUniform(name: String, value: Float) {
        shader.setFloatUniform(name, value)
    }

    override fun setFloatUniform(name: String, value1: Float, value2: Float) {
        shader.setFloatUniform(name, value1, value2)
    }

    override fun setFloatUniform(name: String, value1: Float, value2: Float, value3: Float) {
        shader.setFloatUniform(name, value1, value2, value3)
    }

    override fun setFloatUniform(name: String, value1: Float, value2: Float, value3: Float, value4: Float) {
        shader.setFloatUniform(name, value1, value2, value3, value4)
    }

    override fun setFloatUniform(name: String, values: FloatArray) {
        shader.setFloatUniform(name, values)
    }

    override fun setIntUniform(name: String, value: Int) {
        shader.setIntUniform(name, value)
    }

    override fun setIntUniform(name: String, value1: Int, value2: Int) {
        shader.setIntUniform(name, value1, value2)
    }

    override fun setIntUniform(name: String, value1: Int, value2: Int, value3: Int) {
        shader.setIntUniform(name, value1, value2, value3)
    }

    override fun setIntUniform(name: String, value1: Int, value2: Int, value3: Int, value4: Int) {
        shader.setIntUniform(name, value1, value2, value3, value4)
    }

    override fun setIntUniform(name: String, values: IntArray) {
        shader.setIntUniform(name, values)
    }

    override fun setColorUniform(name: String, color: Color) {
        shader.setColorUniform(name, color.toArgb())
    }
}

/**
 * 是否支持 Compose 原生 RenderEffect（BlurEffect 等）：API 31+。
 */
fun isRenderEffectSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/**
 * 是否支持 AGSL RuntimeShader：API 33+。
 */
fun isRuntimeShaderSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
