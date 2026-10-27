# Tag 日 Runbook（首个 tag / RC 发布）

> **`Verified as of 2026-10-27`**（W88 tip `dc930ca`，0 tag，`core-fix` 领先 `main` 744 commit）。
>
> 本页是**可执行的一页**：每步给命令 + **验证判据** + 失败处置。判据全部回源核实过
> `release.yml` / `build.yml` 的现役文本，不照抄外审草案（草案有两处与现役树不符，见 §七）。
>
> **纪律**：本页不新增挂账、不改判据。任何一步的「验证判据」不成立 ⇒ **停**，不要用「大概没事」推进。

---

## 零、开始前必须由用户表态的两项（不可代裁）

| # | 事项 | 台账 / 入口 | 为什么必须人来拍 |
|---|---|---|---|
| **U1** | **法务未就绪时 alpha tag 打不打** —— `termsVersion` 版本化是否先落地、正式法务文本与托管地址是否到位 | `docs/14 §E4`（`W68-08`） | 这是**硬时序**：`SettingsRepository` 的同意状态是无版本 boolean ⇒ 先换文本会让老用户被当作「已同意新版」（合规事故，不可逆）。工程侧只能提供事实，不能替用户承担合规判断 |
| **U2** | **`main` 是否 FF 到 `core-fix`** —— 首个 tag 打在哪条线上、`core-fix` 是否成为 canonical | `docs/14 §E5`（`C2`） | 分支拓扑是**对外可见的既成事实**（clone / fork / 外部引用都会跟走）。默认推荐 = `core-fix` canonical、`main` FF；但 FF 会一次性改写 `main` 的 744 个 commit 历史，必须用户确认 |

> 两项都拿到明确答复才进 §一。**未表态 = 不开始**（本仓 §D 的 N=3 自动收敛只适用于技术项，
> 不适用于法务与拓扑）。

---

## 一、台账考古（10 分钟，只读）

```bash
git fetch --all --tags
git log --oneline -1                      # 记下 TIP
git rev-list --count origin/main..core-fix
git tag | wc -l                           # 期望 0
grep -n "| active |" docs/15-backlog.md | wc -l
```

**判据**：`docs/15` 的 `active` 行**逐条**能回答「本 tag 是否被它阻塞」。
阻塞项必须在本页 §六 留一行豁免理由；**不允许**「先发再说」。

**失败处置**：出现无法归类的 active 行 ⇒ 回 §零 让用户裁决，不自行销账（`docs/15` 只由主理人波末统一改）。

---

## 二、两条硬臂（缺一不可，且必须**同一个 sha**）

| 臂 | 命令 | 验证判据 |
|---|---|---|
| **R1 门禁轨** | `gh run list --workflow=build.yml --limit 5` | 存在一条 `headSha == TIP` 的 run，且 **`assemble-debug` / `unit-tests` / `lint` 三 job 全 success**。注意 `unit-tests` 现在包含 `Docs/guard parity` 硬门禁 |
| **R2 发布轨** | `gh workflow run release.yml --ref core-fix` 然后 `gh run watch` | 该 run `conclusion=success`，且 `Assemble release APK` / `Assemble release AAB` / `Verify release APK signature` **不是 skipped**（skipped ⇒ 密钥没就位，R8 没真跑） |

**为什么要 R2**：`release.yml` 的 `push.branches` 只有 `UI / harness / harness-improve / improve`，
**不含 `core-fix`** ⇒ 日常推送**永不**触发 R8 + 签名轨；而 R8 结论有时效性（缩减规则 / 依赖矩阵 /
签名配置任一变化都会让「上次那条绿」不再代表当下）。`build.yml` 里新增的
`Release dispatch reminder (advisory)` 就是提醒这件事（只提示、不判红）。

**失败处置**：R1 红 ⇒ 修到绿再回本节（不得带着红臂选 RC）；R2 红 ⇒ 先分清是**密钥缺失**
（`Gate tag release on signing secrets` 在 tag 上会硬失败，分支上只 notice）还是 **R8/签名真失败**，
前者找用户补 secrets，后者修代码。

---

## 三、选 RC sha（一个 sha，不许「差不多」）

```bash
RC=$(git rev-parse core-fix)
git log --oneline -1 "$RC"
gh run list --workflow=build.yml --commit "$RC" --limit 1   # 必须非空且 success
```

**判据**：`RC` 同时满足 R1 与 R2 的**同 sha** 证据。R2 是 `workflow_dispatch` 起的，
其 `headSha` 必须等于 `RC` —— dispatch 跑的是 **ref 的 tip**，不是任意 sha（见 §七 订正①）。

**失败处置**：`core-fix` tip 在两条臂之后又动过 ⇒ 重跑 §二（不许拿旧 run 冒充新 sha）。

---

## 四、main FF（仅在 U2 = 「FF」时执行）

```bash
git checkout main && git merge --ff-only "$RC" && git push origin main
git rev-list --count origin/main..core-fix    # 期望 0
```

**判据**：`--ff-only` 成功（**不许** `-f`、不许 merge commit）；推送后 `origin/main` 的 tip == `RC`。
**失败处置**：`--ff-only` 失败 = `main` 有分叉 ⇒ 停，回 §零 重新裁决（分叉意味着「FF」这个前提不成立）。

---

## 五、打 tag 并观察发布轨

```bash
TAG=v0.1.0-alpha.1                     # 命名由 U2 的拓扑结论决定，alpha 后缀与 U1 结论绑定
git tag -a "$TAG" -m "LiquidAgent $TAG" "$RC"
git push origin "$TAG"
sleep 60 && gh run list --workflow=release.yml --limit 3
```

**判据（60 秒内逐条核）**：

1. 出现一条 `event=push`、`headBranch/tag == $TAG`、`headSha == $RC` 的 `release.yml` run；
2. 该 run 的 **`Gate tag release on signing secrets` 步骤 `conclusion=success`**（**不是 skipped**）——
   `skipped` 在 tag 语境下**不可能**是正常态（该步 `if: startsWith(github.ref,'refs/tags/v')`，
   tag 上必进；skipped 说明 ref 不是 tag，tag 打错了）；
3. `Publish GitHub Release` 步骤 `conclusion=success`。

**失败处置**：
- 60s 内没有 run ⇒ 先看 `gh run list --workflow=release.yml` 是否被 concurrency 排队；仍无 ⇒ 检查
  tag 是否真推到 `origin`（`git ls-remote --tags origin | grep "$TAG"`）。
- `Gate` 步 `failure` ⇒ 密钥不全。**tag 上这是设计内的硬失败**（debug 包不可上架、不可原地升级），
  处置 = 补 secrets 后**删 tag 重打**（§八），不要试图「让这一步变绿」。

---

## 六、核对产物与销账

```bash
gh release view "$TAG" --json assets --jq '.assets[].name'
```

**判据**：
1. Release 页有 **release APK**、**release AAB**、**`mapping.txt`** 三类 asset（`Publish GitHub Release`
   的 `files:` 就这三项；`configuration.txt` 只在 workflow artifact 里，不在 Release 页）；
2. `Verify release APK signature` / `Verify release AAB signature` 均 success（签名不是 debug 证书）；
3. `Verify 16KB ELF page alignment (debug APK)` success —— 该步跑的是 **debug APK**，`FAIL_ON_MISALIGNED='true'`
   （观察期已结束），so 与 release 同源，故它红 = 上架项红；
4. tag 名含 `-`（如 `-alpha.1`）⇒ Release 页必须带 **Pre-release** 标记
   （`prerelease: contains(github.ref_name, '-')`）；没有该标记说明 tag 名与预期不符。

**销账（三处，缺一处即视为没发完）**：
- `README.md` §现状快照：`0 tag` → `1 tag ($TAG)`，并同步 `CI 实证` 的 run id；
- `docs/15-backlog.md`：被本 tag 解除的 active 行 → `closed-done`（**主理人统一改**，本页只给清单）；
- `docs/14 §E`：E4 / E5 状态位落「已裁决」+ 结论。

---

## 七、外审草案的两处订正（回源核实结果）

| # | 草案说法 | 现役事实 | 影响 |
|---|---|---|---|
| ① | 「对该 sha 重跑 release dispatch」 | `workflow_dispatch` 只能指定 **ref（分支 / tag）**，不能指定 sha；跑的是该 ref 的 **tip** | 若 `core-fix` tip 已前移，必须建临时分支 `git branch rc-<sha> <sha> && git push origin rc-<sha>` 后 `--ref rc-<sha>`，用完删分支；否则「对该 sha 重跑」是做不到的动作 |
| ② | 「tag 推送后 60s 内出现 run 且 Gate 步骤**不再 skipped**」 | 该步 `if: startsWith(github.ref,'refs/tags/v')` ⇒ tag 上**必然执行**；`skipped` 只可能出现在分支 / dispatch | 「不再 skipped」不是判据，**`success` 才是**；`skipped` 应当被判为「ref 不是 tag」的异常信号 |

补充事实（写进判据，避免误判）：
- `release.yml` 的 `paths` 过滤**对 tag 不生效**（tag 无 changed files）⇒ tag 一定会跑，不看改了哪些文件。
- tag 推送**只触发 `release.yml`，不触发 `build.yml`** ⇒ 单测 / lint 的绿必须来自**同 sha 的分支推送**（§二 R1），
  tag 当天不会替你补跑。
- `concurrency.cancel-in-progress` 对 tag 为 `false` ⇒ 发布 run 不会被后来的 push 取消（但重复打同名 tag 会被拒）。

---

## 八、回退路径（先判「错在哪一层」，再动手）

| 现象 | 处置 | 判据（处置后必须成立） |
|---|---|---|
| **tag 打错 sha / 名字打错** | `gh release delete "$TAG" --yes` → `git push origin :refs/tags/$TAG` → `git tag -d "$TAG"` → 回 §五 重打 | `git ls-remote --tags origin \| grep "$TAG"` 为空；重打后新 run 的 `headSha == RC` |
| **R2 红（R8 / 签名 / 16KB 校验失败）** | **不删 tag、不绕过**：修到绿 → 回 §一 重新走一遍（新 sha 需要新的 R1 + R2） | 新 sha 的 R1 三 job 全 success 且 R2 success |
| **R1 红（单测 / lint / arch-guard / docs parity）** | 同 R2：修到绿再回 §一 | 同 sha 的 `build.yml` 三 job 全 success |
| **Release 页已建但 asset 缺** | 先看 `Upload release APK / AAB` 与 `Upload R8 mapping` 步骤结论；`Publish` 成功但上传失败 = 部分发布 | 修后**重打 tag**（GitHub Release 的 asset 不能跨 run 续传） |
| **发完才发现文档没销账** | 单独一个 docs-only commit 补 §六 三处 | 三处口径一致（`README` / `docs/15` / `docs/14 §E`），且 `grep -c "$TAG" README.md` ≥ 1 |

> **不许做的事**：`git push -f` 覆盖 `main`；删 `core-fix`；把 tag 从「对外发布」降级成「自用 debug 包」
> （那正是 `Gate` 步存在的理由：debug 签名包不可上架、不可原地升级，用户必须卸载重装、历史会话一起丢）。

---

## 九、一页速查（打印用）

```
U1 法务表态 → U2 拓扑表态 → 台账考古 → R1 build.yml 绿 → R2 release dispatch 绿
→ 选 RC（同 sha）→ [U2=FF] main FF → 打 tag → 60s 内 run + Gate success
→ Release 三类 asset + 签名 + 16KB → README/docs 销账
回退：tag 错 = 删 Release + 删 tag 重打；R1/R2 红 = 修到绿再回 R1（不跳步）
```
