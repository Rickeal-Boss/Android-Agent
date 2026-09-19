package com.rickeal.agent.core.data

import java.nio.file.Files
import java.nio.file.StandardCopyOption
import android.os.Process

import com.rickeal.agent.core.model.AgentJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 原子写 JSON 文件（先写 .tmp 再 rename），避免写到一半崩溃导致会话文件损坏。
 *
 * 读：解析失败一律返回 null（而不是抛异常）—— 一个坏掉的会话文件不该让整个 App 起不来。
 * 所有 IO 都在 Dispatchers.IO 上，不阻塞主线程。
 */
class JsonFileStore(
    private val baseDir: File,
    private val json: Json = AgentJson.Default,
) {
    suspend fun ensureDir() = withContext(Dispatchers.IO) {
        if (!baseDir.exists()) baseDir.mkdirs()
    }

    suspend fun <T> read(fileName: String, strategy: DeserializationStrategy<T>): T? =
        withContext(Dispatchers.IO) {
            val file = File(baseDir, fileName)
            if (!file.exists()) return@withContext null
            try {
                json.decodeFromString(strategy, file.readText())
            } catch (t: Throwable) {
                null
            }
        }

    suspend fun <T> write(
        fileName: String,
        value: T,
        strategy: SerializationStrategy<T>,
    ) = withContext(Dispatchers.IO) {
        if (!baseDir.exists()) baseDir.mkdirs()
        val target = File(baseDir, fileName)
        // 临时文件必须**唯一**：固定名会与并发/上一次崩溃残留的 tmp 相互覆盖。
        // 也不能用「读全文再整写」兜底 —— 那正是会把会话文件写坏的路径。
        val tmp = File(baseDir, "$fileName.${System.nanoTime()}.${Process.myPid()}.tmp")
        tmp.writeText(json.encodeToString(strategy, value))
        try {
            Files.move(
                tmp.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (t: Throwable) {
            // 某些文件系统不支持 ATOMIC_MOVE，退化为普通 rename（仍是元数据操作，非逐字节重写）
            runCatching {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            runCatching { tmp.delete() }
        }
    }

    suspend fun delete(fileName: String) = withContext(Dispatchers.IO) {
        File(baseDir, fileName).delete()
        File(baseDir, "$fileName.tmp").delete()
    }

    suspend fun list(suffix: String = ".json"): List<File> = withContext(Dispatchers.IO) {
        if (!baseDir.exists()) return@withContext emptyList()
        baseDir.listFiles()?.filter { it.isFile && it.name.endsWith(suffix) } ?: emptyList()
    }
}
