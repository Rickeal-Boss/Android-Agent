package com.rickeal.agent.core.data

import com.rickeal.agent.core.model.ModelDescriptor
import com.rickeal.agent.core.model.ModelPresets
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * [activeModelKnownBrokenFlow] 的派生链单测（W68 B1，第十二审 R-2/R-3 数据面）。
 *
 * 为什么能跑在 JVM 上：[ModelRepository] 需要 Android `Context`（纯 JVM 构造不了），
 * 故被测对象是**生产路径实际调用的顶层组合子** `activeModelKnownBrokenFlow`
 * （`ModelRepository.activeModelKnownBroken` 的唯一实现），入参是纯 `Flow` ——
 * 零 Android 依赖，`runBlocking` 来自 `kotlinx-coroutines-core`（经
 * `kotlinx.coroutines.android` 传递引入，见模块 build.gradle.kts 注释）。
 *
 * 钉住三条语义（B1 冻结判据）：
 * 1. active = E2B·GPU 描述子 ⇒ 发射其预设 `knownBrokenOn` 原文；
 * 2. active = E4B 单文件（CPU 可用，knownBrokenOn=null）⇒ 发射 `null`（家族不串）；
 * 3. active = null / 非 preset 文件名 ⇒ 发射 `null`。
 *
 * 判定键精确匹配（禁 `contains`）的敏感性反证在 `feature-models` 的
 * `ModelPresetsKnownBrokenTest`（同一判定键，测试落点就近预设表）。
 */
class ActiveModelKnownBrokenTest {

    private fun emit(fileName: String?): String? = runBlocking {
        val descriptor = fileName?.let { ModelDescriptor(fileName = it) }
        activeModelKnownBrokenFlow(flowOf(descriptor)).first()
    }

    @Test
    fun `active 为 E2B GPU 描述子时发射其 knownBrokenOn 原文`() {
        val name = "gemma-4-E2B-it-gpu.litertlm"
        val expected = ModelPresets.findByFileName(name)?.knownBrokenOn
        assertNotNull(expected, "预设表必须收录 $name 且登记 knownBrokenOn（登记面被删则本测试失面）")
        assertEquals(expected, emit(name))
    }

    @Test
    fun `active 为 E4B 单文件时发射 null（家族不串）`() {
        assertNull(emit("gemma-4-E4B-it.litertlm"))
    }

    @Test
    fun `active 为 null 时发射 null`() {
        assertNull(emit(null))
    }

    @Test
    fun `active 为非 preset 文件名时发射 null`() {
        assertNull(emit("no-such-model.litertlm"))
    }
}
