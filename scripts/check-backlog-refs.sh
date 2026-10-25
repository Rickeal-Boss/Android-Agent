#!/usr/bin/env bash
# =============================================================================
# check-backlog-refs.sh —— 登记面 ↔ 台账 机械对账守卫（W83-01）
#
# 用途：
#   机械防「申报挂账但未入台账」蒸发（W71 教训：申报未入台账 = 下波静默丢失）。
#   arch-guard #42 是单向守卫——只查「ledger-era 交接 delta ID ⊆ 台账」，
#   检不出凭空新条目；本守卫补反向面：任何 docs/*.md 里申报（登记/挂账/转）
#   的 Wxx-yy 必须在 docs/15-backlog.md 主台账有行。
#
# 判据：
#   正则扫描 docs/*.md（含 handoff-*.md）中申报形态（宁可宽勿宁窄）：
#     (登记(为|成)?|挂账|转)\s*W\d{2}-\d{2}
#   对每个命中的号断言 docs/15-backlog.md 存在主台账行（`| Wxx-yy |`）；
#   缺失 ⇒ 打印「蒸发：<文件>:<行号> 申报 <号> 但台账无此号」并最终 exit 1；
#   全过 exit 0 并打印核对计数。
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

if [[ ! -f "${LEDGER}" ]]; then
  echo "check-backlog-refs: 台账缺失 ${LEDGER}" >&2
  exit 1
fi
if [[ ! -d "${DOCS_DIR}" ]]; then
  echo "check-backlog-refs: docs 目录缺失 ${DOCS_DIR}" >&2
  exit 1
fi

# 申报形态（ERE；关键字 + 波次号，\s 用 [[:space:]] 兼容 POSIX ERE）
CLAIM_RE='(登记(为|成)?|挂账|转)[[:space:]]*W[0-9]{2}-[0-9]{2}'

fail=0
checked=0

# grep -rnoH：file:line:match（-o 只出匹配段；一行多申报会出多条）
while IFS= read -r hit; do
  [[ -z "${hit}" ]] && continue
  file="${hit%%:*}"
  rest="${hit#*:}"
  lineno="${rest%%:*}"
  match="${rest#*:}"
  # 从匹配段提取波次号（匹配段恰为 关键字+号，最多一个号）
  id="$(printf '%s' "${match}" | grep -oE 'W[0-9]{2}-[0-9]{2}' | head -1)"
  [[ -z "${id}" ]] && continue
  checked=$((checked + 1))
  # 断言主台账行：行首 `| Wxx-yy |`（容忍 ** 加粗与空格）
  if grep -qE "^\|[[:space:]]*\*{0,2}${id}\*{0,2}[[:space:]]*\|" "${LEDGER}"; then
    continue
  fi
  rel="${file#${REPO_ROOT}/}"
  echo "蒸发：${rel}:${lineno} 申报 ${id} 但台账无此号"
  fail=1
done < <(grep -rnoHE "${CLAIM_RE}" "${DOCS_DIR}" --include='*.md' 2>/dev/null | sort -u)

if [[ ${fail} -ne 0 ]]; then
  echo "check-backlog-refs: FAIL（共核对 ${checked} 处申报，存在蒸发缺口，见上）"
  exit 1
fi

echo "check-backlog-refs: OK（${checked} 处申报 ↔ docs/15-backlog.md 主台账核对通过）"
exit 0
