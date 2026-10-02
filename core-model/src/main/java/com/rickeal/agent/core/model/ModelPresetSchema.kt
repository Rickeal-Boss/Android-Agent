package com.rickeal.agent.core.model

/**
 * 预设 / 采样档案的 schema 键名（Wave 44 P1-4 **桩**）。
 *
 * ## 现状（本波不动）
 *
 * 匹配键 = [FILE_NAME]（单一、精确字符串），两处消费点同源：
 *  - `ModelSamplingProfiles.forFileName`（本模块，`core-model`）；
 *  - `ModelPresets.findByFileName`（`feature-models`）。
 *  二者**本波一行不改** —— 桩只新增常量与空接口。
 *
 * ## 目标（本波**不实现**）
 *
 * 升级为多键 schema（family / size / quantization / modality / variant），使
 * 「一个档案覆盖同族多文件」成为可能（今日一个 `.litertlm` 变体就要在
 * `ModelSamplingProfiles` 里手抄一整段几乎相同的档案）。
 *
 * ⚠️ **迁移逻辑必须单独立项**：它同时改 [ModelSamplingProfiles]（`core-model`）与
 * `ModelPresets`（`feature-models`）两处匹配面，且要处理「旧 fileName 档案 → 多键」的
 * 回退与冲突消解 —— 混进本波会把两个模块的匹配语义同时搅动，风险与收益不成比例。
 *
 * ## 命名为何是 `ModelPresetSchemaKeys`（不是 `ModelPresets`）
 *
 * `ModelPresets` 已是 `feature-models` 里的**实例表**（复数、含具体数据）；
 * 本对象只放**键名字面量**，与实例表是「schema vs data」两层，刻意不复用其名。
 */
object ModelPresetSchemaKeys {
    /** 现行唯一匹配键（迁移前）。 */
    const val FILE_NAME = "fileName"

    // ── 以下为 schema 化后的目标键（本波仅占位，无任何实现读取）──

    /** 模型家族（`ModelFamily.name`）。 */
    const val FAMILY = "family"

    /** 体积分桶（如 "0.5B" / "2B" / "4B"，按参数量级或体积分桶）。 */
    const val SIZE_BUCKET = "sizeBucket"

    /** 量化档位（`Quantization.name`）。 */
    const val QUANTIZATION = "quantization"

    /** 模态组合（text / vision / audio）。 */
    const val MODALITY = "modality"

    /** 后端变体（base / gpu / fixB 等特化后缀）。 */
    const val BACKEND_VARIANT = "backendVariant"
}

/**
 * 预设 / 档案匹配接口（Wave 44 P1-4 **桩**）。
 *
 * 现行实现按 fileName 精确匹配；schema 实现留待迁移波（见 [ModelPresetSchemaKeys]）。
 * 泛型 `T` = 被匹配出的预设 / 档案类型（`ModelPreset` / [ModelSamplingProfile]）。
 *
 * ⚠️ **本波刻意无实现类**：桩的价值是「先把接口形状钉死」，让迁移波只填实现、
 * 不再回头争接口。`@Suppress("unused")` 是**有意保留**（不是漏接线）——
 * 见 [ModelPresetSchemaKeys] KDoc 的立项理由。
 */
@Suppress("unused")
fun interface ModelPresetMatcher<T> {
    /** 按 [descriptor] 匹配出预设 / 档案；无命中返回 null（与现行 `forFileName` 同语义）。 */
    fun match(descriptor: ModelDescriptor): T?
}
