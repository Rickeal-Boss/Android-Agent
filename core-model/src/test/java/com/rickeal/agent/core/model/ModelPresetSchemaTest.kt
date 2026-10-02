package com.rickeal.agent.core.model

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [ModelPresetSchemaKeys] / [ModelPresetMatcher] 的 JVM 单测（Wave 44 P1-4 桩）。
 *
 * 桩的价值是「先把键名与接口形状钉死」：本测试断言常量字面量**稳定**（防止后续改名
 * 静默破坏键），并以 SAM 实现一次 [ModelPresetMatcher] 证明接口可被按 descriptor 实现。
 */
class ModelPresetSchemaTest {

    @Test
    fun `键名常量稳定`() {
        assertEquals("fileName", ModelPresetSchemaKeys.FILE_NAME)
        assertEquals("family", ModelPresetSchemaKeys.FAMILY)
        assertEquals("sizeBucket", ModelPresetSchemaKeys.SIZE_BUCKET)
        assertEquals("quantization", ModelPresetSchemaKeys.QUANTIZATION)
        assertEquals("modality", ModelPresetSchemaKeys.MODALITY)
        assertEquals("backendVariant", ModelPresetSchemaKeys.BACKEND_VARIANT)
    }

    @Test
    fun `匹配接口可按descriptor实现`() {
        val matcher = ModelPresetMatcher<String> { descriptor ->
            descriptor.fileName.takeIf { it.isNotBlank() }
        }
        assertEquals("m.litertlm", matcher.match(ModelDescriptor(fileName = "m.litertlm")))
        assertEquals(null, matcher.match(ModelDescriptor(fileName = "")))
    }
}
