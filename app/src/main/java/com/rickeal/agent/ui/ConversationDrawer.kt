package com.rickeal.agent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rickeal.agent.core.design.GlassButton
import com.rickeal.agent.core.design.GlassMaterial
import com.rickeal.agent.core.design.LiquidGlassSurface
import com.rickeal.agent.core.design.LocalGlassColors
import com.rickeal.agent.core.design.LocalGlassConfig
import com.rickeal.agent.core.design.LocalGlassTokens
import com.rickeal.agent.core.model.ConversationMeta

/**
 * 抽屉宽度。与 M3 `ModalDrawerSheet` 的默认最大宽度（360dp）同量级，取 300dp 留出内容余量。
 *
 * ⚠️ **不要"顺手优化"成 `fillMaxWidth()`**：`ModalNavigationDrawer` 的抽屉槽位外层是
 * `fillMaxSize` 的 Box，若内容自己撑满宽度，抽屉会**占满整屏**（遮罩退化成一层色，
 * 看不出"抽屉浮在内容之上"）。显式锁 300dp 是**防御性正确**的 —— 它不依赖 M3 是否限宽。
 * 右侧是否留空隙（即槽位本身是否已限宽）取决于 M3 实现，需真机确认；但即使槽位已限宽，
 * 锁死 300dp 的结果也一致，所以保持现状最稳。
 */
private val DRAWER_WIDTH = 300.dp

/**
 * 左侧抽屉内容（Wave 10 Phase 2b C-3）—— **仅 COMPACT 使用**。
 *
 * 参照 WorkBuddy 的「云端▾ + 新建任务 + 任务列表 + 空间(N)」，但三处按 LiquidAgent 的
 * 真实语义降级，不照抄：
 *  - 「云端▾」：端侧应用没有可切换的云端工作区 → 做成**静态说明头**，不做假下拉；
 *  - 「新建任务」：与对话页顶栏「新对话」**结果态一致**（空会话、conversationId=null），
 *    但**实现路径不同** —— 本处重建 VM / 顶栏原地 reset（见 [onNewConversation] 的说明，
 *    与 `navigateConversation` 的 KDoc）。**别写成"同一个动作"。**
 *  - 「空间(N)」：无云端空间概念 → 降级为「沙箱工作区」入口（[onOpenSandbox]），
 *    **不编造一个假的计数**。
 *
 * 抽屉本体走液态玻璃（[LiquidGlassSurface]），内容全部由调用方注入的回调驱动 ——
 * 这里不认识导航、不认识 AppContainer，方便单独审视。
 *
 * @param metas 会话摘要列表（`ConversationRepository.metas`），按更新时间倒序。
 * @param engineBusy 引擎是否正忙（有在途生成）。忙时**禁用**会话行与「新建任务」，
 *   并在列表顶部给一条提示 —— 与存储空间页「正在生成，暂不可清理」同一形态。
 *   原因是切会话 / 新建会**销毁旧 ChatViewModel**，而 `onCleared` 只 cancel、不走
 *   `onStop()` 的收尾路径 ⇒ 半截回答既不显示也不落库（见 P1-1）。
 *   这里的禁用只是 UI 便利层，**权威闸门在调用方的回调里**再看一次。
 * @param onNewConversation 新建任务。
 * @param onOpenConversation 点选某个会话（参数是会话 id）。
 * @param onOpenSandbox 打开「沙箱工作区」。
 */
@Composable
internal fun ConversationDrawerContent(
    metas: List<ConversationMeta>,
    engineBusy: Boolean,
    onNewConversation: () -> Unit,
    onOpenConversation: (String) -> Unit,
    onOpenSandbox: () -> Unit,
) {
    val colors = LocalGlassColors.current
    val tokens = LocalGlassTokens.current

    LiquidGlassSurface(
        modifier = Modifier
            .fillMaxHeight()
            .width(DRAWER_WIDTH),
        material = GlassMaterial.THICK,
        // 贴左缘、通顶到底的抽屉：直角，不做胶囊。
        cornerRadius = 0.dp,
        contentPadding = PaddingValues(0.dp),
    ) {
        // 覆盖层 scrim（Wave 21）：alpha 由 GlassConfig.overlayOpacity 驱动（设置页
        // 「覆盖层不透明度」，与 LiquidDialog / 推理参数面板共用同一配置）。
        // 插在表面绘制之后、内容之前：matchParentSize 不占布局测量，纯视觉压暗。
        // 颜色在组合期解析 —— LiquidGlassSurface 的 content 是 BoxScope，这里
        // matchParentSize 合法。
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(colors.glassShadow.copy(alpha = LocalGlassConfig.current.overlayOpacity)),
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                // ⚠️ 吃掉抽屉内**空白区**的点击：`LiquidGlassSurface` 这里没传 `onClick` ⇒
                // 没有 pointerInput 节点 ⇒ 点击会穿透到 M3 的遮罩上，而遮罩的 onClick 就是
                // 「关抽屉」⇒ 在抽屉空白处点一下会误关。空 `onClick` 只为占住这个节点
                // （不给 indication —— 玻璃上不该有材质涟漪，与项目里多处同写法）。
                // 子项（会话行 / 按钮）先命中，不受影响。
                .clickable(
                    interactionSource = null,
                    indication = null,
                    onClick = {},
                ),
        ) {

            // ── 头部：本地工作区（WorkBuddy「云端▾」的降级物）──────────────────
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 14.dp),
            ) {
                Text(
                    text = "本地工作区",
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.onGlass,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "端侧运行 · 数据不出设备",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onGlassSubtle,
                )
            }

            // ── 新建任务 ────────────────────────────────────────────────────
            GlassButton(
                text = "新建任务",
                onClick = onNewConversation,
                // P1-1：忙时禁用（新建同样会销毁旧 VM ⇒ 半截回答静默丢失）。
                enabled = !engineBusy,
                icon = {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = null,
                        tint = if (engineBusy) colors.onGlassSubtle else colors.onGlass,
                        modifier = Modifier.size(18.dp),
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            )

            Spacer(Modifier.height(tokens.gapMd))

            // ── 任务列表 ────────────────────────────────────────────────────
            Text(
                text = "任务",
                style = MaterialTheme.typography.labelSmall,
                color = colors.onGlassMuted,
                modifier = Modifier.padding(start = 20.dp, bottom = 4.dp),
            )
            // P1-1：忙时禁用切换并给一句可行动提示 —— 与存储空间页
            // 「正在生成，暂不可清理」**同一形态**。权威闸门在调用方的回调里再判一次，
            // 这里只是 UI 便利层。
            if (engineBusy) {
                Text(
                    text = "正在生成，暂不可切换会话",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onGlassSubtle,
                    modifier = Modifier.padding(start = 20.dp, bottom = 6.dp),
                )
            }
            if (metas.isEmpty()) {
                Text(
                    text = "还没有会话",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onGlassSubtle,
                    modifier = Modifier.padding(start = 20.dp, top = 8.dp),
                )
                Spacer(Modifier.weight(1f))
            } else {
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(metas, key = { it.id }) { meta ->
                        ConversationRow(
                            meta = meta,
                            enabled = !engineBusy,
                            onClick = { onOpenConversation(meta.id) },
                        )
                    }
                }
            }

            // ── 底部：沙箱工作区（WorkBuddy「空间(N)」的降级物）────────────────
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            interactionSource = null,
                            indication = null,
                            role = Role.Button,
                            onClick = onOpenSandbox,
                        )
                        .padding(horizontal = 8.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Folder,
                        contentDescription = null,
                        tint = colors.onGlassSubtle,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        text = "沙箱工作区",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onGlass,
                    )
                }
            }
        }
    }
}

/**
 * 单个会话行：标题 + 相对时间。
 *
 * 与 `MainShell` 里的页签项同一口径：**不给 ripple**
 * （`indication = null`）—— 抽屉本身就是一块玻璃，再叠材质涟漪就是「玻璃上贴塑料」。
 * 点选后抽屉立即关闭，这本身就是反馈。
 */
@Composable
private fun ConversationRow(
    meta: ConversationMeta,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalGlassColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = null,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 20.dp, vertical = 10.dp),
    ) {
        Text(
            text = meta.title,
            style = MaterialTheme.typography.bodyMedium,
            // 禁用态整体降一档亮度（不再用 onGlass），让"点不动"是可看出来的。
            color = if (enabled) colors.onGlass else colors.onGlassMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = relativeTime(meta.updatedAtMillis),
            style = MaterialTheme.typography.labelSmall,
            color = colors.onGlassSubtle,
        )
    }
}

/**
 * 相对时间（纯 Kotlin，不引 java.time / 不做本地化）：抽屉是「扫一眼最近用过哪些」的场景，
 * 精确到秒没有意义。取整即可，不需要随秒刷新。
 */
private fun relativeTime(millis: Long): String {
    val minutes = (System.currentTimeMillis() - millis) / 60_000L
    return when {
        minutes < 1L -> "刚刚"
        minutes < 60L -> "$minutes 分钟前"
        minutes < 60L * 24L -> "${minutes / 60L} 小时前"
        else -> "${minutes / (60L * 24L)} 天前"
    }
}
