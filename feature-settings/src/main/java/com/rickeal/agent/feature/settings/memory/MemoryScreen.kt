package com.rickeal.agent.feature.settings.memory

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rickeal.agent.core.agent.memory.MemorySection
import com.rickeal.agent.core.design.GlassButton
import com.rickeal.agent.core.design.GlassCard
import com.rickeal.agent.core.design.GlassIconButton
import com.rickeal.agent.core.design.GlassMaterial
import com.rickeal.agent.core.design.GlassScaffold
import com.rickeal.agent.core.design.GlassTextField
import com.rickeal.agent.core.design.GlassTopBar
import com.rickeal.agent.core.design.LiquidDialog
import com.rickeal.agent.core.design.LocalBottomBarOverlay
import com.rickeal.agent.core.design.LocalGlassColors
import com.rickeal.agent.core.data.LocalAppContainer
import com.rickeal.agent.core.data.viewModelFactory
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object MemoryRoute {
    const val ROUTE = "memory"

    fun build(): String = ROUTE
}

fun memoryViewModelFactory(container: com.rickeal.agent.core.data.AppContainer): ViewModelProvider.Factory =
    viewModelFactory { MemoryViewModel(container) }

/**
 * 长期记忆管理页（Wave4 UI 五页签新增）。
 *
 * 记忆条目由模型经 memory_write 沉淀，也可由用户在此人工维护：
 *  - 查看：全部条目按更新时间排列（AgentMemory 侧 takeLast 淘汰，最新在后）；
 *  - 新增 / 编辑：同一弹窗，按标题幂等（与 memory_write 工具同语义）；
 *  - 删除：二次确认（LiquidDialog），防误触。
 */
@Composable
fun MemoryScreen(
    viewModel: MemoryViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsState()
    val colors = LocalGlassColors.current

    var editing by remember { mutableStateOf<MemorySection?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<MemorySection?>(null) }

    GlassScaffold(
        modifier = modifier,
        topBar = {
            GlassTopBar(
                title = "记忆",
                subtitle = "长期记忆 · 跨会话沉淀 · 人工可编辑",
                modifier = Modifier.statusBarsPadding(),
            )
        },
    ) { _ ->
        Column(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(
                    start = 14.dp, top = 12.dp, end = 14.dp,
                    // + 悬浮页签占位（2026-09-26），见 LocalBottomBarOverlay KDoc。
                    bottom = 12.dp + LocalBottomBarOverlay.current,
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    GlassButton(
                        text = if (state.sections.isEmpty()) "记下第一条" else "新增记忆",
                        onClick = { creating = true },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (state.sections.isEmpty() && !state.loading) {
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 40.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Psychology,
                                contentDescription = null,
                                tint = colors.onGlassSubtle,
                                modifier = Modifier.size(36.dp),
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = "还没有长期记忆。模型沉淀的偏好与项目事实会出现在这里；也可以手动记录。",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onGlassSubtle,
                            )
                        }
                    }
                }
                items(state.sections, key = { it.title }) { section ->
                    MemoryCard(
                        section = section,
                        onEdit = { editing = section },
                        onDelete = { deleting = section },
                    )
                }
            }

            val notice = state.message
            if (notice != null) {
                Box(modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp), contentAlignment = Alignment.Center) {
                    Text(
                        text = notice,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onGlassMuted,
                    )
                }
            }
        }
    }

    if (creating || editing != null) {
        MemoryEditDialog(
            initial = editing,
            onDismiss = {
                creating = false
                editing = null
            },
            onConfirm = { title, content ->
                viewModel.upsert(title, content) {
                    creating = false
                    editing = null
                }
            },
        )
    }

    deleting?.let { target ->
        LiquidDialog(
            onDismissRequest = { deleting = null },
            title = "删除记忆",
            actions = { dismiss ->
                GlassButton(text = "取消", onClick = dismiss, material = GlassMaterial.THIN)
                GlassButton(text = "删除", onClick = {
                    viewModel.remove(target.title)
                    dismiss()
                })
            },
        ) {
            Text(
                text = "确定删除「${target.title}」？该条目会从模型的长期记忆中移除，不可恢复。",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onGlass,
            )
        }
    }
}

@Composable
private fun MemoryCard(
    section: MemorySection,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = LocalGlassColors.current
    val dateText = remember(section.updatedAtMillis) {
        if (section.updatedAtMillis <= 0L) "" else
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(section.updatedAtMillis))
    }
    GlassCard(contentPadding = PaddingValues(14.dp)) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = section.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.onGlass,
                    modifier = Modifier.weight(1f),
                )
                GlassIconButton(onClick = onEdit) {
                    Icon(
                        imageVector = Icons.Filled.Edit,
                        contentDescription = "编辑",
                        tint = colors.accent,
                        modifier = Modifier.size(16.dp),
                    )
                }
                GlassIconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = "删除",
                        tint = colors.danger,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            Text(
                text = section.content,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onGlassMuted,
                modifier = Modifier.padding(top = 6.dp),
            )
            if (dateText.isNotEmpty()) {
                Text(
                    text = dateText,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onGlassSubtle,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun MemoryEditDialog(
    initial: MemorySection?,
    onDismiss: () -> Unit,
    onConfirm: (title: String, content: String) -> Unit,
) {
    val colors = LocalGlassColors.current
    var title by remember(initial) { mutableStateOf(initial?.title ?: "") }
    var content by remember(initial) { mutableStateOf(initial?.content ?: "") }
    val valid = title.isNotBlank() && content.isNotBlank()

    LiquidDialog(
        onDismissRequest = onDismiss,
        title = if (initial == null) "新增记忆" else "编辑记忆",
        actions = { dismiss ->
            GlassButton(text = "取消", onClick = dismiss, material = GlassMaterial.THIN)
            // 旧 confirmLabel/onConfirm 的条件签名（valid 才可保存）映射为条件确认按钮。
            if (valid) {
                GlassButton(text = "保存", onClick = {
                    onConfirm(title.trim(), content.trim())
                    dismiss()
                })
            }
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            GlassTextField(
                value = title,
                onValueChange = { title = it },
                placeholder = "标题（如：用户偏好 / 项目约定）",
                singleLine = true,
            )
            GlassTextField(
                value = content,
                onValueChange = { content = it },
                placeholder = "内容（结论本身；细节建议放沙箱文件）",
                maxLines = 6,
            )
            Text(
                text = "标题相同时覆盖原条目（与 memory_write 工具一致）。",
                style = MaterialTheme.typography.labelSmall,
                color = colors.onGlassSubtle,
            )
        }
    }
}
