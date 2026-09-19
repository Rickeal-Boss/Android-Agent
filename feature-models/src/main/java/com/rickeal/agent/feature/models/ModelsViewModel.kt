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

/** 下载刚开始的这段时间用 200ms 快速采样，尽快拿到有意义的速度估计；之后降到 1s。 */
private const val DOWNLOAD_FAST_POLL_WINDOW_MILLIS = 3_000L

/** 速率 EMA 的平滑系数（新样本权重）：越小越平滑、越大越灵敏。 */
private const val DOWNLOAD_RATE_EMA_ALPHA = 0.3

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
    /** 下载速率（字节/秒）；null 表示还没测出来（刚起步 / 进度未知），UI 不展示 */
    val downloadSpeedBytesPerSecond: Long? = null,
    /** 预计剩余秒数；null 表示无法估算（速率或总大小未知），UI 不展示 */
    val downloadEtaSeconds: Long? = null,
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

    /**
     * 从系统文件选择器拿到 Uri 后导入。
     *
     * 这里**仍然要复制**（由 repository 的 `importFromUri` 完成）：SAF 给的 `content://`
     * 授权有时效，不能长期引用，必须把内容拷进 `filesDir/models` 才是我们的文件。
     * 这与下载链路不同 —— 下载文件本来就落在 App 自己的 externalFilesDir 里，可以就地登记、零拷贝。
     */
    fun onImportUri(uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(message = "正在导入…", error = null) }
            val descriptor = container.modelRepository.importFromUri(uri)
            if (descriptor == null) {
                _uiState.update { it.copy(message = null, error = "导入失败：无法读取该文件或格式不支持") }
            } else {
                _uiState.update { current ->
                    val active = current.activeModelId ?: descriptor.id
                    current.copy(
                        // SAF 导入会把文件**复制**一份进 filesDir/models：手机上因此同时存在
                        // 「用户原来那份」和「我们的副本」（2.5GB 的模型就是约 5GB）。
                        // 不提示的话，用户会以为我们凭空吃掉了几 GB，所以必须说清可以删原件。
                        message = if (active == descriptor.id) {
                            "已导入 ${descriptor.fileName}，已设为当前模型，现在可以去对话页聊天了（原来的文件可以删掉了）"
                        } else {
                            "已导入 ${descriptor.fileName}（原来的文件可以删掉了）"
                        },
                        error = null,
                        activeModelId = active,
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
     * 完成后**就地登记**（直接把下载落盘的那个文件登记为模型，不再复制进内部目录）。
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
                it.copy(
                    downloadName = fileName,
                    downloadPercent = 0,
                    downloadSpeedBytesPerSecond = null,
                    downloadEtaSeconds = null,
                    error = null,
                    message = "已开始下载：$fileName",
                )
            }
            // 进度轮询节奏：前 3 秒 200ms（尽快算出有意义的速度），之后 1s（省电、够用）。
            // 速度 = 相邻两次采样的字节差 / 时间差，再做 EMA 平滑 —— DownloadManager 的
            // COLUMN_BYTES_DOWNLOADED_SO_FAR 是分块更新的，不平滑数字会剧烈跳动。
            val startedAtMillis = System.currentTimeMillis()
            var lastBytes = 0L
            var lastSampleMillis = startedAtMillis
            var smoothedRate = 0.0
            while (true) {
                val elapsed = System.currentTimeMillis() - startedAtMillis
                delay(if (elapsed < DOWNLOAD_FAST_POLL_WINDOW_MILLIS) 200L else 1000L)
                val progress = container.modelDownloader.progress(downloadId)

                val nowMillis = System.currentTimeMillis()
                val bytes = progress.bytesDownloaded
                val deltaMillis = nowMillis - lastSampleMillis
                // lastBytes > 0 才算：第一次采样没有基准，差值会虚高成一个离谱的速度
                if (deltaMillis > 0L && lastBytes > 0L && bytes >= lastBytes) {
                    val instantRate = (bytes - lastBytes) * 1000.0 / deltaMillis
                    smoothedRate = if (smoothedRate <= 0.0) {
                        instantRate
                    } else {
                        smoothedRate * (1.0 - DOWNLOAD_RATE_EMA_ALPHA) + instantRate * DOWNLOAD_RATE_EMA_ALPHA
                    }
                }
                lastBytes = bytes
                lastSampleMillis = nowMillis

                // 速率为 0（还没真正开始传输）时视为未知，避免 UI 显示 "0 B/s"
                val rate = smoothedRate.toLong().takeIf { it > 0L }
                val etaSeconds = if (rate != null && progress.totalBytes > 0L) {
                    (progress.totalBytes - bytes).coerceAtLeast(0L) / rate
                } else {
                    null
                }

                _uiState.update {
                    it.copy(
                        downloadPercent = progress.percent,
                        downloadSpeedBytesPerSecond = rate,
                        downloadEtaSeconds = etaSeconds,
                    )
                }
                when (progress.status) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        // 就地登记：直接用下载目录里那个文件的绝对路径登记，**不再复制**。
                        // 2~4GB 的模型因此省掉一次完整拷贝（I/O、耗时、以及复制瞬间的 2 倍峰值占用）。
                        val descriptor = registerDownloaded(progress.localUri, fileName, progress.fromCursor)
                        activeDownloadId = null
                        // 完整性校验：半成品模型会在 native 层崩溃（用户只看到闪退），必须在这里拦下
                        val mismatch = descriptor?.let { verifySize(it, preset) }
                        if (descriptor != null && mismatch == null) {
                            // 小白友好：下完直接用，不用再手动选一次模型
                            container.settingsRepository.setActiveModel(descriptor.id)
                            // 注意：这里**绝不能**再删下载目录里的文件 —— 就地登记后它本身就是模型文件。
                            // （旧实现是「先复制进内部目录、再删掉下载源」；现在不复制了，也就不需要删。）
                        }
                        _uiState.update {
                            it.copy(
                                downloadName = null,
                                downloadPercent = null,
                                downloadSpeedBytesPerSecond = null,
                                downloadEtaSeconds = null,
                                error = mismatch
                                    ?: if (descriptor == null) "下载完成，但登记失败" else null,
                                message = when {
                                    mismatch != null -> null
                                    descriptor != null ->
                                        "已加入模型库 ${descriptor.fileName}，已设为当前模型，现在可以去对话页开始聊天了"
                                    else -> "下载完成，登记失败"
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
                                downloadSpeedBytesPerSecond = null,
                                downloadEtaSeconds = null,
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
        _uiState.update {
            it.copy(
                downloadName = null,
                downloadPercent = null,
                downloadSpeedBytesPerSecond = null,
                downloadEtaSeconds = null,
                message = "已取消下载",
            )
        }
    }

    /**
     * 下载完成后**就地登记**：把下载目录里那个文件的绝对路径直接写进模型清单，**不复制**。
     *
     * 为什么可以就地登记：文件落在 `getExternalFilesDir(DIRECTORY_DOWNLOADS)`，是 App 自己的
     * 目录，重启后路径依然有效（不像 SAF 的 `content://` 授权有时效），所以「下载落盘位置」
     * 就是「模型的最终位置」——与 Google 官方 `google-ai-edge/gallery` 的做法一致。
     *
     * 只有确实解析不出真实路径（DownloadManager 在个别版本只给 `content://`）时，才退回
     * `ModelRepository.importFromUri` 复制一份；那是保底路径，正常流程不会走到。
     *
     * [allowPromote] 直接来自 `progress.fromCursor`，**必须透传、不能省**：`.part` 转正是
     * 一次提交动作，只有 cursor 真实读到 `STATUS_SUCCESSFUL` 时才允许。
     *
     * 为什么不能只依赖 `ModelDownloader` 内部的兜底：那道闸门是
     * `localUri != null || 文件名 ∈ confirmedNames`，而 `confirmedNames` 只能表达「**这个名字**
     * 曾经成功过」，区分不了「上一次的成功」与「本次的半截文件」——**同名，但不是同一次下载**。
     * 历史上一度因此把用户中途删掉任务后残留的半截 `.part` 转正并登记，用户一点加载就
     * native 崩溃（表现为闪退）。
     *
     * 那边现在已经补了两层：`enqueue()` 里 `confirmedNames.remove(safeName)`（同名重新入队时
     * 清掉陈旧条目）与 `allowPromote` 默认值 `false`（fail-safe）。所以本参数在当前实现下是
     * 第三层，但它才是**唯一语义精确**的那一层——别删，也别改成位置参数，以免将来形参顺序
     * 变动时静默错位。
     */
    private suspend fun registerDownloaded(
        localUri: String?,
        fileName: String,
        allowPromote: Boolean,
    ): ModelDescriptor? {
        val path = container.modelDownloader.downloadedPath(
            localUri,
            fileName,
            allowPromote = allowPromote,
        )
        if (path != null) return container.modelRepository.importFromPath(path)
        val raw = localUri ?: return null
        return container.modelRepository.importFromUri(Uri.parse(raw), fileName)
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
            // 提前拦下比加载几十秒后崩溃体验好得多。
            //
            // 阈值口径：**优先用预设里按公式推导的 requiredRamBytes**（口径见 ModelPresets 顶部
            // 注释），查不到预设时才退回估算。
            //
            // 这里曾经写死 `sizeBytes * 2.0`，与预设口径完全脱节，两个方向都错：
            //  - 小模型被**低估**：LFM2.5-VL 450M 体积 0.52GB，2× 只有 1.04GB，而实际峰值约 2GB
            //    —— 1.2~1.9GB 可用内存的机器会被**放行**，加载即 native 崩溃。这正是
            //    ModelPresets 注释里已经警告过的「小模型不能按体积线性缩放，必须设 ~2GB 下限」：
            //    注释警告了，代码没照做。
            //  - 大模型被**高估**：Phi-4-mini 体积 3.64GB，2× = 7.28GB，而推导值是 5.6GB
            //    —— 12GB 机型可用内存常年 6~7GB，本来能跑却被拦死。
            if (model.sizeBytes > 0L) {
                val backend = _uiState.value.config.backend
                val estimated = estimateRequiredRamBytes(model.sizeBytes, backend)
                // 预设值只在「与它的推导后端一致」时才是权威的，所以要跟估算取较大值：
                //
                //  8 条预设里 6 条是按 **CPU 口径**（BASIS_CPU）推的，只有 E2B/E4B 的 GPU 变体是
                //  BASIS_GPU。用户把后端从 CPU 切到 GPU/NPU 后（这在模型库里是常见操作，界面自己
                //  就写着"GPU 不支持时可切到 CPU 重试"），实际需求会**更高**（GPU 1.25 > CPU 1.05）。
                //  只读预设值会漏掉这部分 —— 例：Qwen2.5 1.5B 预设 2.4GB（CPU 口径），但 GPU 实际
                //  约 2.58GB；闸门按 2.4 放行 → 加载即 native 崩溃。而修这条之前用的 `×2.0`（2.98）
                //  反而不会漏 —— 也就是说只写 `preset ?: estimate` 会引入一个**方向危险的回归**。
                //
                //  反过来不会多拦：后端与预设口径一致时，预设值恒 ≥ 估算值（预设含 KV cache、
                //  估算不含，这个差额足以覆盖），我按 8 条逐个回算验证过。所以 maxOf 只在
                //  「后端比预设口径更贵」时才生效，正好补缺口。
                val required = ModelPresets.findByFileName(model.fileName)
                    ?.requiredRamBytes
                    ?.let { maxOf(it, estimated) }
                    ?: estimated
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
    /**
     * 校验下载结果是否完整：与预设体积偏差超过 2% 即判定为不完整。
     *
     * 为什么必须做：网络中断/截断会留下不完整的模型文件，而 LiteRT-LM 加载这种文件时
     * 是在 **native 层**失败——用户看到的是 App 闪退，完全不知道是文件坏了。
     * 这里提前拦下并给出明确提示，同时删掉坏文件避免它留在模型库里。
     *
     * 就地登记后 [ModelDescriptor.path] 指向的就是下载目录里的那个文件，
     * 所以这里的删除依然作用于「下载落盘文件」本身，口径没变。
     */
    private fun verifySize(descriptor: ModelDescriptor, preset: ModelPreset?): String? {
        val expected = preset?.sizeBytes ?: return null
        val actual = descriptor.sizeBytes
        if (expected <= 0L || actual <= 0L) return null
        // 只判「明显小于」，不判「明显大于」：
        // sizeBytes 是我们硬编码的估算值，若预设填小了（实测 > 预期），文件其实很可能是完整的，
        // 绝不能因为预设不准就把用户下完的 2~4GB 删掉。双向判定 = 拿估算值当真相。
        if (actual < expected * 0.98) {
            runCatching { java.io.File(descriptor.path).delete() }
            return "下载的文件不完整（预期 " + formatBytes(expected) + "，实际 " +
                formatBytes(actual) + "），已删除，请重新下载"
        }
        return null
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1_073_741_824L -> "%.1f GB".format(bytes / 1_073_741_824.0)
        bytes >= 1_048_576L -> "%.0f MB".format(bytes / 1_048_576.0)
        else -> "%.0f KB".format(bytes / 1024.0)
    }

    /**
     * 没有预设可对时的内存需求估算（SAF 导入的自定义模型、或因重名落成 `name-1.ext` 的下载）。
     *
     * 口径照 `ModelPresets` 顶部注释简化：`(W × f_backend + O) × 1.25`，其中
     * `f_backend` = CPU 1.05 / GPU 1.25 / NPU **未实测**（理由见下面 `when` 里的注释），
     * `O = max(200MB, 0.12 × W)`。
     *
     * **2GB 下限是必须的，不是保险丝**：小模型的固定开销（运行时 + prefill 激活 + App 自身）
     * 不随权重线性缩放，纯按体积算会把「0.5GB 的模型」误判成「1GB 内存就能跑」，然后在 native
     * 层崩溃。这正是预设里 LFM2.5-VL 450M 的 `requiredRamBytes` 取 2.0GB 而不是按体积算的原因。
     *
     * 已知偏乐观之处：**不含 KV cache**（需要层数 / kv 头数 / head_dim，只有真正打开模型文件
     * 才探得到），所以长上下文场景下这个值是偏低的。带预设的模型走精确值，这里只服务兜底路径。
     */
    private fun estimateRequiredRamBytes(weights: Long, backend: InferenceBackend): Long {
        val factor = when (backend) {
            InferenceBackend.CPU -> 1.05
            InferenceBackend.GPU -> 1.25
            // NPU：**没有实测数据，所以刻意不写一个"看起来精确"的系数。**
            //
            // 这里原来写 1.15（比 GPU 的 1.25 还低），那是**危险方向**：NPU 的峰值约等于
            // 「权重 + N × HTP scratch」，而 scratch 是 GB 量级的，所以 NPU 的实际需求几乎必然
            // **高于** GPU。用比 GPU 更低的系数会往「低估」走 —— 而低估的结果是**加载时崩溃**，
            // 不是被拦下来（内存闸门放行 → native OOM → 用户看到闪退）。
            //
            // 因此：在真机测出 HTP scratch 开销之前，取「**不低于 GPU**」，并明确这是**保守占位**、
            // 不是校准值。绝不为 NPU 写一个未经验证的具体倍率（例如 3.0）——那会把一个编出来的
            // 数字伪装成已知事实，后人再也不会去质疑它。
            InferenceBackend.NPU -> 1.25
        }
        val overhead = maxOf(200L * 1024 * 1024, (weights * 0.12).toLong())
        val raw = ((weights * factor + overhead) * 1.25).toLong()
        return maxOf(raw, 2L * 1024 * 1024 * 1024)
    }
}
