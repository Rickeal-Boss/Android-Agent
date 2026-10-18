package com.rickeal.agent.core.model

/**
 * 内置模型预设：**只收录 Google 官方 `litert-community` 组织下真实存在的文件**，
 * 链接与体积均按 Hugging Face API 核对过（2026-09 收录；2026-09-24 全量复核体积并增补
 * 5 条视觉多模态预设与国内镜像源），不使用社区随机仓库，避免下到损坏/来路不明的权重。
 *
 * ## 下载镜像（2026-09-24 增补，服务国内用户）
 *
 * 每条预设 = 主源 HuggingFace + 两路国内镜像（UI 可一键换源，直链均为 https）：
 *  - `hf-mirror.com`：HF 全站 1:1 代理，路径规则与主源完全一致（仅换域名），
 *    resolve 直链实测 302 → CDN 直通，无需逐仓维护。
 *  - `www.modelscope.cn`：**逐仓核实过文件真实存在**（2026-09-24，13/13 仓全过，
 *    含 gemma-4 全系），LFS 直链 `resolve/master/<file>` 实测 200。魔搭没有全站
 *    代理语义，仓库缺失就是死链 —— 新增镜像前必须先打 repo/files API 核实。
 *
 * ## 视觉多模态预设的取舍（2026-09-24 帕累托前沿调研结论）
 *
 *  - **只收 `.litertlm` 官方转换件**：引擎只吃这个格式，原始权重再强也进不了推理链路。
 *  - LFM2.5-VL 系列收 **`_fixB` 修复版**：stock int4 的 vision graph 有「模型只能看到
 *    图片顶部 1/4」的已知 bug（上游 LiteRT-LM#3246），fixB 把 2×2 pixel-unshuffle
 *    重导出进编码器后整图可见，体积几乎不变。
 *  - **刻意排除**（都是想清楚才不收，别顺手加回来）：
 *    - `google/gemma-3n-E2B/E4B-it-litert-lm`：HF `gated: manual`，DownloadManager
 *      不带访问令牌，收了就是 401 死链（本应用不做 HF 授权流程）。
 *    - `InternVL3_5-1B/2B/4B`：转换件文件名固定 `model.litertlm`，多条预设重名会触发
 *      DownloadManager 的 `model-1.litertlm` 改名 → [findByFileName] 失配，内存闸门
 *      退化为无预设估算；且同档位体积/能力被 LFM2.5-VL 压制。
 *    - `FastVLM-0.5B` / `LLaVA-OneVision-0.5B`：与 SmolVLM2-500M、LFM2.5-VL 同档，
 *      帕累托被压制（体积更大或能力更弱），收录只会稀释选择。
 *  - **引擎版本状态（2026-09-26 Wave 20）**：litertlm-android 已从 0.11.0 升至 **0.17.1**
 *    （MiniCPM5-2B 官方要求 ≥0.16；0.17.1 同时开放 RepetitionPenaltyConfig/ThinkingConfig，
 *    见 libs.versions.toml 注释）。VL 系官方验证运行时为 0.15/0.16，版本前提已满足；
 *    但「视觉后端 GPU + 解码」的组合在真机上**仍未实测**，GPU 白名单对 VL 系保持关闭，
 *    真机验证后逐个放行。文本系 MiniCPM5 有 README 级 GPU 证据，已随升级放行。
 *
 * 格式说明（很重要）：
 *  - `.litertlm` = 新版 LiteRT-LM 运行时格式，本项目引擎直接支持。
 *  - `.task`    = 旧版 MediaPipe / LiteRT Task 格式，仅作兼容导入，推理行为可能受限。
 *
 * ## 关于「建议可用内存」的口径（务必读完再改数字）
 *
 * 估算公式：**(W × f_backend + KV(n) + O) × 1.25**
 *   - `W` = `.litertlm` 文件体积（权重）
 *   - `f_backend` = CPU 1.05 / **GPU 1.25** / NPU **未实测**
 *     —— 系数必须**区分后端**：GPU 后端除常驻权重外还要额外承担 OpenCL buffer 与可能的 fp16 权重副本。
 *     注意 GPU 变体的**文件更小**（E2B-GPU 1.87GB < CPU 版 2.41GB），那是重新打包省的磁盘体积，
 *     **不是运行内存**。早期版本用统一的 1.5× 估算，导致 GPU 变体低估约 20~27%。
 *   - **NPU 刻意不给具体数字**：NPU 峰值约等于「权重 + N × HTP scratch」，而 scratch 是 GB 量级的，
 *     实际需求几乎必然**高于** GPU。曾写过的 NPU 1.15（比 GPU 还低）是危险方向 —— 低估会让内存
 *     闸门放行、加载时 native 崩溃，而不是把用户拦下。所以需要时按「**不低于 GPU**」处理，等真机
 *     测出 scratch 开销再校准。**别为了"看起来完整"补一个未经验证的倍率。**
 *   - `KV(n)` = 2 × L(层) × H_kv(kv 头数) × D(head_dim) × n(上下文 token) × 2 字节
 *   - `O` = max(200MB, 0.12 × W)（运行时 + prefill 激活）
 *   - `×1.25` = Android 安全余量（无 swap + LMK + App 自身 150~300MB）
 *   - **没有预设可对时**（SAF 导入的自定义模型、或因重名落成 `name-1.ext` 的下载），
 *     `ModelsViewModel.estimateRequiredRamBytes` 用同一条式子但**没有 KV 项**，于是系统性
 *     偏乐观，由调用方把尾部余量从 `1.25` 换成 `1.41`（常量
 *     `ModelsViewModel.NO_PRESET_KV_COMPENSATION`）。它不是随手定的：令
 *     `(W·f+O)·c = (W·f+KV+O)·1.25` 得 `c = 1.25 × (1 + KV/(W·f+O))`，按下面各条反算右边，
 *     最大缺口仍出现在 E2B·GPU（KV 0.318 / 底 2.562）≈ **1.4052**（2026-09-24 增补的
 *     5 条视觉预设最高只到 1.4033·MiniCPM-V-4，未越界），向上取整即 1.41。
 *   - **这个余量只作用于无预设那一支**：有预设时预设才是权威值（`maxOf(preset, 估算)`，
 *     估算保持 `×1.25`）。给有预设的模型也用 1.41，会把闸门抬到预设之上 —— 例如
 *     Phi-4-mini 会从 5.6 被抬到 6.0 GiB，把刚消掉的「大模型误拦」加回来，而且卡片上
 *     「≥ 5.6 GB」的文案会和实际闸门对不上。**改这里的系数要同步改那边，反之亦然。**
 *   - 尾部余量作用在 2GB 下限**之前**：下限代表不随权重缩放的固定开销，余量补的是 KV(n)，
 *     两者不该相乘（否则无预设的小模型下限会变成 2.82 GiB 而不是 2.00）。
 *
 * 两点必须避开：
 *  1. **不要拿 Edge0 披露的 2.9GB / 1.0GB 反推系数** —— 那是 MoE + SSD 流式加载下的
 *     "Measured peak **activation** memory"，**不含权重驻留**，与我们的 dense 全量常驻口径完全不同。
 *  2. 本口径仅适用于 **4k 上下文**；上下文调大需按上面 KV(n) 线性上调。
 *
 * 这些是**估算值**，真机实测后应回填 `ModelDescriptor` 的实测字段。
 *
 * 校准锚点（Google 官方 `google-ai-edge/gallery` 的 `model_allowlist.json`，含
 * `estimatedPeakMemoryInBytes` 字段）：
 *   - Gemma 3n E2B int4：2.92GB 权重 → 峰值 5.5GB（≈1.9×）
 *   - Gemma 3n E4B int4：4.10GB 权重 → 峰值 6.5GB（≈1.6×）
 *   - Gemma3-1B q4   ：0.52GB 权重 → 峰值 2.0GB（≈3.9×，小模型由固定基线主导）
 * 结论：中大型模型用 1.6~1.9× 是准的，但**小模型不能按体积线性缩放**，
 * 必须设下限（约 2GB），否则会低估到以为「512MB 模型谁都能跑」。
 */
/**
 * 预设的备用下载源（国内镜像直链）。主源永远是 [ModelPreset.url]（HuggingFace）；
 * 镜像与主源**逐字节同源**（hf-mirror 是代理、ModelScope 是镜像仓），所以文件名、
 * 体积、内存闸门口径完全一致 —— 换源不影响 [ModelPresets.findByFileName]。
 */
data class ModelPresetMirror(
    /** UI 换源行上显示的短标签。 */
    val label: String,
    val url: String,
)

/**
 * 预设证据等级（W66 B3，Q6「预设证据数据化」）：声明该预设的可用性 / 后端结论有多少
 * **仓内可查**的证据支撑。取值必须能从本条预设的 `backendBasis` / `note` 文本**直接读出**，
 * 禁止凭推测预填（与 [ModelPreset.knownBrokenOn] 同款硬前置）。
 *
 *  - [MEASURED]：**本条文本内**有本仓真机 / 离线取证记录 —— 锚点 = 本条 `backendBasis` /
 *    `note` 内出现 `W##` 波次实测标记（W43/W56/W57/W60 等）。**由守卫 #39 机械强制**：声明
 *    MEASURED 但本条文本内无 `W##` 锚点 ⇒ 判红（防「推测预填」把没测过的说成测过）。
 *  - [UPSTREAM]：**本条文本内**无本仓实测锚点，依据来自上游文档 / 官方基准（官方 README /
 *    官方 GPU 基准 / Gallery allowlist）。例：GPU 行只有官方桌面基准、无 Android 验证 ⇒ 仅 CPU。
 *    ⚠️ **语义边界（勿误读）**：本等级**只说明「本条文本内没有本仓锚点」**，**不等于「本仓从未
 *    测过该模型」** —— 例：`MiniCPM5-2B_int4` 标 UPSTREAM（其 GPU 放行依据是上游 Galaxy S26
 *    基准），但该模型在本仓另有 W44/W47/W48/W49 真机史，只是**未写进本条文本**。要把这类条目
 *    升为 MEASURED，须**先把锚点回填进本条 `backendBasis`/`note`**（本字段度量的是「文本内的
 *    证据」，不是「仓库的测试历史」）。
 *  - [UNVERIFIED]：本条文本内查不到证据（不得猜）。**新预设默认此级**，补证据后升级。
 *
 * ⚠️ 本字段**不参与**内存闸门 / GPU 放行判定（那些由 `requiredRamBytes` / [ModelGpuSupport]
 * 驱动）；它只作「结论的可信度标注」。
 * ⚠️ **接线状态（W66 深审 P1 申报，勿当已交付功能）**：本字段目前**只做数据化 + 守卫**，
 * **尚无 UI 消费方**（`ModelCard` 展示的是 `backendBasis` / `knownBrokenOn`，不含本字段）
 * ⇒ 属「🟡 已实现未接线」，已登记 README 挂账台账（重启前提 = 模型卡展示 evidenceLevel）。
 */
enum class EvidenceLevel {
    /** 本条 `backendBasis` / `note` 含 `W##` 波次实测锚点（本仓真机 / 离线取证有记录）。 */
    MEASURED,

    /** 本条文本内无本仓锚点，依据来自上游文档 / 官方基准（≠ 本仓从未测过该模型）。 */

    UPSTREAM,

    /** 仓内无证据（禁推测；新预设默认此级）。 */
    UNVERIFIED,
}

/**
 * 该容器的 `chat_template` 对**数组形态 content** 的处理结论（W72 D 数据件，U-1）。
 *
 * ## 为什么需要它（标记体系的结构性缺口）
 *
 * 本仓此前只记「安全」（`backendBasis` 里写「模板已离线取证数组安全」），**不记「不安全」**
 * —— 而 litertlm bump 决策需要的恰是后者：`0.18.0` 删掉了「1 元素 text 数组 → string」
 * 的收敛点（`docs/12` 锚 #1），于是**所有**「靠收敛兜住」的模板会在 bump 后集体炸。
 * 要判断「bump 会炸哪些」，就必须有一条能机械读出的「数组安全」结论。
 *
 * ## 四态（判据必须可机械区分，⛔ 不许合并）
 *
 * - [SAFE]：**七格**均不抛错且文本**逐字保留**。七格 = `U_str` / `U_arr1` / `U_arrN`
 *   （无 system）+ `S_str_U_str` / `S_arr_U_str` / `S_arr_U_arr1` / `S_arr_U_arrN`
 *   （system 侧 × user 侧三形状）—— ⛔ **不是干净笛卡尔积**（system 为 string 时只配
 *   user=string），格名以 harness 输出为准。判定依据格 = 带**数组**的五格。
 *   ⚠️ **取证必须覆盖 `systemInstruction` 维度** —— W53/W55 当年的 harness 只喂 user
 *   消息，因此**系统性高估**过安全面（MiniCPM5 就是这么被判成「安全」的，实际 system
 *   分支会抛错）。只做 user 三格的结论**不足以**判 SAFE。
 * - [UNSAFE]：任一格抛 `Failed to apply template`（**可见失败**：报错 + 日志 + 无输出，
 *   用户立刻知道）。这类容器「坏得诚实」。
 *   ⚠️ **边界（W73 V-1 订正，补段不删原描述）**：本态描述**容器裸模板**性质；0.17.1 下
 *   不表现（`foldAdjacentText` 收单元素 + 收敛点降 string ⇒ 被完整绕过，两条 UNSAFE
 *   现役可用）；bump 删收敛后才暴露 ⇒ 本字段是 bump 决策数据，**不是当前可用性判据**。
 * - [LOSSY]：不抛错，但文本被**丢弃**（**不可见失败**）。
 *   ⚠️ **现役 0 命中**，但这一态必须保留：`MiniCPM5-2B` 当前判 [UNSAFE]（system 分支
 *   抛错），而它 user 分支的 `content=''` 性质**真实存在且被 system 侧的报错遮蔽**。
 *   一旦谁只修 system 分支，这个容器就会从「可见失败」滑进「不可见失败」—— 而现有
 *   **任何**检测面（探针 / logcat / 输出判别）都看不见文本被丢这件事。
 *   保留 [LOSSY] 的唯一目的 = 让那次滑动在数据里留下可判的落点。
 * - [UNVERIFIED]：无证据。**必须在 `backendBasis` 或 `note` 写明理由类别**，禁裸留：
 *   - `NO_TEMPLATE_IN_CONTAINER`：容器里没有明文 `chat_template`（走运行时内建模板）
 *     ⇒ 容器侧七格取证**方法不适用**（≠「还没做」）；
 *   - `NO_CONTAINER`：连制品都没有（无文件可扫）。
 *
 * ⚠️ 本字段与 [EvidenceLevel] **正交**：前者度量「模板对数组的处理」，后者度量「本条
 * 文本内的证据等级」。一个容器可以 `evidenceLevel=MEASURED`（真机跑过）同时
 * `templateArraySafety=UNVERIFIED`（没做模板七格取证）—— 别互相推断。
 *
 * 取证 harness（W72 方案席，**仓外**不入库）：`_ci-tools/_w72_tpl_safety.py` —— 七格
 * （格名与构成见上）逐格喂入并比对输出文本。复现
 * 结论或新增预设时**用它跑，不要凭模板文本推断**（`+` 拼接在 string 分支合法、在数组
 * 分支抛错，纯读代码很容易看漏 system 分支）。
 *
 * ⚠️ **接线状态（W72，勿当已交付功能）**：本字段目前**只做数据化 + 守卫**，**无 UI
 * 消费方**（模型卡不展示）⇒ 属「🟡 已实现未接线」，与 [EvidenceLevel] 同款挂账。
 * 重启前提 = 模型卡展示本字段 / bump 前置清单消费它。
 */
enum class TemplateArraySafety {
    /** 七格（见枚举 KDoc）均不抛错且文本逐字保留。判定依据格 = 带数组的五格。 */
    SAFE,

    /** 任一格抛 `Failed to apply template`（可见失败）。 */
    UNSAFE,

    /** 不抛错但文本被丢弃（不可见失败）。现役 0 命中 —— 保留本态是为了捕获「只修 system 分支」的滑落。 */
    LOSSY,

    /** 无证据（禁推测；理由类别须写进 `backendBasis` / `note`）。 */
    UNVERIFIED,
}

data class ModelPreset(
    val label: String,
    val url: String,
    val sizeText: String,
    /** 面向用户的「建议可用内存」下界文本。 */
    val ramText: String,
    /** 加载前内存闸门用的阈值（字节）。 */
    val requiredRamBytes: Long,
    /** 下载体积（字节），用于下载前的存储空间检查。主源与镜像同文件，体积一致。 */
    val sizeBytes: Long,
    /** 国内镜像直链（hf-mirror / ModelScope），2026-09-24 起全预设配齐；主源不在列。 */
    val mirrors: List<ModelPresetMirror> = emptyList(),
    /** 是否推荐给新手（排序时置顶，UI 打「新手推荐」标识）。 */
    val recommended: Boolean = false,
    /**
     * 数字是怎么来的，写进数据里，后人才能校准。
     *
     * **两种口径，可机械区分**（W68 A2，复检 Q-1）：
     *  - **公式口径**：值为 [BASIS_CPU] / [BASIS_GPU]（`W×f_backend+KV@4096+O，×1.25`
     *    形态）⇒ 该 `requiredRamBytes` 由估算式算出，**未经真机峰值实测**；含 KV 项但
     *    系数是经验值（历史上对 E4B 单文件低估 ~14%，实测样本见
     *    `ModelsViewModel.estimateRequiredRamBytes` KDoc「已知偏乐观」段 —— 另一模块，
     *    此处不重复数值）。
     *  - **实测口径**：值含「实测」字样与 `W##` 波次锚点（如 [BASIS_CPU_E4B_MEASURED]，
     *    格式 `W67 实测峰值 ×1.25（A13 13309cc8：…PSS 5.66GiB…）`）⇒ 该阈值锚定**真机
     *    dumpsys meminfo 峰值**，后人校准时优先补这类样本（W69-02 挂账）。
     *
     * ⚠️ 读表诀窍：`memBasis` 以「实测」二字 + 波次锚点区分两种口径 —— 现役 14 条中
     * **4 条**不是纯公式口径：3 条实测（[BASIS_CPU_E4B_MEASURED] E4B 单文件 /
     * [BASIS_CPU_PHI4_MEASURED] Phi-4-mini / [BASIS_GPU_E4B_MEASURED] E4B·GPU）+
     * 1 条**显式标注「执行失败不可测」**（MiniCPM-V-4，字面量串，W71 实测批如实申报）；
     * 其余 **10 条**为公式口径（[BASIS_CPU] / [BASIS_GPU] 或逐条字面量估算）。
     * ⚠️ 「不可测」条目的数值仍是公式值，**不是实测** —— 它只是把「为什么没有实测」写进
     * 数据，别当成第 4 条实测样本读。
     */
    val memBasis: String,
    val note: String,
    /** GPU 放行/禁用的依据与警示（模型卡展示）。空串 = 无特别说明。 */
    val backendBasis: String = "",
    /**
     * 该容器在当前运行时（litertlm 0.17.1）的**已知失败签名**；null = 未见已知失败。
     *
     * 取值必须来自**真实失败证据**（真机实测 / 容器取证，出处 = 本文件各条目 note /
     * backendBasis 的 W43/W60 实测记录），禁止凭推测预填 —— 本字段驱动模型卡红字
     * 硬提示与加载前确认对话框，标错会把可用模型吓跑。
     *
     * 不变量：`knownBrokenOn != null` ⇒ `recommended == false`（已知坏容器不得置顶
     * 推荐），由 scripts/arch-guard.sh 第 34 条强制。上游修复 + 真机复验通过后**清空
     * 本字段**即恢复推荐资格（不要只改 recommended 留着红字段）。
     */
    val knownBrokenOn: String? = null,
    /**
     * 该「已知坏」结论**适用的运行时版本**（W68 B3，第十二审 R-1 时效字段，可机读）。
     *
     * ## 为什么需要它（时效性）
     *
     * `knownBrokenOn` 是**某一次运行时下的实测结论**（如「0.17.1 引擎初始化 NOT_FOUND」）。
     * 上游 litertlm bump 后，被修的容器可能**集体过期** —— 届时这些「已知坏」不再是事实，
     * 若还照旧红字硬提示，会把已被上游修好的可用模型吓跑。本字段把「结论针对哪个运行时」
     * 从 note 自由文本里**提出来变成机器可读**，供 bump 后机械复审（复核锚见
     * `docs/12-litertlm-0171-anchors.md` 的「knownBroken / GPU 白名单过期复审」条目）。
     *
     * ## 取值与不变量
     *
     * - 取值 = litertlm 版本串（**人工维护**；当前 `"0.17.1"`）。⚠️ 本字面量与
     *   `gradle/libs.versions.toml` 的 `litertlm` 行**无机械联动** —— 守卫 #29 只钉 toml
     *   一行、**不校验本字段**（本字段是**第 3 处**版本字面量）。故 bump litertlm 时须
     *   **人工**同步本字段，否则复审锚会指向错版本。**不引入第二处版本常量**（避免双源
     *   漂移）；bump 时由守卫 #29 报错提示 + `docs/12` 复审流程兜底。
     * - 不变量：`appliesToRuntime != null` ⇒ `knownBrokenOn != null`（时效字段是「坏结论的
     *   定语」，无坏结论却标适用版本无意义 —— 由 `ModelPresetsKnownBrokenTest` 机械查验）。
     * - `knownBrokenOn == null` 的条目本字段恒 `null`（禁预填）。
     *
     * ⚠️ **本字段当前不参与判定**（GPU 放行 / 内存闸门 / 激活态重校验均只看 `knownBrokenOn`
     * 与 [ModelGpuSupport]）；它只作「结论的时效标注」，bump 后据以**人工/机械复审**是否清空
     * 已过期的 `knownBrokenOn`。
     */
    val appliesToRuntime: String? = null,
    /**
     * 本条预设结论的**证据等级**（W66 B3，Q6）：见 [EvidenceLevel]。
     *
     * 取值必须从本条的 `backendBasis` / `note` 文本读出，**禁止推测预填**；查不到证据留
     * [EvidenceLevel.UNVERIFIED]。`ModelPresets.all` 里**每条预设都必须显式赋值**
     * （守卫 #39「全 schema」强制：缺赋值 / 未知等级 / MEASURED 无 `W##` 锚点 ⇒ 判红）。
     */
    val evidenceLevel: EvidenceLevel = EvidenceLevel.UNVERIFIED,
    /**
     * 该容器 `chat_template` 对数组形态 content 的处理结论（W72 D 数据件，U-1）：见
     * [TemplateArraySafety]。
     *
     * `ModelPresets.all` 里**每条预设都必须显式赋值**（守卫 #39「全 schema」强制：
     * 缺赋值 ⇒ 判红）。`UNVERIFIED` 虽是默认值，但**必须**在 `backendBasis` 或 `note`
     * 写明理由类别（`NO_TEMPLATE_IN_CONTAINER` / `NO_CONTAINER`），否则视为漏做。
     *
     * 现役 14 条分布（W72 方案席 harness 七格取证定稿，10 个模板指纹）：
     * **10 [TemplateArraySafety.SAFE] / 2 [TemplateArraySafety.UNSAFE] /
     * 0 [TemplateArraySafety.LOSSY] / 2 [TemplateArraySafety.UNVERIFIED]**。
     * ⚠️ 改这个分布必须同时改本条计数句，否则守卫与文档会互相打脸。
     */
    val templateArraySafety: TemplateArraySafety = TemplateArraySafety.UNVERIFIED,
) {
    // ⚠️ W72 删除 `val gpuSupported: Boolean get() = ModelGpuSupport.gpuVerifiedFileNames.contains(...)`：
    // W69 C5 把判定源同源化到 `ModelGpuSupport.isGpuVerified(fileName)` 之后，该属性**零生产
    // 消费方**（全仓 grep 只剩本文件定义 + 一句注释），悬置三波即僵尸字段。GPU 放行判定**只有**
    // 一个入口 = `ModelGpuSupport.isGpuVerified(fileName)`（白名单口径）—— ⛔ 不要在此处重新
    // 加一层「预设侧的二次投影」，两层判定必然漂移（漂移的代价是 native 崩溃，Kotlin catch 不住）。
    /** 全部可用直链：主源在前，镜像在后。 */
    val allUrls: List<String>
        get() = listOf(url) + mirrors.map { it.url }

    /** 该直链是否属于本预设（主源或任一镜像）—— 换到镜像源仍是同一个模型。 */
    fun ownsUrl(url: String): Boolean = allUrls.contains(url)
}

object ModelPresets {

    private const val BASE = "https://huggingface.co/litert-community"

    // 国内镜像根。hf-mirror 是 HF 全站 1:1 代理（换域名即得）；ModelScope 镜像仓
    // 逐仓核实过（2026-09-24），且魔搭默认分支是 master 而非 main。
    private const val HF_MIRROR_BASE = "https://hf-mirror.com/litert-community"
    private const val MODELSCOPE_BASE = "https://www.modelscope.cn/litert-community"

    /**
     * 生成两路国内镜像直链。**调用前必须先用魔搭 repo/files API 核实该仓库与文件
     * 真实存在**（本文件全部预设已于 2026-09-24 逐仓核验），禁止凭感觉补链接。
     */
    private fun domesticMirrors(repo: String, file: String): List<ModelPresetMirror> = listOf(
        ModelPresetMirror("hf-mirror · 国内加速", "$HF_MIRROR_BASE/$repo/resolve/main/$file"),
        ModelPresetMirror("ModelScope · 国内直连", "$MODELSCOPE_BASE/$repo/resolve/master/$file"),
    )

    /**
     * 1 GiB（2^30）。**刻意沿用「GB」这个命名、以及界面上「GB」的写法，别改。**
     *
     * 数值口径是二进制的（`sizeBytes` / `requiredRamBytes` / `formatBytes` 全部按 2^30 算），
     * 但面向用户的文案写「GiB」只是给非技术用户加噪音，而 2^30 与 10^9 之间那 7% 的差异
     * 落在一个本身还是估算值的阈值上，不构成任何决策变化。所以：**内部按 GiB 算，对外说 GB**。
     */
    private const val GB = 1_073_741_824L

    private const val BASIS_GPU =
        "W×1.25(GPU)+KV@4096+O，×1.25 安全余量"
    private const val BASIS_CPU =
        "W×1.05(CPU)+KV@4096+O，×1.25 安全余量"

    /**
     * E4B 单文件（CPU）**实测**内存口径（W67 B4，禁推测预填）。
     *
     * 两点采样（`dumpsys meminfo`，OPPO A13 `13309cc8`，MemTotal 11.8GB）：
     * 加载后静置 PSS ≈ **5.66 GiB** / 生成峰值 PSS ≈ **5.66 GiB**（RSS ≈ 5.05 GiB，
     * swap ≈ 1.0 GiB）—— 两点几乎相等 = 3.41 GiB 权重主导（KV@4096 增量小）。
     * `requiredRamBytes` = 峰值 × 既有安全系数 1.25 ≈ **7.1 GiB**。
     */
    private const val BASIS_CPU_E4B_MEASURED =
        "W67 实测峰值 ×1.25（A13 13309cc8：静置 PSS 5.66GiB / 生成峰值 PSS 5.66GiB，RSS 5.05GiB，swap≈1.0GiB）"

    /**
     * Phi-4-mini（CPU）**实测**内存口径（W71 内存实测批，W69-02 销账）。
     *
     * 9 样本（`dumpsys meminfo`，OPPO A13 `13309cc8`，MemTotal 11.8GB）：
     * 生成峰值 **TOTAL PSS 5.20 GiB**（该样本 swap 0.06 GiB ⇒ 同样本合计 5.26 GiB）；
     * 本批**最大同样本合计 5.28 GiB**、swap 峰值 0.18 GiB 出现在另一采样点。
     * 实测**低于**公式预测 5.6 GiB（差 0.32~0.40 GiB，取决于是否含 swap）⇒
     * `requiredRamBytes` **不动**（W69-02 定案：可测容器实测均低于预测 ⇒ tail 1.25 不降）。
     *
     * ⚠️ 为什么不写成单个「合计」数字：PSS 峰值与 swap 峰值来自**不同采样点**，
     * `5.20 + 0.18 = 5.38` **不是任何一次观测的真实值**。`docs/handoff-20261017-wave71-closeout.md`
     * §3.2 表里「合计」列取的是**同样本** `PSS + swap` 的最大值（5.28 GiB，来自另一采样点），
     * 与「峰值 PSS 5.20」「峰值 swap 0.18」是三种不同聚合口径 —— 别把三列拼成一个数。
     */
    private const val BASIS_CPU_PHI4_MEASURED =
        "W71 实测峰值 ×1.25（A13 13309cc8，9 样本：生成峰值 TOTAL PSS 5.20GiB，该样本 swap 0.06GiB ⇒ 合计 5.26GiB；本批最大同样本合计 5.28GiB）——实测低于公式 5.6GiB，阈值仍取 tail 1.25 后的 ≥5.6GiB 不动"

    /**
     * E4B·GPU **实测**内存口径（W71 内存实测批，W69-02 销账）。
     *
     * 15 样本（`dumpsys meminfo`，OPPO A13 `13309cc8`）：峰值 **TOTAL PSS 5.00 GiB**
     * （该样本 swap 0.01 GiB ⇒ 同样本合计 5.01 GiB）。实测低于公式预测 5.2 GiB ⇒
     * `requiredRamBytes` 不动（W69-02 定案）。
     *
     * ⚠️ **本条的证据边界（勿当「跑得好时占多少」读）**：该容器在 0.17.1 下是
     * **已知坏**（`knownBrokenOn` = 引擎初始化 NOT_FOUND），样本里 PSS 由 5.00 GiB
     * 回落到 3.00 GiB（内存释放）⇒ 峰值取自**加载阶段**，**生成期峰值未单独取证**。
     * 对内存闸门（拦的就是加载）仍是有效锚点；对「生成期要多少」不构成结论。
     */
    private const val BASIS_GPU_E4B_MEASURED =
        "W71 实测峰值 ×1.25（A13 13309cc8：加载峰值 TOTAL PSS 5.00GiB，该样本 swap 0.01GiB ⇒ 合计 5.01GiB；本容器 0.17.1 已知坏 ⇒ 生成期峰值未取证）——实测低于公式 5.2GiB，阈值仍取 tail 1.25 后的 ≥5.2GiB 不动"

    val all: List<ModelPreset> = listOf(
        ModelPreset(
            label = "Gemma 4 E2B · GPU",
            url = "$BASE/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it-gpu.litertlm",
            sizeText = "1.87 GB",
            ramText = "≥ 3.6 GB",
            requiredRamBytes = (3.6 * GB).toLong(),
            memBasis = BASIS_GPU,
            // ⚠️ Wave 43 真机实测（OPPO PDRM00 / 骁龙 8s Gen3，LiteRT-LM 0.17.1）：
            // 本变体**输出退化**，不要推荐给用户下载。判据级证据：
            //  - temp 0.4/topK 20：结构化英文 CoT 中途崩 → `[current` n-gram 死锁 →
            //    连续 3 轮触发轮内重复检测被终止；
            //  - temp 1.0/topK 64（官方口径）：采样出 `<unused1556>`/`<unused4347>`
            //    等**保留未训练 token**（正常模型概率≈0）→ logits 分布退化，调采样救不回来；
            //  - 容器 section 表只有 `tf_lite_artisan_text_decoder`（无 audio/vision
            //    encoder），CPU 后端 engine init 直接 NOT_FOUND。
            // 对照：同 prompt、同管线下 CPU 变体（下方条目）多轮工具调用正常收尾；
            // MiniCPM5-2B / Qwen2.5-1.5B 亦正常 ⇒ 应用侧健康，属容器↔运行时错配。
            note = "⚠️ 0.17.1 下不可用（真机实测）：引擎初始化失败（容器缺 TF_LITE_PREFILL_DECODE section）；请用下方 CPU 变体",
            sizeBytes = 2008432640,
            recommended = false,
            backendBasis = "官方 GPU 特化变体（ML Drift GPU）——⚠️ 实测不可用（W60：引擎初始化 NOT_FOUND，容器缺 TF_LITE_PREFILL_DECODE signature；W43：输出退化 + CPU 后端 NOT_FOUND），待上游修复后再开放",
            // W63 刀C：签名取自本条 backendBasis 的 W60 实测记录（engine init NOT_FOUND）。
            knownBrokenOn = "0.17.1 引擎初始化 NOT_FOUND（容器缺 TF_LITE_PREFILL_DECODE section；W43 另实测输出退化）",
            // W68 B3（R-1 时效）：该坏结论只对 0.17.1 成立；bump 后按 docs/12 锚「knownBroken
            // 过期复审」复核是否清空（上游若修复容器即恢复可用）。
            appliesToRuntime = "0.17.1",
            evidenceLevel = EvidenceLevel.MEASURED,
            // 证据：W53 L2 容器取证（`gemma4gpu.jinja`）+ W72 七格复验（含 systemInstruction 维度）
            templateArraySafety = TemplateArraySafety.SAFE,
            mirrors = domesticMirrors("gemma-4-E2B-it-litert-lm", "gemma-4-E2B-it-gpu.litertlm"),
        ),
        ModelPreset(
            label = "Gemma 4 E2B · CPU",
            url = "$BASE/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
            sizeText = "2.41 GB",
            ramText = "≥ 3.9 GB",
            requiredRamBytes = (3.9 * GB).toLong(),
            memBasis = BASIS_CPU,
            // Wave 43 真机实测：同 prompt 下多轮工具调用（current_time / ask_actor /
            // memory_write）解析正常、run 正常收尾，是该型号目前的**唯一可用变体**。
            note = "端侧主力（实测可用）：多轮工具调用与正常收尾均验证通过",
            sizeBytes = 2588147712,
            recommended = true,
            backendBasis = "CPU 特化变体（实测通过）；GPU 变体不可用（W60 引擎初始化 NOT_FOUND / W43 输出退化），勿用",
            evidenceLevel = EvidenceLevel.MEASURED,
            // 证据：W53 L2 容器取证（`gemma4.jinja`）+ W72 七格复验（含 systemInstruction 维度）
            templateArraySafety = TemplateArraySafety.SAFE,
            mirrors = domesticMirrors("gemma-4-E2B-it-litert-lm", "gemma-4-E2B-it.litertlm"),
        ),
        ModelPreset(
            label = "Gemma 4 E4B · GPU",
            url = "$BASE/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it-gpu.litertlm",
            sizeText = "2.77 GB",
            ramText = "≥ 5.2 GB",
            requiredRamBytes = (5.2 * GB).toLong(),
            memBasis = BASIS_GPU_E4B_MEASURED,
            // 同 E2B · GPU：同一转换线的 GPU 特化变体。W60 真机实测**引擎初始化失败**
            // （NOT_FOUND，容器缺 VISION/AUDIO 编码器 section，见 backendBasis），与
            // E2B · GPU 同源；本工程**无 E4B CPU 变体** ⇒ 该变体 0.17.1 下不可用。
            note = "更大的模型：回答更好，需要 8GB 以上内存的手机（GPU 特化变体，0.17.1 下实测不可用）",
            sizeBytes = 2969059328,
            recommended = false,
            backendBasis = "官方 GPU 特化变体（README 含 Galaxy S26 Ultra GPU 基准）——❌ 实测不可用（W60：引擎初始化 NOT_FOUND（容器缺 VISION/AUDIO 编码器 section，model_signature_utils.cc:250），与 E2B·GPU 同源）；⚠️ 视觉能力系 gemma-4 家族外推（同族 E2B-it 已逐容器取证带视觉编码器，E4B 未单独取证）",
            // W63 刀C：签名取自本条 backendBasis 的 W60 实测记录（同 E2B·GPU 失败面）。
            knownBrokenOn = "0.17.1 引擎初始化 NOT_FOUND（容器缺 VISION/AUDIO 编码器 section，与 E2B·GPU 同源）",
            // W68 B3（R-1 时效）：同 E2B·GPU —— 只对 0.17.1 成立，bump 后按 docs/12 锚复审。
            appliesToRuntime = "0.17.1",
            evidenceLevel = EvidenceLevel.MEASURED,
            // 证据：W72 现场抽取（容器字节数 == 预设 sizeBytes 2969059328）+ 七格复验
            templateArraySafety = TemplateArraySafety.SAFE,
            mirrors = domesticMirrors("gemma-4-E4B-it-litert-lm", "gemma-4-E4B-it-gpu.litertlm"),
        ),
        ModelPreset(
            label = "Gemma 4 E4B · CPU（单文件）",
            url = "$BASE/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm",
            sizeText = "3.41 GB",
            ramText = "≥ 7.1 GB",
            requiredRamBytes = (7.1 * GB).toLong(),
            memBasis = BASIS_CPU_E4B_MEASURED,
            // W68 A2（复检 Q-1）：把「12GB 以上」与闸门 7.1GiB 的数字关系写成一句，
            // 禁留「打架」观感 —— 两者是不同的线：12GB = Warrior 级机型**流畅体验建议**
            // （CPU 变体无 GPU 分担、生成期整机压力大）；7.1GiB = **闸门放行线**
            // （W67 实测峰值 PSS 5.66GiB × 1.25 安全余量，只保证「不低于崩溃线」）。
            note = "旗舰质量（单文件 CPU 变体，非 -gpu 特化）：实测可用，但权重 3.41GB、CPU 首字约 29s —— 「12GB 以上内存机型」是流畅体验建议（CPU 无 GPU 分担、生成期整机压力大）；闸门「≥ 7.1 GB」只是放行线（实测峰值 PSS 5.66GiB × 1.25 安全余量，= 不低于崩溃线的下界）：体验看 12GB，门槛看 7.1GB，两回事",
            sizeBytes = 3659530240,
            recommended = false,
            backendBasis = "单文件（**非** `-gpu` 特化变体）；✅ 本仓真机取证：W66 真机 CPU 臂（`ModelStopped` / thinking 177 / 正文 OK / 零 `NOT_FOUND` / 零 FATAL）+ W67 真机复验（in 1365 / out 1，零 FATAL）；⚠️ **不得入 `ModelGpuSupport` 白名单** —— 同文件 GPU 路径在 A13 入门 Adreno 上 OOM（`IOCTL_KGSL_GPUOBJ_ALLOC errno 12` → `Resource deadlock` → SIGABRT → 整机重启，W66 §3.1），须高显存设备复测通过后才议",
            evidenceLevel = EvidenceLevel.MEASURED,
            // 证据：W72 现场抽取（容器字节数 == 预设 sizeBytes 3659530240）+ 七格复验
            templateArraySafety = TemplateArraySafety.SAFE,
            mirrors = domesticMirrors("gemma-4-E4B-it-litert-lm", "gemma-4-E4B-it.litertlm"),
        ),
        ModelPreset(
            label = "Qwen2.5 1.5B · q8",
            url = "$BASE/Qwen2.5-1.5B-Instruct/resolve/main/Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm",
            sizeText = "1.49 GB",
            ramText = "≥ 2.4 GB",
            requiredRamBytes = (2.4 * GB).toLong(),
            memBasis = BASIS_CPU,
            note = "中文好、体积小，6GB 内存的手机也能流畅跑",
            sizeBytes = 1597931520,
            // 注意：recommended 只允许两项（MiniCPM5 主推 + Gemma 4 E2B·CPU 备选；E2B·GPU
            // 为 knownBroken 不参选），标多了就失去「替用户做决定」的意义。
            recommended = false,
            backendBasis = "官方 README 仅有桌面 GPU 基准（macOS Metal），无 Android GPU 验证 → 仅 CPU；✅ W60 真机普查（台账 #45，题面 Say OK 正常：round0 原生工具调用 → round1 收尾，原生通道行使，13 模型零 FATAL）；W61 J3 复验（TOOL_CALLS 收尾全通过）",
            evidenceLevel = EvidenceLevel.MEASURED,
            // 证据：W53 L2 `qwen25.jinja:23`「+」拼接 + **W72 真机 Q4**（`Failed to apply template: + operator on unsupported types string and sequence`）
            templateArraySafety = TemplateArraySafety.UNSAFE,
            mirrors = domesticMirrors(
                "Qwen2.5-1.5B-Instruct",
                "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm",
            ),
        ),
        ModelPreset(
            label = "DeepSeek-R1 蒸馏 Qwen 1.5B",
            url = "$BASE/DeepSeek-R1-Distill-Qwen-1.5B/resolve/main/DeepSeek-R1-Distill-Qwen-1.5B_multi-prefill-seq_q8_ekv4096.litertlm",
            sizeText = "1.71 GB",
            ramText = "≥ 2.7 GB",
            requiredRamBytes = (2.7 * GB).toLong(),
            memBasis = BASIS_CPU,
            note = "会先「想一想」再回答，适合体验思考过程",
            sizeBytes = 1833451520,
            recommended = false,
            backendBasis = "官方 README 无 Android GPU 验证 → 仅 CPU；✅ W60 真机普查（台账 #45：13 模型题面 Say OK，DeepSeek-R1 属「2 个自愈」之一——2 次重复提醒后收尾，零 FATAL）；W61 J1 真机（R1+AUTO：capabilities.thinking/thinkingOn 修复生效）；W66 §3.2 探针（OFF 档正文空 ⛔ 如实记录）；⚠️ 模板数组安全 **`UNVERIFIED`（`NO_TEMPLATE_IN_CONTAINER`）**：容器无明文 `chat_template`（section 扫描仅 `TF_LITE_PREFILL_DECODE` / `model_type` / `prefill_*` / `decode`）⇒ 走运行时内建模板，容器侧七格取证**方法不适用**（W72 D 数据件，非漏做）",
            evidenceLevel = EvidenceLevel.MEASURED,
            // UNVERIFIED 理由 = `NO_TEMPLATE_IN_CONTAINER`：容器无明文 `chat_template`（section 扫描仅 TF_LITE_PREFILL_DECODE / model_type / prefill_* / decode）⇒ 走运行时内建模板，容器侧七格取证**方法不适用**（≠ 漏做）
            templateArraySafety = TemplateArraySafety.UNVERIFIED,
            mirrors = domesticMirrors(
                "DeepSeek-R1-Distill-Qwen-1.5B",
                "DeepSeek-R1-Distill-Qwen-1.5B_multi-prefill-seq_q8_ekv4096.litertlm",
            ),
        ),
        ModelPreset(
            label = "MiniCPM5 2B · int4",
            url = "$BASE/MiniCPM5-2B/resolve/main/MiniCPM5-2B_int4.litertlm",
            sizeText = "1.45 GB",
            ramText = "≥ 2.4 GB",
            requiredRamBytes = (2.4 * GB).toLong(),
            memBasis = BASIS_CPU,
            note = "体积小、中文强，支持思考模式与工具调用（OpenBMB 官方端侧主力）",
            sizeBytes = 1553670064,
            recommended = true,
            backendBasis = "官方 Galaxy S26 OpenCL 全委托实测（逐节点零拒绝，GPU/CPU 双验证）；✅ 本仓真机取证：W60 真机普查（台账 #45：题面 Say OK 正常——首下发 1 次模板失败→自愈重建→重试成功，自愈链真机行使，零 FATAL）；W48/W49 台账 #13 真机（`thinking` 非空 472、正文无 `<think>`，N1 切分根修回归）；W66 §3.2 探针正文恢复非空。" +
                "要求 litert-lm ≥ 0.16 —— 2026-09-26 运行时已升 0.17.1 满足 → GPU 放行。" +
                "⚠️ 真机为首次验证，加载/速度异常请反馈。" +
                "⚠️ 原生工具通道在本容器不可用（模板 tojson(ensure_ascii=…) 与 litertlm 0.17.1 内嵌 minijinja 2.14.0 不兼容，生成期报 too many arguments；已由「证伪 + 回退文本协议」自愈）；文本协议路径不受影响",
            // 证据等级 = MEASURED（W67 B3 回填）：GPU 放行依据仍是**官方 Galaxy S26 OpenCL 实测**
            // （上游基准），但本条文本内已补入**本仓 W## 真机锚点**（W60 台账 #45 / W48·W49 台账 #13 /
            // W66 §3.2 探针）⇒ 按「本条文本内有无锚点」口径（W66 深审 D9）升 MEASURED。
            // ⚠️ 字段语义 = 本条文本内锚点，≠ 本仓是否测过该模型。
            evidenceLevel = EvidenceLevel.MEASURED,
            // 证据：**W72 真机 Q5** 同签名（journal 587 B 无 MODEL 消息）；⚠️ 本容器 user 分支的 `content=''` 性质被 system 侧报错遮蔽 —— 只修 system 分支即滑进 LOSSY（不可见失败）
            templateArraySafety = TemplateArraySafety.UNSAFE,
            mirrors = domesticMirrors("MiniCPM5-2B", "MiniCPM5-2B_int4.litertlm"),
        ),
        ModelPreset(
            label = "Phi-4-mini · q8",
            url = "$BASE/Phi-4-mini-instruct/resolve/main/Phi-4-mini-instruct_multi-prefill-seq_q8_ekv4096.litertlm",
            sizeText = "3.64 GB",
            ramText = "≥ 5.6 GB",
            requiredRamBytes = (5.6 * GB).toLong(),
            memBasis = BASIS_CPU_PHI4_MEASURED,
            note = "推理与代码能力强，但体积大，仅建议 12GB 内存机型",
            sizeBytes = 3910090752,
            recommended = false,
            backendBasis = "官方 README 的 GPU 行无显存数据（N/A），无 Android GPU 验证 → 仅 CPU；✅ W60 真机普查（台账 #45：13 模型题面 Say OK 正常，Phi-4-mini 属「8 个正常」之一——`记忆已更新，任务停止。`，零 FATAL）；⚠️ 模板数组安全 **`UNVERIFIED`（`NO_TEMPLATE_IN_CONTAINER`）**：容器无明文 `chat_template`（section 扫描仅 `TF_LITE_PREFILL_DECODE` / `model_type` / `prefill_*` / `decode`）⇒ 走运行时内建模板，容器侧七格取证**方法不适用**（W72 D 数据件，非漏做）",
            evidenceLevel = EvidenceLevel.MEASURED,
            // UNVERIFIED 理由 = `NO_TEMPLATE_IN_CONTAINER`：容器无明文 `chat_template`（section 扫描仅 TF_LITE_PREFILL_DECODE / model_type / prefill_* / decode）⇒ 走运行时内建模板，容器侧七格取证**方法不适用**（≠ 漏做）
            templateArraySafety = TemplateArraySafety.UNVERIFIED,
            mirrors = domesticMirrors(
                "Phi-4-mini-instruct",
                "Phi-4-mini-instruct_multi-prefill-seq_q8_ekv4096.litertlm",
            ),
        ),
        ModelPreset(
            label = "LFM2.5-VL 450M · 视觉",
            url = "$BASE/LFM2.5-VL-450M/resolve/main/LFM2.5-VL-450M_int8.litertlm",
            sizeText = "0.52 GB",
            ramText = "≥ 2.0 GB",
            requiredRamBytes = (2.0 * GB).toLong(),
            memBasis = BASIS_CPU,
            note = "能看懂图片：用来体验拍照问答，体积最小",
            sizeBytes = 563549568,
            recommended = false,
            backendBasis = "官方在 Pixel 8a（litert-lm 0.16.1）验证过 GPU profile；运行时已升 0.17.1（版本达标），但视觉 GPU 组合真机未实测 → 仍禁 GPU 待验证；✅ **端到端图片输入已真机验证（W56 L3：OPPO A13 + 0.17.1，发图会话建成、0 模板失败、内容判别相符——仅覆盖本容器，同族其余容器不外推）**；⚠️ 模板已离线取证数组安全（W55 L1：`chat_template` 用 `content is string` + `for item in content`，非 `+` 拼接）",
            evidenceLevel = EvidenceLevel.MEASURED,
            // 证据：W55 L1（本条 backendBasis 已载：`chat_template` 用 `content is string` + `for item in content`，非 `+` 拼接）
            templateArraySafety = TemplateArraySafety.SAFE,
            mirrors = domesticMirrors("LFM2.5-VL-450M", "LFM2.5-VL-450M_int8.litertlm"),
        ),
        // ── 视觉多模态批（2026-09-24 增补）：端侧 VL 帕累托前沿，500M/2B/1.6B/3B/8B 五档。
        // 收录判据与排除名单见文件头「视觉多模态预设的取舍」；引擎版本提醒也在那里。
        ModelPreset(
            label = "SmolVLM2 500M · 视觉",
            url = "$BASE/SmolVLM2-500M/resolve/main/SmolVLM2-500M.litertlm",
            sizeText = "0.34 GB",
            ramText = "≥ 2.0 GB",
            requiredRamBytes = (2.0 * GB).toLong(),
            memBasis = "公式 < 1G；按小模型固定基线 2.0G 取下限（同 450M-VL 锚点），图像 token 预算已含",
            note = "能看图的最小模型：老手机也能体验拍照问答",
            sizeBytes = 360822960,
            recommended = false,
            backendBasis = "官方在 Galaxy S26（litert-lm 0.15）验证 Android GPU 可生成；运行时已升 0.17.1（版本达标），但视觉 GPU 组合真机未实测 → 仍禁 GPU 待验证。⚠️ 文本对话能力弱（360M 解码器，为图像输入设计）：纯文本对话建议 Qwen2.5-1.5B 或 DeepSeek-R1；✅ 端到端图片输入已真机验证（W57 L3：台账 #41，异族/同族 fixB 双通过，0 模板失败 + 内容判别命中——仅覆盖本容器，同族其余容器不外推）；⚠️ 模板已离线取证数组安全（W55 L1：`chat_template` 用 `content is string` + `for item in content`，非 `+` 拼接）",
            evidenceLevel = EvidenceLevel.MEASURED,
            // 证据：W55 L1（本条 backendBasis 已载：`chat_template` 用 `content is string` + `for item in content`，非 `+` 拼接）
            templateArraySafety = TemplateArraySafety.SAFE,
            mirrors = domesticMirrors("SmolVLM2-500M", "SmolVLM2-500M.litertlm"),
        ),
        ModelPreset(
            label = "Qwen2-VL 2B · 视觉",
            url = "$BASE/Qwen2-VL-2B/resolve/main/Qwen2-VL-2B.litertlm",
            sizeText = "1.66 GB",
            ramText = "≥ 2.7 GB",
            requiredRamBytes = (2.7 * GB).toLong(),
            memBasis = "W×1.05(CPU)+KV@4096(Qwen2 骨干 ≈0.11G)+O ×1.25 ≈2.6G，加视觉编码器激活取 2.7G",
            note = "阿里通义视觉模型：中文看图、截图问答与 OCR 强",
            sizeBytes = 1783424544,
            recommended = false,
            backendBasis = "官方在 Pixel 8a（视觉 GPU + 解码 CPU）与 Galaxy S26（0.15）验证；运行时已升 0.17.1（版本达标），但视觉 GPU 组合真机未实测 → 仍禁 GPU 待验证；✅ 端到端图片输入已真机验证（W60 L3：台账 #46，红/黄/数字 37 三锚点命中，0 模板失败——仅覆盖本容器，同族不外推）；⚠️ 模板已离线取证数组安全（W55 L1：`chat_template` 用 `content is string` + `for item in content`，非 `+` 拼接）；⚠️ W55 已推翻 W53「Qwen 系同源 ⇒ 风险最高」的假设——其模板与 `Qwen2.5-1.5B` 的 `+` 拼接形态**不同**（同家族 ≠ 同模板）",
            evidenceLevel = EvidenceLevel.MEASURED,
            // 证据：W55 L1（本条 backendBasis 已载：`content is string` + `for item in content`，非 `+` 拼接 —— W55 已推翻「Qwen 系同源 ⇒ 风险最高」假设）
            templateArraySafety = TemplateArraySafety.SAFE,
            mirrors = domesticMirrors("Qwen2-VL-2B", "Qwen2-VL-2B.litertlm"),
        ),
        ModelPreset(
            label = "LFM2.5-VL 1.6B · 视觉",
            // 收 fixB 修复版：stock int4 的 vision graph 有「只见图片顶部 1/4」的
            // 上游 bug（LiteRT-LM#3246），fixB 重导出后整图可见，体积几乎不变。
            url = "$BASE/LFM2.5-VL-1.6B/resolve/main/LFM2.5-VL-1.6B_int4_fixB.litertlm",
            sizeText = "1.21 GB",
            ramText = "≥ 2.4 GB",
            requiredRamBytes = (2.4 * GB).toLong(),
            memBasis = "W×1.05+KV@4096(LFM2.5 混合架构 ≈0.04G)+O ×1.25 ≈1.9G；加视觉激活与图像 token 余量取 2.4G",
            note = "同体积看图能力最强之一：多语言视觉与 OCR 均衡（含视觉修复）",
            sizeBytes = 1298139472,
            recommended = false,
            backendBasis = "官方在 Pixel 8a（litert-lm 0.16.1）验证过 GPU profile；运行时已升 0.17.1（版本达标），但视觉 GPU 组合真机未实测 → 仍禁 GPU 待验证；✅ 端到端图片输入已真机验证（W57 L3：台账 #42，异族/同族 fixB 双通过，0 模板失败 + 内容判别命中——仅覆盖本容器，同族其余容器不外推）；⚠️ 模板已离线取证数组安全（W55 L1：`chat_template` 用 `content is string` + `for item in content`，非 `+` 拼接）",
            evidenceLevel = EvidenceLevel.MEASURED,
            // 证据：W55 L1（本条 backendBasis 已载：`chat_template` 用 `content is string` + `for item in content`，非 `+` 拼接）
            templateArraySafety = TemplateArraySafety.SAFE,
            mirrors = domesticMirrors("LFM2.5-VL-1.6B", "LFM2.5-VL-1.6B_int4_fixB.litertlm"),
        ),
        ModelPreset(
            label = "LFM2.5-VL 3B · 视觉",
            // 同上：收 fixB 修复版而非 stock int4。
            url = "$BASE/LFM2.5-VL-3B/resolve/main/LFM2.5-VL-3B_int4_fixB.litertlm",
            sizeText = "2.19 GB",
            ramText = "≥ 3.5 GB",
            requiredRamBytes = (3.5 * GB).toLong(),
            memBasis = "W×1.05(CPU)+KV@4096(混合架构 ≈0.08G)+O ×1.25 ≈3.3G，加视觉激活取 3.5G",
            note = "小体积视觉旗舰：精细图像理解与文档 OCR，8GB 内存机型舒适运行（含视觉修复）",
            sizeBytes = 2352023888,
            recommended = false,
            backendBasis = "官方在 Pixel 8a（litert-lm 0.16.1）验证过 GPU profile；运行时已升 0.17.1（版本达标），但视觉 GPU 组合真机未实测 → 仍禁 GPU 待验证；✅ 端到端图片输入已真机验证（W60 L3：台账 #46，红/黄/数字 37 三锚点命中，中文回答，0 模板失败——仅覆盖本容器，同族不外推）；⚠️ 模板已离线取证数组安全（W55 L1：`chat_template` 用 `content is string` + `for item in content`，非 `+` 拼接）",
            evidenceLevel = EvidenceLevel.MEASURED,
            // 证据：W55 L1（本条 backendBasis 已载：`chat_template` 用 `content is string` + `for item in content`，非 `+` 拼接）
            templateArraySafety = TemplateArraySafety.SAFE,
            mirrors = domesticMirrors("LFM2.5-VL-3B", "LFM2.5-VL-3B_int4_fixB.litertlm"),
        ),
        ModelPreset(
            label = "MiniCPM-V 4 · 8B 视觉",
            url = "$BASE/MiniCPM-V-4/resolve/main/MiniCPM-V-4-int8.litertlm",
            sizeText = "3.93 GB",
            ramText = "≥ 6.5 GB",
            requiredRamBytes = (6.5 * GB).toLong(),
            // W71 内存实测批：本容器执行失败不可测（knownBroken「Unsupported model type」，
            // 只记失败路径 PSS 0.40GiB）⇒ 数值仍是公式值，只把「为什么没有实测」写进数据。
            memBasis = "公式值 W×1.05(CPU)+KV@4096(Qwen3-8B 骨干 ≈0.56G)+O ×1.25 ≈6.4G，含高分辨率图像 token 余量；⚠️ W71 实测批执行失败不可测（knownBroken「Unsupported model type」，仅记失败路径 PSS 0.40GiB）⇒ 本值未经真机峰值校准",
            note = "视觉能力天花板：8B 级多模态，仅建议 8GB 以上内存机型",
            sizeBytes = 4214021104,
            recommended = false,
            backendBasis = "官方 README 无 Android GPU 验证章节 → 仅 CPU；⚠️ 模板已离线取证数组安全（Wave 53 L2：容器模板对多元素 content 走 is-string 分支并循环吐 <image_soft_token>，非 Qwen2.5 的「+」拼接形态），❌ 端到端不可用（W60：Failed to create conversation config: INVALID_ARGUMENT: Unsupported model type，0.17.1 已知限制）",
            // W63 刀C：签名取自本条 backendBasis 的 W60 实测记录（会话创建级失败）。
            knownBrokenOn = "0.17.1 会话创建失败（Unsupported model type，引擎已知限制）",
            // W68 B3（R-1 时效）：只对 0.17.1 成立（上游支持该 model type 后即过期），bump 后按 docs/12 锚复审。
            appliesToRuntime = "0.17.1",
            evidenceLevel = EvidenceLevel.MEASURED,
            // 证据：W53 L2（本条 backendBasis 已载：模板对多元素 content 走 is-string 分支并循环吐 `<image_soft_token>`，非 Qwen2.5 的「+」拼接形态）
            templateArraySafety = TemplateArraySafety.SAFE,
            mirrors = domesticMirrors("MiniCPM-V-4", "MiniCPM-V-4-int8.litertlm"),
        ),
    )

    /** 推荐项的展示顺序（数字小的在前）。别依赖 sortedByDescending 的稳定性。 */
    private val recommendedOrder = listOf(
        "MiniCPM5 2B · int4",
        "Gemma 4 E2B · GPU",
    )

    /** 推荐项置顶且按 recommendedOrder 排序，供「一键获取模型」对话框使用。 */
    val recommendedFirst: List<ModelPreset> =
        all.filter { it.recommended }.sortedBy { preset ->
            val index = recommendedOrder.indexOf(preset.label)
            if (index < 0) Int.MAX_VALUE else index
        } + all.filter { !it.recommended }

    /**
     * 按直链反查预设（下载卡片显示口径、内存/流量闸门、Gemma 授权标签共用）。
     *
     * 2026-09-24 起一条预设有多路镜像直链，主源与镜像**必须**都能反查到同一个预设：
     * 否则从镜像下载会被当成「自定义链接」，流量/内存闸门口径退化为无预设估算。
     * 用 [ModelPreset.ownsUrl] 而不是等值比较，就是这个原因。
     */
    fun findByUrl(url: String): ModelPreset? = all.firstOrNull { it.ownsUrl(url) }

    /**
     * 按**文件名**反查预设。
     *
     * 为什么需要它：加载前的内存闸门（`ModelsViewModel.onLoad`）手上只有一个
     * [com.rickeal.agent.core.model.ModelDescriptor]，**没有 URL**，拿不到 [ModelPreset.requiredRamBytes]。
     * 下载链路里文件名就是 URL 的末段（`onDownloadFromUrl` 的 `fileName` 派生），而 14 个预设的
     * 文件名只含 `[A-Za-z0-9._-]`，不会被 `ModelDownloader.sanitizeFileName` 改写，所以能稳定匹配。
     *
     * **查不到是正常情况，不是错误**：SAF 导入的用户自定义文件、以及因重名被 DownloadManager
     * 落成 `name-1.ext` 的下载，都匹配不上 —— 调用方必须自带兜底估算，不能把 null 当异常。
     */
    fun findByFileName(name: String): ModelPreset? =
        all.firstOrNull { it.url.substringAfterLast('/') == name }
}
