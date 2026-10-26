package com.rickeal.agent.core.data

import com.rickeal.agent.core.model.AgentLogStore
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/**
 * 下载完整性校验的三态结论（W86-02）。
 *
 * ## 为什么必须是三态，而不是 `Boolean`
 *
 * W84-15 务实版把「算不出摘要」和「摘要不符」都归约成 `false`，于是**一次瞬态读错误**
 * （文件被并发扫描锁住、存储瞬时不可读）会被诊断页报成「与预设锚点不符」——
 * 用户看到的是「你下的模型被篡改了」，而事实是「我们没读成功」。两个结论的处理方式
 * 完全不同：前者必须拒绝并留痕，后者只说明「这次没校验成」。
 *
 * ⇒ 把「无法证伪」与「已证伪」拆成两态：
 *  - [MISMATCH]：**算出来了，且不等** ⇒ 唯一可以判「坏文件」的状态，硬拒绝。
 *  - [UNREADABLE]：**根本没算出来**（IO 错误）⇒ 不构成篡改证据，按「未经校验」处置
 *    （见 `ModelDownloader.promotePartFile` 的处置与论证）。
 *
 * ## 缓冲为什么是 1 MiB（W86-02 外审 B-3）
 *
 * `BufferedInputStream.read(buf, off, len)` 在 `len >= 内部缓冲` 时会**绕过缓冲直达底层**，
 * 所以旧实现（8 KiB 缓冲 + 8 KiB 数组）实际是「每 8 KiB 一次 read syscall」：
 * 4 GB 文件 ≈ **52 万次**系统调用。1 MiB 缓冲把它降到 ≈ 4 千次（128×），
 * 代价是 1 MiB 瞬时堆 —— 对 2~4 GB 模型文件的校验来说这是零成本。
 * ⚠️ 这个数字在**锚点回填之后**才会被用户感知（回填前校验分支根本不进），
 * 所以缓冲与锚点必须同批落地，否则「回填锚点」会以「下载莫名变慢几十秒」的形式被发现。
 */
internal enum class Sha256Check {
    /** 摘要算出来了，且与锚点相等（大小写不敏感）。 */
    MATCH,

    /** 摘要算出来了，但与锚点不等 —— 唯一可以据此判定「文件是坏的」的状态。 */
    MISMATCH,

    /** 摘要**算不出来**（IO 异常 / 文件消失）。不等于「文件是坏的」，只是「这次没校验成」。 */
    UNREADABLE,
}

/** 流式摘要的读缓冲大小（见 [Sha256Check] KDoc 的 128× 论证）。 */
internal const val SHA256_BUFFER_BYTES: Int = 1024 * 1024

/**
 * 流式计算文件 SHA-256，返回小写十六进制串；**读失败返回 null**（不抛）。
 *
 * 为什么不把异常往上抛：调用方要区分「算不出」与「不符」，而这两者的分流点必须紧挨着
 * 拒绝决策（`promotePartFile`）；让异常穿过三层调用栈只会逼出更宽的 catch。
 * 失败原因写进诊断日志（[AgentLogStore.warn]），不会静默。
 */
internal fun sha256HexOf(file: File): String? = try {
    val digest = MessageDigest.getInstance("SHA-256")
    FileInputStream(file).use { fis ->
        BufferedInputStream(fis, SHA256_BUFFER_BYTES).use { bis ->
            val buf = ByteArray(SHA256_BUFFER_BYTES)
            var read: Int
            while (bis.read(buf).also { read = it } != -1) {
                digest.update(buf, 0, read)
            }
        }
    }
    digest.digest().joinToString("") { "%02x".format(it) }
} catch (e: Exception) {
    // 只兜 Exception：Error（OOM / StackOverflow）不该被这里吞掉 —— 那不是「文件读不了」。
    AgentLogStore.warn(
        "完整性校验：读取文件计算 SHA-256 失败（${e.javaClass.simpleName}: ${e.message}）" +
            "，文件：${file.name}",
    )
    null
}

/**
 * 比对文件摘要与锚点，返回三态（见 [Sha256Check]）。比对大小写不敏感。
 *
 * ⚠️ 本函数**不做**「锚点为空则跳过」的判断 —— 那是调用方的职责（空锚点 = 离线未回填，
 * 跳过是设计意图，不是校验失败）。
 */
internal fun checkSha256(file: File, expected: String): Sha256Check {
    val actual = sha256HexOf(file) ?: return Sha256Check.UNREADABLE
    return if (actual.equals(expected.trim(), ignoreCase = true)) {
        Sha256Check.MATCH
    } else {
        Sha256Check.MISMATCH
    }
}
