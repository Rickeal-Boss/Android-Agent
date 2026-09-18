package com.rickeal.agent.core.engine.local

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 把 uri / 绝对路径 读成引擎需要的字节。
 * 图片统一转 PNG（Content.ImageBytes 要求 PNG）；音频直接给原始字节（调用方保证 16kHz mono WAV）。
 *
 * 注意：本文件里的所有调用都是阻塞 IO，必须跑在 Dispatchers.IO 上（调用方 LiteRtLmEngine 已保证）。
 */
object AttachmentBytesReader {

    fun imagePngBytes(uri: String): ByteArray? {
        if (uri.isBlank()) return null
        return try {
            val file = File(stripScheme(uri))
            if (!file.exists()) return null
            val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return null
            val scaled = downscale(bitmap, 1024L * 1024L)
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.PNG, 90, out)
            if (scaled !== bitmap) scaled.recycle()
            bitmap.recycle()
            out.toByteArray()
        } catch (t: Throwable) {
            null
        }
    }

    fun audioBytes(uri: String): ByteArray? {
        if (uri.isBlank()) return null
        return try {
            val file = File(stripScheme(uri))
            if (!file.exists()) return null
            file.readBytes()
        } catch (t: Throwable) {
            null
        }
    }

    private fun stripScheme(uri: String): String =
        if (uri.startsWith("file://")) uri.removePrefix("file://") else uri

    /** 简单按像素总量等比缩小，避免 4000x3000 的原图把 4B 模型的显存打爆。 */
    private fun downscale(bitmap: Bitmap, maxPixels: Long): Bitmap {
        val pixels = bitmap.width.toLong() * bitmap.height.toLong()
        if (pixels <= maxPixels) return bitmap
        val ratio = kotlin.math.sqrt(maxPixels.toDouble() / pixels.toDouble())
        val w = (bitmap.width * ratio).toInt().coerceAtLeast(1)
        val h = (bitmap.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, w, h, true)
    }
}
