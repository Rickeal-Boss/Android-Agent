package com.rickeal.agent.ui

import com.rickeal.agent.feature.chat.ChatRoute
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [isChatRoute] / [relativeTimeBetween] 的 JVM 纯函数单测
 * （Wave 32 · 流 B：为 :app 点亮测试源集 —— 此前该模块 src/test 为空，NO-SOURCE）。
 *
 * :app 的源码几乎全是 Compose / Navigation 脚手架，唯一可测的纯逻辑是这两段：
 *  1. [isChatRoute] 要认**三种** route 形态（ROUTE / PATTERN / 带会话参数）——
 *     漏认任何一种的症状是「底部页签高亮丢失 / 返回手势行为错乱」这类 UI 怪象，
 *     且只在特定导航路径下复现；
 *  2. [relativeTimeBetween] 的四档分界（刚刚 / 分钟 / 小时 / 天）依赖当前墙钟，
 *     抽出纯核后才能在 JVM 上钉住边界。
 */
class AppPureLogicTest {

    // ── isChatRoute：三种 route 形态都要认 ─────────────────────────────────

    @Test
    fun isChatRouteRecognisesAllThreeChatShapes() {
        assertTrue(isChatRoute(ChatRoute.ROUTE), "切页签时的裸 ROUTE")
        assertTrue(isChatRoute(ChatRoute.PATTERN), "栈底 startDestination 的 PATTERN")
        assertTrue(isChatRoute("chat?conversationId=abc"), "带具体会话参数的形态")
    }

    @Test
    fun isChatRouteRejectsOtherDestinationsAndLookalikes() {
        assertFalse(isChatRoute("models"))
        assertFalse(isChatRoute("settings"))
        // 前缀形似但不是 chat 的 route 不能误判（startsWith("${ROUTE}?") 只认带 ? 的）
        assertFalse(isChatRoute("chatx"))
        assertFalse(isChatRoute("chatty"))
        assertFalse(isChatRoute(""))
    }

    // ── relativeTimeBetween：四档分界 ──────────────────────────────────────

    private val now = 1_000_000_000_000L

    @Test
    fun relativeTimeBucketsMatchTheFourTiers() {
        assertEquals("刚刚", relativeTimeBetween(now, now)) // 0 分钟
        assertEquals("刚刚", relativeTimeBetween(now, now - 59_999L)) // 0.99 分钟
        assertEquals("1 分钟前", relativeTimeBetween(now, now - 60_000L)) // 恰 1 分钟
        assertEquals("59 分钟前", relativeTimeBetween(now, now - 59L * 60_000L))
        assertEquals("1 小时前", relativeTimeBetween(now, now - 60L * 60_000L)) // 恰 60 分钟
        assertEquals("23 小时前", relativeTimeBetween(now, now - (60L * 24L - 1L) * 60_000L))
        assertEquals("1 天前", relativeTimeBetween(now, now - 60L * 24L * 60_000L)) // 恰 24 小时
        assertEquals("3 天前", relativeTimeBetween(now, now - 3L * 24L * 60_000L))
    }

    @Test
    fun relativeTimeTruncatesRatherThanRounds() {
        // 1 分 59 秒 = 1.98 分钟 ⇒ 取整为 1 分钟（Kotlin 整数除法向零取整），
        // 不是「四舍五入成 2 分钟」—— 钉住这个口径防止将来有人换成 round
        assertEquals("1 分钟前", relativeTimeBetween(now, now - 119_999L))
        // 25 小时 = 1.04 天 ⇒ 1 天前
        assertEquals("1 天前", relativeTimeBetween(now, now - 25L * 60L * 60_000L))
    }
}
