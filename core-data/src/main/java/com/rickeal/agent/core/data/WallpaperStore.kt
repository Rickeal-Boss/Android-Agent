package com.rickeal.agent.core.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

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
 *
 * 先例：[AppContainer.importAttachment] 同样把 content:// 一次性拷进内部目录 ——
 * Uri 不是长期有效的存储凭据，落盘成本必须发生在选中的那一刻。
 */
class WallpaperStore(private val context: Context) {

    private val wallpaperDir: File get() = File(context.filesDir, "wallpaper")

    /**
     * 从 Photo Picker 的 Uri 导入壁纸：降采样 → JPEG 85 压缩 → 落盘到**唯一**文件名。
     * 成功返回新文件的相对路径；失败返回带原因的 [Result.failure]
     * （存储满 / Uri 已失效 / 不是有效位图）—— 失败时旧壁纸文件原样保留。
     *
     * @param previousPath 当前 DataStore 里存的旧相对路径（调用方从
     *   [SettingsRepository.wallpaperPath] 取）。导入**成功后**删除旧文件 ——
     *   顺序不能反：先删后写，失败窗口里用户会失去正在用的壁纸。
     */
    suspend fun import(uri: Uri, previousPath: String? = null): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                // 1) 只读边界，不解码像素：拿不到尺寸就别谈降采样。
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)?.use { input ->
                    BitmapFactory.decodeStream(input, null, bounds)
                } ?: error("无法读取所选图片")
                check(bounds.outWidth > 0 && bounds.outHeight > 0) { "所选图片不是有效位图" }

                // 2) inSampleSize：目标边长 = min(2048, 屏幕最长边)。
                //    2048 是给平板 / 分屏多窗口留的余量，手机上通常取屏幕最长边本身。
                val screenLongest = maxOf(
                    context.resources.displayMetrics.widthPixels,
                    context.resources.displayMetrics.heightPixels,
                )
                val targetPx = minOf(2048, screenLongest)
                val options = BitmapFactory.Options().apply {
                    inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, targetPx)
                }
                val bitmap = context.contentResolver.openInputStream(uri)?.use { input ->
                    BitmapFactory.decodeStream(input, null, options)
                } ?: error("无法解码所选图片")

                // 3) JPEG 85：视觉上与原图几乎无差，体积约为 PNG 的 1/10 ——
                //    这张图会被玻璃节点反复采样，文件大小直接影响冷启动解码耗时。
                // 4) 唯一文件名：保证「导入成功 ⇒ DataStore 路径变化 ⇒ 显示链重新解码」。
                wallpaperDir.mkdirs()
                val target = File(wallpaperDir, "wallpaper_${System.currentTimeMillis()}.jpg")
                target.outputStream().use { output ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)
                }
                bitmap.recycle()
                // 成功后才删旧文件；新文件与旧文件同在本类签发的相对路径下。
                previousPath?.takeIf { it.isNotBlank() }
                    ?.let { File(context.filesDir, it).delete() }
                "wallpaper/${target.name}"
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

    /**
     * 标准降采样公式：**最长边**超过目标就再砍半，直到不超为止。
     * inSampleSize 必须是 2 的幂（Skia 的硬约束，非 2 的幂会被向上取整造成过采）。
     *
     * ⚠️ 判据必须是「最长边」（`maxOf(width, height)`），不能用"宽高都 ≥ 目标"：
     * 后者对常见宽高比（16:9 / 4:3 的短边 < 目标）会**系统性少砍一档** ——
     * 12MP（4000×3000）会原样全尺寸解码 ≈48MB ARGB_8888，"目标尺寸量级"
     * 的内存承诺不成立，且这张图会全尺寸落盘、全尺寸常驻显示。
     */
    private fun sampleSizeFor(width: Int, height: Int, targetPx: Int): Int {
        var sample = 1
        if (width <= 0 || height <= 0 || targetPx <= 0) return sample
        val longest = maxOf(width, height)
        while (longest / sample > targetPx) sample *= 2
        return sample
    }

    private companion object {
        const val JPEG_QUALITY = 85
    }
}
