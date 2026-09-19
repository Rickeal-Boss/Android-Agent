package com.rickeal.agent.feature.settings

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rickeal.agent.core.data.AppContainer
import com.rickeal.agent.core.data.ThemeState
import com.rickeal.agent.core.model.InferenceConfig
import com.rickeal.agent.core.model.RemoteEndpoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Immutable
data class SettingsUiState(
    val config: InferenceConfig = InferenceConfig(),
    val theme: ThemeState = ThemeState(),
    val endpoints: List<RemoteEndpoint> = emptyList(),
    val activeEndpointId: String? = null,
    /** 非空表示正在编辑该端点（新增时为带默认值的对象） */
    val editing: RemoteEndpoint? = null,
    val message: String? = null,
    val error: String? = null,
)

class SettingsViewModel(
    private val container: AppContainer,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch { container.endpointRepository.refresh() }
        viewModelScope.launch {
            container.settingsRepository.inferenceConfig.collect { config ->
                _uiState.update { it.copy(config = config) }
            }
        }
        viewModelScope.launch {
            container.settingsRepository.themeState.collect { theme ->
                _uiState.update { it.copy(theme = theme) }
            }
        }
        viewModelScope.launch {
            container.endpointRepository.endpoints.collect { list ->
                _uiState.update { it.copy(endpoints = list) }
            }
        }
        viewModelScope.launch {
            container.settingsRepository.activeEndpointId.collect { id ->
                _uiState.update { it.copy(activeEndpointId = id) }
            }
        }
    }

    /**
     * 一次性写入配置（分段控件、开关这类"点一下就定"的交互走这里，直接落盘）。
     */
    fun onConfigChange(transform: (InferenceConfig) -> InferenceConfig) {
        val next = transform(_uiState.value.config).coerce()
        _uiState.update { it.copy(config = next) }
        viewModelScope.launch { container.settingsRepository.updateInferenceConfig { next } }
    }

    fun onThemeChange(theme: ThemeState) {
        _uiState.update { it.copy(theme = theme) }
        viewModelScope.launch { container.settingsRepository.setThemeState(theme) }
    }

    /**
     * 只改内存里的配置，**不落盘**。
     *
     * 滑块 `onValueChange` 是逐帧回调（约 60 次/秒），若在这里直接写 DataStore，
     * 一次拖动就是几十次磁盘事务。与 [onConfigCommit] 配对使用：拖动期间只更新 UI，
     * 松手（`onValueChangeFinished`）时才落盘一次。与 `ChatViewModel.onParamPreview`
     * 保持同一套约定。
     */
    fun onConfigPreview(transform: (InferenceConfig) -> InferenceConfig) {
        _uiState.update { it.copy(config = transform(it.config).coerce()) }
    }

    fun onConfigCommit() {
        val next = _uiState.value.config
        viewModelScope.launch { container.settingsRepository.updateInferenceConfig { next } }
    }

    /**
     * 只改内存里的主题，**不落盘**。
     *
     * 「玻璃质感强度」这条尤其关键：`themeState` 是 `collectAsState` 驱动 `LiquidAgentTheme`
     * 的源头，一次改动 = 整棵组合树重组 + 每个玻璃节点重画（含背景模糊录制）。
     * 再叠加每帧写 DataStore，拖动必卡。
     */
    fun onThemePreview(theme: ThemeState) {
        _uiState.update { it.copy(theme = theme) }
    }

    fun onThemeCommit() {
        val next = _uiState.value.theme
        viewModelScope.launch { container.settingsRepository.setThemeState(next) }
    }

    /* ------------------------------------------------------------ 端点 CRUD */

    fun onEditEndpoint(endpoint: RemoteEndpoint?) {
        _uiState.update { it.copy(editing = endpoint) }
    }

    fun onSaveEndpoint(endpoint: RemoteEndpoint) {
        val endpointWithName = if (endpoint.name.isBlank()) {
            endpoint.copy(name = endpoint.baseUrl.ifBlank { "自定义端点" })
        } else {
            endpoint
        }
        viewModelScope.launch {
            val ok = container.endpointRepository.upsertValidated(endpointWithName)
            _uiState.update {
                if (ok) {
                    it.copy(editing = null, message = "已保存", error = null)
                } else {
                    it.copy(error = "baseUrl 不能为空")
                }
            }
        }
    }

    fun onDeleteEndpoint(id: String) {
        viewModelScope.launch {
            container.endpointRepository.remove(id)
            if (_uiState.value.activeEndpointId == id) {
                container.settingsRepository.setActiveEndpoint(null)
            }
            _uiState.update {
                it.copy(
                    activeEndpointId = if (it.activeEndpointId == id) null else it.activeEndpointId,
                    message = "已删除",
                )
            }
        }
    }

    fun onSelectEndpoint(id: String) {
        viewModelScope.launch {
            container.settingsRepository.setActiveEndpoint(id)
            _uiState.update { it.copy(activeEndpointId = id, message = "已设为当前端点") }
        }
    }

    fun onDismissMessage() {
        _uiState.update { it.copy(message = null, error = null) }
    }
}
