package com.rickeal.agent.core.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * [persistOrWarn] 的三条语义钉死（W86-01）。
 *
 * 为什么能跑在 JVM 上：本函数只依赖 `kotlinx.coroutines` 与 `:core-model` 的
 * [com.rickeal.agent.core.model.AgentLogStore]（纯 JVM 环形缓冲，无 `android.util.Log`），
 * 不触任何 Android 类。
 *
 * 三条判据对应三种命运：成功透传 / 可恢复失败被拦 / 取消必须外传。第三条是本类
 * 存在的**真正理由** —— 若这里退化成吞异常，取消语义会被静默改写（见函数 KDoc）。
 *
 * 写法约定：`assertFailsWith` 一律绑定到 `val` 并补一条 Unit 型断言，禁止裸调用收尾
 * —— 裸 `assertFailsWith {}` 作为方法最后一条表达式时，会让整个测试类
 * `initializationError`、全部用例不跑（本仓 W80 实证）。
 */
class PersistenceGuardTest {

    @Test
    fun `正常 block 的返回值原样透传`() {
        val value = runBlocking {
            persistOrWarn("PersistenceGuardTest.ok") { 42 }
        }
        assertEquals(42, value)
    }

    @Test
    fun `IOException 被拦截且返回 null`() {
        val value = runBlocking {
            persistOrWarn<Int>("PersistenceGuardTest.io") {
                throw IOException("磁盘已满")
            }
        }
        assertNull(value)
    }

    @Test
    fun `CancellationException 必须原样 rethrow`() {
        val ex = assertFailsWith<CancellationException> {
            runBlocking {
                persistOrWarn("PersistenceGuardTest.cancel") {
                    throw CancellationException("已取消")
                }
            }
        }
        assertEquals("已取消", ex.message)
    }
}
