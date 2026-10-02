package com.rickeal.agent.core.engine.local

import com.rickeal.agent.core.engine.EngineFactory
import com.rickeal.agent.core.engine.GenerationRequest
import com.rickeal.agent.core.model.ChatMessage
import com.rickeal.agent.core.model.CriterionHit
import com.rickeal.agent.core.model.EngineKind
import com.rickeal.agent.core.model.InferenceConfig
import com.rickeal.agent.core.model.ModelDescriptor
import com.rickeal.agent.core.model.ModelHealthCriteria
import com.rickeal.agent.core.model.ModelHealthVerdict
import com.rickeal.agent.core.model.Role
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withTimeout

/**
 * 模型加载后**小样本自检**探针（Wave 44 P0-1）。
 *
 * ## 解决什么问题
 *
 * 坏容器要等用户下完 2GB、发第一条消息才发现（Wave 43 真机：`gemma-4-E2B-it-gpu` 输出退化
 * 到采样出 `<unused1556>` 保留 token）。本探针在**用户手动点击**时，用两条固定短 prompt
 * 跑一次推理，对输出做机器判据（见 [ModelHealthCriteria]），给出 PASS / DEGRADED / BAD 结论。
 *
 * ## 为什么放 `:core-engine`
 *
 * 它直接依赖 `LlmEngine` / `GenerationRequest` 类型（`core-engine` 已 `api(project(":core-model"))`），
 * 且**不碰 litertlm**（只用引擎接口）—— 架构铁律「core-agent 不碰 litertlm」满足。
 *
 * ## 三类结果严格区分（任务书硬要求）
 *
 *  - [ProbeOutcome.Completed]：探针**跑通了**。`verdict = BAD/DEGRADED` 是**质量失败**
 *    （模型输出差），`PASS` 是健康。
 *  - [ProbeOutcome.Failed]：探针**没跑通**（生成抛异常 / 超时）。**不是**「模型坏」的结论。
 *  - [ProbeOutcome.NotRun]：前置条件不满足（引擎未加载 / 正在生成 / 无激活模型）。
 *    UI 文案必须与质量结论区分，否则会把「引擎没加载」误导成「模型有问题」。
 *
 * ## 会话隔离（不污染用户会话）
 *
 * 探针用**固定探针 id + 每次运行递增的 `contextVersion`**（[PROBE_CONVERSATION_ID]）：
 * 引擎的会话复用判据含 `conversationId + contextVersion`，探针 cid 与用户 cid 不同 ⇒ 引擎
 * 重建出「只含探针 prompt」的会话；用户下一轮 cid 又不同 ⇒ 再重建回用户会话。**探针内容
 * 永不进用户会话**。同一次探针内的两条 prompt 共享同一 `contextVersion` ⇒ 第二条只发 USER
 * 增量（只付 1 次会话构建成本）。
 *
 * ⚠️ **代价（已申报）**：native 只有单个 Conversation 字段，探针会触发 1 次会话重建
 * （用户下一轮再重建 1 次）。这是**手动按钮**才付的成本；**绝不**改成加载后自动跑。
 *
 * ## 并发纪律
 *
 *  - **单飞**：本类持 [running] 标志，双击只跑一次（返回 [NotRunReason.ENGINE_BUSY] ——
 *    此时引擎确实正被本次探针占用）。
 *  - **在途计数复用 [LlmEngine] 的 `activeGenerations`，不加新计数器**：只要真 `collect`
 *    探针 flow，计数即 >0，`load()` / `unload()` 的 `waitForGenerationsToFinish()` 会自动
 *    兜住 unload 竞态。
 *  - **取消**：探针在宿主 scope 里跑；取消 ⇒ `generateStream` 的 flow finally 执行
 *    `cancelProcess()` + `conversationDirty = true` ⇒ 下一轮自动重建，上下文一致。
 */
class ModelHealthProbe(private val engineFactory: EngineFactory) {

    /** 探针结果（三态，语义见类 KDoc）。 */
    sealed interface ProbeOutcome {
        /**
         * 探针跑通。`verdict` 是**质量结论**（[ModelHealthVerdict.BAD] / DEGRADED = 质量失败，
         * PASS = 健康），**不是**执行失败。
         */
        data class Completed(
            val verdict: ModelHealthVerdict,
            val hits: List<CriterionHit>,
            val sample: String,
            val elapsedMs: Long,
        ) : ProbeOutcome

        /** 前置条件不满足，探针未运行（**不是**质量结论）。 */
        data class NotRun(val reason: NotRunReason) : ProbeOutcome

        /** 执行失败（生成抛异常 / 超时）—— **不是**「模型坏」的结论。 */
        data class Failed(val message: String) : ProbeOutcome
    }

    /** 探针未运行的原因。 */
    enum class NotRunReason { ENGINE_NOT_LOADED, ENGINE_BUSY, NO_ACTIVE_MODEL }

    /** 单飞标志（双击只跑一次）。 */
    private val running = AtomicBoolean(false)

    /**
     * 探针会话的上下文版本号（每次运行递增）。
     *
     * 递增 ⇒ 每次运行都强制重建探针会话，**不累积上一轮探针的上下文**；同一次运行内两条
     * prompt 共用同一版本 ⇒ 第二条只发增量。
     */
    private val probeEpoch = AtomicLong(0L)

    /**
     * 跑一次自检。
     *
     * @param model 当前激活模型（null ⇒ [NotRunReason.NO_ACTIVE_MODEL]）
     * @param config 当前推理配置（探针只覆盖 `maxTokens`，其余沿用用户设置）
     */
    suspend fun run(model: ModelDescriptor?, config: InferenceConfig): ProbeOutcome {
        if (model == null) return ProbeOutcome.NotRun(NotRunReason.NO_ACTIVE_MODEL)
        if (!running.compareAndSet(false, true)) return ProbeOutcome.NotRun(NotRunReason.ENGINE_BUSY)
        try {
            val engine = engineFactory.create(EngineKind.LOCAL)
            // UI 前置（建议性，Boolean 有窗口；真闸门在引擎侧）—— 二者都不是质量结论。
            if (!engine.isLoaded) return ProbeOutcome.NotRun(NotRunReason.ENGINE_NOT_LOADED)
            if (engine.isBusy) return ProbeOutcome.NotRun(NotRunReason.ENGINE_BUSY)

            val startNs = System.nanoTime()
            val epoch = probeEpoch.incrementAndGet()
            val sample = StringBuilder()
            try {
                withTimeout(PROBE_TOTAL_TIMEOUT_MS) {
                    for (prompt in PROBE_PROMPTS) {
                        withTimeout(PROBE_TURN_TIMEOUT_MS) {
                            engine.generateStream(probeRequest(prompt, config, model, epoch))
                                .collect { chunk ->
                                    if (chunk.textDelta.isNotEmpty()) appendBounded(sample, chunk.textDelta)
                                    if (chunk.thinkingDelta.isNotEmpty()) appendBounded(sample, chunk.thinkingDelta)
                                }
                        }
                        // 早停：第 1 条已出 HARD ⇒ 跳过后续（坏容器第 1 条就会暴露，省时间）。
                        val soFar = ModelHealthCriteria.evaluate(sample.toString())
                        if (ModelHealthCriteria.verdictOf(soFar) == ModelHealthVerdict.BAD) break
                    }
                }
            } catch (t: TimeoutCancellationException) {
                // 内层单轮（PROBE_TURN_TIMEOUT_MS）与外层总计（PROBE_TOTAL_TIMEOUT_MS）的超时
                // 都被这一处捕获 ⇒ 文案并列两个上限，不谎报单一值（Wave 44 审查 P3-4）。
                return ProbeOutcome.Failed(
                    "自检超时（单轮 ${PROBE_TURN_TIMEOUT_MS / 1000}s / 总计 ${PROBE_TOTAL_TIMEOUT_MS / 1000}s）",
                )
            }

            val hits = ModelHealthCriteria.evaluate(sample.toString())
            return ProbeOutcome.Completed(
                verdict = ModelHealthCriteria.verdictOf(hits),
                hits = hits,
                sample = sample.toString(),
                elapsedMs = (System.nanoTime() - startNs) / 1_000_000,
            )
        } catch (t: CancellationException) {
            // 外部取消（用户离开页面）：向上传播，不吞（否则宿主无法感知取消）。
            throw t
        } catch (t: Exception) {
            return ProbeOutcome.Failed(t.message?.take(200) ?: t.javaClass.simpleName)
        } finally {
            running.set(false)
        }
    }

    /**
     * 构造探针请求：`tools = emptyList()` 绕过 AgentRunner（不注册任何工具）；
     * `conversationId` 固定探针 id + `contextVersion` 每次运行递增（会话隔离，见类 KDoc）。
     */
    private fun probeRequest(
        prompt: String,
        config: InferenceConfig,
        model: ModelDescriptor,
        epoch: Long,
    ): GenerationRequest = GenerationRequest(
        messages = listOf(ChatMessage(role = Role.USER, text = prompt)),
        config = config.copy(maxTokens = PROBE_MAX_TOKENS),
        model = model,
        tools = emptyList(),
        conversationId = PROBE_CONVERSATION_ID,
        contextVersion = epoch,
    )

    /** 采样文本有界追加（防御性上限；96 token 输出远达不到）。 */
    private fun appendBounded(target: StringBuilder, delta: String) {
        if (target.length >= MAX_SAMPLE_CHARS) return
        val room = MAX_SAMPLE_CHARS - target.length
        target.append(if (delta.length <= room) delta else delta.substring(0, room))
    }

    companion object {
        /**
         * 固定短 prompt（可复现）。
         *
         *  - 第 1 条：闭式短答，抓保留 token / 空输出；
         *  - 第 2 条：开式长答，给字符级周期判据足够语料。
         *
         * 第 1 条已出 HARD ⇒ 早停（见 [run]）。
         */
        val PROBE_PROMPTS: List<String> = listOf(
            "用一句话说明你现在能做什么。",
            "请用三句话描述春天的公园。",
        )

        /** 探针输出上限（token）。96 ≈ 短答；依据：引擎注释「4B 模型 1024 token 约 50 秒」。 */
        const val PROBE_MAX_TOKENS = 96

        /** 单轮超时：冷态 prefill 大模型可能 10–30s，给 30s 余量。 */
        const val PROBE_TURN_TIMEOUT_MS = 30_000L

        /** 总超时：封顶最坏情况（2 轮）。 */
        const val PROBE_TOTAL_TIMEOUT_MS = 60_000L

        /** 探针会话 id（与用户会话 cid 天然隔离）。 */
        const val PROBE_CONVERSATION_ID = "__health_probe__"

        /** 采样文本保留上限（字符）。 */
        const val MAX_SAMPLE_CHARS = 4096
    }
}
