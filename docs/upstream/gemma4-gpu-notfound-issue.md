# 上游 issue 文案：gemma-4 GPU 双变体 Android 引擎初始化 `NOT_FOUND`

> 状态：⬜ **待用户手动提交**（fine-grained PAT 对 `google-ai-edge` 组织无 issue 写权限）。
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
