# AGENTS.md —— 本仓 agent 作业契约

> **这份文件给「未来接手本仓的 agent」看**，不是给人看的入门文档（人看 [`README.md`](README.md)）。
> 本仓 W1–W87 全部由 agent 驱动开发，所以这里写的是**在这个仓里作业的默认姿势**，不是通用建议。
>
> **纪律**：本文件只写「能指到仓内既有机制或台账 id」的规则。写不出出处的规则不写 ——
> 无出处的规则会变成「看起来像规矩、实际无人执行」的装饰。
>
> `Verified as of 2026-10-27`（W87 收尾波）。**日期戳过期即视为可疑**：先复核再引用。

---

## 一、身份与作业自信

你接手的是 **LiquidAgent**：端侧优先的 Android 原生 AI Agent（Kotlin / Compose + LiteRT-LM，
模型在设备本地跑，数据不出设备）。包名前缀 `com.rickeal.agent`，9 个 Gradle 模块。

**默认姿势 = 直接动手修，不是把问题交回用户。** 具体含义：

- 你**有**权限改代码、改守卫、改台账、改文档 —— 只要不越过下面第三节的能力边界。
- 「发现了问题但没修，只在报告里写一段」= **失败**，不是「谨慎」。本仓历史上最贵的债全部来自
  「结论写进了报告、修法没进代码」（`docs/15-backlog.md` 的 W68-01 数据回填事故是原型：
  两项待办在重写沿袭清单时静默丢失十波）。
- 但「自信」不等于「不回源」。**自信的对象是行动，不是判断** —— 判断必须回源码核验（见 §二.6）。

**本仓的方法论底色（三条，都从真实事故里长出来）**：

1. **证据优先**：任何论断都要能指到 `文件:行` / commit / 台账 id / 真机日志。推断可以写，但必须标「推断」。
2. **证伪优先于证实**：外审报告、交接描述、甚至主理人自己的判断，**都可能不成立**。W84 出现过
   「同一条换序路径被三方各证伪一次」——任何一方的结论都不足以直接采信，包括你自己的。
3. **「未触发」≠「已验证通过」**：没跑到的判据必须写「未走 / vacuous」，不能写成通过。
   本仓把「未触发的论断冒充已验证的排除」列为**强度等同假绿**的错误（W50 第 26 处「描述不成立」）。

---

## 二、Standing rules（常驻规则）

每条都指到仓内既有机制。**这些不是建议，是已经用事故换来的纪律。**

### 1. 改机制 / 改数字前，先全仓 grep 同关键词、同数字的**所有形态**

数字与关键词在文档里从来不止一处。改 `@Test` 计数、改守卫条数、改阈值、改文件名之前，
先 `grep -rn "<关键词>" --include=*.md --include=*.sh --include=*.kt .` 把所有形态找齐
（含中文/英文/带反引号/不带反引号/带行号/不带行号）。

- 机制：`scripts/arch-guard.sh` **#26**（`build.yml` 用例基线 ↔ 全仓 `@Test` 代码位**双向**一致，
  任一方向漂移判红）就是为了根治「计数链三连犯」而建的。
- 台账：`docs/15-backlog.md` 的 W83-01（登记面对账 + `scripts/check-backlog-refs.sh`）——
  同型「申报了挂账但台账 0 命中」在 W83 前已复发**第七次**。

### 2. 静态守卫的判据必须钉**行首代码形态**，否则自家注释会被判成违规

判据写成 `^[[:space:]]*funName\(` 这种行首锚形态，**不要**写成裸子串匹配 —— 否则
KDoc / 注释里提到该符号就会被判违规，逼出「改一句注释就假红」的坏守卫。

- 机制：`#24` / `#28` / `#30` / `#33` 都显式做了**注释位排除**（同一款修补做了四次，
  说明这是结构性坑，不是偶发）；`#25` 家族的注释**只留冻结阈值 + 理由**，实测行数一律以
  违规输出里的 `当前 $n 行` 为准，**不要在注释里写死行数**（W54 活 stale 事故：同一 commit
  一边订正注释数字、一边改代码净 +9 行 ⇒ 注释当场过期）。

### 3. 新增守卫的同步面是 **5 个**，少一个就是「加了守卫等于没加」

1. `scripts/arch-guard.sh` —— 注释头（编号 + 动机 + 失败语义）**+** 该条的 `check "..."` 行
2. `scripts/arch-guard-selftest.sh` 的 `KNOWN_GUARDS` 表
3. `scripts/arch-guard-selftest.sh` 的 `affected_guards_for_file` 映射表
4. `scripts/arch-guard-selftest.sh` 的该守卫 case 段（**红必真命中 + 绿 + fail-closed 三面**）
5. `scaffold()` 夹具文件（**不加夹具 ⇒ 干净树正例被误红**）

- 机制：`#60` 的注释头原话就是「四处同步 + 另需 scaffold 夹具」；`#53` 是**四方一致性断言**
  （映射表 ↔ `KNOWN_GUARDS` ↔ arch-guard ↔ case），专治「已映射文件新增守卫号静默少跑」。
- 编号纪律：`#53` 是 selftest 元 case 专属号，**禁止**用作守卫号。号**永不复用、永不重编号**。

### 4. `runCatching` **不能**兜协程内的 IO —— 它会吞掉 `CancellationException`

`runCatching` 捕获 `Throwable`，协程取消异常也被吞 ⇒ Job 从 `cancelled` 变 `completed`，
取消语义静默失效（本该停的 run 继续跑）。协程里的落盘 / IO 边界要用**显式 catch 且 rethrow
`CancellationException`**，或者按 W86-01 的单点收口范式处理。

- 台账：**W86-01**（落盘协程异常边界单点收口；全仓 92 处 `Scope.launch`，收口前只有 3 处有 try/catch）。
- 同族先例：W55 的 `sendMessageAsync` 必须 `try/catch` **且 rethrow**（不吞异常），
  否则模板失败自愈链会在真实路径上失效。

### 5. **告警的量级本身就是判据** —— 全仓逐条告警 = 没有告警

一条会打印几百行的检查，等于没有检查：没人读，真信号被噪声淹没。

- 机制：W86-05 的 `check-new-file-guard.sh --all` 首次实跑 **206 条**告警，加 100 行下限后
  仍 **123 条**（本仓受闸门文件只有 19 个，其余是「从未打算加闸门」的常规文件）⇒ 改成
  **行数下限 100 + top-10 封顶 + 两条聚合计数**。真信号判读 = 「top-10 里出现拆分产物」。
- 推论：新增任何「扫描全仓」的检查前，先问「命中几条？读得完吗？」。读不完就改判据，别改输出。

### 6. 报告 / 注释 / 交接里的**因果**都可能不成立 ⇒ 动手前回源码核验并标出处

被广泛引用的结论也可能是错的。已证伪的实例（全部在案，别重复踩）：

- 「`r_max = 8.4 chars/token`」—— **口径伪影**（把内容 chunk 数当 token 数），W67 B1 离线重测 **5.06**
  ⇒ `C` 16→9、窗口 →(5.06,10.11)、`THINKING_CHAR_BUDGET` 8192→4608。
- 「`Contents.toJson()` 单元素时返回字符串」—— **不成立**，`javap` 实测**恒返回 `JsonArray`**（W51 第 27 处）。
- 「v0.17.1 三层源码未找到 collapse」—— 读的是仓库 **tip**（tip 已删该方法），**版本边界陷阱**；
  真实收敛点在 v0.17.1 tag 的 `generic_data_processor.cc:105-112`（W55 定案）。
- 「tool ✅ ≠ 设置生效」—— `set-string-key` 接受、真写入、exit 0，而 App 侧 `runCatching` 静默回退（W74 最高价值实证）。

⇒ **纪律**：引用任何结论前，`git show` / 读源码 / 跑脚本三选一验证，并在产出里写「据 X（commit / 文件:行 / 台账 id）」。

### 7. 落盘失败**不回滚 UI**（磁盘真值由仓库 Flow 重发校准）

写失败时**不要**把内存态改回去。调用方（ViewModel）通常已经先改内存态再落盘；此刻回滚会让
用户看到「点了没反应」，而正确语义是「内存侧即时生效，磁盘真值稍后由仓库 flow 回灌校准」。

- 机制：`core-data/.../PersistenceGuard.kt` 的类头 KDoc 明文写了这条；`ChatViewModel.kt:512`
  同款纪律。全仓统一，不是局部取舍。
- 反面：W74 的 `set-string-key` 静默回退事故就是「写失败后偷偷改回」的用户可见版本。

### 8. 提交前跑本地闸门链（仓根裸跑，按序）

```bash
bash scripts/arch-guard.sh              # 59 条机械守卫
bash scripts/arch-guard-selftest.sh     # 守卫自测（CI 跑全量；本地可用 --only <n> / --affected 跑子集）
bash scripts/fulltest.sh                # 全量 JVM 单测（内部固定带 --continue，见守卫 #19）
bash scripts/line-table.sh --check      # 行数表漂移（单源派生自 arch-guard 的「总行数 ≤」条目）
bash scripts/check-backlog-refs.sh      # 交接 / 文档里的台账 id 是否真的存在于 docs/15
bash scripts/check-new-file-guard.sh    # 软守卫：新文件 / 增长 >50 行的 .kt 是否进 line-table
```

编译与 lint 需要本地 JDK + Android SDK：

- 历史上有 `_ci-tools/localbuild.sh`（增量 ≈15s 的本地构建封装）+ `_ci-tools/w82_fulltest.sh`，
  ⚠️ **`_ci-tools/` 是仓外目录、不进仓库**（换机器 / 换接手人即失联）⇒ **不要在文档里把它当既成前提**。
  没有它时：`./gradlew :app:assembleDebug` / `:app:lintDebug` / `test` 直接裸跑即可。
- ⚠️ W77 流程教训：本地闸门链**只跑 `assembleDebug` 没跑 `:app:lintDebug`** ⇒ CI 首跑必红。
  新增代码后本地必须补跑 lint。
- ⚠️ 不要在 `src/` 改动的同时跑多路并行 gradle 构建 —— 会损坏 Kotlin IC ABI 快照。

### 9. 工作树纪律（多 agent 并行时）

本仓经常多席并行改同一棵树。**禁止** `git stash` / `git reset --hard` / `git checkout --`
—— 这些会抹掉其他席已完成的改动，且无法区分「我的」和「别人的」。

- 派单时按**文件面互斥**切分；同一文件不要两个席同时改。
- `.md` 一律用编辑工具改，**禁用 `sed -i`**（会破坏行尾 / 编码，且本仓历史上 CRLF 事故修了多波）。
- 不确定自己该动哪些文件时，问主理人，**不要**「顺手清理」。

---

## 三、能力边界自陈（必须写进产出的诚实面）

以下是**本仓已知的取证盲区**。触及时**必须显式申报**，不能静默当作通过：

| 盲区 | 正确写法 |
|---|---|
| **无真机**（没有有线 adb / 设备不可用） | 真机判据写「**未走**」，不能推定通过。`uiautomator dump` 对 Compose 自绘节点（玻璃卡片下半区）**零捕获**，自动化工具点不到也 dump 不出 —— 这类项只能由人眼判读，agent 必须申报「工具盲区」。 |
| **无网络** | 下载完整性只能**静态核验**（比对 `ModelPresets.sha256` 与体积），不能声称「下载链路已验」。 |
| **Android 13 / SDK 33** | FGS 类型强制面（`foregroundServiceType` 的运行时强制）**无法取证**；高版本行为不要外推。 |
| **单设备矩阵** | 只有 OPPO PDRM00 / Android 13 一处 ⇒ 任何「OEM 通用」的结论都不成立（见 [`docs/17-known-limitations.md`](docs/17-known-limitations.md)）。 |
| **模型能力** | 端侧 1–4B 的指令遵循弱、工具调用不稳。**「模型没做到」不等于「代码有 bug」** —— 归因前先分清这两层（W68-10 closed-dead 的原型）。 |

**申报格式**：`✅ 已行使` / `⚠️ 部分行使（缺哪一面）` / `⛔ vacuous（未行使）` 三态，缺一不可。
`docs/10-device-acceptance.md` §11.0.1 的逐条台账就是这个三态的样板。

---

## 四、什么时候**才该**停下来问用户

默认不问。只有下面五类才停：

1. **破坏性 / 不可逆操作** —— 删分支、`git push --force`、重写历史、清设备数据、卸载用户模型。
2. **需要用户外部输入** —— 法务文本与托管地址（`docs/14` §C1 `termsVersion`）、签名密钥、
   tag 的落点与命名、上游 issue 提交（fine-grained PAT 对 `google-ai-edge` 组织**无写权限**，
   只能用户手动提交，见 `docs/14` §B）。
3. **超出当前波次范围** —— 本波派单没覆盖的改动，即使你看出来该做，也**先挂账**
   （写进 `docs/15-backlog.md` 再在交接留指针），不要顺手扩面。
4. **文档之间无法消解的冲突要求** —— 两处文档给出互斥的判据，且回源码也无法判定谁对。
   （先试着自己消解：回源码 > 找台账 > 找最新交接；只有全都不能定时才问。）
5. **要花钱 / 占配额 / 对外发声** —— 消耗真实额度、触发对外通信、占用共享设备窗口。

**不该停下来问的**（本仓历史高频误判）：判断类问题（该不该修、怎么修、用哪个方案）、
文档口径不一致（你自己合）、守卫判红（修代码或修判据，别问）、测试失败（回源码定位）。

> 悬置项有机械兜底：`docs/14` §C5 末「悬置默认路径条款」—— 连续 **N=3 波**无用户裁决
> ⇒ 自动走保守支（= 不做 / 零行为改动）并在 `docs/15` 记 `closed-dead` 留痕。
> **所以「等用户拍板」不是合法的拖延理由**：不拍板就自动收敛，你不能让它无限占排期心智。

---

## 五、Important Files

| 文件 | 为什么重要 |
|---|---|
| [`docs/15-backlog.md`](docs/15-backlog.md) | **挂账权威单一事实源**，四态：`active` / `parked` / `closed-done` / `closed-dead`。每条必含 **ID + 状态 + 重启前提 + 最后触碰波次**。交接文档只写 delta，不整表重抄。 |
| [`docs/14-user-actions.md`](docs/14-user-actions.md) **§E** | **唯一决策入口**（决策仪表板）。每项 = 一句话题面 + 台账 ID + 登记波次 + 已悬波数 + 状态 + 不裁决时的默认值。新增决策项**必须先加到本表**，再在交接挂账留指针。 |
| [`scripts/arch-guard.sh`](scripts/arch-guard.sh) | **59 条机械守卫**（`check` 调用实数；编号到 `#60`，`#11` / `#53` 是历史/元 case 空号）。改代码前先读对应条的注释头 —— 那里写着「为什么存在」和「失败意味着什么」。 |
| [`scripts/arch-guard-selftest.sh`](scripts/arch-guard-selftest.sh) | 守卫的守卫。`KNOWN_GUARDS` + `affected_guards_for_file` + 每个 case 的「红必真命中」断言。新增守卫**必须**同步（见 §二.3 的 5 个面）。 |
| [`docs/handoff-*.md`](docs/) | 逐波交接（最新在前，按文件名日期排）。**读最新一份就够上手**；更早的只在追溯「为什么这么改」时读。 |
| [`.github/workflows/build.yml`](.github/workflows/build.yml) | 主构建：debug APK + 单测 + lint baseline 门禁。**零密钥可跑通**。注意它的 `paths-ignore`（纯文档提交不触发）。 |
| [`.github/workflows/release.yml`](.github/workflows/release.yml) | 发布双轨（签名 release + debug）。`push.branches` 白名单 = `UI` / `harness` / `harness-improve` / `improve` —— ⚠️ **不含 canonical 的 `core-fix`**；`tags: - 'v*'` 独立于 `branches`，所以打 tag 会触发。 |
| [`docs/17-known-limitations.md`](docs/17-known-limitations.md) | **已知限制（有意不做）**。发布前必读：这里的每一条都是「知道、决定不做、有台账 id、有重启前提」，不是待办。 |
| [`docs/16-acceptance-criteria.md`](docs/16-acceptance-criteria.md) | 验收标准 id 化骨架（`AREA.NNN` + 双向一致性）。**当前是骨架 + 抽样，不是 59 条的完整替代**。 |
| [`CONTRIBUTING.md`](CONTRIBUTING.md) / [`docs/00-recon-brief.md`](docs/00-recon-brief.md) | 环境、约定、版本矩阵硬约束（**不得擅自升级依赖**）。 |
| [`NOTICE`](NOTICE) / `LICENSE` | **许可证红线**：本仓只取外部项目的**结构思想**，不搬运代码/文本。上游对照仓（Operit 系）许可证不兼容 ⇒ **零代码搬运、零文本搬运**。 |

---

## 六、Current Repository Notes（`Verified as of 2026-10-27`）

> ⚠️ **别因为文件存在就以为文档完整。** 下面这份清单本身是「诚实未完成面」的索引，
> 不是「已完成面」的证明。

- **分支**：`core-fix` 是 canonical（`docs/14` §E5，W86 裁决）。`main` 落后 **727 commits**。
  `improve` / `harness-improve` / `harness` 是历史线，已无前进提交。
- **发布**：**0 tag**。首个 tag 的硬前置 = `docs/14` §C1 法务 `termsVersion`（正式文本 + 托管地址，
  待用户提供）。`release.yml` 的签名 / R8 轨**已于 2026-10-27 手动 dispatch 实测一次**（run `40498254820`，
  ref `core-fix` / head `ce976ef`，success：assembleRelease + bundleRelease + apksigner v2/v3 断言 + R8 mapping 上传）
  —— 但 `core-fix` **不在** `release.yml` 的 `push.branches` 白名单内 ⇒ 该轨**不会自动跑**，
  **每次 RC 前需重跑一次 dispatch**（代码在动，R8 结论有时效）。
- **活数字**：`@Test` **874**（= `build.yml` 基线）｜arch-guard **59 条**｜`docs/15` **active 19** 条。
  这三个数字每波都会变，**引用前重测**（`grep -c` / `bash scripts/arch-guard.sh` / `docs/15` 状态列）。
- **已知限制**（有意不做，含台账 id 与重启前提）：全部在 [`docs/17-known-limitations.md`](docs/17-known-limitations.md)
  —— 真机矩阵单点（OPPO PDRM00 / A13）、冷启 TTFT p50 ≈13s、国产 OEM 灭屏冻结不可对抗、
  `Qwen2.5 1.5B · q8` 无 sha256 锚点、仅中文、无障碍未做、`litertlm` pin 0.17.1。
- **沿革断档**：README 波次段逐波记录到 **W74** 为止，**W75–W83 未逐波补沿革**（有意取舍）。
  这不是「那些波不存在」——需要结论请读 `docs/handoff-*.md` 对应文件。
- **已知的文档面缺口（本仓自己的诚实申报）**：`docs/16` 是骨架 + 抽样，59 条守卫**尚未**全量 id 化；
  `docs/15` 的「交接 delta ID ⊆ 台账」核对是**单向**的（W86-06 才补反向一致性 + 计数骤降告警）。

---

## 七、一句话总结

**回源、留痕、直接修。** 报告里的因果会错，注释里的数字会烂，交接里的「已完成」会过期 ——
唯一可信的是当前源码 + 当前脚本输出 + 台账 id。发现差就修；修不了就挂账（带 id 和重启前提）；
两者都做不到，才开口问。
