package com.rickeal.agent.core.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.rickeal.agent.core.model.AgentJson
import com.rickeal.agent.core.model.InferenceConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "liquid_agent_settings",
)

class SettingsRepository(private val context: Context) {

    private object Keys {
        val INFERENCE_CONFIG = stringPreferencesKey("inference_config")
        val ACTIVE_MODEL_ID = stringPreferencesKey("active_model_id")
        val ACTIVE_ENDPOINT_ID = stringPreferencesKey("active_endpoint_id")
        val DARK_MODE = stringPreferencesKey("dark_mode")
        val REDUCE_MOTION = booleanPreferencesKey("reduce_motion")
        val GLASS_INTENSITY = floatPreferencesKey("glass_intensity")
        val ENABLE_NOISE = booleanPreferencesKey("enable_noise")
        val ALLOW_METERED_DOWNLOAD = booleanPreferencesKey("allow_metered_download")

        // ---- 首启合规：引导与条款接受 ----
        // 三项刻意**分开**存：应用服务条款与 Gemma 授权条款的法律主体不同
        // （前者是我们自己，后者是 Google），必须能独立表达「接受其一、未接受其二」。
        val HAS_SEEN_ONBOARDING = booleanPreferencesKey("has_seen_onboarding")
        val IS_TOS_ACCEPTED = booleanPreferencesKey("is_tos_accepted")
        val IS_GEMMA_TERMS_ACCEPTED = booleanPreferencesKey("is_gemma_terms_accepted")

        // 引导页**单独**一个标记，与 HAS_SEEN_ONBOARDING（整段首启流程走完）不是一回事。
        // 理由见 hasSeenIntro 的注释：端侧设备 LMK 杀进程很常见，中间步骤必须各自可续。
        val HAS_SEEN_INTRO = booleanPreferencesKey("has_seen_intro")
    }

    val inferenceConfig: Flow<InferenceConfig> = context.settingsDataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { prefs ->
            val raw = prefs[Keys.INFERENCE_CONFIG]
            if (raw.isNullOrBlank()) InferenceConfig() else runCatching {
                AgentJson.Default.decodeFromString(InferenceConfig.serializer(), raw)
            }.getOrDefault(InferenceConfig())
        }

    val themeState: Flow<ThemeState> = context.settingsDataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { prefs ->
            ThemeState(
                darkMode = runCatching { DarkMode.valueOf(prefs[Keys.DARK_MODE].orEmpty()) }
                    .getOrDefault(DarkMode.SYSTEM),
                reduceMotion = prefs[Keys.REDUCE_MOTION] ?: false,
                glassIntensity = prefs[Keys.GLASS_INTENSITY] ?: 1f,
                enableNoise = prefs[Keys.ENABLE_NOISE] ?: true,
            )
        }

    /**
     * 是否允许在「按流量计费」的网络（通常是移动数据）上下载模型。
     * 默认 false：下载前会弹二次确认。部分用户确实需要用流量下载，
     * 所以这里是「开关」而不是「一刀切禁止」。
     */
    val allowMeteredDownload: Flow<Boolean> = context.settingsDataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[Keys.ALLOW_METERED_DOWNLOAD] ?: false }

    /**
     * **整段首启流程**是否已走完（引导 → TOS → Gemma 全部过完）。默认 false。
     *
     * 注意与 [hasSeenIntro] 的区别：这个标记在流程**末尾**才写，代表「首启结束了」；
     * 中间每一步各自有自己的标记，用于被杀进程后续跑。别用这一个去推断单步进度 ——
     * 那样中间步骤就全都不可续，重启必然重放。
     */
    val hasSeenOnboarding: Flow<Boolean> = context.settingsDataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[Keys.HAS_SEEN_ONBOARDING] ?: false }

    /**
     * 引导页（那 3 屏介绍）是否已经看过。默认 false。走完与「跳过」都置 true。
     *
     * ## 为什么要单独一个标记
     *
     * 端侧设备上 LMK（低内存杀手）在冷启动期间杀进程很常见，而首启流程有 3 步、
     * 中间还夹着两次要读条款的停顿，被杀的概率不低。如果只用「流程末尾」那一个标记，
     * 就会出现：看完引导 → 停在 TOS → 被杀 → 重启 → **把引导重看一遍**。
     * 引导虽然可跳过（所以是体验问题不是正确性问题），但首启是用户对 App 的第一印象，
     * 这个序列在低端机上并不罕见。
     *
     * 每一步各记各的，重启后就能从**已完成的下一步**继续，而不是从头来。
     */
    val hasSeenIntro: Flow<Boolean> = context.settingsDataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[Keys.HAS_SEEN_INTRO] ?: false }

    /**
     * 应用服务条款（TOS）是否已被接受。默认 false。
     *
     * 这一项是**进入主界面的硬闸门**：上架合规要求用户能访问条款并作出接受的意思表示，
     * 所以「未接受」时不能放行到主界面。
     */
    val isTosAccepted: Flow<Boolean> = context.settingsDataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[Keys.IS_TOS_ACCEPTED] ?: false }

    /**
     * Gemma 授权条款（Gemma Terms of Use）是否已被接受。默认 false。
     *
     * **必须与 [isTosAccepted] 分开**：Gemma 的授权主体是 Google，不是本应用作者。
     * 未接受时不应下载/使用 Gemma 系列模型，但应用其余功能仍可用。
     */
    val isGemmaTermsAccepted: Flow<Boolean> = context.settingsDataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[Keys.IS_GEMMA_TERMS_ACCEPTED] ?: false }

    val activeModelId: Flow<String?> = context.settingsDataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[Keys.ACTIVE_MODEL_ID] }

    val activeEndpointId: Flow<String?> = context.settingsDataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[Keys.ACTIVE_ENDPOINT_ID] }

    /**
     * 同步读取（架构文档 §7.3 里 ChatViewModel.onSend 用得到）。
     * 必须在协程里调用 —— 故意做成 suspend 而不是阻塞 runBlocking。
     */
    suspend fun activeModelIdSync(): String? = activeModelId.first()

    suspend fun activeEndpointIdSync(): String? = activeEndpointId.first()

    suspend fun updateInferenceConfig(transform: (InferenceConfig) -> InferenceConfig) {
        context.settingsDataStore.edit { prefs ->
            val current = prefs[Keys.INFERENCE_CONFIG]?.let { raw ->
                runCatching { AgentJson.Default.decodeFromString(InferenceConfig.serializer(), raw) }.getOrNull()
            } ?: InferenceConfig()
            prefs[Keys.INFERENCE_CONFIG] = AgentJson.Default.encodeToString(
                InferenceConfig.serializer(),
                transform(current).coerce(),
            )
        }
    }

    suspend fun setSystemInstruction(text: String) {
        updateInferenceConfig { it.copy(systemInstruction = text) }
    }

    suspend fun setActiveModel(id: String?) {
        context.settingsDataStore.edit { prefs ->
            if (id == null) prefs.remove(Keys.ACTIVE_MODEL_ID) else prefs[Keys.ACTIVE_MODEL_ID] = id
        }
    }


    suspend fun setAllowMeteredDownload(allow: Boolean) {
        context.settingsDataStore.edit { it[Keys.ALLOW_METERED_DOWNLOAD] = allow }
    }

    suspend fun setHasSeenOnboarding(seen: Boolean) {
        context.settingsDataStore.edit { it[Keys.HAS_SEEN_ONBOARDING] = seen }
    }

    /** 引导页看完（或跳过）时立刻落盘，供被杀进程重启后续跑。见 [hasSeenIntro]。 */
    suspend fun setHasSeenIntro(seen: Boolean) {
        context.settingsDataStore.edit { it[Keys.HAS_SEEN_INTRO] = seen }
    }

    suspend fun setTosAccepted(accepted: Boolean) {
        context.settingsDataStore.edit { it[Keys.IS_TOS_ACCEPTED] = accepted }
    }

    suspend fun setGemmaTermsAccepted(accepted: Boolean) {
        context.settingsDataStore.edit { it[Keys.IS_GEMMA_TERMS_ACCEPTED] = accepted }
    }

    suspend fun setActiveEndpoint(id: String?) {
        context.settingsDataStore.edit { prefs ->
            if (id == null) prefs.remove(Keys.ACTIVE_ENDPOINT_ID) else prefs[Keys.ACTIVE_ENDPOINT_ID] = id
        }
    }

    suspend fun setThemeState(state: ThemeState) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.DARK_MODE] = state.darkMode.name
            prefs[Keys.REDUCE_MOTION] = state.reduceMotion
            prefs[Keys.GLASS_INTENSITY] = state.glassIntensity
            prefs[Keys.ENABLE_NOISE] = state.enableNoise
        }
    }

    suspend fun snapshot(): InferenceConfig = inferenceConfig.first()
}
