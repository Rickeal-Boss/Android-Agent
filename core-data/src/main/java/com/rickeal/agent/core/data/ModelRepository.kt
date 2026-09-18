package com.rickeal.agent.core.data

import android.content.Context
import android.net.Uri
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
     * 为什么复制而不是直接记路径：SAF 返回的 `content://` Uri 在 App 重启后可能失效，
     * 且 LiteRT-LM 只接受真实文件路径。复制一份是最省事也最可靠的做法。
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
                stream.use { input -> target.outputStream().use { output -> input.copyTo(output) } }
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

    /** 导入一个已知绝对路径（外部目录里用户自己放的文件）。 */
    suspend fun importFromPath(path: String): ModelDescriptor? = withContext(Dispatchers.IO) {
        val file = File(path)
        if (!file.isFile) return@withContext null
        val descriptor = ModelHeuristics.applyTo(
            ModelDescriptor(
                path = file.absolutePath,
                fileName = file.name,
                sizeBytes = file.length(),
            )
        )
        upsert(descriptor)
        descriptor
    }

    private fun scanDirectories(): List<ModelDescriptor> {
        val out = ArrayList<ModelDescriptor>()
        val dirs = listOfNotNull(modelsDir, externalDir)
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
