package com.rickeal.agent.onboarding

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.rickeal.agent.core.data.AppContainer
import com.rickeal.agent.core.design.GlassWallpaper
import kotlinx.coroutines.launch

/** 首启流程的三个步骤。顺序固定：引导 → 应用条款 → Gemma 授权。 */
private enum class FirstRunStep { ONBOARDING, TOS, GEMMA }

/**
 * 首启闸门：引导 + 两份**互相独立**的条款接受。
 *
 * 为什么是两步条款而不是一步（关键，别合并）：
 *  - 应用服务条款的授权主体是应用作者；
 *  - Gemma Terms of Use 的授权主体是 Google。
 * 两个法律主体不同，合并成一个「同意」按钮在法律上是站不住的 —— 官方做法也是两份独立文件。
 * 因此这里用 `SettingsRepository.isTosAccepted` 与 `isGemmaTermsAccepted` 两个独立状态位分别记录。
 *
 * 「独立」还有第二层含义，容易漏：**适用范围也不能混**。Gemma 条款只约束 Gemma 模型，
 * 而内置预设里还有 Qwen / DeepSeek / MiniCPM / Phi / LFM 五条与它无关 —— 所以这里的
 * Gemma 步骤是「提前征询」，真正**硬性**的强制点在 `:feature-models` 的下载 / 加载动作上，
 * 且只在模型确实属于 Gemma 系时才触发（判据见 `ModelLicenses`）。
 *
 * 阻塞策略（刻意不对称）：
 *  - 引导页：可跳过；
 *  - 应用 TOS：**硬闸门**，不接受就退出应用（否则「接受」没有意义）；
 *  - Gemma 授权：可「稍后再说」放行 —— 此刻用户还没决定要用哪个模型，硬拦不合理；
 *    不接受只是拿不到 Gemma 模型，应用其余部分照常可用。
 *
 * 调用方需保证本组件位于 `LiquidAgentTheme` 之内（壁纸与玻璃配色依赖主题下发的 CompositionLocal）。
 */
@Composable
fun FirstRunGate(
    container: AppContainer,
    onExitApp: () -> Unit,
    content: @Composable () -> Unit,
) {
    val settings = container.settingsRepository
    val scope = rememberCoroutineScope()

    // initial = null 而不是 false：DataStore 是异步的，用 false 当初始值会让每次冷启动
    // 都先闪一帧引导页/条款页（老用户尤其明显）。null 表示「还没读到」，先什么都不显示。
    val tosAccepted by settings.isTosAccepted.collectAsState(initial = null)
    val gemmaAccepted by settings.isGemmaTermsAccepted.collectAsState(initial = null)
    val hasSeenOnboarding by settings.hasSeenOnboarding.collectAsState(initial = null)

    if (tosAccepted == null || gemmaAccepted == null || hasSeenOnboarding == null) {
        Box(modifier = Modifier.fillMaxSize()) {
            GlassWallpaper(modifier = Modifier.fillMaxSize())
        }
        return
    }

    if (hasSeenOnboarding == true) {
        content()
        return
    }

    // 中途被杀进程后重启：已经接受过应用 TOS 的，不该再看一遍引导。
    var step by remember {
        mutableStateOf(if (tosAccepted == true) FirstRunStep.GEMMA else FirstRunStep.ONBOARDING)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        GlassWallpaper(modifier = Modifier.fillMaxSize())
        when (step) {
            FirstRunStep.ONBOARDING -> OnboardingScreen(
                onFinished = { step = FirstRunStep.TOS },
            )

            FirstRunStep.TOS -> AppTosScreen(
                onAccept = {
                    scope.launch { settings.setTosAccepted(true) }
                    step = FirstRunStep.GEMMA
                },
                onDecline = onExitApp,
            )

            FirstRunStep.GEMMA -> GemmaTermsScreen(
                onAccept = {
                    scope.launch {
                        settings.setGemmaTermsAccepted(true)
                        settings.setHasSeenOnboarding(true)
                    }
                },
                onLater = {
                    // 不接受也要收尾：把「首启流程已走完」落盘，否则每次冷启动都会重放整段流程。
                    // Gemma 的未接受状态仍然保留在 isGemmaTermsAccepted=false，由使用侧继续把关。
                    scope.launch { settings.setHasSeenOnboarding(true) }
                },
            )
        }
    }
}
