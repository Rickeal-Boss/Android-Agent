# docs/12 — litertlm 0.17.1 行为锚定面清单（bump 前置⑥）

> 本清单 = **bump 前置⑥**（W59 A5 成文，第六审 §3 A5 十二项为底稿；**W61 增补锚 #13 ⇒ 十三项**；**W68 B3 增补锚 #14 ⇒ 十四项**）。
> **复核纪律：逐项复核完成后在本表「打勾」列标记，全部打勾之前不得动 `gradle/libs.versions.toml` 的 litertlm 版本行**（守卫 #29 钉版本取值、红输出指向本清单）。
> 文档头不写本仓引擎源码行号（上游源码坐标为外部锚，可保留）；本清单只做「bump 复核面」的枚举，行为语义的完整论证见各定案波次的交接稿与源码内注释。

## 为什么需要这份清单

litertlm bump 是本仓最高级别的行为风险事件：W56 已实证 v0.18.0 起「1 元素 text 数组 → string」收敛点被删且无等价替代（tag `b2f686e2e` 全树零命中）⇒ fold 失效 ⇒ Qwen2.5 系每轮必炸。除该 P0 级阻断外，0.17.1 上还钉着一批**依赖上游实现细节**的行为锚 —— bump 时任何一项漂移都可能产生「编译能过、真机静默回归」的故障面。本清单把它们收拢为一处，作为 bump 评审的**机械复核单**。

## 锚定面清单（14 项）

| # | 锚定项 | 定案波次 | 依据（本仓锚 / 外部锚） | bump 复核要点 | 锚失效的可观测签名 | 打勾 |
|---|---|---|---|---|---|---|
| 1 | 「1 元素 text 数组 → string」收敛语义 | W55 定案 / W56 v0.18.0 核验 | 收敛点 = v0.17.1 `runtime/conversation/model_data_processor/generic_data_processor.cc:105-112`（`content.size()==1 && [0].type=="text" && !requires_typed_content` ⇒ 收敛为 string）；W55 同构建真机 A/B（fold ON 不炸 / fold OFF 炸模板 `:23`） | **blocker 级**：新版若仍无等价收敛 ⇒ **bump 阻断**（P0，Qwen2.5 系每轮必炸）。⚠️ **tip 已删除该收敛** ⇒ bump 后 `foldAdjacentText` 从「修复」退化为「不够」——所有文本协议多段 user 内容场景全量复发模板炸。**bump 首跑判据**：第一条真机消息就用「一次发 3 个工具结果回灌」的 W51 原题面，collapse 是否还在必须**当场行使**（不是跑回归全绿就算过）。若恢复语义须同步真机 A/B 复验 fold 路径。**预案二选一**（届时按新锚重估）：① 推动上游恢复单元素收敛；② 本仓「整包提示词文本化」退路（不再依赖上游收敛）。⚠️ **W70 情报（2026-10-16）**：gallery #1326 维护者明确要求 0.17.1 vs **0.18.0** 同工件对照复测 ⇒ 0.18.0 = **bump 候选版本情报**；但 W56 已实测其单元素收敛点缺失（tag `b2f686e2e`）⇒ **bump 仍阻断**（W68-06 blocker 双维持：锚 #5 未验 + 锚 #1 收敛缺失）。 | `Failed to apply template` 复发且行号 `:23`/`:27`（W55 fold OFF 实证签名） | ☐ |
| 2 | `Contents.toJson()` 恒返回 JSON 数组 | W51 javap 实锤 / W55 Probe.java 实跑 | `Contents.of("x").toJson()` = `[{"type":"text","text":"x"}]`（0.17.1 Java 层不存在标量 content 通道） | 若新版引入标量通道，前置④的「重启条件②」成立（app 侧标量通道评估另立，勿顺手做） | — | ☐ |
| 3 | `ConversationConfig.channels` 覆盖（thought 通道声明随会话下发） | W43 真机实锤 / W48 按模型选 def | 引擎所有 ConversationConfig 构造点恒显式传 `channels`；缺省时依赖容器元数据自决（MiniCPM5 思维链泄漏形态） | 确认新版 channels 参数与解析行为不变；`front()` 语义（见 #4）不漂移 | — | ☐ |
| 4 | thinking 预算只认 `channels.front()` | W47 | `ThinkingConfig` + 通道声明共同生效；非 front 通道不进预算口径 | 确认 `ThinkingConfig.thinkingTokenBudget` 参数仍在且口径不变 | — | ☐ |
| 5 | `ThinkingBudgetConstraint` 已编入 `.so` | W47（0.17.1 AAR 字节级证实） | 到预算强制吐出 thinking 结束符转入正文（真机 222s 空转的根治面） | **缺失即阻断（P0）**：解包新版 AAR 核对符号仍存在；缺失 ⇒ bump 阻断（不再只是「核对项」）。**升级理由**（W64 K-1 治本后）：字符守卫（`thinkingCharBudgetFor` 派生阈值）已退化为 native 失效**兜底**——maxTokens 滑条越大，native 预算越高、字符守卫越不可达 ⇒ thinking 空转防线**单点依赖 native**；bump 后符号缺失 = 只剩墙钟兜底，W47 222s 空转回归面完整复现。**锚适用条件**（W67 B1 订正后）：健康 native 下守卫阈 = 预算×C(=9) > 预算×实测 r_max(5.06) ⇒ 判定③数学不可达（纯兜底定位）；native 失效时半预算档兜底阈 r > 4.5（实测英文思维链 4.5–5.1 可达）、轴档案档（1536/2048）r > 6.75（条件性收窄，由墙钟 + N-2 warn 承担）。⚠️ **OFF 档不适用本锚的「晚于 native」论证**：`ThinkingMode.OFF` 引擎恒不下发 ThinkingConfig，对不服从 `enable_thinking=false` 的显式通道模型（R1 类）thinking 无预算无守卫（best-effort 第三态，W67 F1 选项 B 申报）。 | thinking 空转（`thinking` 流不结束、无 thinking 结束符转入正文）+ 墙钟兜底触发 | ☐ |
| 6 | `RepetitionPenaltyConfig` 为逐消息参数 | W20（0.17.1 起真实生效） | `sendMessageAsync` 逐消息传参；默认 1.0 = 不惩罚；NPU 后端不传 | 确认逐消息参数签名仍在（若被挪进 ConversationConfig = 会话级固化，调参即时生效语义破坏） | — | ☐ |
| 7 | `automaticToolCalling` 默认 true（红线） | W34 | native 自行执行工具会完全绕过 AgentRunner 审批/沙箱/熔断管线；本仓所有构造点显式写 false，`DeclaredOnlyTool.execute()` 恒抛（物理保险） | 确认默认值未翻转、参数名未改；若上游改默认，本仓显式 false 仍须逐构造点核对 | — | ☐ |
| 8 | `ToolProvider` 抽象方法名带模块混淆后缀 | W34 题 A | 0.17.1 实测：应用侧**无法覆写**，只能走 `tool(OpenApiTool)` 工厂（见 NativeToolBridge 注释） | 确认 `tool(OpenApiTool)` 工厂仍在；若抽象方法可覆写了，探针与注册路径须重新评估 | — | ☐ |
| 9 | `renderPrefaceIntoString`（ExperimentalApi） | W28 | preface 渲染诊断的唯一代码侧观测点（第三态闸门的输入）；渲染失败静默跳过，诊断绝不成为失败面 | 确认 API 仍存在（@OptIn ExperimentalApi）；行为变化只影响观测面，须复跑 preface 诊断真机核对 | — | ☐ |
| 10 | 模板渲染失败在 `sendMessageAsync` **同步抛出** | W55 真机证实 | `Failed to start nativeSendMessageAsync: … Failed to apply template …`；同步 catch 与异步 onError 双入口共用统一处置（W59 A4 起 token 门控幂等） | 真机复核失败路径形态（同步/异步）未漂移；若改为异步回调，处置入口仍兼容（双入口设计） | — | ☐ |
| 11 | `ConversationConfig.systemInstruction` 类型为 `Contents?` | P0-A / W48（0.17.1 起，旧版是 `String?`） | 引擎侧 `systemText?.let { Contents.of(it) }`；传裸 String 编译不过（编译期即拦） | 确认类型未回退；若改回 String 或换类型，构造点编译错 = 有意识动作化 | — | ☐ |
| 12 | 模板引擎 = minijinja 2.14.0 | —（外部锚：上游 minijinja 版本，无本仓定案波次） | v0.17.1 上游模板引擎版本（外部 Cargo 锚；W50-W55 离线复现所用行为基线） | 核对新版依赖的 minijinja 是否仍 2.14.0；升级需重跑「content 为数组必炸」离线复现，确认 `+` 报错语义未变 | — | ☐ |
| 13 | 容器模板可含 minijinja 2.14.0 **不支持的 filter 参数** | W61 定案 | 实证 = MiniCPM5 容器 `chat_template` 第 6 行 `{{- tool \| tojson(ensure_ascii=False) }}`（在 `{%- if tools %}` 块内，即**原生工具通道**分支；`:160` 同款）；依据 = v0.17.1 `runtime/components/rust/minijinja_template.rs:142`（`fn tojson(value: minijinja::Value)` **单参**）+ `:187`（`env.add_filter("tojson", tojson)`）+ 离线复现（PyPI minijinja 2.14.0）⇒ TypeError: unexpected keyword argument 'ensure_ascii'（**同因**：单参 filter 收到关键字参数；Rust 内嵌版同因报 `too many arguments (in template:6)`）+ 真机日志（`原生工具通道：生成期模板渲染失败 … 已证伪本通道`） | 核对新版内嵌 minijinja 版本与 **filter 签名集**（`tojson` 等）；**若新版放宽 `tojson` 参数**（接受关键字参数）⇒ 本容器原生工具通道可用性需真机复验（「探针通过、生成期炸」的探针保真度缺口随之消失）。⚠️ 该失败面**仅在模板语句所在分支被行使时到达**（MiniCPM5 在 `tools` 非空的原生工具通道），与 fold / `requires_typed_content` 无关（W61 双源定案，勿按第七审 §3.1 补前置注记）。⚠️ **W70 情报（2026-10-16，#3576 根因共认）**：上游已复现并定位根因 = 已发布 bundle 内嵌原始模板（`tojson(ensure_ascii=False)`）+ `generic_model{}` 元数据（XML `<function>` 路由需 `minicpm5{}`）⇒ 修复可能落在 **bundle 层**（重打包换模板/元数据）而非引擎层（放宽 `tojson` 签名）——**两种修复路径下**，本仓 MiniCPM5 容器原生工具通道均须**真机复验**后销账。 | — | ☐ |
| 14 | `knownBrokenOn` / GPU 白名单的**运行时时效**（结论会集体过期） | W68 B3（第十二审 R-1） | 机器可读字段 = `ModelPreset.appliesToRuntime`（三条已知坏条目均 = `"0.17.1"`；GPU 白名单 `ModelGpuSupport.gpuVerifiedFileNames` 同属「某运行时 / 设备下的结论」）。单测覆盖见 `feature-models` `ModelPresetsKnownBrokenTest`（时效定语 ⇒ 必有坏结论、无坏结论禁预填）。 | **bump 后必须逐条复审**：上游若修复了容器（`引擎初始化 NOT_FOUND` / `Unsupported model type` / 输出退化等）⇒ **清空对应 `knownBrokenOn`**（并恢复推荐资格，勿只改 `recommended` 留红字段）—— 否则红字硬提示会把已修好的可用模型吓跑；`gpuVerifiedFileNames` 同理须复跑真机验证后逐个放行（**不得**因 bump 就顺手放行）。⚠️ 该字段当前**不参与**任何判定（GPU 放行 / 内存闸门 / 激活态重校验只看 `knownBrokenOn` 与 `ModelGpuSupport`），只作复审锚。⚠️ `appliesToRuntime` 的版本字面量为**人工维护**（现为第 3 处 `"0.17.1"` 字面量），与 `gradle/libs.versions.toml` 的 `litertlm` 行**无机械联动** —— 守卫 #29 只钉 toml 一行、**不校**本字段 ⇒ bump 时须**人工同步**本字段，否则本复审锚会指向错版本。 | 已修好的容器仍按旧结论报「已知不可用」（用户被吓跑）；白名单未随运行时/设备变化复审 | ☐ |

## 复核流程

1. 新 tag 发布后：`git grep` 核对 #1 收敛语义（`MessageToTemplateInput` / `requires_typed_content` 是否回归）→ 直接决定 bump 是否阻断；
2. 解包 AAR 核对 #5 `.so` 符号、#6/#7/#11 Java API 签名；
3. 离线复现 #2/#12/#13（minijinja 行为基线 + filter 签名集：`tojson(ensure_ascii=…)` 是否仍因**关键字参数**报错 —— PyPI 版 `TypeError: unexpected keyword argument` / Rust 内嵌版 `too many arguments`）；
4. 真机批复核 #3/#9/#10（thought 通道声明、preface 诊断、模板失败路径）；
5. **复审锚 #14**（W68 B3 增补）：逐条核对 `ModelPreset.appliesToRuntime`（现全 = `"0.17.1"`）——上游已修容器 ⇒ 清空对应 `knownBrokenOn`（恢复推荐资格）；`ModelGpuSupport.gpuVerifiedFileNames` 须**复跑真机验证**后逐个放行（勿顺手放行）；
6. **跟踪 #3576 issue 状态**（W70 C1 B1 销账判据，锚 #13 同源）：bump 季扫时核对 #3576（MiniCPM5 bundle 模板/元数据根因）是否已发布修复 bundle 或运行时放宽 `tojson` 签名；命中 ⇒ 按锚 #13 复核要点（新 bundle 修复模板/元数据 ⇒ 原生工具通道真机复验）执行后再销账；
7. 全部打勾 + 守卫 #29 期望值同步更新后，方可动 toml 版本行。

### r（chars/token）的离线重测结论（W66 E5 立方法、W67 B1 首次全量执行）

锚 #5 与 `THINKING_CHARS_PER_TOKEN_CEIL`（C=9）的**安全窗口 (5.06, 10.11)** 由 W67 B1 离线重测
定案（脚本 `_ci-tools/_w67_r_retest.py`，仓外 CI 工具；W62/W65/W66 三波 journal 全量扫描、
禁挑样本；W66 E5 落档 4 值被逐位复现 ⇒ 尺子自证）：

| 样本群 | 字符 | token | r |
|---|---|---|---|
| W62 事故样本（gemma-4-E2B-it，2051 字符误判轮） | 2051 | **469** | **4.37** |
| W62 同轮对照组（2010 字符正常作答轮） | 2010 | 495 | 4.06 |
| W62 边界样（2061 字符 V4 决定性） | 2061 | 512 | 4.03 |
| gemma-4-E4B-it（W65 CPU 臂） | 177 | 35 | **5.06 ← r_max** |
| R1 中文思维链（W66 E5，Qwen tokenizer） | 1926/720/520/505 | 1042/318/268/330 | 1.53–2.26 |

**定案一：W62 的 8.4 系口径伪影（证伪）**。README W62 段「2048 字符 ≈ 244 token」的 244
不可能为 thinking token 数 —— 引擎 `completionTokens` 实为**内容 chunk 数**且 thinking chunk
根本不计入（`LiteRtLmEngine` usage 计算）；同样本官方 tokenizer 重测 2051 字符 = 469 token。
两种 tokenizer（gemma 官方 / Qwen 对照 464）下 r≈4.2–4.4，8.4 均不成立；244 的确切来历
在现存工件中不可考（W62 logcat 仅存 1 行、journal 不含 usage），但不影响定案。
**定案二：真 r_max = 5.06**（31 样本全量；≥500 字符主样本段 4.53；语言构成主导：R1 中文
思维链 1.35–2.26、gemma 英文思维链 3.0–5.06）⇒ **C 16 → 9**、`THINKING_CHAR_BUDGET`
8192 → 4608、窗口 (8.4, 16.8) → (5.06, 10.11)；半预算兜底阈 r > 4.5（恢复可达），轴档案档
r > 6.75（条件性收窄申报）。四处单源链（core-engine 常量 / core-model 字面量 / 双不变式
测试 / KDoc 推导段）同批同步。

方法学纪律（沿用）：
1. **native usage 分通道：⛔ 不可用** —— 引擎 `completionTokens` 是内容 **chunk 数**，无 thinking token 数；
2. **离线 tokenizer 计数（硬证据）**：journal 的 `message` 行含 `thinking` 字段 ⇒ 抽 thinking 全文 → 模型官方 tokenizer（gemma 侧 / Qwen 侧各一份）**离线精确计数** → `r = 字符/token`；W67 起以 `_ci-tools/_w67_r_retest.py` 为执行面；
3. `TokenEstimator.estimate` 仅**旁证**（字符启发式），不得当硬证据。
4. **判例六（W68 C1 成文，复审22 P3-②）：非纯数字 token —— 码点级定谳**。凡以「token 数」为分母的结论，引用前先做码点级核验：该数字是**真 tokenizer 输出**、**引擎 usage 字段**、还是**chunk/帧计数**——三者不可混用（8.4 伪影即混用案：244 是 chunk 数）。核验方法 = 同样本用官方 tokenizer 离线重计，两路不一即假。

⚠️ **偏差申报（W67）**：MiniCPM5 唯一样本（W66 `c1_minicpm5_load`，375 字符）本仓无官方
tokenizer 资产 ⇒ 标 SKIP 不计入（不拿异族 tokenizer 凑数）；样本仍以英文题面 run 为主
（`adb input text` 非 ASCII NPE），但思维链语言中英混在（journal 实证），r 的语言构成效应
已在结论表内按 CJK 占比分列。

**薄窗口落档（W68 C2，复检 Q-3）**：native 失效兜底（判定③）的实际覆盖 = **半预算档 × r ∈ (4.5, 5.06)，宽仅 0.56**；轴档案档（1536/2048）兜底阈 **r > 6.75 > r_max 5.06 ⇒ 对 CJK 密集文本实际失守**——由锚 #5 墙钟（:39）+ N-2 warn 承担，链条闭合、可接受。KDoc「实测英文思维链 profile（r≈4.5–5.1）恰好可触发」的「恰好」二字正说明窗口极窄，特此落档备查。**落点选 docs/12 而非 `ThinkingBudget.kt`**：该文件 128/150 余 22 为全表最紧引信（G5），本段长文不入（W68 负面清单同款纪律）。

—— W59 A5 成文（方案席沈思远定稿 / 开发席柯码成落地）；W66 A5 追加 r 测量方法；W67 B1 回填重测结论；W68 B3 增补锚 #14（knownBroken / GPU 白名单运行时时效复审）。
