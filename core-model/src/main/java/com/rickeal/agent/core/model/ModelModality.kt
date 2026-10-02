package com.rickeal.agent.core.model

/**
 * 模型的**多模态能力位**（Wave 44 P0-2 降级链的运行时事实载体）。
 *
 * ## 为什么单独立枚举（不复用 `ModelCapabilities.image/audio` 布尔）
 *
 * `ModelCapabilities` 是**用户请求 / 静态声明**（「这个模型支持视觉吗」），而本枚举用于
 * 表达**加载期 / 会话创建期运行时事实**（「这次加载（或会话创建）因容器缺 section 去掉了
 * 哪个模态」）。两者语义正交：
 * 请求支持视觉、但容器里没有 VISION_ENCODER 子图时，能力位仍为 true（用户没改设置），
 * 而 [com.rickeal.agent.core.engine.EngineSessionDiagnostics.degradedModality] 会记下
 * 「VISION 被去掉了」。用同一个类型表达两件事会让「能力位 = 请求」这条纪律失效。
 *
 * ## 放在 `:core-model`
 *
 * 纯数据、零依赖：`:core-engine`（引擎诊断出口）与 `:feature-chat`（UI 小字）都要引用它，
 * 而 `:core-model` 是二者共同的底层依赖（`:core-engine` 已 `api(project(":core-model"))`）。
 * 放 `:core-engine` 会让 `feature-chat` 为读一个枚举而新增对引擎模块的依赖，不划算。
 *
 * ⚠️ **顺序即降级优先级**：`dropOneModality`（引擎侧）按「先 AUDIO 后 VISION」挑要去的模态
 * —— audio 最不常用、误判面最小（Wave 43 真机根因正是 audio）；vision 是多模态主力，
 * 尽量后降。本枚举的声明顺序与之一致，便于阅读时对照。
 */
enum class ModelModality {
    /** 音频模态（容器需 AUDIO_ENCODER_HW 子图）。 */
    AUDIO,

    /** 视觉模态（容器需 VISION_ENCODER 子图）。 */
    VISION,
}
