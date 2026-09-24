package com.rickeal.agent.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.rickeal.agent.core.data.LegalDocuments
import com.rickeal.agent.core.design.GlassButton
import com.rickeal.agent.core.design.GlassCard
import com.rickeal.agent.core.design.GlassIconButton
import com.rickeal.agent.core.design.GlassIconButtonShape
import com.rickeal.agent.core.design.GlassMaterial
import com.rickeal.agent.core.design.LiquidGlassSurface
import com.rickeal.agent.core.design.LocalGlassColors
import com.rickeal.agent.core.design.LocalGlassTokens
import com.rickeal.agent.core.design.rememberGlassHaptics

/**
 * 应用服务条款（TOS）步骤。
 *
 * 这是进入主界面的**硬闸门**：上架合规要求用户能够访问条款并作出接受的意思表示。
 * 「不同意」不是把对话框关掉继续用，而是退出应用 —— 否则「接受」就失去了意义。
 *
 * 只渲染内容，不铺壁纸（由 [FirstRunGate] 提供）。
 */
@Composable
fun AppTosScreen(
    onAccept: () -> Unit,
    onDecline: () -> Unit,
) {
    LegalStepLayout(
        title = LegalDocuments.APP_TERMS_TITLE,
        lead = LegalDocuments.APP_TERMS_LEAD,
        bodyText = LegalDocuments.APP_TERMS_BODY,
        primaryLabel = "同意并继续",
        onPrimary = onAccept,
        secondaryLabel = "不同意并退出",
        onSecondary = onDecline,
        // 正式条款尚未托管（APP_TERMS_URL 为空）时不渲染外链，避免导向不存在的页面。
        linkLabel = LegalDocuments.VIEW_FULL_TERMS_LABEL,
        linkUrl = LegalDocuments.APP_TERMS_URL,
    )
}

/**
 * Gemma 授权条款步骤。
 *
 * **刻意与 [AppTosScreen] 分离**：Gemma 的授权主体是 Google，与本应用的服务条款
 * 不是同一份文件，法律主体不同就不能合并成一个「同意」按钮。
 *
 * 「稍后再说」是可选的：用户此刻还没决定要用哪个模型，不碰 Gemma 时不该被这份条款
 * 挡住整个应用。真正的强制点在使用侧 —— 下载或加载 **Gemma 系模型**时由
 * `:feature-models` 的授权闸门硬拦（只拦 Gemma，不牵连 Qwen / Phi 等其它模型）。
 */
@Composable
fun GemmaTermsScreen(
    onAccept: () -> Unit,
    onLater: () -> Unit,
) {
    LegalStepLayout(
        title = LegalDocuments.GEMMA_TERMS_TITLE,
        lead = LegalDocuments.GEMMA_TERMS_LEAD,
        bodyText = LegalDocuments.GEMMA_TERMS_BODY,
        primaryLabel = "同意并继续",
        onPrimary = onAccept,
        secondaryLabel = "稍后再说",
        onSecondary = onLater,
        linkLabel = LegalDocuments.VIEW_FULL_TERMS_LABEL,
        linkUrl = LegalDocuments.GEMMA_TERMS_URL,
    )
}

/**
 * 两个条款步骤共用的版式：标题 → 说明 → 可滚动的正文 → 外链 → 按钮。
 * 正文区域限高并独立滚动，条款再长也不会把按钮挤出屏幕。
 */
@Composable
private fun LegalStepLayout(
    title: String,
    lead: String,
    bodyText: String,
    primaryLabel: String,
    onPrimary: () -> Unit,
    secondaryLabel: String,
    onSecondary: () -> Unit,
    linkLabel: String,
    linkUrl: String,
) {
    val colors = LocalGlassColors.current
    val tokens = LocalGlassTokens.current
    val uriHandler = LocalUriHandler.current
    // 同意 / 不同意是两个**相反语义**的提交，用 Confirm / Reject 两种触感区分，
    // 手指不用看屏幕也知道自己按下的是哪一边。
    val haptics = rememberGlassHaptics()
    // 正文限高随窗口高度走：横屏可用高度约 280dp，写死 300.dp 会把
    // 「同意并继续 / 不同意并退出」顶出屏幕 —— 用户既进不去也退不出，只能杀进程。
    val bodyMaxHeight = (LocalConfiguration.current.screenHeightDp.dp * 0.4f)
        .coerceAtLeast(120.dp)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 16.dp)
            // 外层也要能滚：正文限高只保证“正文不挤按钮”，极端情况（大字体 +
            // 横屏 + 小高度窗口）下整卡仍可能超过可用高度。加上外层滚动后，
            // 「同意 / 退出」永远可达 —— 这是硬闸门，不能出现无路可走的版式。
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Center,
    ) {
        GlassCard(
            modifier = Modifier.fillMaxWidth(),
            material = GlassMaterial.THICK,
            cornerRadius = tokens.radiusXl,
            contentPadding = PaddingValues(20.dp),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = colors.onGlass,
                )
                Text(
                    text = lead,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onGlassSubtle,
                    modifier = Modifier.padding(top = 6.dp),
                )

                LiquidGlassSurface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 14.dp)
                        // 限高必须保留：Dialog / 卡片高度约束无界时 verticalScroll 会抛
                        // IllegalStateException。这里只是把固定 300.dp 换成随窗口高度缩放。
                        .heightIn(max = bodyMaxHeight),
                    material = GlassMaterial.ULTRA_THIN,
                    cornerRadius = tokens.radiusSm,
                    // 同意页正文卡是大面积容器且带滚动，色散 7 次采样在这里纯属白烧。
                    // 写死而不是依赖默认值：以后默认值被翻回去时这里不会跟着打开。
                    dispersion = false,
                    contentPadding = PaddingValues(14.dp),
                ) {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        Text(
                            text = bodyText,
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onGlassMuted,
                        )
                    }
                }

                if (linkUrl.isNotBlank()) {
                    // 条款入口此前是裸 Row + clickable（只有字高 ≈24dp、无任何反馈）。
                    // 换成胶囊小按钮：触摸区 48dp、按下有跟手形变；文字即语义。
                    GlassIconButton(
                        onClick = {
                            // 设备上没有浏览器时 openUri 会抛 ActivityNotFoundException，
                            // 条款入口不该让应用崩溃。
                            runCatching { uriHandler.openUri(linkUrl) }
                        },
                        modifier = Modifier.padding(top = 12.dp),
                        shape = GlassIconButtonShape.Capsule,
                        contentPadding = PaddingValues(horizontal = 10.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = linkLabel,
                                style = MaterialTheme.typography.labelLarge,
                                color = colors.accent,
                            )
                            Icon(
                                imageVector = Icons.Filled.OpenInNew,
                                contentDescription = null,
                                tint = colors.accent,
                                modifier = Modifier
                                    .padding(start = 4.dp)
                                    .size(15.dp),
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 18.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                ) {
                    GlassButton(
                        text = secondaryLabel,
                        onClick = {
                            haptics.reject()
                            onSecondary()
                        },
                        material = GlassMaterial.THIN,
                    )
                    GlassButton(
                        text = primaryLabel,
                        onClick = {
                            haptics.confirm()
                            onPrimary()
                        },
                    )
                }
            }
        }
    }
}
