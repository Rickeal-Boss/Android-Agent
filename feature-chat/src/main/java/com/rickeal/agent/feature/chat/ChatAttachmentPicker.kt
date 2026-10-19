package com.rickeal.agent.feature.chat

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * 图片 / 音频选择。
 *
 * 用系统 `OpenDocument`（SAF）而不是自己写文件浏览器：
 *  - 零权限弹窗（READ_MEDIA_IMAGES 只在直接读 MediaStore 时才需要）
 *  - 返回的 content:// Uri 用 `takePersistableUriPermission` 拿到长期读权限，
 *    会话落盘后重开 App 仍然可读
 *  - 缩略图由 `:core-design` 的 `GlassBubble` 用 BitmapFactory 采样解码（不引 Coil）
 */
@Composable
fun rememberImagePicker(onPicked: (uri: String, name: String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            grantPersistable(context, uri)
            onPicked(uri.toString(), displayName(context, uri))
        }
    }
    return remember(launcher) { { launcher.launch(arrayOf("image/*")) } }
}

@Composable
fun rememberAudioPicker(onPicked: (uri: String, name: String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            grantPersistable(context, uri)
            onPicked(uri.toString(), displayName(context, uri))
        }
    }
    return remember(launcher) { { launcher.launch(arrayOf("audio/*")) } }
}

/**
 * 文本 / Markdown 文件选择（UI-1019 Batch 4）。
 *
 * 用户需求：「添加附件应当加入文件按钮并且弹出文件选择（txt 和 markdown）」。
 *
 * MIME 过滤：`text/plain` + `text/markdown`。SAF 的 `EXTRA_MIME_TYPES` 会把这些
 * 一起下发，系统文件选择器按 MIME 匹配（多数 ROM 同时按扩展名回退）⇒ `.txt` /
 * `.md` 都能选到。**不**加 `application/octet-stream`：那等于放开所有二进制文件，
 * 与"txt 和 markdown"的需求相反。
 *
 * 选中的 content:// 会在 VM 侧**立即读成文本**（`Attachment.Text`）而不是落盘复制
 * 原文件 —— 引擎对 `Attachment.File` 是显式 no-op（`LiteRtLmEngine` 的 `is
 * Attachment.File -> Unit`），只有 `Attachment.Text` 会被内联进提示词。这也是
 * 不动内核（定版冻结）的前提下让模型真正"读到文件"的唯一路径。
 */
@Composable
fun rememberFilePicker(onPicked: (uri: String, name: String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            grantPersistable(context, uri)
            onPicked(uri.toString(), displayName(context, uri))
        }
    }
    return remember(launcher) {
        { launcher.launch(arrayOf("text/plain", "text/markdown")) }
    }
}

private fun grantPersistable(context: android.content.Context, uri: Uri) {
    runCatching {
        context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
    }
}

private fun displayName(context: android.content.Context, uri: Uri): String {
    // SAF 的 document URI（content://…/document/1000010056）拿 lastPathSegment 只会得到
    // "document:1000010056" —— 真机上就是附件 chip 显示一串数字（UI-1019 Batch 4 实测）。
    // 先问 provider 的 OpenableColumns.DISPLAY_NAME（系统文件选择器都会给），
    // 查不到再回退 lastPathSegment。
    runCatching {
        context.contentResolver
            .query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) {
                        val name = cursor.getString(index)
                        if (!name.isNullOrBlank()) return name
                    }
                }
            }
    }
    val raw = uri.lastPathSegment.orEmpty()
    return raw.substringAfterLast('/').ifBlank { "附件" }
}
