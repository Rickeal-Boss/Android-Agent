# 上游评论文案：`google-ai-edge/LiteRT-LM#3576` 补充评论

> 状态：⬜ **待用户手动提交**（fine-grained PAT 对 `google-ai-edge` 组织无 issue 写权限）。
> 目标 issue：https://github.com/google-ai-edge/LiteRT-LM/issues/3576 （已存在、官方已复现、
> `status:awaiting LiteRT-LM developer`）——**不新开帖，只补充评论**。
> 提交后请把评论 URL 回贴 `docs/14-user-actions.md` §B1。
> 来源：W63 波（2026-10-11）。以下正文可直接粘贴（含标题行可去掉）。

---

Confirmed on the Android AAR path as well — this is not CLI-specific.

- **Runtime**: `com.google.ai.edge.litertlm:litertlm-android:0.17.1` (AAR), Android 15 arm64 (OPPO A13).
- **Behavior**: with a container whose embedded template contains `{{- tool | tojson(ensure_ascii=False) }}` (MiniCPM5-2B bundle, inside the `{%- if tools %}` branch), the **native tool channel** fails at generation-time template render: `Failed to apply template: too many arguments (in template:6)` — the same error string as the CLI. Non-tool requests on the same bundle work fine, so the failure is only reached when the `tools` branch of the template is exercised (probe paths that never send tools do not hit it — worth keeping in mind when validating a fix).
- **Root cause pin**: in 0.17.1 the embedded Rust minijinja registers `tojson` as a **single-parameter filter** (`runtime/components/rust/minijinja_template.rs` — `fn tojson(value: minijinja::Value)` + `env.add_filter("tojson", tojson)`), so any `ensure_ascii=…` kwarg in a converted template cannot render. PyPI minijinja 2.14.0 reports the same cause as `TypeError: unexpected keyword argument 'ensure_ascii'` — same root cause, different message per binding.

**Feature request that falls out of this**: a **conversion-time template lint** in the bundler/converter — reject or warn when a `chat_template.jinja` uses filter kwargs (e.g. `tojson(ensure_ascii=…)`) that the embedded minijinja filter signatures do not support. That would have caught this bundle before publication, and would keep future converted bundles from silently shipping an unusable native tool channel.

Happy to provide the full Android logcat of the generation-time failure if useful.
