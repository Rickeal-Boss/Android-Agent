# W76 · UI-change-1019 scene#56 全方位 UI 改进（交接）

> **分支**：`UI-change-1019`（自 `improve` tip `b29a771` 起）
> **起手依据**：`docs/handoff-20261019-wave75-kernel-finalization.md` + `docs/w75-ui-plan.md`
> **用户诉求**：scene#56 七项 UI 改进（浅色模式为主场景），要求本地测试 + adb 真机调试 + 多模态像素级分析
> **内核状态**：W75 已收尾，本波**零内核改动**（全部落在 UI / 设计系统 / 数据容器）

---

## 〇、结论先行

七项诉求**全部落地并真机验收**（六项改代码、一项经取证确认已存在）。五个批次分四次提交，
arch-guard 42/42 绿，真机像素判据逐项留痕（截图在 `_w76ui/`）。

| # | 用户诉求 | 结论 | 判据 |
|---|---|---|---|
| 1 | 浅色模式覆盖层文字改白 | ✅ | 抽屉/工作区/sheet/对话框全部白族；sheet 对比度 3.2:1 → 4.8~7.9:1 |
| 2 | 覆盖层四周白边框同步色 | ✅ | 内描边顶色 ×0.34、高光 ×0.35；顶边 4px 纯白亮边消失 |
| 3 | 沙箱工作区模糊 → 缩略图管线 | ✅（已存在，取证确认） | SHELL 缩略图管线实测生效（`OverlayBackdropBlur.kt`，2026-09-27 已实现） |
| 4 | 推理参数通底 + 与 Tab 隔断 | ✅ | sheet 底边到屏幕底；页签为独立清晰岛（撤 PANEL 补糊 + 12dp 间隙） |
| 5 | 模型库横向长条 Liquid scroll | ✅ | LazyRow 吸附、0.94 屏宽、长名两行完整可读（原双列截 6 行） |
| 6 | 附件"文件"按钮（txt/md） | ✅ | SAF text/plain+markdown → 读成 Attachment.Text；真机选中 test_note.md 成功 |
| 7 | Liquid Button 垂直拖出不失动态 + 横滑同步纵滚 | ✅ | 按压态保持：胶囊宽 532→556px（垂直拖出 170px），松手 531px |

---

## 一、真机基线取证（像素级，浅色模式 / overlayOpacity=0.45）

| 对象 | 采样值 | 判定 |
|---|---|---|
| 主页壁纸 | `#F6F1E4` | 米白 |
| 会话抽屉面板 | `#707581` | 深灰；标题深色字，白字对比度 5.2:1 |
| 沙箱工作区面板 | `#7F8591` | 深灰；原深色字不可读，白字 3.7:1 |
| 推理参数 sheet | `#687081` | 深灰；白字 3.2:1 |
| 附件对话框（独立窗口） | `#595F6B` | 与 overlayOpacity 无关（平台 dim + EmptyBackdrop） |
| 覆盖层顶边 | `#F8F8F9` 4px 渐变到面板色 | 白框来源 = 内描边 `glassBorderTop × 0.72` |
| 模型库双列卡 | 名字截成 6 行 | 布局破碎 |

**机制定位**：覆盖层表面恒深色 = THICK 玻璃（backgroundAlpha 0.36）+ 内 scrim
（glassShadow × overlayOpacity）+ 平台 dim。`onGlass` 随主题翻转（浅色深字）⇒ 暗底暗字。

**滑条极值实测**：overlayOpacity=0.99 时抽屉面板 `#0D1D3B`（白字 17:1）—— scrim 是强杠杆。

---

## 二、逐项实现

### Batch 1（commit `e8060b6`）覆盖层文字恒白 + 白描边同步色
- `GlassColorScheme` 新增 `onOverlay/onOverlayMuted/onOverlaySubtle`（恒白族，
  light/dark 同值）+ `asOverlayColors()`。
- 四个覆盖层根部改取 `asOverlayColors()` 并 `CompositionLocalProvider` 下发；
  嵌套 `GlassButton/GlassSegmented/GlassSlider/GlassIconButton` 自动继承。
- `liquidGlass`/`LiquidGlassSurface` 新增 `overlayPanel`（默认 false，70+ 调用点零变化）：
  内描边顶色 ×0.34、底色 ×0.5、高光 ×0.35。

> ⚠️ **坑（给后续接手）**：`val colors = LocalGlassColors.current` 是**组合期捕获**，
> `CompositionLocalProvider` 只惠及子组合 —— 覆盖层根必须自己取 `asOverlayColors()`，
> 否则标题仍深色（子组件白、标题黑）。

### Batch 2（commit `663e47b`）推理参数通底 + Tab 隔断
- sheet 玻璃去掉 `navigationBarsPadding` / 页签占位抬升 ⇒ 底边直达屏幕底；
  内容层补 `navigationBarsPadding + (页签占位 + 12dp)` 避让与间隙。
- **撤除 PANEL 场景的页签补糊**（`navBarBlurProgress`）：sheet 通底后页签浮在
  sheet 玻璃上，继续补糊会让两者融成一片灰雾（与"隔断"相反）。
- 新增 `OVERLAY_TEXT_MIN_SCRIM = 0.62f`：工作区/sheet 内层 scrim 取
  `max(用户滑条, 下限)`；sheet 全屏 dim 0.35→0.45。
  实测：工作区 3.7→5.5~5.9:1，sheet 3.2→4.8~7.9:1。

### Batch 3（commit `663e47b`）模型库横向长条
- `ModelStripCarousel`（ModelCard.kt）：`LazyRow` + `rememberSnapFlingBehavior`，
  每条 `fillParentMaxWidth(0.94f)`（露出下一条边），`graphicsLayer` 绘制期读
  `layoutInfo` 做 4%/8% 缩放淡出。
- `ModelCard` 改横向长条（min 248dp，删卡内 verticalScroll）；GPU 白名单 /
  knownBroken 确认 / 探测 / 删除 / 能力位编辑全保留。

### Batch 4（commit `332ac55`）附件"文件"入口
- `rememberFilePicker`（SAF，`text/plain` + `text/markdown`）。
- `ChatInputBar` 新增「文件」按钮，**不受 supportsImages/Audio 门控**。
- `ChatViewModel.onAttachFile` + `AppContainer.readTextAttachment`：流式读
  （128 KiB 上限）→ `Attachment.Text`。
  **刻意不用 `Attachment.File`**：引擎对它是显式 no-op
  （`LiteRtLmEngine` `is Attachment.File -> Unit`），只有 Text 会内联进提示词；
  内核冻结下这是零内核改动的唯一路径。
- 修 `displayName`：SAF document URI 的 lastPathSegment 是 `document:1000010056`，
  改查 `OpenableColumns.DISPLAY_NAME`（三个 picker 一并受益）。

### Batch 5（commit `d5e6e88`）液态控件手势
- `InteractiveHighlight` 新增 `continuousOnVertical`（默认 false 保持旧语义）：
  true 时纵向意图**不消费、不 break**，继续跟手直到抬手。
- 接入：GlassButton / GlassChip / GlassFab / GlassIconButton / GlassSettingRow /
  GlassTopBar；滑块/开关/页签保持 false（一边滚一边改数值仍是禁忌）。
- 上游对照：Kyant0 `InteractiveHighlight`（kmp 分支）用 `inspectDragGestures` ——
  从不消费、不按轴向打断，抬手才归位；本仓按"视觉控件 / 数值控件"二分落地。

---

## 三、项 3（沙箱工作区模糊）的取证结论

`OverlayBackdropBlur.kt`（2026-09-27）**已经**是缩略图管线（降采样 ≤256px →
缩略图空间逐帧模糊 → 放大铺回），`WorkspaceOverlay` 以 `OverlayBlurScope.SHELL`
登记，打开即生效（真机截图 22/36：面板左侧背景为平滑多级模糊）。
面板**自身玻璃**的 backdrop 采样的是壁纸（2026-09-26 起为纯色），模糊为视觉
no-op ⇒ 不存在"全分辨率 backdrop blur 开销"可优化。**本项无需改码**，
判据与截图留档；若用户指的是别的"模糊"，需指出具体页面/元素。

---

## 四、闸门与验证

| 闸门 | 结果 |
|---|---|
| `scripts/arch-guard.sh` | **42/42 绿**（exit 0） |
| `bash _ci-tools/localbuild.sh assembleDebug` | 5 批全部 BUILD SUCCESSFUL |
| 真机 | OPPO A13 `13309cc8`，浅色模式，逐项截图 + 像素采样 |
| fulltest / lint | 见本波 closeout（若失败项与既有基线一致则按基线口径） |

**真机操作坑（本波新增）**：
- 屏幕会快速休眠：每次交互前 `input keyevent 224`；截图全黑先查 `dumpsys power`。
- `input keyevent 4` 在对话页会直接退出 App（两段式返回在 adb 下不生效）⇒
  关闭覆盖层用界面内的关闭按钮/点外部，别用 BACK。
- 设置页滑条定位必须用 `uiautomator dump` 的 bounds（"0.02"读数曾误读为
  覆盖层不透明度，实际是推理参数 Top-P）。
- 合成手势验证按压态：`input swipe ... <长时长>` 放后台 + 并发 `screencap`
  抓中段；用像素包围盒（如胶囊宽度）判定按压缩放是否保持。

---

## 五、遗留 / 下一步

- `docs/w75-ui-plan.md` §2 的 UI-01..UI-06（evidenceLevel 徽章 / templateArraySafety
  展示 / 人格加档 / 音频能力位 / 批量工具 0 执行提示 / 工具超时可见性）**未动**，
  仍受 U1–U5 裁决约束。
- 项 3 若用户另有所指，需要指出具体页面。
- 覆盖层 scrim 下限 0.62 与「覆盖层不透明度」滑条的关系已在 `GlassConfig.kt`
  写明（滑条 <0.62 时被下限接管）；若用户希望完全由滑条决定，可去掉下限并
  改回深色文字族 —— 属产品取舍，需裁决。
