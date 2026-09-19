package com.rickeal.agent.feature.models

import android.app.DownloadManager
import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rickeal.agent.core.data.AppContainer
import com.rickeal.agent.core.engine.EngineLoadConfig
import com.rickeal.agent.core.model.AgentLogStore
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

/**
 * **只作用于「查不到预设」那一支**的尾部余量，别当成全局安全系数用。
 *
 * ## 来历
 *
 * 预设式是 `(W·f + KV(n) + O) × 1.25`，KV 项是**显式算进去的**；而
 * [ModelsViewModel.estimateRequiredRamBytes] 这条估算式**没有 KV 项**（层数 / kv 头数 /
 * head_dim 只有真正打开模型文件才探得到），所以它系统性偏乐观。
 *
 * 令两边相等 `(W·f+O)·c = (W·f+KV+O)·1.25`，得 `c = 1.25 × (1 + KV/(W·f+O))`。
 * 按 8 条预设反算右边，最大缺口出现在 E2B·GPU（KV 0.318 GiB / 底 2.562 GiB）= **1.4052**，
 * 向上取整即 1.41。
 *
 * ## 作用域（改之前先读这段）
 *
 * **只有 `ModelPresets.findByFileName` 返回 null 时**才传它：`name-1.ext` 重名下载、
 * SAF 导入的自定义文件 —— 也就是我们对该模型一无所知的那条路。
 *
 * 有预设时**绝不能**传：预设才是权威值，估算只用来兜「用户把后端从 CPU 切到 GPU/NPU」
 * 那一支（`maxOf`）。多乘一次会把闸门抬到预设之上，把刚消掉的「大模型误拦」又加回来 ——
 * 例如 Phi-4-mini 会从 5.6 被抬到 6.0 GiB，而且卡片上「≥ 5.6 GB」的文案会和实际闸门对不上。
 */
private const val NO_PRESET_KV_COMPENSATION = 1.41

/**
 * 内存估算的绝对下限（2 GiB）。
 *
 * 小模型的固定开销（运行时 + prefill 激活 + App 自身）不随权重线性缩放，纯按体积算会把
 * 「0.5GB 的模型」误判成「1GB 内存就能跑」，然后在 native 层崩溃。Google 官方锚点也印证
 * 这一点：Gemma3-1B q4 权重 0.52GB → 实测峰值 2.0GB（≈3.9×）。
 *
 * **下限作用在乘完尾部余量之后**：尾部余量补的是 KV(n)，而 KV 同样不适用于「固定开销」
 * 那部分，先把下限乘大反而会让小模型被误判。已知结果：无预设的**小模型**因此仍然是
 * 2.00 GiB，与预设 LFM2.5-VL 450M 的 2.00 持平 —— 自定义模型不会比同体积的预设模型更松，
 * 也不会更严。（若把余量乘在下限之后，下限本身也会被放大 —— 按 1.41 算就是 2.82 GiB；
 * 那不是本口径，别照着改。）
 */
private const val MIN_REQUIRED_RAM_BYTES = 2L * 1024 * 1024 * 1024

/**
 * 存储闸门拦下一次下载时带出的信息，供 UI 渲染「仍要下载」确认框。
 *
 * ## 为什么和 [MemoryGateBlock] 是两个独立的 block，而不是合成一个
 *
 * 两个闸门用**同一类数据**（`ModelPresets.sizeBytes` / `requiredRamBytes` 都是手写估算值）
 * 加一层余量，所以哲学必须一致 —— 都是「警告 + 用户可绕过」。但它们的**触发时机**与
 * **失败代价**完全不同，合成一个会让文案没法同时说清两件事：
 *
 * | | 触发时机 | 失败代价 |
 * |---|---|---|
 * | 本 block | 下载**前** | 下载中途失败 → 白耗几 GB 流量与等待（不崩溃） |
 * | [MemoryGateBlock] | 加载**前** | 加载时 native OOM → **闪退**，未保存的会话可能丢 |
 *
 * 「继续」在两个场景下意味着不同的风险，所以文案也必须不同。别为了少一个状态字段而合并。
 *
 * ## UI 接法
 *
 * `storageGateBlock != null` 时弹确认框；两个按钮分别调
 * [ModelsViewModel.onDownloadIgnoringStorageGate] 与 [ModelsViewModel.dismissStorageGate]。
 * 正文直接用 [riskText]。
 */
@Immutable
data class StorageGateBlock(
    /** 触发这次确认的下载地址。用户点「仍要下载」时用它原样重试，不会丢参数。 */
    val url: String,
    val fileName: String,
    /** 估算需要的空间（字节）= `preset.sizeBytes × 1.2 + 200MB`。估算值，非实测值。 */
    val requiredBytes: Long,
    /** 检测时的可用空间（字节）。 */
    val availableBytes: Long,
    /** 给 UI 直接渲染的正文，含真实代价说明。 */
    val riskText: String,
)

/**
 * 内存闸门拦下一次加载时带出的信息，供 UI 渲染「仍要加载」确认框。
 *
 * ## 为什么估算值必须配一个出口
 *
 * [requiredBytes] 是**估算值，不是实测值**（口径见 `ModelPresets` 顶部注释：连
 * `sizeBytes` 都还没在真机上校准过）。在一个自己标注为估算值的数字上做**不可绕过**的
 * 决策，是拿精度不足的尺子去堵死用户 —— 估算偏保守时机器其实跑得动，用户只会以为
 * 「我的手机不行」，而且没有任何自救手段。
 *
 * 一致性上也是这么定的：NPU 门控（`DeviceCapability`，阈值同样是估计值）走的就是
 * warning-only。内存阈值既然是估算，就不该比它更硬。
 *
 * 所以闸门从「硬拦」降级为「警告 + 仍要加载」：**不是放开保护，是不用估算值替用户
 * 做不可逆的决定**。风险由 [riskText] 讲清楚，决定权交回用户。
 *
 * ## UI 接法
 *
 * `memoryGateBlock != null` 时弹确认框；两个按钮分别调 [ModelsViewModel.onLoadIgnoringMemoryGate]
 * 与 [ModelsViewModel.dismissMemoryGate]。正文直接用 [riskText]（文案放数据层是为了保证
 * 「风险说明」不会被 UI 漏掉）。
 */
@Immutable
data class MemoryGateBlock(
    val modelId: String,
    val fileName: String,
    /** 估算需要的可用内存（字节）。估算值，非实测值。 */
    val requiredBytes: Long,
    /** 检测时的可用内存（字节）。 */
    val availableBytes: Long,
    /** 给 UI 直接渲染的正文，含风险说明。 */
    val riskText: String,
)

/** Gemma 授权闸门拦下的待办动作。用户接受条款后按这里的内容**原样重放**。 */
@Immutable
sealed interface PendingModelAction {
    /**
     * 待重放的下载。
     *
     * 存 URL 而不是让 UI 再传一次：用户确认的一定是他刚才点的那一个模型，
     * 中途也不会因为输入框被改而变成下载别的东西（与 [StorageGateBlock.url] 同一考虑）。
     */
    @Immutable
    data class Download(val url: String) : PendingModelAction

    /** 待重放的加载。 */
    @Immutable
    data class Load(val modelId: String) : PendingModelAction
}

/**
 * Gemma 授权闸门拦下一次「下载 / 加载 Gemma 模型」时带出的信息。
 *
 * ## 与存储 / 内存闸门的区别（别照抄那两个的文案）
 *
 * | | 存储 / 内存闸门 | 本 block |
 * |---|---|---|
 * | 拦的是什么 | **风险**（估算值可能不够） | **授权**（条款还没接受） |
 * | 能不能绕过 | 能，用户看完风险可「仍要…」 | **不能**，只能去接受条款 |
 *
 * 前两个的哲学是「估算值不足以下不可逆的判决，所以讲清代价、把决定权交回用户」；
 * 授权不一样 —— 用户没有「不接受的自由但还是要用」这个选项，所以这里**没有**「忽略闸门」
 * 的入口，只有「同意并继续 / 暂不」。把两者做成同一套「仍要继续」的按钮，
 * 会把一份法律文件的接受降级成一句风险确认。
 *
 * ## 只拦 Gemma 系模型
 *
 * 判据在 [ModelLicenses]。8 条预设里另外 5 条与 Gemma Terms 无关，让下载它们的用户
 * 去接受 Google 的条款是捆绑 —— 条款的适用边界必须与模型的归属一致。
 */
@Immutable
data class GemmaTermsBlock(
    /** 被拦下的动作；用户接受授权后原样重放。 */
    val pending: PendingModelAction,
    /** 展示用：用户是在为哪个模型同意条款。没有它，用户不知道自己同意的边界在哪。 */
    val modelLabel: String,
)

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
    /** 非空表示：存储闸门拦下了一次下载，等用户决定是否「仍要下载」 */
    val storageGateBlock: StorageGateBlock? = null,
    /** 非空表示：内存闸门拦下了一次加载，等用户决定是否「仍要加载」 */
    val memoryGateBlock: MemoryGateBlock? = null,
    /** 非空表示：Gemma 授权闸门拦下了一次下载/加载，等用户接受条款 */
    val gemmaTermsBlock: GemmaTermsBlock? = null,
    val capabilitiesText: String? = null,
)

class ModelsViewModel(
    private val container: AppContainer,
) : ViewModel() {

    private var activeDownloadId: Long? = null

    /** 用户已在「移动数据下载」确认框里点过继续（一次性，用完即清） */
    private var meteredConfirmed: Boolean = false

    /**
     * 用户已在「存储空间可能不足」确认框里点过继续（一次性，用完即清）。
     *
     * 两个确认标志都是**在「通过全部下载前检查、真正入队」时才消费**，见
     * [onDownloadFromUrl] 里的注释 —— 提前消费会让「先过 A 闸门、再被 B 闸门拦下、
     * 回头重走 A」把 A 的确认吃掉，用户会重复看到同一个确认框。
     */
    private var storageConfirmed: Boolean = false

    /** 来自设置的持久开关：允许用移动数据下载（开启后不再弹确认） */
    private var allowMeteredSetting: Boolean = false

    /**
     * Gemma 授权是否已接受。镜像 DataStore 的 `is_gemma_terms_accepted`。
     *
     * 初值是 false 且判据写成 `!= true`（fail-closed）：读不到时按「未接受」处理。
     * 授权闸门宁可多问一次也不能在状态未知时放行 —— 多问一次用户点一下就好，
     * 漏拦则是在没有授权的情况下使用了别人的模型。
     * （与「首启闸门用 null 避免闪现」是相反的取舍，因为那里的代价是体验，这里是合规。）
     */
    private var gemmaTermsAccepted: Boolean = false

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

        viewModelScope.launch {
            container.settingsRepository.isGemmaTermsAccepted.collect { accepted ->
                gemmaTermsAccepted = accepted
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
                // ERROR：用户可见的终态（下面 error 就是给他看的）。
                // 这里拿不到文件名（displayName 是在 repository 里查的，失败时还没查出来），
                // 所以只记 Uri 的 **scheme / authority**：它能区分「用户是从哪个 provider 选的文件」
                // （文件管理器 / 下载管理 / 网盘 …），这是导入失败最常见的分叉点。
                // **不记 path** —— 完整路径可能含用户目录名与真实文件名。
                AgentLogStore.error(
                    "模型导入失败：无法读取或复制该 Uri（scheme=${uri.scheme}，authority=${uri.authority}）",
                )
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
            // 每次下载尝试都从「没有任何确认」开始，避免上一次的 block 残留在界面上
            _uiState.update { it.copy(storageGateBlock = null) }
            val fileName = trimmed.substringBefore('?').substringAfterLast('/').ifBlank { "model.litertlm" }

            // Gemma 授权闸门：**放在所有「代价类」闸门之前**。
            // 流量/存储问的是「这次下载要花多少」，授权问的是「你凭什么能用这个模型」——
            // 后者是前置条件，先问代价再问资格是本末倒置（用户可能压根没资格下载，
            // 那一堆流量确认就白问了）。
            //
            // 只拦 Gemma 系模型（判据见 ModelLicenses）：另外 5 条预设与 Gemma Terms 无关，
            // 拦它们就是捆绑。
            if (ModelLicenses.requiresGemmaTerms(trimmed) && !gemmaTermsAccepted) {
                _uiState.update {
                    it.copy(
                        gemmaTermsBlock = GemmaTermsBlock(
                            pending = PendingModelAction.Download(trimmed),
                            modelLabel = ModelPresets.findByUrl(trimmed)?.label ?: fileName,
                        ),
                        error = null,
                        message = null,
                    )
                }
                return@launch
            }

            // 计量网络（通常是移动数据）保护：GB 级文件用流量下的代价太高。
            // UI 上的「建议连 Wi-Fi」只是一句文案，不实际检查等于没有。
            if (!meteredConfirmed && !allowMeteredSetting &&
                withContext(Dispatchers.IO) { container.isMeteredNetwork() }
            ) {
                _uiState.update { it.copy(meteredConfirmUrl = trimmed, error = null, message = null) }
                return@launch
            }

            // 下载前检查存储空间：GB 级文件下到一半失败，代价太高。
            //
            // 但和内存闸门一样**不硬拦**：`sizeBytes` 是手写估算值，再乘 1.2 的余量是
            // 「估上加估」。用它做不可绕过的决策，一旦某个预设填偏大，用户就会被
            // 「存储空间不足」直接挡在下载入口外、毫无自救手段 —— 和内存侧被推翻的那个
            // 失败模式同构。所以这里只警告 + 给出口，风险由 riskText 讲清。
            val preset = ModelPresets.findByUrl(trimmed)
            if (preset != null && !storageConfirmed) {
                val need = (preset.sizeBytes * 1.2 + 200L * 1024 * 1024).toLong()
                // StatFs 是阻塞 I/O，别占着主线程
                val available = withContext(Dispatchers.IO) { container.availableStorageBytes() }
                if (available < need) {
                    _uiState.update {
                        it.copy(
                            storageGateBlock = StorageGateBlock(
                                url = trimmed,
                                fileName = fileName,
                                requiredBytes = need,
                                availableBytes = available,
                                // 存储不足的代价和内存不足**不同**：不是崩溃，是下到一半失败、
                                // 那几 GB 白下。文案必须讲这个，用户才知道「继续」意味着什么。
                                riskText = "存储空间可能不够：这个模型大约需要 ${formatBytes(need)}，" +
                                    "当前可用 ${formatBytes(available)}。" +
                                    "继续下载可能会下到一半就失败，那几 GB 就白下了" +
                                    "（用移动数据的话流量照常扣）。建议先清理空间再下载。",
                            ),
                            error = null,
                            message = null,
                        )
                    }
                    return@launch
                }
            }

            // 两个一次性确认都在**这里**才消费：它们代表「用户已同意这一串检查里的每一项」，
            // 而上面任何一个闸门提前 return 都意味着这一串还没走完。若在各自检查后立刻清，
            // 就会出现「先确认流量 → 被存储闸门拦下 → 回头重走时流量确认已被吃掉 → 又弹一次
            // 流量确认框」的来回弹窗。
            meteredConfirmed = false
            storageConfirmed = false

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
                        // 体积体检只产出一句提示，**不产出判决**：DM 说下完了就照常登记、照常设为当前模型。
                        // 判据与「为什么不能删」见 sizeHint 的注释（旧实现在这里形成过下载死循环）。
                        val hint = descriptor?.let {
                            sizeHint(
                                descriptor = it,
                                preset = preset,
                                downloadedBytes = progress.bytesDownloaded,
                                totalBytes = progress.totalBytes,
                            )
                        }
                        if (descriptor != null) {
                            // 小白友好：下完直接用，不用再手动选一次模型
                            container.settingsRepository.setActiveModel(descriptor.id)
                            // 注意：这里**绝不能**再删下载目录里的文件 —— 就地登记后它本身就是模型文件。
                            // （旧实现是「先复制进内部目录、再删掉下载源」；现在不复制了，也就不需要删。）
                        }
                        if (descriptor == null) {
                            // ERROR：用户可见的终态，而且是**最难自查**的一种 ——
                            // 文件明明下完了却用不了。所以这里要留下「为什么没登记上」的判据三件套：
                            //  1. fromCursor —— cursor 有没有真的报过成功（没有 ⇒ 磁盘上很可能还停在 .part）；
                            //  2. localUri 有没有给 —— 少数机型/版本它不可靠，是解析失败的常见原因；
                            //  3. 实际下到的字节数 —— 能区分「根本没开始」与「下到一半」。
                            // 不记 localUri 本体（它是完整落盘路径），也不记 URL。
                            AgentLogStore.error(
                                "模型登记失败：下载已完成但无法登记 $fileName" +
                                    "（fromCursor=${progress.fromCursor}，" +
                                    "localUri=${if (progress.localUri == null) "null" else "有"}，" +
                                    "已下载 ${formatBytes(progress.bytesDownloaded)}）",
                            )
                        }
                        _uiState.update {
                            it.copy(
                                downloadName = null,
                                downloadPercent = null,
                                downloadSpeedBytesPerSecond = null,
                                downloadEtaSeconds = null,
                                error = if (descriptor == null) "下载完成，但登记失败" else null,
                                message = when {
                                    descriptor == null -> "下载完成，登记失败"
                                    hint != null ->
                                        "已加入模型库 ${descriptor.fileName}，已设为当前模型。$hint"
                                    else ->
                                        "已加入模型库 ${descriptor.fileName}，已设为当前模型，现在可以去对话页开始聊天了"
                                },
                                activeModelId = descriptor?.id ?: it.activeModelId,
                            )
                        }
                        return@launch
                    }

                    DownloadManager.STATUS_FAILED -> {
                        activeDownloadId = null
                        // 终态：本次下载不会再有人继续写，清掉磁盘上的半截 .part（否则就是几 GB 的孤儿文件）。
                        // 只在这里 / onCancelDownload 调，**绝不能放进上面的每秒轮询**。
                        container.modelDownloader.discardPartFiles(fileName)
                        // ERROR：用户可见的终态。记原因码 + 已下载/总字节，是为了能区分两类失败：
                        // 「0 KB 就失败」（连接都没建立，多是地址/网络问题）与
                        // 「下了 2.1 GB 才失败」（中断或磁盘写满）—— 这两者的处置完全不同。
                        // 不记 URL（直链可能带 token）与落盘路径。
                        AgentLogStore.error(
                            "模型下载失败：$fileName（${progress.reason ?: "无原因码"}，" +
                                "已下载 ${formatBytes(progress.bytesDownloaded)} / 共 ${formatBytes(progress.totalBytes)}）",
                        )
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

    /**
     * 用户在「存储空间可能不够」提示里点了「仍要下载」。
     *
     * 只有从 [StorageGateBlock] 走过来的请求才允许绕过存储检查 —— 也就是说用户确实看过
     * 代价说明并做了选择。没有待确认项时直接忽略，避免被误调用成「无条件跳过存储检查」。
     *
     * 用 [StorageGateBlock.url] 原样重试（而不是让 UI 再传一次），这样用户确认的一定是
     * 他看到的那条链接，中途也不会因为输入框被改而变成下载别的东西。
     */
    fun onDownloadIgnoringStorageGate() {
        val blocked = _uiState.value.storageGateBlock ?: return
        storageConfirmed = true
        _uiState.update { it.copy(storageGateBlock = null) }
        onDownloadFromUrl(blocked.url)
    }

    /**
     * 用户在「存储空间可能不够」提示里点了取消。
     *
     * 这里**同时清掉两个一次性确认**：用户取消的是「这一次下载尝试」，而流量确认
     * （[meteredConfirmed]）是同一次尝试里的前置步骤 —— 只清存储那个的话，用户下次点下载
     * 会因为流量确认还在而跳过流量提示，等于悄悄少问了一次。
     */
    fun dismissStorageGate() {
        storageConfirmed = false
        meteredConfirmed = false
        _uiState.update { it.copy(storageGateBlock = null) }
    }

    fun setAllowMeteredDownload(allow: Boolean) {
        viewModelScope.launch { container.settingsRepository.setAllowMeteredDownload(allow) }
    }

    fun onCancelDownload() {
        val id = activeDownloadId ?: return
        // cancel / discardPartFiles 都是 suspend（内部有磁盘 IO），必须进协程。
        // discardPartFiles 只在这里调：此刻下载已进入终态，磁盘上的 .part 不会再有人继续写。
        val name = _uiState.value.downloadName
        viewModelScope.launch {
            container.modelDownloader.cancel(id)
            if (!name.isNullOrBlank()) container.modelDownloader.discardPartFiles(name)
        }
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
        loadModel(id, ignoreMemoryGate = false)
    }

    /**
     * 用户在「内存可能不足」提示里点了「仍要加载」。
     *
     * 只有从 [MemoryGateBlock] 走过来的请求才允许绕过闸门 —— 也就是说用户确实看过
     * 风险说明并做了选择。没有待确认项时直接忽略，避免被误调用成「无条件跳过闸门」。
     */
    fun onLoadIgnoringMemoryGate() {
        val blocked = _uiState.value.memoryGateBlock ?: return
        _uiState.update { it.copy(memoryGateBlock = null) }
        loadModel(blocked.modelId, ignoreMemoryGate = true)
    }

    /** 用户在「内存可能不足」提示里点了取消。 */
    fun dismissMemoryGate() {
        _uiState.update { it.copy(memoryGateBlock = null, message = null) }
    }

    /**
     * 用户在 Gemma 授权提示里点了「同意并继续」。
     *
     * 只有从 [GemmaTermsBlock] 走过来的请求才会被重放 —— 也就是说用户确实看过条款并
     * 作出了接受的意思表示。没有待办动作时直接忽略，避免被误调用成「无条件放行」。
     *
     * 这里**没有**「忽略闸门」的出口（对比 [onLoadIgnoringMemoryGate]）：内存闸门可以绕过，
     * 因为它的判据是估算值；授权不能绕过，因为它的判据是「有没有拿到许可」。
     */
    fun onGemmaTermsAccepted() {
        val block = _uiState.value.gemmaTermsBlock ?: return
        // 先本地置位、再重放：DataStore 的写入与回读都是异步的，等它回来会把刚重放的
        // 动作又拦一次 —— 用户就会看到同一个对话框连弹两遍。
        gemmaTermsAccepted = true
        viewModelScope.launch { container.settingsRepository.setGemmaTermsAccepted(true) }
        _uiState.update { it.copy(gemmaTermsBlock = null) }
        when (val pending = block.pending) {
            is PendingModelAction.Download -> onDownloadFromUrl(pending.url)
            // 内存闸门重新按未确认走：用户还没看过那一步的说明。
            is PendingModelAction.Load -> loadModel(pending.modelId, ignoreMemoryGate = false)
        }
    }

    /** 用户在 Gemma 授权提示里点了「暂不」。只是放弃这一次动作，不改变授权状态。 */
    fun dismissGemmaTerms() {
        _uiState.update { it.copy(gemmaTermsBlock = null, message = null) }
    }

    private fun loadModel(id: String, ignoreMemoryGate: Boolean) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(loadingModelId = id, error = null, message = null, memoryGateBlock = null)
            }
            val model = container.modelRepository.find(id)
            if (model == null) {
                _uiState.update { it.copy(loadingModelId = null, error = "模型不存在") }
                return@launch
            }
            // Gemma 授权闸门：**加载是「使用」Gemma 模型的实际动作**，所以这里同样要拦。
            // 只拦下载是不够的 —— 用户完全可能通过 SAF 导入、或从旧版本遗留的文件拿到
            // Gemma 权重，那时下载闸门根本没机会触发。
            //
            // 同样排在内存闸门之前：先确认「能用」，再讨论「跑不跑得动」。
            if (ModelLicenses.requiresGemmaTerms(model) && !gemmaTermsAccepted) {
                _uiState.update {
                    it.copy(
                        // 必须复位：loadModel 入口已经把它置成 id，不复位卡片会一直转圈。
                        loadingModelId = null,
                        gemmaTermsBlock = GemmaTermsBlock(
                            pending = PendingModelAction.Load(id),
                            modelLabel = model.displayName.ifBlank { model.fileName },
                        ),
                        error = null,
                        message = null,
                    )
                }
                return@launch
            }
            // 内存闸门：本地推理的内存不足会在 native 层表现为崩溃（用户看到的是闪退），
            // 提前提醒比加载几十秒后崩溃体验好得多。
            //
            // 但**不硬拦**：阈值是估算值，估算偏保守时机器其实跑得动，硬拦会让用户以为
            // 「我的手机不行」且毫无自救手段。所以这里只把信息放进 memoryGateBlock，
            // 由用户决定是否仍要加载（理由见 MemoryGateBlock 的注释）。
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
            if (model.sizeBytes > 0L && !ignoreMemoryGate) {
                val backend = _uiState.value.config.backend
                val preset = ModelPresets.findByFileName(model.fileName)
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
                //
                // 尾部余量按支路分开，**补偿只落在无预设那一支**（作用域理由见
                // NO_PRESET_KV_COMPENSATION 的注释）：
                //  - 有预设：预设已是权威值，估算保持默认 1.25，只兜后端切换；
                //    多乘一次 1.41 会把闸门抬到预设之上（Phi-4-mini 5.6 → 6.0 GiB）。
                //  - 无预设：估算式没有 KV 项，用 1.41 把这块缺口补上。
                val required = if (preset != null) {
                    maxOf(preset.requiredRamBytes, estimateRequiredRamBytes(model.sizeBytes, backend))
                } else {
                    estimateRequiredRamBytes(
                        weights = model.sizeBytes,
                        backend = backend,
                        tail = NO_PRESET_KV_COMPENSATION,
                    )
                }
                val available = container.availableMemoryBytes()
                if (available < required) {
                    val riskText = "加载这个模型大约需要 ${formatBytes(required)} 可用内存，" +
                        "当前只有 ${formatBytes(available)}。" +
                        "强行加载可能会失败并闪退，正在进行的对话如果还没保存可能会丢失。"
                    _uiState.update {
                        it.copy(
                            loadingModelId = null,
                            memoryGateBlock = MemoryGateBlock(
                                modelId = id,
                                fileName = model.fileName,
                                requiredBytes = required,
                                availableBytes = available,
                                riskText = riskText,
                            ),
                            // 兜底显示：确认框还没接上时，至少要解释「点了为什么没反应」，
                            // 否则闸门从「报错」降级成「静默无响应」是纯粹的退步。
                            // UI 接好后这条 message 可以删（对话框直接用 memoryGateBlock.riskText）。
                            message = riskText,
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
                // ERROR：这是**用户可见的终态**（下面 error 就是给用户看的），而且这条路径
                // 完全不经 AgentRunner —— 它是诊断页能查到「模型加载失败」的**唯一**来源，
                // 缺了这行，用户来诊断页查「为什么加载不了」时黑匣子里是空的。
                // 级别分界：被内存/存储闸门拦下是 warn（我们兜住了，用户只是被问了一次），
                // 这里已经没有任何恢复手段，才记 error。
                //
                // 只记「文件名 + 后端 + 异常类型/消息」三样定位信息：
                //  - 不记完整路径（可能含用户目录名），端点名 / URL / API Key 一律不记；
                //  - throwable.message 是自由文本，可能很长，而 AgentLogStore 单条上限 400 字符，
                //    所以先把它截断在尾部 —— 保证「哪个文件、哪个后端」不会被挤掉。
                AgentLogStore.error(
                    "模型加载失败：${model.fileName}（后端 ${_uiState.value.config.backend}，" +
                        "${throwable.javaClass.simpleName}: ${throwable.message?.take(120) ?: "无"}）",
                )
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
            // WARN：探测失败**不影响使用**（模型照常能加载、能对话），所以不是 error。
            // 记在 update 之外，避免把副作用塞进状态更新 lambda。
            // 只记 id（不透明标识），不记路径 —— 探测失败最常见的原因就是文件已被删除或移动。
            if (updated == null) {
                AgentLogStore.warn("模型能力探测失败：id=$id（文件可能已不存在或打不开）")
            }
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
     * 下载完成后的体积体检。**返回的是一句提示，不是判决；本函数永远不会删文件。**
     *
     * ## 为什么不能拿体积当删除依据（这曾经是个死循环）
     *
     * 旧实现是「实际体积 < 预设 `sizeBytes` × 0.98 → 删掉文件并报错」。问题在于
     * `sizeBytes` 是**手写进源码的估算值**，谁也没在真机上验过。一旦某个预设填大了
     * （例如上游换成了更大的打包，或当初就是估的），用户每次下完 2~4GB 都会被判成
     * 「不完整」→ 删除 → 提示重新下载 → 再下再删。用户永远下不完一个模型，而且
     * 每次都要重新花流量和时间。**拿估算值当真相，就必然出现这种循环。**
     *
     * ## 完整性只认 DownloadManager 的账本
     *
     * `bytesDownloaded == totalBytes` 是唯一能区分「下完了」与「下了一半」的信息源，
     * 它是真实传输过程的记录，不是估算。DM 说下完了，我们就认为下完了：
     * 照常登记、照常设为当前模型，`sizeBytes` 只用来提示「上游仓库可能换过文件」。
     *
     * ## 拿不到 DM 总数时
     *
     * 才退回体积比对，且阈值放宽到 0.70、**只警告不删**。放宽是因为这个兜底同样
     * 建立在估算值上，宁可漏报（让用户自己去试加载）也不能误报（删掉一个可能是好的文件）。
     *
     * 就地登记后 [ModelDescriptor.path] 指向的就是下载目录里的那个文件，
     * 所以这里曾经删的确实是「下载落盘文件」本身——口径没错，错的是判据。
     */
    private fun sizeHint(
        descriptor: ModelDescriptor,
        preset: ModelPreset?,
        downloadedBytes: Long,
        totalBytes: Long,
    ): String? {
        val expected = preset?.sizeBytes ?: return null
        val actual = descriptor.sizeBytes
        if (expected <= 0L || actual <= 0L) return null

        // 偏小 30% 以上 → 更可能是真的没下完（截断）
        val looksTruncated = actual < expected * 0.70
        // 偏离 ±2% → 更可能是上游换了打包（估算值本身的误差不会到这个量级）
        val looksChanged = actual < expected * 0.98 || actual > expected * 1.02
        // DownloadManager 的账本：它说下完了，就不能因为体积不符而拒绝登记
        val dmSaysComplete = totalBytes > 0L && downloadedBytes >= totalBytes

        return when {
            looksTruncated ->
                "警告：文件体积（${formatBytes(actual)}）明显小于预期（${formatBytes(expected)}），" +
                    "可能没有下载完整。建议删掉后重新下载；如仍要使用，加载失败时请删除它。"
            looksChanged && dmSaysComplete ->
                "提示：文件体积（${formatBytes(actual)}）与预期（${formatBytes(expected)}）不一致，" +
                    "上游仓库可能换过文件。如果加载失败，请删掉后重新下载。"
            // 拿不到 DM 总数时，±2% 的小偏差不足以下结论（预期值本身就是估算），
            // 所以只有「明显偏小」才提醒 —— 阈值放宽到 0.70 的理由。
            else -> null
        }
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1_073_741_824L -> "%.1f GB".format(bytes / 1_073_741_824.0)
        bytes >= 1_048_576L -> "%.0f MB".format(bytes / 1_048_576.0)
        else -> "%.0f KB".format(bytes / 1024.0)
    }

    /**
     * 内存需求估算：`max((W × f_backend + O) × [tail], MIN_REQUIRED_RAM_BYTES)`，其中
     * `f_backend` = CPU 1.05 / GPU 1.25 / NPU **未实测**（理由见下面 `when` 里的注释），
     * `O = max(200MB, 0.12 × W)`。
     *
     * [tail] 是尾部余量，**按支路分开传**，两处用途语义不同，别混：
     *  1. **有预设**：用默认 `1.25`（= Android 安全余量：无 swap + LMK + App 自身 150~300MB），
     *     调用方再和预设取 `maxOf`。返回值只用来兜「用户把后端从 CPU 切到 GPU/NPU」那一支 ——
     *     预设已经显式算了 KV(n)，所以这里**不能**传 [NO_PRESET_KV_COMPENSATION]，
     *     否则闸门会高于预设值（Phi-4-mini 5.6 → 6.0 GiB，卡片文案与实际闸门对不上）。
     *  2. **无预设**：传 [NO_PRESET_KV_COMPENSATION]（1.41），补上这条式子缺掉的 KV 项。
     *     缺口存在的理由与反算过程见该常量的注释。
     *
     * 余量作用在 [MIN_REQUIRED_RAM_BYTES] **之前**：下限代表的是不随权重缩放的固定开销，
     * 而余量补的是 KV(n)，两者不该相乘（否则小模型的下限会被抬成 2.82 GiB 而不是 2.00）。
     *
     * 已知偏乐观之处：**不含 KV cache**（需要层数 / kv 头数 / head_dim，只有真正打开模型文件
     * 才探得到），所以长上下文场景下这个值是偏低的 —— 缺口由上面第 2 条补。
     */
    private fun estimateRequiredRamBytes(
        weights: Long,
        backend: InferenceBackend,
        tail: Double = 1.25,
    ): Long {
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
        val raw = ((weights * factor + overhead) * tail).toLong()
        return maxOf(raw, MIN_REQUIRED_RAM_BYTES)
    }
}
