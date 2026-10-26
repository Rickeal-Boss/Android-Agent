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
>
> **W68 C3（2026-10-13，随 W68 交接附议程卡）**：本页即议程卡底稿 —— A/B/C 六项 + 新增
> **C4（Wave 3 三大项裁决 = 转段合法性来源）** + **C5（三项技术/产品表决）** + **§D 一页纸
> 决策清单**（R-6：每项 A/B 选项 + 成本 + 后果 + 推荐，**20 分钟会开完**；用户逐条同意/否决
> 即可，无需读交接文档）。
>
> **W72 A3（2026-10-17）新增**：**C6（bump 0.18.0 分叉裁决）** —— W72 条件真机臂已完成
> （决策树 **(b) 命中**：收益真实 + 代价真实），数据齐备、**可拍板**；另在 §C5 补
> **「悬置默认路径」条款**（N=3 波无裁决 ⇒ 自动走保守支，防悬置项无限占用排期心智）。

---

## A. 真机手验（人眼 / 多模态判读，工具盲区）

### A1. knownBroken 容器「加载前确认对话框」点验

- **状态**：⬜ 待办（W63 §三 C-1/C-2 留档）
- **推荐选项（W68 C3 附）**：**做**（用户 10 秒；docs/14 外部依赖 0/6 的最近一项；逻辑已由单测 + 守卫 #34 兜底，缺的只是人眼确认）。
- **① 做什么**：真机（OPPO A13 或任意在机设备）→ 底部 tab「设置」旁进「模型」页 → 找到
  `gemma-4-E4B-it-gpu`（或 `gemma-4-E2B-it-gpu` / `MiniCPM-V-4-int8`）→ 点其「加载」按钮。
- **② 预期看到什么**：
  1. 模型卡上有一行**常驻红字**（`knownBrokenOn` 原文，如「该容器在当前运行时已知不可用…」）；
  2. 点加载后**弹确认对话框**：「该容器在当前运行时已知不可用…仍要尝试？」带「取消 / 仍要尝试」；
  3. 点「仍要尝试」后**走原失败路径**（仍 Failed，不是假装成功）；
  4. 点「取消」则**不加载**。
- **③ 销账判据**：以上 4 点全中 ⇒ 告诉项目「A1 通过」+ 设备型号。任一点不符 ⇒ 回报哪一步 +
  截图（截图判读需多模态会话）。
- **完成后销账于**：`docs/14-user-actions.md` §A1「状态」行（⬜→✅ + 日期/结论）+ `README.md`
  波次段 W65 挂账行「🟡 C-1/C-2 knownBroken 对话框手验回收」条（现 `:468`，README 编辑会致行号
  漂移、按条名锚定，删除该条）+ `docs/handoff-20261011-wave64-closeout.md:61` §五第 3 条（同条
  删除；§五权威版 = 最新波次交接，随波次滚动）。
- **为什么只有用户能做**：`uiautomator dump` 对玻璃卡片（`GlassCard`）下半区**零捕获**
  （Compose 自绘节点 + 网格单元拉伸互覆盖），自动化工具点不到、dump 里也不出现该节点
  （W63 §六.4 实证）。逻辑正确性已由单测 + 守卫 #34 兜底，**缺的只是人眼确认**。

---

## B. 上游出货（需用户账号权限，文案已入仓）

> ⚠️ fine-grained PAT 对 `google-ai-edge` 组织**无 issue 写权限**（`Resource not accessible by
> personal access token`）⇒ 以下各条**转用户手动提交**（B1/B2 已提交，B3 待办）。文案已入仓（见下），换机器不失联。
> 提交后请把 URL 回贴到本页对应项，并在 `docs/12-litertlm-0171-anchors.md` 的 bump 季扫里
> 加一条「跟踪该 issue 状态」。

### B1. `google-ai-edge/LiteRT-LM#3576` 补充评论

- **状态**：✅ **已提交（2026-10-16）** —— 补充评论已挂到 #3576：https://github.com/google-ai-edge/LiteRT-LM/issues/3576#issuecomment-6207887539（不新开帖，只评论，符合原案）。
- **推荐选项（W68 C3 附）**：**做**（文案已入仓原样贴；bump 僵局唯一外力解之一；需用户账号约 3 分钟）。
- **① 做什么**：打开 https://github.com/google-ai-edge/LiteRT-LM/issues/3576 ，把
  [`docs/upstream/3576-minicpm5-tojson-comment.md`](upstream/3576-minicpm5-tojson-comment.md)
  的正文**原样**贴为评论提交。
- **② 预期看到什么**：评论成功挂在该 issue 下（含 Android 0.17.1 AAR 路径实证 + `minijinja_template.rs`
  单参 `tojson` 根因钉点 + 转换期模板 lint 诉求）。
- **③ 销账判据**：评论 URL 回贴本页 ⇒ 项目把「upstream 响应监视」挂进 bump 季扫。
- **完成后销账于**：`docs/upstream/3576-minicpm5-tojson-comment.md:3`「状态」字段（⬜ 待用户手动
  提交 → ✅ 已提交 + 评论 URL）+ `docs/14-user-actions.md` §B1「状态」行（⬜→✅ + URL 回贴）+
  `docs/12-litertlm-0171-anchors.md` §复核流程（`:29`）追加「跟踪 #3576 issue 状态」一行
  （bump 季扫跟踪项，锚 #13 同源）。

### B2. gemma-4 GPU 双变体 `NOT_FOUND` 新 issue

- **状态**：✅ **已提交（2026-10-16）** —— **实际落点与预案不同**：预案为 LiteRT-LM 新开 issue，用户**改投 gallery 仓库**，issue = https://github.com/google-ai-edge/gallery/issues/1326 （gallery 维护者 2026-10-12 已回复三项检查；我方回复文案起草见 [`docs/upstream/gallery-1326-reply-comment.md`](upstream/gallery-1326-reply-comment.md) ，待用户手动提交）。
- **落点说明**：本条预案文案（`docs/upstream/gemma4-gpu-notfound-issue.md`，LiteRT-LM 新 issue 形态）保留为预案留痕；**实际提交以 gallery #1326 为准**，销账与后续跟踪均按 gallery #1326 进行。
- **推荐选项（W68 C3 附）**：**做**（证据链可加 E4B 单文件 GPU OOM 对照——W66 追加证据已入本条；约 4 分钟）。
- **① 做什么**：在 https://github.com/google-ai-edge/LiteRT-LM/issues/new 用
  [`docs/upstream/gemma4-gpu-notfound-issue.md`](upstream/gemma4-gpu-notfound-issue.md)
  的**标题 + 正文**创建 issue。
- **② 预期看到什么**：新 issue 创建成功，正文含环境（0.17.1 AAR / OPPO A13 / Android 15 arm64）、
  两个失败签名（`TF_LITE_PREFILL_DECODE not found` 与缺 `VISION_ENCODER`/`AUDIO` section）、
  以及「CPU 变体同设备正常」的对照。
- **③ 销账判据**：issue URL 回贴本页。上游回复「需特定 runtime 版本 / 需 repack」时，
  据此决定是否把这两个预设从「已知坏」升为「已下架」。
- **完成后销账于**：`docs/upstream/gemma4-gpu-notfound-issue.md:3`「状态」字段（⬜→✅ 已创建 +
  issue URL）+ `docs/14-user-actions.md` §B2「状态」行（⬜→✅ + URL 回贴）；上游若回复「需特定
  runtime / 需 repack」⇒ 两个预设升「已下架」属代码侧（`feature-models/.../ModelPresets.kt`
  的 `knownBrokenOn`），由项目按 ③ 判据立项销账，不属用户动作。

> **W66 追加证据（2026-10-13，不改变本条动作，仅加固 issue 正文）**：本项目已实拉
> `litert-community/gemma-4-E4B-it-litert-lm` 的**单文件** `gemma-4-E4B-it.litertlm`
> （3,659,530,240 B，section 表含 `tf_lite_prefill_decode` + vision/audio encoder，**无缺失**）
> 并在 OPPO A13 真机双臂实测：**CPU 臂 ✅ 可用**；**GPU 臂 ⛔ 崩溃**——但根因是
> `Adreno-GSL: IOCTL_KGSL_GPUOBJ_ALLOC errno 12 Out of memory`（3.66 GB 权重塞不进入门
> Adreno 显存）→ `Resource deadlock` → `SIGABRT` → 整机重启，**与两个 `-gpu` 特化变体的
> `NOT_FOUND`/缺 section 失败面是两条不同的路**。⇒ 提交 §B2 issue 时建议**同时附上这条对照**
> （说明「section 完整 ≠ 设备可跑」，上游可据此判断是否为显存门槛问题）。

### B3. 0.18.0 单元素 content 收敛删除回归 issue（W73 新增）

- **状态**：⬜ **待用户手动提交** —— 文案已入仓 [`docs/upstream/0180-convergence-removal-regression-issue.md`](upstream/0180-convergence-removal-regression-issue.md)（fine-grained PAT 对 `google-ai-edge` 组织无 issue 写权限 ⇒ 转用户手动提交；与 gallery #1326 回复**同一次用户触点**提交）。
- **背景（为什么值得一次上游触点）**：0.18.0 删除「1 元素 text 数组 → string」收敛点（v0.17.1 `generic_data_processor.cc:105-112`；W56 tag `b2f686e2e` + W71 0.18.0 离线锚批源码级核验）⇒ 所有把 `content` 当 string 拼接的 chat_template **每轮必炸**。W72 条件真机臂实测：`Qwen2.5-1.5B`（默认中文模型）与 `MiniCPM5-2B`（主推）**首轮即失败**（journal 仅 587 B、无 MODEL 消息；逐字错串 `Failed to apply template: … tried to use + operator on unsupported types string and se…`）⇒ 爆面 ≥2/14 预设（DeepSeek-R1 Qwen 蒸馏底座高度疑似）。该收敛是 **bump 选项①的前置解**：不恢复收敛，导入闸门只能拦新导入、救不回存量预设。
- **① 做什么**：在 https://github.com/google-ai-edge/LiteRT-LM/issues/new 用 [`docs/upstream/0180-convergence-removal-regression-issue.md`](upstream/0180-convergence-removal-regression-issue.md) 的**标题 + 正文**创建 issue（与 gallery #1326 回复**同一次触点**完成，约 5 分钟）。
- **② 预期看到什么**：issue 创建成功；正文含三段证据（Q4/Q5 真机逐字签名 / minijinja 2.14.0 七格离线复现 / 0.17.1↔0.18.0 `generic_data_processor.cc` 版本 diff）+ 请求**恢复收敛或提供兼容开关** + **与 #3576 不同族声明**（#3576 = `{%- if tools %}` 段 `tojson` filter 签名问题、仅原生工具通道；本件 = user content 单元素收敛删除、每轮核心文本路径）。
- **③ 销账判据**：issue URL 回贴本页 ⇒ 项目把「跟踪该 issue 状态」挂进 docs/12 bump 季扫；上游回复「恢复收敛 / 提供兼容开关 / 判定不作」三选一，据此重开 §C6 选项①的称重（收敛恢复 = bump 代价侧 P0 消除）。
- **完成后销账于**：`docs/upstream/0180-convergence-removal-regression-issue.md` 头部「状态」字段（⬜→✅ + issue URL）+ 本页 §B3「状态」行（⬜→✅ + URL 回贴）+ `docs/12` 复核流程季扫清单追加「跟踪 0.18.0 收敛回归 issue 状态」一行（锚 #1 同源）。

---

## C. 产品 / 法务裁决（只有用户能拍板）

### C1. 法务 `termsVersion`（时序硬约束）

- **状态**：🟡 部分：迁徙语义已裁 A（2026-10-15）；正式文本/托管 URL 待提供（**仍须先于任何法务文本替换落地**）
- **推荐选项（W68 C3 附，第十三审 §5 同款）**：老用户 `is_tos_accepted = true` 视为「已同意第 1 版」+ 首启补征 v2（成本最低、无合规敞口）；**硬时序不变**：先于 tag。
- **① 做什么**：裁定 —— 老用户的 `is_tos_accepted = true`（无版本 boolean）在替换法务文本后
  算「已同意第 1 版」还是「未同意任何版本」？并给出正式法务文本 + 托管地址。
- **② 预期看到什么**：`core-data/.../LegalDocuments.kt` 的 4 处 `TODO(legal)` 可清；
  `SettingsRepository` 的 `is_tos_accepted` / `is_gemma_terms_accepted` 改为带版本号。
- **③ 销账判据**：给出裁决结论 + 正式文本 + 托管 URL ⇒ 项目立项实现。
- **完成后销账于**：`README.md:483` 挂账台账「**`termsVersion`（法务条款版本化）**」行整行移入
  同文档 `:492`「已裁定（不再是挂账）」小节 + `docs/14-user-actions.md` §C1「状态」行
  （⬜→✅ + 裁决结论）+ `docs/handoff-20261011-wave64-closeout.md:67` §五沿袭行剔除「法务
  `termsVersion`」（README 波次段沿袭句同步剔除）。
- **风险提示**：反序（先换文本、后做版本化）会让**当天所有老用户**命中 `true` 而被视为
  「已同意新条款」，首启门禁与法律页开关被直接跳过（合规事故），且事后无法反推用户当年
  同意的是哪一版（README 挂账台账 `termsVersion` 行）。

### C2. 首个 tag + 分支拓扑 canonical 化

- **状态**：⬜ 待办
- **推荐选项（W68 C3 附，第十三审 §5 同款）**：`improve` 为 canonical 分支、`1011` 定格只读快照锚点、tag `v0.1.0` 于法务（C1）落地后落；依赖 C1。
- **① 做什么**：裁定首个 release tag 的落点分支（`improve` 还是 `main` 快照？）与
  「canonical 分支」是谁；`1011`（2026-10-11 快照锚点）是否冻结为只读锚点。
- **② 预期看到什么**：`git tag` 非空；`1011` 定位成文（W63 已把它定为「快照锚点 + 回滚锚点」）。
- **③ 销账判据**：给出 tag 名 + 落点分支 + canonical 裁决 ⇒ 项目补 tag 与 README 口径。
- **完成后销账于**：`docs/14-user-actions.md` §C2「状态」行（⬜→✅ + tag 名/落点/canonical）+
  `README.md:274` 分支口径段「首个 tag 的落点分支…」句按裁决结论落定 + `README.md:450`
  W63 波次段 B1 落档条补 tag 名 + `docs/handoff-20261011-wave64-closeout.md:67` §五沿袭行
  更新「0 tags」状态。
- **前置**：C1（`termsVersion`）必须先落地（法务文本未定稿前不打 release tag）。

### C3. 会话删除 UI 立项

- **状态**：✅ 已立项（2026-10-15，本波 W69 产品件实施）
- **推荐选项（W68 C3 附）**：**立项**（小、用户可见、激活 W58 休眠钩子 `onConversationDeleted` + store 清键；方案席另建议与 docs/11 #6 插件三层开关联动评估范围，避免单开）。
- **① 做什么**：裁定是否立项「会话删除 UI」（激活既有 `delete` 卫生位）。
- **② 预期看到什么**：立项后项目在会话列表加删除入口 + 级联清理 journal/持久化。
- **③ 销账判据**：给出「立项 / 不立项」结论。
- **完成后销账于**：`docs/14-user-actions.md` §C3「状态」行（⬜→✅ + 结论）+
  `docs/handoff-20261011-wave64-closeout.md:67` §五沿袭行剔除「会话删除 UI」（README 波次段
  沿袭句同步）；若「立项」⇒ 另立波次挂账并进 README 路线图（M8 打磨段）后再销。

### C4. Wave 3 三大项保留或判死裁决（W68 C3 新增 = 转段合法性来源）

- **状态**：✅ 已裁决（2026-10-15）：定时任务 **判死**／人格系统 **保留（重定范围 = system prompt 人格档位）**／插件化装载 **判死**（见 D1 落档；`docs/11` #6/#9 随同判死留痕）
- **① 做什么**：对**定时任务 / 人格系统 / 插件化装载**三大项逐项裁决「保留（重定范围）/ 判死」；
  附带确认 docs/11 九件 backlog 判裁（#3 history_v2 流式切块 / #4 prompt cache 分层注入已判死
  留痕，#6 插件三层开关与 #9 定时任务随本裁决定夺，余 parked）。
- **② 预期看到什么**：README Wave 3 行按裁决更新；docs/11 与 `docs/15-backlog.md` 台账同步。
- **③ 销账判据**：三大项逐项结论（保留+范围 或 判死+理由）⇒ 项目更新路线图 + 台账。
- **推荐选项（方案席建议，起始立场可改）**：定时任务 = **判死**（WorkManager 版本矩阵成本高、
  端侧常驻场景价值低）；人格系统 = **保留**（与 LiquidAgent 产品定位直接相关，重定范围为
  system prompt 人格档位）；插件化装载 = **判死**（端侧安全面 + 无外部生态，docs/11 #6 随同）。

### C5. 三项技术/产品表决（W68 C3 新增；均为「等米下锅」态，裁决后即排期）

> **W69 用户会话未单独裁决，维持 W68 C3 推荐默认**（C5-1 推荐 A / C5-2 推荐 B / C5-3 推荐 B）。

- **C5-1 evidenceLevel 接线形态**（W67 挂账 §五.5）：
  - 选项 A = ModelCard 三态徽章 + 口径 tooltip（复审22 P3-①：14/14 全 MEASURED 后须防「本仓已验证」过度声称）；选项 B = 不接线、留档 README 台账。
  - **推荐 A**（零行为、防误导）；前置已报销（D1 重启前提 = 字段无消费方，W67 B3 回填已完成）。
- **C5-2 F1 第三态强度**（W67 已裁决选项 B，本项为**复议通道**）：
  - 选项 A = 强抑制（OFF 档对显式通道模型补发 `ThinkingConfig(budget=1)`，需真机双臂：R1 + gemma/MiniCPM5 OFF 回归）；选项 B = 维持 best-effort 申报（零行为改动）。
  - **推荐 B 维持**（真机双臂数据缺位前不改行为；A 的真机臂 = 台账 W68-02）。
- **C5-3 W68-B OFF 档 thinking 确定性字符硬阈**（深审 🟡-7）：
  - 选项 A = 实施（按 `maxTokens×C` 派生的固定字符硬阈，不依赖容器是否听 `enable_thinking=false`）；选项 B = 不实施（与 C5-2 的 A 耦合）。
  - **推荐 B**（预裁量③：本波零行为改动纪律优先；若 C5-2 改选 A，本项随 A 一并重开）。

> **⚠️ 悬置默认路径条款（W72 新增，莫斯线 §五.1 升级口径）**：本页任何「待裁决」技术/产品项，
> **连续 N=3 波无用户裁决 ⇒ 自动走保守支（= 不做 / 零行为改动）**，并在
> [`docs/15-backlog.md`](15-backlog.md) 记 **`closed-dead`** 留痕（判据写明「因 N=3 波悬置自动收敛」），
> 不再占用排期心智。
> - **计数口径**：以该项**最后一次被明确列入本页待裁决**的波次起算、逐波累加；
>   `docs/15` 台账「最后触碰波次」列即机械锚点（不靠人记）。
> - **用户裁决优先**：任何时点用户裁决均**覆盖**自动收敛结果；自动收敛只决定「默认不动」，
>   不改变本页结论可被重新打开。
> - **C5-1 现状（只记计数，不代裁）**：W70 / W71 / W72 三波均未单独裁决（W69 用户会话亦未裁决，
>   维持 W68 C3 推荐默认）⇒ **N=3 计数条件已满足**。⚠️ **本页不代用户裁决 C5-1 本体** ——
>   「是否把 C5-1 记 `closed-dead`」由主理人在 `docs/15` 落账时执行并留痕；本条款只确立
>   **规则 + 计数口径**，不产生既成裁决。

### C6. bump 0.18.0 分叉裁决（W72 新增 = 决策树 (b) 命中后的裁决入口）

> 编号提示：本项编号 **C6** 与 W69 工程件里的「C6 = 激活态提示 clause-aware 文案」**同名不同物**（后者是提交内小项编号，见台账 #48）；引用时请带上「docs/14 §C6」或「W69 工程件 C6」。

- **状态**：⬜ **待裁决** —— **数据收集已完成，不等于决策已完成**；**bump 本体未做**（`gradle/libs.versions.toml` 仍 pin `0.17.1`，全树零触碰）。
- **数据来源（实测，非推断）**：W72 条件真机臂 [`_research/w72-0180-device-arm.md`](../../_research/w72-0180-device-arm.md)（仓外研究件；臂 = 临时 worktree `_w72_arm` / 分支 `w72-arm-0180`，**测后弃**，主树 `git status` 全程干净）。设备 OPPO A13 `13309cc8`（有线 adb）；制品 = **Google Maven** `litertlm-android:0.18.0`（20,905,807 B；0.18.0 **从未上 Maven Central**，查证勿再查错地方）。
- **决策树定位**：`docs/12` 预登记的 (a)/(b)/(c) 三分支中 **（b）=「收益真实 + 代价真实」命中**（下方两栏全部由真机实测支撑）。
- **① 收益（实测）**：
  1. **`gemma-4-E2B-it-gpu` 引擎初始化成功**：0.17.1 下 = `NOT_FOUND`（缺 `TF_LITE_PREFILL_DECODE` section，会话建不起来）；0.18.0 下同一条 loader 信息**降为 W 级 warning 并继续**，实际后端 = **`GPU_ARTISAN`**，38 token @ **18.34 tok/s**（同臂 CPU 0.47 tok/s ≈ 39× ⇒ 确系 GPU 路径、非静默回落 CPU）。
  2. **`MiniCPM-V-4-int8` 可建会话**：0.17.1 = `Unsupported model type`（knownBroken 在册）；0.18.0 下会话建成并产出正文。代价：首 token **81.8s**（81779ms）/ 12 token —— **可用但极慢**。
- **② 代价（实测）**：
  1. **锚 #1 = P0 真机回归**：`Qwen2.5-1.5B` 首轮 `Failed to apply template: tried to use + operator on unsupported types string and se…`（journal 仅 587 B、无 MODEL 消息）；**追加实证 `MiniCPM5-2B` 同样首轮必炸** ⇒ 爆面 **≥2/14（含主推 MiniCPM5-2B 与默认中文模型 Qwen2.5-1.5B）预设**（DeepSeek-R1 为 Qwen 蒸馏底座，高度疑似）——「非数组安全模板」是**普遍面**，不是个别容器问题。
  2. **`Capabilities` 类被删除**（改 `ModelInfo.from(...)`）⇒ 首次 `assembleDebug` **BUILD FAILED**（`Unresolved reference 'Capabilities'`，7 错误）；**2 文件 API 迁移**（`core-engine/.../ModelCapabilityProbe.kt`、`core-engine/.../LiteRtLmEngineLoader.kt`，另 import 两行）后 BUILD SUCCESSFUL（1m40s）。⇒ bump **不是「改一行 toml」**：**五处联动** = toml + 2 文件 API 迁移 + 守卫 #29 期望值 + `appliesToRuntime` 字面量 + `docs/12` 锚 #14 依据列声明（#29 / #41C-1 会机械判红，**属设计意图，不是误报**）。
  3. **`gemma-4-E2B-it-gpu` 输出退化未修**：跨脚本混杂（天城文 `मोन` + `RESET Remix` 碎片，即 W43 登记签名）⇒ **`knownBrokenOn` 不能整体清空**，只能订正为「0.18.0 输出退化（引擎初始化 NOT_FOUND 已修复）」并同步 `appliesToRuntime` 版本串。
- **③ 三选项（`docs/12` 已预登记，现全部有数据支撑）**：
  - **① bump + 导入期模板探测闸门**：升级到 0.18.0，并在模型导入期加「模板数组安全性探测」闸门（不安全的模板直接拦在导入前）。**收益**：GPU 用户故事 + MiniCPM-V-4 可用；**成本**：上面三条代价全付 + 闸门本身是新功能（需设计 + 单测 + 真机臂），且闸门只能**拦新导入**、存量 14 预设仍需逐个判定。⚠️ **UNVERIFIED 运行时默认策略格（W73 N-2 补）**：闸门对 UNVERIFIED 态（容器无明文 `chat_template`、走运行时内建模板）**按模板指纹匹配 UNSAFE 才拦，指纹不中放行并保持 UNVERIFIED 标注**——论据：① 伤害载体是模板数组签名（Q4/Q5 逐字同款 `+` on string and seq），无明文模板容器不经过该签名面；② 模板指纹设施已建（14 预设 → 10 个不同模板，可复现比对）；③ R1/Phi-4「无明文模板 ≠ 不安全」反例在案（W72-02）⇒「未知即最坏」若传染运行时会 over-block 全部存量导入件。⚠️ **闸门真实成本订正（v26 审读补充）**：存量导入件全 UNVERIFIED ⇒ 闸门成本 ≠ 设计本身，而是**取证 harness 产品化**（七格复现现为仓外脚本、依赖 pip 环境；拦新导入须随包分发可复现判据）。
  - **② 继续 pin `0.17.1`，等锚 #1 收敛恢复**：零改动、零回归风险；**代价**：GPU 用户故事（W68-07）与 MiniCPM-V-4 继续不可用，`gemma-4-E4B-it` 单文件双后端路线仍是唯一出路。⚠️ **残余风险（v26 审读补充）**：E2B·GPU 输出退化未修（W43 签名、0.18.0 臂仍在）+ MiniCPM5 GPU 未测——两条债不因 pin 而消，锚 #1 收敛恢复后 bump 时仍须面对。
  - **③ 双 runtime（0.17.1 + 0.18.0 并存）**：**不可行** —— 单依赖（同一 AAR 坐标 + 同名 native 符号），两版本无法同进程共存；除非复制 native 符号做私有封装，成本远超收益（shadow + dlopen 前缀改造量级 = **周级**，v26 审读补充）。
- **④ 销账判据**：用户给出「①/②/③」明确选择（或「暂不裁决」）⇒ 项目按选择立项：① ⇒ bump 分支 + 闸门设计 + 四处联动 + 真机回归臂；② ⇒ 把 `docs/12` 决策树收敛为「(b) 已知、维持 pin」并关闭 W68-06 的 bump 待裁面；③ ⇒ 先做可行性否决记录（预期否决）。
- **推荐（方案席立场，可改；用户裁决优先）**：**② 继续 pin**。理由：① 的收益两项都是**边缘可用**（E2B-GPU 输出仍退化 = 实际不可用；MiniCPM-V-4 首 token 81.8s = 体验不可接受），而代价命中的是**默认锚模型 Qwen2.5-1.5B 的首轮 P0**（≥2/14 预设），代价/收益严重不对称；待锚 #1 收敛（上游或导入闸门落地）再议 ①。
- **⚠️ 口径纪律**：本节**只呈现实测数据与三选项**，不构成 bump 决策；任何「bump 已开始」的表述均不成立（`libs.versions.toml` 零触碰、主树零改动）。

### C7. JankStats（metrics-performance）依赖审批（W81 新增 = W80-07 的解锁钥匙）

> **编号提示**：本项编号 C7 与工程件内小项编号无关，引用时请带「docs/14 §C7」。

- **状态**：⬜ **待裁决** —— W80-07（帧统计） parked 的唯一原因就是「等用户批依赖版本」；本项不入队 = 用户按惯例读本页永远看不到 ⇒ 帧测量的完整指标路径将无声卡死（第二十审 F1）。
- **背景（为什么值得一次裁决）**：W80-01 真机取证 PDRM00/ColorOS `dumpsys gfxinfo` 帧统计**恒 0**（设备级不可用）⇒ 滚动/生成期帧率无一手数据，W80 全部 perf 改动处于「改前无基线、改后无验证」状态。完整路径 = `debugImplementation androidx.metrics:metrics-performance`（JankStats：运行时库、无注解处理器、release 零影响）。**W81 已落地零依赖的 Choreographer 采集（fps + 掉帧计数，仅 debug 构建）解锁 W80-08 标定前提** ⇒ 本项性质从「阻断」降为「更完整指标（jank 分类 / 状态管理 / 聚合报表）的后续」。
- **① 做什么**：裁定是否批准新增 `debugImplementation androidx.metrics:metrics-performance`（版本随批时锁死，走 `gradle/libs.versions.toml`）。
- **② 预期看到什么**：批准 ⇒ 项目在 debug 源集接线 JankStats，产出生成期/滚动期 p50/p95 帧直方图（补 W80-01 的 scroll/generation 空缺）；不批 ⇒ W80-07 转 closed-dead 留痕（Choreographer 采集已够用，零依赖口径维持）。
- **③ 销账判据**：给出「批准（附版本）/ 不批」结论 ⇒ 项目按结论立项或销账。
- **推荐选项（方案席立场，可改；用户裁决优先）**：**不批 / 暂缓**。理由：零依赖采集已解锁当前唯一决策（W80-08 标定）；JankStats 的增量价值（jank 分类）要等「帧数据发现真问题」才兑现——现在批 = 为假想的收益付一次依赖面。若日后 Choreographer 数据不够用再重开。
- **完成后销账于**：`docs/14-user-actions.md` §C7「状态」行（⬜→✅ + 结论）+ `docs/15-backlog.md` W80-07 行（批准 ⇒ active 排期；不批 ⇒ closed-dead 留痕）。

---

## D. 一页纸决策清单（R-6；20 分钟会，逐项同意/否决即可）

| # | 事项 | 选项 A | 选项 B | 成本 | 推荐 |
|---|---|---|---|---|---|
| 1 | A1 knownBroken 对话框手验 | 做（10 秒人眼） | 跳过 | 10 秒 | **做** |
| 2 | B1 上游 #3576 评论 | 做（文案已入仓） | 跳过 | 3 分钟 | **做** |
| 3 | B2 gemma GPU 新 issue | 做（附 E4B 对照） | 跳过 | 4 分钟 | **做** |
| 4 | C1 termsVersion | 老用户视为已同意 v1 + 首启补征 v2 | 全部重新同意 | 法务工作量 | **A**（硬时序：先于 tag） |
| 5 | C2 tag + 拓扑 | improve canonical + 1011 定格 + v0.1.0（法务后） | main 快照 canonical | 2 分钟 | **A**（依赖 #4） |
| 6 | C3 会话删除 UI | 立项（激活 W58 钩子） | 不立项 | 一波次 | **立项** |
| 7 | C4 定时任务 | 判死 | 保留重定范围 | — | **判死** |
| 8 | C4 人格系统 | 保留（人格档位） | 判死 | — | **保留** |
| 9 | C4 插件化装载 | 判死（含 docs/11 #6） | 保留重定范围 | — | **判死** |
| 10 | C5-1 evidenceLevel 接线 | 三态徽章 + tooltip | 不接线留档 | 半天 | **A** |
| 11 | C5-2 F1 强度 | 强抑制（需真机双臂） | 维持 best-effort | 一真机窗 | **B** |
| 12 | C5-3 W68-B OFF 硬阈 | 实施 | 不实施 | 半天 | **B** |
| 13 | **C6 bump 0.18.0 分叉（决策树 (b) 命中）** | ① bump + 导入期模板探测闸门 | ② 继续 pin 0.17.1 等锚 #1 收敛（③ 双 runtime 不可行：单依赖） | ① toml + 2 文件 API 迁移 + **≥2/14（含主推 MiniCPM5-2B 与默认中文模型 Qwen2.5-1.5B）预设首轮 P0** + 闸门新功能；② 零改动 | **②**（① 待闸门设计 + 锚 #1 收敛） |
| 14 | B3 上游 0.18.0 收敛回归 issue | 做（文案已入仓，与 gallery #1326 回复同一次触点提交，约 5 分钟） | 跳过（bump 僵局少一个外力解） | 5 分钟 | **做** |
| 15 | **C7 JankStats 依赖审批（W80-07 解锁钥匙）** | 批准 `debugImplementation metrics-performance`（jank 分类/聚合报表，debug-only） | 不批（W81 零依赖 Choreographer 采集已够用，W80-07 转 closed-dead） | 批准 = 一次依赖面 + debug 源集接线 | **不批/暂缓**（假想收益不付依赖面；数据不够用再重开） |

> 会议流程建议：#1–#3 手验/贴文项先过（8 分钟内）；**#14（B3）与 gallery #1326 回复同一次触点提交**；#4/#5 法务与 tag 联动定；#6–#9 产品项
> 逐项拍；#10–#12 技术项随 #11 一并定（#12 与 #11 联动）；**#13 与 `docs/12` 决策树 (a)/(b)/(c)
> 联动**（(b) 已实测命中，数据在 `_research/w72-0180-device-arm.md`，本节即拍板底稿）。全部结论回贴本页各「状态」行，
> 项目按 `docs/15-backlog.md` 台账（W68-04）逐项销账。
>
> ⚠️ **悬置项自动收敛**（W72 起）：本清单中任一「待裁决」项**连续 N=3 波无裁决 ⇒ 自动走保守支**
> （= 不做 / 零行为改动）并在 `docs/15` 记 `closed-dead` 留痕（详见 §C5 末「悬置默认路径条款」）；
> 用户裁决**优先于**自动收敛。**C5-1 的 N=3 计数条件已满足**（W70/W71/W72 三波未裁决），
> 但**本页不代裁**其本体 —— 落账动作由主理人在 `docs/15` 执行。
> **#10（C5-1）= 悬置条款首次行使检查点**：N=3 已满足 ⇒ 请用户确认 **closed-dead**（永久退出候选池，由主理人在 `docs/15` 落账留痕）或**救回**（转为 UI 波首件）—— 本页仍不代裁。

---

## E. 决策仪表板（W86 重建 · 唯一决策入口）

> **为什么重建**：外审第四轮 F5 实测 —— W82-15 在 §D 有条款、在台账有状态位，但 `grep -c "W82-15" docs/14` = **0**；
> U1–U5 同样 0 命中（自 W75 起悬置 **10+ 波**）。决策项散落在 `docs/14` / `docs/15` 状态位 / 专项 plan 三处，
> **互不索引、无波龄计数、无机械执行** ⇒ 「工程侧对外审吸收 < 24h」与「决策侧多波零产出」的剪刀差持续拉大。
> 本节把三条入口收编成一张表：**每项 = 一句话题面 + 台账 ID + 登记波次 + 已悬波数 + §D 状态 + 不裁决时的默认值**。
>
> **维护规则（每波收官必做，1 分钟）**：对本表每项 `已悬波数 +1`；`≥ 3` 即按 §D 走保守支并在 `docs/15` 记
> `closed-dead` 留痕（**用户裁决优先于自动收敛**，随时可救回）。新增决策项**必须先加到本表**，再在交接挂账留一行指针。

| # | 题面（一句话） | 台账 ID | 登记波 | 已悬波数 | §D 状态 | 不裁决时的默认值（推荐） |
|---|---|---|---|---|---|---|
| E1 | 首字延迟 p50 12.9s：启动即预热 / 进对话页后台预热 / 维持现状 + 文案披露？ | W82-15 | W82 | **4** | 🔴 **已触发并执行（W86）** | **维持现状 + 文案披露**（= 选项 C，零行为改动）⇒ 已 `closed-dead`，可救回 |
| E2 | UI 波 U1–U5（`docs/w75-ui-plan.md`）立项与否 | 无独立 ID | W75 | **11** | 🔴 已触发（本表补登后才可计） | **不立项**（保守支）；若你要做，请指定 U1–U5 里哪几项 |
| E3 | 无障碍（语义标签 / 48dp 触控目标）做不做 | W84-13 | W84 | 2 | ⏳ 未触发（N=2） | 暂不排期（core-design 波再议） |
| E4 | 法务：termsVersion 老用户口径 + 托管地址 | W68-08 | W68 | 长 | ⏳ 未触发（非 §D 管辖，属硬时序前置） | 视为「首启补征 v2」；**它是首个 tag 的硬前置** |
| E5 | 发布拓扑 + 首个 tag（哪条线 canonical） | C2（§D #5） | W68 | 长 | ⏳ 同上 | `core-fix` 为 canonical（三支演进线已实质合流于此） |
| E6 | litertlm bump 0.18.0 分叉 | C6（§D #13） | W72 | 多波 | ⏳ 数据已齐、可拍板 | **继续 pin 0.17.1**（选项 ②） |
| E7 | JankStats 依赖审批 | C7（§D #15） | W80 | 多波 | ⏳ | **不批/暂缓** |
| E8 | **sha256 锚点来源的信任模型**：接受「镜像体积一致性 + ModelScope API 摘要」作权威，还是必须等 HF 官方摘要？ | W86-02 | W86 | 0 | ✅ **已裁决（W86）：维持 W86-02 现状，不退回 `null`** | **接受当前口径**（体积逐字节相等 + 同名同仓 ⇒ 同一份字节；1 例 Qwen2.5 体积差 8664B 显式 fail-open） |
| E9 | i18n（171 处硬编码中文）做不做 / 边界在哪 | 沿袭滚动 | — | 长 | — | 暂不做（发布链前置） |

> **E8 单独说明（这是一条会改变信任模型的裁决）**：本波把 13/14 条预设的完整性锚点**通电**了，来源是
> ModelScope 镜像仓 API 的 `Sha256` 字段，跨源同一性用「仓库体积逐字节等于预设 `sizeBytes`」作代理判据
> （`sizeBytes` 2026-09 取自 HF API）。本仓沙箱无法直连 HuggingFace（502），拿不到 HF 侧摘要 —— 若你要求
> 「必须 HF 官方摘要才算数」，则这 13 条应退回 `null`（校验面重新 vacuous）。当前取舍与理由写在
> `ModelPresets.sha256` 的 KDoc 与 `scripts/fetch-preset-sha256.py` 头注里，可逐条复核。
>
> **W86 裁决结论（用户于 2026-10-26 23:55 明确）**：**不接受「必须等 HF 官方摘要」的更严要求，维持 W86-02
> 现状**——13/14 锚点保留（仅对体积逐字节相等者通电；Qwen2.5 体积差 8664B 保持 `null` fail-open）。即
> 「不裁决默认值 = 接受当前口径」已被用户确认采纳，无需代码回退。本仓仍保留 `fetch-preset-sha256.py` 作为
> 日后若直连 HF 可一键复核/回填的通道。

---

## 附：本页维护约定

- 新增用户侧待办 ⇒ 一律先加到本页，再在交接 §五 挂账里留一行指向本页（**单一入口**，
  不再散落）。
- 上游沟通工件（issue/评论文案）一律入仓 `docs/upstream/`，**不得只存仓外**。
- 每波收官时核对：本页「⬜ 待办」项与最新交接 §五「用户侧」项**逐条对齐**（本仓计数链已建
  三条守卫，挂账链暂无守卫 —— 靠本节手工对账，第九审 L-2 建议的最小校验待立项）。
- **W68 C3 起**：本页随每波交接**直接附议程卡**（§D 一页纸决策清单为会议底稿）；用户结论回贴
  后按 `docs/15-backlog.md` 台账（W68-04 及各项 ID）销账——「等待天数」若持续增长，须在交接
  首行显式披露（第十三审 G3：让停滞成为可见成本，而不是沿袭清单里的一行字）。
