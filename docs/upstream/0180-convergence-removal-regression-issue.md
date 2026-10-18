# 上游 issue 文案：0.18.0 移除单元素 content 收敛 ⇒ 所有 string 拼接 chat_template 容器每轮必炸

> 状态：⬜ **待用户手动提交**（fine-grained PAT 对 `google-ai-edge` 组织无 issue 写权限 ⇒ 转用户手动提交；沿 B1/B2/gallery #1326 先例）。
> 目标仓库：`google-ai-edge/LiteRT-LM` —— **新开 issue**（与 #3576 **不同族**，声明见正文末节）。
> 新建入口：https://github.com/google-ai-edge/LiteRT-LM/issues/new
> 来源：W73 波（2026-10-18）。原始数据：`_research/w72-0180-device-arm.md`（0.18.0 条件真机臂 Q4/Q5，逐字已核）。
> 分寸纪律：只呈现实测数据 + 版本 diff + 可复现路径，不裁定上游意图；全部日志/template 存档可应求提供。
>
> **用户触点三段式（与 gallery #1326 回复同一次触点提交）**：
> - **① 做什么**：在 https://github.com/google-ai-edge/LiteRT-LM/issues/new 用下方 **Title + Body** 创建 issue（Body 为英文、可直接粘贴，不含本说明区）。
> - **② 预期看到什么**：issue 创建成功，标题/正文含三段证据（Q4/Q5 真机逐字签名 / minijinja 七格离线复现 / 0.17.1↔0.18.0 `generic_data_processor.cc` 版本 diff）+ 恢复收敛或兼容开关的请求 + 与 #3576 不同族声明。
> - **③ 销账判据**：issue URL 回贴 [`docs/14-user-actions.md`](../14-user-actions.md) §B3（与 gallery #1326 回复**同一次用户触点**提交；回贴后同步进 docs/12 bump 季扫跟踪）。

---

## Title

litertlm 0.18.0 regression: removal of single-element text-array convergence breaks all chat templates that concatenate `message.content` as a string (every turn fails)

## Body

### Environment

- Runtime: `com.google.ai.edge.litertlm:litertlm-android:0.18.0` (AAR, 20,905,807 B, Google Maven — note: 0.18.0 is not on Maven Central), Android 15 arm64, OPPO A13.
- Control: the **same containers, same device, same prompt** on `0.17.1` work (fold path active) — this is a 0.18.0 regression, not a pre-existing failure.
- Affected containers (both fail on the **very first turn** under 0.18.0):
  1. `Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm` (session uuid `dfc68e9e-5540-4959-a4d1-c7373a329192`; embedded template `qwen25.jinja`);
  2. `MiniCPM5-2B_int4.litertlm` (session uuid `851b9e2d-0f24-4616-a437-56509b79e574`; embedded template `minicpm5.jinja`).
- Prompt used for both: `Hello please reply OK`.

### 1. Failure signature (on-device, verbatim)

Qwen2.5-1.5B, first turn, 0.18.0:

```
[WARN] 生成期模板渲染失败（Failed to start nativeSendMessageAsync: INTERNAL: Failed to apply template:
       invalid operation: tried to use + operator on unsupported types string and se…
[WARN] 引擎重建：LOCAL 生成失败（… Failed to apply template …）
[ERROR] 生成失败：LOCAL 重试后仍失败（… Failed to apply template …）
```

MiniCPM5-2B, first turn, same prompt: same signature (`+ operator on unsupported types string and seq`).

- Run journal for both failures is only **587 B** (`run_started` → `user_input` → failure): **no MODEL message**, i.e. nothing ever reaches the model — the failure is at template evaluation time, synchronously in `sendMessageAsync`.
- Same two containers on 0.17.1, same device: normal replies (fold ON).
- Blast radius: ≥2 of our 14 registered presets are string-concatenating templates; DeepSeek-R1 (Qwen-distilled base) is highly suspect. We read this as a **general surface** (all converted containers whose templates treat `content` as a string), not a per-container issue.

### 2. Offline reproduction (no device needed; PyPI minijinja = the engine the runtime embeds)

PyPI `minijinja` is the same engine family the runtime embeds (0.17.1 pins `minijinja = "=2.14.0"` in `Cargo.toml`, our behavior baseline since W50–W55):

```
python -m pip install 'minijinja==2.14.0'
```

Seven-grid matrix against each container's plaintext `chat_template` (grid names = harness output; only the **five array-containing grids** are decisive):

| grid | system | user | Qwen2.5 | MiniCPM5 |
|---|---|---|---|---|
| `U_arr1` | — | [text] | **ERR** `:23` | LOSS (text dropped) |
| `U_arrN` | — | [image,text] | **ERR** `:23` | LOSS (text dropped) |
| `S_arr_U_str` | [text] | string | **ERR** `:16` | **ERR** `:24` |
| `S_arr_U_arr1` | [text] | [text] | **ERR** `:16` | **ERR** `:24` |
| `S_arr_U_arrN` | [text] | [image,text] | **ERR** `:16` | **ERR** `:24` |

(Non-array grids `U_str` / `S_str_U_str` pass on all presets — omitted.)

- Verbatim error: `invalid operation: tried to use + operator on unsupported types string and sequence (in <string>:N)`, where `N` is the template line containing `+ messages[…].content` (Qwen2.5 user branch `:23` / system branch `:16`; MiniCPM5 system branch `:24`).
- Decisive observation: the failure point is **the system branch going through an array** — `S_str_*` grids pass, `S_arr_*` grids throw. MiniCPM5's user branch additionally drops text silently (`content=''`), currently masked by the system-branch throw.
- Conclusion: under 0.18.0 the template engine **always** sees `content` as an array (single-element convergence removed), so any template that concatenates `content` as a string fails at evaluation time. Our harness reproduces this in ~25 s offline; method/fingerprints archived (each conclusion is anchored to the container/template `sha256[:12]`).

### 3. Root cause: 0.17.1 → 0.18.0 diff (`generic_data_processor.cc`)

- 0.17.1 — `runtime/conversation/model_data_processor/generic_data_processor.cc:105-112`: a `MessageToTemplateInput` override **collapses single-element text arrays to a string**:
  `content.size()==1 && content[0].type=="text" && !requires_typed_content ⇒ collapse to string`.
- 0.18.0 (verified at tag `b2f686e2e`, full-tree grep): the override is **deleted** (`generic_data_processor.cc` rewritten to 93 lines); the base-class default only calls `NormalizeMessageContent` (wraps, does not collapse); `requires_typed_content` is gone from the tree (0 hits across three files) — same conclusion reproduced on the released 0.18.0 AAR/sources.
- Why the app cannot fully compensate: our app-side `foldAdjacentText` shim collapses multi-segment user content, but W53/W55 on-device A/B (same device/build) showed with the shim **OFF** the template fails at `:23` — the runtime-side convergence is load-bearing, and a shim only covers part of the surface (e.g. content built by `parser_utils.cc` can still contain 2+ text elements).

### Not a duplicate of #3576

#3576 is a **different failure family**: `{{- tool | tojson(ensure_ascii=False) }}` inside the `{%- if tools %}` branch — a **filter-signature** issue (single-parameter `tojson` receiving a kwarg), reached only on the native tool channel and only for that bundle. This report is the **core text path on every turn**: removal of the single-element text-array convergence makes any template that treats `content` as a string fail on all messages, tools or not. Root cause, blast radius, and fix surface are all different — please keep this as a separate issue.

### Ask

1. Please confirm whether the removal of the single-element text-array convergence in 0.18.0 was intentional; and
2. either **restore the convergence** (or an equivalent in `NormalizeMessageContent`) or provide a **compatibility switch** (e.g. a `ConversationConfig` flag) so containers whose templates concatenate `content` as a string keep working.

If a conversion-time template lint is in scope (cf. the lint request on #3576), rejecting `chat_template.jinja` files whose branches treat `content` as a string would also have caught this at publication time.

Full logcat for both failing runs and the working 0.17.1 control runs on the same device is available on request.
