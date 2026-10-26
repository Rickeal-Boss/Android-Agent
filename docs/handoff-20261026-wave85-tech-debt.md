# W85 交接 — scripts + docs 治理波（技术债收口，2026-10-26）

分支 `core-fix`，tip `83179a3`。树干净。本波 = **W85 E 批（scripts + docs 治理）**：只改 `scripts/**` 与 `docs/**`，
**未触碰** `src/` 任何业务文件、`build.yml`、`gradlew`；**未运行 gradle**（多路并行构建会损坏 Kotlin IC ABI 快照）。
`@Test` 计数与 CI 实跑结果由主理人最后回填。

## 一、波次范围与输入

本波聚焦 **6 项技术债收口 + W84-15 务实版**，并显式 deferred 两项。输入 = W84 交接 §八 的 W85 候选清单 +
主理人本轮派单（W84-14 脚本 / W85 台账登记 / W85 交接文档三项归本席）。

| 项 | 本波处置 | 落点（据 W84 交接 §二 / 台账 W84-xx） | 负责批 |
|---|---|---|---|
| W84-14 新文件守卫脚本 | closed-done | `scripts/check-new-file-guard.sh`（本席新建） | E 批（本席） |
| W84-17 journal 尾读 | closed-done | journal 存储 `findUnsettled`（ChatRunRecovery 调用链） | B 批 |
| W84-19 引擎闸门 CAS | closed-done | `LiteRtLmEngine.kt` `generateStream` | A 批 |
| W84-20 两处余量告急拆分 | closed-done | `FileTools.kt`→`FileArgNormalizer.kt`；`DiagnosticsScreen.kt` 抽 `LogRow` | B 批 + C 批 |
| W84-21 两处可测件补测试 | closed-done | `ChatMessageList.kt` `isFirstRoundWithoutToolOutput`；`ModelsViewModel.kt` `onDelete` | C/D 批 |
| W83-14 可测性裁决 | closed-done | 台账行（D 批已改） | D 批 |
| W84-15 sha256 务实版 | 部分落地（务实版 done，离线锚点+DB 待下波） | `ModelPreset.kt` / `ModelDownloader.kt` | — |
| W84-16 journal reconcile→reclaim | **deferred（本波未做）** | — | 待方案席一页纸 |
| U1–U5 / 法务 / 无障碍 | **deferred** | — | 待用户拍板（W84-13 已恢复无障碍独立条目 parked） |

## 二、辩证核验结论（先给结论）

本波是纯治理波，无外部审查报告回源；但每条 closure 都经「落点文件 + 关键决策」钉死，不是空转销账：

- **六项技术债全部 closed-done**：W84-14（本席脚本，已自测）、W84-17/19/20/21、W83-14 由对应批落地并回填台账。
- **W84-15 务实版落地、但明确 cut-line**：只做「下载时计算 + 内存态 + 非空才校验 + 独立拒绝通道」，
  把「体积作拒绝依据」这条死路彻底堵死（B 岗实证 `importFromPath` size 强制复核不可直接执行）；
  **内置预设离线锚点回填 + DB 持久化留待下波**（状态保持 `active`）。
- **两项明确 deferred 并写明前置**：
  1. W84-16 —— 必须方案席先出一页纸（reconcile 判据 = runId+消息 id 双键、时机=冷启、源=journal），
     且**顺序不可反：先对账后 reclaim**，反序会拆掉终态答案崩溃窗口的唯一副本（救命绳）。
  2. U1–U5 / 法务 / 无障碍 —— 需用户拍板（外审 §四「决策摘要页」遗留项）。

⇒ 本波的方法论延续 W84：「任何一方的结论都不足以直接采信」——即便务实版已落地，仍把
「离线锚点回填 + DB 持久化」与「reconcile→reclaim 一页纸」显式挂起，不因「已做一部分」而把未做的半截假装完成。

## 三、落地清单

### 1. W84-14 `check-new-file-guard.sh`（scripts/，本席新建，LF）

- **落点**：`scripts/check-new-file-guard.sh`（新建，纯 shell，不依赖 gradle）。
- **关键决策**：
  - 软守卫 / 咨询级 —— **只打印警告，永远 `exit 0`**，不阻断正常新增文件；等机制稳定（接入 CI、误报率归零）再升级为硬门禁。
  - 扫描面 = 新增 `.kt`（`git diff --diff-filter=A`）；登记列表 = `arch-guard.sh` 的「总行数 ≤」守卫族
    （与 `line-table.sh` 同源同口径，**直接解析 arch-guard 而非调用 line-table**，故半成品仓内改动导致 line-table
    运行异常时本脚本仍可用）。
  - 排除 `build/`、`.gradle/` 与测试文件（`/test/`、`/androidTest/`、`*Test.kt` 等）—— 这二类不强制进 line-table。
  - ② 既有 `.kt` 净增长 >50 行且余量 ≤ `WARN_MARGIN`（默认 100）⇒ 提示「X 增长 N 行，余量不足，下一波动它前先拆分」。
  - **未接入 CI / 未改 arch-guard / 未改 build.yml**（硬约束：避免改动主流程）；台账 W84-14 行已注明「接入 CI 待下波评估」。
- **自测**：`mktemp -d` 夹具 git repo（含一个新未登记 `.kt` + 一个受闸门文件增长 60 行）
  ⇒ 两路警告均打出；干净态 `exit 0`。（详见 §八 新坑 3 的清理要点）

### 2. W84-17 journal 尾读（B 批落地）

- **落点**：journal 存储的 `findUnsettled`（ChatRunRecovery 调用链）；`ChatRunRecoveryTest` 全绿为判据。
- **关键决策（尾读窗口与回退）**：`RandomAccessFile` 从尾部反向读固定窗口（如 64KB / 末 200 行），
  命中 settled 即停；超上限则**回退全文读并打诊断**（保证正确性不退化）。JVM 单测覆盖三面：
  尾读命中 / 上限回退 / 截断半行。

### 3. W84-19 引擎在途闸门 AtomicBoolean CAS（A 批落地）

- **落点**：`LiteRtLmEngine.kt` `generateStream`。
- **关键决策（CAS 不变量）**：独立 `AtomicBoolean` 占位 CAS，闸门到自增之间包 `try/finally` 释放；
  **不得复用 `activeGenerations`**；**建会话必须先于自增**（不变量 1：否则重建闸门自等自死锁）。
  把 W84 如实申报的「check-then-act 窗口」焊死为硬约束。

### 4. W84-20 两处余量告急拆分（B 批 FileTools + C 批 DiagnosticsScreen）

- **落点**：`FileTools.kt` 把纯函数 `fileListPathArg` 外提到 `FileArgNormalizer.kt`（同包 `internal`，~25 行；调用点 `FileTools.kt:555` 与 `FileListPathArgTest` 不变）；`FileArgNormalizer.kt` 体量小、未单列行数闸门、`line-table.sh` 未扩面，`#54 FileTools ≤600` 仍覆盖拆分后 579 行（余 21，较 590 缓 11 行）。`DiagnosticsScreen.kt` 把日志区 `LogRow` 一族（`LogRow` + 私有辅助 `levelColor` / `LOG_MESSAGE_MAX_LINES`）抽到同包新文件 `DiagnosticsLogRow.kt`（~72 行，**`LogRow` 改 `internal` 供原文件调用、`formatLogTime` 在原文件改 `internal` 共用**）；`DiagnosticsScreen.kt` 实由 1137 → **1084 行**（#50 ≤1150 余 13 → 余 66），`#50` 只测该单文件、新文件不计入闸门，故余量缓解真实成立、且 `DiagnosticsLogRow.kt` 无需另立闸门。
- **关键决策**：触顶的正确处置是**拆分不是改阈值**（纪律重申）；拆分落点必须有自己的行数闸门（#45 KDoc 清单）。

### 5. W84-21 两处可测件补测试（C/D 批）

- **落点**：`ChatMessageList.kt` `isFirstRoundWithoutToolOutput`（private 纯函数，外提 `internal` 后 JVM 直测）；
  `ModelsViewModel.kt` `onDelete` 忙闸门判据（`loadingModelId != null` 或 `isEngineBusy()`，外提 `internal` 后 JVM 直测）。
- **关键决策**：各 3 条断言 ≈ 10 行，纯函数抽取后直测，不依赖 coroutines-test（全仓无此依赖，W83-14 裁决项）。

### 6. W83-14 可测性裁决（D 批已改台账行）

- **落点**：台账行本身（D 批改台账行的「可裁决事项」状态）；裁决方向 = 把「每处不可测点必须有纯函数抽取件」升格为 feature 层架构守则（沿 missingTaskProgress 先例），而非强制引入 coroutines-test。

### 7. W84-15 sha256 务实版（部分落地）

- **务实版已做（落点 `ModelPreset.kt` / `ModelDownloader.kt`）**：
  - 下载落盘后**计算 sha256**，与内存态锚点比对；**非空才校验**（预设锚点为空时跳过，避免离线未回填即误拒）；
  - 不匹配 ⇒ 走**独立拒绝通道**（拒 `.part` 转正 + 诊断页 ERROR 留痕），**不复用 size 闸门**；
  - 体积不符只打 WARN（钉死「体积永不作拒绝依据」这条裁决，避免重现下载死循环）。
- **明确 deferred（留待下波，状态保持 `active`）**：
  - 内置预设离线锚点回填（逐文件实测摘要写入 `ModelPreset.sha256` 默认值）；
  - DB 持久化（已下载模型摘要落库，跨 session 复用，避免重复计算）。
- **务实设计取舍**：B 岗实证 `importFromPath` 的 `sizeBytes` 强制复核不可直接执行
  （`sizeBytes` 是手写估算值；`sizeHint` KDoc 已明文裁决「DM 说下完了就不能因体积不符拒绝登记」，
  硬拒会重现下载死循环）⇒ 放弃体积拒绝，改 **sha256 锚点 + 独立拒绝通道**。这是「拒绝语义唯一机械依据 = sha256」的干净契约。

### 8. W84-16 journal reconcile→reclaim（deferred，本波未做）

- **前置（不可反序）**：方案席先出一页纸 —— reconcile 判据 = `runId` + 消息 id 双键、时机 = 冷启、源 = journal；
  **先对账后 reclaim**，反序会拆掉终态答案崩溃窗口的唯一副本（救命绳）。
- 一石三鸟 = W68-01 收口 + 磁盘治理 + W82-17 schema 收敛；schema 显式版本字段随 envelope 迁移落地。
- 设计草案见 §九。

## 四、闸门结果（本地）

> **W86 回填（2026-10-26）**：本波未跑 gradle（并行构建冲突），结果由主理人接管后统一实跑并回填。

| 闸门 | 结果 |
|---|---|
| `:app:compileDebugKotlin` | BUILD SUCCESSFUL（全 9 模块） |
| `scripts/fulltest.sh` | **862 / 0 / 0**（852 → 862，新增 10 例：journal 尾读 4 + feature-chat 3 + feature-models 3） |
| `scripts/arch-guard.sh` | **57 / 57 OK**（EXIT=0） |
| `:app:lintDebug` | BUILD SUCCESSFUL，**0 新增**（baseline 吸收） |
| `@Test` 基线 | 852 → **862**（`build.yml` baseline 已同步，守卫 #26 双向核对通过） |

## 五、真机验收（OPPO PDRM00 `13309cc8` / Android 13）

> **W86 回填**：本波无新增真机判据需求项（纯 scripts + docs 治理）。W84-15 务实版的真机面
> （下载篡改 1 字节必拒）**在本波无法验证** —— 当时 14 条预设锚点全空 ⇒ 校验分支永不进入
> （外审第二轮 B-2 的「全链路 vacuous」判据）。该项已随 **W86-02 锚点回填（13/14）** 通电，
> 并列入 **W86-07 设备窗口批** 待真机取证。

## 五之二、CI 实跑（W86 回填）

- **run `40331796224`（push，head `6b01caf`）三 job 全 success**：Unit tests 2m19s / Lint (baseline gate) 3m36s / Assemble Debug (JDK 21) 3m33s。
- 独立查证方式：`GET /repos/Rickeal-Boss/Android-Agent/commits/6b01caf/check-runs` → 3/3 success。
- ⚠️ 沿革说明：本波断了一次惯例（W81/W82/W83/W84 每波都有「CI 实证回填」commit 或章节）——
  外审第四轮 F3 点名此事，本节即补账。

## 六、外审条目处置台账

- **本波主动不做（已挂账 deferred）**：W84-16（待方案席一页纸）、U1–U5 / 法务 / 无障碍（待用户拍板）。
- **落地**：W84-14 / W84-17 / W84-19 / W84-20 / W84-21 / W83-14 六项 closed-done；W84-15 务实版（部分，active）。
- **与 W84 交接 §五 对齐**：W84-14 的对账模式（git diff 比对）可复用 A-2 思路；W84-15/16 的 deferred 与设计草案见 §九。

## 七、挂账权威表更新摘要（`docs/15-backlog.md`）

- `W84-14` `parked → closed-done`（描述补「脚本已建，接入 CI 待下波评估」）。
- `W84-17` `parked → closed-done`（B 批落地）。
- `W84-19` `active → closed-done`（A 批 CAS 落地）。
- `W84-20` `active → closed-done`（B 批 FileTools + C 批 DiagnosticsScreen 拆分）。
- `W84-21` `parked → closed-done`（C/D 批补测试）。
- `W83-14` `parked → closed-done`（D 批已改台账行）。
- `W84-15` 状态保持 `active`；描述补「W85 落地务实版（下载时计算 + 内存态 + 非空才校验 + 独立拒绝通道）已做；
  离线锚点回填 + DB 持久化留待下波」。
- `W84-16` 状态保持 `active`；描述补「W85 未做，待方案一页纸先行（顺序不可反：先对账后 reclaim）；需主理人/方案席出设计」。
- 新增 **W85 续更** 行（波摘要，只写事实；CI 结果由主理人回填）。
- ⚠️ 全部为既有行原地更新（单条目单权威行，符合 N-1 台账完整性），未新建重复 ID 行。

## 八、本波新坑与教训（铁律增补候选）

1. 🔴 **软守卫先只警告不 exit 1**：新机制早期误报会阻断正常开发，故 W84-14 定为咨询级，等接入 CI、误报率归零再升级硬门禁。
2. 🔴 **登记列表直接读 arch-guard 的「总行数 ≤」族**，与 `line-table.sh` 同源且**不依赖 line-table 运行态**——
   半成品仓内改动导致 line-table 运行异常时，本脚本仍可独立跑（这是「扫描面失效必须判红」契约在工具侧的镜像）。
3. 🔴 **夹具清理用 python `shutil.rmtree`**：`rm -rf` 盘符路径被 safe-delete 拦；git 持锁时 `rmtree` 偶发
   `PermissionError`，需 `onerror` 忽略或重试（不要用 `rm -rf` 盘符路径）。
4. **docs/*.md 必须纯 LF**：`README.md` 是唯一的 CRLF（index 存 CRLF），其余 `docs/*.md` 是 LF；改动一律用 Edit，
   禁 `sed -i`，改完 python 二进制核验 `b'\r'` 计数为 0 再交。
5. **台账改行必须守 7 单元格**：状态位用 awk 取「恰等于四态（active/parked/closed-done/closed-dead）」的单元格；
   单元格内禁止字面 `|`（用「或」代替），否则 awk 字段数漂移会让 check-backlog-refs.sh 误判。

## 九、下波建议（待设计 / 待用户拍板）

### 9.1 W84-15 设计草案：离线锚点回填 + DB 持久化（供主理人评审）

- **离线锚点回填**：为现有 14 个 preset 逐文件实测 sha256，写入 `ModelPreset.sha256` 默认值（硬编码常量或资源文件）。
  下载校验时：预设锚点**非空才比对**（与务实版「非空才校验」一致）；预设为空 ⇒ 跳过校验、仅内存态记录实际摘要，
  避免「离线锚点未回填即误拒」把正常下载卡死。
- **DB 持久化**：已下载模型的实测摘要落 `DataStore`/DB（key = modelId），跨 session 复用 —— 冷启后二次校验不必重算；
  与「非空才校验」配合：首次下载算并落库，后续直接比库值。
- **拒绝契约不变**：任何不匹配仍走独立拒绝通道（拒 `.part` 转正 + 诊断页 ERROR），体积不符仍只 WARN。
- **回归判据**：篡改 1 字节必拒；预设锚点空缺时放行（钉住「离线未回填不误拒」）。

### 9.2 W84-16 设计草案：journal reconcile→reclaim 一页纸（供方案席评审）

- **顺序铁律**：reconcile（对账）**必须**在 reclaim（回收）之前；journal 是终态答案崩溃窗口的唯一副本，反序 = 拆救命绳。
- **reconcile 判据**：双键 = `runId` + 消息 id；源 = settled journal；时机 = 冷启；动作 = 与会话存储对账补录终态答案
  （收口 W68-01 + 磁盘治理 + W82-17 envelope:1 裸数组收敛）。
- **reclaim 前置**：仅当 reconcile 校验通过才允许回收；schema 显式版本字段随 envelope 迁移落地（版本不符 ⇒ 拒绝 reclaim 并诊断）。
- **验收面**：冷启后终态答案可恢复（对照 W68-01 判据）；磁盘治理面（settled journal 体积受控）不退化。

### 9.3 其余候选（按优先级）

1. `check-new-file-guard.sh` 接入 CI：条件成熟后包装成 arch-guard 新守卫（**编号避 #53**，描述不写进 line-table 派生面），
   或作为 PR 门禁软检查（先 advisory、再硬门禁）。
2. U1–U5 / 法务 / 无障碍：需用户拍板（外审 §四「决策摘要页」遗留；W84-13 已恢复无障碍独立条目 parked）。
3. W84-20 拆分后的新文件必须各自带行数闸门（#45 KDoc 清单纪律），防止「拆分脱闸」重演（A-1 同族教训）。
