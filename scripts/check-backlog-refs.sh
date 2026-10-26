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
fi

if [[ ${fail} -ne 0 ]]; then
  echo "check-backlog-refs: FAIL（共核对 ${checked} 处申报，存在蒸发缺口，见上）"
  exit 1
fi

echo "check-backlog-refs: OK（${checked} 处申报 ↔ docs/15-backlog.md 主台账核对通过）"
exit 0
