package com.rickeal.agent.core.data

import java.nio.file.Files
import java.nio.file.StandardCopyOption
import android.os.Process

import com.rickeal.agent.core.model.AgentJson
import com.rickeal.agent.core.model.AgentLogStore
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

    /**
     * 目标文件是否存在。
     *
     * 用来区分 [read] 返回 null 的两种含义 —— 「文件根本不存在（首次启动）」与
     * 「文件存在但解析失败（半截 / 损坏）」。这两者的处置**完全相反**：
     * 前者可以放心写回，后者**绝不能写回**（否则等于把损坏的文件覆盖成"只剩当前扫描到的"，
     * 用户手动登记的条目永久消失）。见 ModelRepository.refresh 的用法。
     */
    suspend fun exists(fileName: String): Boolean = withContext(Dispatchers.IO) {
        File(baseDir, fileName).exists()
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

    /**
     * 原子写 JSON 文件。返回**是否落盘成功**（外部审查报告3-C1 修正项）。
     *
     * 失败不抛异常（历史上抛异常 = 炸穿到无异常处理器的 viewModelScope，表现为
     * 闪退 —— Wave4 E-P1-1 才改为吞掉），错误日志保留；但失败必须让调用方**可知**
     * —— 磁盘满时静默丢数据（模型清单 / 会话全文）是最难排查的故障形态。
     * 现有全部调用点都把返回值当语句用（忽略返回值），签名加宽编译兼容；
     * 需要区分成败的调用方将来可直接消费返回值。
     */
    suspend fun <T> write(
        fileName: String,
        value: T,
        strategy: SerializationStrategy<T>,
    ): Boolean = withContext(Dispatchers.IO) {
        if (!baseDir.exists()) baseDir.mkdirs()
        val target = File(baseDir, fileName)
        // 临时文件必须**唯一**：固定名会与并发/上一次崩溃残留的 tmp 相互覆盖。
        // 也不能用「读全文再整写」兜底 —— 那正是会把会话文件写坏的路径。
        val tmp = File(baseDir, tmpName(fileName))
        try {
            // Wave4 审查（E-P1-1）：tmp 写入必须在 try 内 —— 磁盘配额耗尽时 writeText 抛
            // IOException，此前直接炸穿到 ChatViewModel.viewModelScope（无异常处理器），
            // 表现为「发消息时 App 闪退」。吞掉并留 ERROR，UI 侧由调用方决定如何提示。
            tmp.writeText(json.encodeToString(strategy, value))
            Files.move(
                tmp.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            true
        } catch (t: Throwable) {
            // 某些文件系统不支持 ATOMIC_MOVE，退化为普通 rename（仍是元数据操作，非逐字节重写）。
            // 注意：writeText 失败时 move 也会失败，退化 rename 静默不成 —— 所以 finally 统一清 tmp。
            runCatching {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            AgentLogStore.error("JSON 落盘失败：$fileName（${t.javaClass.simpleName}: ${t.message}）")
            false
        } finally {
            // 孤儿 tmp 清理：唯一清理入口 delete() 在「写失败」路径上永远走不到，必须就地清。
            if (tmp.exists()) runCatching { tmp.delete() }
        }
    }

    /**
     * 删除目标文件，**并清理它遗留的临时文件**。
     *
     * 临时文件名由 [tmpName] 生成（`$fileName.<nano>.<pid>.tmp`，名字必须唯一，
     * 否则并发写会互相覆盖），所以按固定名 `$fileName.tmp` 去删是**删不到的** ——
     * 上一次 ATOMIC_MOVE 失败 / 进程被杀残留的 tmp 会一直躺在目录里，既占空间又会被
     * [list] 之外的扫描逻辑看到。这里按「前缀 + 后缀」成对匹配来清理。
     *
     * 匹配用的是**完整形态的正则**而不是简单的 `startsWith(prefix)`：
     * 只判前缀会让 `delete("index")` 连带删掉 `index.json.<nano>.<pid>.tmp`
     * （它们都以 `index.` 开头），属于跨条目误删。
     */
    suspend fun delete(fileName: String) = withContext(Dispatchers.IO) {
        File(baseDir, fileName).delete()
        val pattern = tmpPattern(fileName)
        baseDir.listFiles()?.forEach { file ->
            if (!file.isFile) return@forEach
            if (pattern.matches(file.name)) runCatching { file.delete() }
        }
        // 兼容更早期实现写出的固定名 tmp（`$fileName.tmp`）
        File(baseDir, "$fileName.tmp").delete()
    }

    suspend fun list(suffix: String = ".json"): List<File> = withContext(Dispatchers.IO) {
        if (!baseDir.exists()) return@withContext emptyList()
        baseDir.listFiles()?.filter { it.isFile && it.name.endsWith(suffix) } ?: emptyList()
    }

    private companion object {
        /** [write] 生成的临时文件名：$fileName.<nano>.<pid>.tmp */
        fun tmpName(fileName: String): String =
            "$fileName.${System.nanoTime()}.${Process.myPid()}.tmp"

        /** 精确匹配某个 fileName 的临时文件名形态（不做前缀模糊匹配）。 */
        fun tmpPattern(fileName: String): Regex =
            Regex("^${Regex.escape(fileName)}\\.\\d+\\.\\d+\\.tmp$")
    }
}
