package com.rickeal.agent.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.rickeal.agent.core.design.GlassButton
import com.rickeal.agent.core.design.GlassCard
import com.rickeal.agent.core.design.GlassMaterial
import com.rickeal.agent.core.design.LocalGlassColors
import com.rickeal.agent.core.design.LocalGlassTokens

/** 一页引导的内容。刻意用纯数据描述，页面本身不做任何业务判断。 */
private data class OnboardingPage(
    val title: String,
    val body: String,
    val icon: ImageVector,
)

private val onboardingPages = listOf(
    OnboardingPage(
        title = "端侧运行的 AI 助手",
        body = "LiquidAgent 把大模型直接跑在你的手机上。对话、工具调用、文件读写都在本机完成，"
            + "不需要把内容发到云端。",
        icon = Icons.Filled.Psychology,
    ),
    OnboardingPage(
        title = "模型需要先下载",
        body = "模型权重从几百 MB 到数 GB，首次使用前需要下载，建议连接 Wi-Fi。"
            + "下载后的模型保存在应用专属目录，不会进入系统相册或公共存储。",
        icon = Icons.Filled.Download,
    ),
    OnboardingPage(
        title = "数据留在你的设备上",
        body = "会话记录与附件都保存在本机，应用不申请任何存储权限，也不会上传到我们的服务器。"
            + "你可以随时在「设置」中查看并清理，卸载应用即全部删除。",
        icon = Icons.Filled.Lock,
    ),
)

/**
 * 首启引导：3 页，**可跳过**。
 *
 * 刻意不引入 ViewPager / 导航库（简报 §6：不加依赖），用一个页索引 + 圆点指示器实现。
 * 只负责「走完 / 跳过」这一个信号，是否持久化由调用方决定 —— 引导页本身不该知道 DataStore。
 *
 * 只渲染内容，不铺壁纸：壁纸由 [FirstRunGate] 统一提供，这样同一套内容也能被当作浮层复用。
 */
@Composable
fun OnboardingScreen(onFinished: () -> Unit) {
    val colors = LocalGlassColors.current
    val tokens = LocalGlassTokens.current

    // rememberSaveable：旋转 / 折叠屏展开时不要退回第一页。
    var index by rememberSaveable { mutableStateOf(0) }
    val page = onboardingPages[index.coerceIn(0, onboardingPages.lastIndex)]
    val isLast = index >= onboardingPages.lastIndex

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            GlassButton(
                text = "跳过",
                onClick = onFinished,
                material = GlassMaterial.THIN,
            )
        }

        // W88 横屏修正：本页全文件此前**零滚动**，而中间卡片的内容高度是固定的
        // （图标 40 + 标题 + 正文 + 24×2 内边距）。横屏手机可用高只有 ≈344dp，
        // 扣掉顶栏与底部圆点/按钮后 ≈180dp ⇒ 卡片被压住、正文盖住圆点（真机同型事故
        // 见 ConsentScreens 的历史记录：按钮被顶出屏幕后只能杀进程）。
        // 这里用「Box 居中 + 内层可滚」：内容放得下时视觉与原来完全一致（Box 居中），
        // 放不下时滚动而不是裁切。verticalScroll 的节点高度 = min(内容, 视口)，
        // 所以居中在两种情况下都成立。
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    material = GlassMaterial.THICK,
                    cornerRadius = tokens.radiusXl,
                    contentPadding = PaddingValues(24.dp),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            imageVector = page.icon,
                            contentDescription = null,
                            tint = colors.accent,
                            modifier = Modifier.size(40.dp),
                        )
                        Text(
                            text = page.title,
                            style = MaterialTheme.typography.titleLarge,
                            color = colors.onGlass,
                            modifier = Modifier.padding(top = 16.dp),
                        )
                        Text(
                            text = page.body,
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onGlassMuted,
                            modifier = Modifier.padding(top = 10.dp),
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 14.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (i in onboardingPages.indices) {
                val active = i == index
                Box(
                    modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .size(if (active) 8.dp else 6.dp)
                        .background(
                            color = if (active) colors.accent else colors.onGlassSubtle,
                            shape = CircleShape,
                        ),
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            GlassButton(
                text = if (isLast) "开始使用" else "下一步",
                onClick = {
                    if (isLast) onFinished() else index += 1
                },
            )
        }
    }
}
