package com.rickeal.agent.core.data

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import java.io.File

/**
 * 下载中的临时后缀。
 *
 * DownloadManager 的落盘名在**入队时固定**，半截文件与完整文件同名，扫描侧无法按名字区分；
 * 与其加 sidecar 标记文件（多一个状态就多一类 bug），不如让 DM 直接下到 `<name>.part`：
 * 带这个后缀的文件不命中 MODEL_EXTENSIONS（见 ModelRepository），因此**永远不会被扫描成一个能加载的模型**，
 * 下载完成后由 [ModelDownloader.downloadedPath] rename 回真名。
 */
private const val PART_SUFFIX = ".part"

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
    /**
     * 该状态来自 **cursor 的真实行**，而不是「任务已不存在」时的推测。
     *
     * `.part` 转正闸门必须用它：只有 cursor 真报了成功，才证明文件是完整的。
     * 带默认值是为了让上层现有读取编译不变；调用点应显式传 `allowPromote = progress.fromCursor`。
     */
    val fromCursor: Boolean = false,
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
     * 已被 **cursor 证实下载成功**的文件名集合（进程内有效）。
     *
     * `.part` 转正闸门的依据，见 [downloadedPath] 的调用不变式。
     * 只记文件名、不记 downloadId，是因为 [downloadedPath] 的入参只有文件名。
     * 进程重启后这份记忆会丢失——但那时上层的 `activeDownloadId` 也没了，本来就要重新下载。
     */
    private val confirmedNames: MutableSet<String> =
        java.util.Collections.synchronizedSet(mutableSetOf())

    /**
     * 入队一个下载任务，返回 downloadId；失败返回 null。
     * 注意：4B 模型通常 2~4GB，建议只在 Wi-Fi 下调用（UI 层负责提示）。
     */
    fun enqueue(url: String, fileName: String): Long? {
        val dm = manager ?: return null
        val safeName = sanitizeFileName(fileName)
        // 清掉上次中断遗留的 *.part：它们按定义都是不完整的。留着会让 DownloadManager
        // 因目标名被占用而落成 "name-1.ext.part"，导致"哪个 .part 属于本次下载"变得不确定。
        deletePartFiles(safeName)
        // 同时忘掉这个名字上一次的成功记录：它是"上一次下载"的证据，不能拿来给本次下载背书。
        // 否则会留一条陈旧条目 —— 本次下载中途被用户删掉 DM 任务、磁盘留半截 .part 时，
        // 那次陈旧的"曾成功过"会让半截文件被转正（详见 downloadedPath 的 KDoc）。
        confirmedNames.remove(safeName)
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle(safeName)
            .setDescription("LiquidAgent 模型下载")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            // 注意这里故意下到 "<name>.part"：理由见 [PART_SUFFIX]。
            .setDestinationInExternalFilesDir(
                context,
                Environment.DIRECTORY_DOWNLOADS,
                safeName + PART_SUFFIX,
            )
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
                if (status == DownloadManager.STATUS_SUCCESSFUL) {
                    // 记下"这个文件被 cursor 证实下完了"，作为 .part 转正闸门的依据。
                    // 不能只靠 localUri：本文件 KDoc 自己就写了 COLUMN_LOCAL_URI 在部分版本不可靠
                    // （可能返回 content://、甚至 null），拿它当判据会让那些机型永远转不了正。
                    val title = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE))
                    if (!title.isNullOrBlank()) confirmedNames.add(sanitizeFileName(title))
                }
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
                    fromCursor = true,
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
     * 另外：入队时故意让 DM 下到 `<name>.part`（理由见 [PART_SUFFIX]），
     * 所以本函数在拿到成功证据时**顺带把 .part 转正**（[promotePartFile]）——放在这里做，
     * 是因为"落盘目录 + .part 约定"是本类的知识，上层不该知道文件名细节。
     * 转正有闸门，见下面的「调用不变式」。
     *
     * 顺序是：`file://` 路径（非 .part）→ 本次 rename 出来的文件 → `dir/<name>` → 同目录里「去掉 -N 后缀同名」的最新文件。
     *
     * ## 调用不变式（务必遵守）
     *
     * **只允许在「确认下载成功」之后调用**（即 cursor 真的报了 `STATUS_SUCCESSFUL`）。
     * 理由：`.part` 转正是一次**提交动作**，若由一个"不知道有没有下完"的分支驱动，
     * 就会把半截文件转正并登记成模型（用户点加载 → native 崩溃 = 闪退）。
     * 特别地，**不要在每秒轮询里调用它**——那会把下载中的 `.part` 当场转正。
     *
     * ## 转正判据（代码实际执行的，别照着更严格的说法理解）
     *
     * `allowPromote && (localUri != null || 文件名 ∈ [confirmedNames])`，见 [isConfirmedComplete]。
     *  - `confirmedNames` 只在 [progress] **从 cursor 真实行**读到 `STATUS_SUCCESSFUL` 时加入，
     *    并在 [enqueue] 里 `remove` —— 它是"**本次**下载"的证据，**不跨下载复用**；
     *  - 因此 [progress] 的「任务已不存在」分支（恒 `localUri = null`、且从不入集合），
     *    配合调用方传入的 `fromCursor = false`，**不会**触发转正；
     *  - 反之，成功下载即便 `COLUMN_LOCAL_URI` 为 null（部分机型/版本如此，见本文件 KDoc），
     *    也能靠 `confirmedNames` 正常转正，不会白下几 GB。
     *
     * **为什么 `confirmedNames` 必须带"本次"语义**：它按文件名累积，若不在 [enqueue] 里清除，
     * 「上一次同名下载成功过」的陈旧记录会给「本次下载中途被删任务、残留在磁盘的半截 `.part`」背书，
     * 把半截文件转正并登记成模型（用户点加载 → native 崩溃 = 闪退）。
     *
     * 调用方**必须**显式传 `allowPromote = progress.fromCursor`。默认 `false` 是 **fail-safe**：
     * 不传参即不允许转正（本函数退化为纯解析），绝不"自己猜"。
     *
     * @param allowPromote 是否允许把 `.part` 转正。
     * @return 真实存在的绝对路径；null 表示确实解析不出来（调用方再退回复制导入）。
     */
    fun downloadedPath(
        localUri: String?,
        fileName: String?,
        // 默认 false = fail-safe：不显式声明"我有成功证据"就一律不转正。
        // 曾经默认 true（由本函数自己判断），但那让任何不传参的调用方都暴露在
        // 「陈旧成功记录 → 半截 .part 被转正」这条路径上；真正的证据只能来自调用方。
        allowPromote: Boolean = false,
    ): String? {
        val fromUri = localUri
            ?.takeIf { it.startsWith("file://", ignoreCase = true) }
            ?.let { runCatching { Uri.parse(it).path }.getOrNull() }
        // 绝不允许把 .part 路径交出去：那会登记成一个扫不到、加载必崩的模型条目
        if (fromUri != null && File(fromUri).isFile && !fromUri.endsWith(PART_SUFFIX)) return fromUri

        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return null
        val name = fileName?.let { sanitizeFileName(it) } ?: return null

        // 下载完成那一刻把 "<name>.part" 转正。放在这一层（而不是上层回调里）是因为
        // 「落盘目录 + .part 约定」是本类的知识，上层只该拿到真名路径。
        //
        // 关键闸门：只有拿到「这个文件确实下完了」的证据才允许转正，见 [isConfirmedComplete]。
        // 少了这道闸门，用户删掉 DM 记录 + 磁盘留着半截 .part ⇒ 半截文件被"转正"并登记，
        // 用户一点加载就在 native 层崩溃（表现为闪退）。
        // （预期大小比对在这里救不了：那一分支的 totalBytes 恒为 0，上层校验会静默放行。）
        val promoted = if (allowPromote && isConfirmedComplete(name, localUri)) {
            promotePartFile(dir, name)
        } else {
            null
        }

        // 先认本次 rename 出来的文件：真名被占用时 renameToUnique 会落到 "name-1.ext"，
        // 此时若先去匹配 dir/name，会把**另一个同名旧文件**当成本次下载登记。
        if (promoted != null && File(promoted).isFile) return promoted
        val exact = File(dir, name)
        if (exact.isFile) return exact.absolutePath
        // 同名文件已存在时 DownloadManager 会落成 "name-1.ext"，按「去掉 -N 的基名」找最新的那个。
        // 这一路同时服务「DM 记录已被用户删除、但文件已下完」的恢复场景：那时磁盘上的完整文件
        // 已经是真名（rename 早已发生），半截文件则永远是 .part，被下面这行天然排除。
        return dir.listFiles()
            ?.filter { it.isFile && !it.name.endsWith(PART_SUFFIX) && stripDuplicateSuffix(it.name) == name }
            ?.maxByOrNull { it.lastModified() }
            ?.absolutePath
    }

    /**
     * 是否拿到了「这个文件确实下完了」的证据 —— 两条任一成立即可：
     *
     * 1. [confirmedNames] 里有它（[progress] 从 cursor 真实行读到过 `STATUS_SUCCESSFUL`）；
     * 2. `localUri` 非 null（它只可能来自 cursor 那一分支）。
     *
     * 之所以不能只看 `localUri`：**`COLUMN_LOCAL_URI` 在部分机型/版本不可靠**
     * （本文件 KDoc 就写了它可能返回 `content://`），若它为 null 就拒绝转正，
     * 那些机型上成功下载会永远停在 `.part`，而 `exact` / `-N` 兜底都排除 `.part` ⇒ 白下几 GB。
     * 「任务已不存在」分支两条证据都不成立，因此不会误转正。
     */
    private fun isConfirmedComplete(name: String, localUri: String?): Boolean =
        localUri != null || confirmedNames.contains(name)

    /**
     * 把下载落盘的 "<name>.part" rename 回真名，返回最终路径；没有 .part 或 rename 失败返回 null。
     *
     * 只在状态已是 STATUS_SUCCESSFUL 之后才被调用（即 [downloadedPath] 的调用点），
     * 所以此刻 .part 里的内容是完整的——这正是下载期间不让它用真名的原因。
     */
    private fun promotePartFile(dir: File, name: String): String? {
        val part = File(dir, name + PART_SUFFIX)
        val source = if (part.isFile) {
            part
        } else {
            // DownloadManager 遇到同名落盘文件会自己加 "-N"，所以也可能是 "name-1.ext.part"
            dir.listFiles()
                ?.filter {
                    it.isFile &&
                        it.name.endsWith(PART_SUFFIX) &&
                        stripDuplicateSuffix(it.name.removeSuffix(PART_SUFFIX)) == name
                }
                ?.maxByOrNull { it.lastModified() }
                ?: return null
        }
        return renameToUnique(source, dir, name)
    }

    /** rename 到一个不冲突的目标名（"name.ext" 被占用就依次试 "name-1.ext"…）。 */
    private fun renameToUnique(source: File, dir: File, name: String): String? {
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var target = File(dir, name)
        var index = 1
        while (target.exists()) {
            target = File(dir, "$base-$index$ext")
            index++
        }
        return if (source.renameTo(target)) target.absolutePath else null
    }

    /** 清掉某个名字遗留的所有 *.part —— 它们按定义都是不完整的半成品。 */
    private fun deletePartFiles(name: String) {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return
        dir.listFiles()?.forEach { file ->
            if (!file.isFile || !file.name.endsWith(PART_SUFFIX)) return@forEach
            if (stripDuplicateSuffix(file.name.removeSuffix(PART_SUFFIX)) == name) {
                runCatching { file.delete() }
            }
        }
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
