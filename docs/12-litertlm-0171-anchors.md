# docs/12 — litertlm 0.17.1 行为锚定面清单（bump 前置⑥）

> 本清单 = **bump 前置⑥**（W59 A5 成文，第六审 §3 A5 十二项为底稿；**W61 增补锚 #13 ⇒ 十三项**）。
> **复核纪律：逐项复核完成后在本表「打勾」列标记，全部打勾之前不得动 `gradle/libs.versions.toml` 的 litertlm 版本行**（守卫 #29 钉版本取值、红输出指向本清单）。
> 文档头不写本仓引擎源码行号（上游源码坐标为外部锚，可保留）；本清单只做「bump 复核面」的枚举，行为语义的完整论证见各定案波次的交接稿与源码内注释。

## 为什么需要这份清单

litertlm bump 是本仓最高级别的行为风险事件：W56 已实证 v0.18.0 起「1 元素 text 数组 → string」收敛点被删且无等价替代（tag `b2f686e2e` 全树零命中）⇒ fold 失效 ⇒ Qwen2.5 系每轮必炸。除该 P0 级阻断外，0.17.1 上还钉着一批**依赖上游实现细节**的行为锚 —— bump 时任何一项漂移都可能产生「编译能过、真机静默回归」的故障面。本清单把它们收拢为一处，作为 bump 评审的**机械复核单**。

## 锚定面清单（13 项）

| # | 锚定项 | 定案波次 | 依据（本仓锚 / 外部锚） | bump 复核要点 | 锚失效的可观测签名 | 打勾 |
|---|---|---|---|---|---|---|
| 1 | 「1 元素 text 数组 → string」收敛语义 | W55 定案 / W56 v0.18.0 核验 | 收敛点 = v0.17.1 `runtime/conversation/model_data_processor/generic_data_processor.cc:105-112`（`content.size()==1 && [0].type=="text" && !requires_typed_content` ⇒ 收敛为 string）；W55 同构建真机 A/B（fold ON 不炸 / fold OFF 炸模板 `:23`） | **blocker 级**：新版若仍无等价收敛 ⇒ **bump 阻断**（P0，Qwen2.5 系每轮必炸）。⚠️ **tip 已删除该收敛** ⇒ bump 后 `foldAdjacentText` 从「修复」退化为「不够」——所有文本协议多段 user 内容场景全量复发模板炸。**bump 首跑判据**：第一条真机消息就用「一次发 3 个工具结果回灌」的 W51 原题面，collapse 是否还在必须**当场行使**（不是跑回归全绿就算过）。若恢复语义须同步真机 A/B 复验 fold 路径。**预案二选一**（届时按新锚重估）：① 推动上游恢复单元素收敛；② 本仓「整包提示词文本化」退路（不再依赖上游收敛）。 | `Failed to apply template` 复发且行号 `:23`/`:27`（W55 fold OFF 实证签名） | ☐ |
| 2 | `Contents.toJson()` 恒返回 JSON 数组 | W51 javap 实锤 / W55 Probe.java 实跑 | `Contents.of("x").toJson()` = `[{"type":"text","text":"x"}]`（0.17.1 Java 层不存在标量 content 通道） | 若新版引入标量通道，前置④的「重启条件②」成立（app 侧标量通道评估另立，勿顺手做） | — | ☐ |
| 3 | `ConversationConfig.channels` 覆盖（thought 通道声明随会话下发） | W43 真机实锤 / W48 按模型选 def | 引擎所有 ConversationConfig 构造点恒显式传 `channels`；缺省时依赖容器元数据自决（MiniCPM5 思维链泄漏形态） | 确认新版 channels 参数与解析行为不变；`front()` 语义（见 #4）不漂移 | — | ☐ |
| 4 | thinking 预算只认 `channels.front()` | W47 | `ThinkingConfig` + 通道声明共同生效；非 front 通道不进预算口径 | 确认 `ThinkingConfig.thinkingTokenBudget` 参数仍在且口径不变 | — | ☐ |
| 5 | `ThinkingBudgetConstraint` 已编入 `.so` | W47（0.17.1 AAR 字节级证实） | 到预算强制吐出 thinking 结束符转入正文（真机 222s 空转的根治面） | **缺失即阻断（P0）**：解包新版 AAR 核对符号仍存在；缺失 ⇒ bump 阻断（不再只是「核对项」）。**升级理由**（W64 K-1 治本后）：字符守卫（`thinkingCharBudgetFor` 派生阈值）已退化为 native 失效**兜底**——maxTokens 滑条越大，native 预算越高、字符守卫越不可达 ⇒ thinking 空转防线**单点依赖 native**；bump 后符号缺失 = 只剩墙钟兜底，W47 222s 空转回归面完整复现。 | thinking 空转（`thinking` 流不结束、无 thinking 结束符转入正文）+ 墙钟兜底触发 | ☐ |
| 6 | `RepetitionPenaltyConfig` 为逐消息参数 | W20（0.17.1 起真实生效） | `sendMessageAsync` 逐消息传参；默认 1.0 = 不惩罚；NPU 后端不传 | 确认逐消息参数签名仍在（若被挪进 ConversationConfig = 会话级固化，调参即时生效语义破坏） | — | ☐ |
| 7 | `automaticToolCalling` 默认 true（红线） | W34 | native 自行执行工具会完全绕过 AgentRunner 审批/沙箱/熔断管线；本仓所有构造点显式写 false，`DeclaredOnlyTool.execute()` 恒抛（物理保险） | 确认默认值未翻转、参数名未改；若上游改默认，本仓显式 false 仍须逐构造点核对 | — | ☐ |
| 8 | `ToolProvider` 抽象方法名带模块混淆后缀 | W34 题 A | 0.17.1 实测：应用侧**无法覆写**，只能走 `tool(OpenApiTool)` 工厂（见 NativeToolBridge 注释） | 确认 `tool(OpenApiTool)` 工厂仍在；若抽象方法可覆写了，探针与注册路径须重新评估 | — | ☐ |
| 9 | `renderPrefaceIntoString`（ExperimentalApi） | W28 | preface 渲染诊断的唯一代码侧观测点（第三态闸门的输入）；渲染失败静默跳过，诊断绝不成为失败面 | 确认 API 仍存在（@OptIn ExperimentalApi）；行为变化只影响观测面，须复跑 preface 诊断真机核对 | — | ☐ |
| 10 | 模板渲染失败在 `sendMessageAsync` **同步抛出** | W55 真机证实 | `Failed to start nativeSendMessageAsync: … Failed to apply template …`；同步 catch 与异步 onError 双入口共用统一处置（W59 A4 起 token 门控幂等） | 真机复核失败路径形态（同步/异步）未漂移；若改为异步回调，处置入口仍兼容（双入口设计） | — | ☐ |
| 11 | `ConversationConfig.systemInstruction` 类型为 `Contents?` | P0-A / W48（0.17.1 起，旧版是 `String?`） | 引擎侧 `systemText?.let { Contents.of(it) }`；传裸 String 编译不过（编译期即拦） | 确认类型未回退；若改回 String 或换类型，构造点编译错 = 有意识动作化 | — | ☐ |
| 12 | 模板引擎 = minijinja 2.14.0 | —（外部锚：上游 minijinja 版本，无本仓定案波次） | v0.17.1 上游模板引擎版本（外部 Cargo 锚；W50-W55 离线复现所用行为基线） | 核对新版依赖的 minijinja 是否仍 2.14.0；升级需重跑「content 为数组必炸」离线复现，确认 `+` 报错语义未变 | — | ☐ |
| 13 | 容器模板可含 minijinja 2.14.0 **不支持的 filter 参数** | W61 定案 | 实证 = MiniCPM5 容器 `chat_template` 第 6 行 `{{- tool \| tojson(ensure_ascii=False) }}`（在 `{%- if tools %}` 块内，即**原生工具通道**分支；`:160` 同款）；依据 = v0.17.1 `runtime/components/rust/minijinja_template.rs:142`（`fn tojson(value: minijinja::Value)` **单参**）+ `:187`（`env.add_filter("tojson", tojson)`）+ 离线复现（PyPI minijinja 2.14.0）⇒ TypeError: unexpected keyword argument 'ensure_ascii'（**同因**：单参 filter 收到关键字参数；Rust 内嵌版同因报 `too many arguments (in template:6)`）+ 真机日志（`原生工具通道：生成期模板渲染失败 … 已证伪本通道`） | 核对新版内嵌 minijinja 版本与 **filter 签名集**（`tojson` 等）；**若新版放宽 `tojson` 参数**（接受关键字参数）⇒ 本容器原生工具通道可用性需真机复验（「探针通过、生成期炸」的探针保真度缺口随之消失）。⚠️ 该失败面**仅在模板语句所在分支被行使时到达**（MiniCPM5 在 `tools` 非空的原生工具通道），与 fold / `requires_typed_content` 无关（W61 双源定案，勿按第七审 §3.1 补前置注记） | — | ☐ |

## 复核流程

1. 新 tag 发布后：`git grep` 核对 #1 收敛语义（`MessageToTemplateInput` / `requires_typed_content` 是否回归）→ 直接决定 bump 是否阻断；
2. 解包 AAR 核对 #5 `.so` 符号、#6/#7/#11 Java API 签名；
3. 离线复现 #2/#12/#13（minijinja 行为基线 + filter 签名集：`tojson(ensure_ascii=…)` 是否仍因**关键字参数**报错 —— PyPI 版 `TypeError: unexpected keyword argument` / Rust 内嵌版 `too many arguments`）；
4. 真机批复核 #3/#9/#10（thought 通道声明、preface 诊断、模板失败路径）；
5. 全部打勾 + 守卫 #29 期望值同步更新后，方可动 toml 版本行。

### r（chars/token）的测量方法（W66 E5 落档）

锚 #5 与 `THINKING_CHARS_PER_TOKEN_CEIL`（C=16）的**安全窗口 (8.4, 16.8)** 依赖实测 `r_max`，
而现有两个 r 样本（W62 的 8.4 / W65 补丁臂的 2.0）**分母都是软的** ⇒ **先修尺子再采样**：

1. **native usage 分通道：⛔ 不可用** —— 引擎 `completionTokens` 是内容 **chunk 数**，无 thinking token 数；
2. **离线 tokenizer 计数（采用）**：真机 journal 的 `message` 行含 `thinking` 字段 ⇒ 抽 thinking 全文 → 模型官方 tokenizer（`transformers`/`sentencepiece`）**离线精确计数** → `r = 字符/token`；
3. `TokenEstimator.estimate` 仅**旁证**（字符启发式），不得当硬证据。

判定：`r_max ≤ 8.4` ⇒ C=16 冻结；`> 8.4` ⇒ 窗口下界上移、C 重估（四处同步单见
`core-engine/.../ThinkingBudget.kt` 顶部 + 轴档案 `minThinkingTokenBudget`）。

—— W59 A5 成文（方案席沈思远定稿 / 开发席柯码成落地）；W66 A5 追加 r 测量方法。
