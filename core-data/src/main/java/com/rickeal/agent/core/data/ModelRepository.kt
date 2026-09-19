package com.rickeal.agent.core.data

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import com.rickeal.agent.core.engine.local.ModelCapabilityProbe
import com.rickeal.agent.core.model.ModelCapabilities
import com.rickeal.agent.core.model.ModelDescriptor
import com.rickeal.agent.core.model.ModelHeuristics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

/** 别名：与任务书里的命名对齐（真正实现的类名沿用架构文档 §6.3 的 ModelRepository）。 */
typealias ModelsRepository = ModelRepository

private val MODEL_EXTENSIONS = setOf("litertlm", "task", "bin", "tflite")

/**
 * 本地模型清单。
 *
 * 目录约定：
 *  - 内部：`filesDir/models/` —— 通过 SAF 导入的模型会被**复制**到这里（App 卸载即清理）
 *  - 外部：`getExternalFilesDir(null)/models/` —— 用户自己 adb push 进来的，只读扫描
 *  - 下载：`getExternalFilesDir(DIRECTORY_DOWNLOADS)/` —— DownloadManager 的落盘处，
 *    下载完成后**就地登记**（不复制），见 [importFromPath]
 *  - 索引：`filesDir/model_index/models.json`（不放进 models/ 是为了避免扫描时把它当模型文件）
 *
 * 所有文件 IO 都在 Dispatchers.IO；任何解析/读取失败都退化成「当作没有」，不抛异常。
 */
class ModelRepository(
    private val context: Context,
    /** 只用来观察「当前选中模型 id」；传 null 时 observeActiveModel() 恒返回 null。 */
    private val settings: SettingsRepository? = null,
) {

    private val modelsDir: File = File(context.filesDir, "models")
    private val externalDir: File? = context.getExternalFilesDir(null)?.let { File(it, "models") }
    private val store = JsonFileStore(File(context.filesDir, "model_index"))

    private val _models = MutableStateFlow<List<ModelDescriptor>>(emptyList())
    val models: StateFlow<List<ModelDescriptor>> = _models.asStateFlow()

    /** 内部导入目录的绝对路径，UI 提示用户「把 .litertlm 放到这里」时用。 */
    val importDirPath: String get() = modelsDir.absolutePath

    suspend fun refresh() = withContext(Dispatchers.IO) {
        modelsDir.mkdirs()
        val known = store.read("models.json", ListSerializer(ModelDescriptor.serializer()))
            ?.filter { it.path.isNotBlank() }
            ?: emptyList()
        val knownPaths = known.map { it.path }.toSet()
        val discovered = scanDirectories().filter { it.path !in knownPaths }
        // 双重去重：
        //  1) knownPaths 挡掉「已登记 + 又被扫描到」的同路径文件 —— 就地登记的下载文件正好走这条
        //     （scanDirectories 本来就包含下载目录，登记后仍会被扫到）；
        //  2) distinctBy 兜底 known 自身可能存在的同路径重复项，保留先出现的那个。
        val next = (known + discovered)
            .map { ModelHeuristics.applyTo(it) }
            .distinctBy { it.path }
        _models.value = next
        store.write("models.json", next, ListSerializer(ModelDescriptor.serializer()))
    }

    suspend fun upsert(model: ModelDescriptor) = withContext(Dispatchers.IO) {
        val next = _models.value.filter { it.id != model.id } + model
        store.write("models.json", next, ListSerializer(ModelDescriptor.serializer()))
        _models.value = next
    }

    /** @param deleteFile 是否连带删除磁盘上的模型文件（默认只从清单移除）。 */
    suspend fun remove(id: String, deleteFile: Boolean = false) = withContext(Dispatchers.IO) {
        if (deleteFile) {
            val target = _models.value.firstOrNull { it.id == id }
            if (target != null && target.path.isNotBlank()) {
                runCatching { File(target.path).delete() }
            }
        }
        val next = _models.value.filter { it.id != id }
        store.write("models.json", next, ListSerializer(ModelDescriptor.serializer()))
        _models.value = next
    }

    suspend fun find(id: String?): ModelDescriptor? =
        if (id == null) null else _models.value.firstOrNull { it.id == id }

    /**
     * 观察「当前选中的模型」：activeModelId 与 models 任一变化都会重新发射。
     * 未选中 / 选中 id 已不存在时发射 null。
     */
    fun observeActiveModel(): Flow<ModelDescriptor?> {
        val ids = settings?.activeModelId
            ?: return flowOf(null)
        return combine(ids, models) { id, list ->
            if (id == null) null else list.firstOrNull { it.id == id }
        }
    }

    suspend fun setCapabilities(id: String, capabilities: ModelCapabilities) {
        val current = find(id) ?: return
        upsert(current.copy(capabilities = capabilities))
    }

    /**
     * 能力探测入口（架构文档 §5.3）。
     * 先跑文件名启发式补齐 family/量化/能力位，再用 LiteRT-LM 的 Capabilities 探测
     * 是否支持 speculative decoding。探测失败一律吞掉。
     */
    suspend fun probe(id: String): ModelDescriptor? = withContext(Dispatchers.IO) {
        val current = _models.value.firstOrNull { it.id == id } ?: return@withContext null
        val file = File(current.path)
        val exists = file.exists()
        val size = if (current.sizeBytes > 0L) current.sizeBytes else if (exists) file.length() else 0L
        val enriched = ModelHeuristics.applyTo(current.copy(sizeBytes = size))
        val speculative = if (exists) {
            ModelCapabilityProbe.hasSpeculativeDecoding(current.path)
        } else {
            false
        }
        val updated = enriched.copy(
            capabilities = enriched.capabilities.copy(speculativeDecoding = speculative),
        )
        upsert(updated)
        updated
    }

    /**
     * 导入本地模型文件：把 Uri 内容**复制**进 `filesDir/models/`。
     *
     * 为什么必须复制而不是直接记路径：SAF 返回的 `content://` Uri 的读取授权是**有时效**的
     * （重启 / 授权撤销后可能失效），且 LiteRT-LM 只接受真实文件路径。
     *
     * 注意与 [importFromPath] 的区别：**只有 SAF 来源才复制**。
     * 我们自己用 DownloadManager 下到 `externalFilesDir/Download` 的文件属于 App 私有目录、
     * 路径长期有效，走 [importFromPath] 就地登记即可，不必再拷一份 2~4GB。
     *
     * @return 成功返回新的 ModelDescriptor；失败（Uri 读不到 / IO 错误）返回 null。
     */
    suspend fun importFromUri(uri: Uri, displayName: String? = null): ModelDescriptor? =
        withContext(Dispatchers.IO) {
            try {
                if (!modelsDir.exists()) modelsDir.mkdirs()
                val raw = displayName ?: queryDisplayName(uri) ?: "model_${System.currentTimeMillis()}"
                val safe = sanitizeFileName(raw)
                val target = uniqueFile(modelsDir, safe)
                val stream = context.contentResolver.openInputStream(uri)
                    ?: return@withContext null
                // 1MB 缓冲：默认 8KB 拷 3.6GB 要走几十万次循环，明显拖慢导入
                stream.use { input -> target.outputStream().use { output -> input.copyTo(output, 1024 * 1024) } }
                val descriptor = ModelHeuristics.applyTo(
                    ModelDescriptor(
                        path = target.absolutePath,
                        fileName = target.name,
                        sizeBytes = target.length(),
                    )
                )
                upsert(descriptor)
                descriptor
            } catch (t: Throwable) {
                null
            }
        }

    /**
     * **就地登记**一个已知绝对路径的文件：只在清单里写一条记录，**不复制文件**。
     *
     * 适用场景（文件已落在我们长期可读的位置）：
     *  - `getExternalFilesDir(DIRECTORY_DOWNLOADS)`：我们自己用 DownloadManager 下的模型；
     *  - `getExternalFilesDir(null)/models`：用户 adb push 进来的。
     * 这些路径不依赖 SAF 授权，重启后依然有效，所以「文件位置」就是「模型位置」（零拷贝）。
     *
     * **不要**用它登记 SAF 的 `content://`（授权有时效），那类必须走 [importFromUri] 复制。
     *
     * 幂等：同一路径重复登记（下载完成后 refresh() 又扫到、用户重试下载）会复用已有条目的 id，
     * 避免清单里出现「同路径不同 id」的重复项。
     */
    suspend fun importFromPath(path: String): ModelDescriptor? = withContext(Dispatchers.IO) {
        val file = File(path)
        if (!file.isFile) return@withContext null
        val absolute = file.absolutePath
        val existing = _models.value.firstOrNull { it.path == absolute }
        val base = existing?.copy(fileName = file.name, sizeBytes = file.length())
            ?: ModelDescriptor(path = absolute, fileName = file.name, sizeBytes = file.length())
        val descriptor = ModelHeuristics.applyTo(base)
        upsert(descriptor)
        descriptor
    }

    private fun scanDirectories(): List<ModelDescriptor> {
        val out = ArrayList<ModelDescriptor>()
        // DownloadManager 的落盘目录（可能为 null，交给 listOfNotNull 过滤）：
        // 下载完成后**就地登记**的模型本来就住在这里，登记过就会被 refresh() 的 knownPaths 挡掉，
        // 不会重复；只有「下完还没来得及登记就被中断（进程被杀 / 登记失败）」的文件才会被这里
        // 重新发现，用户点「扫描」就能把这 2~4GB 找回来，不至于白下载一次。是**追加**，不替换上面的目录。
        val downloadDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        val dirs = listOfNotNull(modelsDir, externalDir, downloadDir)
        for (dir in dirs) {
            if (!dir.exists()) continue
            val files = dir.listFiles() ?: continue
            for (file in files) {
                if (!file.isFile) continue
                if (file.extension.lowercase() !in MODEL_EXTENSIONS) continue
                out.add(
                    ModelHeuristics.applyTo(
                        ModelDescriptor(
                            path = file.absolutePath,
                            fileName = file.name,
                            sizeBytes = file.length(),
                        )
                    )
                )
            }
        }
        return out
    }

    private fun queryDisplayName(uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
        } catch (t: Throwable) {
            null
        }
    }

    private fun sanitizeFileName(name: String): String {
        val cleaned = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
        return if (cleaned.isBlank()) "model_${System.currentTimeMillis()}" else cleaned
    }

    private fun uniqueFile(dir: File, name: String): File {
        var candidate = File(dir, name)
        if (!candidate.exists()) return candidate
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var index = 1
        while (candidate.exists() && index < 1000) {
            candidate = File(dir, "${base}_$index$ext")
            index++
        }
        return candidate
    }
}
