#!/usr/bin/env bash
# =============================================================================
# check-backlog-refs.sh —— 登记面 ↔ 台账 机械对账守卫（W83-01；W84 N-2/N13/P3-N2/P1 扩面）
#
# 用途：
#   机械防「申报挂账但未入台账」蒸发（W71 教训：申报未入台账 = 下波静默丢失）。
#   arch-guard #42 是单向守卫——只查「ledger-era 交接 delta ID ⊆ 台账」，
#   检不出凭空新条目；本守卫补反向面：任何申报（登记/挂账/转/补登/补挂/回填）
#   的 Wxx-yy 必须在 docs/15-backlog.md 主台账有行。
#
# 判据：
#   正则扫描**扫描面清单**（见下节）中申报形态（宁可宽勿宁窄）：
#     (补?登记?(为|成)?|挂账|补挂|回填|转)[[:space:]*_]*W[0-9]{2,}-[0-9]+
#   对每个命中的号断言 docs/15-backlog.md 存在主台账行（`| Wxx-yy |`）；
#   缺失 ⇒ 打印「蒸发：<文件>:<行号> 申报 <号> 但台账无此号」并最终 exit 1；
#   全过 exit 0 并打印核对计数。
#
# 扫描面清单（W84 N-2：此前只有 docs/，README 里的申报形态完全在视野外）：
#   ① `docs/` 下全部 `*.md`（递归；含 handoff-*.md / 各波交接与审查报告）
#   ② 仓库根 `README.md`（**存在才扫**，不存在不报错 —— 不把「文件不在」当违反）
#
# 已知边界（如实申报，勿当成 bug）：
#   · 只认「关键字 + 分隔 + 号」的**行内**形态。表格数据行 `| W84-01 | … |`、
#     纯 ID 列举（「W83-12/13」）都不算申报 —— 那是**台账本体**与**引用**，不是申报。
#   · 代码块 / KDoc / 引用块里的同形态文字**会计入**（宁可宽：误报的代价是补一行台账，
#     漏报的代价是整条挂账蒸发十波）。
#   · 只校验「号在台账有行」，不校验语义首词一致（语义一致是评审面，见 arch-guard #52
#     注释的「已知边界」段）。
#   · 号形态 `W[0-9]{2,}-[0-9]+`：波次号**不写死两位**（W84 教训：旧 `W[0-9]{2}`
#     下 `W100-01` 静默零命中 = 守卫自带过期日，`W84-100` 被截断成 `W84-10` 查错号）。
#
# 附加：候选排序 ↔ 台账状态位冲突**警告**（N-1 的机制判据，只警告不判红）
#   扫最新一份 `docs/handoff-*.md` 的「下波建议 / 候选」段（行首 `N. **Wxx-yy`），
#   对每个 ID 去台账查状态字段；若状态为 `parked` ⇒ 打印
#     「申报冲突：<交接文件> §候选 把 Wxx-yy 列为下波第一位，但台账状态=parked」。
#   为什么不判红：候选排序是**人的判断**，状态位是**机械事实**，两者冲突值得提示
#   但不足以阻断构建（排序可能有意超前于状态更新）。等机制稳定后再考虑升级红面。
#   ⚠️ 该警告打 **stderr**：arch-guard #52 包装在脚本成功时吞掉全部输出（check() 把
#      任何非空输出当违反）⇒ CI 里看不到，**本机制面向本地/开发者**，这是刻意取舍。
#
# 附加（本脚本共四条 advisory，全部 stderr + 不阻断 + 措辞带「仅提示，不阻断」）：
#   ① 候选排序 ↔ 台账状态位冲突（N-1）
#   ② 反向状态一致性：交接说「已实施」但台账仍 active（W86-06）
#   ③ 申报计数骤降（低于 MIN_CLAIMS ⇒ 复核扫描面/正则是否失效，W86-06）
#   ④ 承接行销账一致性：承接行已终态、源行仍 active/parked（W87-17，外审 N-B）
#
# 为何进 CI：
#   **已由 arch-guard #52 接线**（W83-05 闸门批落地，与 arch-guard.sh 同批进 CI）——
#   手工聚合面必腐（G1 同型第七次：W82 evidence 申报 W82-15/16/17 四条挂账、
#   台账 0 命中，外审 P0-1），只有 CI 里跑的机械核对能在下波开始前抓住蒸发；
#   靠人记得补登不可靠。#52 的包装口径：本脚本成功时的 OK 汇总行必须吞掉
#   （check() 把任何非空输出当违反），失败时原样吐出归因。
#   本地用法：bash scripts/check-backlog-refs.sh
# =============================================================================

set -u

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LEDGER="${REPO_ROOT}/docs/15-backlog.md"
DOCS_DIR="${REPO_ROOT}/docs"
README="${REPO_ROOT}/README.md"

if [[ ! -f "${LEDGER}" ]]; then
  echo "check-backlog-refs: 台账缺失 ${LEDGER}" >&2
  exit 1
fi
if [[ ! -d "${DOCS_DIR}" ]]; then
  echo "check-backlog-refs: docs 目录缺失 ${DOCS_DIR}" >&2
  exit 1
fi

# 申报形态（ERE）。分隔段 `[[:space:]*_]*` 而不是 `[[:space:]]*`：
#   实测盲区①——`转 **W83-12**`（关键字与号之间夹加粗/斜体标记）此前静默漏检。
# 关键字组补 `补?登记?(为|成)?` + `补挂` + `回填`：
#   实测盲区③——W83-01 自己的提交信息就是「补登 W82-15~18」，而旧正则不认「补登」。
#   ⚠️ 注意 `登记?`（登 + 可选「记」）而不是 `登记`：W84 夹具实测证伪了「只写 `补?登记`
#   就够」——「补登 W82-15」的「登」后没有「记」字，`补?登记` 对它**零命中**。故必须
#   把「记」也设为可选，才能同时覆盖 登记 / 补登记 / 补登 三种写法（实测见交付简报）。
# 波次号 `W[0-9]{2,}-[0-9]+`：
#   实测盲区②——两位写死 ⇒ `W100-01` 零命中（守卫自带过期日）、`W84-100` 截断成 `W84-10`。
CLAIM_RE='(补?登记?(为|成)?|挂账|补挂|回填|转)[[:space:]*_]*W[0-9]{2,}-[0-9]+'
# 提取用的号形态（与 CLAIM_RE 尾部**必须同步**：改一处漏改另一处 = 查错号）
ID_RE='W[0-9]{2,}-[0-9]+'

# 附加②：**反向状态一致性**警告（W86-06，外审第四轮 F1，只警告不判红）
#   本脚本原先只查单向「申报号 ⊆ 台账」——查不出**反向陈旧**：台账里号还在、状态位还是
#   `active`，但代码早就做完了。W84 一波就漏翻 9 条（其中 W84-01 是本仓返工代价最高的一处，
#   三方各证伪一次才收敛），下一波按台账排期会**重做已完成的工作**。
#   判据：扫**最新一份** handoff 里带「已实施 / 已落地 / 已销账 / closed-done / closed-dead」
#   字样的行，取其 `Wxx-yy`；若该号台账状态仍为 `active` 且行内无「部分完成」标注 ⇒ 提示。
#   为什么只警告：交接的「已实施」段可能写在**代码落地前**（如并行子代理在途），机械判红会
#   误伤；等机制稳定后再考虑升级（沿 N-1 冲突警告的同一条演进路径）。
#   ⚠️ 与上一条一样打 stderr（#52 包装在成功时吞 stdout，见文件头）。
#
# 附加③：**申报计数骤降**警告（W86-06，外审第四轮 P3-1）
#   `28 → 14` 这种变化是文档措辞演化的正常结果，但**同一个正则族永远追不上申报形态的自然
#   变异** —— 计数骤降既可能是「真的变少了」，也可能是「扫描面/正则失效」（静默缩水）。
#   故设一个冻结下限：低于它就在 stderr 提示复核（不判红，同「扫描面失效必须判红」契约的
#   软前置）。**下调该值必须写明理由**（与 line-table.sh 的 MIN_ROWS 同款纪律）。
#
# 📌 **申报数字纪律（W87-17 成文，勿凭外审报告的转述数改动本行）**：
#   任何「N 处申报」的数字**一律取 commit 后干净树的实测输出**（`bash scripts/check-backlog-refs.sh`
#   的 OK 汇总行），**不采信任何报告/交接里的转述数字**。实证：W86 收尾时两份外审报告分别称
#   此处为「16 处」与「32 处」，干净树实测 = **34 处**（两者皆不成立，若按报告数改 MIN_CLAIMS
#   会把阈值定成永远报警或永远静默）。原因：本脚本的计数是**扫描面 × 正则 × 文档当前措辞**的
#   函数，三者任一变动都会改数，而报告里的数字是**某一时点某个人手跑的一次快照**。
MIN_CLAIMS=24

fail=0
checked=0

# ---- 扫描面：docs/**.md（递归）+ 仓根 README.md（存在才扫）----
SCAN_PATHS=("${DOCS_DIR}")
if [[ -f "${README}" ]]; then
  SCAN_PATHS+=("${README}")
fi

# grep -rnoH：file:line:match（-o 只出匹配段；一行多申报会出多条）
while IFS= read -r hit; do
  [[ -z "${hit}" ]] && continue
  file="${hit%%:*}"
  rest="${hit#*:}"
  lineno="${rest%%:*}"
  match="${rest#*:}"
  # 从匹配段提取波次号（匹配段恰为 关键字+号，最多一个号）
  id="$(printf '%s' "${match}" | grep -oE "${ID_RE}" | head -1)"
  [[ -z "${id}" ]] && continue
  checked=$((checked + 1))
  # 断言主台账行：行首 `| Wxx-yy |`（容忍 ** 加粗与空格）
  if grep -qE "^\|[[:space:]]*\*{0,2}${id}\*{0,2}[[:space:]]*\|" "${LEDGER}"; then
    continue
  fi
  rel="${file#${REPO_ROOT}/}"
  echo "蒸发：${rel}:${lineno} 申报 ${id} 但台账无此号"
  fail=1
done < <(grep -rnoHE "${CLAIM_RE}" "${SCAN_PATHS[@]}" --include='*.md' 2>/dev/null | sort -u)

# ---- 附加（N-1，只警告不判红）：最新交接「下波候选」↔ 台账状态位冲突 ----
# 口径：候选段形态 = 行首 `N. **Wxx-yy`（可带说明文字）；只取**最新一份**交接
# （`sort | tail -1`：文件名带日期前缀，字典序即时间序）。
LATEST_HANDOFF="$(ls -1 "${DOCS_DIR}"/handoff-*.md 2>/dev/null | sort | tail -1)"
if [[ -n "${LATEST_HANDOFF}" ]]; then
  HANDOFF_REL="${LATEST_HANDOFF#${REPO_ROOT}/}"
  while IFS= read -r cand; do
    [[ -z "${cand}" ]] && continue
    wid="$(printf '%s' "${cand}" | grep -oE "${ID_RE}" | head -1)"
    [[ -z "${wid}" ]] && continue
    # 状态字段提取：取台账该行里**恰好等于四态之一**的单元格（不按列号硬编码 ——
    # 台账描述列含 `|` 或列数变动时按列号会取错，取状态词集合最稳）。
    status="$(grep -E "^\|[[:space:]]*\*{0,2}${wid}\*{0,2}[[:space:]]*\|" "${LEDGER}" \
              | head -1 \
              | awk -F'|' '{for(i=1;i<=NF;i++){c=$i; gsub(/^[[:space:]]+|[[:space:]]+$/,"",c);
                               if(c=="active"||c=="parked"||c=="closed-done"||c=="closed-dead"){print c; exit}}}')"
    if [[ "${status}" == "parked" ]]; then
      echo "⚠️ 申报冲突：${HANDOFF_REL} §候选 把 ${wid} 列为下波候选，但台账状态=parked（候选是人的判断、状态位是机械事实；仅提示，不阻断）" >&2
    fi
  done < <(grep -E "^[[:space:]]*[0-9]+\. \*\*${ID_RE}" "${LATEST_HANDOFF}" 2>/dev/null)

  # ---- 附加②：反向状态一致性（交接说「已实施」但台账仍 active）----
  # 触发词**只取实现声明**（已实施/已落地/已销账）。刻意**不**取 `closed-done`：
  # 那是**状态词**，常出现在「W84-15 → closed-done；遗留转 W86-09」这类一行多义的句子里
  # （W86 实跑命中过一次假阳性）⇒ 把状态词当实现声明会误伤。
  while IFS= read -r line; do
    [[ -z "${line}" ]] && continue
    # 行内带「部分完成」标注 ⇒ 允许仍 active（W84-06 就是半项完成的合法形态）
    if printf '%s' "${line}" | grep -q "部分完成"; then continue; fi
    for wid in $(printf '%s' "${line}" | grep -oE "${ID_RE}" | sort -u); do
      st="$(grep -E "^\|[[:space:]]*\*{0,2}${wid}\*{0,2}[[:space:]]*\|" "${LEDGER}" \
            | head -1 \
            | awk -F'|' '{for(i=1;i<=NF;i++){c=$i; gsub(/^[[:space:]]+|[[:space:]]+$/,"",c);
                             if(c=="active"||c=="parked"||c=="closed-done"||c=="closed-dead"){print c; exit}}}')"
      if [[ "${st}" == "active" || "${st}" == "parked" ]]; then
        echo "⚠️ 反向陈旧：${HANDOFF_REL} 有「已实施/已销账」表述提到 ${wid}，但台账状态=${st}（若为半项完成请在行内标注「部分完成」；仅提示，不阻断）" >&2
      fi
    done
  done < <(grep -E "(已实施|已落地|已销账)" "${LATEST_HANDOFF}" 2>/dev/null \
             | grep -E "${ID_RE}")
fi

# ---- 附加④：**承接行销账一致性**警告（W87-17，外审 N-B，只警告不判红）----
# 病灶（实测）：`W84-15 → closed-done`（W86-02 通电 13/14 锚点销账），但其**源行 W83-12
#   仍 active** —— 台账里出现「承接行已收口、源行没跟着收」的幽灵行对。
#   为什么附加②查不到：附加②只查「交接说已实施 ↔ 台账仍 active」（**交接 → 台账**），
#   本判据查的是「**台账行 → 台账行**」的指针一致性，两者扫描面不同。
#   同型行对 W83-13/W84-16 目前双 active 尚一致，但 W84-16 一旦 closed 就会复现同病 ⇒
#   这条判据是**下一次**同型事故的唯一机械拦截面。
# 判据（**钉行首**，本仓铁律：判据必须锚定行首代码形态，否则自家注释/说明文字会被判成违规）：
#   ① 扫描面 = 主台账数据行，锚 `^\|[[:space:]]*\*{0,2}Wxx-yy\*{0,2}[[:space:]]*\|`（行首）；
#      ⇒ 正文、图例、续更段、本脚本自己的注释文字一律不进判据视野。
#   ② 取该行**自身**状态；仅 active / parked 才有「该不该收口」的问题（终态行直接跳过）。
#   ③ 行内提取三类**指针形态**（= 「本行指向另一行的销账关系」）：
#        a) `由 **Wxx-yy** 收口`（显式销账指针）
#        b) `承接 … Wxx-yy`（承接行 ↔ 源行，允许中间夹 14 字符：`承接 delta 行 = **W84-15**`）
#        c) `转 **Wxx-yy**`（遗留面转移指针）
#   ④ 指针目标行状态 ∈ {closed-done, closed-dead} ⇒ 源行该跟着收口，打 stderr 提示。
# 为什么只警告不判红：源行可能**部分完成** —— 承接行收口的是 delta 面，源行还有独立剩余面
#   （实证：W83-12 收口 sha256 锚点后，Qwen2.5 锚点与摘要落库仍作为 W86-09 独立挂账）。
#   机械判红会误伤这类合法形态 ⇒ 沿附加①/② 同一条演进路径（先咨询级，机制稳定后再议升级）。
# ⚠️ 与附加①②一样打 **stderr**（#52 包装在成功时吞 stdout，见文件头）。
# 本地处置：提示出现时二选一 —— ① 源行也改终态并加「由 Wxx-yy 收口」指针；② 源行保留
#   active/parked 但在行内写明剩余面（说明它为什么还没到终态）。
# ⚠️ 已知边界（如实申报，与附加①②同一边界）：状态列**写成散文**的行取不到「四态之一」
#   （实测 4 行：W68-06 `active（blocker 维持…）`、W77-04 / W78-10 / W78-11 的状态列是
#   整句说明）⇒ 本判据对它们**静默跳过**（self_st 为空 = 不判）。这不是漏报的借口：
#   要让本判据覆盖某行，先把它的状态列收敛成纯四态词。
#
# 【为什么用**单进程 awk** 而不是 bash 逐行 + grep】—— 实测性能判据，不是风格偏好：
#   首版实现（bash while + 每行 3 次 grep + 每目标 1 次状态查询）在本机 Git-Bash 上把
#   整个脚本从「几秒」拖到 **4 分 52 秒**（sys 4m33s = 全在 fork）。本仓波末清单要求
#   每波本地跑本脚本 ⇒ 5 分钟的工具**没人会跑**，守卫等于自动作废（「永远报警/永远不跑
#   = 同一个病」的同型）。改成一趟 awk 后回到秒级。⚠️ 别改回逐行 grep。
#   awk 侧依赖：区间表达式 `{0,14}`（gawk ≥3 / mawk ≥1.3.4 均支持；CI = Ubuntu 24.04
#   mawk 1.3.4、本地 = gawk 5.4，两处都实测过）。awk 的 stderr **不重定向**到 /dev/null：
#   万一 awk 方言不支持而报错，错误必须看得见（否则 advisory 静默消失 = 假绿）。
awk -v idre='W[0-9][0-9]*-[0-9]+' '
  BEGIN { FS = "|" }
  {
    # 行首钉形态（本仓铁律）：只认主台账数据行 `| **Wxx-yy** | …`
    if (match($0, /^\|[[:space:]]*\*{0,2}W[0-9][0-9]*-[0-9]+/)) {
      head = substr($0, RSTART, RLENGTH)
      if (!match(head, idre)) next
      id = substr(head, RSTART, RLENGTH)
      n++; rid[n] = id; rtext[n] = $0
      # 状态字段：取**恰好等于四态之一**的单元格（不按列号硬编码，同附加①②）
      st = ""
      m = split($0, cells, "|")
      for (i = 1; i <= m; i++) {
        c = cells[i]; gsub(/^[[:space:]]+|[[:space:]]+$/, "", c)
        if (c == "active" || c == "parked" || c == "closed-done" || c == "closed-dead") { st = c; break }
      }
      rst[n] = st
    }
  }
  END {
    for (i = 1; i <= n; i++) smap[rid[i]] = rst[i]
    for (i = 1; i <= n; i++) {
      # ② 只有 active / parked 才有「该不该收口」的问题（终态行直接跳过）
      if (rst[i] != "active" && rst[i] != "parked") continue
      t = rtext[i]; cands = ""
      # a) `由 **Wxx-yy** 收口`
      rest = t
      while (match(rest, /由[[:space:]]*\*{0,2}W[0-9][0-9]*-[0-9]+\*{0,2}[[:space:]]*收口/)) {
        seg = substr(rest, RSTART, RLENGTH)
        if (match(seg, idre)) cands = cands " " substr(seg, RSTART, RLENGTH)
        rest = substr(rest, RSTART + RLENGTH)
      }
      # b) `承接 … Wxx-yy`（中间夹 ≤14 字符：`承接 delta 行 = **W84-15**`）
      rest = t
      while (match(rest, /承接[^|]{0,14}W[0-9][0-9]*-[0-9]+/)) {
        seg = substr(rest, RSTART, RLENGTH)
        if (match(seg, idre)) cands = cands " " substr(seg, RSTART, RLENGTH)
        rest = substr(rest, RSTART + RLENGTH)
      }
      # c) `转 **Wxx-yy**`（遗留面转移指针）
      rest = t
      while (match(rest, /转[[:space:]]*\*{0,2}W[0-9][0-9]*-[0-9]+/)) {
        seg = substr(rest, RSTART, RLENGTH)
        if (match(seg, idre)) cands = cands " " substr(seg, RSTART, RLENGTH)
        rest = substr(rest, RSTART + RLENGTH)
      }
      split(cands, arr, " ")
      delete seen
      for (k = 1; k <= length(arr); k++) {
        tgt = arr[k]
        if (tgt == "" || tgt == rid[i] || (tgt in seen)) continue
        seen[tgt] = 1
        ts = (tgt in smap) ? smap[tgt] : ""
        # ④ 指针目标已终态 ⇒ 源行该跟着收口
        if (ts == "closed-done" || ts == "closed-dead")
          printf("⚠️ 承接行销账：台账 %s（状态=%s）指向的 %s 已是 %s，但源行未跟着收口 —— 若剩余面已独立挂账，请在 %s 行内写明剩余面；否则改终态并加「由 %s 收口」指针（仅提示，不阻断）\n",
                 rid[i], rst[i], tgt, ts, rid[i], tgt)
      }
    }
  }
' "${LEDGER}" >&2

# ---- 附加③：申报计数骤降提示（低于冻结下限 ⇒ 复核扫描面/正则是否失效）----
if [[ ${checked} -lt ${MIN_CLAIMS} ]]; then
  echo "⚠️ 申报计数骤降：本次仅核对 ${checked} 处 < 冻结下限 ${MIN_CLAIMS} —— 可能是文档措辞演化（正常），也可能是扫描面/正则失效（静默缩水）。请复核后再决定是否下调 MIN_CLAIMS。" >&2
fi

if [[ ${fail} -ne 0 ]]; then
  echo "check-backlog-refs: FAIL（共核对 ${checked} 处申报，存在蒸发缺口，见上）"
  exit 1
fi

echo "check-backlog-refs: OK（${checked} 处申报 ↔ docs/15-backlog.md 主台账核对通过）"
exit 0
