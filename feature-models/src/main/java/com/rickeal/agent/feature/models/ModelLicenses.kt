package com.rickeal.agent.feature.models

import com.rickeal.agent.core.model.ModelDescriptor

/**
 * 模型授权归属：判断一个模型是否受 **Gemma Terms of Use** 约束。
 *
 * ## 为什么需要它
 *
 * Gemma 的授权主体是 Google，与应用自己的服务条款是两份独立文件。但「独立」不只是
 * **法律主体**不能混，**适用范围**同样不能混 —— 内置的 8 条预设里只有 3 条是 Gemma
 * （Gemma 4 E2B · GPU / CPU、Gemma 4 E4B · GPU），另外 5 条（Qwen2.5、DeepSeek-R1、
 * MiniCPM5、Phi-4-mini、LFM2.5-VL）与 Gemma Terms 毫无关系。
 *
 * 让只想下载 Qwen 的用户去接受 Google 的条款，是**捆绑**，不只是体验问题：
 * 让用户接受一份与他要用的东西无关的法律条款，本身就是不恰当的。
 * 所以闸门必须精确到「这一个模型」，而不是「模型页」。
 *
 * ## 判据为什么是文件名/URL 里的 "gemma"，而不是 `ModelDescriptor.family`
 *
 * `ModelFamily` 目前只有 `GEMMA_3N` / `GEMMA_3` 两个 Gemma 取值（见 `:core-model`），
 * 而预设里的 Gemma 4 文件名是 `gemma-4-E2B-it-gpu.litertlm` —— `ModelHeuristics.inferFamily`
 * 的两条判据是 `contains("3n")` 与 `contains("gemma-3")/contains("gemma3")`，**都命中不了
 * Gemma 4**，会被归成 `OTHER`。也就是说 family 在这里**恰好是错的**，用它做授权闸门会
 * 静默放行 Gemma 4（漏拦，是危险方向）。
 *
 * 因此这里用最朴素也最稳的判据：名字里含 `gemma`。
 * 它是**宁可多问、不可漏问**的方向 —— 误报只是多问一次，漏报则是没拿到授权就用。
 * 真要用 family，得先在 `:core-model` 补上 GEMMA_4 并同步改 `inferFamily`，那是另一件事。
 */
object ModelLicenses {

    private const val GEMMA_MARKER = "gemma"

    /**
     * 下载前的判定：手上只有 URL。
     *
     * 预设的 URL 形如 `…/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/…`，
     * 仓库名与文件名都含 `gemma`，能稳定命中；Qwen / Phi 等的仓库名不含，不会误伤。
     */
    fun requiresGemmaTerms(url: String): Boolean =
        url.contains(GEMMA_MARKER, ignoreCase = true)

    /**
     * 加载前的判定：手上是已登记的 [ModelDescriptor]（没有 URL）。
     *
     * 看 [ModelDescriptor.fileName]（下载链路里它派生自 URL 末段，SAF 导入时来自用户选的文件），
     * 为空时退回 [ModelDescriptor.path] 的末段；再兜一层 [ModelDescriptor.sourceUrl]。
     */
    fun requiresGemmaTerms(model: ModelDescriptor): Boolean {
        val name = model.fileName.ifBlank { model.path.substringAfterLast('/') }
        if (name.contains(GEMMA_MARKER, ignoreCase = true)) return true
        return model.sourceUrl?.contains(GEMMA_MARKER, ignoreCase = true) == true
    }
}
