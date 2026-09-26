package com.rickeal.agent.core.design

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 玻璃风格图片缩略图（Wave 20）：按 URI 解码出小图，未就绪/失败时显示占位图标。
 *
 * ## 为什么是「三态 URI」（Wave 20 根修）
 *
 * 附件落库的 `Attachment.Image.uri` 有三种形态：
 *  1. **裸绝对路径**（主流）：`AppContainer.importAttachment` 把 content:// 拷进内部
 *     存储后返回的就是 `/data/user/0/...` —— 旧版 `decodeThumbnail` 只会走
 *     ContentResolver，对无 scheme 的 Uri 抛 FileNotFoundException，导致**发送图片后
 *     气泡里永远是占位图标**（用户截图实证）；
 *  2. `file://`（旧数据 / 部分设备直存）；
 *  3. `content://`（导入失败回退 uriString 原值时的兜底形态）。
 *
 * ## 约定
 *
 *  - 刻意不引 Coil（架构约定：不引入图片库），RGB_565 省一半内存；
 *  - 解码在 Dispatchers.IO（content:// 可能是网盘 Provider，openInputStream 会同步走
 *    网络），组合期只出占位图标；
 *  - 消费方：聊天气泡附件条（GlassSurface.AttachmentThumb）与输入框附件缩略块
 *    （ChatInputBar），两处尺寸不同、由 modifier 决定。
 */
@Composable
fun GlassImageThumb(
    uri: String,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    cornerRadius: androidx.compose.ui.unit.Dp = 12.dp,
) {
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(initialValue = null, key1 = uri) {
        value = withContext(Dispatchers.IO) { decodeThumbnail(context, uri) }
    }
    // 委托属性（by produceState）在 null 检查与使用之间可能被其他帧改写，
    // Kotlin 不会为它做 smart cast —— 必须先取到局部 val 再判空。
    val decoded = bitmap
    if (decoded != null) {
        Image(
            bitmap = decoded,
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            modifier = modifier.clip(RoundedCornerShape(cornerRadius)),
        )
    } else {
        val colors = LocalGlassColors.current
        Box(
            modifier = modifier,
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.PhotoLibrary,
                contentDescription = null,
                tint = colors.onGlassSubtle,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

/** 缩略图长边的目标上限（px）。缩略图实际显示尺寸 ≤96×96 dp，320px 足够。 */
private const val THUMBNAIL_MAX_DIM = 320

/**
 * 按「三态 URI」打开字节流：裸绝对路径 / file:// 走文件系统，其余交给 ContentResolver。
 * 所有分支都可能抛异常（路径失效 / Provider 报错），统一 runCatching 兜底返回 null。
 */
private fun openSource(context: android.content.Context, uri: String): java.io.InputStream? = when {
    uri.isBlank() -> null
    // 裸绝对路径：importAttachment 落库的主流形态。ContentResolver 对无 scheme 的
    // Uri 一律 FileNotFoundException —— 旧版缩略图永远显示占位图标的根因。
    uri.startsWith("/") -> runCatching { java.io.FileInputStream(uri) }.getOrNull()
    uri.startsWith("file://") -> runCatching {
        java.io.FileInputStream(android.net.Uri.parse(uri).path ?: return@runCatching null)
    }.getOrNull()
    else -> runCatching { context.contentResolver.openInputStream(android.net.Uri.parse(uri)) }.getOrNull()
}

/**
 * 缩略图解码：先读 bounds 算 inSampleSize，再按需缩放解码。
 *
 * **必须在后台线程调用**：`content://` 可能是网盘 Provider，`openInputStream`
 * 会同步走网络（调用点 [GlassImageThumb] 已切到 Dispatchers.IO）。
 */
private fun decodeThumbnail(context: android.content.Context, uri: String): ImageBitmap? = runCatching {
    if (uri.isBlank()) return null
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    openSource(context, uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
    val maxDim = maxOf(bounds.outWidth, bounds.outHeight)
    // inJustDecodeBounds 失败（流不可读 / 非图片 / Provider 报错）时 outWidth/outHeight
    // 会是 -1 或 0：不挡掉的话 maxOf(-1, -1) = -1 ⇒ 下面的 while 一次都不执行 ⇒
    // sample 保持 1 ⇒ 整图解码，4000×3000 的照片就是 24~36MB ⇒ OOM。
    if (maxDim <= 0) return null
    var sample = 1
    while (maxDim / sample > THUMBNAIL_MAX_DIM) sample *= 2
    val options = android.graphics.BitmapFactory.Options().apply {
        inSampleSize = sample.coerceAtLeast(1)
        inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
    }
    val decoded = openSource(context, uri)?.use {
        android.graphics.BitmapFactory.decodeStream(it, null, options)
    }
    decoded?.asImageBitmap()
}.getOrNull()
