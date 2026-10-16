# 上游回复文案：`google-ai-edge/gallery#1326` 回复维护者三项检查

> 状态：⬜ **待用户手动提交**（fine-grained PAT 对 `google-ai-edge` 组织无 issue 写权限 ⇒ 转用户手动提交；沿 B1/B2 先例）。
> 目标 issue：https://github.com/google-ai-edge/gallery/issues/1326
> 回复对象：维护者（`secheverrianogal-beep`）2026-10-12 评论（[#issuecomment-6122445892](https://github.com/google-ai-edge/gallery/issues/1326#issuecomment-6122445892)）提出的三项检查。
> 来源：W70 波（2026-10-16）。以下正文为英文、可直接粘贴（不含本说明区）。
> 分寸纪律：只呈 section 取证 + 版本/登记情报，**不裁定**「artifact 缺陷」或「GPU-delegate 缺陷」——上游明示等待检查期间不下结论（w70-plan §七.6）。

---

Thanks for the detailed triage. Below are our findings, split by your three checks. We can only report artifact forensics and version history — we are not drawing a conclusion on whether this is an artifact issue or a GPU-delegate issue.

### 1. Section inventory comparison, provenance, and init-failure signatures

All three artifacts were downloaded and byte-verified locally; the sizes match the registered values exactly. Provenance note: Hugging Face direct is unreachable from our environment, so we pulled through the ModelScope mirror of the same `litert-community` container repos; revisions and SHA-256 below are the values registered in the ModelScope repo API at download time.

| Artifact | Local size (bytes) | Revision (registered) | SHA-256 (registered) | Sections found (head-region scan, first 50 MB) |
|---|---|---|---|---|
| `gemma-4-E4B-it-gpu.litertlm` | 2,969,059,328 (matches registration exactly) | `075a038be08b9926df7c8eef2b1da5ce16cb76ea` | `4912bb5a9c30993c51a7711f763212077458529312175df0573a78323a2bb7ff` | **only `tf_lite_artisan_text_decoder`** — no `tf_lite_prefill_decode`, no vision encoder, no audio encoder section |
| `gemma-4-E2B-it-gpu.litertlm` | 2,008,432,640 (matches registration exactly) | `61e25a57b2123d944496d8b11d632d246ed640a7` | `a53a59001894c58e6bdb5b9b227709f91a2e3e556baa7d85acf9c55402ba5cf5` | `backend_constraint` + `tf_lite_artisan_text_decoder` only — same specialization as E4B-GPU |
| `gemma-4-E4B-it.litertlm` (single-file CPU, same repo) | 3,659,530,240 (matches registration exactly) | `46feb4dd5a76f4620508e7055ac9f13cecc267d2` | `0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0` | `tf_lite_prefill_decode` + vision adapter/encoder + audio adapter/encoder sections + `backend_constraint` (+ draft/embedder sections) — **loads and runs on the same device** |

Registration repos: `litert-community/gemma-4-E4B-it-litert-lm` (E4B GPU + E4B single-file) and `litert-community/gemma-4-E2B-it-litert-lm` (E2B GPU).

Engine-init failure signatures on `litertlm-android:0.17.1` (AAR), Android 15, arm64, OPPO A13:

- `gemma-4-E2B-it-gpu.litertlm`: `Failed to create engine: NOT_FOUND: TF_LITE_PREFILL_DECODE not found in the model`
- `gemma-4-E4B-it-gpu.litertlm`: `Failed to create engine: NOT_FOUND` — VISION/AUDIO encoder sections reported missing (`model_signature_utils.cc:250`)

These are the load-bearing lines; full logcat for both bundles (and the working CPU-bundle run on the same device) is available on request.

### 2. 0.17.1 vs 0.18.0 retest

Not done yet: we have not re-tested these artifacts on `0.18.0`. The retest is queued in our internal runtime-upgrade checklist (we audit all 0.17.1 behavior anchors before any version bump) and we will report back once we have the 0.18.0 AAR and same-artifact device results. Separately, and unrelated to these bundles, we have already observed that 0.18.0 removed a single-element text-array convergence that exists in 0.17.1 — that is one reason a bump is currently blocked on our side, but we are drawing no conclusion about these GPU artifacts from it.

### 3. text-only?

As artifacts, both `-gpu` bundles contain only a text decoder section — no vision or audio encoder sections are present. Whether they are *intended* to be text-only variants or are missing sections is for you to confirm; we are only reporting artifact content.

Scope note: this comment contains forensic evidence and version/registration information only. We are not asserting an artifact defect or a GPU-delegate defect, and we agree with holding off on any Gallery dependency change until the checks are complete.
