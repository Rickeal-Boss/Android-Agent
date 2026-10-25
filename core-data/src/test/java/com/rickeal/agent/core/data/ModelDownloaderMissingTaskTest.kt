package com.rickeal.agent.core.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

// DownloadManager.STATUS_* 的 AOSP 取值（API 9 起冻结，源里写作 `1 << 3` / `1 << 4`）。
// 这里写字面量而不引用 android.app.DownloadManager —— 与本模块既有纪律一致
// （见 ThermalGovernorTest 顶部注释：单测不直接引用 android 类）。
// 对应关系由本文件的断言守住：被测函数若换了 status 取值，`assertEquals(STATUS_FAILED…)` 会红。
private const val STATUS_SUCCESSFUL = 8
private const val STATUS_FAILED = 16

/**
 * 「DownloadManager 里查不到任务」的判定（W82 P0：下载取消竞态的最后一道闸门）。
 *
 * 被测对象是文件级纯函数 [missingTaskProgress]，而不是 [ModelDownloader] 的方法：
 * 本模块无 Robolectric，构造不出 `DownloadManager` / `Context`，`progress()` 整条路径
 * 在 JVM 上不可达 —— 而「空 cursor 算不算成功」正是那条缺陷链的最后一环，必须被钉住。
 *
 * 缺陷回顾（旧语义）：`progress()` 在 cursor 为空时返回 `STATUS_SUCCESSFUL / percent = 100`，
 * 于是「用户点取消 → DownloadManager 删掉任务 → 僵尸轮询下一 tick」被读成「下载完成」，
 * 上层随即把下载目录里**上一次留下的同名文件**登记成模型并设为当前模型。
 *
 * 三个用例分别钉住缺陷的三个面：状态码、转正证据（`fromCursor`）、上屏数字与原因文案。
 */
class ModelDownloaderMissingTaskTest {

    @Test
    fun `空 cursor 判定为失败而不是成功`() {
        val progress = missingTaskProgress()
        assertEquals(STATUS_FAILED, progress.status, "任务消失是终态，但不是成功（W82 P0）")
        assertNotEquals(
            STATUS_SUCCESSFUL,
            progress.status,
            "旧语义在这里返回 SUCCESSFUL —— 回退即缺陷复现（用户取消后弹出「下载完成」）",
        )
    }

    @Test
    fun `空 cursor 不携带成功证据 —— fromCursor 恒 false`() {
        // fromCursor 是 `.part` 转正闸门的输入（调用方显式传 allowPromote = progress.fromCursor）。
        // 它一旦变 true，磁盘上的半截 .part 就可能被转正并登记成模型 ⇒ 用户点加载就 native 崩溃。
        assertFalse(missingTaskProgress().fromCursor)
    }

    @Test
    fun `空 cursor 的进度与落盘线索为空，且带可读原因`() {
        val progress = missingTaskProgress()
        assertEquals(0, progress.percent, "旧语义给 100 —— 那个数字会直接上屏成「已下载 100%」")
        assertNull(progress.localUri, "没有 cursor 行就没有 localUri，转正闸门的第二条证据也不成立")
        assertEquals(0L, progress.bytesDownloaded, "未拿到行 ⇒ 字节数必须归零，不能沿用上一次的值")
        assertEquals(0L, progress.totalBytes)
        assertEquals("任务已不存在", progress.reason, "上层把 reason 直接当作 error 文案展示给用户")
    }
}
