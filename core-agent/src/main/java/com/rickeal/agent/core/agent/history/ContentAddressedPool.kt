package com.rickeal.agent.core.agent.history

import com.rickeal.agent.core.model.AgentLogStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * 内容寻址正文池（Octop history_v2 的 blobs 降级）：`blobs/<sha256前2位>/<sha256>.txt`，
 * 同内容幂等复用 —— 重试/重复正文零写放大。纯文本存储（不 JSON 化，避免转义膨胀）。
 *
 * 记录层纪律：**永远不是失败源** —— put 失败返回 null（调用方降级跳过该正文引用），
 * get 缺失/损坏返回 null；绝不向归档流程抛异常。
 */
class ContentAddressedPool(
    private val rootDir: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    suspend fun put(text: String): BlobRef? = withContext(ioDispatcher) {
        if (text.isEmpty()) return@withContext null
        runCatching {
            val digest = sha256Hex(text)
            val file = File(File(rootDir, digest.take(2)), digest)
            if (!file.exists()) {
                file.parentFile?.mkdirs()
                // tmp 唯一名 + ATOMIC_MOVE（原子写范式，同 AgentPlanStore.persistSync）
                val tmp = File(rootDir, digest.take(2) + "." + System.nanoTime() + ".tmp")
                tmp.writeText(text)
                try {
                    Files.move(
                        tmp.toPath(),
                        file.toPath(),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                } catch (t: Throwable) {
                    // 个别文件系统不支持 ATOMIC_MOVE，退化普通 rename（仍是元数据操作）
                    Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            }
            BlobRef(sha256 = digest, chars = text.length)
        }.getOrNull()
    }

    fun getSync(ref: BlobRef): String? {
        val file = File(File(rootDir, ref.sha256.take(2)), ref.sha256)
        if (!file.exists()) return null
        return runCatching { file.readText() }.getOrNull()
    }

    fun exists(ref: BlobRef): Boolean = File(File(rootDir, ref.sha256.take(2)), ref.sha256).exists()

    companion object {
        fun sha256Hex(text: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(text.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}
