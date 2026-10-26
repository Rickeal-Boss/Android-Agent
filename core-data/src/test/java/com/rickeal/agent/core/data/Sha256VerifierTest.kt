package com.rickeal.agent.core.data

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 下载完整性校验的三态判据（W86-02）。
 *
 * ## 为什么这四例值得写
 *
 * W84-15 把「摘要算不出」与「摘要不符」都归约成 `false`，于是**一次瞬态读错误**
 * 会在诊断页被报成「与预设锚点不符」—— 用户读到的是「你下的模型被篡改了」，
 * 事实是「我们没读成功」。两个结论的处置完全相反（前者拒绝 + 判坏文件，
 * 后者只说明这次没校验成），所以「算不出 ≠ 不符」必须被机械钉住，而不是靠注释约定。
 *
 * 被测对象是文件级纯函数（`Sha256Verifier.kt`），不是 [ModelDownloader] 的方法：
 * 本模块无 Robolectric，构造不出 `DownloadManager` / `Context`，`promotePartFile`
 * 整条路径在 JVM 上不可达 —— 与本模块既有纪律一致（见 `ModelDownloaderMissingTaskTest` 头注）。
 *
 * 第 1 例的期望摘要取自公开的 SHA-256 测试向量（`"hello"` →
 * `2cf24dba…`），**不是**在测试里再算一遍（那会与实现同源、等于没测）。
 */
class Sha256VerifierTest {

    /** "hello" 的 SHA-256（公开测试向量，非本仓计算）。 */
    private val helloDigest = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"

    private fun tempFile(content: String): File {
        val dir = Files.createTempDirectory("w86-sha").toFile()
        return File(dir, "sample.bin").apply {
            writeText(content)
            deleteOnExit()
            dir.deleteOnExit()
        }
    }

    @Test
    fun `已知内容的摘要等于公开测试向量`() {
        assertEquals(helloDigest, sha256HexOf(tempFile("hello")))
    }

    @Test
    fun `锚点比对大小写不敏感`() {
        val file = tempFile("hello")
        assertEquals(Sha256Check.MATCH, checkSha256(file, helloDigest))
        assertEquals(Sha256Check.MATCH, checkSha256(file, helloDigest.uppercase()))
        // 锚点两侧空白由调用方 trim —— 这里也钉住（预设常量手抄时极易带上空格）
        assertEquals(Sha256Check.MATCH, checkSha256(file, "  $helloDigest  "))
    }

    @Test
    fun `摘要算出来了但不等 ⇒ MISMATCH（唯一可判坏文件的状态）`() {
        val file = tempFile("hello")
        val tampered = helloDigest.dropLast(1) + if (helloDigest.last() == '0') '1' else '0'
        assertEquals(Sha256Check.MISMATCH, checkSha256(file, tampered))
    }

    @Test
    fun `文件读不到 ⇒ UNREADABLE 而不是 MISMATCH（W86-02 的核心判据）`() {
        val missing = File(tempFile("hello").parentFile, "not-there.bin")
        assertNull(sha256HexOf(missing), "读失败必须归约为 null（不抛、不返回空串）")
        assertEquals(
            Sha256Check.UNREADABLE,
            checkSha256(missing, helloDigest),
            "「无法证伪」不等于「已证伪」：IO 错误不得被判成摘要不符",
        )
    }
}
