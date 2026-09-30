package com.rickeal.agent.feature.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import com.rickeal.agent.core.data.perf.PerfSample
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
import com.rickeal.agent.core.model.AgentLog
import com.rickeal.agent.core.model.AgentLogLevel
import com.rickeal.agent.core.model.AgentLogStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 诊断页：展示进程内最近的运行日志（黑匣子），以及**上次崩溃前落盘的 ERROR 记录**。
 *
 * 为什么没有 ViewModel：日志收集器在 `:core-model`，那里**没有协程依赖**（该模块只有
 * kotlinx-serialization），因此没有 StateFlow 可以订阅。与其为此新增依赖或把状态机搬来搬去，
 * 不如老老实实「进页面取一次快照 + 手动刷新」—— 简单、无新依赖、行为可预期。
 *
 * 两个数据源刻意**分开呈现**、不合并：内存缓冲是「本次运行」，磁盘文件是「上次崩溃之前」。
 * 混在一起会让人误以为崩溃前的记录也在内存里（那样的话它们根本活不到现在）。
 *
 * 「复制全部」把这两区**当前可见**的日志拼成纯文本进剪贴板（取证/贴给他人看）。它是静默
 * 操作，因此**必须有 Toast 反馈**（成功与「剪贴板不可用」都要说）—— 没有反馈时用户无法
 * 区分「复制成功」与「点了没反应」，这正是本项目「失败可见化」铁律要避免的形态。
 *
 * @param readPersistedErrors 读回落盘的 ERROR 记录（阻塞 IO，调用方保证在 IO 线程执行）
 * @param clearPersistedErrors 清空落盘记录
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
            verticalArrangement = Arrangement.spacedBy(10.dp),
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
 * CPU-秒折线（端侧裁剪版曲线区）：横轴 = 样本序（1s/点，最多 180 点），纵轴 =
 * 进程累计 CPU 秒归一化到窗口最大值。曲线单调递增是健康形态，斜率即占用强度。
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
        val stepX = size.width / (samples.size - 1)
        val path = Path()
        samples.forEachIndexed { index, sample ->
            val x = index * stepX
            val y = size.height - ((sample.cpuSeconds / maxCpu) * size.height).toFloat()
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color = color, style = Stroke(width = 2.dp.toPx()))
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
