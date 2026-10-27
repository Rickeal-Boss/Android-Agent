package com.rickeal.agent.core.data

import com.rickeal.agent.core.model.AgentLogStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [JsonFileStore.write] 的三条语义钉死（W87-01 / W87-04）。
 *
 * ## 为什么能跑在 JVM 上
 * 本模块单测不开 `returnDefaultValues`，而 `write` 的临时文件名里带进程号
 * （`android.os.Process.myPid()`，未 mock 的 android 方法在单测里直接抛
 * `RuntimeException: Method myPid ... not mocked`）。因此 [JsonFileStore] 把进程号
 * 抽成可注入的 `pidProvider`（与既有的 `json` 参数同一性质），测试注入固定值即可
 * 走通整条落盘路径 —— 不需要 Robolectric，也不需要 `android.util.Log`。
 *
 * ## 四条判据各自钉住什么
 *  1. 正常写：返回值 true + 内容可读回 + 不残留孤儿 tmp（原有契约不回归）。
 *  2. **写取消必须 rethrow**（本类的核心判据）：`write` 的 catch 曾经是
 *     `catch (t: Throwable)` + fallback 里的 `runCatching`，两处都会把
 *     [CancellationException] 吞成「落盘失败 ⇒ 返回 false」。吞取消会让 Job 从
 *     cancelled 变 completed、`joinAll` 提前返回 —— 本仓 W84 已实摔过，W86-01 在
 *     `SettingsRepository` 侧立了铁律，本文件是当时**最后一条漏网的落盘路径**。
 *  3. **读取消必须 rethrow**（同族收口）：`read` 返回 null 只表示「文件不存在 / 解析失败」，
 *     取消被改写成 null 会让调用方走「没有数据 ⇒ 可以写回」的分支，比返回 false 更危险。
 *  4. 落盘失败：返回 false、不抛异常（失败必须让调用方**可知**，但不能炸穿到 UI）。
 *     同时钉住 W87-04 的日志口径：只记异常类名，**不得**把异常 message（真机上常含
 *     完整绝对路径）写进日志。
 *
 * ## 写法约定（本仓铁律）
 * `assertFailsWith` 一律绑定到 `val` 并补一条 Unit 型断言收尾 —— 裸 `assertFailsWith {}`
 * 作为方法最后一条表达式会让整个测试类 `initializationError`、全部用例不跑（W80 实证）。
 */
class JsonFileStoreTest {

    /**
     * 在临时目录里跑一段测试，结束时递归清理。
     *
     * `pidProvider` 注入固定 0：只为避开 `android.os.Process` 的 stub，不参与任何断言。
     */
    private fun withStore(prefix: String, block: (JsonFileStore, File) -> Unit) {
        val dir = Files.createTempDirectory(prefix).toFile()
        try {
            block(JsonFileStore(dir, pidProvider = { 0L }), dir)
        } finally {
            dir.deleteRecursively()
        }
    }

    /** 序列化阶段就抛取消的策略：模拟「block 内出现挂起点后被取消」这一形态。 */
    private val cancellingStrategy = object : SerializationStrategy<String> {
        override val descriptor: SerialDescriptor = String.serializer().descriptor
        override fun serialize(encoder: Encoder, value: String) {
            throw CancellationException("已取消")
        }
    }

    /** 反序列化阶段就抛取消的策略：用于钉住 [JsonFileStore.read] 的「取消 ≠ 读失败」。 */
    private val cancellingDeserializer = object : DeserializationStrategy<String> {
        override val descriptor: SerialDescriptor = String.serializer().descriptor
        override fun deserialize(decoder: Decoder): String = throw CancellationException("已取消")
    }

    @Test
    fun `正常写返回 true 且内容可读回`() {
        withStore("w87-json-ok") { store, dir ->
            val ok = runBlocking { store.write("ok.json", "hello", String.serializer()) }
            assertTrue(ok, "正常落盘必须返回 true")
            assertEquals("hello", runBlocking { store.read("ok.json", String.serializer()) })
            assertFalse(
                dir.listFiles().orEmpty().any { it.name.endsWith(".tmp") },
                "成功路径不得残留孤儿 tmp",
            )
        }
    }

    @Test
    fun `写操作被取消时 write 必须 rethrow 而不是返回 false`() {
        withStore("w87-json-cancel") { store, dir ->
            val ex = assertFailsWith<CancellationException> {
                runBlocking { store.write("cancel.json", "hello", cancellingStrategy) }
            }
            assertEquals("已取消", ex.message)
            assertFalse(File(dir, "cancel.json").exists(), "取消路径不得产出目标文件")
        }
    }

    /**
     * 读路径的同族判据（W87-01 收口时补）：`read` 返回 null 的契约只有「文件不存在」与
     * 「解析失败」两种含义，**取消不是第三种**。改写成 null 会让调用方按「没有数据」继续
     * 走（`ModelRepository.refresh` 见 null 会走「首次启动可放心写回」分支），比 write
     * 返回 false 更危险。
     */
    @Test
    fun `读操作被取消时 read 必须 rethrow 而不是返回 null`() {
        withStore("w87-json-read-cancel") { store, dir ->
            val target = File(dir, "cancel-read.json").apply { writeText("\"hello\"") }
            val ex = assertFailsWith<CancellationException> {
                runBlocking { store.read("cancel-read.json", cancellingDeserializer) }
            }
            assertEquals("已取消", ex.message)
            assertEquals("\"hello\"", target.readText(), "取消不得改动已存在的文件")
        }
    }

    @Test
    fun `落盘失败返回 false 且日志不记异常 message`() {
        AgentLogStore.clear()
        val root = Files.createTempDirectory("w87-json-fail").toFile()
        try {
            // 把 baseDir 指向一个**普通文件**：mkdirs 无从补救，writeText 与退化 rename
            // 必然双双失败 —— 这正是「磁盘满 / 目录不可写」在 JVM 上可移植的等价形态
            // （不依赖 chmod，Windows 与 Linux 行为一致）。
            val blocker = File(root, "blocker.txt").apply { writeText("x") }
            val store = JsonFileStore(blocker, pidProvider = { 0L })
            val ok = runBlocking { store.write("a.json", "hello", String.serializer()) }
            assertFalse(ok, "目标不可写时 write 必须返回 false，而不是抛异常炸穿调用方")

            val logged = AgentLogStore.recent().map { it.message }
            assertTrue(
                logged.any { it.startsWith("JSON 落盘失败：a.json") },
                "落盘失败必须留一条 ERROR（带 fileName，才能定位是哪一次写失败）",
            )
            assertFalse(
                logged.any { it.contains("blocker.txt") },
                "W87-04：日志只记异常类名，不得带出异常 message 里的绝对路径",
            )
        } finally {
            root.deleteRecursively()
        }
    }
}
