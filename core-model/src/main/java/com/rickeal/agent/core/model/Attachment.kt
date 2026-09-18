package com.rickeal.agent.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 多模态附件。只保存「引用」，不保存字节。
 * 字节在真正调用引擎时由 :core-engine 的 AttachmentBytesReader 按需读取。
 *
 * 注意：sealed 基类只用**抽象函数**携带公共行为，绝不用抽象属性
 * （抽象属性在某些插件版本下会触发 duplicate serial name 问题）。
 */
@Serializable
sealed class Attachment {

    abstract fun key(): String
    abstract fun label(): String

    @Serializable
    @SerialName("text")
    data class Text(
        val id: String = newId(),
        val text: String = "",
        val name: String = "文本",
    ) : Attachment() {
        override fun key(): String = id
        override fun label(): String = name
    }

    @Serializable
    @SerialName("image")
    data class Image(
        val id: String = newId(),
        val uri: String = "",
        val name: String = "图片",
        val mimeType: String = "image/png",
        val width: Int = 0,
        val height: Int = 0,
        val sizeBytes: Long = 0L,
    ) : Attachment() {
        override fun key(): String = id
        override fun label(): String = name
    }

    @Serializable
    @SerialName("audio")
    data class Audio(
        val id: String = newId(),
        val uri: String = "",
        val name: String = "音频",
        val mimeType: String = "audio/wav",
        val durationMillis: Long = 0L,
        val sizeBytes: Long = 0L,
    ) : Attachment() {
        override fun key(): String = id
        override fun label(): String = name
    }

    @Serializable
    @SerialName("file")
    data class File(
        val id: String = newId(),
        val uri: String = "",
        val name: String = "文件",
        val mimeType: String = "*/*",
        val sizeBytes: Long = 0L,
    ) : Attachment() {
        override fun key(): String = id
        override fun label(): String = name
    }
}

/** 便捷取值：任意附件的稳定 key（用于 LazyColumn 的 item key）。 */
fun Attachment.stableKey(): String = key() + "_" + label()
