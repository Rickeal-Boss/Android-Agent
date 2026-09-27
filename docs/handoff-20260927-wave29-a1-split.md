# Wave 29 交接：深度评审驱动修复批（检索召回悬崖 + lint 门禁 + 测试源集 + A1 六步拆分）

> 写于 2026-09-27 晚。基线 `51bc959`（Wave 28 追记后）→ 推送 `54158bc`。
> 本波性质：**验证体系建设 + 结构性技术债清偿**。外部输入 = 三份审查（莫斯复审4 / 全量问题清单 53+15 项 / 深度评审）。
> 团队：沈思远（A1 方案+终审）→ 柯码成（B1 测试 + A1 三批实施）→ 崔续程（B5 lint）→ 严质衡（终审）→ 主理人（bigram 修复 + 汇编）。

---

## 1. 本波提交清单（全绿）

| commit | 内容 | CI |
|---|---|---|
| `ac3e24e` | **P0-3 检索召回悬崖修复**：HiddenToolCatalog `split(' ')` → CJK bigram tokenize + DateTimeTool 描述补"现在几点"锚点 + 4 用例 | ✅ |
| `54f4947` | tokenize 断言修正（CI 首红 1 例，生产路径零缺陷） | ✅ |
| `44a9160` | **B5 lint 门禁**（崔续程）：`:app:lintDebug` + checkDependencies=true 覆盖 9 个 AGP 模块，warn-only 三件套，独立并行 job，**首跑 success** | ✅ |
| `39740ac` | **B1 core-agent 测试源集**（柯码成）：65 例纯函数单测（TextToolProtocol 三态 / ToolApprovalCache 档位 key / ToolArgsValidator） | ✅（1 例自污染修正后） |
| `3ac724f` | 档位隔离用例自污染修正（先 grant 后 peek 同 key 必命中） | ✅ |
| `b71d888` | **A1 Step 1**：生成重试块 → `runGenerationRound`（`GenerationOutcome?` 可空映射） | ✅ |
| `813d987` | **A1 Step 2+3**：RunState 24 字段收编 + for 体 → `executeSingleToolCall`（8 处 continue → NextCall） | ✅ |
| `54158bc` | **A1 Step 4+5+6**：三分支/轮内循环/收尾外提 | ✅ |

全仓测试用例 96 → 165+。

## 2. P0-3 检索召回悬崖（深度评审硬实锤）

评分器原用 `split(' ')` 分词：中文无空格，「读取文件」成为一个 term，`description.contains("读取文件")` 对「读取沙箱目录内的文本文件」= false → 零命中。离线复现 5 组自然语言 query 全零，而 ON_DEMAND 已 shipped；零命中回灌「换一个更宽泛的关键词」正是 Wave 27 转发 P0 的同款死循环形态。

修法（评分权重/结构零改动）：`tokenize()` —— CJK 连续段 bigram（读取文件→{读取,取文,文件}，与描述公共子串必然相交），非 CJK 段整词保留，标点/空白词界；两字 CJK 段 = 单个 bigram 与旧 split 等价（既有用例零回归的根据）。DateTimeTool 描述补自然语言变体作检索锚点。

## 3. A1 六步拆分（本波主菜）

**成果**：`executeBodyUnchecked` 976 行（字节码逼近 64K 上限，Wave 27 实际炸过）→ **407 行**（余量 >2.5×）；24 个跨轮局部 var 收进 `RunState`（每 run 局部对象，R2-4 红线）；新增 5 个无类字段依赖的扩展方法 + 3 个控制流枚举（ToolCallStep/NoCallStep/PostStreamStep）。

**方案与实施纪律**（可作为后续大方法拆分标准动作固化，对标 Wave 27 d015d0e）：
- 方案定稿落盘 `_plans/wave29-a1-split-plan.md`：§〇 控制流全量底账（6 return/3 break/11 continue）+ §一 状态清单 + §三 6 步蓝图（每步行号区间/签名/字节码余量表）+ §四 风险清单 + §五 明确不做红线。
- 机械等价变换、每 Step 独立 commit、每批单独过 CI（三批全绿一次过）。
- 「机械等价 + 每 commit 独立 CI + commit message 内嵌控制流底账」使终审可完全机械化复核（严质衡复盘建议，已采纳为惯例）。

**双轨终审结论（交叉验证一致）**：
- 沈思远（方案作者视角）：**通过**，17/17 控制流映射逐条兑现，零阻断。确认柯码成补的 `intraStreamLoop` 参数正是方案原始意图（Step 5 签名疏漏，方案文件已补正）。
- 严质衡（独立视角）：**通过**，底账全等价、`!!` 零出现、when 均穷尽无 else 兜底、注释平移保真（约 200 行原注释仅 :491-494 四行按申报并入 RunState KDoc）。
- 3 条建议级观察（不阻塞）：① executeSingleToolCall 经 toolRegistry 未参数化（日后纯函数化时做）；② 原注释内嵌旧行号引用漂移（后续清理波次）；③ RunState KDoc 为改写并入（已申报闭环）。

**方案论证的防线真实生效**：R2-1 smart-cast 预案一次命中（elvis-also 死分支无害）；具名实参让联动漏改显形为编译错（runGenerationRound 的 round 悬空，安全失败）；CI 真跑抓到 2 个测试缺陷（tokenize 大小写断言 / 档位用例自污染）——本地无编译环境下顺序依赖类缺陷只有 CI 能抓。

## 4. 深度评审其余结论的处置状态

| 评审项 | 状态 |
|---|---|
| P0-1 core-agent 零测试 | ✅ 本波解决（65 例，B1 第一波） |
| P0-2 BreakerLedger+RunState 一体 | ⏳ **部分**：RunState 本波落地；BreakerLedger/振荡检测/墙钟/TokenBudget 接线待 Wave 30（RunState 接口已一步到位，评审 §3.4 设计可直接叠） |
| P0-3 bigram 召回悬崖 | ✅ 本波解决 |
| P0-4 工具调用振荡检测 | ⏳ Wave 30（并入 BreakerLedger 批） |
| P0-5 history_v2 判死或接线 | ⏸ **待主理人裁决**（删写入调用 vs 接线恢复回落） |
| P1-6/7 墙钟+token 预算/失败计数 | Wave 30 |
| P1-8 ConversationRepository 增量写 | Wave 30 |
| P1-9 记忆 pull 化 + memory_search | Wave 30（复用 tokenize） |
| P1-10/11 formatVersion + arch-guard 第 12/13 项 | Wave 30 |
| P2-13 SQLDelight（仅 conversations） | 需主理人裁决，P1-8 完成后重估 |
| P2-15 README 特性表「规划中」失实 | 待主理人裁决（对外信号） |
| B7 本地 JDK | **用户否决**（不装本地环境，CI+真机） |

## 5. 真机验收清单（本波新增）

1. **ON_DEMAND × 中文自然语言 query**：search_tools 填「读取文件」「帮我读取文件」「现在几点了」「我想记住用户的偏好」「把这个复制到剪贴板」——修复前 5 组全零命中，修复后必须命中对应工具。
2. **A1 回归**：长工具会话多轮跑（同参重试/审批熔断/空输出/循环检测各触发一次）——行为与拆分前逐字节一致的预期；任何行为差异即回归。
3. lint job 产物 `lint-reports-<sha>` 的存量 issue 计数（首跑预计两位数）——清障后翻转 abortOnError=true 成硬门禁。

## 6. CI 机制备忘

- concurrency 取消旧 SHA 的 run：Release 显示 cancelled 属正常，新 SHA 覆盖验证。
- 取失败日志：`runs/{id}/jobs` → `jobs/{id}/logs`（curl -L + Bearer；annotations 端点对新 PAT 返回非 JSON 不可用）。
- 测试顺序依赖类缺陷（自污染）本地无法发现，CI 真跑是唯一裁判。
