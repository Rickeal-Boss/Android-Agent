# docs/12 — litertlm 0.17.1 行为锚定面清单（bump 前置⑥）

> 本清单 = **bump 前置⑥**（W59 A5 成文，第六审 §3 A5 十二项为底稿；**W61 增补锚 #13 ⇒ 十三项**；**W68 B3 增补锚 #14 ⇒ 十四项**；**W72 增补锚 #15 ⇒ 十五项**）。
> **复核纪律：逐项复核完成后在本表「打勾」列标记，全部打勾之前不得动 `gradle/libs.versions.toml` 的 litertlm 版本行**（守卫 #29 钉版本取值、红输出指向本清单）。
> 文档头不写本仓引擎源码行号（上游源码坐标为外部锚，可保留）；本清单只做「bump 复核面」的枚举，行为语义的完整论证见各定案波次的交接稿与源码内注释。

## 为什么需要这份清单

litertlm bump 是本仓最高级别的行为风险事件：W56 已实证 v0.18.0 起「1 元素 text 数组 → string」收敛点被删且无等价替代（tag `b2f686e2e` 全树零命中）⇒ fold 失效 ⇒ Qwen2.5 系每轮必炸。除该 P0 级阻断外，0.17.1 上还钉着一批**依赖上游实现细节**的行为锚 —— bump 时任何一项漂移都可能产生「编译能过、真机静默回归」的故障面。本清单把它们收拢为一处，作为 bump 评审的**机械复核单**。

## 锚定面清单（15 项）

| # | 锚定项 | 定案波次 | 依据（本仓锚 / 外部锚） | bump 复核要点 | 锚失效的可观测签名 | 打勾 |
|---|---|---|---|---|---|---|
| 1 | 「1 元素 text 数组 → string」收敛语义 | W55 定案 / W56 v0.18.0 核验 | 收敛点 = v0.17.1 `runtime/conversation/model_data_processor/generic_data_processor.cc:105-112`（`content.size()==1 && [0].type=="text" && !requires_typed_content` ⇒ 收敛为 string）；W55 同构建真机 A/B（fold ON 不炸 / fold OFF 炸模板 `:23`） | **blocker 级**：新版若仍无等价收敛 ⇒ **bump 阻断**（P0，Qwen2.5 系每轮必炸）。⚠️ **tip 已删除该收敛** ⇒ bump 后 `foldAdjacentText` 从「修复」退化为「不够」——所有文本协议多段 user 内容场景全量复发模板炸。**bump 首跑判据**：第一条真机消息就用「一次发 3 个工具结果回灌」的 W51 原题面，collapse 是否还在必须**当场行使**（不是跑回归全绿就算过）。若恢复语义须同步真机 A/B 复验 fold 路径。**预案二选一**（届时按新锚重估）：① 推动上游恢复单元素收敛；② 本仓「整包提示词文本化」退路（不再依赖上游收敛）。⚠️ **W70 情报（2026-10-16）**：gallery #1326 维护者明确要求 0.17.1 vs **0.18.0** 同工件对照复测 ⇒ 0.18.0 = **bump 候选版本情报**；但 W56 已实测其单元素收敛点缺失（tag `b2f686e2e`）⇒ **bump 仍阻断**（W68-06 blocker 双维持：锚 #5 未验 + 锚 #1 收敛缺失）。 | `Failed to apply template` 复发且行号 `:23`/`:27`（W55 fold OFF 实证签名） | ⛔ 0.18.0 收敛缺失（W71 锚批实证，见下节） |
| 2 | `Contents.toJson()` 恒返回 JSON 数组 | W51 javap 实锤 / W55 Probe.java 实跑 | `Contents.of("x").toJson()` = `[{"type":"text","text":"x"}]`（0.17.1 Java 层不存在标量 content 通道） | 若新版引入标量通道，前置④的「重启条件②」成立（app 侧标量通道评估另立，勿顺手做） | — | ✅ W71 锚批（toJson 仍返回 JsonArray） |
| 3 | `ConversationConfig.channels` 覆盖（thought 通道声明随会话下发） | W43 真机实锤 / W48 按模型选 def | 引擎所有 ConversationConfig 构造点恒显式传 `channels`；缺省时依赖容器元数据自决（MiniCPM5 思维链泄漏形态） | 确认新版 channels 参数与解析行为不变；`front()` 语义（见 #4）不漂移 | — | ✅ 静态签名面（W71；行为归真机） |
| 4 | thinking 预算只认 `channels.front()` | W47 | `ThinkingConfig` + 通道声明共同生效；非 front 通道不进预算口径 | 确认 `ThinkingConfig.thinkingTokenBudget` 参数仍在且口径不变 | — | ✅ 静态签名面（W71；行为归真机） |
| 5 | `ThinkingBudgetConstraint` 已编入 `.so` | W47（0.17.1 AAR 字节级证实） | 到预算强制吐出 thinking 结束符转入正文（真机 222s 空转的根治面） | **缺失即阻断（P0）**：解包新版 AAR 核对符号仍存在；缺失 ⇒ bump 阻断（不再只是「核对项」）。**升级理由**（W64 K-1 治本后）：字符守卫（`thinkingCharBudgetFor` 派生阈值）已退化为 native 失效**兜底**——maxTokens 滑条越大，native 预算越高、字符守卫越不可达 ⇒ thinking 空转防线**单点依赖 native**；bump 后符号缺失 = 只剩墙钟兜底，W47 222s 空转回归面完整复现。**锚适用条件**（W67 B1 订正后）：健康 native 下守卫阈 = 预算×C(=9) > 预算×实测 r_max(5.06) ⇒ 判定③数学不可达（纯兜底定位）；native 失效时半预算档兜底阈 r > 4.5（实测英文思维链 4.5–5.1 可达）、轴档案档（1536/2048）r > 6.75（条件性收窄，由墙钟 + N-2 warn 承担）。⚠️ **OFF 档不适用本锚的「晚于 native」论证**：`ThinkingMode.OFF` 引擎恒不下发 ThinkingConfig，对不服从 `enable_thinking=false` 的显式通道模型（R1 类）thinking 无预算无守卫（best-effort 第三态，W67 F1 选项 B 申报）。 | thinking 空转（`thinking` 流不结束、无 thinking 结束符转入正文）+ 墙钟兜底触发 | ✅ W71 锚批（双 ABI `.so` 符号均在，「锚 #5 未验」半边消除） |
| 6 | `RepetitionPenaltyConfig` 为逐消息参数 | W20（0.17.1 起真实生效） | `sendMessageAsync` 逐消息传参；默认 1.0 = 不惩罚；NPU 后端不传 | 确认逐消息参数签名仍在（若被挪进 ConversationConfig = 会话级固化，调参即时生效语义破坏） | — | ✅ W71 锚批（sendMessageAsync 第 4 参逐消息传） |
| 7 | `automaticToolCalling` 默认 true（红线） | W34 | native 自行执行工具会完全绕过 AgentRunner 审批/沙箱/熔断管线；本仓所有构造点显式写 false，`DeclaredOnlyTool.execute()` 恒抛（物理保险） | 确认默认值未翻转、参数名未改；若上游改默认，本仓显式 false 仍须逐构造点核对 | — | ✅ W71 锚批（字节码实证默认 true 未翻） |
| 8 | `ToolProvider` 抽象方法名带模块混淆后缀 | W34 题 A | 0.17.1 实测：应用侧**无法覆写**，只能走 `tool(OpenApiTool)` 工厂（见 NativeToolBridge 注释） | 确认 `tool(OpenApiTool)` 工厂仍在；若抽象方法可覆写了，探针与注册路径须重新评估 | — | ✅ W71 锚批（混淆后缀 + 工厂均在） |
| 9 | `renderPrefaceIntoString`（ExperimentalApi） | W28 | preface 渲染诊断的唯一代码侧观测点（第三态闸门的输入）；渲染失败静默跳过，诊断绝不成为失败面 | 确认 API 仍存在（@OptIn ExperimentalApi）；行为变化只影响观测面，须复跑 preface 诊断真机核对 | — | ✅ W71 锚批（API 在） |
| 10 | 模板渲染失败在 `sendMessageAsync` **同步抛出** | W55 真机证实 | `Failed to start nativeSendMessageAsync: … Failed to apply template …`；同步 catch 与异步 onError 双入口共用统一处置（W59 A4 起 token 门控幂等） | 真机复核失败路径形态（同步/异步）未漂移；若改为异步回调，处置入口仍兼容（双入口设计） | — | ☐ 静态不可判（归真机） |
| 11 | `ConversationConfig.systemInstruction` 类型为 `Contents?` | P0-A / W48（0.17.1 起，旧版是 `String?`） | 引擎侧 `systemText?.let { Contents.of(it) }`；传裸 String 编译不过（编译期即拦） | 确认类型未回退；若改回 String 或换类型，构造点编译错 = 有意识动作化 | — | ✅ W71 锚批（systemInstruction 仍 Contents） |
| 12 | 模板引擎 = minijinja 2.14.0 | —（外部锚：上游 minijinja 版本，无本仓定案波次） | v0.17.1 上游模板引擎版本（外部 Cargo 锚；W50-W55 离线复现所用行为基线） | 核对新版依赖的 minijinja 是否仍 2.14.0；升级需重跑「content 为数组必炸」离线复现，确认 `+` 报错语义未变 | — | ✅ W71 锚批（Cargo.toml 钉 `=2.14.0`） |
| 13 | 容器模板可含 minijinja 2.14.0 **不支持的 filter 参数** | W61 定案 | 实证 = MiniCPM5 容器 `chat_template` 第 6 行 `{{- tool \| tojson(ensure_ascii=False) }}`（在 `{%- if tools %}` 块内，即**原生工具通道**分支；`:160` 同款）；依据 = v0.17.1 `runtime/components/rust/minijinja_template.rs:142`（`fn tojson(value: minijinja::Value)` **单参**）+ `:187`（`env.add_filter("tojson", tojson)`）+ 离线复现（PyPI minijinja 2.14.0）⇒ TypeError: unexpected keyword argument 'ensure_ascii'（**同因**：单参 filter 收到关键字参数；Rust 内嵌版同因报 `too many arguments (in template:6)`）+ 真机日志（`原生工具通道：生成期模板渲染失败 … 已证伪本通道`） | 核对新版内嵌 minijinja 版本与 **filter 签名集**（`tojson` 等）；**若新版放宽 `tojson` 参数**（接受关键字参数）⇒ 本容器原生工具通道可用性需真机复验（「探针通过、生成期炸」的探针保真度缺口随之消失）。⚠️ 该失败面**仅在模板语句所在分支被行使时到达**（MiniCPM5 在 `tools` 非空的原生工具通道），与 fold / `requires_typed_content` 无关（W61 双源定案，勿按第七审 §3.1 补前置注记）。⚠️ **W70 情报（2026-10-16，#3576 根因共认）**：上游已复现并定位根因 = 已发布 bundle 内嵌原始模板（`tojson(ensure_ascii=False)`）+ `generic_model{}` 元数据（XML `<function>` 路由需 `minicpm5{}`）⇒ 修复可能落在 **bundle 层**（重打包换模板/元数据）而非引擎层（放宽 `tojson` 签名）——**两种修复路径下**，本仓 MiniCPM5 容器原生工具通道均须**真机复验**后销账。⚠️ **W73 追加**：上游 MiniCPM5 模板变更（bundle 修复 / 重打包 / 新版本）⇒ **重跑七格取证判定表**（新模板的数组行为未知，`templateArraySafety` 结论随模板指纹 `sha256` 作废重算；W72-01 重启前提同族）。 | — | ✅ W71 锚批（tojson 单参签名逐字未放宽） |
| 14 | `knownBrokenOn` / GPU 白名单的**运行时时效**（结论会集体过期） | W68 B3（第十二审 R-1） | 机器可读字段 = `ModelPreset.appliesToRuntime`（三条已知坏条目均 = `"0.17.1"`；GPU 白名单 `ModelGpuSupport.gpuVerifiedFileNames` 同属「某运行时 / 设备下的结论」）。单测覆盖见 `feature-models` `ModelPresetsKnownBrokenTest`（时效定语 ⇒ 必有坏结论、无坏结论禁预填）。 | **bump 后必须逐条复审**：上游若修复了容器（`引擎初始化 NOT_FOUND` / `Unsupported model type` / 输出退化等）⇒ **清空对应 `knownBrokenOn`**（并恢复推荐资格，勿只改 `recommended` 留红字段）—— 否则红字硬提示会把已修好的可用模型吓跑；`gpuVerifiedFileNames` 同理须复跑真机验证后逐个放行（**不得**因 bump 就顺手放行）。⚠️ 该字段当前**不参与**任何判定（GPU 放行 / 内存闸门 / 激活态重校验只看 `knownBrokenOn` 与 `ModelGpuSupport`），只作复审锚。⚠️ `appliesToRuntime` 的版本字面量为**人工维护**（现为第 3 处 `"0.17.1"` 字面量），与 `gradle/libs.versions.toml` 的 `litertlm` 行**无机械联动** —— 守卫 #29 只钉 toml 一行、**不校**本字段 ⇒ bump 时须**人工同步**本字段，否则本复审锚会指向错版本。 | 已修好的容器仍按旧结论报「已知不可用」（用户被吓跑）；白名单未随运行时/设备变化复审 | ☐ 部分覆盖（W72 臂：E2B-gpu 的 NOT_FOUND 半边已修、输出退化半边仍在 ⇒ **不得整体清空**，须订正为「仅输出退化」；MiniCPM-V-4 已可建会话 ⇒ 该条结论过期） |
| 15 | `Capabilities` 类被 `ModelInfo` 取代（能力探测 API 重构） | W72 真机臂（**编译期**发现） | v0.18.0 AAR `classes.jar` 实证：`com.google.ai.edge.litertlm.Capabilities` **整类删除**。替代 = `ModelInfo`（`abstract class … : AutoCloseable`；静态工厂 `ModelInfo.from(String)`），`ModelInfo.Llm : ModelInfo(), LlmCapability` 提供 `hasSpeculativeDecodingSupport()` / `supportsThinking()` / `supportsFunctionCalling()` / `defaultSamplerParams()` / `isDynamicContext()` / `maxVisionTokenBudget()` / `visionSignatureSelection()`；另有 `LlmCapability` / `EmbeddingCapability` 两接口 | **编译期即拦，不是静默回归**：本仓 2 处 `Capabilities(modelPath).use { it.hasSpeculativeDecodingSupport() }`（`core-engine/.../ModelCapabilityProbe.kt:19`、`.../LiteRtLmEngineLoader.kt:497`）在 0.18.0 下 `Unresolved reference 'Capabilities'`（实测 7 条编译错误）。迁移写法（W72 臂实证可编译）：`ModelInfo.from(p).use { (it as? ModelInfo.Llm)?.hasSpeculativeDecodingSupport() ?: false }` | `Unresolved reference 'Capabilities'` / `Cannot infer type for type parameter 'R'` | ✅ W72 臂（迁移后 `assembleDebug` BUILD SUCCESSFUL） |

### 七格取证判定表（W73 立；锚 #1 同源 + W72-01 挂账事实源）

> **用途**：`templateArraySafety` 四态（现役分布 = 10 SAFE / 2 UNSAFE / 0 LOSSY / 2 UNVERIFIED = 14）的取证必须**可复现**，否则即推测预填。harness 脚本本体**仓外不入库**（`_ci-tools/_w72_tpl_safety.py`，依赖 PyPI `minijinja==2.14.0` pip 环境；先例 = 本清单对 170KB 模板「改存判定表 + 复现命令」）⇒ **本表 + 复现命令入仓即事实源**，换机器/换接手人不失联。
>
> **复现命令**：
> 1. `python -m pip install 'minijinja==2.14.0'`（与锚 #12 钉死的上游内嵌版本一致；PyPI 版 `TypeError: unexpected keyword argument` 与 Rust 内嵌版 `too many arguments` **同因不同串**）；
> 2. `python _ci-tools/_w72_tpl_safety.py --all`（~25 s；`--no-hash` 调试快跑 / `--verbose` 逐格明细）。**零网络**：模板来源只有两处 —— `_litert_forensics/_w53_containers/*.jinja`（W53 存档）/ `_research/w55_l1/*.jinja`（W55 存档）+ 盘上容器现场抽取；
> 3. 判据行 = 尾部固定输出 `DIST 10/2/0/2` + `TEMPLATE_FINGERPRINTS 12 条预设 -> 10 个不同模板`（两行供 grep）；
> 4. **锚定**：每条结论挂「容器/头 dump `sha256[:12]` + 模板指纹 `sha256[:12]`」（`TEMPLATE_FINGERPRINTS` 行内）；容器字节与预设 `sizeBytes` 逐字节相等（E4B 两条已由主理人独立核实）⇒ **换容器/换版本 ⇒ 旧结论作废重跑**。

**判定依据格 = 带数组的五格**（非数组两格 `U_str` / `S_str_U_str` 14 条全 OK，不列）：

| 格 | system | user | 期望（0.17.1 收敛语义下） | 判红条件 |
|---|---|---|---|---|
| `U_arr1` | — | `[text]` | 不抛错且文本逐字保留 | 抛 `Failed to apply template` ⇒ UNSAFE；不抛但文本被丢 ⇒ LOSSY |
| `U_arrN` | — | `[image,text]` | 同上 | 同上 |
| `S_arr_U_str` | `[text]` | string（**应用真实形态**） | 同上 | 同上 |
| `S_arr_U_arr1` | `[text]` | `[text]`（bump 后纯文本真实形态） | 同上 | 同上 |
| `S_arr_U_arrN` | `[text]` | `[image,text]`（bump 后多模态真实形态） | 同上 | 同上 |

- 逐字错串（两条同款）：`invalid operation: tried to use + operator on unsupported types string and sequence (in <string>:N)`；`N` = 模板内 `+ message(s)[…].content` 所在行（Qwen2.5 user 支 `:23` / system 支 `:16`；MiniCPM5 system 支 `:24`）。
- **空命中语义（判红）**：`DIST` 行缺失或分布 ≠ `10/2/0/2` ⇒ 结论作废重跑；某预设无 `S_arr_*` 格输出 = 只跑了 user 三格（W53/W55 旧 harness 形态）⇒ 该行作废；容器缺失且无存档模板 ⇒ `SKIP(NO_CONTAINER)`，**禁伪造结论**；无明文模板 ⇒ `UNVERIFIED(NO_TEMPLATE_IN_CONTAINER)`，禁裸留。
- **样本独立性申报**：14 预设只有 **10 个不同模板**（LFM2.5-VL 450M/1.6B/3B 三档共用同一份 882 B 模板，`sha256[:12]=f57ac3cedd00`）⇒ 任何「安全面比例」表述按**模板数**计，「10 SAFE」的样本独立性 = 9（同族同模板 = 一个证据；镜像纪律 = W55「同家族 ≠ 同模板」）。
- W72 实测：Qwen2.5-1.5B 与 MiniCPM5-2B 的 `S_arr_*` 格抛错（真机 Q4/Q5 逐字同签名）⇒ 0.18.0 下 bump 爆面 ≥2/14 的离线侧铁证；MiniCPM5 user 分支 `content=''`（`U_arr1`/`U_arrN` = LOSS）被 system 支抛错遮蔽 ⇒ W72-01 挂账依据。

## 0.18.0 离线锚批（W71，2026-10-17）

> 制品 = `com.google.ai.edge.litertlm:litertlm-android:0.18.0`（**20,905,807 B**，Last-Modified 2026-10-06 15:49:10 GMT）；jni 双 ABI = arm64-v8a + x86_64（`liblitertlm_jni.so`）。
> ⚠️ **制品位置订正**：0.18.0 **从未上 Maven Central**（repo1.maven.org 路径整组 404），只在 `google()`（Google Maven）。任何「Maven Central 查证 ⇒ 误判不可得」的推论须按此订正。
> 取证报告 = 工作区 `_research/w71-0180-anchor-batch.md`（AAR 解包 + javap + 二进制符号 grep + 上游 v0.18.0 源码三路；本仓仓库零触碰）。

| 锚 | 0.18.0 结论 | 依据摘要 |
|---|---|---|
| #1 | ⛔ **缺失** | `generic_data_processor.cc` 重写为 93 行；`MessageToTemplateInput` 覆盖整体删除；基类默认实现只调 `NormalizeMessageContent`（仅包装不收敛）；`requires_typed_content` 三文件 0 命中（字段随重构消失，非改名）。与 W56 tag `b2f686e2e` 结论一致 |
| #5 | ✅ | `ThinkingBudgetConstraint` 符号 arm64-v8a + x86_64 双 ABI 均在 |
| #12 | ✅ | `Cargo.toml` 钉 `minijinja = "=2.14.0"` |
| #13 | ✅ | `tojson` 单参签名 + `add_filter` 与 0.17.1 逐字一致（关键字参数失败面未放宽，与 #3576 bundle 层修复路径相符） |
| #2/#3/#4/#6/#7/#8/#9/#11 | ✅（#3/#4 仅静态签名面，行为归真机） | javap/字节码/源码逐项核 |
| #10/#14 | 未覆盖 | 归真机批复核（#10）/ 本仓 bump 后人工复审（#14） |

**净结论**：静态可判 12 项仅锚 #1 缺失 ⇒ **W68-06 bump blocker 从「锚 #5 未验 + 锚 #1 收敛缺失」双维持变为单维持（锚 #1）**；gallery #1326 check②（0.17.1 vs 0.18.0 复测）的**离线侧已清**——两版本静态差异 = 仅收敛语义一项。

### 0.18.0 条件真机臂（W72，2026-10-17）

> 臂 = 临时 worktree（分支 `w72-arm-0180`，**不入 improve，测后弃**）；设备 OPPO A13 `13309cc8` 有线 adb；驱动 `_ci-tools/_w72_arm_probe.py` / `_w72_arm_case.py`；原始件 `_w72_dev/`；完整报告 `_research/w72-0180-device-arm.md`。
> **取数，不是决策**：臂只回答「0.18.0 在各容器上的真实行为」，bump 本体仍须用户裁决（docs/14 C6）。

| 问 | 容器 | 0.17.1 基线 | 0.18.0 实测 | 判定 |
|---|---|---|---|---|
| 编译 | — | 可编译 | `Capabilities` 类删除 ⇒ 2 处 `Unresolved reference`（7 条错误）；迁移到 `ModelInfo.from` 后 BUILD SUCCESSFUL | **⛔ 需 2 文件迁移（锚 #15）** |
| Q1 | gemma-4-E2B-it-gpu | 引擎初始化 NOT_FOUND，会话建不起来 | `tf_lite_prefill_decode not found` 降为 **W** 并继续；`backend: GPU_ARTISAN`；会话建成；38 token @ **18.34 tok/s**（CPU 对照 0.47） | **✅ init 修复** |
| Q1′ | 同上（输出质量） | 输出退化（W43） | 正文 = `您好。…需要我能为您 after what? 请提供具体的前मोन Ally (5/2) 问题RESET Remix 上…`（跨脚本混杂） | **⚠️ 退化未修 ⇒ knownBroken 不得整体清空** |
| Q2 | MiniCPM-V-4-int8 | `Unsupported model type`，会话创建失败 | 会话建成，`{'body': '你好！有什么我可以帮你的吗？'}`，firstToken 81.8s | **✅ 修复（慢）** |
| Q3 | gemma-4-E2B-it（CPU） | 正常 | 正常（thinking + 正文，0.47 tok/s） | **✅ 无回归** |
| Q4 | Qwen2.5-1.5B | 可用（fold ON） | `Failed to apply template: invalid operation: tried to use + operator on unsupported types string and se…`，journal 587 B 无 MODEL 消息 | **⛔ 锚 #1 P0 真机实证** |
| Q5 | MiniCPM5-2B | 可用（fold ON） | 同上签名（`+` on string and seq），journal 587 B 无 MODEL 消息 | **⛔ 爆面扩至 2/14** |

**两条方法学教训（W72 新增，进交接 §六）**：
1. **日志关键词判据必须排除自家文案**。驱动脚本首版用 `NOT_FOUND` / `Unsupported model type` 做旗标，被本仓 knownBroken **预设警告文案**命中 ⇒ 一度误读为「(a) 未修复」。正确三证 = ① native/引擎侧日志行（`litert_lm_loader.cc` / `llm_gpu_artisan_executor.cc`）② journal 有无 MODEL 消息 ③ `tokensPerSecond` 量级。
2. **模板静态阅读 ≠ 运行期行为**。曾据 MiniCPM5 模板 `{%- if content is string %}…{%- else %}{%- set content = '' %}` 推演「数组 ⇒ 静默丢字」；实测是模板求值期**同步抛错**。凡「静默 vs 抛错」的形态判断，必须真机或离线 minijinja 复现。

### bump 决策树预登记（复测前写死，防结果出来后临场博弈）

> **W72 状态：分支 (b) 已由真机臂命中**（见下节「0.18.0 条件真机臂」）。以下三支保持原文留痕（预登记的价值在于「事后不得改写判据」），命中后的事实订正写在每支的行内。

- **(a) 真机证 0.18.0 仍 GPU init NOT_FOUND** ⇒ 回复上游「双版本同签名」，bump 维持单挂起（锚 #1），季扫继续。零变化。**⛔ W72 实测未命中**：`tf_lite_prefill_decode not found` 由致命错误降为 W 级 warning，`GPU_ARTISAN` 后端建成会话（18.3 tok/s）。
- **(b) 0.18.0 修好 GPU init** ⇒ bump 权衡**首次变成真实的**：收益 = gemma-4 GPU 用户故事（W68-07）+ 可能修复 MiniCPM-V-4 `Unsupported model type`；代价 = 锚 #1 收敛缺失 ⇒ Qwen2.5 系 P1 回归（fold 失效、每轮必炸）。选项空间预先写明：① bump + 对不安全模板容器启用「导入期模板探测闸门」（第十一审 E4/E6，evidenceLevel schema 已就位）；② 不 bump 继续等收敛恢复；③ 双 runtime 不可行（单依赖）。**该分叉须一次专门用户裁决（可挂 C5 追加项）**。**✅ W72 实测命中**：收益两项**均已证实**（GPU init 修复 + MiniCPM-V-4 可建会话，首 token 81.8s）；代价侧**实测加码**：① 锚 #1 真机复现（Qwen2.5-1.5B 首轮 `+` on string and seq）；② **爆面扩至 2/14**（MiniCPM5-2B 同样首轮必炸）；③ 新增**编译级**代价（锚 #15：`Capabilities` 类删除 ⇒ 2 文件迁移）；④ `gemma-4-E2B-it-gpu` 的「输出退化」半边**未修**（跨脚本混杂仍在）⇒ knownBroken **不得整体清空**，须订正为「仅输出退化」。裁决项见 `docs/14` C6。
- **(c) 0.18.0 恢复收敛**（低概率；W56 源码 grep 零命中 + 本次锚批确认删除）⇒ bump 前置④转绿，走完整前置清单。**⛔ W72 实测未命中**：Qwen2.5 / MiniCPM5 首轮均 `Failed to apply template`。

## 已知盲区登记簿（W71 C4，v23 M14；防「探针 PASS = 健康证明」误读）

> 收录「现役检测面**确实不覆盖**的形态」——与判据失效无关，是成本受控设计下的已知边界；新增盲区须在此登记（散落各 KDoc 的申报以此簿为聚合面）。

| # | 盲区 | 申报位置 | 现状 |
|---|---|---|---|
| 1 | 探针对**多语种退化输出**假阴性：`gemma-4-E2B-it-gpu` 输出「我cannOlivКи blockchains…」（跨脚本混杂）被现役判据全漏、探针判 PASS | `ModelHealthCriteria.kt` KDoc（W70 留痕） | W71 P-1 离线判分给出正交候选判据（非必要脚本混合计数，退化样本命中/健康零误报，n=2），**待数据面补齐后另立项**（`_plans/w71-p1-scriptmix-report.md`） |
| 2 | 端侧 2B「提醒后直答」不合规：gemma-2B 6 触发 0 合规 ⇒ 模型直答腿判死 | W68-10（closed-dead 留痕） | 已判死；重启 = 更强模型入册时重开 |
| 3 | 双口径 token 对照观测力弱：`cumulativeIn`/`cumulativeOut` 是本仓自算（`TokenEstimator` 估算 + 内容 chunk 帧计数），引擎从不回报 usage ⇒ 「估算 vs 真实」承诺不成立 | README Token 账本行 + `TokenUsage.estimated` 沿袭 | 沿袭挂账（F4 三级文字 token 统一同面） |

> 已消解（勿再当盲区引用）：gpuClause「零行使」—— W70 Y3 真机逐字命中 + GPU→CPU 回落闸同场实证，三道防线联动已行使。

## 复核流程

1. 新 tag 发布后：`git grep` 核对 #1 收敛语义（`MessageToTemplateInput` / `requires_typed_content` 是否回归）→ 直接决定 bump 是否阻断；
2. 解包 AAR 核对 #5 `.so` 符号、#6/#7/#11 Java API 签名；
3. 离线复现 #2/#12/#13（minijinja 行为基线 + filter 签名集：`tojson(ensure_ascii=…)` 是否仍因**关键字参数**报错 —— PyPI 版 `TypeError: unexpected keyword argument` / Rust 内嵌版 `too many arguments`）；
4. 真机批复核 #3/#9/#10（thought 通道声明、preface 诊断、模板失败路径）；
5. **复审锚 #14**（W68 B3 增补）：逐条核对 `ModelPreset.appliesToRuntime`（现全 = `"0.17.1"`）——上游已修容器 ⇒ 清空对应 `knownBrokenOn`（恢复推荐资格）；`ModelGpuSupport.gpuVerifiedFileNames` 须**复跑真机验证**后逐个放行（勿顺手放行）；
6. **跟踪 #3576 issue 状态**（W70 C1 B1 销账判据，锚 #13 同源）：bump 季扫时核对 #3576（MiniCPM5 bundle 模板/元数据根因）是否已发布修复 bundle 或运行时放宽 `tojson` 签名；命中 ⇒ 按锚 #13 复核要点（新 bundle 修复模板/元数据 ⇒ 原生工具通道真机复验）执行后再销账；
7. 全部打勾 + 守卫 #29 期望值同步更新后，方可动 toml 版本行。
8. **0.18.0 离线锚批已完成（W71，2026-10-17）**：静态 12 项结论与 bump 决策树 (a)/(b)/(c) 见上节；剩余未覆盖 = #10（真机失败路径）/ #14（bump 后人工逐条复审）。**若真机臂成立（另立项）**：先复测 gemma-4 GPU 双变体 init（答维护者 NOT_FOUND 是否修复），同臂顺带跑 W51 三工具结果原题面（collapse 首跑判据）。
9. **修 system 分支必须同步验 user 分支**（W73 V-4）：模板数组取证必须覆盖 `systemInstruction` 维度——只验 user 三格会**系统性高估**安全面（W53/W55 旧 harness 事故同型：MiniCPM5 曾被判「安全」）；判定依据格 = 带数组的五格（见「七格取证判定表」节），只修 system 分支不修 user 分支 = 把容器从可见失败推进不可见失败（W72-01 挂账面）。
10. **bump 季扫清单（W73 新增行）**：每次季扫核对三项 —— ① #3576（MiniCPM5 bundle 模板/元数据修复）② 0.18.0 单元素收敛回归 issue（`docs/14` §B3，提交后回贴 URL）③ 上游 MiniCPM5 模板变更（新 bundle / 新 tag）；**命中任一项 ⇒ 先重跑「七格取证判定表」再按对应锚要点执行**；**空命中语义 = 三项均无变更 ⇒ 记录「无变更」并维持 W68-06 blocker（锚 #1 收敛缺失）**，0 命中 ≠ 通过。

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

#### W73 K4 移入：`THINKING_CHARS_PER_TOKEN_CEIL` KDoc 推导长段归档（原文照搬）

> **移入缘由（K4 触发制件）**：`ThinkingBudget.kt` 总行数 128/150 余 22 = 行数表全表最紧引信，而 bump 准备 / C6 材料回填都要写此文件 ⇒ W73 把该常量 KDoc 的推导长段（约 43 行）整体搬迁至本归档段，文件内只留结论 + 文字版复现规程指针（出落户内自查：文件约 129 → 约 90 行，见 `bash scripts/line-table.sh`）。
> **出处**（原文照搬；W64 K-1 / W65 D1 / W66 E5 / W67 B1 四波沉淀）：`core-engine/src/main/java/com/rickeal/agent/core/engine/local/ThinkingBudget.kt` 中 `THINKING_CHARS_PER_TOKEN_CEIL` 常量的 KDoc（W73 移出前版本）。⚠️ 文件顶部「三处同步单」纪律块（core-engine 常量 / core-model 字面量 / 双不变式测试 / 轴档案）按计划**禁搬**，仍留文件内。

thinking 字符预算相对 native token 预算的**字符当量上界系数**（chars/token，W64 K-1 治本；W67 B1 按离线重测结论重新标定 16 → 9）。

出处与推导（禁无据拍值）：

- **W62 原始上界 8.4 已被 W67 离线重测证伪（口径伪影）**：`README.md` W62 段「2048 字符 ≈ 244 token」的 244 不可能是 thinking token 数 —— 引擎 `completionTokens` 实为**内容 chunk 数**、且 thinking chunk 根本不计入（见 `LiteRtLmEngine` 的 usage 计算）；同样本用 gemma 官方 tokenizer 离线精确计数：2051 字符 = **469 token**（r = 4.37），2010 字符 = 495 token（r = 4.06）；换 Qwen tokenizer 复核 464 / 479 token（r ≈ 4.2–4.4）⇒ 两种 tokenizer 下 8.4 均不成立。
- **W67 全量实测 r_max = 5.06**（W62/W65/W66 三波 31 个 thinking 样本，禁挑样本：R1 中文思维链 1.35–2.26、gemma 英文思维链 3.0–5.06；≥500 字符主样本段 r_max = 4.53）。方法学自证：W66 E5 落档 4 值（318 / 268 / 330 / 1042）被同一脚本逐位复现 ⇒ 尺子可信；
- ×≈1.8 余量 ⇒ 取整 **9**；
- **故障不对称**：系数取小 ⇒ 字符守卫早于 native 抢跑（= W62 病灶：native 优雅路径被硬截）；取大 ⇒ 退化为纯兜底（native 先优雅收尾）⇒ 必须**向上**取。

**安全窗口**（W65 D1 前半立、W67 B1 按实测重标定）：`C ∈ (r_max, 2×r_max) = (5.06, 10.11)`，其中 r_max = W67 离线实测 chars/token 上界 5.06：

- **下界**（C > 5.06）：健康 native 下阈值 = budget×C > budget×r_max ≥ thinklen ⇒ 守卫数学不可达，不误触发（W62 病灶防线）；
- **上界**（C < 10.11）：native 失效（bump 删符号 / .so 缺编）时思考可吃满 maxTokens，超阈需 r > C/2 = 4.5 —— 实测英文思维链 profile（r ≈ 4.5–5.1）恰好仍可触发 ⇒ 兜底能力不失；
  - ⚠️ **轴档案条件化**（W65 M-1）：`configured > 0`（R1/MiniCPM5 = 1536，maxTokens=2048）时失效兜底阈 = C×budget/maxTokens ⇒ r > 9×1536/2048 = **6.75** —— 高于 W67 实测 r_max 5.06 ⇒「判定③ = native 失效兜底」对两个轴档案**条件性收窄**（同型样本在新预算档不触发思考判据，由锚 #5 墙钟 + N-2 warn（`thinkingOnlyEmptyDiagnostic`，全仓唯一生产落点）兜底承担）。半预算档（configured=0）不受影响，仍为 r > 4.5；
- **C=9 的取法**：窗口上部带余量取值（= 1.78×r_max，与原设计 16/8.4 = 1.90× 同量级）。不取 C=10：贴上界 2×r_max = 10.11 仅 0.11 余量（取整即出界），且半预算兜底阈 r > 5.0 ≈ 实测上界 ⇒ 兜底对已实测 profile 失效；不取 C≤8：对未实测文本的守卫余量低于原设计 Ratio，「向上取」纪律不允许。

🔴 **r 的测量方法（W66 E5 落档、W67 B1 完成首次全量重测）** —— 历史两个软样本（W62 的 8.4 / W65 补丁臂的 2.0）已淘汰；现役口径：

1. **native usage 分通道：⛔ 不可用** —— 引擎 `completionTokens` 实为内容 **chunk 数**（见 `LiteRtLmEngine` 的 usage 计算），**无 thinking 通道 token 数**；
2. **离线 tokenizer 计数（现役硬证据）**：从 journal 抽 thinking 全文，用模型官方 tokenizer（gemma 侧 / Qwen 侧各一份，W66 E5 同款资产）**离线精确计数**，得 `r = thinking 字符数 / thinking token 数`。W67 复测脚本 `_ci-tools/_w67_r_retest.py`（仓外 CI 工具，结论表见 W67 回报）：全量 31 样本，与 W66 E5 落档值逐位复现；
3. **`TokenEstimator.estimate` 估算：仅旁证** —— 字符启发式（英文 3.2 字/token、CJK 1 字/token），**不得当硬证据**。

判定口径（已执行）：W67 实测 r_max = 5.06 ⇒ 历史声称的 8.4 系口径伪影（证伪）⇒ 窗口下界 8.4 → 5.06、C 16 → 9（同步单见原文件顶部注释块）。

> **回落判据**：本归档段为历史推导存档；现役口径以 `ThinkingBudgetInvariantTest` 钉住的常量（C = 9 / 窗口 (5.06, 10.11) / `THINKING_CHAR_BUDGET` = 4608）与代码为准，冲突时以现役值为准并回查本段。文件内指针保留**文字版复现规程**（journal thinking 全文 + 官方 tokenizer 离线计数），不依赖仓外脚本存活。

#### K2 层5 压缩门控判死结论（W73 主理人终裁）

> **W73 裁决：判死（closed-dead，台账 `docs/15-backlog.md` W73-01）**。判死措辞写死防临场博弈：压缩触发恒由估算开窗（W41）驱动；token 实测账本维持只写不读（读侧投影定位不变，Wave 30 §2.3 明立「账本只做读侧投影，门控口径不接」= 设计决策非疏漏）；**重启前提 = 出现估算失准事故（压缩时机明显失准的实证）**。
>
> 依据：① 现役压缩由 W41 估算开窗驱动且无估算失准事故记录；② 接线解（runMutex 重入：门控检查移锁外 / channel 化二选一）动核心循环最精密并发面，风险/收益严重不对称（W69 拆 `ToolExecutionGuard` 前因）。代码侧两处 KDoc 自陈（`AgentRunner` 记账块 / `RunTokenLedger` 消费顺序约定）已同步补裁结论句——**只加句、不改原自陈事实**。

—— W59 A5 成文（方案席沈思远定稿 / 开发席柯码成落地）；W66 A5 追加 r 测量方法；W67 B1 回填重测结论；W68 B3 增补锚 #14（knownBroken / GPU 白名单运行时时效复审）；**W73 K4 追加 `THINKING_CHARS_PER_TOKEN_CEIL` KDoc 推导长段归档（「W73 K4 移入」小节，原文照搬 + K4 瘦身指针互换）**；**W73 A5 追加七格取证判定表节 + 锚 #13 复核要点 + 复核流程第 9/10 条（V-4 system/user 同步验 + 季扫清单）+ K2 判死结论段（W73-01）**。
