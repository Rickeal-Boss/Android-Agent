# docs/15-backlog.md —— 挂账台账单一事实源（W68 转段波建立；W69/W70/W71 续更）

> **建立**：W68 C1（治理急救，第十三审 G1 修补②）。**地位**：本文件是挂账（backlog）的**唯一事实源**；
> 各波交接文档 §五 **只写状态变更（delta：销账/新增/顺延 + 判据），不再整表重抄**。
> **为什么建**：W58 §六.6 重写沿袭清单时「数据回填/阈值回填」两项静默丢失、十波无一提及
> （G1 事故，实证见下方复盘表）——沿袭清单是全仓唯一没有机械守卫的聚合面，手抄传递必腐。
> **机械核对**：轻量守卫（交接 delta 项 ID 必须存在于本台账）已落地 = arch-guard **#42**（W70 `ee3ca12`：ledger-era 交接 `W(6[89]|[7-9]\d)-\d+` ID ⊆ 本台账主台账 ID 集，fail-closed；原台账项 W68-09 已 closed-done）。

## 图例与字段纪律

- 状态四态：**active**（可排期）｜**parked**（有重启前提未满足）｜**closed-done**（已完成销账）｜**closed-dead**（判死留痕）。
- 每条必含四要素（缺一即 FAIL）：**ID + 状态 + 重启前提 + 最后触碰波次**。
- 销账必须附**销账判据**（可执行触发条件）；未过判据不得销账。
- **维护约定（P3-④ 成文）**：新增/修订 `ModelPresets` 条目的 commit，必须**同 commit** 迁本台账
  （条数、预设 ID、状态）——B4 群发未含台账同步义务曾致 13→14 漂移五处（复审22 P3-④）。

- **波次编号大小写（复审 34 P3-3 定义）**：`w79`（小写）= 2026-10-22 晚 **W78-12 跟进批**的真机取证批
  （非独立波次；交付 `ee0df65` / `12bef26`，记录见 `docs/handoff-20261022-wave78-bg-run.md` §七）；
  `W79`（大写）= 2026-10-23 起的 **core-fix 波**（P1 冻结时钟修复 + UI 圆角/间距收敛 + #1326 诊断）。

## 主台账

| ID | 条目 | 状态 | 重启前提 | 最后触碰波次 |
|---|---|---|---|---|
| W68-01 | **数据回填**（= G1 恢复项①）：W46 P0 用户数据丢失的补救。口径已裁定 = 按 USER 交错插入（不能 append）+ 过 R-E 健康判据；丢失回复仍在 journal 可救 | active | 触发时机与去重判据裁决（**用户议程** W68-04） | W68 |
| W68-02 | F1 选项 A 真机臂 / W68-B（OFF 档 thinking 确定性字符硬阈） | active | 议程卡裁决改选强抑制（预裁量③：本波零行为改动） | W68 |
| W68-03 | evidenceLevel 接线（ModelCard 三态徽章 + 口径 tooltip） | active | 产品裁定（议程卡）；「此字段不参与可信度排序」声明先行 | W68 |
| W68-04 | docs/14 六项用户侧 + 新增 C4（Wave 3 三大项裁决）+ C5（三项技术/产品表决） | active | 用户会话发起后按项销账（**C4 已于 W69 裁决销账，见 W69-06；C5 三项 W69 用户会话未单独裁决，维持 W68 C3 推荐默认**） | W68 |
| W68-05 | 会话删除 UI（C3）+ `onConversationDeleted` 钩子激活 | active | C3 过会；未过则钩子休眠状态成文（W68-D 分支） | W68 |
| W68-06 | bump 首跑判据（docs/12 锚 #5 未验 + fold 收敛点前置）+ **knownBroken/GPU 白名单过期复审条目**（随功能刀 B3 入 bump 锚清单，预裁量④） | active（blocker 维持；**W71 起单维持**） | **锚 #5 已验**（W71 离线锚批：0.18.0 AAR 双 ABI `.so` 均含 `ThinkingBudgetConstraint` ⇒ 「锚 #5 未验」半边消除）；剩余维持 = **锚 #1 收敛缺失**（v0.18.0 实证删除单元素收敛，W56 复核一致）；bump 决策树 (a)/(b)/(c) 已预登记 docs/12（W71 C1） | W71 |
| W68-07 | gemma-4 GPU 用户故事（E4B 单文件 GPU 在 A13 OOM；需高显存设备复测；上游 `-gpu` NOT_FOUND issue 仍应提交） | active | 高显存设备 | W68 |
| W68-08 | main fast-forward / 首个 tag / 法务 `termsVersion`（C1/C2） | active | **C1 迁徙语义已裁 A（2026-10-15：老用户 `is_tos_accepted=true` 视为「已同意第 1 版」+ 首启补征 v2）；剩余阻塞 = 正式法务文本 + 托管地址（待用户提供）**；C2 tag 拓扑依赖 C1 文本（预裁量⑤：tag 未立前 FF 无收益） | W68 |
| W68-09 | docs/15 轻量核对守卫（交接 delta ID ∈ 台账，沿 #27 范式） | closed-done | 销账判据：#42 落地（W70 `ee3ca12`，arch-guard 42 项）——ledger-era 交接 delta ID ⊆ 台账机械核对 + fail-closed；运维提醒见 W70 交接 §六.2 | W70 |
| W68-10 | **模型直答腿**（W65 §五.4：gemma-2B 6 触发 0 合规，端侧 2B 「提醒后直答」不合规） | closed-dead | 判死留痕：端侧 2B 现实；若需考察换更强模型（重启 = 新模型入册时重开） | W68 |
| W69-01 | ToolExecutionGuard.kt 拆分实施（= G4 具体化落地；含深审 🟡-5 墙钟口径） | closed-done | 已实施（拆分 + 🟡-5）；**🟡-1/🟡-4 设计草案转 W69-12/W69-13** | W69 |
| W69-02 | 内存公式补实测批（MiniCPM-V 4·8B 6.5GiB / Phi-4-mini 5.6GiB / E4B·GPU 5.2GiB + meminfo PSS 含 swap 定案） | closed-done | 销账判据：W71 真机实测批（`_w71_dev/mem_report.txt`）—— Phi-4-mini 峰值 PSS **5.20 GiB**（+swap 0.18 = 5.28）vs 预测 5.6；E4B·GPU 峰值 PSS **5.00 GiB**（+swap 0.18 = 5.01）vs 预测 5.2；MiniCPM-V-4 **执行失败不可测**（knownBroken `Unsupported model type`，只记失败路径 0.40 GiB，如实申报）。PSS/swap 口径定案 = `dumpsys meminfo` 的 TOTAL PSS 与 TOTAL SWAP PSS 分列（PSS 不含 swap）。两可测容器实测均**低于**预测（余量 0.19–0.32 GiB）⇒ **tail 1.25 维持不改** | W71 |
| W69-03 | **阈值回填校准**（= G1 恢复项②）：健康探针 BAD/DEGRADED 校准 | closed-done | 销账判据：W70 真机校准（**6 有效**容器探针批 = 4 PASS + MiniCPM-V-4 Failed + D7 PASS；另 **2 歧义**容器 Qwen2.5/Phi-4-mini logcat 陈旧行污染判据歧义，脚本已修 ⇒ 不计入有效样本）⇒ 候选判据「低字符多样性 <0.15」**校准后不采用**（退化样本多样性 0.722 与健康 0.748 均远高于阈，无判别力）；KDoc 留痕含探针保真度缺口申报（`7450b21`）。**W71 订正（Phi-4 歧义根因查明）**：W70 驱动脚本 Phi-4 UUID 错一位（`4c95` vs 实际 `4d95`）⇒ 该轮实为「未选中模型」态（非陈旧行）；W71 已用正确 UUID 补跑（内存批 9 样本峰值 PSS 5.20 GiB）⇒ 校准结论本身不受影响（Phi-4 不进多样性判据的判分面） | W70 |
| W69-04 | #39 开启行 evidenceLevel 提取为无锚定 match（raw string 若放 `ModelPreset(` 开启行且内容含「evidenceLevel = X」字样可污染 ev 判定 = 极端構巧；来源：严质衡 W68 A1 复审 P3） | parked | 出现该写法时修（awk 侧同款 `blank_strlits` 对偶）或随 #39 下次大修 | W68 |
| W69-05 | #41 镜像/续行形态覆盖（A3-R2 已改字段级根治主面；残余 exotic 边界见扫描器 docstring） | parked | 观察一轮；出现镜像/续行新形态时随 #41 扫描器大修 | W68 |
| W69-06 | **C4 裁决落地**（README Wave 3 行 + `docs/11` #6/#9 + 本台账三处同步） | closed-done | 销账判据：三处文档按 §四.E 裁决落定（W69 议程销账） | W69 |
| W69-07 | **人格系统（重定范围 = system prompt 人格档位预设 + 选择器）** | closed-done | 销账判据：人格档位产品件交付（W70 `be43745`：PersonaPresets 3 档 + SettingsScreen chip 选择器 + 逐字相等 matches + 单测 3 例 + baseline 730）+ 真机冒烟（chip 点选逐字写入 systemInstruction）；**不做**原项目 16-MBTI 模板 + SOUL.md 工作区文件机制（桌面 agent 形态）——C4 裁决范围外 | W70 |
| W69-08 | **定时任务（CronManager）** | closed-dead | 判死留痕（W69 C4 裁决）：无人值守自主执行**强依赖模型能力**（端侧 1-4B 指令遵循弱、工具调用不稳；W65 实证端侧 2B「提醒后直答」不合规 = 本台账 W68-10 closed-dead）、触发时用户不在场做错比不做更差、成本含 WorkManager 版本矩阵 + 国产 OEM 后台存活限制、源项目「与 IM/控制台共用处理链路」前提在端侧不存在。**重启前提** = 端侧模型跨过「无人值守可靠执行」阈值（≥8B 在机且工具链稳定）或单点低风险模板化任务诉求（须重立项，不走通用 cron） | W69 |
| W69-09 | **插件化装载** | closed-dead | 判死留痕（W69 C4 裁决）：端侧无外部生态（插件系统价值 = 生态）、插件 = 任意代码执行安全面与端侧定位冲突、工具面收窄诉求已由 disclosure 元工具（W27）+ `ToolRegistry` 动态启停更低成本满足。**`docs/11` #6 随同判死留痕**。**重启前提** = 出现真实第三方工具接入诉求且配套审核/沙箱机制设计成立 | W69 |
| W69-10 | **D7（R1 探针 verdict 实读）+ 2047 补丁臂（N-2 warn 真机命中）真机件** | closed-done | 销账判据：D7 ✅（R1 verdict PASS、采样（空）、27020ms，W66 预判复验）；2047 臂 Arm B 完整行使（改→行使→还原→重建→git 干净）但 **N-2 未命中**（R1 思考 ~14-200 token ≪ 2047 预算无 starvation + 非工具 JSON 答案非空）⇒ 离线替身测试保持触发链权威判据，真机臂按计划定位降级为回归确认；残留 N-2 真机命中挂 W70-01；Y3 ✅（gpuClause 警告 + GPU→CPU 回落闸双命中） | W70 |
| W69-11 | **#40/#41 锚对账机械化**（豁免令牌 `W##` 锚点 ↔ `docs/12` 锚清单机械对账；复审23 §四可迁移项，W68 §六.8 W69 主刀） | closed-done | 销账判据：#41 方向 C 落地（W70 `ee3ca12`：C-1 appliesToRuntime 取值集合+条数 ↔ docs/12 锚 #14 声明非 vacuous；C-2 豁免锚对账现役 0 命中如实标注 + selftest case73 假锚注入证明可触发） | W70 |
| W69-12 | **工具超时孤儿写代次校验**（外部审查「深审」🟡-1）：超时后孤儿协程仍可能完成 `File.writeText` + `ATOMIC_MOVE`，静默覆盖重试后的新内容（last-writer 竞态） | parked | `ToolContext.isCurrent` API 设计通过（侵入 `FileTools` / `ContentAddressedPool` 两处写路径） | W69 |
| W69-13 | **超时调用的 indeterminate 记账**（外部审查「深审」🟡-4）：超时副作用在「同参防连发」计数之外异步发生 ⇒ 双写窗口 | parked | `ToolResult` 加 indeterminate 位（core-model）+ 护栏判据 + 提示词面变化须真机验证 | W69 |
| W70-01 | **N-2 warn 真机命中**（W69-10 2047 臂的残留真机件） | parked | 重启 = 端侧模型思考长度可逼近预算档（≥1.5k token 推理链）或低预算档配置；W70 实测 R1 思考仅 ~14-200 token（远低于 2047 预算）且设备 ~2s/token 下达预算需 ~70min 不现实 ⇒ 离线替身测试（ThinkingBudgetN2SubstituteTest）保持触发链权威判据。**W71 P-2 budget=96 臂（第十四审 P-2 假设）实测未命中且归因精确化**：budget 确认生效（logcat `think 96`），两轮 thinking 仅 **188/180 字符 ≈ 36-37 token**（r≈5）≪ 96 ⇒ 预算从未触顶；正文 211/265 字符非空（N-2 前提「thinking 非空 ∧ 正文空」不成立）⇒ **压低预算 ≠ 造出 N-2**：需模型单轮思考 ≥ 预算**且**预算耗尽后不产正文（模型行为面，非配置面）；「R1 思考上限 ~200 token」在该题面不成立（实测 36-37 token）。重启条件收紧为：**出现实测思考 > 预算且正文为空的模型/题面组合**（离线替身仍为权威判据） | W71 |
| W70-02 | **记忆冲突调和**（端侧记忆写入新值覆盖旧值时的冲突调和策略：同 key 新旧值并存/择优/合并）。来源注记：外部参照 Hy-Memory 对照确认可行域；**SDK/30B/MemHub 均不接入（不另立台账）；不含 embedding**（纯文本匹配面） | active | 记忆写入面（写点① `ensureConversation` 成功路径同族）上线时按销账判据执行；**销账判据**：① 覆盖后可观测旧值（旧值不静默丢失，判据可执行 = 写入后旧值可经读取路径查得或显式标注被覆盖）；② 纯 JVM 单测覆盖调和三分支；③ 注入面增量有界（不新增跨模块依赖、不动 AgentRunner 面） | W71 |
| W70-03 | **可复用经验记忆**（跨会话沉淀可复用经验并在新会话召回）。来源注记：外部参照 Hy-Memory 对照确认可行域；**SDK/30B/MemHub 均不接入（不另立台账）；不含 embedding**（纯文本匹配面） | active | W70-02 冲突调和落地后评估可行性；**销账判据**：① 可行性结论成文先行（召回精度/误召回成本量化结论落 docs/12 同族归档）；② 预算淘汰策略成文（容量上限 + 淘汰序：LRU/频次/优先级择一并写死）；③ 验证不通过 ⇒ 转 closed-dead 留痕（判死须附失败形态，沿 W69-08/09 范式） | W71 |
| W71-01 | **#41 方向 C-1 兄弟面：raw string 污染提取假红**（W71 G5 迁移 #29 时申报的待修面）：`check-preset-profile-parity.py` 的 #41 C-1 appliesToRuntime 值集提取若遇 `ModelPreset(` 开启行是 raw string 且内容含 `appliesToRuntime = "X"` 字样 ⇒ 可污染 ev 判定（字符串内容里的同款字样被误提取；#29 已迁 preset_blocks 双重锚定免疫，兄弟面 #41 未迁） | parked | 随 #41 扫描器下次大修时同批迁移 preset_blocks（或单独立刀）；现役 ModelPresets.kt 0 处三引号 raw string（申报面非缺陷）；selftest case74 已证明 #29 面免疫，case74 注释与 arch-guard.sh #41 段注释均在案 | W71 |
| W71-02 | **P-1 脚本混合判据进探针（候选判据 E）**：W71 离线判分（`_plans/w71-p1-scriptmix-report.md`）——E2B-GPU 退化样本命中（西里尔 2 字/4.3%）、健康 E2B-it 零误报；但可判分样本仅 n=2（退化 n=1）⇒ 单样本命中 ≠ 判据有效 | parked | **数据面补齐 + 阈值标定先行**：① 重跑探针批使 ≥6 容器采样文本在档（W70 存档仅 2/8：4 容器 dump 早于出结论、1 无激活模型、1 执行失败）；② 多退化样本上确认「健康零误报 ∧ 退化命中」双条件；③ 阈值标定（现 4.3% 命中 vs 0% 误报）；④ 齐备后走 W69-03 同款流程（KDoc + 单测 + 真机批）另立项。**禁凭 n=2 直接落地** | W71 |
| W72-01 | **LOSSY 态监控面**（W72 D 数据件）：`templateArraySafety` 四态中 LOSSY **现役 0 命中**，但性质已被离线七格实测可复现——MiniCPM5-2B 的 user 分支 `U_arr1/U_arrN` = `LOSS`（文本被清空不抛错），当前被 system 分支抛错**遮蔽** ⇒ 真机表现为 UNSAFE | parked | **重启前提 = 任何人修掉 `+ messages[0].content` 的 system 分支抛错**（上游或本仓侧）而不同时修 user 分支 ⇒ MiniCPM5 滑入不可见丢字（探针/logcat/输出判别全盲）。修 system 分支的 PR 必须同时跑 `_ci-tools/_w72_tpl_safety.py` 七格并复核 user 分支（**复现命令见 docs/12「七格取证判定表」节**——pip minijinja + 跑法 + 容器/模板 sha256 锚定，脚本本体仓外不入库） | W72 |
| W72-02 | **R1 / Phi-4 模板数组安全补证**（W72 D 数据件的 UNVERIFIED 面）：两容器**无明文 `chat_template`**（section 扫描仅 `TF_LITE_PREFILL_DECODE`/`model_type`/`prefill_*`/`decode`）⇒ 走运行时内建模板，容器侧取证**方法不适用**（非漏做） | parked | 补证二选一：① 真机（0.18.0 臂或 bump 后首发题面看是否 `Failed to apply template`）；② 读上游 0.18.0 内建模板源码定数组行为。不补 ⇒ bump 评估按「未知即最坏」计入。**W73 X4 备忘**：补证时同步标注 0.18.0 维度（0.18.0 容器模板形态可能不同，若内嵌明文模板则 UNVERIFIED 可升级取证） | W72 |
| W72-03 | **datastore_tool 设备 I/O 长尾**（W72 C 治理槽）：T-3 已端到端行使 snapshot / set-active-model / restore / verify 四命令各 ≥1 次；但「UUID 对账拒绝路径」「超范围自动回滚路径」仅离线单测覆盖（13/0 + 变异测试），真机恶意输入路径 ⛔ vacuous。`--skip-index-check` 逃生阀**裁断不加**（W70 UUID 错一位正是被宽容放过） | parked | 重启前提 = 设备侧 `files/model_index/models.json` 路径/格式变更（索引读不到 ⇒ 工具 exit 2 拒绝，届时同步改 `validate_uuid_in_index`）；或出现需要绕过对账的合法场景（须先过主理人裁断） | W72 |
| W73-01 | **K2 层5 压缩门控**（token 实测账本的压缩门控消费面；最老悬置 ~22 波） | closed-dead | **判死留痕（W73 主理人终裁）**：压缩触发恒由估算开窗（W41）驱动，无估算失准事故记录；Wave 30 §2.3 明立「账本只做读侧投影，门控口径不接」= 设计决策非疏漏；接线的 runMutex 重入解动核心循环最精密并发面，风险/收益严重不对称（W69 拆 ToolExecutionGuard 前因）。**重启前提 = 出现估算失准事故（压缩时机明显失准的实证）**。代码侧两处 KDoc 自陈（`AgentRunner:799-800` / `RunTokenLedger:64-66`）已同步补裁结论句（只加句、不改原自陈事实） | W73 |
| W73-02 | **NPU 后端定性**（M4 多模态 NPU 段）：samplerConfig=null 约束在案、现役唯一真机（OPPO A13）无 NPU 能力 ⇒ 设备侧验证**不可能** | parked | **重启前提 = NPU 能力设备到位**；设备侧注记「⛔ 无设备不可验证」落 docs/10 §11.8（停止隐性欠账漂流，「申报未入簿 = 下波静默丢失」W71 教训） | W73 |
| W73-03 | **音频能力位诚实化**（W73 条件产品件）：若音频 section dd 微取证（R3）坐实 E2B-it/E4B-it 容器无 AUDIO section ⇒ `ModelHeuristics` GEMMA_3N `audio=true` 属虚高（模型卡推断、非容器实证）⇒ 订正 `audio=false` + `ModelHeuristicsTest` 断言语境更新 + preset note 记 section 证据。**定性 = 事实订正，非产品裁决**（虚高位 = 向用户展示做不到的开关） | closed-done | **销账判据（R3 已行使）**：R3 音频 section 微取证：E2B-it/E4B-it 容器 head 扫描 AUDIO section 在档（同窗 VISION_ENCODER 可见 = 窗口有效性），audio=true 启发式虚高排除 ⇒ 条件产品件不触发，产品件 = 无。原始件 `_w73_dev/e2b_scan.txt` / `_w73_dev/e4b_head.bin`（section 名 `tf_lite_audio_adapter` / `tf_lite_audio_encoder_hw` / `tf_lite_end_of_audio`）；注意口径：section 在档只证**能力位展示**，端到端音频输入仍未验（禁推行为承诺） | W73 |
| W73-04 | **P0 事故：datastore 测试夹具写坏真机（用户报告「同意用户协议后即应用闪退」）** | closed-done | **销账判据（两 commit + 三重验证）**：`3ccb3ad`（`validate_preferences_blob` 整文件结构闸 + `_apply` 写前闸 + 夹具补外层包裹 + P0 回归四例）+ `6727b7e`（⑪ 绿面补 mock `_apply` + 索引测试补 mock + run_adb 绊线）。① 单测 20/20；② 变异自证（掏空校验器 ⇒ ⑭/⑮ 判红）；③ 跑测试前后设备 datastore mtime/内容一致（绊线后零设备接触）；设备经 restore 还原 927B 逐字节一致、App 健康。**根因**：测试 ⑪ 绿面漏 mock `_apply` ⇒ 带设备跑测试把夹具（`make_string_blob(b"LIGHT!")`）推进真机 datastore ⇒ DataStore CorruptionException 崩溃循环（App 为受害者：DarkMode 枚举无「LIGHT!」）。**方法论增量**：测试套件离线契约必须在唯一收口（run_adb）机械化；adb input text 的设备侧 shell 会把 `;` 当命令分隔符（题面截断，单引号包裹可解） | W73 |

| W74-01 | **K9–K11 真机未行使批**（W74 内核定版批的可挂项）：① 振荡 A/B 真机诱导（W73 四形态未成功，离线测试为权威判据）；② ask_actor critic/summarizer 全链；③ 批量多工具调用的可归因验证（K5 实测模型 8 轮不收敛 ⇒ 多工具结果无法归因到内核面） | parked | **重启前提**：① 需更强模型或多轮续航诱导脚本（同会话驱动 `_ci-tools/_w74_run_multi.sh` 已可复用）；② 需模型能稳定调 planner 后继续调 critic；③ 需模型能收敛（Qwen2.5-1.5B 在单工具上即 8 轮重复）。**禁凭「未行使」写 ✅**（W73 先例） | W74 |
| W74-02 | **`set-string-key` 值域零校验 ⇒ 工具 ✅ 假绿**（真机实证）：`dark_mode DARK!!`（6B 等长）被接受、真写入、exit 0，而 App `runCatching { DarkMode.valueOf(...) }` **静默回退 SYSTEM**（`SettingsRepository.kt:116`） | active | **销账判据**：① 工具侧为每个白名单键加**值域集**（`dark_mode`={SYSTEM,LIGHT,DARK} / `haptic_level` / `capability_mode` / `disclosure_mode` 各自枚举），越域 exit 2；② 单测覆盖「等长但越域」拒绝；③ 真机行使一次越域拒绝 + 一次合法写入后 App 读回值 = 写入值（不是回退值）。**根因表述**：工具只保证字节等长，不保证语义合法 | W74 |
| W74-03 | **datastore 工具 P1 六件**（W74 审查席 F1/F2/F3/F4/F18 + F5 文档面）：① 零差异路径 `exit 0` + ⚠️ ⇒ 脚本按退出码判绿假绿（应独立退出码）；② 写前不 force-stop 目标应用 ⇒ 内存态 DataStore 可覆盖写入（本波已用「先停后写」规避）；③ 回滚失败提示打印的手工 `adb push` 命令会推**未校验**文件（应打印 `restore --snap` 而非裸 push）；④ `validate_preferences_blob` 放行 0 字节 ⇒ `restore` 空快照 = 静默清空用户全部设置；⑤ **F18：force-stop 后工具缓存的旧快照须重读**（否则 `set-active-model` 可能拿陈旧基线与设备现状比对）；F5 = 工具内 KDoc/交接把「读路径也会崩」的因果写错（实为读路径被 catch 成 empty prefs，写路径未捕获才崩循环） | parked | **重启前提** = 下次改 `datastore_tool.py` 时同批修（本波负面清单：审查波不改源码）。改后必须 `python scripts/tests/test_datastore_tool.py`（现 22/22）并保持 `@Test` 严格计数与 `build.yml:502 baseline` 同步 | W74 |
| W74-04 | **仓外驱动脚本卫生**：`_ci-tools/_w73_run.sh` 头部引用不存在的 `_w73_set_model.sh`（R11）；新驱动 `_w74_run_multi.sh` 的 `wait_settled` 用「journal 目录数增加」判 settled ⇒ **同会话第二轮不新增目录**，必超时误报（Q4 实测 240s 假超时，实际已 settled） | parked | **重启前提** = 下次真机多轮批；修法：settled 判据改为「同目录下新增 `run_*.jsonl` 且末行 kind=settled」，并按文件数而非目录数比对 | W74 |
| W74-05 | **`fulltest.sh` 假绿风险**：gradle 全 `UP-TO-DATE` 时沿用 `build/test-results/**` 旧 XML ⇒ 汇总可报 `tests=740 failures=0` 假绿（方案席 R15） | active | **销账判据**：① 运行前清 `*/build/test-results/**` 或 `--rerun-tasks`；② 汇总前断言「最早 XML mtime ≥ 本次启动时刻」，否则判红；③ 变异自证：手工塞一个旧 XML 且本次不跑，脚本必须判红。**本波实测未被污染**（12 tasks executed + XML mtime 21:29:56–21:30:11 落在运行窗口内） | W74 |
| W77-01 | **FGS 类型合规的 A14+ 真机补测**：本机 Android 13（SDK 33）平台不校验 `foregroundServiceType` ⇒ 「`specialUse` 在 targetSdk 36 + 平台 34+ 是否抛 `SecurityException`」本波**无法取证**，只有静态守卫 #43 兜底 | parked | **重启前提** = 手上有 A14+ 真机（或模拟器 API 34/35）。销账判据：真机 `startForeground` 无异常 + `dumpsys activity services` 的 `isForeground=true` + 无 `SecurityException` 日志 | W77 |
| W77-02 | **FGS 通知「停止」action**：后台跑着时用户只能点通知回 App 再停；加 action 需新增 `BroadcastReceiver`（新组件面 + 守卫） | parked | **重启前提（W78 D7 改写）** = **进程级取消通道设计过评审**。D7 裁决：`AgentRunner` 没有公开 cancel 面（run 挂 `viewModelScope`），服务侧只能 `stopSelf` —— **停服务不停 run 比不做更糟**（run 继续跑但没了 FGS 与 WakeLock ⇒ 静默被冻结，用户以为已停）。评审必须回答「取消信号如何穿透到 run」，否则本项不启动 | W78 |
| W77-03 | **OEM 深链**（`com.coloros.safecenter` / `com.miui.securitycenter` 等）：本波明确不做，只跳系统页 + 给准确路径 | parked | **重启前提** = 出现某厂商「系统页也找不到入口」的实测反馈；届时按厂商单独验深链可用性，失败必须落应用详情页 | W77 |
| W77-04 | ~~**ColorOS 真实白名单臂未验**~~ **已销账（条件销账，判据已修订）**：原判据「锁存活 ≥2 分钟」⇒ 实跑发现本机 run 本身只有 30–63s（prefill ~30s + 生成秒级），「≥2min」字面线物理不可达。修订判据为「灭屏窗口 ≥3 倍对照回收阈值（≥30s）× ≥3 轮复现，`LiquidAgent::Run` 零回收、run 正常结算」。2026-10-20 23:26–23:56 四轮实测（用户已手动开启 ColorOS「耗电行为控制 → 允许完全后台行为」）：23:26 臂 Say hello 40s｜23:34 臂 Count… 48s｜23:38 臂 Tell me a long story… 40s｜23:56 臂 Write a very detailed guide… 40s —— **四轮锁全程 held=true、`OplusProxyWakeLock` 对本应用 0 条 force-release、run 全部「正常结束」**；对照臂（未开耗电控制，W77 首测）灭屏 ~10s 即被回收、TTFT 200.9s。⛔ **2026-10-22 本项销账被 W78-08 推翻（判据方法失效，非结论微调）**：W77 判 `held=true` 用的是「`dumpsys power` 段位检测」（Wake Locks / Restored Wake Locks 段里是否出现 `LiquidAgent::Run`），而 **ColorOS 的托管段条目在锁释放后不清理** —— 实证 `08:26:29.804 REL` 之后该条目仍留在 `Restored Wake Locks` 段内。⇒ W77 的「四轮锁全程 held」是**陈旧条目造成的假阳性**；同轮「0 条 force-release」亦不成立（改用 ACQ/REL 历史后可见 `add … 'Liqui' LONG … to FroceReleaseWakeLock list`）。**权威锁态判据 = `dumpsys power` 的 Wake Lock History 区 ACQ/REL 事件对**（最后一条为 ACQ 且无后续 REL ⇒ 持有），段位只能作参考 | reopened | **重启前提** = 用 ACQ/REL 历史为判据重跑 W77 的四臂场景（Say hello / Count… / Tell me a long story… / Write a very detailed guide…），逐臂记录：① 冻结事件（`freeze uid: 10310` / `SM_EVENT_LCD_OFF_UNIFIED_FREEZE`）② 锁 ACQ/REL 时刻 ③ run 结算结果。销账判据：**白名单开启臂的锁在 run 全程无 REL**（不是段位里看得见），或明确证实「白名单无效，只能靠冻结期外的间歇窗口」并据此修订 `guideText` 与设置卡口径。**禁止再用段位检测判 held** | W78 |
| W78-01 | **9 族 OEM 引导的真机取证**：`OemBackgroundPolicy.EVIDENCE` 现为 1 档 MEASURED（COLOR_OS）+ 7 档 OFFICIAL + 1 档 COMMUNITY（FLYME），**8 族未在本机实测** | parked | **重启前提** = 手上有对应厂商真机（或可信社区实测反馈可复现）。销账判据：该族 `evidence` 升为 `MEASURED` 且锚点含本仓 `W##` 波次号（守卫 **#46** 强制锚点形态，无锚点的 MEASURED 直接判红）；同时更新 `guideText` 路径为实测路径 | W78 |
| W78-02 | **FLYME「最近任务下拉加锁」待证**：魅族无官方后台管控文档（现为 COMMUNITY 档），本波文案**刻意不写加锁**（其余 5 族写「最近任务下拉加锁」）—— 写了就是给用户一条不存在的路径 | parked | **重启前提** = 有 Flyme 真机（或官方支持页出现加锁说明）。销账判据：实测确认加锁**存在/不存在**并把结论写进 `FLYME` 的 `path`，档位随之升 `MEASURED`（带 `W##` 锚点）；若确认不存在，把「本机无加锁」写进锚点备注 | W78 |
| W78-03 | **OriginOS「应用冻结」独立层**：vivo 的「应用冻结」与「后台高耗电」是两套开关，现文案只覆盖后者 —— 冻结层未取证，可能与 W77 ColorOS 的 `OplusProxyWakeLock` 同型 | parked | **重启前提** = vivo/iQOO 真机 + 可复现的灭屏冻结（判据：灭屏 ≥30s 后 run 被挂起且 `dumpsys` 无本应用 wakelock 回收记录）。销账判据：定位到具体开关 → 补进 `ORIGIN_OS` 路径 → 升 `MEASURED` | W78 |
| W78-04 | **小米 PowerKeeper intent 探针**：`com.miui.powerkeeper` 的深链 intent 可用性**未验**（W77-03 明确不做深链，只跳系统页）；MIUI/HyperOS 的「省电策略」页是否可由 intent 直达未知 | parked | **重启前提** = 出现「系统电池优化页也找不到入口」的实测反馈（与 W77-03 联动）。销账判据：探针在真机上 `resolveActivity` 非空 + 跳转成功；失败必须回落应用详情页（不得静默无响应） | W78 |
| W78-05 | **D9 WakeLock 周期续期的真机复验（V8）**：续期逻辑（120s 周期 / 390s 超时 / 30min 上限）目前只有 JVM 单测（`RunForegroundPolicyTest` 三例）与静态推演，**OEM 侧被回收后能否靠续期补回**未真机取证；W77-04 的遗留「≥2min 长 run」也归到这里。**2026-10-22 部分取证完成（结论 = 机制正常、但被冻结结构性击败）**：① **机制本身已实证生效** —— `08:37:32.812 WakeLock 已获取` → `08:39:32.823 续期（已持 120006ms）`，周期精确命中 120s、无条件 re-acquire 有效、`held=true`；② **但在 ColorOS 上补不回**：续期后 **2.7s** 锁即被强制回收（`08:39:35.566 add … 'Liqui' LONG … to FroceReleaseWakeLock list`），且**冻结期间协程根本不执行** ⇒ 下一次续期迟到 420s（等到 `am start` 解冻才补发）。⇒ 续期的正确定位是「**冻结窗口外的 CPU 保持 + 解冻后立即补锁**」，不是「对抗 OEM 冻结」；③ 解冻后续期立即重新取到锁并持续 HELD（`08:46:32.126 ACQ` 后无 REL），证明循环在进程可运行时可靠 | parked | **重启前提** = 有 **≥2min 长 run** 场景（更长输出 / 更重 prefill）+ ColorOS 或 A14+ 真机，**且判据必须用 ACQ/REL 历史（禁段位检测，见 W78-08）**。销账判据：① 灭屏全程 ≥2min；② 续期日志 ≤15 行（1800/120 = 15，不冲掉 `AgentLogStore` 200 条环形缓冲 —— 已由 `wakeLockRenewalLogVolumeIsBounded` 单测钉 `MAX_HOLD/INTERVAL ≤ 20`）；③ **如实记录锁在每个时间点的 ACQ/REL 状态**（允许出现「冻结段 REL + 解冻段补 ACQ」的间歇形态，不得因存在 REL 就判失败，也不得用段位把 REL 美化成 held）；④ run 最终结算。**豁免说明**：若本轮处于 ColorOS 冻结窗口内，销账判据 ③ 按「间歇窗口」口径评估，并同步 W78-08 | W78 |
| W78-06 | **`RunWakeLockKeeper` 补 JVM 单测**：keeper 已可从 `null` PowerManager 构造（不触碰 `Context`）⇒ 可纯 JVM 断言 —— 空 PM 下 `acquire` 不崩 / `isHeld` 恒 false / `startRenewal` 与 `release` 幂等（重复调用无副作用）。本波**刻意不做**：动 `@Test` 即动 `build.yml` baseline 与守卫 #26 双向同步，属另一条变更链 | parked | **重启前提** = 下一波改 keeper 时同批补（不许只改实现不补测试）。销账判据：新增 `RunWakeLockKeeperTest` ≥3 例且全绿，`baseline` 同步上调并由守卫 #26 复核 | W78 |
| W78-07 | **守卫 #47（候选，本波不加）**：`*Service.kt` 的**字段初始化期**不得调用 `getSystemService`。动因 = W78 真机 P0：C5 一度把 keeper 写成字段初始化，Service 构造期 `attachBaseContext()` 尚未调用 ⇒ NPE ⇒ `Unable to create service` 打死进程，而 **assemble / 单测 / lint 三层全绿**（静默失效面，只有真机冷启才看得见）。判据形态：`^[[:space:]]*(private|internal|protected)[[:space:]]+(val|var)[[:space:]].*getSystemService\(`，扫描面 `core-data/src/main/java/com/rickeal/agent/core/data/background/*Service.kt`；**反向断言**：`onCreate` 内 `= RunWakeLockKeeper(getSystemService(` 合法、不得判红 ⇒ 正则必须带 `(val|var)`（拿 PM 的方法调用形态天然不命中） | parked | **重启前提** = 落地时必须成对配 case81（字段形态必红）+ **case81b（`onCreate` 形态必绿 —— 81b 比 81 更重要，反向断言缺失会让守卫误红合法代码）**；selftest 下一个空闲 case 号 = **81**。销账判据：#47 在 `# 47)` 形态落地 + `KNOWN_GUARDS`/映射表同步 + case81/81b 均 PASS + case53 元判据复跑 | W78 |
| W78-08 | **🔴 ColorOS 灭屏进程冻结 = 保活的真实硬上限（推翻 W77-04 销账）**：2026-10-22 四轮受控实测（设备 13309cc8 / ColorOS / Android 13，应用侧「允许完全后台行为」**已开启**）证明 —— 灭屏后 **~10–11s** ColorOS 走 `OplusHansManager` 状态机触发 **cgroup 级进程冻结**：`SM_EVENT_LCD_OFF_UNIFIED_FREEZE to uids=[10310]` → `F enter(), M stay=3` → `freeze uid: 10310 … scene: \|airPlane\|LcdOff` → netlink `FROZEN_TRANS` 到内核；**同一毫秒** `OplusProxyWakeLock` 把本应用 wakelock（`'Liqui' LONG`）放进 `FroceReleaseWakeLock` 名单。**冻结不受以下任何因素影响（已逐一控制变量）**：① FGS（`isForeground=true` / `foregroundId=1002` / `specialUse` + `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`）；② WakeLock（390s 超时 + 120s 周期续期，见 W78-05）；③ AOSP 电池优化豁免；④ ColorOS「允许完全后台行为」（截图证实为 ON）；⑤ 进程新旧（`am force-stop` 后冷启新 pid 复测，结论不变）；⑥ 飞行模式开关（开/关各测一轮，结论不变）。**冻结期间进程不执行 ⇒ 续期协程无法运行**（实证：续期待续期 120006ms 准时触发后，锁在 2.7s 内被回收；下一次续期迟到 420s，直到我用 `am start` 唤起 Activity 解冻才补发） | active | **重启前提 / 后续动作** = ① 把「OEM 侧真实上限」写进设置卡与交接：本仓 FGS + WakeLock + 续期是**必要不充分**条件，ColorOS 上真正的门是进程冻结；② 续期的价值定位改为「冻结窗口外的 CPU 保持 + 解冻后立即补锁」，不再宣称能对抗 OEM 冻结（W78-05 销账判据随之修订）；③ 若要继续压这条线，方向是「降低被冻概率」而非「冻结后续命」——候选：FGS 类型选型（`specialUse` 是否被 ColorOS 降权待验）、`allowlist` 之外的 ColorOS 专有豁免入口、或把长任务拆成「灭屏可暂停 + 亮屏恢复」的契约。**禁把「已开白名单」当成「不会冻结」的证据**（W77-04 已因此假绿一次） | W78 |
| W78-09 | **ColorOS 三开关组合豁免冻结 —— 已证伪**：原开放问题（「允许唤醒前台」「允许应用自启动」「允许应用关联启动」是否与「允许完全后台行为」构成组合条件、组合后能否豁免灭屏冻结）。**w79 真机实测结论 = 即使四开关全开仍被 cgroup 冻结**：日志三次 `freeze uid: 10310 … scene: \|airPlane\|LcdOff`（时间戳约 +33330 / +33420 / +33831s），与 W78-08 完全一致；四开关全开（含原本 OFF 的三项，经人机交互点动为 ON）未改变「灭屏 ~10s 冻结」结论 ⇒ **组合不构成豁免**，与 W78-08 的「OEM 侧真实上限」结论同向，二者合并为同一判据。原担忧「adb 点不动三开关故未证」已通过改用非 adb 交互消除 | closed-done | **销账判据（w79 已满足）**：① 四开关全开（截图/日志证实状态 ON）；② 灭屏 ≥30s 内出现 ≥1 次 `freeze uid: 10310`（scene 含 `LcdOff`）；③ 结论已并入 W78-08 的「OEM 侧真实上限」叙述（设置卡/guideText 不再把任一项写成豁免前提）。**重启前提** = 出现可点动三开关的人机交互实测新证据推翻本结论（当前为 w79 三 freeze 实证） | W78 |
| W78-10 | ~~W77-04 四轮「正常结算」与本次「冻结 6m56s」如何并存~~ **已答（2026-10-22 复测）**：两者不矛盾 —— **冻结不杀 run，只把 TTFT 拉长几十倍；W77 的判据是「journal 有 settled 记录」（最终结算），没有记 TTFT，所以慢几十倍也显示为「正常结束」**。复测证据（`_ci-tools/_w78_repro.sh`，四臂 = W77 原场景，每臂 force-stop 冷启 + 输入回读校验）：① **四臂全部触发冻结**（灭屏 +10s，无一例外：A1 pid10810 10:36:54 / A2 pid11679 10:54:44 / A3 pid15714 10:58:02 / A4 pid22800 11:01:07）；② **续期与解冻同一毫秒触发**（A2 差 4ms / A3 差 9ms / A4 差 8ms，且 `已持` 156–163s > 预期 120s，超出部分正是冻结时长）⇒ 从时间维度证明「`delay(120s)` 在冻结期间不推进，解冻后立即补发」；③ **同题面 TTFT 差 47 倍**：`run_1792612359109`「Count from one to twenty slowly」TTFT=9818ms vs `run_1792612709845` 同题面 TTFT=462444ms，差异只在于是否落在冻结窗口内 | closed | **销账判据已满足**（三项证据齐）。连带结论：W77-04 四态判为 **⚠️**（原结论「路径引导有效」可能仍成立，因 run 最终都结算；但「锁全程 held、0 条 force-release」两条**明确为假** —— 段位检测假绿）。**方法论沉淀**：凡以「最终结算 / 最终成功」为判据的验证，都必须同时记录**时延**（TTFT / 总时长），否则「慢 47 倍」与「正常」不可区分 | W78 |
| W78-11 | **设置卡尾注点名「锁屏后数秒内冻结本进程」机制**：原尾注只覆盖「电池优化豁免 ≠ OEM 后台管控」，未覆盖「灭屏后被系统冻结」这一层，而 W78-08 证明这才是真上限（用户开了「允许完全后台行为」仍被冻，体验落差真实） | closed | **已实现（`26195e4`）**。尾注由「……切后台后仍可能被冻结或回收，部分机型还会在灭屏后限制 CPU。中断后可从恢复卡继续。」改为「……切后台后仍可能被冻结或回收；**部分机型会在锁屏后数秒内冻结本进程，此时生成会暂停、解锁后自动继续。**中断后可从恢复卡继续。」（净增 17 字符，仍是单字符串字面量，样式与位置未动）。**四条措辞纪律**：① 只说「部分机型」不点厂商（W78-09 未证遍）；② 不带具体秒数承诺；③ 「暂停 / 解锁后自动继续」是 W78-10 实测的**行为描述**非承诺；④ 不写「必然 / 一定 / 保证」。**依据不依赖 W78-09/10**（依据是 R15 实测机制），故不必等开放问题 | W78 |
| W78-12 | **冻结感知生成超时（freeze-aware generation timeout）—— 防锁屏/灭屏/首字延迟期间生成被单调钟超时误掐（"断线"）**：根因 = 原 `withTimeout(generationTimeoutMillis)` 的截止由 OS 单调钟决定，ColorOS 灭屏 ~10s 后 cgroup 冻结期间单调钟照走，长锁屏解冻瞬间「墙钟流逝 > 预算」误取消 ⇒ run 被掐 = 用户感知「思考 / 首字延迟时锁屏断线」。本项用 `FreezeClock` 接口 + `ScreenStateMonitor`（累计灭屏时长，过近似为冻结）+ `freezeAwareTimeout` 看门狗（只统计可运行时长）替代 `withTimeout`，使超时预算剔除冻结时段；首字延迟（`generateStream` 内）一并覆盖 | closed-done | **销账判据（已满足）**：① 单测 `FreezeAwareTimeoutTest` 3 例全绿（单调钟超预算如期超时 / 预算内正常完成 / 冻结视角恒 0 不超时）—— 证明超时剔除冻结时段、首字延迟与思考阶段锁屏存活；② JVM + Android 双编译绿（`core-agent:testDebugUnitTest` 全量 + `:app:assembleDebug`）；③ 机制落位：`core-agent` 平台无关 `FreezeClock` 接口 + `AgentRunner.freezeAwareTimeout`（看门狗 `scopeJob.cancel(GenerationTimeoutCancelledException)` 复用原「超时→重建引擎+重试一次」链路），`core-data` `ScreenStateMonitor`（承接 `FreezeClock`，进程级单例不注销，与 `RunWakeLockKeeper`/`ThermalGovernor` 同口径）累计灭屏时长，`AppContainer` 注入 `screenStateMonitor` 为 `freezeClock`；④ 与 W78-08 关系锚定：冻结本身不可避免（W78-08 硬上限），本项**不防冻结**，而是「冻结期间不消耗超时预算 ⇒ 解冻后生成继续、不误掐」—— 即用户要的「思考 / 首字延迟时锁屏不断线」= run 存活 + 解冻续跑。**重启前提** = 真机复验（OPPO 灭屏长生成）仍出现锁屏断线（真机复验列为**建议后续、非阻断**，因 freeze 不可避免，本项只保「解冻后不误掐」） | W78 |

| W79-01 | **P1：冻结时钟跨 run 污染修复**（复审 34 唯一 P1）。旧 `runnableElapsedNanos(started)` = `now-start-进程级accum`，而 accum 含 start **之前**的灭屏 ⇒ 历史冻结从本轮预算扣除（416s 历史 ⇒ 本轮实际可跑 716s，断路器随使用单调退化）。修法（复审 34 终裁方向 1）：`FreezeClock` 拆成 `frozenNanos()`（绝对累计，**含在途灭屏窗口**）+ `runnableNanosSince(started, baseline)`；`freezeAwareTimeout` 起点同取 `frozenBaselineNanos`；累加逻辑上提 `core-agent.FreezeAccumulator`（offAt/accum 同锁，重复 off 保留最早起点）；删单参入口堵复发通道 | closed-done | **销账判据（已满足）**：① `FreezeAwareTimeoutTest` **6 例**全绿（含红转绿锚「历史累计 416s 不占本轮预算」+ 中途冻结顺延 + 顺延非无限豁免）；② 新建 `FreezeAccumulatorTest` **3 例**全绿（窗口精确累计一次 / 基线落在窗口内排除更早段 / 持续灭屏严格递增）；③ 代码位 `@Test` **773** = `build.yml` baseline；④ `:core-agent:testDebugUnitTest` + `:app:assembleDebug` + `:app:lintDebug`（0 新增）+ `arch-guard` 46/46 全绿。**残留**：比较式为 `>=`，无边界用例 —— 理由 = `>` 仅延后 ≤1 tick（250ms），补例需冻结时钟钉比较符 = 过度规格化（设计 §1.6）。**最后触碰**：W79 | W79 |
| W79-02 | **issue #1326 维护者回复处置**：上游确认 GPU 容器（E2B/E4B-GPU）**只含 `tf_lite_artisan_text_decoder` 段**；`NOT_FOUND (TF_LITE_PREFILL_DECODE not found)` 在 v0.17.1 是「请求 section 大小为 0」的查表失败（`model_resources_litert_lm.cc:124-126`），**不等于 GPU 缺算子**；未解问题 = 为何调用路径请求 prefill/decode 而非 Artisan 路由；明确否决「通用 NOT_FOUND ⇒ GPU 不兼容」helper。本仓处置 = **只做终态失败现场留档**（`LiteRtLmEngineLoader` 终态 `warn`：attempt / backend / vision / audio / degraded / contextLength / container+size / message），**不升依赖、不改 knownBroken、不搬上游代码**；本仓降级链本就会走到「纯文本 + GPU」（`MAX_LOAD_ATTEMPTS=4`），故不再加自动降级 | closed-done | **销账判据（已满足）**：留档日志落地（+13 行，loader 704→717 ≤900）；`knownBrokenOn` 原文未动（守卫 #34 绿）；无新增以 `NOT_FOUND` 为触发的行为分支。**重启前提** = 拿到 E2B/E4B-GPU 真机 + 0.17.1/0.18.0 对照，才可能判定 Artisan 路由可达性 | W79 |
| W79-03 | **UI 圆角收敛 + 卡片间距**（用户诉求：圆角稍收敛、卡片之间距离拉大）。`GlassTokens` 圆角 7/12/16/24/30（≈−15%）+ `GlassDefaults.RadiusLg` 28→24 + `GlassCard` 默认值由硬编码 `28.dp` 改为引用 `GlassDefaults.RadiusLg`（30 余处未传参调用点的真正杠杆）+ `LiquidShapes` 同步（extraSmall 7 / large 24）；新增 `cardGap=14.dp` 并在 11 处卡片↔卡片列表替换（**不动 `gapSm`**：其 34 个消费点中 31 个是卡内 Spacer/padding） | closed-done | **销账判据（已满足）**：① `:app:assembleDebug` + `:app:lintDebug` 0 新增；② 真机（OPPO PDRM00）冷启无崩溃 + 设置页/模型页改前改后截图对比（圆角 28→24、卡片间距 10/12→14 可见）；③ dp 数值无单测断言（grep 实测 0 命中）⇒ 无测试需改。**不做**：`LiquidShapes.extraLarge 48`（零消费者） | W79 |
| W79-04 | **复审 34 P3 批（P3-1~P3-4、P3-6）**：① handoff 门禁基线「服务 212」→ **221**；② handoff §四 W78-09 旧「开放未证」→ closed-done（与 §七一致）；③ `w79`/`W79` 大小写指代定义入台账；④ handoff 头补「W78-12 不在 w78-bg-run-design.md 内」边界；⑤ `AgentRunner` 竞态注释从 `withTimeout` 改为 `freezeAwareTimeout` 现状。**P3-5（keeper scope 复活）本波回滚不修**，见 W79-08 | closed-done | **销账判据（已满足）**：五项逐条落地 + `arch-guard` 46/46。**P3-5 口径**：本波一度删 `serviceScope.cancel()`，审查席判「空转」+ 开发席从调用点结构实证「`release()` 仅两处，均在 `stopSelf`/`onDestroy` 终态 ⇒ 不存在 release 后同实例再 onStartCommand」⇒ 零收益且会作废 `w78-bg-run-design.md:499` 的已归档修法（scope 重建）⇒ **整体回滚到 110 行**，只登记不修 | W79 |
| W79-05 | **借道竞态窗 1001 残挂**（复审 34 P3-7）：FGS attach 前已发出的 1001 速度通知在借道期间无撤销点；tick 落在 [run 起, attach) 窗（冷启秒级）时，冻结文案 1001 与实时 1002 并挂整个 run。低概率、终态自愈、纯外观 | parked | **重启前提** = 出现「两条常驻通知」的真机实测反馈，或下次改 `OngoingNotificationSlot`/notifier 时顺手补撤销点 | W79 |
| W79-06 | **守卫 #45 KDoc 行数陈旧 + 包枚举非递归**：KDoc 写「899/101」而实测 **977/23**；且 `background/*.kt` 只扫一层（当前无子目录，新增子目录会漏算） | parked | **重启前提** = 下一次动 `background/` 包，或下一次跑全量 selftest 时顺手改（改守卫注释须跑全量 selftest ~52min 证明元 case 不破，本波不付这个成本） | W79 |
| W79-07 | **`background/` 包拆分规划**：包已到 **973/1000（余 27）**，下波加任何 OEM/冻结策略第一屏就触顶（守卫 #45 语义 = 触顶启动拆分评审，非改阈值） | active | **重启前提** = 下一波需要在 `background/` 新增 ≥27 行时先拆（候选：`OemBackgroundPolicy` 的 9 族文案表抽成独立数据对象） | W79 |
| W79-08 | **keeper 续期复活（复审 34 P3-5）—— 登记不修**：`release()` 会 `serviceScope.cancel()`；若同实例之后再次前台化，`acquire()` 能补锁但 `startRenewal()` 起不来 ⇒ 续期静默失效。**主判据（不依赖可达性）**：全仓 `startRenewal()` 唯一调用点在 `AgentForegroundService.onCreate`（`:85`），而 **`onCreate` 必然早于任何 `release()`** ⇒ 不存在「release 之后是否会再续期」这个讨论面。故删 `serviceScope.cancel()` 的收益恒为零；唯一效果是把 `w78-bg-run-design.md:499` 的规范修法变成永不执行的死代码。**判据边界**：本条前提 = **无第二调用点**；若将来在 `onStartCommand` 或别处新增 `startRenewal()` 调用，本条**立即失效需重评**（届时 scope 死活才有意义，须按 `:499` 的顺序规格施工）。**辅证（仅辅证）**：`release()` 全仓仅两处（`:104` catch 降级 / `:120` onDestroy），均在终止路径；`stopSelf(startId)` 语义 + 控制器 `started` 双重条件 ⇒ 可达但极窄 | parked | **重启前提** = 出现「release 后同实例复用」的真实路径（如新增 `stopForeground` 不 `stopSelf` 的复用形态），或下一次动 keeper（W78-06 要求同批补 JVM 单测）。**销账判据**：按 `:499` 的规范修法落地 + 补测试证明续期真的复活（不是「看起来修了」） | W79 |
| W80-01 | **测量基建**（三份外审一致建议的先行件）：① `MainActivity` 冷启 `reportFullyDrawn` 锚（`LaunchedEffect(Unit)` + runCatching，供 `am start -W` 与 logcat `Fully drawn` 读取）；② 工作区工具 `_ci-tools/gfxinfo-capture.sh` 三场景采集脚本（coldstart/scroll/generation，不入仓）。**真机关键取证**：冷启锚实证生效（`Fully drawn +671ms` 与 TotalTime 670 重合 = 首帧即锚）；**`dumpsys gfxinfo` 在 PDRM00/ColorOS 上帧统计路径设备级不可用**（Total frames 恒 0：pid 匹配活进程、UI 正常渲染、有无 reset 均 0 帧）⇒ scroll/generation 场景判 ⛔ vacuous，帧级统计唯一可行路径转程序化采集（W80-07） | closed-done | **销账判据（已满足）**：冷启锚真机实证 + 脚本 coldstart 场景产出基线（W79 构建 TotalTime 459/508ms）；`:app:compileDebugKotlin` 绿；无新增依赖。**残留**：scroll/generation 场景数据依赖 W80-07 | W80 |
| W80-02 | **P1-render 四件套**（第十九审 §5 行号级实证：流式期间 `ChatMessageList` body 顶层 collect + core-model 无稳定性注解 ⇒ 每 120ms flush 全可见历史气泡陪跑重组 ~8Hz，与推理争抢 SoC）：a) Compose 编译器稳定性报告（`composeCompiler{} DSL`，CLI `-P` 在 Kotlin 2.3.0 静默失效——报告留存工作区 `_w80_compose_stability_report/`，`MessageRow(message)` unstable→stable 两态实证）；b) `feature-chat/stability.conf`（仅声明报告背书的全 val 类：ChatMessage/Attachment sealed 基类+四子类/ToolCall/ToolResult/TokenUsage，**不改 core-model 源码**）；c) 流式收集收窄（顶层只留 `map{it.isStreaming}.distinctUntilChanged()` 低频订阅，文本订阅下沉到 streaming item 内部，滚动跟随 `snapshotFlow{text.length}` 改 `streamingFlow.map{...}.collectLatest` 语义等价）；d) 行内 remember（attachments.map/associateBy/splitThinkAndClean，key 全覆盖） | closed-done | **销账判据（已满足）**：`:feature-chat:compileDebugKotlin` + lintDebug 0 新增；ChatMessageList 490→507（自守 600 内）；真机流式生成端到端正常（气泡/滚动跟随/thinking 无回归）；无新增 @Test。**红线核验**：不触 ChatRunCoordinator（1253/1300）、不改 core-model | W80 |
| W80-03 | **会话读缓存 Phase A**（上审 P0-1 域内裁剪）：`ConversationCache`（纯 JVM，`LinkedHashMap(accessOrder=true)` + `@Synchronized`，LRU 上限 8 可注入）+ Repository 接线（load 命中短路 / save 写穿回填 / delete 锁内 invalidate）。**appendMessage 热路径 2 次全量读 → 稳态 0 次**（exists 一次 stat + 缓存命中）。**审查席 P1（TOCTOU 丢消息）+ P2（瞬时 IO 被缓存成永久熔断）合并修法（主理人裁定）**：load 未命中一律不回填（缓存唯一写方 = save 落盘成功后写穿）+ parseFailed 二态整体删除 + appendMessage 探测/读取合并（`exists → load ?: 拒写本次`，损坏保护语义与 HEAD 等价、瞬时失败只损失一次写）。**写去抖 Phase B 被方案席否决**（见 W80-06） | closed-done | **销账判据（已满足）**：`ConversationCacheTest` 10 例 + core-data 全量 121 绿；真机端到端：流式生成落盘（faa10949 会话 USER+MODEL 全文写齐）+ 杀进程重启后抽屉/会话页消息完整渲染；`writableOrCorrupted`/`putParseFailed` 全仓无残留引用。**一致性边界成文**：进程内单例 + save 唯一写方 + load 绝不回填 | W80 |
| W80-04 | **沙箱扫描装饰-排序-去装饰**（性能审查 Q-4）：`SandboxFileScanner.scan` 条目过滤后一次遍历缓存 name/mtime/size/childCount（每条目 stat 恰一次），排序用缓存值（原 `sortedWith` selector 在每次比较时调用 ⇒ O(N log N) 次 lastModified stat），map 不再二次 stat ⇒ stat 从 3N+2N·logN 降为 O(N)；逃逸剔除前移到排序前（比较子与剔除判据正交 + 剔除只删子集不改余序 + totalEntries 口径不变，方案席复核成立） | closed-done | **销账判据（已满足）**：新增 `SandboxFileScannerTest` 1 例（mtime 降序 + 名字 tiebreak + childCount/size/extension 去装饰后不丢 + 过滤断言）；core-data 121 绿；真机工作区页文件列表渲染正常（note.txt size/mtime 显示无回归）；`resolveWithinSandbox`/`isAtomicWriteTemp` 主体与 HEAD 逐字节一致 | W80 |
| W80-05 | **onScreenOn 返回 (delta,total)**（性能审查 Q-1 顺手项）：`FreezeAccumulator.onScreenOn` 返回 `Pair<Long,Long>`（锁内清零 offAt 后 total 恒等 accum = `frozenNanos()` 读数），`ScreenStateMonitor` 亮屏日志改用返回值消除第二次 `frozenNanos()` 取锁；FreezeClock.kt 105→109（无单文件闸门）；ScreenStateMonitor +1 行（background/ 包 973→974，余 26） | closed-done | **销账判据（已满足）**：`FreezeAccumulatorTest` 2 例改双断言（total == 亮屏后 frozenNanos() 读数）；core-agent 234 绿；`onScreenOn(` 全仓调用点仅 ScreenStateMonitor + 测试，均已同步 | W80 |
| W80-06 | **会话写去抖 Phase B —— 否决登记**（方案席裁定 + 主理人批准）：① journal 兜底**只对未 settled run 成立**（`findUnsettled` 判最后一行是否 settled；run 已写 settled 而会话 flush 未落盘时进程死 ⇒ 答案在 journal 但无恢复卡，UI 可见历史丢失）⇒ 去抖窗口内终态答案崩溃后无任何恢复路径；② 收益错位：每 run 仅 2 次 append（user + final，相隔整个 run 时长），去抖合并不了它们，真正热开销是读（Phase A 已解决）；③ 补「run 边界强制 flush」必须改 `ChatRunCoordinator`（commitAssistant/onStop），该文件 1253/1300 余 47 禁触；④ onCleared 不能 await（:1150-1154 已评审否决）⇒ 去抖把销毁竞态窗口从 ms 级放大到 500ms 级 | parked | **重启前提** = Phase B 单独立项设计评审通过（硬边界清单：commitAssistant 后 / onStop / create 后 / delete 前先 flush / resetForNewConversation / Activity onStop=OEM 冻结杀主入口，缺一即丢失；验收必须含 kill -9 crash-recovery 真机用例）；或 `ChatRunCoordinator` 第二拆分落地腾出余量 | W80 |
| W80-07 | **JankStats 程序化帧统计**（W80-01 取证的直接下游）：PDRM00 `dumpsys gfxinfo` 帧统计恒 0（设备级不可用）⇒ 帧级性能数据唯一可行路径 = `debugImplementation androidx.metrics:metrics-performance`（运行时库无注解处理器，不违约）+ debug 源集 JankStats 接线；W80 本波按版本锁死纪律未新增依赖 | parked | **重启前提** = 用户批准新增依赖版本（锁死纪律）；或下一性能波立项时一并批。**验收判据**：生成期/滚动期 p50/p95 帧 MOS 直方图产出（补 W80-01 的 scroll/generation 空缺） | W80 |
| W80-08 | **玻璃模糊滚动期降强度**（性能审查 Q-2）：列表滚动时降 blurRadius（`derivedStateOf` 包 `listState.isScrollInProgress`，本仓已有同款范式）；外审要求「先真机标定是否真掉帧，不凭 blur 很贵直接改」 | parked | **重启前提** = W80-07 落地后拿到滚动期帧数据标定（标定链路被 gfxinfo 不可用阻塞）；标定证伪则 closed-dead | W80 |
| W80-09 | **`SegmentedHistoryStore.listTurnsSync` 双重解析 —— 登记不修**（性能审查 Q-5）：`partition{decode}` + `mapNotNull{decode}` 每行解析 2 次属实，但该类 Wave 30 已判死（`listTurnsSync` 仅被同为死代码的 `rebuildHistorySync` 调用，零生产调用点）⇒ 修复零运行时收益 | parked | **重启前提** = Wave 3.5 启用历史重建（`rebuildHistorySync` 进热路径）时一并修（解析一次保留结果）——届时启用决策与修复同批 | W80 |

—— W80 续更：性能开销波七项落地（W80-01~05 closed-done）+ 两项否决/登记（W80-06 写去抖、W80-09 listTurnsSync）+ 两项真机链路依赖挂账（W80-07/08）；`@Test` 773→**784**（baseline 同步，见 build.yml 沿革）；`background/` 973→974（W79-07 余量再收 1，余 26）。

## 沿袭滚动（无独立 ID；随各波交接清单滚动，禁重抄整表）

`fulltest.sh` 接 CI 运行步｜F4 三级文字 token 统一｜`TokenUsage.estimated`｜bump 双轴季扫｜
上游出货 B1/B2（用户手动，docs/14）｜审查 P3-d（测试源集依赖方向成文）｜N-2 warn 聚合键保持 `N-2`｜
`.md` CRLF 编辑纪律（禁 `sed -i`）。

## M12 对账（handoff §五/§六 ↔ 本台账，W69）

方法（v21 M12）：`grep` **W64–W68 五波**交接 §五/§六 挂账项，逐条比对本台账是否已收；缺口按四要素补登。
**受理门**（H5）：外部报告先 `git cat-file -e <基线 SHA>` 验存在，不存在则整份降级「仅治理教训可迁移」
（本对账只涉仓内交接文档，无此问题）。

| 交接项（来源） | 是否已在台账 | 处置 |
|---|---|---|
| bump 首跑判据（W64–W68 各 §五 沿袭） | ✅ W68-06 | — |
| C 复测收尾 r_max（W64 §五.1 / W65 §五.2 / W66 §五.4） | ✅ 已由 W67 B1 销账（r_max=5.06，C 16→9） | 结案（非挂账，无独立 ID） |
| C1 R1-Distill-Llama 容器取证（W64 §五.2） | ✅ 已由 W65 D4 销账（容器不存在 ⇒ 外推维持） | 结案 |
| C-1/C-2 对话框手验（W64 §五.3 / W65 §五.9） | ✅ W68-04（docs/14 §A1） | — |
| K 论证件 BuildConfig 化（W64 §五.4） | ✅ 已由 W65 D5 并入 bump 前置⑤ 销独立项 | 结案 |
| evidenceLevel 全 schema / 接线（W64 §五.5 / W65 §五.6 / W66 §五.5 / W67 §五.5） | ✅ 数据层 W66 B3；接线 = W68-03 | — |
| AgentRunner 行数余量 / 接缝②（W64 §五.6 / W65 §五.8 / W66 §五.6） | ⛔ 非台账项（负面清单；预案 = `docs/13`） | 由 `docs/13` 承接，不占台账 |
| selftest 进一步提速（W64 §五.7） | ⛔ 非台账项（`--only/--affected` 已落地；分片/并行未立项） | 观察，不占台账 |
| 审查 P3-d（W64 §五.8 沿袭） | ✅ 沿袭滚动 | — |
| 模型直答腿（W65 §五.4） | ✅ W68-10（closed-dead） | — |
| 轴档案兜底收窄（W65 §五.3） | ✅ W68-06（bump 场景复核） | — |
| E4B 容器 / E4B 单文件预设化（W65 §五.7 / W66 §五.2） | ✅ 已由 W67 B4 销账（单文件入册 7.1 GiB） | 结案；残余「思考双变体复验」并入 W68-07 |
| gemma-4 GPU 用户故事（W66 §五.3 / W67 §五.6） | ✅ W68-07 | — |
| knownBroken 激活态重校验（W65 §五.5 / W66 §五.5 / W67 §五.1） | ✅ 已由 W68 B2 功能刀销账 | 结案 |
| 🟡-1 工具超时竞态 / ToolExecutionGuard（W67 §五.7） | ✅ W69-01 | — |
| F1 选项 A 真机臂（W67 §五.3） | ✅ W68-02 | — |
| docs/14 六项 + C4 + C5（W67 §五.8 / W68 §五） | ✅ W68-04（C5 已补注；C4 销账 = W69-06） | — |
| 法务 termsVersion / 0 tags / main 快照 / fulltest 接 CI / F4 / 删除 UI / TokenUsage.estimated / bump 季扫 / 上游出货（各波沿袭） | ✅ W68-05 / W68-08 / 沿袭滚动 | — |
| **D7（R1 探针 verdict）+ 2047 补丁臂**（W67 §五.2 / W68 §三.2、§五、§六.8） | ❌ **交接有、台账无** | **本波补登 W69-10** |
| **#41 镜像/续行形态覆盖**（W68 §五 已编 W69-05） | ❌ **交接有、台账无**（并入台账时漏收） | **本波补登 W69-05** |
| **#40/#41 锚对账机械化**（W68 §六.8 W69 主刀） | ❌ **交接有、台账无** | **本波补登 W69-11** |

**结论**：W66/W67/W68 三波 §五/§六 迁移完整性逐条核对，**发现 3 处缺口**（D7+2047 真机件、
#41 镜像/续行形态、#40/#41 锚对账机械化），已按四要素补登 **W69-05 / W69-10 / W69-11**；
其余项均已在台账或已结案（closed）。缺口根因 = W68 §五 的 W69-05 在并入台账时漏抄
（同 G1 结构性根因：手工聚合面）—— W68-09 轻量核对守卫仍 parked，缺口靠本 M12 人工对账兜底。

**W71 续更行**（G4 台账订正批，2026-10-17）：① W69-03 销账判据订正（「8 容器」⇒「6 有效
（4 PASS + MiniCPM-V-4 Failed + D7 PASS）+ 2 歧义（Qwen2.5/Phi-4-mini logcat 陈旧行污染，
脚本已修）」——W70 交接 §3.1 实况；**W71 再订正**：Phi-4 歧义根因 = 驱动脚本 UUID 错一位
（`4c95` vs 实际 `4d95`）⇒ 该轮实为「未选中模型」态，W71 已用正确 UUID 补跑内存批）；② 记忆两条重新入台账 **W70-02（记忆冲突调和）/ W70-03
（可复用经验记忆）**（深度审 §六 上轮因编号冲突丢失；W70-01 为现役最大号 ⇒ 两号空闲无冲突），
按四要素补登 + 来源注记（外部参照 Hy-Memory 对照确认；SDK/30B/MemHub 不接入；不含 embedding）；
③ 补登后 #42 机械验证：W71 delta ID ⊆ 台账（fail-closed）已跑绿（干净树 arch-guard 42 项 exit 0）。
④ **W71-01 / W71-02 新增**（质检席 P2-2 教训落地）：G5 迁移 #29 时申报的 #41 C-1 兄弟面
（raw string 污染提取假红）「另立台账 ID」承诺**只写在代码注释 ⇒ 登记入簿**（W70-02/03 同型坑：
申报未入台账 = 下波静默丢失，#42 单向守卫检测不到凭空新条目）；P-1 脚本混合判据离线判分
（`_plans/w71-p1-scriptmix-report.md`）双条件达标但 n=2 ⇒ **W71-02 parked**（数据面补齐 +
阈值标定先行，禁凭 n=2 落地）。⑤ W68-06 单维持化（锚 #5 已验）；⑥ W69-02 内存实测批销账
（tail 1.25 维持）；⑦ W70-01 重启条件收紧（P-2 budget=96 臂未命中：思考 36-37 token ≪ 96）。

## G1 全链复盘（W49–W57，「曾出现后消失」条目 diff）

方法：以 W49–W57 各波交接 §五/§六 沿革清单为底，`grep` 逐项追踪出现/消失波次
（`grep -rn "数据回填\|阈值回填" docs/handoff-*.md` 实测）。

| 条目 | 出现 | 消失点 | 恢复为 | 备注 |
|---|---|---|---|---|
| 数据回填 | W49 立项（W46 P0 补救）；W52–W57 逐波在列（W57 `handoff-20261009-wave57-closeout.md:90` 实证） | **W58 `handoff-20261010-wave58-closeout.md` §六.6 重写沿袭清单时漏抄**（该波新增 evidenceLevel 条目、更新 R1 状态，重写时丢项）；W59–W67 十波零提及 | W68-01 | G1 头号项；含用户数据丢失补救 |
| 阈值回填 | 同链在列至 W57 §六.5 | 同上 W58 重写丢失 | W69-03 | 可行性反转：坏样本已在机 |
| 模型直答腿 | W64 closeout:58 裁定语、W65 closeout:20/:54/:85 全记录 | W66/W67 沿革清单未再出现（半结案未显式销账） | W68-10（closed-dead） | 判死留痕，补显式销账 |

**结构性结论**：丢失发生在「手工重抄聚合面」⇒ 本台账 + delta 纪律为治本；W68-09 轻量核对守卫
 parked 观察。另：W65 段其余「C=16/r>16/窗口 (8.4,16.8)」旧口径已由 W67 B1 勘误覆盖
（README:458/:472 已带 W67 勘误，W66 交接 :18/§五.4 两处由 W68 C1 就地补勘误，复审22 P3-③）。

—— W68 C1 建立（方案席沈思远）；波次交接只写 delta。
—— W69 续更：C4 裁决落档（W69-06/07/08/09）+ C1 决策收窄（W68-08）+ M12 对账补登（W69-05/10/11）。
—— W70 续更：治理批销账（W68-09/W69-11 #42+#41C 落地）+ 阈值校准销账（W69-03 不采用留痕）+ 真机件销账（W69-10 D7/2047臂/Y3，N-2 残留转 W70-01）+ 人格档位销账（W69-07 交付冒烟）。
—— W71 续更（G4 台账订正批）：W69-03 销账判据订正（6 有效 + 2 歧义）+ W70-02/W70-03 记忆两条补登（四要素 + Hy-Memory 来源注记）。
—— W72 续更：① **W68-06 bump 决策取数完成**——0.18.0 条件真机臂命中决策树 **(b)**：收益实测（GPU init 修复 + MiniCPM-V-4 type 13 修复）/ 代价实测（锚 #1 P0 真机 2/14 + **锚 #15 新增** `Capabilities` 类删除 + E2B·GPU 输出退化未修）；**bump 本体仍挂起**，转 `docs/14` §C6 一次专门用户裁决（数据收集 ≠ 决策）。② **W68-07 重启前提更新**：0.18.0 已修 GPU init（E2B·GPU `GPU_ARTISAN` 实测）⇒ 该项从「高显存设备 + 上游修复」双前提变为「bump 后 + 高显存设备」单前提；E2B·GPU 在 A13 上可跑但**输出退化未修** ⇒ knownBroken 不得清空（订正为「仅输出退化」，随 bump 时点执行）。③ **W68-03（evidenceLevel 徽章）**：docs/14 §D 悬置自动收敛条款（W72 A3 新立）已满足计数 ⇒ **保守支 = 本波不做**；是否 closed-dead 由用户下次议程确认（不代裁）。④ **新增 W72-01/02/03**（四要素见上）。
—— W73 续更：① **新增 W73-01**（K2 层5 压缩门控 = closed-dead，主理人终裁；判死理由 + 重启前提 = 估算失准事故；代码两处 KDoc 自陈同步补裁结论句，只加句不改原自陈事实）。② **新增 W73-02**（NPU 后端定性 = parked，重启前提 = NPU 能力设备到位；「⛔ 无设备不可验证」注记落 docs/10 §11.8）。③ **新增 W73-03**（音频能力位诚实化 = 条件产品件；**R3 已行使 AUDIO 在档 ⇒ 虚高排除，closed-done 销账，`ModelHeuristics` 零改动**）。④ **W72-02 重启前提补 0.18.0 维度备忘**（X4）。⑤ **新增 W73-04**（P0 事故：测试夹具写坏真机 ⇒ 用户「同意用户协议后闪退」；两 commit 结构性了断 + 设备恢复；closed-done）。W73 delta ID 先入本簿（S1，#42 机械核对）再进交接。

## UI 波候选（W75 起；单一事实源 = `docs/w75-ui-plan.md`）

> **建立**：W75（2026-10-19）。**解决 W-1**：内核终审「转UI前收尾检查」指出 UI 波执行清单**完全在仓外 `_plans/`**（仓外同型第三次）；本波已把完整方案落库为 `docs/w75-ui-plan.md`，本段为防丢失的精简镜像。
> **状态约定**：下列 ID 为 UI 波候选，未带四要素（重启前提/最后触碰波次），**不计入 #42 机械核对的正向台账**（它们是「未来波次输入」，非现役挂账）；正式排期时再按四要素补登为本台账主台账条目。
> **待用户裁决**（U1–U5，见 `docs/w75-ui-plan.md` §1）：evidenceLevel 徽章形态/排序（U1）/ bump 0.18.0（U2）/ 法务 termsVersion→tag（U3）/ 人格加档（U4）/ U3 持久化失败面形态（U5）——U 未裁决前不代裁、不动 toml/文案。

| ID | 条目 | 关联裁决/来源 |
|---|---|---|
| UI-01 | `evidenceLevel` 徽章（模型卡三态 + 口径 tooltip） | U1 / W68-03 |
| UI-02 | `templateArraySafety` 模型卡展示（10/2/0/2 四态） | W68-03 衍生 |
| UI-03 | 人格加档 UI（选择器扩展） | U4 / W68-03 |
| UI-04 | 音频能力位下游（B3 虚高已排除，仅展示） | W68-03 衍生 |
| **UI-05** | **批量工具调用 0 执行的可见提示**（W73/W74 两波提了、台账零命中，#42 单向盲区又吃一条；落地即补 `docs/15` 反向盲区登记） | W-5④ / 转UI前收尾 §六 |
| UI-06 | W69-12 / W69-13（工具超时孤儿写 / indeterminate 记账）可见性 | W69-12/13（parked，草案在 KDoc） |

**UI 波开工前小修（各 ≤20 行，在要动的 UI 文件里）**：P1-3 设置页逐键落盘（`SettingsScreen.kt:488`）/ P1-5 下载取消轮询协程不死（`ModelsViewModel.kt:653`）/ UI-05 探针 UI finally 复位 / UI-06 Gemma 授权清一次性标志 / UI-07 对话页系统提示词补 commit / W-2 改采样参数=会话复位披露（`ChatParamsPanel`）/ W-3 滑条显示值≠生效值回显（`ModelSamplingProfiles.appliedTo`）/ W-4 `GlassSwitch` 加 label（无障碍）。**UI 波会撞守卫**：#20 `ChatRunCoordinator ≤1300`（余 55，主力文件）/ #35 lint baseline ≤4（Compose 新组件极易触发，第一波就跑 lint 看增量）。

—— W75 建立（内核收尾验证通过后立 UI 波单一事实源；本段为 `docs/w75-ui-plan.md` 防丢失镜像）。
