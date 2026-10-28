# W90 交接 · 收尾波 + final-cut 分支（2026-10-28）

> 定位：**首个 Release tag 前的最后一波**。本波做两件事：① 核验三份外审并落地其中成立的处方；
> ② 把 `termsVersion` 版本化机制落地（严守「先版本化、后换文本」硬时序）。随后按用户要求
> 在 `final-cut` 分支上做**全量改动审查**，审出两条 P1 并当波修掉。
>
> 分支拓扑：`core-fix`（`ea19062`）→ `final-cut`（`6468fd5` 加分支触发面 → `7473c0b` 审查修复
> → `446cc2a` 复审补丁）。**`final-cut` 是后续 tag 的落点候选**；`release.yml` 刻意不含该分支。

---

## 一、交付清单（按提交顺序）

| 提交 | 内容 |
|---|---|
| `ea19062` | W90 收尾波：三份外审辩证核验 + `termsVersion` 版本化 + 发布前治理件（10 文件） |
| `6468fd5` | `build.yml` 的 `push.branches` 加 `final-cut`（GitHub 网页提交，作者 Final Station） |
| `7473c0b` | **审查修复**：`承接` 判据静默失效 + 首启门禁不闭环 + 文档计数订正（6 文件） |
| `446cc2a` | 复审补丁：自检补 14/15 字符边界夹具 + `W68-08` 措辞去冗余（2 文件） |

---

## 二、审查结论：两条 P1 都是「机制形同虚设」，不是措辞问题

### P1-1 `check-backlog-refs.sh` 的 `承接` 判据已静默失效

- **病灶**：`ea19062` 把 `承接[^|]{0,14}Wxx-yy` 改成 `承接[^|]*Wxx-yy` + 事后 `length(mid) > 14`。
  awk 的 `match()` 是 leftmost-**longest** ⇒ `[^|]*` 吞到单元格内**最远**的 Wxx-yy；最远项一超
  14 字符就整段跳过，**近处合法项一起丢**。
- **实测**：真实台账 4 处 `承接` **全部归零** —— `W83-12→W84-15`、`W83-13→W84-16`、
  `W84-15→W83-13`、`W87-17→W83-12`（旧写法 4 对全空）；构造串
  `承接 delta 行 = **W84-15** 与一个更远的波次号并存（夹具串里的假号一律写成 `Wxx-99` 形态，见 §六.4）` 同样 0 候选。**这不是等价改写。**
- **修法**：`[^|]{0,14}` 的方言无关等价物 = **14 个 `[^|]?` 动态展开**
  （`for(k=0;k<14;k++) q=q"[^|]?"`），抽成 `CJ_AWK_LIB` 的 `cjInitRe`/`cjCands`/`cjCount`，
  **自检与生产共用同一段 awk 源码**（消除「自检一份、生产另一份」的漂移面）。
  同批修 a) `由…收口` 与 c) `转…` 的既有缺陷：内层 `match(seg, idre)` 会覆盖全局
  `RSTART`/`RLENGTH`，必须先存 `rs`/`rl`。
- **自检升级**：原自检只断言「正则能匹配」——**恰恰放过了这个 bug**。改为断言**候选集**
  （条数 + 具体号），五条夹具：plain / `…与一个更远的波次号并存（夹具串里的假号一律写成 `Wxx-99` 形态，见 §六.4）`（关键回归）/ `承接 abc` /
  14 字符填充（必须命中）/ 15 字符填充（必须不命中）。
  ⚠️ 后两条是复审席用变异探针追加的：**没有它们时把 14 改成 13 或 15 自检仍全绿**，
  而 13 会让真实台账 `W84-15` 行（恰好 14 填充）静默归零。

### P1-2 `FirstRunGate` 让「bump 版本号即重新征求」成为假陈述

- **病灶**：门禁早退只看 `hasSeenOnboarding`（老用户该位恒 `true`）⇒ `isTosAccepted` 因版本
  bump 变 `false` 也仍被直接放进主界面。全仓无第二处 TOS 门禁（另一消费点 `LegalScreen`
  只读徽标）⇒ `SettingsRepository.isTosAccepted` KDoc 里「未来 bump 版本号即可让老用户重新
  征求同意」是**代码级假陈述**。5 例纯函数单测对「谁来行使判据」是 vacuous ——
  **测试全绿 ≠ 口径被验证**。
- **修法**：抽 `firstRunStepFor` / `shouldEnterMainShell` 两个 internal 纯函数；放行判据改为
  `hasSeenOnboarding && tosAccepted`；TOS 的 `onAccept` 只在**首次走首启**时推进 GEMMA
  （重新征求场景保持停在 TOS 直到 Flow 回真：不闪 Gemma 帧，写失败则 fail-closed 留在 TOS）。
  新增 `FirstRunGateDecisionTest` 6 例，其中「bump 后拒进」是钉住闭环的回归闸。
- **行为面**：`CURRENT_TERMS_VERSION = 1` ⇒ v1 下零变化（legacy boolean=true 迁移为 1 ⇒ 通过）。

### P2（同批修）

- `docs/18` §六·A 的枚举命令按列号 `$4` 取状态，被 `W78-08` 描述列里的转义管道 `\|` 挤到
  `$5` ⇒ **漏一条 active**（实测逐格扫描 17 / 按 `$4` 16，差集恰为 `W78-08`）。已改逐格扫描
  + 补第 17 行 + 计数口径 18→19；`docs/17` 的 active 计数同步 19。
- `docs/18` 第 5 行原写「`termsVersion` 版本化未落地」，与同一提交的落地事实矛盾 —— 已订正。

---

## 三、门禁基线（`446cc2a` 实测）

| 面 | 值 |
|---|---|
| 严格 `@Test` | **901**（`build.yml` baseline 同步 901；890 → 895（termsVersion 5 例）→ 901（门禁 6 例）） |
| 全量单测 | `tests=901 failures=0 errors=0 skipped=0`，9 模块全覆盖，103 个 TEST-*.xml |
| arch-guard | **61 项**全通过；自测 `PASS=286 FAIL=0` |
| line-table | 21 行（文件级）+ 1 行（方法级 #12）≥ 冻结下限 21 |
| docs16-parity | 条目 9（已机械化 5 / 白名单 4）；引用守卫 14；check 61 ⇒ 待迁移 47 = 白名单 47 |
| backlog-refs | 34 处申报核对通过 |
| new-file-guard | OK（21 个受闸门文件 / 2 个包级目录） |
| lint | 0 新增（`3 errors + 1 hint` 由 baseline 过滤，精确等于 arch-guard 第 14 项冻结值 4） |

---

## 四、CI

- `core-fix` / `ea19062`：Build `40683192741` success；Release `40683960004` success
  （**签名链路已真跑**：keystore 解码 / release APK+AAB / 签名校验 / R8 mapping 全 success）。
- `final-cut` / `6468fd5`：Build `40689879262` success（三 job 全绿，`tests=895`）。
- `final-cut` / `70dd34e`：Build `40700028025` **failure** —— 唯一红点是 `Architecture guard`
  的「ledger-era 交接 delta ID」项：本交接首版把判据夹具串原样抄进来，串里的假波次号
  被判「交接有、台账无」。**守卫是对的，改文档不改守卫**（见 §六.4）。
- `final-cut` / `0909f36`：Build `40701736966` success（`workflow_dispatch`；三 job 全绿，
  `[fulltest] tests=901 failures=0 errors=0 skipped=0`、`awk: GNU Awk 5.2.1 / /usr/bin/gawk`、
  `[docs16-parity] OK 条目 9 / 守卫 14 / check 61 / 待迁移 47`）。
  ⚠️ 该 run 是 dispatch 而非 push：修守卫判红的那次提交是 docs-only，`paths-ignore` 不触发
  build.yml（这是既定口径，不是绕过门禁）。

---

## 五、真机验证状态（如实标注）

本波**未做真机验证**：改动集中在 shell 判据、纯函数门禁与文档，无 UI/引擎行为改动；
`CURRENT_TERMS_VERSION = 1` 使首启门禁在现网形态下零变化。真机侧最后一份判据仍是
W89 的 `gfxinfo` 数字（见 `handoff-20261027-wave89-ui2.md`）。

---

## 六、残余风险与未做项

1. **自检只覆盖 `CJ_AWK_LIB` 库函数，不覆盖生产调用点**（`cands = cands cjCands(t)` 这一行
   被单独改回旧内联时自检仍绿）。复审席已用变异探针确认此边界；彻底堵住需要
   arch-guard selftest 的 fixture 台账里放一条 `承接` 行并断言 stderr 出告警 —— 未做。
2. **mawk 未实证**：本机无 mawk，CI 实测是 `GNU Awk 5.2.1`（`build.yml` 已回显 awk 实现）。
   「方言无关化」的收益是**不依赖作者记得 CI 用哪种 awk**，但 mawk 路径本身没有实测样本。
3. **`W68-08` 仍 active**：剩余阻塞只有用户侧两项输入（正式法务文本 + 托管地址）。
   机制侧已就绪：bump `CURRENT_TERMS_VERSION` 即触发老用户重新征求（门禁已闭环）。
4. **本文件首版曾被自己的守卫判红（如实记录）**：§二 里把判据夹具串原样抄进了交接，
   串中的假波次号被 `ledger-era 交接 delta ID` 守卫判为「交接有、台账无」（W58 式丢账形态）。
   ⇒ **文档里引用夹具串时，假 ID 一律写成 `Wxx-99` 形态**（不含数字，不会被 ID 正则命中）；
   不要为了让例子「看起来真」而写一个格式合法的假 ID —— 那正是守卫要抓的形态。

---

## 七、下波起手

- 台账：`docs/15-backlog.md`（active 精确口径 17 条 + `W74-05` / `W77-04` 两行非恰好 active = 19）。
- tag 日流程：`docs/18-tag-runbook.md`（§六·A 已按 17 条逐条判定；唯一阻塞项 `W68-08` 走 §零 U1/U2）。
- 推送纪律：`final-cut` 在 `build.yml` 白名单内、**不在** `release.yml` ⇒ 打 tag 前手动 dispatch
  一次 `release.yml` 留痕（签名链路在 `ea19062` 上已验证，风险低）。
