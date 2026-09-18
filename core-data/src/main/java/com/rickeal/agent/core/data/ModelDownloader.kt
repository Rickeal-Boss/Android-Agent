package com.rickeal.agent.core.data

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment

/** 一次下载的瞬时状态。status 取值即 [DownloadManager] 的 STATUS_*。 */
data class DownloadProgress(
    val status: Int,
    val percent: Int,
    val localUri: String? = null,
    val reason: String? = null,
)

/**
 * 模型下载器：基于系统 DownloadManager，因此可以享受系统级断点续传、通知栏进度与后台下载，
 * 且不引入任何第三方依赖（OkHttp 只用于引擎侧的网络请求，不适合 GB 级文件）。
 *
 * 下载落盘到 `getExternalFilesDir(DIRECTORY_DOWNLOADS)`，完成后由上层调用
 * [ModelRepository.importFromPath] 复制进内部 models 目录并登记。
 */
class ModelDownloader(private val context: Context) {

    private val manager: DownloadManager? =
        context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager

    /**
     * 入队一个下载任务，返回 downloadId；失败返回 null。
     * 注意：4B 模型通常 2~4GB，建议只在 Wi-Fi 下调用（UI 层负责提示）。
     */
    fun enqueue(url: String, fileName: String): Long? {
        val dm = manager ?: return null
        val safeName = sanitizeFileName(fileName)
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle(safeName)
            .setDescription("LiquidAgent 模型下载")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, safeName)
            .setMimeType("application/octet-stream")
        return runCatching { dm.enqueue(request) }.getOrNull()
    }

    /** 查询进度。任务不存在时按「已完成」处理，避免上层死等。 */
    fun progress(downloadId: Long): DownloadProgress {
        val dm = manager ?: return DownloadProgress(
            status = DownloadManager.STATUS_FAILED,
            percent = 0,
            reason = "系统下载服务不可用",
        )
        val cursor = dm.query(DownloadManager.Query().setFilterById(downloadId))
            ?: return DownloadProgress(DownloadManager.STATUS_FAILED, 0, reason = "无法查询下载状态")
        return try {
            if (!cursor.moveToFirst()) {
                DownloadProgress(DownloadManager.STATUS_SUCCESSFUL, 100, reason = "任务已不存在")
            } else {
                val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                val done = cursor.getLong(
                    cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR),
                )
                val localUri = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))
                val reasonCode = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                val percent = if (total > 0L) (done * 100L / total).toInt().coerceIn(0, 100) else 0
                DownloadProgress(
                    status = status,
                    percent = percent,
                    localUri = localUri,
                    reason = if (status == DownloadManager.STATUS_FAILED) "下载失败（code=$reasonCode）" else null,
                )
            }
        } catch (t: Throwable) {
            DownloadProgress(DownloadManager.STATUS_FAILED, 0, reason = t.message)
        } finally {
            cursor.close()
        }
    }

    /** 取消并删除下载记录。 */
    fun cancel(downloadId: Long) {
        runCatching { manager?.remove(downloadId) }
    }

    private fun sanitizeFileName(raw: String): String {
        val name = raw.substringAfterLast('/').substringBefore('?').trim()
        val cleaned = name.map { ch ->
            if (ch.isLetterOrDigit() || ch == '.' || ch == '_' || ch == '-') ch else '_'
        }.joinToString("")
        return if (cleaned.isBlank() || cleaned == "." || cleaned == "..") "model.litertlm" else cleaned
    }
}
