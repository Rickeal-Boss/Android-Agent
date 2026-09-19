package com.rickeal.agent.feature.models

import android.app.DownloadManager
import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rickeal.agent.core.data.AppContainer
import com.rickeal.agent.core.engine.EngineLoadConfig
import com.rickeal.agent.core.model.EngineKind
import com.rickeal.agent.core.model.InferenceConfig
import com.rickeal.agent.core.model.InferenceBackend
import com.rickeal.agent.core.model.ModelCapabilities
import com.rickeal.agent.core.model.ModelDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Immutable
data class ModelsUiState(
    val models: List<ModelDescriptor> = emptyList(),
    val activeModelId: String? = null,
    val config: InferenceConfig = InferenceConfig(),
    val backend: InferenceBackend = InferenceBackend.CPU,
    /** 正在加载的模型 id */
    val loadingModelId: String? = null,
    /** 已成功加载的模型 id */
    val loadedModelId: String? = null,
    val importDirPath: String = "",
    /** 正在下载的模型名；非空表示有下载任务在跑 */
    val downloadName: String? = null,
    /** 下载进度 0~100 */
    val downloadPercent: Int? = null,
    /** 非空表示：检测到当前可能是按流量计费的网络，等用户确认是否仍要下载 */
    val meteredConfirmUrl: String? = null,
    /** 设置项：允许用移动数据下载（开启后不再弹确认） */
    val allowMeteredDownload: Boolean = false,
    val message: String? = null,
    val error: String? = null,
    val capabilitiesText: String? = null,
)

class ModelsViewModel(
    private val container: AppContainer,
) : ViewModel() {

    private var activeDownloadId: Long? = null

    /** 用户已在「移动数据下载」确认框里点过继续（一次性，用完即清） */
    private var meteredConfirmed: Boolean = false

    /** 来自设置的持久开关：允许用移动数据下载（开启后不再弹确认） */
    private var allowMeteredSetting: Boolean = false

    private val _uiState = MutableStateFlow(ModelsUiState())
    val uiState: StateFlow<ModelsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            container.modelRepository.refresh()
            _uiState.update { it.copy(importDirPath = container.modelRepository.importDirPath) }
        }
        viewModelScope.launch {
            container.modelRepository.models.collect { list ->
                _uiState.update { it.copy(models = list) }
            }
        }
        viewModelScope.launch {
            container.settingsRepository.activeModelId.collect { id ->
                _uiState.update { it.copy(activeModelId = id) }
            }
        }
        viewModelScope.launch {
            container.settingsRepository.inferenceConfig.collect { config ->
                _uiState.update { it.copy(config = config, backend = config.backend) }
            }
        }

        viewModelScope.launch {
            container.settingsRepository.allowMeteredDownload.collect { allow ->
                allowMeteredSetting = allow
                _uiState.update { it.copy(allowMeteredDownload = allow) }
            }
        }
    }

    /** 从系统文件选择器拿到 Uri 后导入（复制进内部目录由 repository 完成）。 */
    fun onImportUri(uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(message = "正在导入…", error = null) }
            val descriptor = container.modelRepository.importFromUri(uri)
            if (descriptor == null) {
                _uiState.update { it.copy(message = null, error = "导入失败：无法读取该文件或格式不支持") }
            } else {
                _uiState.update {
                    it.copy(
                        message = "已导入 ${descriptor.fileName}",
                        error = null,
                        activeModelId = it.activeModelId ?: descriptor.id,
                    )
                }
                if (_uiState.value.activeModelId == descriptor.id) {
                    container.settingsRepository.setActiveModel(descriptor.id)
                }
            }
        }
    }

    /**
     * 从 URL 下载模型：交给系统 DownloadManager（支持断点续传与后台下载），
     * 完成后自动复制进内部 models 目录并登记。
     */
    fun onDownloadFromUrl(url: String) {
        val trimmed = url.trim()
        if (!trimmed.startsWith("http://", ignoreCase = true) &&
            !trimmed.startsWith("https://", ignoreCase = true)
        ) {
            _uiState.update { it.copy(error = "请填写 http(s) 开头的模型直链", message = null) }
            return
        }
        viewModelScope.launch {
            // 计量网络（通常是移动数据）保护：GB 级文件用流量下的代价太高。
            // UI 上的「建议连 Wi-Fi」只是一句文案，不实际检查等于没有。
            if (!meteredConfirmed && !allowMeteredSetting &&
                withContext(Dispatchers.IO) { container.isMeteredNetwork() }
            ) {
                _uiState.update { it.copy(meteredConfirmUrl = trimmed, error = null, message = null) }
                return@launch
            }
            meteredConfirmed = false

            // 下载前检查存储空间：GB 级文件下到一半失败，代价太高
            val preset = ModelPresets.findByUrl(trimmed)
            if (preset != null) {
                val need = (preset.sizeBytes * 1.2 + 200L * 1024 * 1024).toLong()
                // StatFs 是阻塞 I/O，别占着主线程
                val available = withContext(Dispatchers.IO) { container.availableStorageBytes() }
                if (available < need) {
                    _uiState.update {
                        it.copy(
                            error = "存储空间不足：需要约 ${formatBytes(need)}，当前可用 ${formatBytes(available)}。请先清理空间。",
                            message = null,
                        )
                    }
                    return@launch
                }
            }
            val fileName = trimmed.substringBefore('?').substringAfterLast('/').ifBlank { "model.litertlm" }
            val downloadId = container.modelDownloader.enqueue(trimmed, fileName)
            if (downloadId == null) {
                _uiState.update { it.copy(error = "无法启动下载：系统下载服务不可用", message = null) }
                return@launch
            }
            activeDownloadId = downloadId
            _uiState.update {
                it.copy(downloadName = fileName, downloadPercent = 0, error = null, message = "已开始下载：$fileName")
            }
            while (true) {
                delay(1000L)
                val progress = container.modelDownloader.progress(downloadId)
                _uiState.update { it.copy(downloadPercent = progress.percent) }
                when (progress.status) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        val descriptor = importDownloaded(progress.localUri)
                        activeDownloadId = null
                        // 完整性校验：半成品模型会在 native 层崩溃（用户只看到闪退），必须在这里拦下
                        val mismatch = descriptor?.let { verifySize(it, preset) }
                        if (descriptor != null && mismatch == null) {
                            // 小白友好：下完直接用，不用再手动选一次模型
                            container.settingsRepository.setActiveModel(descriptor.id)
                            // 导入成功：删掉下载目录里的源文件，否则 2.5GB 模型会占 5GB
                            deleteDownloadedSource(progress.localUri, descriptor.path)
                        }
                        _uiState.update {
                            it.copy(
                                downloadName = null,
                                downloadPercent = null,
                                error = mismatch
                                    ?: if (descriptor == null) "下载完成，但导入失败" else null,
                                message = when {
                                    mismatch != null -> null
                                    descriptor != null ->
                                        "已导入 ${descriptor.fileName}，已设为当前模型，现在可以去对话页开始聊天了"
                                    else -> "下载完成，导入失败"
                                },
                                activeModelId = if (mismatch == null) {
                                    descriptor?.id ?: it.activeModelId
                                } else {
                                    it.activeModelId
                                },
                            )
                        }
                        return@launch
                    }

                    DownloadManager.STATUS_FAILED -> {
                        activeDownloadId = null
                        _uiState.update {
                            it.copy(
                                downloadName = null,
                                downloadPercent = null,
                                error = progress.reason ?: "下载失败",
                            )
                        }
                        return@launch
                    }
                }
            }
        }
    }

    /** 用户在「正在使用移动数据」对话框里点了「仍然下载」。 */
    fun confirmMeteredDownload() {
        val url = _uiState.value.meteredConfirmUrl ?: return
        meteredConfirmed = true
        _uiState.update { it.copy(meteredConfirmUrl = null) }
        onDownloadFromUrl(url)
    }

    fun dismissMeteredConfirm() {
        meteredConfirmed = false
        _uiState.update { it.copy(meteredConfirmUrl = null) }
    }

    fun setAllowMeteredDownload(allow: Boolean) {
        viewModelScope.launch { container.settingsRepository.setAllowMeteredDownload(allow) }
    }

    fun onCancelDownload() {
        val id = activeDownloadId ?: return
        container.modelDownloader.cancel(id)
        activeDownloadId = null
        _uiState.update { it.copy(downloadName = null, downloadPercent = null, message = "已取消下载") }
    }

    /** DownloadManager 完成后拿到的可能是 file:// 或 content://，两种都要能落到 models 目录。 */
    private suspend fun importDownloaded(localUri: String?): ModelDescriptor? {
        val raw = localUri ?: return null
        val uri = Uri.parse(raw)
        val path = if (raw.startsWith("file://", ignoreCase = true)) uri.path else null
        return if (path != null) {
            container.modelRepository.importFromPath(path)
        } else {
            container.modelRepository.importFromUri(uri)
        }
    }

    /** 扫描内部 / 外部 models 目录里用户自己放的文件。 */
    fun onScanDirectories() {
        viewModelScope.launch {
            container.modelRepository.refresh()
            _uiState.update { it.copy(message = "扫描完成", error = null) }
        }
    }

    fun onSelect(id: String) {
        viewModelScope.launch {
            container.settingsRepository.setActiveModel(id)
            _uiState.update { it.copy(activeModelId = id, message = "已设为当前模型") }
        }
    }

    fun onBackendChange(backend: InferenceBackend) {
        val next = _uiState.value.config.copy(backend = backend)
        _uiState.update { it.copy(backend = backend, config = next) }
        viewModelScope.launch { container.settingsRepository.updateInferenceConfig { next } }
    }

    fun onLoad(id: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(loadingModelId = id, error = null, message = null) }
            val model = container.modelRepository.find(id)
            if (model == null) {
                _uiState.update { it.copy(loadingModelId = null, error = "模型不存在") }
                return@launch
            }
            // 内存闸门：本地推理的内存不足会在 native 层表现为崩溃（用户看到的是闪退），
            // 提前拦下比加载几十秒后崩溃体验好得多。阈值口径见 ModelPresets 顶部注释。
            if (model.sizeBytes > 0L) {
                val required = (model.sizeBytes.toDouble() * 2.0).toLong()
                val available = container.availableMemoryBytes()
                if (available < required) {
                    _uiState.update {
                        it.copy(
                            loadingModelId = null,
                            error = "可用内存不足：需要约 ${formatBytes(required)}，" +
                                "当前可用 ${formatBytes(available)}。请在模型库改用更小/量化更狠的模型，或先释放后台应用。",
                        )
                    }
                    return@launch
                }
            }
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val config = _uiState.value.config
                    val engine = container.engineFactory.create(EngineKind.LOCAL)
                    engine.load(
                        EngineLoadConfig(
                            model = model,
                            remote = null,
                            config = config,
                            cacheDir = container.engineEnvironment.cacheDir,
                            nativeLibraryDir = container.engineEnvironment.nativeLibraryDir,
                            externalFilesDir = container.engineEnvironment.externalFilesDir,
                            sandboxDir = container.engineEnvironment.sandboxDir,
                        ),
                    )
                    engine.capabilities()
                }
            }
            result.onSuccess { capabilities ->
                _uiState.update {
                    it.copy(
                        loadingModelId = null,
                        loadedModelId = id,
                        capabilitiesText = capabilitiesTextOf(capabilities.supportsImage, capabilities.supportsAudio, capabilities.supportsTools, capabilities.supportsThinking, capabilities.supportedBackends),
                        message = "加载完成：${model.fileName}",
                    )
                }
            }.onFailure { throwable ->
                _uiState.update {
                    it.copy(
                        loadingModelId = null,
                        error = "加载失败：${throwable.message ?: "未知错误"}（GPU 不支持时可切到 CPU 重试）",
                    )
                }
            }
        }
    }

    fun onUnload() {
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    container.engineFactory.create(EngineKind.LOCAL).unload()
                }
            }
            _uiState.update { it.copy(loadedModelId = null, capabilitiesText = null, message = "已卸载") }
        }
    }

    fun onDelete(id: String, deleteFile: Boolean) {
        viewModelScope.launch {
            container.modelRepository.remove(id, deleteFile = deleteFile)
            if (_uiState.value.activeModelId == id) {
                container.settingsRepository.setActiveModel(null)
            }
            _uiState.update {
                it.copy(
                    activeModelId = if (it.activeModelId == id) null else it.activeModelId,
                    loadedModelId = if (it.loadedModelId == id) null else it.loadedModelId,
                    message = "已删除",
                )
            }
        }
    }

    /** 能力探测（文件名启发式 + LiteRT-LM Capabilities）。 */
    fun onProbe(id: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(message = "正在探测…", error = null) }
            val updated = container.modelRepository.probe(id)
            _uiState.update {
                if (updated == null) {
                    it.copy(message = null, error = "探测失败")
                } else {
                    it.copy(message = "探测完成：${updated.family} / ${updated.quantization}")
                }
            }
        }
    }

    fun onEditCapabilities(id: String, capabilities: ModelCapabilities) {
        viewModelScope.launch {
            container.modelRepository.setCapabilities(id, capabilities)
            _uiState.update { it.copy(message = "能力位已更新") }
        }
    }

    fun onDismissMessage() {
        _uiState.update { it.copy(message = null, error = null) }
    }

    private fun capabilitiesTextOf(
        image: Boolean,
        audio: Boolean,
        tools: Boolean,
        thinking: Boolean,
        backends: Set<InferenceBackend>,
    ): String {
        val caps = mutableListOf<String>()
        caps.add("文本")
        if (image) caps.add("图片")
        if (audio) caps.add("音频")
        if (tools) caps.add("工具")
        if (thinking) caps.add("思考")
        val backendText = backends.joinToString("/") { it.name }
        return "能力：${caps.joinToString("·")}　后端：$backendText"
    }
}

    /**
     * 校验下载结果是否完整：与预设体积偏差超过 2% 即判定为不完整。
     *
     * 为什么必须做：网络中断/截断会留下不完整的模型文件，而 LiteRT-LM 加载这种文件时
     * 是在 **native 层**失败——用户看到的是 App 闪退，完全不知道是文件坏了。
     * 这里提前拦下并给出明确提示，同时删掉坏文件避免它留在模型库里。
     */
    private fun verifySize(descriptor: ModelDescriptor, preset: ModelPreset?): String? {
        val expected = preset?.sizeBytes ?: return null
        val actual = descriptor.sizeBytes
        if (expected <= 0L || actual <= 0L) return null
        val diff = kotlin.math.abs(actual - expected)
        if (diff > expected * 0.02) {
            runCatching { java.io.File(descriptor.path).delete() }
            return "下载的文件不完整（预期 " + formatBytes(expected) + "，实际 " +
                formatBytes(actual) + "），已删除，请重新下载"
        }
        return null
    }

    /**
     * 导入成功后删除下载目录里的源文件。
     *
     * 因为导入是「复制」，不删的话一个 2.5GB 模型会同时占用 Download 目录和 models 目录，
     * 也就是 5GB——对存储空间紧张的用户是实打实的浪费。
     * 只删除我们自己下载目录里的文件，**绝不碰**用户通过 SAF 导入的原始文件。
     */
    private fun deleteDownloadedSource(localUri: String?, importedPath: String) {
        val raw = localUri ?: return
        val path = if (raw.startsWith("file://")) raw.removePrefix("file://") else raw
        if (path.isBlank() || path == importedPath) return
        val source = java.io.File(path)
        val dlDir = container.downloadDirPath ?: return
        if (source.exists() && source.absolutePath.startsWith(dlDir)) {
            runCatching { source.delete() }
        }
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1_073_741_824L -> "%.1f GB".format(bytes / 1_073_741_824.0)
        bytes >= 1_048_576L -> "%.0f MB".format(bytes / 1_048_576.0)
        else -> "%.0f KB".format(bytes / 1024.0)
    }
