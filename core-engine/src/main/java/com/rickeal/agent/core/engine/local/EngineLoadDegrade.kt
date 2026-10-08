package com.rickeal.agent.core.engine.local

import com.rickeal.agent.core.model.InferenceBackend
import com.rickeal.agent.core.model.ModelModality

/**
 * 一次 `load()` / 会话期重建尝试的完整状态（Wave 44 P0-2 降级链；Wave 45 会话创建期复用）。
 *
 * 为什么必须从 `Pair<Backend, Backend?>` 升级：旧结构只表达「主后端 → 视觉后端」，且
 * audio 后端在元组外（`audioBackend = resolvedAudioBackend?.let { … }` 恒取原值 —— ⚠️ 这是
 * **Wave 44 之前**的旧结构描述，**已不适用**），因此无法表达「去 audio 重建」；也无法记录
 * 「相对用户请求已去掉哪些模态」这一累积事实。
 *
 * ⚠️ **当前事实（Wave 44 起，回源见 `loadLocked`）**：audio / vision 后端**均在元组内**
 * （见下方 `audioBackend` 字段）；`EngineConfig` 读 `current.visionBackend` /
 * `current.audioBackend`（**不是** `resolved*`）。`resolved*` 仅用于 `sameEngine` 判据、
 * `initial(...)` 入参、`loaded*Backend` 赋值（恒记用户请求值）。Wave 45 起 `initial(...)`
 * 按 `seedDegrade` 把对应模态后端置 null。
 *
 * [degraded] 是**累积**的（跨尝试保留），最终写入诊断出口
 * [EngineSessionDiagnostics.degradedModality]。
 *
 * @param backend 本次尝试的主后端
 * @param visionBackend 本次尝试的视觉后端（null = 不用视觉 / 已去视觉模态）
 * @param audioBackend 本次尝试的音频后端（null = 不用音频 / 已去音频模态）
 * @param degraded 相对用户请求，本尝试**已去掉**的模态（空集 = 原样请求）
 */
internal data class EngineAttempt(
    val backend: InferenceBackend,
    val visionBackend: InferenceBackend?,
    val audioBackend: InferenceBackend?,
    val degraded: Set<ModelModality> = emptySet(),
)

/**
 * NOT_FOUND 错误驱动的模态降级链**纯决策逻辑**（Wave 44 P0-2）。
 *
 * 抽成纯函数（无 native、无状态）以便 JVM 单测覆盖全部决策分支；`loadLocked()`（`load()` 与
 * 会话期降级重建 `reloadForDegrade` 共用）只做「建引擎 → 失败 → 问 [next] → 建下一个」的
 * 驱动循环；会话创建期的失败另经 [modalityToDegradeOnSessionError] 判定（Wave 45）。
 *
 * ## 核心纪律：只认 NOT_FOUND
 *
 *  - `NOT_FOUND`（容器缺 `VISION_ENCODER` / `AUDIO_ENCODER_HW` 子图）⇒ **触发模态降级**；
 *  - `INTERNAL`（GPU 委托 dlopen / CompiledModel）⇒ **不触发模态降级**，属既有 GPU
 *    二段降级辖区（见 [next] 的第 2 步）；
 *  - 超时 / SIGSEGV / 其它 ⇒ 不触发（SIGSEGV 不是 `Throwable`，进程直接被内核杀，本函数
 *    根本收不到）；
 *  - **text 级 NOT_FOUND 不触发**：若当前尝试既无 vision 又无 audio（模态已降无可降），
 *    NOT_FOUND 只能是 text decoder 缺失 ⇒ [dropOneModality] 返回 null ⇒ [next] 返回 null
 *    ⇒ 调用方直接抛（与「无 GPU 可退」同理）。
 */
internal object EngineLoadDegrade {

    /**
     * 一次 `loadLocked` 的最大尝试次数（防循环）。
     *
     * 上界推导：原样 → 去 audio → 去 vision → GPU 回退，任意组合 ≤ 4。配合 `visited` 集合
     * 去重（任何重复状态不再入队），循环必终止。
     */
    const val MAX_LOAD_ATTEMPTS = 4

    /**
     * 一次**会话期**模态降级重建的最大次数（Wave 45）：每模态最多降一次 ⇒ 上界 = 模态数（2）。
     *
     * 与 [MAX_LOAD_ATTEMPTS] 正交：本常量兜 `ensureConversationWithDegrade` 的重试循环
     * （即便 [EngineAttempt.degraded] 幂等失效，也最多重建 2 次）；[MAX_LOAD_ATTEMPTS]
     * 兜 `loadLocked` 内「重建 → 再失败 → 再重建」的驱动循环。
     */
    const val MAX_SESSION_DEGRADE_ATTEMPTS = 2

    /**
     * 确定性「缺子图」错误：litert-lm 请求 `AUDIO_ENCODER_HW` / `VISION_ENCODER` 子图，
     * 但容器 section 表里没有 ⇒ 抛含 `NOT_FOUND` 的错误（Wave 43 真机根因实证）。
     */
    fun isDeterministicMissingSection(t: Throwable): Boolean =
        t.message.orEmpty().contains("NOT_FOUND", ignoreCase = true)

    /**
     * 从用户请求（解析后的值）构造首个尝试。
     *
     * [degraded] 默认空集 ⇒ 既有调用（load 路径）逐字不变；会话期降级重建
     * （[LiteRtLmEngine.loadLocked] 的 `seedDegrade`）用它**预置**「已去模态」。
     */
    fun initial(
        backend: InferenceBackend,
        visionBackend: InferenceBackend?,
        audioBackend: InferenceBackend?,
        degraded: Set<ModelModality> = emptySet(),
    ): EngineAttempt = EngineAttempt(backend, visionBackend, audioBackend, degraded)

    /**
     * 从错误消息解析**缺失的模态**（Wave 45，会话创建路径）。
     *
     * 与 load 路径的 [dropOneModality] 盲降不同：真机会话创建失败消息含 section 名
     * （`TF_LITE_AUDIO_ENCODER_HW` / `VISION_ENCODER`）⇒ 可精确解析（Wave 45 §1.3 纠偏）。
     *
     * @return 命中的模态；null = 非确定性缺 section / 消息不含可识别 section 名
     *   （交由 [modalityToDegradeOnSessionError] 走盲降兜底）。
     */
    fun missingModality(error: Throwable): ModelModality? {
        if (!isDeterministicMissingSection(error)) return null
        val message = error.message.orEmpty()
        return when {
            message.contains("AUDIO_ENCODER", ignoreCase = true) -> ModelModality.AUDIO
            message.contains("VISION_ENCODER", ignoreCase = true) -> ModelModality.VISION
            else -> null
        }
    }

    /**
     * **会话创建**失败 → 是否需去模态重建；null = 不触发（落既有「工具重试 / legacy 回退」路径）。
     *
     * 三道闸（Wave 45 §4-4「text 级 NOT_FOUND 不触发」纪律）：
     *  1. 非确定性缺 section（[isDeterministicMissingSection] 为 false）⇒ null；
     *  2. `available = currentModalities - degradedModality` 为空（已降无可降 / text 级
     *     NOT_FOUND）⇒ null；
     *  3. 命名模态 `∉ available`（如已降过）⇒ null。
     *
     * 解析优先：命中 [missingModality] 时按 section 名精确去；解析不出时按 [dropOneModality]
     * 的盲降优先级兜底（**先 AUDIO 后 VISION** —— 复用同一优先级，不另起一套）。
     */
    fun modalityToDegradeOnSessionError(
        error: Throwable,
        currentModalities: Set<ModelModality>,
        degradedModality: Set<ModelModality>,
    ): ModelModality? {
        if (!isDeterministicMissingSection(error)) return null
        val available = currentModalities - degradedModality
        if (available.isEmpty()) return null
        val named = missingModality(error)
        if (named != null) return if (named in available) named else null
        return if (ModelModality.AUDIO in available) ModelModality.AUDIO else ModelModality.VISION
    }

    /**
     * 用户请求是否涉及 GPU（主后端或视觉后端为 GPU）—— GPU 文案门控与二段降级的共同判据，
     * 等价于旧 `attempts.size > 1`（用户请求 CPU 且视觉非 GPU 时为 false，不得拼 GPU 文案）。
     */
    fun gpuInvolved(backend: InferenceBackend, visionBackend: InferenceBackend?): Boolean =
        backend == InferenceBackend.GPU || visionBackend == InferenceBackend.GPU

    /**
     * 去掉一个模态（保持主后端）：**先 AUDIO 后 VISION**。
     *
     * 优先级理由：audio 最不常用、误判面最小（Wave 43 真机根因正是 audio）；vision 是多模态
     * 主力，尽量后降。返回 null = 已无可降模态（两个模态后端都为 null）。
     *
     * 「每模态最多降一次」由结构保证：去模态即把对应后端置 null，null 不再入选；
     * [EngineAttempt.degraded] 只用于累积记录（写入诊断出口）。
     *
     * ⚠️ **盲降（留档，Wave 44 审查 P3-5）**：[isDeterministicMissingSection] 只判 `NOT_FOUND`
     * 字符串，**无法从 message 可靠解析缺的是哪个 section**（上游文案不保证含 section 名）⇒
     * 只能按固定优先级盲降。若容器**只缺 VISION**（audio 正常），首个尝试会白白去掉 audio 再
     * 重试，多付 1 次失败的引擎构建；有界（[MAX_LOAD_ATTEMPTS]=4 兜底），仅性能/日志噪声。
     */
    fun dropOneModality(current: EngineAttempt): EngineAttempt? {
        if (current.audioBackend != null) {
            return current.copy(
                audioBackend = null,
                degraded = current.degraded + ModelModality.AUDIO,
            )
        }
        if (current.visionBackend != null) {
            return current.copy(
                visionBackend = null,
                degraded = current.degraded + ModelModality.VISION,
            )
        }
        return null
    }

    /**
     * GPU→CPU 二段降级（既有语义，保持模态）：主后端落 CPU，**视觉后端跟随主后端**落 CPU
     * （与旧 `add(CPU to if (wantsVision) CPU else null)` 逐字等价）；audio 后端与主后端正交，
     * 保持不变。
     */
    fun toCpu(current: EngineAttempt): EngineAttempt = current.copy(
        backend = InferenceBackend.CPU,
        visionBackend = current.visionBackend?.let { InferenceBackend.CPU },
    )

    /**
     * 根据失败原因决定下一个尝试；返回 null = 终态失败（调用方抛 [error]）。
     *
     * @param gpuInvolved 用户请求是否涉及 GPU（主后端或视觉后端为 GPU）。等价于旧
     *   `attempts.size > 1`（既有 GPU 二段降级门控，用于 [next] 第 2 步与 GPU 文案门控）。
     */
    fun next(current: EngineAttempt, error: Throwable, gpuInvolved: Boolean): EngineAttempt? {
        // 1) 确定性缺 section：保持后端，只去一个模态（先 AUDIO 后 VISION）。
        if (isDeterministicMissingSection(error)) {
            return dropOneModality(current)
        }
        // 2) 既有 GPU→CPU 二段降级：保持模态，只换后端。GPU 仍参与（主后端或视觉后端）且
        //    尚未落 CPU 时才可退；toCpu 后 backend==CPU 且 vision∈{CPU,null} ⇒ 自动不可再退。
        if (gpuInvolved &&
            (current.backend == InferenceBackend.GPU || current.visionBackend == InferenceBackend.GPU)
        ) {
            return toCpu(current)
        }
        // 3) 终态失败。
        return null
    }
}

/** GPU 失败的特征串（Wave 33，大小写不敏感）：命中即可断定是 GPU 委托层的问题。 */
internal val GPU_FAILURE_FEATURES = listOf("dlopen", "OpenCL", "INTERNAL", "CompiledModel", "ClGl")

/** GPU 两段尝试都失败时的可操作文案（Wave 33：裸异常用户读不懂）。 */
internal const val GPU_FAILURE_HINT =
    " —— GPU 委托不可用（驱动/OpenCL 库缺失或机型不支持），已回退 CPU 仍失败；" +
        "请改用 CPU 后端重试或反馈机型信息"
