package com.rickeal.agent.core.data

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [conversationArtifactDirs] / [purgeConversationArtifactDirs] 的 JVM 单测（W69 C3）。
 *
 * 这两个纯函数是「删除会话」级联清理里**唯一会递归删目录**的一步 —— 不可逆，判据一旦
 * 写错就是灾难（最坏：空白 cid 让 `File(root, "")` 退化成根目录本身，把**全部会话**的
 * journal / history 一次删光）。所以把它们外提为不触 Android、不做 IO 的纯函数，在 JVM
 * 上直接钉住：路径拼接、非法 cid 拒绝、只删目标不动兄弟目录。
 *
 * 临时目录用 `java.io.tmpdir` + 纳秒后缀，用例内自建自清。
 */
class ConversationDeletionCleanupTest {

    private fun tempRoot(): File =
        File(System.getProperty("java.io.tmpdir"), "w69-cleanup-" + System.nanoTime()).apply { mkdirs() }

    // ── 路径判据：只产出该 cid 的两个专属子目录 ────────────────────────────

    @Test
    fun artifactDirsTargetOnlyTheConversationSubdirs() {
        val journalRoot = tempRoot()
        val historyRoot = tempRoot()
        val dirs = conversationArtifactDirs(journalRoot, historyRoot, "cid-1")
        assertEquals(
            listOf(File(journalRoot, "cid-1"), File(historyRoot, "cid-1")),
            dirs,
            "应恰好产出 <journalRoot>/<cid> 与 <historyRoot>/<cid> 两个直接子目录",
        )
    }

    // ── 防御判据：空白 cid / 路径逃逸 cid 一律不产出任何待删目录 ────────────

    @Test
    fun artifactDirsRejectBlankConversationId() {
        val journalRoot = tempRoot()
        val historyRoot = tempRoot()
        // 核心安全用例：File(root, "") == root ⇒ 若放行，会把整个根目录递归删光。
        assertTrue(conversationArtifactDirs(journalRoot, historyRoot, "").isEmpty(), "空 cid 必须拒绝")
        assertTrue(conversationArtifactDirs(journalRoot, historyRoot, "   ").isEmpty(), "纯空白 cid 必须拒绝")
    }

    @Test
    fun artifactDirsRejectPathTraversalIds() {
        val journalRoot = tempRoot()
        val historyRoot = tempRoot()
        assertTrue(conversationArtifactDirs(journalRoot, historyRoot, "../other").isEmpty(), ".. 逃逸必须拒绝")
        assertTrue(conversationArtifactDirs(journalRoot, historyRoot, "a/b").isEmpty(), "/ 分隔符必须拒绝")
        assertTrue(conversationArtifactDirs(journalRoot, historyRoot, "a\\b").isEmpty(), "\\ 分隔符必须拒绝")
    }

    // ── 防御判据（外部审查 P2 补正）："." 与含控制字符的 cid ──────────────────
    // 旧判据只挡 blank / 分隔符 / ".."，**漏了 "."** —— 而 File(root, ".") 在文件系统上
    // 等价 root，deleteRecursively() 会把根下全部兄弟会话目录删光（与 blank 同型事故）。

    @Test
    fun artifactDirsRejectDotAndControlCharacterIds() {
        val journalRoot = tempRoot()
        val historyRoot = tempRoot()
        assertTrue(conversationArtifactDirs(journalRoot, historyRoot, ".").isEmpty(), "\".\" 等价 root，必须拒绝")
        assertTrue(conversationArtifactDirs(journalRoot, historyRoot, "..").isEmpty(), "\"..\" 必须拒绝")
        assertTrue(
            conversationArtifactDirs(journalRoot, historyRoot, "a\u0000b").isEmpty(),
            "含 NUL 控制字符的 cid 必须拒绝",
        )
        assertTrue(
            conversationArtifactDirs(journalRoot, historyRoot, "a\nb").isEmpty(),
            "含换行控制字符的 cid 必须拒绝",
        )
    }

    @Test
    fun safeDirNameAcceptsNormalUuidShapedIds() {
        // 正向：UUID 形态（生产实际形态）必须放行，防判据过紧误伤。
        assertTrue(isSafeConversationDirName("0c9a576d-9289-468d-9933-af392781b893"))
        assertTrue(isSafeConversationDirName("cid-1"))
        assertFalse(isSafeConversationDirName("."))
        assertFalse(isSafeConversationDirName(".."))
        assertFalse(isSafeConversationDirName(""))
        assertFalse(isSafeConversationDirName("a/b"))
        assertFalse(isSafeConversationDirName("a\u0000b"))
    }

    // ── 副作用判据：递归删只删目标目录，兄弟目录不受影响 ────────────────────

    @Test
    fun purgeRemovesOnlyTargetDirsAndKeepsSiblings() {
        val journalRoot = tempRoot()
        val historyRoot = tempRoot()
        try {
            val targetJournal = File(journalRoot, "cid-1").apply {
                mkdirs()
                File(this, "run.jsonl").writeText("x")
            }
            val siblingJournal = File(journalRoot, "cid-2").apply {
                mkdirs()
                File(this, "run.jsonl").writeText("y")
            }
            val targetHistory = File(historyRoot, "cid-1").apply {
                mkdirs()
                File(this, "turn.json").writeText("z")
            }
            val siblingHistory = File(historyRoot, "cid-2").apply { mkdirs() }

            purgeConversationArtifactDirs(conversationArtifactDirs(journalRoot, historyRoot, "cid-1"))

            assertFalse(targetJournal.exists(), "目标会话的 journal 目录（含内部文件）应被递归删除")
            assertFalse(targetHistory.exists(), "目标会话的 history 目录应被递归删除")
            assertTrue(siblingJournal.exists(), "其它会话的 journal 目录不得被误删")
            assertTrue(siblingHistory.exists(), "其它会话的 history 目录不得被误删")
        } finally {
            journalRoot.deleteRecursively()
            historyRoot.deleteRecursively()
        }
    }

    @Test
    fun purgeWithBlankIdIsNoOp() {
        val journalRoot = tempRoot()
        val historyRoot = tempRoot()
        try {
            val keep = File(journalRoot, "cid-1").apply { mkdirs() }
            // 空白 cid ⇒ 判据产出空列表 ⇒ 一个目录都不删（尤其不能删到根目录）。
            purgeConversationArtifactDirs(conversationArtifactDirs(journalRoot, historyRoot, ""))
            assertTrue(keep.exists(), "空白 cid 下不得删除任何目录")
            assertTrue(journalRoot.exists(), "根目录必须完好")
        } finally {
            journalRoot.deleteRecursively()
            historyRoot.deleteRecursively()
        }
    }
}
