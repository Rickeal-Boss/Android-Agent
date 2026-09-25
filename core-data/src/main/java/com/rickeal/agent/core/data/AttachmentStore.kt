package com.rickeal.agent.core.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** 拷贝附件用的缓冲大小（1MB）：默认 8KB 拷几十 MB 的图片/音频要走上万次循环。 */
private const val COPY_BUFFER_BYTES = 1024 * 1024

/**
 * 附件存储器。
 *
 * 背景：用户通过系统文件选择器（SAF）选中的是 `content://...` Uri，**不是文件路径**。
 * 而引擎侧（本地 AttachmentBytesReader / 远程 imageToDataUri）都是按文件路径读取字节的，
 * 直接把 content:// 存进 Attachment 会导致图片、音频 100% 读取失败。
 *
 * 因此：附件在「选中时」就复制进内部目录 `filesDir/attachments/`，
 * 领域模型里存的是**真实文件路径**，两个引擎都无需感知 Uri 的存在。
 */
class AttachmentStore(private val context: Context) {

    /**
     * 附件目录（`filesDir/attachments`）。
     *
     * 对 [AppContainer] 暴露为**附件目录的唯一事实来源**：此前 `filesDir/attachments`
     * 这个字面量在本类与 [AppContainer.importAttachment] 各拼了一次（overview.md DAT-B3
     * 记为两处几乎逐行相同的重复实现），现在两边都从这里取。
     */
    val directory: File = File(context.filesDir, "attachments").apply { mkdirs() }

    /**
     * 把 Uri 复制进内部目录，返回可直接当文件路径使用的绝对路径；失败返回 null。
     * 若传入的已经是本地文件路径，则原样返回（不重复拷贝）。
     *
     * **必须是 suspend + Dispatchers.IO**：这里是几十 MB 的阻塞拷贝，
     * 调用方是 `viewModelScope`（默认主线程），同步版本会把"选张图"变成 ANR。
     */
    suspend fun copyToInternal(uriString: String, displayName: String): String? =
        withContext(Dispatchers.IO) {
            if (uriString.isBlank()) return@withContext null
            // 已经是真实路径（内部文件 / 用户自己提供的绝对路径）
            if (!uriString.startsWith("content://", ignoreCase = true)) {
                val maybeFile = File(uriString.removePrefix("file://"))
                if (maybeFile.exists() && maybeFile.isFile) return@withContext maybeFile.absolutePath
            }
            runCatching {
                val uri = Uri.parse(uriString)
                val safeName = sanitize(displayName)
                val target = uniqueFile(safeName)
                val input = context.contentResolver.openInputStream(uri)
                    ?: return@runCatching null
                input.use { source ->
                    target.outputStream().use { output -> source.copyTo(output, COPY_BUFFER_BYTES) }
                }
                if (!target.exists() || target.length() <= 0L) return@runCatching null
                target.absolutePath
            }.getOrNull()
        }

    private fun sanitize(raw: String): String {
        val base = raw.substringAfterLast('/').substringBefore('?').trim()
        val cleaned = base.map { ch ->
            if (ch.isLetterOrDigit() || ch == '.' || ch == '_' || ch == '-') ch else '_'
        }.joinToString("")
        return if (cleaned.isBlank() || cleaned == "." || cleaned == "..") {
            "attachment_${System.currentTimeMillis()}"
        } else {
            cleaned
        }
    }

    private fun uniqueFile(name: String): File {
        var candidate = File(directory, name)
        if (!candidate.exists()) return candidate
        val dot = name.lastIndexOf('.')
        val prefix = if (dot > 0) name.substring(0, dot) else name
        val suffix = if (dot > 0) name.substring(dot) else ""
        var index = 1
        while (candidate.exists() && index < 1000) {
            candidate = File(directory, "${prefix}_$index$suffix")
            index++
        }
        return candidate
    }
}
