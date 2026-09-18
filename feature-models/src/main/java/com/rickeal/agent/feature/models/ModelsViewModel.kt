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
    val message: String? = null,
    val error: String? = null,
    val capabilitiesText: String? = null,
)

class ModelsViewModel(
    private val container: AppContainer,
) : ViewModel() {

    private var activeDownloadId: Long? = null

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
                        _uiState.update {
                            it.copy(
                                downloadName = null,
                                downloadPercent = null,
                                error = if (descriptor == null) "下载完成，但导入失败" else null,
                                message = descriptor?.let { m -> "已导入 ${m.fileName}" }
                                    ?: "下载完成，导入失败",
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
