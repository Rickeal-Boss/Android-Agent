# Wave 28 交接：KV 超卖根治（模型运行支持的架构级缺陷修复）

> 写于 2026-09-27。基线 `d015d0e`（Wave 27）→ 推送 `443689a`（3 commit：docs `16ebf74` + 修复 `4130edc`/`443689a`）。
> 本波性质：**架构层缺陷修复**——「只输出提示词然后胡言乱语」在 Wave 24 角色通道修复后的残留根因 + Wave 24-27 深度审查缺陷批。
> 团队：沈思远（根因逐环验证+方案）→ 严质衡（Wave 24-27 全量审查）→ 主理人实施（专家会话中途取消，实施由主理人接管）。

---

## 1. 根因链（三条独立证据源汇合）

### 1.1 KV 超卖（P0，实锤——本波主修）

`LiteRtLmEngine.kt` 旧实现把 `InferenceConfig.maxTokens`（默认 **1024**，语义=输出上限）传给
litertlm `EngineConfig.maxNumTokens` —— 而官方 KDoc 原文：**「The maximum number of the sum of
input and output tokens. It is equivalent to the size of the kv-cache.」**

后果链：
1. 系统提示词 5 段 + 12 条工具行 ≈ 中文 1500-2000 token，**单独就超出 1024 的 KV**；
2. litertlm 超容行为 = **硬报错**（`"Input token ids are too long"`，沈思远经二进制字符串 +
   executor 快照 + 上游 v0.17.1 源码三层证据证实，**证伪**「静默左截断/滑动窗口」假设）或
   `kMaxNumTokensReached` 提前收尾 —— 用户看到的是生成失败或残缺输出；
3. 复合缺陷：压缩预算 `contextLength(4096) × 0.75 = 3072`（App 侧估算）远大于引擎真实 KV 1024
   → 压缩永远来不及救；且 `TokenEstimator` 3.2 字符/token 对中文**低估 2~3 倍**（KDoc 自认
   「中文按 1 字 ≈ 1 token 更准，取折中」），两个方向不对称，必须偏保守。

**证据细节**：`ModelSamplingProfiles` 注释早已写明「litertlm 的 KV 预算是转换时定死的
（ekv4096 文件 / metadata max_num_tokens=4096）」—— 正确值一直在手边，只是接线错位。

### 1.2 角色通道「静默忽略」第三态（需真机验证，本波加观测）

若某转换件对 systemInstruction/initialMessages **不抛异常而是渲染错位/丢失**（createConversation
成功 → 回退门控抓不住 → SYSTEM 两边都不发），则模型直接失去系统提示词。这是 Wave 24 修复后
「不同视觉模型胡言风格完全不同」的首选嫌疑（不同模型 chat template 对 system role 的渲染行为
不同）。本波加入 `renderPrefaceIntoString()`（@OptIn ExperimentalApi，失败静默）作为唯一代码侧
观测点。

### 1.3 相邻 USER 播种 → 静默 legacy 回退（P1，实锤）

空文本 MODEL 被过滤后 USER/TOOL 在 initialMessages 里连成两条 user → 部分 chat template 判非法
→ createConversation 抛异常 → **静默 legacy 回退（仅一条 warn）= 长工具会话复活原始 P0**。
修法：`mergeAdjacentNativeUsers()` 合并（Contents 拼接），水印登记不变。

---

## 2. 本波修复清单

| # | 级别 | 修复 | 文件 |
|---|---|---|---|
| 1 | P0 | `maxNumTokens = contextLength`（复用判据换轴：`loadedMaxTokens`→`loadedContextLength`，maxTokens 变更不再触发任何重建） | LiteRtLmEngine.kt |
| 2 | P0 | `sendMessageAsync(maxOutputToken = config.maxTokens)` 逐消息输出上限（含思考输出；NPU 无此约束） | LiteRtLmEngine.kt |
| 3 | P0 | TokenEstimator CJK 感知（CJK 1 字=1 token，非 CJK 维持 3.2:1，ceil 保守）+ 7 个单测 | TokenEstimator.kt + Test |
| 4 | P1 | 压缩预算 `(contextLength - maxTokens).coerceAtLeast(512) × threshold` 显式预留输出 | AgentRunner.kt |
| 5 | P1 | 相邻 USER 播种合并（见 §1.3） | LiteRtLmEngine.kt |
| 6 | P1 | 审批缓存 key 加档位维度：`cid|mode|tool|digest`（降档必重新弹卡；升回原档旧授权仍在 TTL 内；参数带默认 null 旧调用方零改动） | ToolApprovalCache.kt + AgentRunner peek + ChatViewModel grant |
| 7 | P1 | 建会话日志 `kv/estPrompt/role`（>85% 升 warn）+ preface 渲染诊断 + 超容报错映射可行动中文文案 | LiteRtLmEngine.kt |
| 8 | P2 | 判定⑨ KDoc 口径修正：典型 64 字符、**最坏 ~79**（窗口对齐偏移 0..STRIDE-1），调参按最坏口径 | StreamRepetitionDetector.kt |

### 复用判据换轴的三处同步（沈思远硬告诫，已核对）

- 字段声明：`loadedMaxTokens` → `loadedContextLength`（KDoc 记录语义修正缘由）；
- `sameEngine` 判据：`maxTokens` 比较**移除**（maxTokens 现在是逐消息参数），换 `contextLength`；
- `releaseInternal()` 清理点同步改名。

**行为收益**：用户在参数面板改「输出上限」从此即时生效（旧实现会整引擎重载 4B 权重数十秒）。

---

## 3. 严质衡 Wave 24-27 审查结论（全量，含已核验面）

**缺陷**：P1-1 审批缓存档位穿透（本波 #6 修复）；P1-2 相邻 USER 播种（本波 #5）；P1-3 角色通道
静默忽略第三态（本波 #7 加观测，真机验证清单）；P2-1 档位/披露 run 内快照 by design（文档化即可）；
P2-2 判定⑨ KDoc（本波 #8）；P2-3 MODEL 回灌带原始 JSON 块（**本波未修**——行为面变更需真机，
见 §5）；P2-4 `executeBodyUnchecked` 77.6KB 逼近 JVM 64K 字节码上限（**本波未拆**——独立重构，
见 §5）。

**已核验无问题（防重复排查）**：判定⑨ Rabin-Karp 数学（Python 差分测试：精确/改写回显均触发，
正常回答/短引用不触发，两侧哈希同序）；Wave 25 StreamReset 三路径 + 重试路径由既有 Retrying
覆盖；水印全链路（播种预登记→filter 早退→回退 clear→四处 clear 位置）；Wave 26 ToolEffect
fail-closed 默认 + ask_actor 双继承；Wave 27 披露八项修法复验无新问题；sanitizeForProvider
消息 id 稳定；arch-guard 第 11 项无命中；测试质量良好。

---

## 4. 真机验收清单（CI 查不出）

1. **P0 主验收**：SmolVLM2-500M / Qwen2-VL-2B 发同一批此前触发复述的问题——
   - 预期：不再大面积失败/残缺；日志 `LiteRT-LM 会话已建：… kv=4096 tok role=on estPrompt=N tok`；
   - 若出现 `上下文占用偏高` warn → 说明 prompt 真的太大（下一波提示词瘦身的依据）；
   - 若出现「生成失败 (…too long…) —— 上下文超出模型容量…」→ KV 已对齐但历史过长，属预期防线。
2. **preface 诊断**：`LiteRT-LM preface 渲染诊断：N chars，开头「…」`——确认系统提示词真的渲染
   进了 preface（尤其 SmolVLM2/LFM2.5-VL 这类小模型）。若开头不是系统提示词内容 → 命中第三态，
   下一步按模型做 template 适配。
3. **输出上限即时生效**：参数面板改 maxTokens，**无需重新加载模型**，下次生成即生效。
4. **审批降档**：WORKSPACE_WRITE 档授权某写操作 → 降 READ_ONLY → 同参写调用必须**重新弹卡**。
5. **长会话压缩**：连续对话至压缩触发，确认不再出现「Input token ids are too long」。
6. 回归：工具调用轮次、ask_actor、授权卡三按钮、上下文压缩、恢复卡。

---

## 5. 挂账（明确未做）

| 项 | 理由 |
|---|---|
| 提示词分档瘦身 | KV 修复 + 估算修正后需真机数据决定瘦哪些段（`>85%` warn 频率即依据）；瘦身处必须在 buildSystemSections 内（echoCorpus 自动同源） |
| `executeBodyUnchecked` 拆分 | 77.6KB/1090 行，逼近 JVM 64K 字节码上限（Wave 27 实测触发过一次）；根治需把 6 个跨轮可变状态收 holder，独立重构波次 |
| MODEL 回灌 JSON 剥离（P2-3） | 播种侧 strip 需与 legacy 路径同步改保持等价，行为面变更需真机对照 |
| 记忆 pull 化 | Wave 27 挂账不变（需真机验证小模型是否主动查） |
| THIN 0.21 底色裁决 / 跟随手势竞争 | 用户手上，不变 |

---

## 6. CI 验收（tip `443689a`，双绿一次通过零返工）

| Run | 结论 | 产物 |
|---|---|---|
| Build `36312219730` | **success**（Assemble Debug 2m43s / Unit tests 全绿） | `liquidagent-debug-443689af…` **39.23 MB**（30 天） |
| Release `36312219725` | **success** | 正式包 **70.03 MB**（90 天）/ mapping **4.23 MB** / debug 39.23 MB |

- `Unit tests` job 实跑通过：新增 `TokenEstimatorTest` 7 用例 + 既有 core-model 24 + core-data 12 全绿。
- Release 侧 16KB ELF 对齐、APK/AAB 验签、arch-guard 11 项全过。
- 本地静态闸门先行：`scripts/arch-guard.sh` 11/11、`balance_check.py` 7 个改动文件全配平。
- 本文档为 docs-only 提交，按惯例随下波代码一起推（`build.yml` 无 paths 过滤，单独推 docs 白跑全量 CI）。
