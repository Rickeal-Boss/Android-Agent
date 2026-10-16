# 上游评论文案：`google-ai-edge/LiteRT-LM#3576` 补充评论

> 状态：✅ **已提交（2026-10-16，评论 comment-6207887539：https://github.com/google-ai-edge/LiteRT-LM/issues/3576#issuecomment-6207887539）**（fine-grained PAT 对 `google-ai-edge` 组织无 issue 写权限 ⇒ 提交由用户手动完成；不新开帖，只评论，符合原案）。
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

---

## 上游动态（W70 回填，2026-10-16）

- **2026-09-16** 上游（COLLABORATOR `nireekshak`）评论本 issue（[#issuecomment-5699703637](https://github.com/google-ai-edge/LiteRT-LM/issues/3576#issuecomment-5699703637)）：**已在本地复现两种行为并定位根因** —— 已发布的 `MiniCPM5-2B_int4.litertlm` bundle 内嵌的是**原始检查点模板**（`tojson(ensure_ascii=False)`），且元数据为 `llm_model_type { generic_model {} }`，而非 XML `<function>` 路由所需的 `llm_model_type { minicpm5 {} }`（后者才会走 `MiniCpm5DataProcessor`）。另：在用 `minicpm5` 元数据 repack 测试时发现 **XML `<param>` 类型处理与 `0.17.0` 模板兼容性**的若干细节，承诺**继续调查**。
- ⇒ #3576 从「我方单方报告」升级为「上游复现 + 根因共认」。**修复可能落在 bundle 层**（重打包换模板/元数据）而非引擎层（放宽 `tojson` 签名）——两种路径下，本仓 MiniCPM5 容器原生工具通道均须**真机复验**后方可销账（docs/12 锚 #13 已同源入档，见 W70 C3）。
- 我方评论已于 2026-10-16 提交（comment-6207887539）：确认 Android AAR 路径同现象 + minijinja 单参 `tojson` 根因钉点 + 转换期模板 lint 诉求（正文见上）。
