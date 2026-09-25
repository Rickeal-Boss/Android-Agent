package com.rickeal.agent.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.rickeal.agent.core.data.LegalDocuments
import com.rickeal.agent.core.data.LocalAppContainer
import com.rickeal.agent.core.design.GlassCard
import com.rickeal.agent.core.design.GlassIconButton
import com.rickeal.agent.core.design.GlassIconButtonShape
import com.rickeal.agent.core.design.GlassScaffold
import com.rickeal.agent.core.design.GlassTopBar
import com.rickeal.agent.core.design.LocalBottomBarOverlay
import com.rickeal.agent.core.design.LocalGlassColors
import com.rickeal.agent.core.design.LocalGlassTokens

/**
 * 「条款与授权」：回看自己同意了什么。
 *
 * ## 为什么必须有这个页面
 *
 * 「同意」这件事不能只让人签、不让人看。用户接受之后应当能随时找回自己同意的内容与
 * 原文入口 —— 这是接受这件事的基本配套，不是锦上添花。
 *
 * ## 为什么只读、没有 ViewModel
 *
 * 这里不接受、不撤销、不改任何状态，只是把 [LegalDocuments] 的文本和两个状态位摊开。
 * 没有业务逻辑需要承载，硬套一个 ViewModel 只会多一层转发。
 *
 * 两个状态位直接从 `LocalAppContainer` 读：它们本来就是 `SettingsRepository` 的 Flow，
 * 与 `:app` 的首启闸门、`:feature-models` 的下载闸门读的是**同一份**持久化状态。
 */
@Composable
fun LegalScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalGlassColors.current
    val tokens = LocalGlassTokens.current
    val uriHandler = LocalUriHandler.current
    val settings = LocalAppContainer.current.settingsRepository

    // initial = false：这里只用来显示「已接受 / 未接受」的徽标，读到之前按未接受显示，
    // 不会造成误导（与首启闸门用 null 避免闪现的场景不同 —— 那里 false 会让老用户
    // 冷启动时闪一帧条款页，这里只是徽标文字）。
    val tosAccepted by settings.isTosAccepted.collectAsState(initial = false)
    val gemmaAccepted by settings.isGemmaTermsAccepted.collectAsState(initial = false)

    GlassScaffold(
        modifier = modifier,
        topBar = {
            GlassTopBar(
                title = "条款与授权",
                subtitle = "你已同意的内容与原文入口",
                modifier = Modifier.statusBarsPadding(),
                navigationIcon = {
                    // pressOnly：顶栏图标位于 GlassTopBar 自己的玻璃之上（见 GlassIconButton KDoc）。
                    GlassIconButton(
                        onClick = onBack,
                        shape = GlassIconButtonShape.Capsule,
                        pressOnly = true,
                    ) {
                        Text(
                            text = "返回",
                            style = MaterialTheme.typography.labelLarge,
                            color = colors.accent,
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "下面两份文件是彼此独立的：应用服务条款的授权主体是本应用作者，" +
                    "Gemma 授权条款的授权主体是 Google。接受其中一份不代表接受另一份。",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onGlassSubtle,
            )

            LegalDocumentCard(
                title = LegalDocuments.APP_TERMS_TITLE,
                accepted = tosAccepted,
                body = LegalDocuments.APP_TERMS_BODY,
                linkUrl = LegalDocuments.APP_TERMS_URL,
                onOpenLink = { url -> runCatching { uriHandler.openUri(url) } },
                // APP_TERMS_URL 为空是**有意的**：没有官方可引用的地址时，编一个出来
                // 比没有更糟（会把用户导向不存在的页面）。这里如实说明现状。
                missingLinkNote = "正式条款正文与托管地址待法务提供，上方为占位文本。",
            )

            LegalDocumentCard(
                title = LegalDocuments.GEMMA_TERMS_TITLE,
                accepted = gemmaAccepted,
                body = LegalDocuments.GEMMA_TERMS_BODY,
                linkUrl = LegalDocuments.GEMMA_TERMS_URL,
                onOpenLink = { url -> runCatching { uriHandler.openUri(url) } },
                missingLinkNote = null,
            )

            Spacer(modifier = Modifier.height(tokens.bottomBarHeight))
        }
    }
}

/** 单份文件的卡片：标题 + 接受状态 + 正文 + 原文外链。 */
@Composable
private fun LegalDocumentCard(
    title: String,
    accepted: Boolean,
    body: String,
    linkUrl: String,
    onOpenLink: (String) -> Unit,
    missingLinkNote: String?,
) {
    val colors = LocalGlassColors.current
    val tokens = LocalGlassTokens.current

    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(14.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.onGlass,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = if (accepted) "已接受" else "未接受",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (accepted) colors.success else colors.warning,
                )
            }

            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = colors.onGlassMuted,
                modifier = Modifier.padding(top = tokens.gapSm),
            )

            if (linkUrl.isNotBlank()) {
                // 条款入口此前是裸 Row + clickable（只有字高 ≈24dp、无任何反馈）。
                // 换成胶囊小按钮：触摸区 48dp、按下有跟手形变；文字即语义。
                GlassIconButton(
                    onClick = { onOpenLink(linkUrl) },
                    modifier = Modifier.padding(top = tokens.gapSm),
                    shape = GlassIconButtonShape.Capsule,
                    contentPadding = PaddingValues(horizontal = 10.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = LegalDocuments.VIEW_FULL_TERMS_LABEL,
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
            } else if (missingLinkNote != null) {
                Text(
                    text = missingLinkNote,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onGlassSubtle,
                    modifier = Modifier.padding(top = tokens.gapSm),
                )
            }
        }
    }
}
