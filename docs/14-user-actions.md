# 用户行动卡（User Action Card）

> 建立于 Wave 64（2026-10-11）。**本页只列「只有用户能做」的事项** —— 工程侧能自主完成的
> 已由各波交接文档承接；这里收拢的是需要用户裁决、用户账号权限、或真机人眼/多模态判读的动作。
>
> 用法：每项三段式 —— **① 做什么 → ② 预期看到什么 → ③ 做完后告诉项目什么（销账判据）**。
> 完成一项即把该行「状态」改为 ✅ 并注明日期与结论；销账判据命中后，把该项从本页移入对应
> 交接文档的「已回收」段。
>
> 背景：第九审 C4 指出用户侧待办此前散落在交接 §三/§四/§五 与 README 台账**五处**，且上游
> issue 文案一度只存**仓外**（换机器/换接手人即失联）。本页 + `docs/upstream/` 入仓即为修复。

---

## A. 真机手验（人眼 / 多模态判读，工具盲区）

### A1. knownBroken 容器「加载前确认对话框」点验

- **状态**：⬜ 待办（W63 §三 C-1/C-2 留档）
- **① 做什么**：真机（OPPO A13 或任意在机设备）→ 底部 tab「设置」旁进「模型」页 → 找到
  `gemma-4-E4B-it-gpu`（或 `gemma-4-E2B-it-gpu` / `MiniCPM-V-4-int8`）→ 点其「加载」按钮。
- **② 预期看到什么**：
  1. 模型卡上有一行**常驻红字**（`knownBrokenOn` 原文，如「该容器在当前运行时已知不可用…」）；
  2. 点加载后**弹确认对话框**：「该容器在当前运行时已知不可用…仍要尝试？」带「取消 / 仍要尝试」；
  3. 点「仍要尝试」后**走原失败路径**（仍 Failed，不是假装成功）；
  4. 点「取消」则**不加载**。
- **③ 销账判据**：以上 4 点全中 ⇒ 告诉项目「A1 通过」+ 设备型号。任一点不符 ⇒ 回报哪一步 +
  截图（截图判读需多模态会话）。
- **为什么只有用户能做**：`uiautomator dump` 对玻璃卡片（`GlassCard`）下半区**零捕获**
  （Compose 自绘节点 + 网格单元拉伸互覆盖），自动化工具点不到、dump 里也不出现该节点
  （W63 §六.4 实证）。逻辑正确性已由单测 + 守卫 #34 兜底，**缺的只是人眼确认**。

---

## B. 上游出货（需用户账号权限，文案已入仓）

> ⚠️ fine-grained PAT 对 `google-ai-edge` 组织**无 issue 写权限**（`Resource not accessible by
> personal access token`）⇒ 以下两条**转用户手动提交**。文案已入仓（见下），换机器不失联。
> 提交后请把 URL 回贴到本页对应项，并在 `docs/12-litertlm-0171-anchors.md` 的 bump 季扫里
> 加一条「跟踪该 issue 状态」。

### B1. `google-ai-edge/LiteRT-LM#3576` 补充评论

- **状态**：⬜ 待办（issue 已存在、官方已复现，**不新开帖**，只补充评论）
- **① 做什么**：打开 https://github.com/google-ai-edge/LiteRT-LM/issues/3576 ，把
  [`docs/upstream/3576-minicpm5-tojson-comment.md`](upstream/3576-minicpm5-tojson-comment.md)
  的正文**原样**贴为评论提交。
- **② 预期看到什么**：评论成功挂在该 issue 下（含 Android 0.17.1 AAR 路径实证 + `minijinja_template.rs`
  单参 `tojson` 根因钉点 + 转换期模板 lint 诉求）。
- **③ 销账判据**：评论 URL 回贴本页 ⇒ 项目把「upstream 响应监视」挂进 bump 季扫。

### B2. gemma-4 GPU 双变体 `NOT_FOUND` 新 issue

- **状态**：⬜ 待办（上游无同型报告，#2461/#3879 签名均不同 ⇒ **新开**）
- **① 做什么**：在 https://github.com/google-ai-edge/LiteRT-LM/issues/new 用
  [`docs/upstream/gemma4-gpu-notfound-issue.md`](upstream/gemma4-gpu-notfound-issue.md)
  的**标题 + 正文**创建 issue。
- **② 预期看到什么**：新 issue 创建成功，正文含环境（0.17.1 AAR / OPPO A13 / Android 15 arm64）、
  两个失败签名（`TF_LITE_PREFILL_DECODE not found` 与缺 `VISION_ENCODER`/`AUDIO` section）、
  以及「CPU 变体同设备正常」的对照。
- **③ 销账判据**：issue URL 回贴本页。上游回复「需特定 runtime 版本 / 需 repack」时，
  据此决定是否把这两个预设从「已知坏」升为「已下架」。

---

## C. 产品 / 法务裁决（只有用户能拍板）

### C1. 法务 `termsVersion`（时序硬约束）

- **状态**：⬜ 待办（**必须先于任何法务文本替换落地**）
- **① 做什么**：裁定 —— 老用户的 `is_tos_accepted = true`（无版本 boolean）在替换法务文本后
  算「已同意第 1 版」还是「未同意任何版本」？并给出正式法务文本 + 托管地址。
- **② 预期看到什么**：`core-data/.../LegalDocuments.kt` 的 4 处 `TODO(legal)` 可清；
  `SettingsRepository` 的 `is_tos_accepted` / `is_gemma_terms_accepted` 改为带版本号。
- **③ 销账判据**：给出裁决结论 + 正式文本 + 托管 URL ⇒ 项目立项实现。
- **风险提示**：反序（先换文本、后做版本化）会让**当天所有老用户**命中 `true` 而被视为
  「已同意新条款」，首启门禁与法律页开关被直接跳过（合规事故），且事后无法反推用户当年
  同意的是哪一版（README 挂账台账 `termsVersion` 行）。

### C2. 首个 tag + 分支拓扑 canonical 化

- **状态**：⬜ 待办
- **① 做什么**：裁定首个 release tag 的落点分支（`improve` 还是 `main` 快照？）与
  「canonical 分支」是谁；`1011`（2026-10-11 快照锚点）是否冻结为只读锚点。
- **② 预期看到什么**：`git tag` 非空；`1011` 定位成文（W63 已把它定为「快照锚点 + 回滚锚点」）。
- **③ 销账判据**：给出 tag 名 + 落点分支 + canonical 裁决 ⇒ 项目补 tag 与 README 口径。
- **前置**：C1（`termsVersion`）必须先落地（法务文本未定稿前不打 release tag）。

### C3. 会话删除 UI 立项

- **状态**：⬜ 待办
- **① 做什么**：裁定是否立项「会话删除 UI」（激活既有 `delete` 卫生位）。
- **② 预期看到什么**：立项后项目在会话列表加删除入口 + 级联清理 journal/持久化。
- **③ 销账判据**：给出「立项 / 不立项」结论。

---

## 附：本页维护约定

- 新增用户侧待办 ⇒ 一律先加到本页，再在交接 §五 挂账里留一行指向本页（**单一入口**，
  不再散落）。
- 上游沟通工件（issue/评论文案）一律入仓 `docs/upstream/`，**不得只存仓外**。
- 每波收官时核对：本页「⬜ 待办」项与最新交接 §五「用户侧」项**逐条对齐**（本仓计数链已建
  三条守卫，挂账链暂无守卫 —— 靠本节手工对账，第九审 L-2 建议的最小校验待立项）。
