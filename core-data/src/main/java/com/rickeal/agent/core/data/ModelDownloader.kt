package com.rickeal.agent.core.data

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import java.io.File

/** 一次下载的瞬时状态。status 取值即 [DownloadManager] 的 STATUS_*。 */
data class DownloadProgress(
    val status: Int,
    val percent: Int,
    val localUri: String? = null,
    val reason: String? = null,
    /** 已下载字节数（未知时为 0）。上层用它算下载速率与剩余时间。 */
    val bytesDownloaded: Long = 0L,
    /** 总字节数（DownloadManager 未知时返回 -1，这里归一成 0）。 */
    val totalBytes: Long = 0L,
)

/**
 * 模型下载器：基于系统 DownloadManager，因此可以享受系统级断点续传、通知栏进度与后台下载，
 * 且不引入任何第三方依赖（OkHttp 只用于引擎侧的网络请求，不适合 GB 级文件）。
 *
 * 下载落盘到 `getExternalFilesDir(DIRECTORY_DOWNLOADS)`，**完成后就地把该文件登记为模型**
 * （见 [downloadedPath] + [ModelRepository.importFromPath]）：2~4GB 的模型不再往
 * `filesDir/models` 复制一份，省掉一次完整拷贝的 I/O、耗时与峰值空间。
 * 这与 Google 官方 `google-ai-edge/gallery` 的做法一致（`Model.path` 直接指向下载落盘位置）。
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
                    // DownloadManager 在总大小未知时给 -1，归一成 0 让上层判断更简单
                    bytesDownloaded = done.coerceAtLeast(0L),
                    totalBytes = total.coerceAtLeast(0L),
                )
            }
        } catch (t: Throwable) {
            DownloadProgress(DownloadManager.STATUS_FAILED, 0, reason = t.message)
        } finally {
            cursor.close()
        }
    }

    /**
     * 解析本次下载最终落盘的**绝对路径**（下载完成后调用），供上层就地登记。
     *
     * 为什么不直接用 DownloadManager 的 `COLUMN_LOCAL_URI`：
     *  - 多数机型/版本它给的是 `file://`，取 path 即可；
     *  - 少数版本会返回 `content://`（那样就只能走 SAF 复制，白白多拷一份 2~4GB）；
     *  - DownloadManager 遇到同名文件会把落盘名改成 `name-1.ext`，入队时的名字会失配。
     * 所以顺序是：`file://` 路径 → 入队时确定的落盘文件 → 同目录里「去掉 -N 后缀同名」的最新文件。
     *
     * @return 真实存在的绝对路径；null 表示确实解析不出来（调用方再退回复制导入）。
     */
    fun downloadedPath(localUri: String?, fileName: String?): String? {
        val fromUri = localUri
            ?.takeIf { it.startsWith("file://", ignoreCase = true) }
            ?.let { runCatching { Uri.parse(it).path }.getOrNull() }
        if (fromUri != null && File(fromUri).isFile) return fromUri

        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return null
        val name = fileName?.let { sanitizeFileName(it) } ?: return null
        val exact = File(dir, name)
        if (exact.isFile) return exact.absolutePath
        // 同名文件已存在时 DownloadManager 会落成 "name-1.ext"，按「去掉 -N 的基名」找最新的那个
        return dir.listFiles()
            ?.filter { it.isFile && stripDuplicateSuffix(it.name) == name }
            ?.maxByOrNull { it.lastModified() }
            ?.absolutePath
    }

    /** `model.litertlm` 与 `model-1.litertlm` 都归一成 `model.litertlm`（DownloadManager 的重名规则）。 */
    private fun stripDuplicateSuffix(name: String): String {
        val match = Regex("^(.*)-\\d+(\\.[^.]+)$").find(name) ?: return name
        return match.groupValues[1] + match.groupValues[2]
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
