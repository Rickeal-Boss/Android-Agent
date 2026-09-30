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
import com.rickeal.agent.core.model.AiCapabilityMode
import com.rickeal.agent.core.model.AgentJson
import com.rickeal.agent.core.model.InferenceConfig
import com.rickeal.agent.core.model.ToolDisclosureMode
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
        val DARK_MODE = stringPreferencesKey("dark_mode")
        val REDUCE_MOTION = booleanPreferencesKey("reduce_motion")
        val GLASS_INTENSITY = floatPreferencesKey("glass_intensity")
        val ENABLE_NOISE = booleanPreferencesKey("enable_noise")
        val ALLOW_METERED_DOWNLOAD = booleanPreferencesKey("allow_metered_download")
        // 触感强度：存 String（枚举名），因为 GlassHapticLevel 定义在 :core-design，
        // :core-data 不能 import 它（依赖方向倒置）。解析失败一律回退 STANDARD。
        val HAPTIC_LEVEL = stringPreferencesKey("haptic_level")

        // AI 能力档位（Wave 26）。存枚举名 String，与 HAPTIC_LEVEL 同款容错姿态：
        // 解析失败回退 **WORKSPACE_WRITE**（默认档）而不是 FULL —— 设置读坏绝不能
        // 变成「AI 能力被静默放宽」，回退值必须是仍然可用且最保守的那个档。
        val CAPABILITY_MODE = stringPreferencesKey("capability_mode")

        // 工具披露模式（Wave 27）。同样存枚举名 String。解析失败回退 **FULL**（默认档）
        // —— 与 CAPABILITY_MODE 相反的方向，因为本字段管的是**提示词预算与可见性**，
        // 不是权限：回退到 FULL 只是「工具清单照旧全量进提示词」，不会放宽任何闸门。
        val DISCLOSURE_MODE = stringPreferencesKey("disclosure_mode")

        // 覆盖层 scrim 不透明度（Wave 21）。Float 原生类型，走 floatPreferencesKey
        // （同 GLASS_INTENSITY 模式，无依赖倒置问题）。
        val OVERLAY_OPACITY = floatPreferencesKey("overlay_opacity")

        // 覆盖层背景深度模糊的满量程半径 dp（2026-09-27）。同为 Float 原生类型。
        val OVERLAY_BLUR_RADIUS = floatPreferencesKey("overlay_blur_radius")

        // 「生成速度通知」开关（Wave 9 需求 5）。默认 false：
        // 通知是观测窗口不是能力，且 API 33+ 要运行时权限 —— 默认关掉，
        // 不在用户没表达意愿时去请求权限。
        val GENERATION_NOTIFICATION = booleanPreferencesKey("generation_notification")

        // 自定义壁纸（Wave 9 需求 3b）：存 WallpaperStore 的相对路径，"" = 程序化壁纸。
        // 刻意不存绝对路径 —— filesDir 随设备迁移 / 备份恢复会变。
        val WALLPAPER_PATH = stringPreferencesKey("wallpaper_path")

        // ---- 首启合规：引导与条款接受 ----
        // 三项刻意**分开**存：应用服务条款与 Gemma 授权条款的法律主体不同
        // （前者是我们自己，后者是 Google），必须能独立表达「接受其一、未接受其二」。
        val HAS_SEEN_ONBOARDING = booleanPreferencesKey("has_seen_onboarding")

        // ⚠️ 时序风险（Wave 39 补记，**只记录不修**）：这是一个**无版本 boolean**。
        // 它只能表达「同意过 / 没同意过」，表达不了「同意的是**哪一版**条款」。
        // ⇒ 一旦将来替换法务文本（换文案、换条款、换发布主体），**当天所有老用户**
        // 都会命中 `IS_TOS_ACCEPTED == true` 而被视为「已同意新条款」，首启门禁
        // （Wave 39 时位于 `app/src/main/java/com/rickeal/agent/onboarding/FirstRunGate.kt`
        // 的 `tosAccepted == true -> FirstRunStep.GEMMA`）与法律页开关都会被直接跳过 ——
        // 即「**未同意新条款却被视为已同意**」，属合规事故，且事后无法从本字段反推
        // 用户当年同意的是哪一版。
        //
        // ✅ 落地顺序是**硬约束**：`termsVersion`（或任何等价的版本化方案 ——
        // 存「已同意的版本号」，版本不匹配即重新征求同意）**必须先于任何法务文本替换
        // 落地**，不能反序。反序则老用户的同意状态不可区分、不可补征。
        //
        // ⛔ 为什么本波只加注释不改行为：实现涉及一个产品/法务决策 —— 老用户的
        // `boolean = true` 到底算「已同意第 1 版」（据此仅对第 2 版重新征求）还是算
        // 「未同意任何版本」（据此全量重新征求）。这不是工程侧能自行裁定的。
        // 且**法务文本替换尚未发生**，时序风险**未到期**，现在改行为属于给一个还不存在
        // 的场景做产品决策。故本波只把这笔债务固化成可见注释；等真的要换法务文本时，
        // 先做 `termsVersion`，再换文本。
        //
        // 消费点（改本字段或新增版本字段时必须同步）：
        //  - `app/.../onboarding/FirstRunGate.kt`（首启门禁，`tosAccepted`）
        //  - `feature-settings/.../LegalScreen.kt`（法律页开关，`settings.isTosAccepted`）
        //  - 本文件 `isTosAccepted` / `setTosAccepted`（读写两侧都要带上版本号口径）
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
                // 枚举名非法（改名 / 脏数据 / 老版本）时回退 STANDARD，不让坏值毒化整个 flow。
                hapticLevel = prefs[Keys.HAPTIC_LEVEL] ?: "STANDARD",
                overlayOpacity = prefs[Keys.OVERLAY_OPACITY] ?: 0.45f,
                overlayBlurRadius = prefs[Keys.OVERLAY_BLUR_RADIUS] ?: 20f,
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
     * 「生成速度通知」开关。默认 **false**：通知要 API 33+ 的运行时权限，
     * 且它是观测窗口不是能力 —— 必须由用户显式打开，App 才有资格去请求权限。
     */
    val generationNotification: Flow<Boolean> = context.settingsDataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[Keys.GENERATION_NOTIFICATION] ?: false }

    /**
     * 用户给 AI 的**整体能力档位**（Wave 26）。默认 [AiCapabilityMode.WORKSPACE_WRITE]
     * = 与引入档位前的行为逐字节一致。
     *
     * 回退策略刻意选 WORKSPACE_WRITE 而非 FULL：设置值损坏/被外部改写时，绝不能变成
     * 「AI 能力被静默放宽」。档位只收紧不放宽，回退点必须落在仍然可用且更保守的那一侧。
     */
    val capabilityMode: Flow<AiCapabilityMode> = context.settingsDataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { prefs ->
            when (prefs[Keys.CAPABILITY_MODE]) {
                AiCapabilityMode.READ_ONLY.name -> AiCapabilityMode.READ_ONLY
                AiCapabilityMode.FULL.name -> AiCapabilityMode.FULL
                else -> AiCapabilityMode.WORKSPACE_WRITE
            }
        }

    /**
     * 工具**披露模式**（Wave 27 / Operit「CLI 工具模式」裁剪移植）。默认
     * [ToolDisclosureMode.FULL] = 工具清单完整进提示词，与引入本模式前的行为逐字节一致。
     *
     * 回退策略与 [capabilityMode] 相反、刻意选 FULL：本字段管的是**提示词预算与
     * 工具可见性**，不是权限。回退到 FULL 的后果只是「工具清单照旧全量进提示词」，
     * 不会放宽任何闸门（转发调用照走完整审批链路），因此这里无需选保守侧。
     */
    val disclosureMode: Flow<ToolDisclosureMode> = context.settingsDataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { prefs ->
            when (prefs[Keys.DISCLOSURE_MODE]) {
                ToolDisclosureMode.ON_DEMAND.name -> ToolDisclosureMode.ON_DEMAND
                else -> ToolDisclosureMode.FULL
            }
        }

    /**
     * 自定义壁纸的相对路径（相对 filesDir，由 [WallpaperStore] 写入）。默认 "" = 程序化壁纸。
     * 只存路径不存图：图片字节归 [WallpaperStore]，这里只做「指向哪张图」的单一事实来源。
     */
    val wallpaperPath: Flow<String> = context.settingsDataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[Keys.WALLPAPER_PATH] ?: "" }

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

    /**
     * 同步读取（架构文档 §7.3 里 ChatViewModel.onSend 用得到）。
     * 必须在协程里调用 —— 故意做成 suspend 而不是阻塞 runBlocking。
     */
    suspend fun activeModelIdSync(): String? = activeModelId.first()

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

    /** AI 能力档位落盘。UI 侧负责在用户选择后立即调用（设置页与输入区共用同一入口）。 */
    suspend fun setCapabilityMode(mode: AiCapabilityMode) {
        context.settingsDataStore.edit { it[Keys.CAPABILITY_MODE] = mode.name }
    }

    /** 工具披露模式落盘。与 [setCapabilityMode] 同款入口纪律（UI 选择后立即调用）。 */
    suspend fun setDisclosureMode(mode: ToolDisclosureMode) {
        context.settingsDataStore.edit { it[Keys.DISCLOSURE_MODE] = mode.name }
    }

    /**
     * 「生成速度通知」开关落盘。**只在拿到权限后调用**（UI 侧负责）：
     * 用户拒绝权限时不落 true，否则下次启动开关是开的却永远不出通知。
     */
    suspend fun setGenerationNotification(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.GENERATION_NOTIFICATION] = enabled }
    }

    /**
     * 壁纸路径落盘。传 "" = 恢复默认（落盘前应由调用方先删 [WallpaperStore] 的文件，
     * 否则会出现「路径为空但文件还在」的孤儿文件）。
     */
    suspend fun setWallpaperPath(path: String) {
        context.settingsDataStore.edit { it[Keys.WALLPAPER_PATH] = path }
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

    suspend fun setThemeState(state: ThemeState) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.DARK_MODE] = state.darkMode.name
            prefs[Keys.REDUCE_MOTION] = state.reduceMotion
            prefs[Keys.GLASS_INTENSITY] = state.glassIntensity
            prefs[Keys.ENABLE_NOISE] = state.enableNoise
            prefs[Keys.HAPTIC_LEVEL] = state.hapticLevel
            prefs[Keys.OVERLAY_OPACITY] = state.overlayOpacity
            prefs[Keys.OVERLAY_BLUR_RADIUS] = state.overlayBlurRadius
        }
    }

    suspend fun snapshot(): InferenceConfig = inferenceConfig.first()
}
