package com.rickeal.agent.core.design.liquid.platform

/**
 * AGSL 着色器缓存。按 key（着色器名称）缓存 RuntimeShader 实例，
 * 每帧只 `setFloatUniform` / `setColorUniform`，绝不每帧 new 一个 shader
 * （new 一个 shader 意味着重新编译 AGSL，单次耗时数十毫秒）。
 *
 * 端口自 Kyant0 backdrop 库（Apache-2.0，详见 [com.rickeal.agent.core.design.liquid.Shaders]）。
 */
interface RuntimeShaderCache {

    fun obtainRuntimeShader(key: String, string: String): RuntimeShader
}

internal class RuntimeShaderCacheImpl : RuntimeShaderCache {

    private val runtimeShaders = mutableMapOf<String, RuntimeShader>()

    override fun obtainRuntimeShader(key: String, string: String): RuntimeShader {
        return runtimeShaders.getOrPut(key) { liquidRuntimeShader(string) }
    }

    fun clear() {
        runtimeShaders.clear()
    }
}
