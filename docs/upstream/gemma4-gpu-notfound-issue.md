# 上游 issue 文案：gemma-4 GPU 双变体 Android 引擎初始化 `NOT_FOUND`

> 状态：✅ **已创建（2026-10-16，落点改投 `google-ai-edge/gallery#1326`：https://github.com/google-ai-edge/gallery/issues/1326）**（fine-grained PAT 对 `google-ai-edge` 组织无 issue 写权限 ⇒ 提交由用户手动完成）。⚠️ **落点与预案不同**：原案为在 LiteRT-LM 新开 issue，用户**改投 gallery 仓库** ⇒ **本文件为预案文案留痕，非实际提交正文**；实际跟踪以 gallery #1326 为准，我方回复文案起草见 `docs/upstream/gallery-1326-reply-comment.md`（待用户手动提交）。
> 上游无同型报告（`#2461` / `#3879` 签名均不同）⇒ **新开 issue**。
> 新建入口：https://github.com/google-ai-edge/LiteRT-LM/issues/new
> 提交后请把 issue URL 回贴 `docs/14-user-actions.md` §B2。
> 来源：W63 波（2026-10-11）。

---

## Title

Gemma 4 E2B/E4B GPU bundles (litert-community) fail engine init on Android 0.17.1: `NOT_FOUND` (TF_LITE_PREFILL_DECODE not found)

## Body

### Environment
- `com.google.ai.edge.litertlm:litertlm-android:0.17.1` (AAR), Android 15 arm64, OPPO A13 (MediaTek, GPU delegation via OpenCL/vulkan path used by the AAR)
- Containers: `gemma-4-E2B-it-gpu.litertlm` and `gemma-4-E4B-it-gpu.litertlm` from litert-community
- The CPU variant `gemma-4-E2B-it.litertlm` from the same community repo **loads and runs fine** on the same device/build.

### Failure
Engine initialization fails with `NOT_FOUND` for both GPU bundles. Log signatures (two distinct shapes observed):

1. `TF_LITE_PREFILL_DECODE not found` — the GPU bundle's signature/opcode is not present in the embedded GPU delegate/runtime of the AAR.
2. A second shape where init reports the missing `VISION_ENCODER` / `AUDIO` encoder sections (the GPU bundles ship without the encoder subgraphs the multimodal path expects).

Both fail **at engine init**, i.e. before any session/template work; the CPU bundle's init path (prefill/decode ops present, encoder sections present) works.

### Ask
- Are the `-gpu` Gemma 4 bundles in litert-community expected to load with the 0.17.x Android AAR as-is, or do they require a specific runtime/delegate version (or a repack)?
- If repack is required, could the bundle docs state the compatible AAR range? Right now the failure surfaces as an opaque `NOT_FOUND` at init, which is hard to distinguish from a corrupt download.

Happy to provide full logcat for both bundles (init failure with stack) and the working CPU-bundle run on the same device.

---

## 上游动态（W70 回填，2026-10-16）

实际落点 `google-ai-edge/gallery#1326` 的维护者于 **2026-10-12** 回复（[#issuecomment-6122445892](https://github.com/google-ai-edge/gallery/issues/1326#issuecomment-6122445892)），提出先区分「model-artifact 问题」与「Android runtime 兼容问题」，并列**三项检查**：

1. **工件清单对照**：CPU 工件 vs 两个 GPU 工件的 section 清单（重点 `TF_LITE_PREFILL_DECODE` / `VISION_ENCODER` / audio 相关 section）+ 精确 HF 仓库 / revision + SHA-256 + **完整初始化日志**；
2. **版本复测**：同工件在 `litertlm-android:0.17.1` 与 `0.18.0` 上复现，设备与引擎配置保持不变；
3. **意图确认**：GPU 工件设计上是否支持图像/音频输入，还是 text-only 变体。

维护者并明确判据口径：必需 section 缺失 ⇒ 修在**打包/发布**侧；同工件 0.18.0 可跑而 0.17.1 失败 ⇒ 记录最低兼容 runtime 版本并查版本特异性失败；**拿到上述检查前，不建议改 Gallery 依赖版本，也不把该错误当作已确认的 GPU-delegate 缺陷**。

⇒ W70 处置：检查①大部可答（已实拉 section 扫描 + ModelScope 登记链 rev/SHA-256，回复文案起草见 `docs/upstream/gallery-1326-reply-comment.md`）；检查③内容面可答（artifact = text decoder only，「intended」声明留给上游）；检查②需 0.18.0 AAR + 同工件设备复测，**本波不做**，已入 docs/12 跟踪。
