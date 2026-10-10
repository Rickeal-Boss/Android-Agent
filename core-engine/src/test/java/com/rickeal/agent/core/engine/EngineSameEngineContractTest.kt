package com.rickeal.agent.core.engine

import com.rickeal.agent.core.engine.local.isSameEngine
import com.rickeal.agent.core.engine.local.resolveAudioBackend
import com.rickeal.agent.core.engine.local.resolveVisionBackend
import com.rickeal.agent.core.model.InferenceBackend
import com.rickeal.agent.core.model.InferenceConfig
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `sameEngine` 复用判据的**契约单测**（W59 A3 契约固化，复审 18 §3 全盘采纳）。
 *
 * 为什么值得写：判据已外提为纯函数 [isSameEngine]（拆分后单源），其**签名缺席
 * nativeToolChannel 形参**就是「原生工具通道开关不进实例重建判据」这条契约的机械表达
 * —— 开关走会话级生效路径（探针懒探测 + 会话重建判据），切开关不换 native Engine
 * 实例。判错方向各有代价：
 *  - 开关被加进判据 ⇒ 用户切开关触发整引擎重建（几十秒 + KV 全清），且「翻转复位块」
 *    （同实例 OFF→ON 跳变的韧性复位）与开关即时生效**同时死亡**（因果链见引擎
 *    generateStream 复位块注释申报）；
 *  - backend / contextLength 判据漏项 ⇒ 「改了参数但引擎静默不生效」（ENG-2 同类症状，
 *    历史真坑）。
 *
 * 纯 JVM、零 native：只调纯函数（isSameEngine / resolve*），不构造引擎、不触 litertlm。
 * ⚠️ JUnit4 纪律：所有 `@Test` 方法以返回 **void** 的断言收尾（`assertTrue`/`assertFalse`
 * 均返回 `Unit`）。
 */
class EngineSameEngineContractTest {

    @Test
    fun `仅 nativeToolChannel 不同的两份配置判 same`() {
        // 契约本体：两份配置除「原生工具通道」开关外逐字段相同，经 resolve* 解析后
        // 喂判据（loaded* = 前一份的解析值）⇒ 必须**判 same**（开关不触发实例更换）。
        // 若未来有人把开关加进判据（签名加形参或判据里引入开关读取），本例即红。
        val withChannelOn = InferenceConfig(backend = InferenceBackend.CPU, nativeToolChannel = true)
        val withChannelOff = InferenceConfig(backend = InferenceBackend.CPU, nativeToolChannel = false)
        val loadedVision = resolveVisionBackend(
            wantsVision = false,
            requestedBackend = withChannelOn.backend,
            requestedVisionBackend = withChannelOn.visionBackend,
        )
        val loadedAudio = resolveAudioBackend(
            wantsAudio = false,
            requestedAudioBackend = withChannelOn.audioBackend,
        )
        assertTrue(
            isSameEngine(
                forceRebuild = false,
                loaded = true,
                hasEngine = true,
                loadedModelPath = "/models/a.litertlm",
                modelPath = "/models/a.litertlm",
                loadedContextLength = withChannelOn.contextLength,
                contextLength = withChannelOff.contextLength,
                loadedBackend = withChannelOn.backend,
                backend = withChannelOff.backend,
                loadedVisionBackend = loadedVision,
                visionBackend = resolveVisionBackend(
                    wantsVision = false,
                    requestedBackend = withChannelOff.backend,
                    requestedVisionBackend = withChannelOff.visionBackend,
                ),
                loadedAudioBackend = loadedAudio,
                audioBackend = resolveAudioBackend(
                    wantsAudio = false,
                    requestedAudioBackend = withChannelOff.audioBackend,
                ),
            ),
            "仅 nativeToolChannel 不同的两份配置必须判 same（开关走会话级生效路径，不进重建判据）",
        )
    }

    @Test
    fun `backend 或 contextLength 变化判 not same`() {
        // 既有语义回归钉：backend（EngineConfig 级参数）与 contextLength（KV 预算，
        // native 按它分配）任一变化都必须**整机重建** —— 判据必须 false。
        val cpu = InferenceConfig(backend = InferenceBackend.CPU, contextLength = 4096)
        val gpu = InferenceConfig(backend = InferenceBackend.GPU, contextLength = 8192)
        assertFalse(
            isSameEngine(
                forceRebuild = false,
                loaded = true,
                hasEngine = true,
                loadedModelPath = "/models/a.litertlm",
                modelPath = "/models/a.litertlm",
                loadedContextLength = cpu.contextLength,
                contextLength = gpu.contextLength,
                loadedBackend = cpu.backend,
                backend = gpu.backend,
                loadedVisionBackend = resolveVisionBackend(
                    wantsVision = false,
                    requestedBackend = cpu.backend,
                    requestedVisionBackend = cpu.visionBackend,
                ),
                visionBackend = resolveVisionBackend(
                    wantsVision = false,
                    requestedBackend = gpu.backend,
                    requestedVisionBackend = gpu.visionBackend,
                ),
                loadedAudioBackend = resolveAudioBackend(
                    wantsAudio = false,
                    requestedAudioBackend = cpu.audioBackend,
                ),
                audioBackend = resolveAudioBackend(
                    wantsAudio = false,
                    requestedAudioBackend = gpu.audioBackend,
                ),
            ),
            "backend / contextLength 变化必须判 not same（整机重建组，静默复用 = 调参不生效）",
        )
    }
}
