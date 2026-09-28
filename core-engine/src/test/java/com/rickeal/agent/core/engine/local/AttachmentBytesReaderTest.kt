package com.rickeal.agent.core.engine.local

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [AttachmentBytesReader] 字节读取边界的 JVM 单测（Wave 31 · 流 4）。
 *
 * 覆盖面刻意收窄到**不触碰 android.graphics 的分支**：[audioBytes] 全程只用
 * `java.io.File`（[imagePngBytes] 走 BitmapFactory 解码，纯 JVM 无法执行 ——
 * 它已由真机/引擎链路验证，这里不硬造 Bitmap mock）。
 *
 * 为什么值得写：这三条边界（空 uri / 文件不存在 / file:// 前缀）都是「错了不报错、
 * 只静默把附件丢掉」的类型 —— 表现是「图片/音频发出去了但模型收不到」，且无任何异常。
 */
class AttachmentBytesReaderTest {

    @Test
    fun `空 uri 返回 null`() {
        assertNull(AttachmentBytesReader.audioBytes(""))
        assertNull(AttachmentBytesReader.audioBytes("   "))
    }

    @Test
    fun `不存在的文件返回 null`() {
        val missing = File(System.getProperty("java.io.tmpdir"), "cam-p-missing-${System.nanoTime()}.wav")
        assertNull(AttachmentBytesReader.audioBytes(missing.absolutePath))
    }

    @Test
    fun `读取已存在的文件字节`() {
        val file = File.createTempFile("cam-p-audio", ".wav")
        try {
            val payload = byteArrayOf(1, 2, 3, 4, 5)
            file.writeBytes(payload)
            val bytes = AttachmentBytesReader.audioBytes(file.absolutePath)
            assertEquals(payload.toList(), bytes?.toList())
        } finally {
            file.delete()
        }
    }

    @Test
    fun `file 协议前缀被剥离`() {
        val file = File.createTempFile("cam-p-audio", ".wav")
        try {
            val payload = byteArrayOf(9, 8, 7)
            file.writeBytes(payload)
            val bytes = AttachmentBytesReader.audioBytes("file://${file.absolutePath}")
            assertEquals(payload.toList(), bytes?.toList())
        } finally {
            file.delete()
        }
    }
}
