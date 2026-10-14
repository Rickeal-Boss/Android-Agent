package com.rickeal.agent.feature.models

import com.rickeal.agent.core.model.ModelPreset
import com.rickeal.agent.core.model.ModelPresets
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

    /* ─────────── 坑1：判定键 = 下载文件名精确匹配（W68 B1）───────────
     *
     * `ModelPresets.findByFileName`（`url.substringAfterLast('/') == name`，**精确相等**）
     * 是激活态重校验（W68 B2）与加载前内存闸门共用的判定键。本组钉住「禁 contains /
     * startsWith 等模糊键」：
     *  - E4B 家族：`gemma-4-E4B-it-gpu.litertlm`（已知坏）与 `gemma-4-E4B-it.litertlm`
     *    （单文件，CPU 可用）**只差一个 `-gpu` 段**，模糊键必互窜（把可用模型判成坏）；
     *  - 自定义/重名落盘（`name-1.ext`）与未知文件名一律 `null`（白名单语义的对称面）。
     *
     * 反证（已跑，见 W68 B2 回报）：把匹配临时退化为去扩展名的**双向 contains**
     * （`presetBase.contains(query) || query.contains(presetBase)`）⇒ ①②两断言判红，
     * 证明断言真敏感；随后还原。
     */

    @Test
    fun `坑1 家族不串 E4B 单文件不得命中 gpu 变体的 broken 判定`() {
        // 先证兄弟条目确实带坏签名（否则本条断言 vacuous：删了 gpu 条目也能「绿」）。
        assertNotNull(
            ModelPresets.findByFileName("gemma-4-E4B-it-gpu.litertlm")?.knownBrokenOn,
            "gemma-4-E4B-it-gpu.litertlm 必须仍登记 knownBrokenOn（本断言的对照面）",
        )
        assertNull(
            ModelPresets.findByFileName("gemma-4-E4B-it.litertlm")?.knownBrokenOn,
            "E4B 单文件（CPU 可用）不得命中 -gpu 变体的 broken 判定（判定键必须精确匹配，禁 contains）",
        )
    }

    @Test
    fun `坑1 自定义导入或重名落盘文件名不得命中任何 broken 判定`() {
        assertNull(
            ModelPresets.findByFileName("gemma-4-E4B-it-gpu-1.litertlm")?.knownBrokenOn,
            "重名落盘（name-1.ext）/ SAF 导入的自定义文件不得命中任何 broken 判定",
        )
        assertNull(
            ModelPresets.findByFileName("MiniCPM-V-4-int8-2.litertlm")?.knownBrokenOn,
            "重名落盘的已知坏容器副本同样不得命中（判定键是完整文件名，不是前缀）",
        )
    }

    @Test
    fun `坑1 非 preset 文件名不得命中任何 broken 判定`() {
        assertNull(
            ModelPresets.findByFileName("totally-unknown-model.litertlm")?.knownBrokenOn,
            "未知文件名 ⇒ findByFileName 返回 null ⇒ knownBrokenOn 为 null（不提示）",
        )
    }
}
