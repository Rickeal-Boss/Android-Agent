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

    /**
     * **调用契约**：调用方必须先判 [LiquidGlassCapabilities.hasRuntimeShader]（或
     * [isRuntimeShaderSupported]）再调用 —— [liquidRuntimeShader] 需要 API 33，而 minSdk 31。
     *
     * 此处不静默降级：降级会在渲染层产生「无折射」的静默差异，用户只看到「效果不对」
     * 却拿不到任何信号。lint 看不到跨文件调用点的版本守卫，故显式抑制并在此声明契约。
     */
    @Suppress("NewApi")
    override fun obtainRuntimeShader(key: String, string: String): RuntimeShader {
        return runtimeShaders.getOrPut(key) { liquidRuntimeShader(string) }
    }

    fun clear() {
        runtimeShaders.clear()
    }
}
