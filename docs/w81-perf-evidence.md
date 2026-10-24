# W81 性能波 · 测量依据入仓（F3 收口）

> 建立：W81（2026-10-24）。起因：第二十审 F3 + 深度审查 P0-2 —— W80 性能波的**依据长期只存工作区
> `_ci-tools/`（不入仓）**，而承重配置（`feature-chat/stability.conf`）在仓内 ⇒「配置在仓内、依据
> 在仓外、无机械校验」三重叠加，失效必然且静默。本文件把三类依据的**可复核摘录**固化入仓；
> 原始报告仍留工作区（`_ci-tools/_w80_compose_stability_report/`，含 baseline/with_conf 两态全量），
> 本文件是「丢了工作区也能重建判据」的最小集。
>
> ⚠️ 本文件只收**测量依据**；stability.conf 的机械校验（无 var / 存在性 / FQN 可解析）已另落地
> arch-guard **#47**（W81），ConversationCache 行数闸门 = arch-guard **#48**。

## 一、`dumpsys gfxinfo` 在 PDRM00/ColorOS 帧统计恒 0（W80-01 取证）

- **方法**（三对照，防「pid 错配」类假阴）：
  1. `adb shell dumpsys gfxinfo <pid>` —— pid 经 `ps -A | grep rickeal` 匹配**活进程**；
  2. UI 正常渲染对照（滚动/生成期间界面 visibly 正常）；
  3. `--reset` 后复测（排除累计窗口问题）。
- **判据与结果**：三种情形下 `Total frames` **恒 0**（有无 reset 均 0 帧）⇒ 帧统计路径
  **设备级不可用**，scroll/generation 场景判 ⛔ vacuous（不是「没掉帧」，是「测不出帧」）。
- **结论链**：帧级数据唯一可行路径从「FrameMetrics/gfxinfo」转为**程序化采集** ⇒
  W80-07（JankStats，需新增依赖，parked 等用户批，docs/14 §C7）+ W80-08（模糊滚动降强度，
  等帧数据标定）的全部前提。
- **仍可用的维度**：冷启 `am start -W` TotalTime + logcat `Fully drawn`（W80-01 锚：
  实测 `Fully drawn +671ms` ≈ TotalTime 670ms = 首帧即锚）。

## 二、Compose 稳定性报告两态实证（W80-02 依据）

`composeCompiler{}` DSL 产出（CLI `-P` 在 Kotlin 2.3.0 **静默失效**——坑已留档；`classes.txt`
0 字节为预期旁证，`composables.txt` 仍有效）。同一 `MessageRow` 在两态下的签名差异：

```
# baseline/（conf 清空）
restartable skippable scheme("[androidx.compose.ui.UiComposable]") fun com.rickeal.agent.feature.chat.MessageRow(
  unstable message: ChatMessage        ← conf 前：MessageRow 不可 skip
  stable expanded: Boolean

# with_conf/（stability.conf 生效后）
restartable skippable scheme("[androidx.compose.ui.UiComposable]") fun com.rickeal.agent.feature.chat.MessageRow(
  stable message: ChatMessage          ← conf 后：可 skip
  stable expanded: Boolean
```

- **根因**：core-model 的 `ChatMessage` 全 val 不可变但含 `List` 字段 ⇒ Compose 默认判 unstable
  ⇒ 流式 ~8Hz flush 时全列表 MessageRow 陪跑重组（与推理争抢 SoC）。因 core-model 禁改，
  走 conf 声明 9 个类（ChatMessage / Attachment sealed 四子类 / ToolCall / ToolResult / TokenUsage）。
- **「声明有据」纪律**：conf 只声明**报告背书**的类；W81 arch-guard #47 机械校验「conf 类在
  core-model 无 var + FQN 可解析 + conf 存在」，防将来加 var 后静默退化（编译/lint/测试全绿
  但重组退化回全量）。

## 三、零依赖帧统计采集（W81-05 依据）

- **为什么不用 JankStats**：`androidx.metrics:metrics-performance` 底层即
  `Choreographer.FrameCallback` + `FrameMetrics` 封装；而 W80-08 的判据只需「滚动/生成期
  掉帧率」。**版本锁死纪律防的是「随意加依赖」，零依赖方案不触发它**——用「要加依赖」当
  「先不做」的理由是把纪律用反了（深度审查 P0-1）。
- **实现**：`core-data/.../perf/FrameStatsCollector.kt`（W81）：主线程 Choreographer
  postFrameCallback 采集帧间隔，滑动窗口 120 帧（2s @60fps），delta > 20ms 记 janky；
  `isDebuggable`（ApplicationInfo.FLAG_DEBUGGABLE，非 BuildConfig——零 gradle 改动）门控，
  release 零行为；诊断页开关 + fps/janky% 展示。
- **同源资产参照**：Cyber-Android-Monitor-Pro `FloatingWindowService` 的标准写法（同作者，
  许可无问题，只取思路；其修复史实证 `Choreographer.getInstance()` 必须主线程获取）。

## 四、真机帧数据采集结果（W81 回填，2026-10-24 主理人实测）

> 采集方法：诊断页 → 「帧统计」卡 → 开始采集 → 执行场景 → 读数。设备 OPPO PDRM00
> （13309cc8）/ Android 13。窗口 = 最近 120 帧 ≈ 2s @60fps；掉帧判据 delta > 20ms。

| 场景 | 口径 | fps | janky% | 结论 |
|---|---|---|---|---|
| 设置页滚动 | 连续滚动 ~10s（adb swipe 驱动） | 57 | 16.7 | ⚠️ 健康但**负载污染**：adb swipe + uiautomator dump 本身占帧预算，非手指滚动口径；只作「无灾难性掉帧」参考 |
| 对话页滚动（长会话） | 同上 | 未采集 | 未采集 | ⛔ 导航摩擦（锁屏/退桌面），未单独取证 |
| 流式生成期 | 一次完整生成 | 未采集 | 未采集 | ⛔ 窗口只覆盖最近 2s + 导航不可行（采集需停在诊断页，而生成发生在对话页）⇒ 生成期干净数据缺口，登记 W80-07/08 残留 |

- **观测者效应实录（本表最重要的行）**：采集器初版「每帧重算即发布 StateFlow」⇒ 诊断页整页
  每帧重组 ⇒ 同一场景实测 **5–6 fps / 98.5% 掉帧**；发布限流 250ms（窗口仍每帧累积）后
  **57 fps / 16.7%**。**教训：性能观测面自身进重组路径时，发布频率就是被测对象的一部分**——
  该修复已随 W81-02 落地（`PUBLISH_INTERVAL_MS`）。
- **W80-08 标定判据**（预登记，维持）：若滚动期 janky% 显著高于生成期且模糊节点在掉帧帧的
  render thread 占比可观测 ⇒ 实施滚动降 blurRadius；若滚动期帧数据健康 ⇒ W80-08 closed-dead
  （证伪留痕）。**数据到手前不改模糊参数**（外审原话：先真机标定，不凭 blur 很贵直接改）。
  当前唯一可用数据点（设置页 57fps/16.7%，adb 驱动口径）倾向健康，但**不足以判死**——
  对话页/生成期缺口补上后再裁。
