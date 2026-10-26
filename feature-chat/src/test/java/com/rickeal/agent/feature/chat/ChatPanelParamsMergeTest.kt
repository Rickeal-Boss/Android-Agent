package com.rickeal.agent.feature.chat

import com.rickeal.agent.core.model.InferenceBackend
import com.rickeal.agent.core.model.InferenceConfig
import com.rickeal.agent.core.model.SamplingParams
import com.rickeal.agent.core.model.ThinkingMode
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 「对话页落盘闭包只改本键」的 JVM 单测（W83-09 + R4，W82-07 同族残余）。
 *
 * 被测 = 生产路径实际调用的纯函数 [InferenceConfig.mergeChatPanelParams]
 * （[ChatViewModel.commitParams] / [ChatViewModel.onParamChangeWith] 的
 * `updateInferenceConfig` 闭包直接调用它）。纯 JVM：只有 core-model 的纯数据类。
 *
 * 缺陷形态（本类存在的理由）：`updateInferenceConfig` 在 `DataStore.edit` 内**先 decode
 * 磁盘现值、再跑 transform**。闭包若忽略入参、直接返回对话页的整份内存快照，就等于用
 * 「对话页最后一次 collect 时」的旧值覆盖所有其他键 —— 设置页刚改的 `thinking`、模型页刚切的
 * `backend`、设置页的原生工具通道开关会被**静默抹掉**（内存侧已生效、磁盘被回灌成旧值，
 * 用户看到开关自己弹回）。
 *
 * 钉住三条语义：
 * 1. **面板键取 next**：11 个可编辑键必须按对话页内存值落盘（否则「改了不生效」）；
 * 2. **非面板键取磁盘现值**：`visionBackend` / `audioBackend` / `thinkingTokenBudget` /
 *    `nativeToolChannel` / `stream` 一律保留 `this`（磁盘）的值；
 * 3. **`sampling.seed` 不被连带覆盖**：`sampling` 必须逐字段 copy —— 整体替换
 *    `sampling = next.sampling` 会把 seed 一起按对话页旧值写回，那是「按键收敛」自己
 *    制造的新丢更新。
 */
class ChatPanelParamsMergeTest {

    /** 「磁盘现值」：所有非面板键都取**非默认**值，才可能发现被覆盖（默认值会被假绿掩盖）。 */
    private fun diskConfig(): InferenceConfig = InferenceConfig(
        sampling = SamplingParams(
            temperature = 0.5f,
            topP = 0.8f,
            topK = 20,
            repetitionPenalty = 1.1f,
            seed = 12345,
        ),
        maxTokens = 1024,
        contextLength = 4096,
        backend = InferenceBackend.CPU,
        visionBackend = InferenceBackend.GPU,
        audioBackend = InferenceBackend.CPU,
        thinking = ThinkingMode.AUTO,
        thinkingTokenBudget = 512,
        systemInstruction = "磁盘上的提示词",
        maxAgentRounds = 8,
        enableTools = true,
        nativeToolChannel = true,
        stream = false,
    )

    /** 「对话页内存快照」：面板 11 个键全部改成与磁盘不同的值。 */
    private fun panelSnapshot(): InferenceConfig = InferenceConfig(
        sampling = SamplingParams(
            temperature = 1.2f,
            topP = 0.6f,
            topK = 60,
            repetitionPenalty = 1.3f,
            // ⚠️ 刻意留 null：对话页根本不碰 seed，它是否被写成 null 是本类的第 3 条断言。
            seed = null,
        ),
        maxTokens = 2048,
        contextLength = 8192,
        backend = InferenceBackend.GPU,
        thinking = ThinkingMode.ON,
        systemInstruction = "面板里改的提示词",
        maxAgentRounds = 16,
        enableTools = false,
    )

    @Test
    fun `面板编辑的 11 个键按对话页内存值落盘`() {
        val merged = diskConfig().mergeChatPanelParams(panelSnapshot())
        assertEquals(InferenceBackend.GPU, merged.backend, "计算后端是面板键")
        assertEquals(1.2f, merged.sampling.temperature, "temperature 是面板键")
        assertEquals(0.6f, merged.sampling.topP, "topP 是面板键")
        assertEquals(60, merged.sampling.topK, "topK 是面板键")
        assertEquals(1.3f, merged.sampling.repetitionPenalty, "repetitionPenalty 是面板键")
        assertEquals(2048, merged.maxTokens, "maxTokens 是面板键")
        assertEquals(8192, merged.contextLength, "contextLength 是面板键")
        assertEquals(16, merged.maxAgentRounds, "maxAgentRounds 是面板键")
        assertEquals(ThinkingMode.ON, merged.thinking, "thinking 是面板键")
        assertEquals("面板里改的提示词", merged.systemInstruction, "systemInstruction 是面板键")
        assertEquals(false, merged.enableTools, "enableTools 是面板键")
    }

    @Test
    fun `非面板键一律取磁盘现值（防整份快照覆盖丢更新）`() {
        val merged = diskConfig().mergeChatPanelParams(panelSnapshot())
        assertEquals(InferenceBackend.GPU, merged.visionBackend, "视觉后端面板无入口 ⇒ 取磁盘现值")
        assertEquals(InferenceBackend.CPU, merged.audioBackend, "音频后端面板无入口 ⇒ 取磁盘现值")
        assertEquals(512, merged.thinkingTokenBudget, "thinking 预算面板无入口 ⇒ 取磁盘现值")
        assertEquals(true, merged.nativeToolChannel, "原生工具通道是设置页专属开关 ⇒ 取磁盘现值")
        assertEquals(false, merged.stream, "stream 面板无入口 ⇒ 取磁盘现值")
    }

    @Test
    fun `sampling 逐字段合并 不覆盖 seed`() {
        val merged = diskConfig().mergeChatPanelParams(panelSnapshot())
        assertEquals(12345, merged.sampling.seed, "对话页不碰 seed ⇒ 必须保留磁盘现值，不得被写成 null")
    }

    @Test
    fun `磁盘与内存完全相同时合并是恒等变换`() {
        val disk = diskConfig()
        assertEquals(disk, disk.mergeChatPanelParams(disk), "同一份配置合并后必须逐字段相等（无副作用）")
    }
}
