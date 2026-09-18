package com.rickeal.agent.core.data

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
        val tmp = File(baseDir, "$fileName.tmp")
        tmp.writeText(json.encodeToString(strategy, value))
        if (!tmp.renameTo(target)) {
            target.writeText(tmp.readText())
            tmp.delete()
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
