package com.rickeal.agent.feature.models

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [ModelPreset.knownBrokenOn] 登记纪律的 JVM 单测（W63 刀C）。
 *
 * 钉住两条不变量：
 * 1. **登记面**：三个已知坏容器（W43/W60 真机实测，出处 = 各预设 backendBasis）必须
 *    登记非空签名 —— 漏登 = 用户看不到红字硬提示与加载前确认，回到「点了才知道坏」。
 * 2. **互斥面**：`recommended == true` ⇒ `knownBrokenOn == null`（arch-guard 第 34 条
 *    的数据面镜像：守卫管仓库现状，本测试管将来新增条目时的合法组合）。
 *
 * 另钉「禁推测预填」：未在已知坏容器清单内的预设一律 null —— 本字段驱动红字提示
 * 与确认框，凭推测预填会把可用模型吓跑。
 *
 * 纯 JVM：[ModelPresets] 是纯内存表，零协程 / 零 Android 依赖。
 */
class ModelPresetsKnownBrokenTest {

    @Test
    fun `三个已知坏容器必须登记失败签名`() {
        val brokenFiles = listOf(
            "gemma-4-E2B-it-gpu.litertlm",
            "gemma-4-E4B-it-gpu.litertlm",
            "MiniCPM-V-4-int8.litertlm",
        )
        for (name in brokenFiles) {
            val preset = ModelPresets.findByFileName(name)
            assertNotNull(preset, "预设表必须收录 $name（收录被删则本测试与守卫 #34 同步失面）")
            val broken = preset.knownBrokenOn
            assertNotNull(
                broken,
                "$name 必须登记 knownBrokenOn（真实失败签名，出处见其 backendBasis 的 W43/W60 实测记录）",
            )
            assertTrue(
                broken.contains("0.17.1"),
                "$name 的签名必须携带运行时版本前缀（knownBrokenOn 语义 = 当前运行时下的已知失败），实际：$broken",
            )
        }
    }

    @Test
    fun `全部 recommended 预设不得登记 knownBrokenOn`() {
        for (preset in ModelPresets.all.filter { it.recommended }) {
            assertNull(
                preset.knownBrokenOn,
                "推荐项「${preset.label}」不得登记 knownBrokenOn（不变量：knownBrokenOn != null ⇒ recommended == false，守卫 #34）",
            )
        }
    }

    @Test
    fun `未登记坏签名的预设保持 null（禁推测预填）`() {
        val brokenFiles = setOf(
            "gemma-4-E2B-it-gpu.litertlm",
            "gemma-4-E4B-it-gpu.litertlm",
            "MiniCPM-V-4-int8.litertlm",
        )
        for (preset in ModelPresets.all) {
            if (preset.url.substringAfterLast('/') in brokenFiles) continue
            assertNull(
                preset.knownBrokenOn,
                "「${preset.label}」不在已知坏容器清单内，不得预填 knownBrokenOn（禁推测预填 —— 标错会把可用模型吓跑）",
            )
        }
    }
}
