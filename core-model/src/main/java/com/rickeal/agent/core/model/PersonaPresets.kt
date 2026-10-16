package com.rickeal.agent.core.model

/**
 * 人格档位预设（W70 B1；W69-07 人格系统重定范围后的产品件）。
 *
 * **设计口径（C4 裁决 + 预裁量②）**：人格 = 往 `InferenceConfig.systemInstruction`
 * 写入一段 system prompt 文本，**不新增存储字段、不建第二状态源** —— 选中态的唯一
 * 事实源就是该字段的逐字内容（[matches] 逐字相等判定）。落 core-model：
 * feature-settings（设置页选择器）与 feature-chat（对话页参数面板的自定义逃生阀）
 * 均依赖它，与 [InferenceConfig] 同模块、无引擎依赖（架构铁律 #3 无涉）。
 *
 * **行为面申报（端侧小模型现实，UI 文案不得过度声称）**：人格就是一条 system
 * 指令，与任意手输指令同质地被模型遵循；小模型（1–4B 端侧）对人格化指令的遵循
 * 强度**不作承诺**，效果可能有差异。预设文本全部为本仓原创，无第三方提示词搬运
 * （Apache-2.0 红线：Operit 系提示词原文零搬运，只取「按语气分档」的思想）。
 *
 * 加档位 = 追加枚举项（prompt 文本 + 短 label + 稳定 id）；id 不落盘、只用于
 * 归因与测试，改名无迁移面（与 [ToolDisclosureMode] 的落盘契约不同，刻意不
 * @Serializable —— 真正落盘的是 systemInstruction 文本本身）。
 */
enum class PersonaPreset(
    /** 稳定标识（归因/测试用；不落盘）。 */
    val id: String,
    /** 选择器 chip 的短展示名。 */
    val label: String,
    /** 写入 `systemInstruction` 的提示词正文（选中 chip 即逐字写入本文本）。 */
    val prompt: String,
) {
    /** 直接给结论，不铺陈。 */
    CONCISE(
        id = "concise",
        label = "简洁直接",
        prompt = "回答请直接给出结论，避免冗长铺垫。能一句话说清的不要展开；先给结论，" +
            "再按需补一句理由。不确定时直接说不确定，不要用填充内容凑长度。",
    ),

    /** 直觉先行、分步讲解。 */
    PATIENT(
        id = "patient",
        label = "耐心讲解",
        prompt = "解释概念时像给初学者讲课：先用一句直觉说明它是什么，再分步骤讲怎么用，" +
            "必要时举一个贴近生活的小例子。涉及不确定的部分要标明，不要编造细节。",
    ),

    /** 可执行做法 + 取舍 + 风险提示。 */
    PRACTICAL(
        id = "practical",
        label = "务实工程",
        prompt = "优先给出可执行的做法与取舍：先给结论，再列关键步骤和注意点。" +
            "涉及风险时明确提示；不堆砌术语，术语首次出现时用一句话解释。",
    ),
    ;

    companion object {
        /**
         * 选中态判据：逐字相等 ⇒ 返回命中的档位；不相等（含手改一个句号、
         * 多一个空格、留空）⇒ `null`（UI 显示为「自定义」，无 chip 高亮）。
         *
         * 刻意**不做** trim / 忽略空白 / 前缀匹配：宽松匹配会把用户手改过的
         * 文本误判成预设选中，chip 高亮与文本内容不符（零第二状态源被破坏）。
         */
        fun matches(systemInstruction: String): PersonaPreset? =
            entries.firstOrNull { it.prompt == systemInstruction }
    }
}
