# W88 交接 — UI 收尾波（2026-10-27）

分支 `core-fix`，波前 tip `93d7f53`（W87）。输入 = 用户点名的四项 UI 改进
（按钮动效回弹 / Liquid 组件拖动层级 / 推理参数覆盖层可滑动退出 / 横屏全量适配）
＋ 主理人真机逐屏取证。

## 一、波次定性：收尾期最后一波「看得见的东西」

W87 之后代码级 P0/P1 已清空，本波不开新功能面，只修**用户能直接感知的交互质量**。
四项需求在回源核验后收敛成三类真缺陷（另有两类被现役树证伪，见 §二）：

| 需求 | 真缺陷 | 处置 |
|---|---|---|
| 按钮动效回弹 | `reduceMotion` 映射到 `Gentle`（stiffness **50** vs Default 400）⇒ **打开「减少动效」反而慢 8 倍**；`InteractiveHighlight` 的按压弹簧写死不读档位；`liquidPress` 用 `by` 委托每帧重组且 `visibilityThreshold` 截掉 29% 行程；分段项 `pressScale` 名不副实（实为选中态）⇒ 点未选中项零反馈 | W88-01 |
| 拖动层级覆盖 | `GlassSegmented` 的胶囊 `pressedScale = 60/48 = 1.25`，折射拷贝 1.25× vs 真实文字 1.0× ⇒ **按压双影**；`LiquidBottomTabs` 早在 2026-09-26 修过同一问题，分段控件从未接上那条通道 | W88-02 |
| 覆盖层可滑动退出 | 位移被 `AnimatedVisibility` 独占（手势无处插入）；模糊走**另一条**独立弹簧 ⇒ 拖动期位移与模糊必然错拍；判退后只能从满高重滑 | W88-03 |
| 横屏全量适配 | `useTwoPane` 只看宽度 ⇒ 横屏手机与平板同档，**工作区面板整体丢失**；参数面板 0.78×344≈268dp（弹键盘后可视 ≈5dp）；引导页零滚动；`LiquidDialog` 无高度上限 ⇒ actions 被顶出屏幕；空态 `fillParentMaxHeight(0.7f)` 把标题裁一半；NavRail 5 项溢出须滚动才能到「设置」 | W88-04 |

## 二、辩证核验（4 条处方被现役树证伪/降级）

| # | 主张 | 回源核验 | 处置 |
|---|---|---|---|
| V1 | 「`liquidPress` 是按钮主路径，改它就能统一手感」 | ⛔ 全仓唯一调用点是 `GlassSurface.kt:118`，真实消费只有 `ThinkingBlock` 与一张 GlassCard；7 类按钮走的是 `pressLayerBlock` + `InteractiveHighlight` | 改 `liquidPress` 只作为**正确性**修正（重组 + 阈值），**不**当作手感总开关 |
| V2 | 「给 `InteractiveHighlight` 加 `LiquidMotionSpec` 形参、默认取 `LiquidMotion.Default`」 | ⛔ 默认值与现值不等（0.5/300 vs 0.82/400）⇒ 忘记传参的调用点会**静默变手感**，推翻 2026-09-26 有录屏证据的结论 | 只加 `reduceMotion: Boolean` 布尔开关，**弹簧数值一个字不动** |
| V3 | 「抽屉会话行/工作区入口零反馈，一并补按压色」 | ⚠️ 部分不成立：会话行「点选后抽屉立即关闭，这本身就是反馈」是**有 KDoc 记录的刻意决定**；复制键实际用的是默认 ripple（非 `indication=null`） | 只给**删除键**补按压色（唯一没有即时结果反馈的危险动作），其余维持原判并写明理由 |
| V4 | 「横屏 NavRail 已处理（可滚动）⇒ 判 🟢」 | ⛔ 真机复现：344dp 高下 5 项 + 品牌字放不下，「设置」被挤到屏外，**必须滚动导航栏才能切页** | 判 🔴 并修（隐藏品牌字 + 压缩内边距/间距，48dp 触控目标不缩） |

## 三、落地清单

### 3.1 动效（W88-01 / W88-02）

- `LiquidMotion.Instant`（stiffness `1e5` / 临界阻尼）+ `LiquidAgentTheme` 映射订正；
  `Gentle` 的 KDoc 补「当前零消费，**不要**再拿它当 reduceMotion 的替代档」。
- `InteractiveHighlight(..., reduceMotion)`：`setPressed` 与归位的 `finally` 分支在
  reduceMotion 下 `snapTo`；拖动中的瞬时跟手保持不变（拖拽不是动画）。
  7 个 core-design 构造点 + `LiquidAgentApp` 的 NavRail 构造点显式传入，且
  `reduceMotion` 进 `remember` key（否则翻开关不重建实例 = 开关"不生效"）。
- `liquidPress`：State 持有 + `graphicsLayer` 内读 `.value`；`visibilityThreshold = 0.001f`。
- `GlassSegmented`：`pressScale` → `selectedScale`（选中态），新增 `interactionSource`
  驱动的真按压 0.97；**不**加第二层手势（分段拖拽已独占静态宿主）。
- `CyberJoystickSwitch`：`NoBouncy + StiffnessHigh` → `LocalLiquidMotion`。
- 双影：`GlassPressLayer.kt` 新增 `internal LocalPressScaleRegistration`，
  `LiquidBottomTabs` 的私有实现改为引用（行为零变化），`GlassSegmented` 新增 provider
  + `SegmentItem` 三量相乘（选中 × 按压 × 注册 `scaleY`），注册条件 = 真选中格且胶囊压在其上。

### 3.2 覆盖层（W88-03）

- `ChatParamsSheet`：删 `AnimatedVisibility`，`Animatable<Float>` + `Modifier.offset` 自持位移；
  头部 `draggable`（内容区仍 `verticalScroll`）；`CoroutineStart.UNDISPATCHED` 保证当帧跟手。
- 纯函数 `shouldDismissSheet(dragPx, velocityPxPerSec, heightPx)`：0.28×量程 或 900px/s；
  **上甩否决**、**高度未测量（0）永不判退**。4 例 JVM 单测，生产路径真的调用。
- `SheetDragHandle.progress` 单一进度源：位移、scrim（0.45×p）、`overlayBackdropBlur`
  三者同源。`OverlayBackdropBlur.kt` **零改动**（SHELL 场景继续用既有弹簧）。
- 四条关闭路径（scrim / 关闭键 / BACK / 拖拽过阈）仍走同一个 `onDismiss`，
  W83-02 边界② 的 `onParamCommit()` 不变。

### 3.3 横屏（W88-04）

- `WindowSizeClass.isLandscapePhone`（宽 ≥ MEDIUM 且 高 = COMPACT）：**不改断点、不加枚举**，
  竖屏恒 false。
- `LiquidAgentApp`：抽屉 / 工作区面板 / 边缘手势三处判据改
  `useTwoPane && !isLandscapePhone`（三处必须逐字同源，否则「有汉堡但滑不出抽屉」）。
- `ChatParamsSheet` 高度分档：竖屏 0.78 / 横屏 0.94 / 键盘态 1f。
- `OnboardingScreen`：中间区 `Box(居中) + Column(verticalScroll)`（短内容仍居中，长内容可滚）。
- `LiquidDialog`：`heightIn(max = 0.9 × 窗口高)` + 内容区 `weight(1f, fill = false) + verticalScroll`
  ⇒ 头部与 actions 恒在可视区，只有内容滚。
- `ChatMessageList`：空态高度档（COMPACT 吃满视口）+ 内层可滚 + `GlassEmptyState.verticalPadding`
  紧凑档 12dp（实测视口 ≈138dp，内容 106dp + 24dp 留白 = 130dp）。
- `GlassNavRail`：COMPACT 高度隐藏「Liquid」品牌字、`contentVerticalPad` 28→12、间距 6→4
  （预算 296dp > 需要 256dp）。
- `ConversationDrawer`：删除键补危险色按压底色（其余维持「点选即关闭 = 反馈」的既有裁定）。

## 四、闸门结果（本地）

| 闸门 | 结果 |
|---|---|
| `:app:compileDebugKotlin` | BUILD SUCCESSFUL（全 9 模块） |
| `scripts/fulltest.sh` | **881 / 0 / 0 / 0**，9 模块全覆盖，`TEST-*.xml` mtime 新鲜 |
| `scripts/arch-guard.sh` | **59 / 59 OK，EXIT=0** |
| `:app:lintDebug` | BUILD SUCCESSFUL，**0 新增**（3 errors + 1 hint 由 baseline 冻结） |
| `scripts/check-backlog-refs.sh` | 34 处申报 OK，EXIT=0（仅 W83-11 / W86-09 两条已登记 advisory） |

`@Test` 基线 **874 → 881**（+7：`WindowSizeClassTest` 3 + `SheetDismissPredicateTest` 4），
`build.yml` 沿革注释与 baseline 同 commit 同步（守卫 #26 会机械核对，首次跑就是被它拦下的）。

⚠️ **两次假失败（如实记录）**：`fulltest.sh` 首轮报「6 个 test-results 目录未能清除」
（W74-05 的 Windows 句柄锁形态）—— **脚本拒绝判绿是正确行为**，不是脚本 bug。
处置 = `python shutil.rmtree` 清目录 + 等 10s 让句柄释放 + 重跑；第二轮得 881/0/0 干净汇总。
CI（Ubuntu）无此问题。

## 四·1、CI 实跑

- push：`93d7f53..c12ee48 core-fix -> core-fix`（1 commit / 30 files）。
- run **`40521616097`**（push，head `c12ee48`）**三 job 全 success**：

| job | 结论 | 内含 |
|---|---|---|
| **Unit tests** | **success** | `arch-guard.sh` 真仓 **59/59** + 全量 selftest + `fulltest.sh` **881/0** |
| **Lint (baseline gate)** | **success** | 0 新增 |
| **Assemble Debug (JDK 21)** | **success** | — |

- **本地 vs CI 一致性**：本地 fulltest **881/0/0**、arch-guard **59/59**、对账 **34 处**、lint 0 新增 —— 与 CI 全量口径一致。

## 五、真机验收（OPPO PDRM00 `13309cc8` / Android 13 / SDK 33）

| 项 | 判据 | 结果 |
|---|---|---|
| 参数面板跟手拖拽 | `input motionevent` 拖到中途截图 | ✅ 面板随手指下移 ≈280px；背景由「糊到看不清」变为「可读」= 模糊逐级跟随 |
| 松手回弹（未过阈） | 拖 160px 松手 | ✅ 弹回满高，模糊恢复满强度 |
| 松手退出（过阈） | 累计 680px > 0.28×1872 | ✅ 面板滑出、**背景无残留模糊** |
| 按压态胶囊放大 | 按住分段项拖动 30px 截图 | ✅ 胶囊明显变大（1.25×） |
| 横屏 Rail | 5 项是否全部可见 | ✅ 全部可见（此前「设置」需滚动才出现） |
| 横屏空态 | 标题 + 两出口按钮 | ✅ 完整可见（此前标题被裁一半、按钮切边） |
| 横屏工作区 | 顶栏文件夹图标 + 面板 | ✅ **入口回来了**，面板可开、背景模糊正确 |
| 横屏推荐模型弹窗 | actions 是否可达 | ✅ 「关闭」在屏内（此前被顶出屏幕） |
| 横屏参数面板 | 高度档 | ✅ 0.94（≈323dp），此前 0.78（≈268dp） |
| 冷启 | `am start -W` | ⚠️ `LaunchState=UNKNOWN` + 3026ms（屏幕在 AOD，`Displayed` 行未产出；按既有口径不判回归） |

## 六、本波新坑与教训

1. 🔴 **`@Test` 基线守卫会先于你自己发现漏同步**：本波改完先跑 fulltest 得 881，再跑 arch-guard
   直接被 #26 判红（`全仓 @Test 代码位实数(881) > build.yml baseline(874)`）。**顺序应当是
   「先算数、再改 baseline、再跑守卫」**，否则每次都要多跑一轮 4.5 分钟的守卫。
2. 🔴 **`Animatable.snapTo` 在 120Hz 拖拽里必须 `CoroutineStart.UNDISPATCHED`**：每次 `launch`
   排队派发时，后一个 `snapTo` 会取消前一个（`MutatorMutex` 语义）⇒ 大量位移被丢，
   表现为「手指走 100px、面板只跟 60px」。与 `InteractiveHighlight.kt:304` 同一写法。
3. 🔴 **`useTwoPane` 是宽度判据，不能当「宽屏」用**：手机横屏 764×344dp 与平板同宽但高度差一倍，
   拿它当"宽屏"会一次砍掉两个入口（抽屉 + 工作区面板），而只有抽屉有替代。
4. **adb 截不到「按压态」**：`DampedDragAnimation` 的 `pressProgress` 由 `onDragStarted` 驱动，
   单纯 `motionevent DOWN` 不触发；必须 DOWN + 越过 touch slop 的 MOVE 才看得到按压形变。
5. **`LiquidDialog` 的内容区必须「弹性占位 + 自己滚」**：只给 Column 加 `heightIn` 不够 ——
   actions 仍会被挤出；`weight(1f, fill = false)` 才把剩余空间让给内容、把 actions 钉在底部。

## 七、挂账与后续

- `docs/15` 新增 `W88-01~05`（均 `closed-done`，含销账判据）。
- **不做并登记**：横屏挖孔左侧 107px 黑边（`windowLayoutInDisplayCutoutMode=shortEdges`
  需逐屏横向 `safeDrawing` 审计，超出本波范围）→ 建议并入 `docs/17`。
- 双影的**逐帧像素比对**未做（真机只复现到「胶囊放大」，文字同步量级 ≈2px@3x）——
  如需硬证据，建议下一波用 `screenrecord` 逐帧比对胶囊内外同一字形的边界。
- `docs/15` 候选段的两条 advisory（W83-11 / W86-09）按 W87 的既定处置继续「知道它为什么响」。

## 八、一句话结论

**四项 UI 需求全部落地并在真机上逐项复验：动效层修掉了「减少动效反而更慢」的语义反转与
分段控件的双影；参数覆盖层从「只能点关」变成「跟手拖拽退出 + 模糊逐级跟随」，且位移/压暗/模糊
由同一个进度源驱动；横屏从「功能缺失 + 内容被裁」变成「页签齐全、工作区可用、弹窗可达」。
闸门四项全绿（881-0-0-0 / 59-59 / lint 0 新增 / 对账 34），未新增守卫、未改依赖。**
