package com.rickeal.agent.core.engine.local

import com.rickeal.agent.core.model.ModelDescriptor

/** 简报 §3.1：思考文本走 channels["thought"]。 */
internal const val THOUGHT_CHANNEL = "thought"

/**
 * thought 通道的**输出解析声明**（Wave 43 真机实锤后新增；Wave 48 起按模型选择；**Wave 49 R-A 起数据驱动 + 默认可不下发**）。
 *
 * ## 背景（Gemma 侧）
 *
 * Gemma-4 的 chat template（litert-lm `google-gemma-4-multi-prefill.jinja`）在带 tools /
 * system 角色时，模型会以 `<|channel>thought ... <channel|>` 自发输出思维链；而 0.17.1
 * 运行时只有在容器元数据或 [ConversationConfig.channels] **声明**了该通道时才会做流式
 * 切分——未声明时标记连同思维链全部混进正文（OPPO PDRM00 真机 journal 实锤：
 * `<|channel>thought` 原样泄漏 + 思维链噪声触发轮内重复检测三连 → run 终止）。
 *
 * ## 为什么「按模型选择」而不是无条件下发 Gemma 标记（Wave 48 N1 根因）
 *
 * MiniCPM5 的 `.litertlm` 元数据**本就声明了** `<think>` / `</think>` 通道，但 native 的
 * 通道配置是 **overwrite 语义**（litert-lm `conversation.cc:189-200`：只要配置非空，元数据
 * 通道被整体丢弃）⇒ 无条件下发 Gemma 标记会**覆盖**元数据声明 ⇒ native 只找
 * `<|channel>thought`（MiniCPM5 永不输出）⇒ 不切分 ⇒ `<think>…</think>` 明文混进正文、
 * `message.channels["thought"]` 恒空（真机铁证 `_ci-tools/_w47_after/08bacd4c-…json`）。
 *
 * ## Wave 49 R-A：把「按模型选择」进一步收敛为**数据驱动 + 默认信任元数据**
 *
 * 返回 `null` ⇒ **不下发 `channels`** ⇒ native `nullopt` ⇒ 用**容器元数据**的通道配置
 * （0.17.1 KDoc：`null` = 用 `LlmMetadata` 默认；`empty` = 禁用通道 —— 后者**绝不能用**）。
 * 字面量来源 = [ModelDescriptor.thoughtChannelSyntax]（数据驱动，见 [ChannelSyntax] KDoc），
 * 未知模型默认 `null`。**能信任元数据就信任，不猜字面量** ⇒ 从机制上消除 N1 本体。
 *
 * ## 为什么**不能**简单 append 第二个 def（方案 A 被否决）
 *
 * native `ThinkingBudgetConstraint` 只用 `channels.front()` 的 start/end token ids
 * （`conversation.cc:371-392`，含上游 TODO b/521921341）⇒ 多通道下只有 **front()** 生效。
 * 若把 `<think>` 追加到末尾，W47 的 thinking 预算对 MiniCPM5 **静默失效**；放 front 则
 * Gemma 失效。**单元素返回（唯一非空分支 = [ChannelSyntax]）保证 `front()` 恒为该模型自己的
 * 思考通道** ⇒ 切分与预算同时正确。
 *
 * ## 判定口径 = 模型身份（**不**读 `capabilities.thinking`）
 *
 * channel def 描述的是**容器真实的通道语法**（模型事实），不是用户偏好；若随用户开关变化，
 * 会出现「用户关思考但模型仍自决输出 `<think>` ⇒ 再次泄漏」。故取
 * [ModelDescriptor.thoughtChannelSyntax]（由 `ModelHeuristics.inferChannelSyntax` 按 family 归类）。
 *
 * ## 声明后的行为与字面量出处
 *
 * 运行时把 start/end 标记之间的增量送进 `message.channels["thought"]`（引擎回调侧
 * [THOUGHT_CHANNEL] 消费路径已有），正文不再含思维链与标记。
 * - Gemma 侧标记 = litert-lm 源码 `channel_util.h` kThoughtChannelName 注释
 *   「e.g. "<|channel>thought"」+ `io_types.h` 同款示例，与真机泄漏文本逐字节一致；
 * - MiniCPM5 侧标记 = `_research/models/RM_MiniCPM5-2B.md:163`「the `thought` channel as
 *   `<think>\n` / `</think>`」（**回退档**；默认可不下发，信任元数据）。
 *
 * ⚠️ 类型注意：`ConversationConfig.channels` 的形参是 **List<Channel>?**（可空，默认 null；
 * 0.17.1 `Config.kt:235`），不是 Map —— 0.17.1 class 常量池里的 getChannels 取出的就是 List，
 * 首版误判为 Map 编译期被拦。
 * ⚠️ 引用必须**全限定**：本文件已 import kotlinx.coroutines.channels.Channel，
 * 短名 `Channel(...)` 会被解析到协程工厂函数而非 litertlm 构造器（首版实测两个编译错）。
 * ⚠️ 构造必须**位置实参**（channelName, start, end）：AAR 编译未带 -java-parameters，
 * 参数名不保留，具名实参编译期被拦（首版实测）。
 *
 * 纯函数（文件级 internal，可被单测直接调）：`null`（含未知模型 / `thoughtChannelSyntax == null`）
 * ⇒ 不下发 channels、信任容器元数据。**不变量**：返回非空时必为**单元素**且 `front()` 即该模型
 * 自己的思考通道 —— 这是把「预算 front() 正确性」钉进测试的关键。
 */
internal fun thoughtChannelDefsFor(
    model: ModelDescriptor?,
): List<com.google.ai.edge.litertlm.Channel>? {
    // null（含未登记未知模型）⇒ 不下发 channels，信任容器元数据。R-A 核心：
    // 元数据已声明思考通道的模型（MiniCPM5 等）不再被错误字面量覆盖 ⇒ 机制上消除 N1。
    val syntax = model?.thoughtChannelSyntax ?: return null
    // Channel(channelName, start, end)；不变量：非空返回恒单元素 ⇒ front() 即该模型自己的思考通道。
    return listOf(com.google.ai.edge.litertlm.Channel(THOUGHT_CHANNEL, syntax.start, syntax.end))
}
