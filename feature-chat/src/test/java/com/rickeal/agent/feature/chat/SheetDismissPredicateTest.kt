package com.rickeal.agent.feature.chat

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [shouldDismissSheet] 的纯逻辑测试（W88 议题 3）。
 *
 * 为什么必须单测：这条判据的两个分支都属于「写错了会静默错」——
 *  - **空命中**（`heightPx <= 0`，sheet 尚未测量）：若漏掉这条，`dragPx > 0 * 0.28`
 *    会让任何一次 1px 的抖动都直接关掉面板（真机上表现为「面板自己消失」）；
 *  - **上甩否决**（速度为负且超阈）：若漏掉这条，用户拖到一半又往回甩会被判退出，
 *    而他的意图恰恰相反。
 *
 * 被测函数只吃三个 Float，无 Android / Compose 依赖 ⇒ 无需 Robolectric。
 * 阈值语义见 `ChatParamsSheet.kt` 的 KDoc：位移 28% 或下甩 900px/s 判退。
 */
class SheetDismissPredicateTest {

    /** 位移未过阈 + 慢速 ⇒ 回弹（最常见的「拖了一点点又放手」）。 */
    @Test
    fun smallDragSlowReleaseDoesNotDismiss() {
        assertFalse(shouldDismissSheet(dragPx = 60f, velocityPxPerSec = 100f, heightPx = 280f))
    }

    /** 位移只有 20px，但下甩速度超阈 ⇒ 退出（速度优先于位移）。 */
    @Test
    fun fastFlingDownDismissesEvenWithSmallDrag() {
        assertTrue(shouldDismissSheet(dragPx = 20f, velocityPxPerSec = 1200f, heightPx = 280f))
    }

    /** 位移已过半，但松手是**向上**甩 ⇒ 上甩否决，回弹。 */
    @Test
    fun upwardFlingVetoesLargeDrag() {
        assertFalse(shouldDismissSheet(dragPx = 200f, velocityPxPerSec = -1200f, heightPx = 280f))
    }

    /** 尚未测量（heightPx = 0）⇒ 永不判退，否则任何拖动都会关面板。 */
    @Test
    fun unmeasuredHeightNeverDismisses() {
        assertFalse(shouldDismissSheet(dragPx = 500f, velocityPxPerSec = 5000f, heightPx = 0f))
    }
}
