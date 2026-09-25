package com.rickeal.agent.core.data

import java.io.FileNotFoundException
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * [classifyImportFailure] 的 JVM 纯函数单测：5 条映射。
 *
 * 刻意**不构造** android.net.Uri —— 本函数本就与 Uri 无关（纯 Throwable → 失败类型），
 * 这样单测不依赖任何 Android 类，也避开仓库守卫对网络栈符号的静态拦截。
 */
class WallpaperFailureTest {

    @Test
    fun `SecurityException 映射为权限失败`() {
        assertTrue(
            classifyImportFailure(SecurityException("denied")) is WallpaperImportFailure.Permission,
        )
    }

    @Test
    fun `FileNotFoundException 映射为相册未找到`() {
        assertTrue(
            classifyImportFailure(FileNotFoundException("no entry")) is WallpaperImportFailure.NotFound,
        )
    }

    @Test
    fun `IOException 映射为存储异常`() {
        assertTrue(
            classifyImportFailure(IOException("io")) is WallpaperImportFailure.Io,
        )
    }

    @Test
    fun `其他异常映射为解码失败`() {
        assertTrue(
            classifyImportFailure(IllegalStateException("boom")) is WallpaperImportFailure.DecodeFailed,
        )
    }

    @Test
    fun `null 映射为解码失败`() {
        assertTrue(classifyImportFailure(null) is WallpaperImportFailure.DecodeFailed)
    }
}
