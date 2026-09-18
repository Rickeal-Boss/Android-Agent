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
            onPicked(uri.toString(), displayName(uri))
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
            onPicked(uri.toString(), displayName(uri))
        }
    }
    return remember(launcher) { { launcher.launch(arrayOf("audio/*")) } }
}

private fun grantPersistable(context: android.content.Context, uri: Uri) {
    runCatching {
        context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
    }
}

private fun displayName(uri: Uri): String {
    val raw = uri.lastPathSegment.orEmpty()
    return raw.substringAfterLast('/').ifBlank { "附件" }
}
