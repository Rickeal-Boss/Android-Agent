package com.rickeal.agent.feature.settings

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.rickeal.agent.core.data.AppContainer
import com.rickeal.agent.core.data.LocalAppContainer
import com.rickeal.agent.core.data.perf.PerformanceMonitorManager
import com.rickeal.agent.core.data.viewModelFactory
import com.rickeal.agent.feature.settings.memory.MemoryRoute
import com.rickeal.agent.feature.settings.memory.MemoryScreen
import com.rickeal.agent.feature.settings.memory.MemoryViewModel
import com.rickeal.agent.feature.settings.storage.StorageScreen
import com.rickeal.agent.feature.settings.storage.StorageViewModel
import com.rickeal.agent.feature.settings.tools.SandboxFilesRoute
import com.rickeal.agent.feature.settings.tools.SandboxFilesScreen
import com.rickeal.agent.feature.settings.tools.SandboxFilesViewModel
import com.rickeal.agent.feature.settings.tools.ToolsRoute
import com.rickeal.agent.feature.settings.tools.ToolsScreen
import com.rickeal.agent.feature.settings.tools.ToolsViewModel

object SettingsRoute {
    const val ROUTE = "settings"

    fun build(): String = ROUTE
}

object DiagnosticsRoute {
    const val ROUTE = "settings/diagnostics"

    fun build(): String = ROUTE
}

/**
 * 「条款与授权」回看页。
 *
 * 单独一条路由而不是塞进设置首页：条款正文较长，摊在设置列表里会把其它设置项挤下去；
 * 而且它的性质是「查阅已同意内容」，与「调整配置」不是一类操作。
 */
object LegalRoute {
    const val ROUTE = "settings/legal"

    fun build(): String = ROUTE
}

/**
 * 「存储空间」页（Wave 10 Phase 2b C-2）。
 *
 * route 走 `settings/` 前缀：会被 `MainShell` 的 `routeTop()` 最长前缀匹配自动归到
 * SETTINGS 页签（选中态与滑动方向零额外改动）—— 与 diagnostics / legal 同一处置。
 */
object StorageRoute {
    const val ROUTE = "settings/storage"

    fun build(): String = ROUTE
}

fun settingsViewModelFactory(container: AppContainer): ViewModelProvider.Factory =
    viewModelFactory { SettingsViewModel(container) }

fun NavGraphBuilder.settingsGraph(
    navController: NavController,
    onOpenModels: () -> Unit,
) {
    composable(route = SettingsRoute.ROUTE) {
        val container = LocalAppContainer.current
        SettingsScreen(
            viewModel = viewModel(factory = settingsViewModelFactory(container)),
            onOpenDiagnostics = { navController.navigate(DiagnosticsRoute.build()) },
            onOpenLegal = { navController.navigate(LegalRoute.build()) },
            onOpenStorage = { navController.navigate(StorageRoute.build()) },
        )
    }

    composable(route = StorageRoute.ROUTE) {
        val container = LocalAppContainer.current
        StorageScreen(
            viewModel = viewModel(factory = viewModelFactory { StorageViewModel(container) }),
            onBack = { navController.popBackStack() },
            onOpenModels = onOpenModels,
        )
    }

    composable(route = LegalRoute.ROUTE) {
        LegalScreen(onBack = { navController.popBackStack() })
    }

    composable(route = DiagnosticsRoute.ROUTE) {
        val container = LocalAppContainer.current
        // 物理量观测（Wave 30 §2.2）：StateFlow 收集 + 窗口开关经引用计数交给
        // DiagnosticsScreen 的 DisposableEffect（进入 acquire / 离开 release）。
        val perfSamples by container.perfMonitorManager.samples.collectAsState()
        // 帧统计（W81，审查 P0-1 的 07a 档）：仅 debug 构建有采集行为
        // （FrameStatsCollector 内部按 FLAG_DEBUGGABLE 门控，release 恒 no-op）；
        // 「采集中」状态归本路由持有，卡片按它切按钮文案。
        val frameStats by container.frameStatsCollector.stats.collectAsState()
        var frameStatsActive by remember { mutableStateOf(false) }
        // 模型自检（Wave 44 P0-1）：探针状态机归 DiagnosticsViewModel（本页此前无 VM，
        // 探针引入异步/可变状态后该姿态已不成立 —— 见 DiagnosticsScreen KDoc）。
        val diagnosticsViewModel: DiagnosticsViewModel = viewModel(
            factory = viewModelFactory { DiagnosticsViewModel(container) },
        )
        val probeState by diagnosticsViewModel.probeState.collectAsState()
        // 激活模型 knownBroken 签名（W71 B1 数据面）：诊断页「自检面 PASS」与
        // 「已登记坏结论」并列呈现用（T-2/I-2，见 DiagnosticsScreen.probePresentation）。
        val knownBrokenOn by diagnosticsViewModel.knownBrokenOn.collectAsState()
        DiagnosticsScreen(
            onBack = { navController.popBackStack() },
            readPersistedErrors = { container.agentLogFileStore.read() },
            clearPersistedErrors = { container.agentLogFileStore.clear() },
            perfSamples = perfSamples,
            perfHeader = "CLK_TCK：${container.perfMonitorManager.clkTckSourceLabel}" +
                " · 样本 ${perfSamples.size}/${PerformanceMonitorManager.HISTORY_CAPACITY}（1s 间隔）",
            onPerfObservation = { active ->
                // 页面窗口：clearSamples=false —— 不清空样本，「刚跑完开诊断页要看
                // 这次数据」不被 0→1 的线程重建抹掉（W81 审查 P1-1；run 窗口的
                // clearSamples=true 在 ChatRunCoordinator 的 acquire 点）。
                if (active) container.perfMonitorManager.acquire("diagnostics", clearSamples = false)
                else container.perfMonitorManager.release("diagnostics")
            },
            frameStats = frameStats,
            frameStatsAvailable = container.frameStatsCollector.isDebuggable,
            frameStatsActive = frameStatsActive,
            onFrameStatsToggle = { active ->
                frameStatsActive = active
                if (active) container.frameStatsCollector.start()
                else container.frameStatsCollector.stop()
            },
            probeState = probeState,
            onRunProbe = diagnosticsViewModel::onRunProbe,
            knownBrokenOn = knownBrokenOn,
        )
        // 离开页面停帧统计采集（窗口纪律同 onPerfObservation 的 DisposableEffect）：
        // 不停的话 Choreographer 回调会跟到诊断页之后白耗电。stop 幂等。
        DisposableEffect(Unit) {
            onDispose {
                frameStatsActive = false
                container.frameStatsCollector.stop()
            }
        }
    }

    // Wave4：工具页提升为一级页签（route 已改为顶层 "tools"），但 composable 仍注册
    // 在本 graph builder 里 —— settingsGraph 只在 MainShell 的 NavHost 上调用一次，
    // 与注册在独立 toolsGraph 完全等价，少一层文件改动（B-P1-8 的零构建脚本改动原则）。
    composable(route = ToolsRoute.ROUTE) {
        val container = LocalAppContainer.current
        ToolsScreen(
            viewModel = viewModel(
                factory = viewModelFactory { ToolsViewModel(container) },
            ),
            onBack = { navController.popBackStack() },
            // Wave 33：沙箱工作区入口卡 → 文件子页。push 进栈（非 navigateTop）：
            // 子页返回应回到工具页，这与 Diagnostics / Storage 子页同一形态。
            onOpenSandboxFiles = { navController.navigate(SandboxFilesRoute.build()) },
        )
    }

    // Wave 33：沙箱文件子页。route 前缀 `tools/` 使 routeTop() 最长前缀匹配自动
    // 归到 TOOLS 页签（见 SandboxFilesRoute KDoc），composable 注册在本 graph builder
    // 的理由与上面的工具页 / 记忆页一致（B-P1-8 零构建脚本改动原则）。
    composable(route = SandboxFilesRoute.ROUTE) {
        val container = LocalAppContainer.current
        SandboxFilesScreen(
            viewModel = viewModel(
                factory = viewModelFactory { SandboxFilesViewModel(container) },
            ),
            sandboxRoot = container.sandboxDir,
            onBack = { navController.popBackStack() },
        )
    }

    // Wave4：记忆页与工具页同理 —— 一级页签的路由也注册在本 graph builder。
    composable(route = MemoryRoute.ROUTE) {
        val container = LocalAppContainer.current
        MemoryScreen(
            viewModel = viewModel(
                factory = viewModelFactory { MemoryViewModel(container) },
            ),
        )
    }
}
