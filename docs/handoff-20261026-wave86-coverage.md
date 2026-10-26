# W86 交接 — 保护面覆盖度波（从 0 到 1，2026-10-26）

分支 `core-fix`，本波 2 commits（代码 + 文档）。输入 = Desktop 四份外审报告（第四轮深度审查报告 /
改进点清单 / W84-W85 改进清单 / improvement-plan-v2）+ 主理人逐条回源核验。

## 一、波次定性：不是「哪里错了」，而是「修好的范式没有复制到同族」

四份报告独立收敛到同一个结论，我复核后认同：**代码级 P0/P1 存量已清空，剩下的债集中在保护面的
覆盖度**，且三条都是同一形态 —— **样板已立、覆盖面为零或残缺**：

| 保护面 | 样板 | 覆盖 | 后果 |
|---|---|---|---|
| 落盘协程异常边界 | `P2-N1` 包了 3 处 | **3 / 92** 处 `Scope.launch` | 一次 DataStore IO 异常 = 进程死 |
| 下载完整性校验 | W84-15 通道完整 | **锚点 0 / 14** | 校验分支永不进入，0 次下载被校验 |
| 行数闸门 | W83-05 → #58 立了规矩 | 拆出的 2 个新文件**零闸门** | 「拆分脱闸」第三次复现 |

⇒ 本波只做三件「从 0 到 1」+ 治理对账，**不碰新功能面**。

## 二、辩证核验（先给结论：4 项修正 / 1 项证伪 / 1 项口径差异）

| 外审主张 | 核验 | 处置 |
|---|---|---|
| F4.2：sha256「算不出」应先重试，仍失败则**拒绝** | ⚠️ **部分修正**：拒绝要付 2~4 GB 重下代价，而「算不出」不构成篡改证据（引擎加载失败是可见的） | 改为「重试一次 → 仍算不出则**放行** + ERROR 留痕（文案与『不符』可机械区分）」。理由写进 `ModelDownloader.verifyBeforePromote` KDoc |
| P2-C / B-4：`--all` 全仓存量扫描「哪怕手动跑一次也能列出所有脱闸文件」 | ⛔ **形态证伪（方向对）**：首次实跑 **206 条**告警；加 100 行下限后仍 **123 条** —— 本仓受闸门文件只有 19 个，其余是「从未打算加闸门」的常规文件 ⇒ 逐条列出等于没有告警 | `--all` 改为 **行数下限 100 + top-10 封顶 + 两条聚合计数**；真信号判读 = 「top-10 里出现拆分产物」 |
| P1-B / B-2：14 条预设锚点回填 | ⚠️ **只能回填 13 条**：`Qwen2.5 1.5B · q8` 镜像体积 1597922856 ≠ 预设 1597931520（差 **8664 B**）⇒ 跨源同一性不成立 | 该条**显式留 null（fail-open）**并登记 W86-09；硬填会让 HF 主源下载被误拒。**「14/14」的目标被实测修正为「13/14 + 1 条例外留痕」** |
| F1：台账 9 条状态失真（W84-01~07/09/10） | ✅ 成立（28 个 active 中 9 个已完成） | 九行全翻 `closed-done`；W84-06 拆半项注明「部分完成」 |
| P2-E：并发拒斥「独立枚举值，一行改动」 | ⚠️ **不是一行**：需核 5 处消费面（`shouldSalvageOutput` / `ChatRunCoordinator` / `ChatScreen` / `TurnRecord` / `ChatViewModel`）+ 序列化兼容 | 已全核；结论是既有 `!= BreakerTripped` 判据天然把新值归到非熔断侧 ⇒ **无行为回归** |
| 报告称「全仓 78 处落盘写点」 | ⚠️ **口径差异**：我实测 `Scope.launch` = **92 处**（报告按各模块求和 78，口径未申报） | 交接按 92 记，差异如实申报 |

## 三、落地清单

### 1. W86-01 落盘异常边界单点收口（P1，`core-data`）

- 新建 `PersistenceGuard.kt`：`persistOrWarn(tag, block)` —— **显式先 catch `CancellationException` 并 rethrow**，其余 Throwable 记 warn 返回 null。KDoc 写清「为什么不用 `runCatching`」（吞取消 ⇒ Job 从 cancelled 变 completed ⇒ `joinAll`/`isCancelled` 语义漂移，W84 实摔）。
- `SettingsRepository` **全部** `DataStore.edit` 写点套壳（`updateInferenceConfig` 的「先 decode 磁盘现值再 transform」语义逐字保留）；`ModelRepository` / `WallpaperStore` 同法。
- `AppContainer.processScope` 挂 `CoroutineExceptionHandler`（**第二层**兜底；KDoc 声明它只拦 `launch`、拦不住 `async`）。
- `PersistenceGuardTest` 3 例（透传 / IOException 不抛 / **CancellationException 必 rethrow**）。
- ⇒ 覆盖面 3 处 → **仓库层全兜**，新增写点自动继承。

### 2. W86-02 完整性校验通电（P1，`core-data` + `core-model` + `scripts`）

- **13/14 锚点回填**：来源 = ModelScope `repo/files` API 的 `Sha256`；跨源同一性判据 = **仓库体积逐字节等于 `sizeBytes`**（后者 2026-09 取自 HF API）。判据与来源写进 `ModelPreset.sha256` KDoc。
- 缓冲 **8 KiB → 1 MiB**（4 GB 文件 read syscall 约 52 万 → 4 千；这个数字在锚点回填后才会被感知，故必须同批）。
- 新建 `Sha256Verifier.kt`：`Sha256Check { MATCH / MISMATCH / UNREADABLE }` 三态 + `sha256HexOf` / `checkSha256` 纯函数（JVM 可测）。
- `promotePartFile` 改走 `verifyBeforePromote`：只有 **MISMATCH** 拒绝转正；**UNREADABLE** 重试一次，仍失败则放行 + ERROR「无法完成校验（IO 错误，不是摘要不符）」。
- `Sha256VerifierTest` 4 例（含核心判据「读不到 ≠ 不符」）。
- 新建 `scripts/fetch-preset-sha256.py`：离线可重跑的复核工具，**已知例外显式登记**（`KNOWN_UNANCHORED`），只有**新的**体积不一致 / 已回填锚点变 STALE 才判红 —— 「永远 exit 1 的检查 = 没人跑」。

### 3. W86-03 并发拒斥独立终止原因（P2，`core-agent` + `feature-chat`）

- `TerminationReason.GateRejected`（KDoc 钉死「凡 `== BreakerTripped` 判据，本值都落非熔断一侧」）。
- `AgentRunner` 并发闸门拒斥出口改用它（`AgentRunner.kt` **净增 0 行**，仍 2838/2900）。
- `ChatScreen.terminationHintOf` 加一档：「（上一轮仍在生成，请等当前回复结束后再试）」。
- `SalvageOutputTest` +1 例钉住「GateRejected 不进熔断救援」。
- ⇒ 熔断统计不再混入「手快点了两次发送」的样本；用户不再被告知「已被安全熔断」。

### 4. W86-04 两处拆分脱闸文件入闸（P1，`scripts`）

- **#59 `FileArgNormalizer.kt` ≤80**（实测 21）/ **#60 `DiagnosticsLogRow.kt` ≤150**（实测 74），含「文件缺失 ⇒ `::error::` + exit 1」fail-closed。
- `line-table.sh` `MIN_ROWS` 17 → **19**（冻结下限恒等于派生行数，防整族删除）。
- selftest 四方同步：`KNOWN_GUARDS` / `affected_guards_for_file` / 红绿成对 case92/92b/93/93b / 元 case53 自动派生；另补 `scaffold()` 的夹具文件面（否则 `--only 59` 跑不起来 —— 这是开发席当波抓到的第五个同步面）。
- **没有小文件豁免条款**（纪律与实践择一对齐）。

### 5. W86-05 软守卫接线（P2，`scripts` + `build.yml`）

- `check-new-file-guard.sh` 加 `--all` 存量模式（见 §二 的 206 → 123 → top-10 演进）。
- `build.yml` 的 Unit tests job 加 **advisory 步骤**（`continue-on-error` + 回显 `::warning::` 到 stdout + 写 summary）—— 沿「报告先行、门禁后置」。
- 该脚本从此**获得了运行时机**：此前它只在人记得跑的时候才说话（W85 建好当波就抓到自己的违例，却没人听）。

### 6. W86-06 台账反向一致性 + 计数骤降告警（P2，`scripts`）

- `check-backlog-refs.sh` 加**反向 advisory**：最新 handoff 里带「已实施/已销账/closed-done」的行，其 `Wxx-yy` 若台账仍 `active`/`parked` 且行内无「部分完成」⇒ stderr 提示（夹具实测：命中 active、放过 closed-done、exit 0）。
- 加**申报计数骤降**提示：低于冻结下限 `MIN_CLAIMS=24`（当前实测 32）⇒ 提示复核扫描面是否失效。
- 台账九行翻 `closed-done` + W84-15 销账 + 新增 W86-01~10 十条 + W86 续更段。

### 7. 治理件

- `docs/14` 重建 **§E 决策仪表板**（唯一决策入口）：W82-15 / U1–U5 / W84-13 / 法务 / tag / bump / JankStats / **sha256 信任模型（E8，新增）** / i18n，每项带「登记波 / 已悬波数 / §D 状态 / 不裁决时的默认值」。
- **W82-15 按 §D 正式收敛为 `closed-dead`**（四波零裁决 > N=3，保守支 = 维持现状 + 文案披露，零行为改动）。⚠️ **用户裁决优先**，随时可救回。
- W85 交接 §四/§五 的「留空待填」已回填（本地闸门 + CI run `40331796224`）。

## 四、闸门结果（本地）

| 闸门 | 结果 |
|---|---|
| `:app:compileDebugKotlin` | BUILD SUCCESSFUL（全 9 模块，47s） |
| `scripts/fulltest.sh` | **870 / 0 / 0 / 0**，9 模块全覆盖，`TEST-*.xml` mtime 新鲜 |
| `scripts/arch-guard.sh` | **59 / 59 OK，EXIT=0**（#59/#60 新增） |
| `scripts/line-table.sh --check` | EXIT=0，**19 行**（文件级）+ 1 行（方法级），双提取面一致 |
| `:app:lintDebug` | BUILD SUCCESSFUL，**0 新增**（3 errors + 1 hint 由 baseline 冻结） |
| `scripts/check-backlog-refs.sh` | **32 处申报 OK，EXIT=0**（含新增两条 advisory 无假阳性） |
| `scripts/arch-guard-selftest.sh --only 59` | **PASS=5 / FAIL=0**（case92/92b 红绿成对 + 夹具逐字节还原） |
| `scripts/check-new-file-guard.sh --all` | 10 条告警 + 2 条聚合计数（123 个未登记文件 ≥100 行，最大 1275 行） |

`@Test` 基线 **862 → 870**（+8：`Sha256VerifierTest` 4 / `PersistenceGuardTest` 3 / `SalvageOutputTest` 1），
`build.yml` 沿革注释与 baseline 同步，守卫 #26 双向核对通过。

⚠️ **一次假失败（如实记录）**：首次 `fulltest.sh` 报 `BUILD SUCCESSFUL` 但「5 个 test-results 目录未能清除」
（W74-05 的 Windows 句柄锁形态）—— **脚本拒绝判绿是正确行为**。处置 = `gradlew --stop` + 6s + `shutil.rmtree`
清目录重跑，第二次得 870/0/0 干净汇总。CI（Ubuntu）无此问题。

## 五、真机验收（OPPO PDRM00 `13309cc8` / Android 13 / SDK 33）

| 项 | 判据 | 结果 |
|---|---|---|
| 冷启 | `am start -W` + logcat `Displayed` | **660 ms**（`Displayed +655ms`，LaunchState=COLD）—— 与 W84 的 713ms 同量级，无回归 |
| 正常生成未被新闸门误拒 | 会话文件 USER→MODEL | ✅ `Say` → `Say "hello"`，`finishReason=STOP`，TTFT 18884ms（冷加载）/ 9.69 tok/s |
| 崩溃现场帧预算（回归） | `am crash` → `last_errors.log` | ✅ 新记录「前 **5** 帧」**380 字符、无省略号**；同文件旧记录（W84 前）为「前 10 帧」**421 字符、行尾 `…` 被截** —— 新旧对照即证据 |
| 安装/启动异常边界（回归） | 冷启 + 生成 + 崩溃三路径 | ✅ 无新增 crash；`processScope` CEH 未误拦正常路径 |

⚠️ **未走（如实申报）**：① W84-03 权限档位真机判据（设置页切 READ_ONLY → 新对话 → 授权卡按新档位弹）——
需要构造写工具调用，本轮真机窗口不足，仍由 `NewConversationStateTest` 5 例确定性覆盖；② W86-02 篡改必拒
真机臂 —— 需要一次 1.5~4 GB 下载 + 单字节篡改，属 W86-07 设备窗口批的专项。两项均留在 W86-07。

## 六、外审条目处置台账

- **已实施**：第四轮 P1-A（W86-01）/ P1-B（W86-02，13/14）/ P2-C（W86-04+05）/ P2-E 统计面（W86-03）/
  F1 九行销账 + 反向 advisory（W86-06）/ F3 CI 回填 / F5 决策仪表板 + W82-15 §D 收敛；
  第二轮 B-1~B-5 全部；improvement-plan A1 前半（锚点回填）/ A3（M27 软守卫登记面加固）。
- **本波主动不做（已挂账）**：W86-07 设备窗口批（active）/ W86-08 `ModelsViewModel` 预拆（parked）/
  W86-09 Qwen2.5 锚点 + 摘要落库（parked）/ W86-10 校验期 UI 过渡态（parked，理由是状态机契约）/
  W84-16 reconcile→reclaim（等方案一页纸）/ W84-18（等 W86-08 先拆）。
- **修正外审处方 1 项**（sha256 IO 失败处置）、**证伪形态 1 项**（`--all` 逐条列出）、
  **目标修正 1 项**（14/14 → 13/14）。

## 七、挂账权威表更新摘要（`docs/15-backlog.md`）

- `W84-01~07 / 09 / 10` → **closed-done**（九行，各带代码锚 + 测试/CI 锚）；`W84-06` 保留 `active` 并注明「部分完成：file_list 半项已销，ON_DEMAND 半项未做」。
- `W84-15` → **closed-done**（锚点通电 + 缓冲 + 三态分流；遗留转 W86-09）。
- `W82-15` → **closed-dead**（§D 自动收敛，四波零裁决；保守支 = 维持现状 + 文案披露；可救回）。
- 新增 **W86-01~10** 十条（四要素齐全）+ **W86 续更**段。

## 八、本波新坑与教训（铁律增补候选）

1. 🔴 **全仓逐条告警 = 没有告警**：软守卫 `--all` 首版对 206 个未登记文件逐条 `::warning::`，加 100 行下限后仍 123 条。**告警的量级本身就是判据** —— 超过人眼能读的量，等于把信号埋了。修法 = 行数下限 + top-N 封顶 + 聚合计数，并在注释里写清「top-N 里出现拆分产物才是真信号」。
2. 🔴 **「永远 exit 1 的检查」和「永远 exit 0 的软守卫」是同一个病**：`fetch-preset-sha256.py` 若把已知例外（Qwen2.5 体积差）也算异常，工具就永远红 ⇒ 没人跑。必须把**已知例外显式登记**（带理由），只让**新**异常判红。
3. 🔴 **跨源同一性只能靠代理判据，且代理判据必须写进 KDoc**：一个 `sha256` 字段对三路直链，只有「体积逐字节相等 + 同名同仓」能作为「同一份字节」的代理。代理判据不成立时**留空**，不能「为了齐全」硬填 —— 硬填的代价是让正常下载被误拒。
4. 🔴 **「无法证伪」不等于「已证伪」**：IO 错误不是篡改证据。三态（MATCH/MISMATCH/UNREADABLE）比 Boolean 贵不了几行，但把「诊断指向错误方向」这个坑填掉了。
5. **新增守卫的同步面是 5 个，不是 4 个**：`arch-guard` 注释头 + `check` 行、`KNOWN_GUARDS`、`affected_guards_for_file`、selftest case、**`scaffold()` 的夹具文件**（最后一个由开发席当波抓到，否则 `--only 59` 直接跑不起来）。
6. **`--all` 类脚本必须能扛住真实树**：合成夹具跑绿不等于真仓可用（W85 的软守卫就是只跑过夹具）。**新脚本落地时至少对真实树跑一次**，否则「建成」会被误记为「生效」。

## 九、下波建议（W87 候选，按优先级）

1. **W86-07 设备窗口批**（8 项，每项 ≤15 分钟，设备在位）—— 含 W84-03 权限档位与 W86-02 篡改必拒两条本波未走的硬判据。
2. **W86-08 `ModelsViewModel` 预拆**（余 19 行；W84-18 四个子项全以它为落点，重启第一行就撞闸门）。
3. **W84-16 journal reconcile→reclaim**（一页纸已备，顺序铁律：先对账后 reclaim）。
4. **W86-09** Qwen2.5 锚点复核（需 HF 直连）+ 摘要落库。
5. **W83-12/13 数据面主线**继续；W84-08 熔断阈值回填同批。
6. **决策会**：`docs/14 §E` 九项（E1 已自动收敛，E8 是新的信任模型裁决）。

## 十、CI 实跑

- push：`6b01caf..8599935 core-fix -> core-fix`（2 commits）。
- **run `40350019795`（push，head_sha=`8599935`）三 job 全 success**：
  - **Unit tests**：success —— 内含 Architecture guard（arch-guard.sh 真仓 **59/59**）+ **全量 selftest**
    （含新增 case92/92b/93/93b 与元 case53 四方一致）+ fulltest.sh **870/0**。
  - **Lint (baseline gate)**：success（0 新增）。
  - **Assemble Debug (JDK 21)**：success。
- **本地 vs CI 一致性**：本地 fulltest 870/0、arch-guard 59/59、selftest `--only 59` PASS=5、
  line-table 19+1 —— 与 CI 全量口径一致。⚠️ 本地未跑全量 selftest（每 case 重跑 arch-guard.sh，
  200+ case 开销不可接受；用户已明确指导本地只冒烟、全量归 CI），CI 绿 = 全量真绿。
- 外审 F3 点名的「CI 实证回填」惯例，本波已恢复（W85 那次的缺口一并补上，见 W85 交接 §五之二）。
