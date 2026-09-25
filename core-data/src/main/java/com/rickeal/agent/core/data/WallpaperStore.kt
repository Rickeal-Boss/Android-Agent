package com.rickeal.agent.core.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.rickeal.agent.core.model.AgentLogStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream

/**
 * 自定义壁纸的导入 / 解码 / 删除（Wave 9 需求 3b）。
 *
 * ## 设计要点
 *
 * - **唯一文件名落盘**（Wave 9 审查修正，原名"固定文件名"）：`filesDir/wallpaper/wallpaper_<时间戳>.jpg`。
 *   DataStore 里只存**相对路径**（不存绝对路径 —— filesDir 随设备迁移 / 备份恢复会变，
 *   绝对路径换台设备就成死链）。⚠️ 不能用固定名覆盖写：覆盖后 DataStore 里的路径
 *   **不变**，而「路径」是显示链的唯一刷新信号（collectAsState → LaunchedEffect(path)），
 *   同值写入不会重启解码 —— 换壁纸将永远显示旧图。唯一文件名让每次导入都产生新路径，
 *   刷新链自动失效重建；旧文件在导入成功后删除，不产生垃圾。
 * - **导入即降采样**：相机原图动辄 1200 万像素（几十 MB），壁纸永远铺满全屏，
 *   超过屏幕最长边（上限 2048px，照顾平板 / 分屏多窗口）的像素全是浪费 ——
 *   直接塞给玻璃层做 `drawImage` 等于每帧都在吞吐一张巨大纹理。两段式
 *   `inJustDecodeBounds` → `inSampleSize` 是官方口径，内存峰值只有目标尺寸量级。
 *   ⚠️ 判据必须是「**最长边** > 目标就再砍半」（见 [sampleSizeFor]）：按"两维都 ≥
 *   目标才砍"的口径，常见宽高比（短边 < 目标）会系统性少砍一档，12MP 照片会按
 *   全尺寸解码（≈48MB ARGB_8888）并全尺寸落盘、全尺寸常驻显示。
 * - **零新权限、不裁剪**：Android 13+ 的 Photo Picker 在**系统进程**里运行，
 *   应用拿不到照片真实路径、不需要 `READ_MEDIA_IMAGES`，只有一个一次性的
 *   `content://` Uri；不做裁剪让系统把压缩与降采样后的成品交给用户即可。
 * - **IO 边界**：`import` 自带 `Dispatchers.IO`；`decode` 是同步函数（显示路径
 *   只在路径变化时调用一次，由调用方包 IO，见 LiquidAgentApp 的 LaunchedEffect）。
 * - **失败可诊断**：`import` 失败时返回的是 [WallpaperImportFailure] 的具体子类
 *   （而不是一句笼统的"无法读取所选图片"），其 `message` 可直接上屏；同时把
 *   **最小化的**诊断信息写进 [AgentLogStore]（见 [logImportFailure]）。
 *
 * 先例：[AppContainer.importAttachment] 同样把 content:// 一次性拷进内部目录 ——
 * Uri 不是长期有效的存储凭据，落盘成本必须发生在选中的那一刻。
 */
class WallpaperStore(private val context: Context) {

    private val wallpaperDir: File get() = File(context.filesDir, "wallpaper")

    /**
     * 从 Photo Picker 的 Uri 导入壁纸：一次性读取字节 → 两遍解码（只读边界 + 降采样）
     * → JPEG 85 压缩 → 落盘到**唯一**文件名。成功返回新文件的相对路径；失败返回
     * 带**可诊断原因**的 [Result.failure]（[WallpaperImportFailure] 各子类，`message`
     * 可直接上屏）。失败时旧壁纸文件原样保留。
     *
     * ## ⚠️ 真根因（Wave 10 修复：改动前本函数 100% 必挂）
     *
     * 旧实现写的是：
     * ```
     * context.contentResolver.openInputStream(uri)?.use { input ->
     *     BitmapFactory.decodeStream(input, null, bounds)   // inJustDecodeBounds = true
     * } ?: error("无法读取所选图片")
     * ```
     * `?:` 的左操作数是 **`?.use { }` 的返回值**，即 lambda 的最后一个表达式
     * `BitmapFactory.decodeStream(...)`。而 Android 官方对 `inJustDecodeBounds` 的语义
     * 是明确的：*"If set to true, the decoder will return null (no bitmap), but the
     * out... fields will still be set"* —— **返回 null 是设计如此**。于是：
     *   · `openInputStream` 返回 null → 整条表达式为 null → 命中 `?:`；
     *   · `openInputStream` 返回非 null → `use` 返回 `decodeStream` 的 null → 同样命中 `?:`。
     * **两条分支都抛错**，`import()` 从未成功执行过一次（与权限、OEM、Uri 授权
     * 生命周期全无关）。因此这里刻意**只读边界那一遍不判 `decodeStream` 的返回值**，
     * 改判 `bounds.outWidth / outHeight > 0`；"开流失败"与"解码结果"两件事彻底拆开。
     * ⚠️ 不要再把它"优化"回单表达式 elvis —— 那正是本函数此前 100% 必挂的原因。
     *
     * ## 一次读取、两次解码（不再二次开流）
     *
     * 旧实现两遍解码各自 `openInputStream(uri)` 一次 —— 部分 ROM 的相册 provider
     * 对同一 Uri 的第二次开流会失败（一次性授权 / provider 状态机），是真实存在的
     * 失败面。现在先 [readSourceBytes] 把源字节读进内存（含 64MB 上限保护），
     * 两遍解码都从**同一份字节**走 `ByteArrayInputStream`，不再第二次调用 ContentResolver。
     *
     * @param previousPath 当前 DataStore 里存的旧相对路径（调用方从
     *   [SettingsRepository.wallpaperPath] 取）。导入**成功后**删除旧文件 ——
     *   顺序不能反：先删后写，失败窗口里用户会失去正在用的壁纸。
     */
    suspend fun import(uri: Uri, previousPath: String? = null): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                // 1) 一次性把源字节读进内存（含上限保护）——两遍解码共用，杜绝二次开流。
                val bytes = readSourceBytes(uri)
                    ?: throw WallpaperImportFailure.StreamUnavailable()

                // 2) 只读边界，不解码像素。⚠️ 这里刻意**不判** decodeStream 的返回值
                //    （inJustDecodeBounds=true 时它按设计返回 null），只认 out* 是否被填上。
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeStream(ByteArrayInputStream(bytes), null, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                    throw WallpaperImportFailure.NotAnImage()
                }

                // 3) inSampleSize：目标边长 = min(2048, 屏幕最长边)。
                //    2048 是给平板 / 分屏多窗口留的余量，手机上通常取屏幕最长边本身。
                val screenLongest = maxOf(
                    context.resources.displayMetrics.widthPixels,
                    context.resources.displayMetrics.heightPixels,
                )
                val targetPx = minOf(2048, screenLongest)
                val options = BitmapFactory.Options().apply {
                    inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, targetPx)
                }
                val bitmap = BitmapFactory.decodeStream(ByteArrayInputStream(bytes), null, options)
                    ?: throw WallpaperImportFailure.DecodeFailed()

                // 4) JPEG 85：视觉上与原图几乎无差，体积约为 PNG 的 1/10 ——
                //    这张图会被玻璃节点反复采样，文件大小直接影响冷启动解码耗时。
                // 5) 唯一文件名：保证「导入成功 ⇒ DataStore 路径变化 ⇒ 显示链重新解码」。
                wallpaperDir.mkdirs()
                val target = File(wallpaperDir, "wallpaper_${System.currentTimeMillis()}.jpg")
                try {
                    target.outputStream().use { output ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    // ⚠️ 写盘段失败（磁盘满 / 目录不可写）必须**单独归类**：此处的
                    // FileNotFoundException 含义是"写不了"，不是"相册里没有这张图" ——
                    // 若丢给 [classifyImportFailure]（它只认读源段语义），会被误导成
                    // 「系统相册没有找到这张图片」。
                    throw WallpaperImportFailure.WriteFailed(e)
                }
                bitmap.recycle()
                // 成功后才删旧文件；新文件与旧文件同在本类签发的相对路径下。
                previousPath?.takeIf { it.isNotBlank() }
                    ?.let { File(context.filesDir, it).delete() }
                Result.success("wallpaper/${target.name}")
            } catch (e: CancellationException) {
                // ⚠️ 协程取消必须**原样重抛**：下面的 catch(Throwable) 会吞掉它，把
                // 「页面销毁 / 导入被取消」伪装成一次"导入失败"，并让调用方的取消语义失真。
                // 顺序固定：CancellationException → WallpaperImportFailure → Throwable。
                throw e
            } catch (failure: WallpaperImportFailure) {
                // 已分类的失败（开流不可用 / 非图片 / 解码失败 / 体积超限 / 写盘失败）：原样透传。
                logImportFailure(uri, failure)
                Result.failure(failure)
            } catch (cause: Throwable) {
                // ContentResolver 抛出的原始异常（Security / FileNotFound / IOException 等）：
                // 映射成可上屏的失败类型再透传。
                val classified = classifyImportFailure(cause)
                logImportFailure(uri, classified)
                Result.failure(classified)
            }
        }

    /**
     * 一次性把 Uri 指向的源字节读进内存。
     *
     * 两级取流，L1 返回 null（provider 未提供流）才走 L2 —— 部分 ROM 相册 provider
     * 只支持其中一条：
     *  - L1：`openInputStream`（标准路径，绝大多数 provider 走这条）
     *  - L2：`openFileDescriptor("r")` + `FileInputStream`（L1 拿不到流时的兜底）
     *
     * 上限 [MAX_SOURCE_BYTES]：超限**立即停止读取**并抛 [WallpaperImportFailure.TooLarge]，
     * 绝不 `readBytes()` 无上限读（一张被改名的超大文件能把进程 OOM 掉）。
     *
     * 本函数**不做异常分类**：ContentResolver 抛出的原始异常直接向上抛，由 [import]
     * 的 catch 统一交给 [classifyImportFailure]（这样 [classifyImportFailure] 保持
     * "只认原始异常类型"的纯函数语义）。
     *
     * @return 源字节；两条路径都拿不到流时返回 null（由调用方归类为
     *   [WallpaperImportFailure.StreamUnavailable]）。
     * @throws WallpaperImportFailure.TooLarge 累计字节超过上限。
     */
    private fun readSourceBytes(uri: Uri): ByteArray? {
        // L1：标准开流路径。
        val direct = context.contentResolver.openInputStream(uri)
            ?.use { readCapped(it, MAX_SOURCE_BYTES) }
        if (direct != null) return direct
        // L2：L1 没给流（不是"开流抛异常"——那种情况已在上面向上抛出）。记一条 WARN
        // 便于下次真机拿到"哪个 provider 需要 L2"。sink 只转发 ERROR，故不会落盘。
        AgentLogStore.warn("壁纸导入：L1 openInputStream 不可用，已回退 openFileDescriptor")
        return context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            FileInputStream(pfd.fileDescriptor).use { readCapped(it, MAX_SOURCE_BYTES) }
        }
    }

    /**
     * 失败时落一条诊断日志。
     *
     * **只记可诊断的最小信息**：`uri.scheme` + `uri.authority` + 路径段数 + 异常类名。
     * **绝不记完整 uri / query**（照片 Uri 里带媒体库 id，属于隐私）；**也刻意不记
     * `cause.message`** —— `FileNotFoundException` 的 message 在真机上常含完整
     * `content://...` URI，而这条日志会随备份 / 文件分享离开设备。结构化字段
     * （provider authority + 异常类名）已足够定位"是哪个 provider、哪类失败"。
     * 包 `runCatching`：这是错误路径，日志设施绝不能反过来把原始错误顶掉。
     */
    private fun logImportFailure(uri: Uri, cause: Throwable) {
        runCatching {
            AgentLogStore.error(
                "壁纸导入失败：scheme=${uri.scheme} authority=${uri.authority} " +
                    "pathSegments=${uri.pathSegments?.size ?: 0} " +
                    "cause=${cause.javaClass.simpleName}",
            )
        }
    }

    /**
     * 解码已导入的壁纸。文件不存在 / 已损坏返回 null（调用方回退程序化壁纸）。
     *
     * @param targetPx 期望的边长上限（px）。小于图片实际边长时按 [sampleSizeFor] 降采样；
     *   默认 [Int.MAX_VALUE] = 原样解码（导入时已降过采样，显示路径无需再降）。
     */
    fun decode(relativePath: String, targetPx: Int = Int.MAX_VALUE): Bitmap? {
        if (relativePath.isBlank()) return null
        val file = File(context.filesDir, relativePath)
        if (!file.exists()) return null
        return runCatching {
            if (targetPx == Int.MAX_VALUE) {
                BitmapFactory.decodeFile(file.absolutePath)
            } else {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, bounds)
                BitmapFactory.decodeFile(
                    file.absolutePath,
                    BitmapFactory.Options().apply {
                        inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, targetPx)
                    },
                )
            }
        }.getOrNull()
    }

    /**
     * 删除指定相对路径的壁纸文件。路径为空 / 文件不存在时是 no-op。
     * DataStore 路径由调用方清空（先删文件后清路径，反了会留孤儿文件）。
     */
    fun delete(relativePath: String) {
        if (relativePath.isBlank()) return
        File(context.filesDir, relativePath).delete()
    }

    private companion object {
        const val JPEG_QUALITY = 85

        /** 源图字节上限（64MB）：远超任何手机壁纸的合理体积，同时把 OOM 面关掉。 */
        const val MAX_SOURCE_BYTES = 64 * 1024 * 1024
    }
}

/** 读取缓冲块大小（64KB）。文件级：供 [readCapped] 与单测共享。 */
private const val READ_BUFFER_BYTES = 64 * 1024

/**
 * 按 [READ_BUFFER_BYTES] 缓冲循环读，累计超过 [maxBytes] 立即停止并抛
 * [WallpaperImportFailure.TooLarge]。
 *
 * 提升为**文件级 `internal` 纯函数**（只依赖 `java.io`，不碰 Android）：这样 JVM 单测
 * 可以用**小上限**直接覆盖"恰好等于上限不抛 / 超一字节抛"的边界，不必真读 64MB。
 * 生产调用点用 `MAX_SOURCE_BYTES`（64MB）。
 */
internal fun readCapped(input: InputStream, maxBytes: Int): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(READ_BUFFER_BYTES)
    var total = 0
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        total += read
        if (total > maxBytes) throw WallpaperImportFailure.TooLarge()
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}

/**
 * 壁纸导入的失败分类。
 *
 * 为什么需要它：旧实现把所有失败都压成一句 `error("无法读取所选图片")` —— 用户拿不到
 * 任何可行动信息，我们（远程）也分不清是"权限没了""provider 不给流"还是"这不是图片"。
 * 每个子类的 `message` 都写成**面向用户、可直接上屏**的中文，调用方
 * （[com.rickeal.agent.feature.settings.SettingsViewModel]）用 `it.message` 展示即可。
 *
 * 纯 Kotlin（不引用任何 android 类）—— 便于 JVM 单测直接覆盖 [classifyImportFailure]。
 */
sealed class WallpaperImportFailure(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class StreamUnavailable : WallpaperImportFailure("系统未能提供这张图片的数据（部分机型相册存在此问题），请换一张或改用其他方式选择")
    class NotAnImage : WallpaperImportFailure("所选文件不是有效图片")
    class DecodeFailed : WallpaperImportFailure("无法解码所选图片")
    class TooLarge : WallpaperImportFailure("图片体积过大，无法导入")
    class WriteFailed(cause: Throwable) : WallpaperImportFailure("无法保存壁纸（存储空间不足或不可写）", cause)
    class Permission(cause: Throwable) : WallpaperImportFailure("没有读取这张图片的权限（授权可能已失效），请重新选择", cause)
    class NotFound(cause: Throwable) : WallpaperImportFailure("系统相册没有找到这张图片，请换一张", cause)
    class Io(cause: Throwable) : WallpaperImportFailure("读取图片时出错（存储异常）", cause)
}

/**
 * 把**读源段**抛出的任意原始异常映射成可上屏的 [WallpaperImportFailure]。**纯函数**，
 * 不依赖任何 android 类（JVM 单测可直接覆盖）。
 *
 * ⚠️ 只负责**读源段**（ContentResolver 开流 / 读流）的异常。**写盘段**（
 * `target.outputStream()`）的失败**不走这里** —— 那段由 `import()` 单独 catch 并抛
 * [WallpaperImportFailure.WriteFailed]，否则"磁盘满"的 `FileNotFoundException` 会被
 * 这里的 `NotFound` 分支误导成「系统相册没有找到这张图片」。5 条映射**保持不变**。
 *
 * ⚠️ 分支顺序不能乱：[FileNotFoundException] 是 [IOException] 的子类，必须排在
 * [IOException] **之前**，否则"相册里找不到"会被吞成笼统的"存储异常"。
 * [SecurityException] 是 `RuntimeException` 的子类，与 IOException 无继承关系，
 * 顺序无所谓，但放在最前语义最清晰。
 */
internal fun classifyImportFailure(cause: Throwable?): WallpaperImportFailure = when (cause) {
    is SecurityException -> WallpaperImportFailure.Permission(cause)
    is FileNotFoundException -> WallpaperImportFailure.NotFound(cause)
    is IOException -> WallpaperImportFailure.Io(cause)
    else -> WallpaperImportFailure.DecodeFailed
}

/**
 * 标准降采样公式：**最长边**超过目标就再砍半，直到不超为止。
 * inSampleSize 必须是 2 的幂（Skia 的硬约束，非 2 的幂会被向上取整造成过采）。
 *
 * ⚠️ 判据必须是「最长边」（`maxOf(width, height)`），不能用"宽高都 ≥ 目标"：
 * 后者对常见宽高比（16:9 / 4:3 的短边 < 目标）会**系统性少砍一档** ——
 * 12MP（4000×3000）会原样全尺寸解码 ≈48MB ARGB_8888，"目标尺寸量级"
 * 的内存承诺不成立，且这张图会全尺寸落盘、全尺寸常驻显示。
 *
 * ⚠️ 可见性为 `internal`（而非原先的 `private`）是为了让 JVM 单测覆盖它；同时刻意
 * 提升为**文件级纯函数**——它不需要 [WallpaperStore] 实例（更不需要 Android
 * [Context]），放成成员函数在无 Robolectric 的 JVM 单测里根本构造不出实例来调它。
 * 逻辑与原先逐字一致，调用点（[WallpaperStore.import] / [WallpaperStore.decode]）不变。
 */
internal fun sampleSizeFor(width: Int, height: Int, targetPx: Int): Int {
    var sample = 1
    if (width <= 0 || height <= 0 || targetPx <= 0) return sample
    val longest = maxOf(width, height)
    while (longest / sample > targetPx) sample *= 2
    return sample
}
