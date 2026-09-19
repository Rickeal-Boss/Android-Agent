package com.rickeal.agent.core.data

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import com.rickeal.agent.core.engine.local.ModelCapabilityProbe
import com.rickeal.agent.core.model.AgentLogStore
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

/** 别名：与任务书里的命名对齐（真正实现的类名沿用架构文档 §6.3 的 ModelRepository）。 */
typealias ModelsRepository = ModelRepository

private val MODEL_EXTENSIONS = setOf("litertlm", "task", "bin", "tflite")

/**
 * 扫描时认作模型文件的最小体积（1MB）。
 *
 * 只用于挡掉「刚创建还没写入的空文件 / 占位文件」这类边缘情况，
 * **挡不住下到一半的大文件** —— 那种靠 ModelDownloader 的 ".part" 命名规避
 * （半截文件叫 `<name>.part`，扩展名不在这里，根本扫不到）。
 */
private const val MIN_MODEL_FILE_BYTES = 1024L * 1024L

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

    /**
     * 串行化所有「读-改-写」清单的操作。
     *
     * `upsert` / `refresh` / `remove` 都是「读 `_models.value` → 改 → 写 models.json」，
     * 无锁并发时后写的整份会覆盖先写的，表现为「刚导入的模型从清单里消失」且无任何报错。
     * 注意 `Mutex` 不可重入：这三个方法之间不互相调用（`probe` / `setCapabilities` /
     * `importFrom*` 只调 `upsert`，自己不加锁），不会自锁。
     */
    private val writeMutex = Mutex()

    private val _models = MutableStateFlow<List<ModelDescriptor>>(emptyList())
    val models: StateFlow<List<ModelDescriptor>> = _models.asStateFlow()

    /** 内部导入目录的绝对路径，UI 提示用户「把 .litertlm 放到这里」时用。 */
    val importDirPath: String get() = modelsDir.absolutePath

    /**
     * 重新加载清单：读 `models.json` → 扫描目录补漏 → 回写。
     *
     * ## 解析失败时**绝不写回**（本方法最重要的一条不变式）
     *
     * `models.json` 半截 / 损坏时 [JsonFileStore.read] 返回 null。若照常走到写回那一步，
     * 写进去的就是「只有本次扫描到的那几条」——用户手动登记、就地登记的模型条目会被
     * **永久删除**（磁盘上的文件还在，但清单没了，UI 再也看不到）。
     *
     * 会触发它的真实例子：`SamplingParams.init` 的 `require` 在**反序列化**时抛异常
     * （一条历史脏数据就能让整个 `List<ModelDescriptor>` 解析失败）。
     *
     * 关于那条 `require` 的取舍（主理人裁决，勿改）：
     *  **保留原样**。收紧会把 `temperature == 0f` 这类历史数据也变成异常，把问题放大；
     *  放宽则失去一处防御。真正切断「一条坏数据 → 用户模型清单被清空」链条的是**这里**
     *  的不写回，所以 `SamplingParams.init` 既不要收紧也不要放宽。
     */
    suspend fun refresh() = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            modelsDir.mkdirs()
            // 必须先区分「文件不存在」与「文件在但解析不出来」：
            // 前者是首次启动，写回是必需的（把扫描结果固化下来）；后者是数据损坏，写回即销毁。
            val hasFile = store.exists("models.json")
            val parsed = store.read("models.json", ListSerializer(ModelDescriptor.serializer()))
            val parseFailed = hasFile && parsed == null
            val known = parsed?.filter { it.path.isNotBlank() } ?: emptyList()
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
            if (parseFailed) {
                // 只更新内存，**一个字节都不写**。磁盘上那份坏文件保持原样：
                // 用户还有机会手动修 / 等下次解析成功时原样读回，而写回是不可逆的。
                //
                // 不记文件名以外的任何内容（不记路径、不记条数），避免把用户的模型清单
                // 通过日志带出去；这里要留下的只是「发生了」这个事实。
                AgentLogStore.error("模型清单解析失败：已跳过回写，磁盘上的 models.json 保持原样")
                return@withLock
            }
            store.write("models.json", next, ListSerializer(ModelDescriptor.serializer()))
        }
    }

    suspend fun upsert(model: ModelDescriptor) = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            val next = _models.value.filter { it.id != model.id } + model
            store.write("models.json", next, ListSerializer(ModelDescriptor.serializer()))
            _models.value = next
        }
    }

    /**
     * @param deleteFile 是否连带删除磁盘上的模型文件（默认只从清单移除）。
     *
     * `deleteFile = true` 时**只有确认没有别的条目仍指向同一路径才删文件**：
     * 两个条目完全可能指向同一个文件（手工登记一次 + 扫描又登记一次、或用户自己
     * 登记了同一个路径两次），此时删掉其中一条就会让另一条变成"清单里有、磁盘上没有"
     * 的死条目 —— 用户点加载直接 native 崩溃（表现为闪退）。
     * 文件本身的删除是**不可逆**的，所以宁可留一个孤儿文件（用户可以自己清理），
     * 也不要误删别人还在用的。
     */
    suspend fun remove(id: String, deleteFile: Boolean = false) = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            if (deleteFile) {
                val target = _models.value.firstOrNull { it.id == id }
                if (target != null && target.path.isNotBlank()) {
                    // File(path).absolutePath 归一：同一文件可能登记成相对 / 带 ".." 的两种写法
                    val absolute = File(target.path).absolutePath
                    val stillReferenced = _models.value.any { other ->
                        other.id != id &&
                            other.path.isNotBlank() &&
                            File(other.path).absolutePath == absolute
                    }
                    if (!stillReferenced) runCatching { File(target.path).delete() }
                }
            }
            val next = _models.value.filter { it.id != id }
            store.write("models.json", next, ListSerializer(ModelDescriptor.serializer()))
            _models.value = next
        }
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
            var part: File? = null
            try {
                if (!modelsDir.exists()) modelsDir.mkdirs()
                val raw = displayName ?: queryDisplayName(uri) ?: "model_${System.currentTimeMillis()}"
                val safe = sanitizeFileName(raw)
                val target = uniqueFile(modelsDir, safe)
                // 与 ModelDownloader 的 ".part" 约定对齐：先写 `<name>.part`。
                // 它的扩展名不命中 MODEL_EXTENSIONS，扫描永远扫不到 —— 于是「导入 2~4GB 模型时
                // 存储耗尽 / 切后台被杀」留下的半截文件不会被当成正常模型。
                // （原实现直接写最终名且失败不清理：那种半截 `.litertlm` 体积也满足 ≥1MB，
                //  重启后被扫成可加载模型，用户点加载 → LiteRT native 崩溃闪退。）
                val tmp = File(modelsDir, target.name + ".part")
                part = tmp
                val stream = context.contentResolver.openInputStream(uri)
                    ?: return@withContext null
                // 1MB 缓冲：默认 8KB 拷 3.6GB 要走几十万次循环，明显拖慢导入
                stream.use { input -> tmp.outputStream().use { output -> input.copyTo(output, 1024 * 1024) } }
                // 只有真正写完才转正
                if (!tmp.renameTo(target)) return@withContext null
                part = null
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
            } finally {
                // 任何失败路径（Uri 读不到 / IO 错误 / 存储耗尽 / 协程被取消）都清掉半截文件
                runCatching { part?.delete() }
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
                // 廉价防御：只挡空文件/占位文件，挡不住半截的大文件（那种靠 .part 命名规避）
                if (file.length() < MIN_MODEL_FILE_BYTES) continue
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
