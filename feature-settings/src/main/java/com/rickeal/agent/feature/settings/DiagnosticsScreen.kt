package com.rickeal.agent.feature.settings

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.rickeal.agent.core.data.perf.FrameStatsCollector
import com.rickeal.agent.core.data.perf.JANK_THRESHOLD_NANOS
import com.rickeal.agent.core.data.perf.PerfSample
import com.rickeal.agent.core.data.thermal.ThermalGovernor
import com.rickeal.agent.core.design.GlassButton
import com.rickeal.agent.core.design.GlassCard
import com.rickeal.agent.core.design.GlassEmptyState
import com.rickeal.agent.core.design.GlassIconButton
import com.rickeal.agent.core.design.GlassIconButtonShape
import com.rickeal.agent.core.design.GlassScaffold
import com.rickeal.agent.core.design.GlassSegmented
import com.rickeal.agent.core.design.GlassTopBar
import com.rickeal.agent.core.design.LocalBottomBarOverlay
import com.rickeal.agent.core.design.LocalGlassColors
import com.rickeal.agent.core.design.LocalGlassTokens
import com.rickeal.agent.core.design.liquid.platform.isRenderEffectSupported
import com.rickeal.agent.core.design.liquid.platform.isRuntimeShaderSupported
import com.rickeal.agent.core.engine.local.ModelHealthProbe
import com.rickeal.agent.core.model.AgentLog
import com.rickeal.agent.core.model.AgentLogLevel
import com.rickeal.agent.core.model.AgentLogStore
import com.rickeal.agent.core.model.CriterionSeverity
import com.rickeal.agent.core.model.ModelHealthVerdict
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 诊断页：展示进程内最近的运行日志（黑匣子）、**上次崩溃前落盘的 ERROR 记录**，
 * 以及**模型自检**入口（Wave 44 P0-1）。
 *
 * 为什么现在**有** ViewModel（Wave 44 改写）：此前本页刻意无 ViewModel，理由是「日志收集器
 * 在 `:core-model`，那里**没有协程依赖**（该模块只有 kotlinx-serialization），没有 StateFlow
 * 可订阅，故取只读快照」。该理由因**模型自检探针**的引入**已不成立** —— 探针带来异步 +
 * 可变状态（running / 结果 / 错误 / 取消），需要生命周期持有者。日志区仍走「取一次快照 +
 * 手动刷新」（本函数内的 `snapshot`），探针状态经 [DiagnosticsViewModel] 注入（见下方参数）。
 *
 * 两个数据源刻意**分开呈现**、不合并：内存缓冲是「本次运行」，磁盘文件是「上次崩溃之前」。
 * 混在一起会让人误以为崩溃前的记录也在内存里（那样的话它们根本活不到现在）。
 *
 * 「复制全部」把这两区**当前可见**的日志拼成纯文本进剪贴板（取证/贴给他人看）。它是静默
 * 操作，因此**必须有 Toast 反馈**（成功与「剪贴板不可用」都要说）—— 没有反馈时用户无法
 * 区分「复制成功」与「点了没反应」，这正是本项目「失败可见化」铁律要避免的形态。
 *
 * W81 观测面三项改进（外部审查实证）：① CPU-秒曲线改按**真实时间**定位 + 冻结缺口
 * 断线（见 [CpuSecondsChart] KDoc 的 W78 取证）；② 「物理量」卡内新增**电池温度行**
 * （与 ThermalGovernor 熔断判据同源展示 —— 用户被热保护拒了得知道为什么）；③ 新增
 * **帧统计卡**（仅 debug 构建，Choreographer 零依赖采集，见 [FrameStatsCollector] KDoc）。
 *
 * W82 帧统计两处修正：① 卡内并列「2s 滑窗」与「整次会话累计」—— 窗口装不下「导航到对话页
 * + 操作 + 返回」这种跨页链路（见 [FrameStatsSession]）；② 订阅下沉到
 * [FrameStatsCard]，4Hz 发布只重组那张卡，不再拖整页（W81 真机实证：每帧发布压到 ~5fps）。
 *
 * @param readPersistedErrors 读回落盘的 ERROR 记录（阻塞 IO，调用方保证在 IO 线程执行）
 * @param clearPersistedErrors 清空落盘记录
 * @param probeState 模型自检状态机（Wave 44，带默认值向后兼容：不传 = 不显示自检结论）
 * @param onRunProbe 点「运行自检」的回调（Wave 44，带默认值向后兼容）
 * @param knownBrokenOn 激活模型的已知坏签名（W71 B1，来自 [DiagnosticsViewModel.knownBrokenOn]；
 *   `null` = 无激活模型 / 未登记 / 非 preset —— 并列呈现只在非空时追加登记行，
 *   见 [probePresentation]；W72 起该行也出现在 `NotRun(ENGINE_NOT_LOADED)` 态）
 */
@Composable
fun DiagnosticsScreen(
    onBack: () -> Unit,
    readPersistedErrors: () -> List<AgentLog>,
    clearPersistedErrors: () -> Unit,
    modifier: Modifier = Modifier,
    // ── 物理量观测窗口（Wave 30 §2.2）─────────────────────────────────
    // 本页打开 = acquire 采样（acquire 点 ②），与 run 窗口（acquire 点 ①）经
    // 引用计数求精确交集：run 结束后本页仍开着，采样继续；两窗口全关，线程即停。
    perfSamples: List<PerfSample> = emptyList(),
    /** 头部说明（CLK_TCK 来源 + 样本数），由宿主从 PerformanceMonitorManager 拼装。 */
    perfHeader: String? = null,
    /** true = 进入页面（acquire）；false = 离开（release）。 */
    onPerfObservation: (Boolean) -> Unit = {},
    // ── 帧统计（W81，审查 P0-1 的 07a 档；W82 订阅下沉到卡片）───────────────────
    /**
     * 帧统计采集器（应用级单例）。**订阅发生在 [FrameStatsCard] 内部**，本函数不 collect：
     * 采集器按 4Hz 发布快照，若在此处 collectAsState，本页（1000+ 行）每次发布都会全量重组
     * —— W81 的节流只降了频率、没收窄重组范围（W82 修正）。
     *
     * null = 宿主未接线（不显示帧统计卡）；release 包 [FrameStatsCollector.isDebuggable]
     * 恒 false ⇒ 同样不显示（采集器在 release 恒零行为）。
     */
    frameStatsCollector: FrameStatsCollector? = null,
    // ── 模型自检（Wave 44 P0-1）─────────────────────────────────────
    probeState: ProbeUiState = ProbeUiState.Idle,
    onRunProbe: () -> Unit = {},
    // ── 激活模型已知坏签名（W71 B1）──────────────────────────────────
    // 诊断页此前零消费 knownBroken（消费方仅 feature-chat）；W71 起与「自检面 PASS」
    // 并列呈现（T-2/I-2）——不拦截、只告知，与 W68 对话页激活态重校验提示同范式。
    knownBrokenOn: String? = null,
) {
    val colors = LocalGlassColors.current
    val tokens = LocalGlassTokens.current
    val scope = rememberCoroutineScope()
    // 只在 onClick 里用（拿剪贴板 + 弹 Toast）：读写都不参与组合，故不需要 remember。
    val context = LocalContext.current

    // 物理量观测窗口（Wave 30 acquire 点 ②）：进入即采样、离开即停。
    DisposableEffect(Unit) {
        onPerfObservation(true)
        onDispose { onPerfObservation(false) }
    }

    var filterIndex by remember { mutableIntStateOf(0) }
    var snapshot by remember { mutableStateOf(AgentLogStore.recent(MAX_SHOWN)) }
    var persisted by remember { mutableStateOf(emptyList<AgentLog>()) }

    // 磁盘读取必须离开组合阶段（组合跑在主线程，不该做磁盘 IO）。
    LaunchedEffect(Unit) {
        persisted = withContext(Dispatchers.IO) { readPersistedErrors() }
    }

    val levelFilter = levelOfFilter(filterIndex)
    val visible = if (levelFilter == null) snapshot else snapshot.filter { it.level == levelFilter }
    // 落盘只有 ERROR 级：筛选到 INFO / WARN 时显示这一区会自相矛盾，所以只在「全部 / ERROR」下显示。
    val showPersisted = filterIndex == 0 || levelFilter == AgentLogLevel.ERROR

    GlassScaffold(
        modifier = modifier,
        topBar = {
            GlassTopBar(
                title = "诊断信息",
                subtitle = "内存 ${snapshot.size} 条 · 磁盘 ${persisted.size} 条",
                modifier = Modifier.statusBarsPadding(),
                titleAlignment = Alignment.CenterHorizontally,
                navigationIcon = {
                    // pressOnly：顶栏图标位于 GlassTopBar 自己的玻璃之上，再叠玻璃会浑浊、
                    // 也会复现「镜面高光从边缘溢出盖住标题」的问题（详见 GlassIconButton KDoc）。
                    GlassIconButton(
                        onClick = onBack,
                        shape = GlassIconButtonShape.Capsule,
                        pressOnly = true,
                    ) {
                        // 对齐参考形态（iOS 26 返回钮）：玻璃圆钮 + 深色 chevron，无文字。
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "返回",
                                tint = colors.onGlass,
                                modifier = Modifier.size(18.dp),
                        )
                    }
                },
            )
        },
    ) { _ ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 12.dp)
                // 悬浮页签占位（2026-09-26）：加在滚动内容**之内**，末尾条目能滚出
                // 页签区；内容本体仍从玻璃页签底下穿过（见 LocalBottomBarOverlay KDoc）。
                .padding(bottom = LocalBottomBarOverlay.current),
            verticalArrangement = Arrangement.spacedBy(tokens.cardGap),
        ) {
            GlassCard(contentPadding = PaddingValues(14.dp)) {
                Column {
                    GlassSegmented(
                        items = FILTER_LABELS,
                        selectedIndex = filterIndex,
                        onSelected = { filterIndex = it },
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "只记录异常与决策点；ERROR 级额外落盘，其余仅存内存",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onGlassSubtle,
                            modifier = Modifier.weight(1f),
                        )
                        GlassButton(
                            text = "刷新",
                            onClick = {
                                snapshot = AgentLogStore.recent(MAX_SHOWN)
                                scope.launch {
                                    persisted = withContext(Dispatchers.IO) { readPersistedErrors() }
                                }
                            },
                            modifier = Modifier.padding(start = 10.dp),
                        )
                        GlassButton(
                            text = "复制全部",
                            onClick = {
                                // 复制**当前可见**的两区（磁盘区只在「全部 / ERROR」筛选下显示，
                                // 故这里用同一个 showPersisted 判据，保证「所见即所复制」）。
                                val shownPersisted = if (showPersisted) persisted else emptyList()
                                val text = diagnosticsPlainText(shownPersisted, visible)
                                // 用 android.content.ClipboardManager 而不是 LocalClipboardManager：
                                // 后者面向富文本（AnnotatedString），取证场景要的是可粘贴的纯文本。
                                // 写剪贴板不需要权限；ClipData / setPrimaryClip 远早于 minSdk 31。
                                val manager = context.getSystemService(Context.CLIPBOARD_SERVICE)
                                    as? ClipboardManager
                                if (manager == null) {
                                    // 失败可见化：静默失败 = 用户以为复制成功，然后白等一场。
                                    Toast.makeText(context, "剪贴板不可用", Toast.LENGTH_SHORT).show()
                                } else {
                                    // 显式绑成非空局部量：后面在 runCatching 的 lambda 里用，
                                    // 不依赖「智能转换穿透 lambda」这种边界行为。
                                    val clipboard: ClipboardManager = manager
                                    // setPrimaryClip 会抛（远端剪贴板服务异常等）：它跑在 onClick
                                    // 里，抛出去就是一次崩溃，而这只是「复制点日志」这种低价值动作。
                                    val copied = runCatching {
                                        clipboard.setPrimaryClip(
                                            ClipData.newPlainText("LiquidAgent 诊断日志", text),
                                        )
                                    }.isSuccess
                                    // 两种结局都说了：写入失败 / 成功（带条数）。
                                    Toast.makeText(
                                        context,
                                        if (copied) {
                                            "已复制 ${shownPersisted.size + visible.size} 条日志"
                                        } else {
                                            "复制失败（剪贴板拒绝写入）"
                                        },
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            },
                            modifier = Modifier.padding(start = 10.dp),
                        )
                    }
                }
            }

            /* ------------------------------------------ 玻璃渲染能力（折射降级诊断） */
            // 为什么 API 直读而不是查日志：Lens.kt 的折射降级日志在 release 包会被
            // R8 -assumenosideeffects 整条删除（见 Lens.kt:139-140），诊断页不能依赖日志。
            // 这三项都是设备能力的客观事实，不随参数变化，因此放在最上面先给结论。
            val sdkInt = Build.VERSION.SDK_INT
            val runtimeShader = isRuntimeShaderSupported()
            GlassCard(contentPadding = PaddingValues(14.dp)) {
                Column {
                    Text(
                        text = "玻璃渲染能力",
                        style = MaterialTheme.typography.titleSmall,
                        color = colors.onGlass,
                    )
                    Text(
                        text = "设备 Android $sdkInt（${Build.VERSION.RELEASE}）",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onGlassSubtle,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                    CapabilityRow(
                        label = "折射（液态玻璃）",
                        supported = runtimeShader,
                        supportedText = "支持（完整折射）",
                        unsupportedText = "不支持 —— 仅模糊，需 Android 13+",
                    )
                    CapabilityRow(
                        label = "背景模糊",
                        supported = isRenderEffectSupported(),
                        supportedText = "支持",
                        unsupportedText = "不支持（需 Android 12+）",
                    )
                    Text(
                        text = if (runtimeShader) {
                            "本机为完整折射：玻璃边缘有厚度弯折与色散，这是正常形态。"
                        } else {
                            "本机不支持 AGSL 折射，玻璃已降级为纯模糊 —— 这不是参数问题，" +
                                "折射带 / 强度 / 底色调什么都调不出来，也不是 bug。"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onGlassSubtle,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }

            /* ------------------------------------------ 模型自检（Wave 44 P0-1） */
            // 手动触发的小样本自检：对当前模型跑两条固定短 prompt，检查输出是否退化。
            // 质量结论（PASS/DEGRADED/BAD）与「执行失败 / 未运行」严格区分（见 ProbeUiState
            // KDoc）—— 后者不是「模型坏」的结论，文案与配色都必须区分。
            // W71（T-2/I-2）：PASS ∧ 激活模型已登记已知坏 ⇒ 「自检面结论」与「已登记坏结论」
            // 并列呈现 + 免责一行（展示层全部经 probePresentation 纯函数产出，JVM 可测）。
            GlassCard(contentPadding = PaddingValues(14.dp)) {
                Column {
                    Text(
                        text = "模型自检",
                        style = MaterialTheme.typography.titleSmall,
                        color = colors.onGlass,
                    )
                    Text(
                        text = "对当前模型跑两条固定短 prompt，检查输出是否退化（保留 token / 复读 / " +
                            "空输出）。手动触发；会重建一次会话，用户下一条消息会重新 prefill。",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onGlassSubtle,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                    val presentation = probePresentation(probeState, knownBrokenOn)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = presentation.headline,
                            style = MaterialTheme.typography.labelMedium,
                            color = probeToneColor(presentation.headlineTone),
                            modifier = Modifier.weight(1f),
                        )
                        GlassButton(
                            text = "运行自检",
                            onClick = onRunProbe,
                            loading = probeState is ProbeUiState.Running,
                            enabled = probeState !is ProbeUiState.Running,
                            modifier = Modifier.padding(start = 10.dp),
                        )
                    }
                    // 第二行：耗时·采样预览（或执行失败澄清）；null = 不渲染。
                    presentation.detail?.let { line ->
                        Text(
                            text = line.text,
                            style = MaterialTheme.typography.labelSmall,
                            color = probeToneColor(line.tone),
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                    // 并列登记行（W71 B2；W72 起也覆盖 NotRun(ENGINE_NOT_LOADED)）。
                    presentation.knownBroken?.let { line ->
                        Text(
                            text = line.text,
                            style = MaterialTheme.typography.labelSmall,
                            color = probeToneColor(line.tone),
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    // 免责一行（W71 B3 / I-2）：仅 PASS；与登记行同家族（warning）口径。
                    presentation.disclaimer?.let { line ->
                        Text(
                            text = line.text,
                            style = MaterialTheme.typography.labelSmall,
                            color = probeToneColor(line.tone),
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    // 命中明细（仅 Completed 有）：HARD=danger / SOFT=warning，与结论同口径。
                    if (probeState is ProbeUiState.Completed) {
                        for (hit in probeState.hits) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 4.dp),
                                verticalAlignment = Alignment.Top,
                            ) {
                                Text(
                                    text = "${hit.id} · ${hit.severity.name}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (hit.severity == CriterionSeverity.HARD) {
                                        colors.danger
                                    } else {
                                        colors.warning
                                    },
                                )
                                Text(
                                    text = hit.evidence,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = colors.onGlassSubtle,
                                    modifier = Modifier.padding(start = 8.dp),
                                )
                            }
                        }
                    }
                }
            }

            /* ------------------------------------------ 物理量（Wave 30 性能采样） */
            if (perfHeader != null) {
                GlassCard(contentPadding = PaddingValues(14.dp)) {
                    Column {
                        Text(
                            text = "物理量",
                            style = MaterialTheme.typography.titleSmall,
                            color = colors.onGlass,
                        )
                        Text(
                            text = perfHeader,
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onGlassSubtle,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                        // 电池温度（W81，审查 P1-3）：ThermalGovernor 用电池温度 ≥44.9℃
                        // 做熔断主判据（W43），但此前诊断页零展示 ⇒ 用户被「拒新 run /
                        // 轮间冷却」却不知情。口径与 AppContainer 给 ThermalGovernor 的
                        // batteryTempTenths **同源**（同一个 ACTION_BATTERY_CHANGED
                        // sticky broadcast 的 EXTRA_TEMPERATURE，十分之一℃）—— 不另起
                        // 采集源、不 1Hz 轮询 registerReceiver(null, …)（每次一次
                        // binder，审查明确警告）。
                        var batteryTempTenths by remember {
                            mutableIntStateOf(Int.MIN_VALUE)
                        }
                        DisposableEffect(Unit) {
                            val receiver = object : BroadcastReceiver() {
                                override fun onReceive(ctx: Context?, intent: Intent?) {
                                    batteryTempTenths = intent?.getIntExtra(
                                        BatteryManager.EXTRA_TEMPERATURE,
                                        Int.MIN_VALUE,
                                    ) ?: Int.MIN_VALUE
                                }
                            }
                            val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
                            // 进程内接收，不被外部唤起；API 33+ 必须显式
                            // RECEIVER_NOT_EXPORTED（ContextCompat 跨版本安全，
                            // 与 ScreenStateMonitor 同款）。ACTION_BATTERY_CHANGED 是
                            // 受保护的系统广播，NOT_EXPORTED 不影响系统侧投递。
                            val sticky = ContextCompat.registerReceiver(
                                context,
                                receiver,
                                filter,
                                ContextCompat.RECEIVER_NOT_EXPORTED,
                            )
                            // registerReceiver 的返回值即当前 sticky intent —— 立即取
                            // 初值，无需等下一次广播（温度变化很慢，粘性值就是现状），
                            // 也免掉一次 registerReceiver(null, …) 的 binder 往返。
                            if (sticky != null) receiver.onReceive(context, sticky)
                            onDispose { context.unregisterReceiver(receiver) }
                        }
                        PerfRow(
                            "电池温度",
                            if (batteryTempTenths != Int.MIN_VALUE) {
                                // 保护线引用 ThermalGovernor 的常量（W82 单源化）：此前这里
                                // 硬编码 "44.9"，熔断线一调就与真实判据脱钩 —— 而这一行的
                                // 全部价值就是让用户看懂「为什么被拒」，报错数字必须同源。
                                "%.1f℃（保护线 ${ThermalGovernor.BATTERY_FUSE_CELSIUS}℃）"
                                    .format(batteryTempTenths / 10f)
                            } else {
                                "—"
                            },
                        )
                        val latestSample = perfSamples.lastOrNull()
                        if (latestSample == null) {
                            Text(
                                text = "暂无样本：打开本页或运行任务时开始采样（1s 间隔）",
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.onGlassSubtle,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        } else {
                            // CPU-秒曲线（单调递增；斜率即占用强度）。
                            CpuSecondsChart(
                                samples = perfSamples,
                                color = colors.onGlass,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(64.dp)
                                    .padding(top = 8.dp),
                            )
                            PerfRow("CPU 累计", "%.1f s".format(latestSample.cpuSeconds))
                            PerfRow(
                                "CPU 占比（最近 1s）",
                                latestSample.cpuPercent?.let { "%.0f%%".format(it) } ?: "—",
                            )
                            PerfRow("PSS", "${latestSample.pssKb / 1024} MB")
                            PerfRow("可用内存", "${latestSample.availMemBytes / (1024 * 1024)} MB")
                        }
                    }
                }
            }

            /* ------------------------------------------ 帧统计（W81，审查 P0-1 的 07a 档） */
            // 仅 debug 构建显示：采集器按 FLAG_DEBUGGABLE 门控，release 包恒零行为。
            // 为什么帧级数据只剩这条路：W80-01 取证 PDRM00 dumpsys gfxinfo 帧统计恒 0
            // （设备级不可用）；JankStats 底层就是 Choreographer.FrameCallback 封装，但引它
            // 要动版本锁死纪律 —— 零依赖自采覆盖「滚动 / 生成期掉帧率」这个唯一消费场景
            // （详见 FrameStatsCollector KDoc）。W82：订阅与渲染整体搬进 FrameStatsCard，
            // 本函数不再持有任何帧统计数据（4Hz 发布只重组卡片）。
            if (frameStatsCollector != null && frameStatsCollector.isDebuggable) {
                FrameStatsCard(collector = frameStatsCollector)
            }

            /* ------------------------------------------ 上次崩溃前的记录（磁盘） */
            // 这一区是整个诊断设施存在的理由：崩溃 = 进程死 = 内存缓冲全没，
            // 所以「最需要日志的场景」只能靠落盘文件回答。
            if (showPersisted && persisted.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "上次崩溃前的记录（${persisted.size} 条）",
                            style = MaterialTheme.typography.titleSmall,
                            color = colors.onGlass,
                        )
                        Text(
                            text = "来自磁盘：ERROR 级在写入时即落盘，重启 App 后仍可回看",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onGlassSubtle,
                        )
                    }
                    GlassButton(
                        text = "清空",
                        onClick = {
                            scope.launch {
                                withContext(Dispatchers.IO) { clearPersistedErrors() }
                                persisted = withContext(Dispatchers.IO) { readPersistedErrors() }
                            }
                        },
                        modifier = Modifier.padding(start = 10.dp),
                    )
                }
                for (log in persisted.asReversed()) {
                    LogRow(log)
                }
            }

            /* ------------------------------------------ 本次运行（内存） */
            Text(
                text = "本次运行（内存 ${snapshot.size} 条 · 上限 ${AgentLogStore.DEFAULT_CAPACITY}）",
                style = MaterialTheme.typography.titleSmall,
                color = colors.onGlass,
                modifier = Modifier.padding(top = 4.dp),
            )

            if (visible.isEmpty()) {
                GlassEmptyState(
                    title = "暂无日志",
                    subtitle = "正常轮次不会写日志，所以空是正常的",
                )
            }

            // 最新的排在最上面：真出问题时不用先滚到底。
            for (log in visible.asReversed()) {
                LogRow(log)
            }

            Box(modifier = Modifier.size(tokens.bottomBarHeight))
        }
    }
}

@Composable
private fun LogRow(log: AgentLog) {
    val colors = LocalGlassColors.current
    GlassCard(contentPadding = PaddingValues(12.dp)) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = log.level.name,
                    style = MaterialTheme.typography.labelMedium,
                    color = levelColor(log.level),
                )
                Text(
                    text = formatLogTime(log.atMillis),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onGlassSubtle,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            Text(
                text = log.message,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = colors.onGlass,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** 能力行：左侧名称 + 右侧「支持/不支持」结论，配色沿用本页日志级别的既有口径。 */
@Composable
private fun CapabilityRow(
    label: String,
    supported: Boolean,
    supportedText: String,
    unsupportedText: String,
) {    val colors = LocalGlassColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = colors.onGlass,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = if (supported) supportedText else unsupportedText,
            style = MaterialTheme.typography.labelMedium,
            color = if (supported) colors.onGlassMuted else colors.warning,
        )
    }
}

/** 物理量行：左侧指标名 + 右侧当前值（形态对齐 [CapabilityRow]，无支持/不支持语义）。 */
@Composable
private fun PerfRow(label: String, value: String) {
    val colors = LocalGlassColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = colors.onGlass,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace,
            color = colors.onGlass,
        )
    }
}

/**
 * 帧统计卡（W81 引入；W82 改为**自订阅** + 并列会话累计）。
 *
 * 为什么订阅点在这里而不在 [DiagnosticsScreen]：订阅点就是重组边界。采集器按 4Hz 发布快照，
 * 此前 `stats` 在路由级 collect 再当参数传下来 ⇒ 每次发布都让本页 1000+ 行整体重组（W81
 * 真机实证：每帧发布把本页压到 ~5fps；节流到 4Hz 只是把「每帧」降成「每 250ms」，重组范围
 * 一点没收窄）。订阅搬进卡片后，同一个 4Hz 只重组这一张卡 —— 节流与收窄是两道互补的闸。
 *
 * 为什么并列两套数：窗口（最近 2s）答「现在」，会话（自采集开始累计）答「这一整段」。要测的
 * 跨页链路（导航到对话页 + 操作 + 返回）比窗口长，只看窗口永远只剩返回动画那 2s。
 *
 * 采集器生命周期归 [FrameStatsCollector]（debug 构建进程启动即开始、跨页持续），本卡只做
 * 开关与重置 —— 页面进出不再 start/stop，否则「跨页测量」在离开本页那一刻就断了。
 */
@Composable
private fun FrameStatsCard(collector: FrameStatsCollector, modifier: Modifier = Modifier) {
    val colors = LocalGlassColors.current
    val stats by collector.stats.collectAsState()
    val session by collector.session.collectAsState()
    val active by collector.active.collectAsState()
    // 委托属性不能智能转换（自定义 getter）⇒ 先落成局部快照再判空。
    val window = stats
    val total = session
    GlassCard(modifier = modifier, contentPadding = PaddingValues(14.dp)) {
        Column {
            Text(
                text = "帧统计",
                style = MaterialTheme.typography.titleSmall,
                color = colors.onGlass,
            )
            Text(
                text = "仅 debug 构建；采集自进程启动即开始、跨页持续。掉帧 = 帧间隔超过 " +
                    "${JANK_THRESHOLD_NANOS / 1_000_000}ms。",
                style = MaterialTheme.typography.labelSmall,
                color = colors.onGlassSubtle,
                modifier = Modifier.padding(top = 2.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (active) "采集中…" else "未采集",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onGlass,
                    modifier = Modifier.weight(1f),
                )
                GlassButton(
                    text = if (active) "停止采集" else "开始采集",
                    onClick = { if (active) collector.stop() else collector.start() },
                )
            }
            /* ── 窗口（最近 2s）：答「现在」 ───────────────────────────── */
            if (window == null) {
                Text(
                    text = "暂无窗口数据：滚动列表或等待生成（约 2s 出结论）",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onGlassSubtle,
                    modifier = Modifier.padding(top = 6.dp),
                )
            } else {
                PerfRow("FPS（最近 2s）", "%.0f".format(window.fps))
                PerfRow("掉帧率（最近 2s）", "%.1f%%".format(window.jankyPercent))
                PerfRow("窗口帧数", "${window.frames}（其中掉帧 ${window.jankyFrames}）")
            }
            /* ── 会话累计：答「这一整段」（跨页也连续） ─────────────────── */
            if (total != null) {
                PerfRow("会话帧数", "${total.totalFrames}（其中掉帧 ${total.totalJankyFrames}）")
                PerfRow("会话掉帧率", "%.1f%%".format(total.jankyPercent))
                // 会话时长 = Σ 帧间隔（帧覆盖口径，刻意不取墙钟差）：后台 / 冻结期间无帧，
                // 墙钟差会让 FPS 假性暴跌（W78 实测单次冻结 416s）⇒ 数字会远小于实际经过时间。
                PerfRow("会话帧覆盖时长", "%.1f s".format(total.sessionNanos / 1_000_000_000f))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    // 重置只清会话、不动窗口：窗口答「现在」，清掉它等于把即时读数也抹了。
                    GlassButton(text = "重置会话", onClick = collector::resetSession)
                }
            }
        }
    }
}

/**
 * CPU-秒折线（端侧裁剪版曲线区）：横轴 = **真实时间**（可见缓冲区内首末样本的
 * atElapsedMillis 跨度映射），纵轴 = 进程累计 CPU 秒归一化到窗口最大值。曲线单调递增
 * 是健康形态，斜率即占用强度。
 *
 * 为什么按时间定位而不是样本序号（W81，审查 P1-2）：W78 实测 ColorOS 灭屏 ~10s 冻结
 * 进程、采样线程随停 —— 实测一次冻 416s ⇒ 时间戳存在数分钟缺口。按序号等距画图会把
 * 冻结缺口抹平成连续线（曲线谎称「那段时间也在采样」）；按时间定位后，缺口显式断线
 * （相邻样本间隔 > [GAP_THRESHOLD_MILLIS] 时 moveTo 而非 lineTo）—— 断线才是真相。
 * 后台保活落地后冻结从边缘场景变日常，这条口径随之事关正确性。
 */
@Composable
private fun CpuSecondsChart(
    samples: List<PerfSample>,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        if (samples.size < 2) return@Canvas
        val maxCpu = samples.maxOf { it.cpuSeconds }.coerceAtLeast(1e-6)
        val firstAt = samples.first().atElapsedMillis
        val lastAt = samples.last().atElapsedMillis
        val span = lastAt - firstAt
        // span <= 0（时间戳异常/全部同刻）时退回序号等距：至少画得出曲线 —— 观测面
        // 绝不能因为除零崩 Composable。span > 0 时 x = 样本时刻在跨度内的真实占比。
        val stepX = if (span > 0) size.width / span else size.width / (samples.size - 1)
        val path = Path()
        var previousAt = 0L
        samples.forEachIndexed { index, sample ->
            val x = if (span > 0) {
                (sample.atElapsedMillis - firstAt).toFloat() / span * size.width
            } else {
                index * stepX
            }
            val y = size.height - ((sample.cpuSeconds / maxCpu) * size.height).toFloat()
            // 缺口断线：与上一样本间隔超过阈值（2.5 个采样周期）⇒ 提笔。冻结 / 采样
            // 线程停摆期间没有数据，连线就是伪造连续性（见 KDoc 的 W78 取证）。
            if (index == 0 || sample.atElapsedMillis - previousAt > GAP_THRESHOLD_MILLIS) {
                path.moveTo(x, y)
            } else {
                path.lineTo(x, y)
            }
            previousAt = sample.atElapsedMillis
        }
        drawPath(path, color = color, style = Stroke(width = 2.dp.toPx()))
    }
}

/**
 * 曲线断线阈值（W81，审查 P1-2）：相邻样本间隔超过 2.5 个采样周期（采样间隔 1s，
 * 见 `PerformanceMonitorManager.SAMPLE_INTERVAL_MILLIS`）即判定为缺口 —— 提笔断线，
 * 不把冻结期（W78 实测单次 416s）画成连续曲线。
 */
private const val GAP_THRESHOLD_MILLIS = 2_500L

/**
 * 自检结论行的配色意图（W71 B2）：纯函数侧只表达「用哪支颜色」，**不触碰**
 * `LocalGlassColors` —— 映射收敛在 [probeToneColor]，纯逻辑因此可在 JVM 上单测。
 */
internal enum class ProbeTone {
    /** PASS 中性结论（历史口径：`onGlassMuted`）。 */
    NEUTRAL,

    /** DEGRADED / 执行失败 / knownBroken 登记行 / 免责行（历史口径：`warning`）。 */
    WARNING,

    /** BAD（历史口径：`danger`）。 */
    DANGER,

    /** 未运行 / 空闲 / 第二行的弱提示（历史口径：`onGlassSubtle`）。 */
    SUBTLE,

    /** 跑中的常规前景（历史口径：`onGlass`）。 */
    ONGLASS,
}

/** 结论卡的补充行：一行文字 + 它的配色意图（W71 B2 并列登记行 / B3 免责行）。 */
internal data class ProbeCardLine(val text: String, val tone: ProbeTone)

/**
 * 自检结论卡的展示要素（W71 B2/B3）：[probePresentation] 的产出。颜色经 [ProbeTone]
 * 间接表达 ⇒ 纯逻辑 JVM 可测（`DiagnosticsProbePresentationTest`）。
 *
 * @param headline 结论行文字。
 * @param headlineTone 结论行配色意图。
 * @param detail 第二行（耗时·采样预览 / 执行失败澄清）；null = 不渲染。
 * @param knownBroken 并列登记行（`Completed(PASS)` ∧ `knownBrokenOn != null`，
 *   或 W72 起的 `NotRun(ENGINE_NOT_LOADED)` ∧ `knownBrokenOn != null`）。
 * @param disclaimer 免责行（仅 `Completed(PASS)`）。
 */
internal data class ProbePresentation(
    val headline: String,
    val headlineTone: ProbeTone,
    val detail: ProbeCardLine?,
    val knownBroken: ProbeCardLine?,
    val disclaimer: ProbeCardLine?,
)

/** 免责一行（W71 B3 / I-2）：PASS 的语义诚实化 —— 未检出已知失败面 ≠ 输出质量保证。 */
private const val PROBE_PASS_DISCLAIMER = "PASS = 未检出已知失败面，不构成输出质量保证"

/**
 * 登记行文案（W71 B2 引入，W72 起被两处共用）：`Completed(PASS)` 的并列呈现与
 * `NotRun(ENGINE_NOT_LOADED)` 的登记行**必须逐字一致**（W71 真机 UI dump 判据钉的
 * 就是「该模型已登记已知不可用：」这个前缀）—— 各写一份迟早漂移，故收敛为一处。
 */
private fun knownBrokenLine(raw: String): ProbeCardLine =
    ProbeCardLine("该模型已登记已知不可用：$raw", ProbeTone.WARNING)

/**
 * 自检结论卡的展示要素（W71 B2/B3）：`ProbeUiState` × 激活模型 knownBroken 签名 ⇒
 * 结论行 / 第二行 / 登记行 / 免责行的全部文案与配色意图。
 *
 * ## 并列呈现语义（W71 产品件，T-2/I-2）
 *
 * `Completed(PASS)` ∧ `knownBrokenOn != null` ⇒ 结论行改「结论：通过（自检面）」
 * （**本次自检面**结论）+ 追加「该模型已登记已知不可用：原文」（warning，与
 * DEGRADED 同口径）——「已登记的坏结论」与「本次自检面结论」并列，**不拦截、
 * 只告知**（与 W68 对话页激活态重校验提示同范式）。
 *
 * `knownBrokenOn == null`（无激活模型 / 未登记 / 非 preset）⇒ 结论行与第二行
 * **逐字维持** W71 前文案 —— ⛔ 不得把「未登记」显示成「已登记坏」。
 *
 * 免责行对**任意 PASS** 生效：探针 PASS = 未检出已知失败面 ≠ 输出质量保证 ——
 * 假阴性盲区对「未登记坏」的模型同样成立（M14 登记簿），只对已登记坏显示会把
 * 最大的误读面（健康模型）漏掉。
 *
 * ## W72：`NotRun(ENGINE_NOT_LOADED)` 也追加登记行（唯一的 NotRun 例外）
 *
 * 为什么必须加：两条 knownBroken 预设（Gemma 4 E2B·GPU / E4B·GPU）登记的坏签名
 * 恰恰是「**引擎初始化 NOT_FOUND**」。用户在未发消息（引擎未加载）时打开诊断页，
 * 原实现只显示「请先在模型页加载模型，再运行自检」—— 等于把用户引向一个
 * **按本仓自己登记的结论不可能成功**的动作（加载即 NOT_FOUND），且页面上没有任何
 * 一处告诉他这件事。加的是「**已登记的结论**」的可见性，不是把 NotRun 说成 BAD。
 *
 * ### 这个引导在现役运行时上「必然失败」—— 实测双证，不是推测
 *
 * W72 的 0.18.0 条件真机臂（`_research/w72-0180-device-arm.md` §一.2，判据汇总表 §三）：
 *
 *  - **现役 0.17.1**：`gemma-4-E2B-it-gpu`（uuid `d0de1e7a-…`）**引擎初始化
 *    NOT_FOUND**（容器缺 `TF_LITE_PREFILL_DECODE` section）⇒ **会话根本建不起来**；
 *  - **0.18.0 条件臂**：同一条 loader 信息降级为 W 级 warning 并继续，实际后端
 *    `GPU_ARTISAN`，会话建成、38 token @ 18.34 tok/s（同臂 CPU 仅 0.47 tok/s，≈39×）
 *    ⇒ 证明 0.17.1 的 NOT_FOUND 是**版本面**，不是「模型文件坏了」。
 *
 * 即：在现役 0.17.1 上，对这两条已登记容器，「请先在模型页加载模型」所指向的动作
 * **必然失败**。此时页面只显示那句引导，等于让用户去撞一堵本仓自己登记过、且刚被
 * 实测确认的墙 —— 这正是「失败可见化」要避免的形态。
 *
 * ⚠️ **证据边界**：该臂实测的是 `E2B·GPU`；`E4B·GPU` 未在 0.18.0 单独复测，其结论
 * 来自**同源登记**（`knownBrokenOn` 明写「与 E2B·GPU 同源」，W60 同一失败面）。
 * bump 后这两条 knownBroken 的订正走 `docs/12` **锚 #14「knownBroken / GPU 白名单
 * 过期复审」人工复审面**（不是自动清空 —— 该臂同时确认 0.18.0 下「输出退化」半边仍在）。
 *
 * ⛔ **不改变 Wave 44「未运行 ≠ 质量结论」口径**：headline 仍是 NotRun 原文案
 * （`SUBTLE` 色、不改成 DANGER、不出现 PASS/BAD 字样），登记行是**并列追加**；
 * 免责行**不加** —— NotRun 不是 PASS，不存在「PASS 被误读成输出质量保证」那个面。
 *
 * 为什么只扩这一个 reason：`ENGINE_BUSY`（正在生成）与 `NO_ACTIVE_MODEL`
 * （无激活模型 ⇒ `knownBrokenOn` 恒 null）都没有「用户即将执行一个已登记必败动作」
 * 的误导面；扩面越大，越容易让 NotRun 被读成质量结论。**扩面须先有同等级理由。**
 *
 * ## 不变量（`DiagnosticsProbePresentationTest` 钉死）
 *
 * - 非 PASS 各态（DEGRADED / BAD / Failed / Idle / Running，以及
 *   `NotRun(ENGINE_BUSY)` / `NotRun(NO_ACTIVE_MODEL)`）**不受** `knownBrokenOn`
 *   影响：无登记行、无免责行，文案逐字不动；
 * - **唯一例外 = `NotRun(ENGINE_NOT_LOADED)`**（W72）：headline / detail 逐字不动，
 *   仅当 `knownBrokenOn != null` 时追加登记行（WARNING），免责行仍恒 null；
 * - PASS ∧ broken=null 的 headline / detail 与 W71 前逐字一致。
 */
internal fun probePresentation(state: ProbeUiState, knownBrokenOn: String?): ProbePresentation =
    when (state) {
        ProbeUiState.Idle -> ProbePresentation(
            headline = "尚未运行",
            headlineTone = ProbeTone.SUBTLE,
            detail = null,
            knownBroken = null,
            disclaimer = null,
        )
        ProbeUiState.Running -> ProbePresentation(
            headline = "正在自检…",
            headlineTone = ProbeTone.ONGLASS,
            detail = null,
            knownBroken = null,
            disclaimer = null,
        )
        is ProbeUiState.Completed -> {
            val preview = state.sample.replace('\n', ' ').take(120)
            val sampling = ProbeCardLine(
                text = "耗时 ${state.elapsedMs}ms · 采样：${preview.ifBlank { "（空）" }}",
                tone = ProbeTone.SUBTLE,
            )
            when (state.verdict) {
                ModelHealthVerdict.PASS -> ProbePresentation(
                    // 并列呈现：已登记坏 ⇒ 结论行降为「自检面」口径 + 追加登记行；
                    // 未登记 ⇒ 逐字维持「结论：通过（PASS）」。
                    headline = if (knownBrokenOn != null) {
                        "结论：通过（自检面）"
                    } else {
                        "结论：通过（PASS）"
                    },
                    headlineTone = ProbeTone.NEUTRAL,
                    detail = sampling,
                    knownBroken = knownBrokenOn?.let(::knownBrokenLine),
                    disclaimer = ProbeCardLine(PROBE_PASS_DISCLAIMER, ProbeTone.WARNING),
                )
                ModelHealthVerdict.DEGRADED -> ProbePresentation(
                    headline = "结论：降级（DEGRADED）",
                    headlineTone = ProbeTone.WARNING,
                    detail = sampling,
                    knownBroken = null,
                    disclaimer = null,
                )
                ModelHealthVerdict.BAD -> ProbePresentation(
                    headline = "结论：异常（BAD）",
                    headlineTone = ProbeTone.DANGER,
                    detail = sampling,
                    knownBroken = null,
                    disclaimer = null,
                )
            }
        }
        is ProbeUiState.NotRun -> {
            // ⚠️ 「未运行」与「质量结论」必须文案区分：它不是「模型坏」的结论，
            // 否则会把「引擎没加载」误导成「模型有问题」（Wave 44 口径延续）。
            val reason = when (state.reason) {
                ModelHealthProbe.NotRunReason.ENGINE_NOT_LOADED -> "请先在模型页加载模型，再运行自检"
                ModelHealthProbe.NotRunReason.ENGINE_BUSY -> "正在生成，请稍后再试"
                ModelHealthProbe.NotRunReason.NO_ACTIVE_MODEL -> "当前没有激活模型"
            }
            ProbePresentation(
                // headline / detail 逐字维持 Wave 44 口径：登记行是**并列追加**，
                // 不把「未运行」改写成质量结论（理由见 KDoc「W72」段）。
                headline = reason,
                headlineTone = ProbeTone.SUBTLE,
                detail = null,
                // W72 唯一扩面：`ENGINE_NOT_LOADED` ∧ 已登记坏 ⇒ 追加登记行（否则用户被
                // 引向一个按登记结论不可能成功的动作）。其余两个 reason 恒 null。
                knownBroken = if (state.reason == ModelHealthProbe.NotRunReason.ENGINE_NOT_LOADED) {
                    knownBrokenOn?.let(::knownBrokenLine)
                } else {
                    null
                },
                // 免责行不加：NotRun 不是 PASS，没有「PASS 被误读成输出质量保证」的面。
                disclaimer = null,
            )
        }
        is ProbeUiState.Failed -> ProbePresentation(
            headline = "自检未跑通：${state.message}",
            headlineTone = ProbeTone.WARNING,
            detail = ProbeCardLine(
                text = "执行失败（非质量结论）：这不代表模型坏，请确认模型已加载、设备未过热后重试",
                tone = ProbeTone.SUBTLE,
            ),
            knownBroken = null,
            disclaimer = null,
        )
    }

/**
 * [ProbeTone] → 玻璃配色映射（历史口径逐字沿用 Wave 44 的 `probeHeadlineColor`：
 * PASS=`onGlassMuted` / DEGRADED=`warning` / BAD=`danger`，与 [CapabilityRow] 同口径）。
 */
@Composable
private fun probeToneColor(tone: ProbeTone): Color {
    val colors = LocalGlassColors.current
    return when (tone) {
        ProbeTone.NEUTRAL -> colors.onGlassMuted
        ProbeTone.WARNING -> colors.warning
        ProbeTone.DANGER -> colors.danger
        ProbeTone.SUBTLE -> colors.onGlassSubtle
        ProbeTone.ONGLASS -> colors.onGlass
    }
}

@Composable
private fun levelColor(level: AgentLogLevel): Color {
    val colors = LocalGlassColors.current
    return when (level) {
        AgentLogLevel.INFO -> colors.onGlassMuted
        AgentLogLevel.WARN -> colors.warning
        AgentLogLevel.ERROR -> colors.danger
    }
}

/** 一次最多取多少条：与环形缓冲容量一致，读全量即可。 */
private val MAX_SHOWN: Int = AgentLogStore.DEFAULT_CAPACITY

private val FILTER_LABELS: List<String> = listOf("全部", "INFO", "WARN", "ERROR")

/** 只在主线程（Compose）使用，故不做并发保护。 */
private val LOG_TIME_FORMAT = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

private fun formatLogTime(atMillis: Long): String = LOG_TIME_FORMAT.format(Date(atMillis))

/** 一行日志的纯文本形态：「时间  级别  消息」。 */
private fun formatLogLine(log: AgentLog): String =
    "${formatLogTime(log.atMillis)}  ${log.level.name}  ${log.message}"

/**
 * 两区日志拼成可粘贴的纯文本：**最新的排在最上面**（与页面呈现顺序一致，避免复制出来
 * 和屏幕上看到的是反的，那会让「贴给别人看」变成事故现场）。
 *
 * 刻意保留「磁盘区 / 内存区」两个小标题：粘贴出去的受众看不到本页的分区卡片，
 * 丢了标题就分不清哪条是崩溃前的、哪条是本次运行的。
 */
private fun diagnosticsPlainText(persisted: List<AgentLog>, visible: List<AgentLog>): String =
    buildString {
        if (persisted.isNotEmpty()) {
            appendLine("── 上次崩溃前的记录（磁盘 ${persisted.size} 条）──")
            persisted.asReversed().forEach { appendLine(formatLogLine(it)) }
            appendLine()
        }
        appendLine("── 本次运行（内存 ${visible.size} 条）──")
        visible.asReversed().forEach { appendLine(formatLogLine(it)) }
    }

/** 下标 0 = 全部（不过滤）。 */
private fun levelOfFilter(index: Int): AgentLogLevel? = when (index) {
    1 -> AgentLogLevel.INFO
    2 -> AgentLogLevel.WARN
    3 -> AgentLogLevel.ERROR
    else -> null
}
