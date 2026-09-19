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

    /** 降采样目标最长边。与远程引擎 `OpenAiCompatibleEngine.imageToDataUri()` 保持同一口径。 */
    private const val MAX_EDGE = 1024

    fun imagePngBytes(uri: String): ByteArray? {
        if (uri.isBlank()) return null
        return try {
            val path = stripScheme(uri)
            val file = File(path)
            if (!file.exists()) return null
            // 必须先解边界算采样率再解码，绝不能先全量解码再缩。
            // 手机原图 4000x3000 全量解码就是 48MB 的 Bitmap 分配，叠加 4B 模型已经占掉的
            // 2~3GB，峰值再乘上下面 compress() 的 ByteArrayOutputStream 翻倍扩容 —— 直接 OOM。
            // 远程引擎做了这一步，本地引擎没做（两条路径不对称），所以一直没被发现。
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            val maxDim = maxOf(bounds.outWidth, bounds.outHeight)
            val sample = if (maxDim > MAX_EDGE) {
                var s = 1
                while (maxDim / (s * 2) >= MAX_EDGE) s *= 2
                s
            } else {
                1
            }
            val bitmap = BitmapFactory.decodeFile(
                path,
                BitmapFactory.Options().apply { inSampleSize = sample },
            ) ?: return null
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
