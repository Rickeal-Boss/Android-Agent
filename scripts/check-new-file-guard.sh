#!/usr/bin/env bash
# =============================================================================
# check-new-file-guard.sh — 新文件 / 大增长「行数守卫脱闸」机械检查（W84-14 / W85）
#
# 背景（外审 A-2）：「新文件 / 增长 >50 行的 .kt 必须进 line-table」这条纪律长期
#   靠人记 —— #35/#37/#48/#51/#56 五次「拆分新文件裸奔」全是人肉漏检后才补。本脚本
#   把这条纪律机械化，作为「软守卫 / 咨询级」工具。
#
# 扫描面（scope）：
#   · 新增 .kt 文件（git diff --diff-filter=A），逐个核对是否落在「行数守卫登记列表」；
#     未登记 ⇒ 打印警告「新文件 X 未进 line-table，增长将脱离行数守卫」。
#   · 既有 .kt 文件（git diff --numstat）净增长 >50 行，若其已逼近该行数闸门上限
#     （读 arch-guard 的「总行数 ≤」阈值，余量 ≤ WARN_MARGIN）⇒ 提示「X 增长 N 行，
#     余量不足，下一波动它前先拆分」（辅助信息，不判红）。
#
# 已知边界（exclusions，可选放宽）：
#   · build/ 与 .gradle/ 等生成目录 —— 永远排除（不是源码）。
#   · 测试文件（路径含 /test/、/androidTest/，或类名 *Test.kt/*Tests.kt/*Spec.kt/
#     *Mock.kt）—— 不强制进 line-table（测试代码体量不受行数闸门约束，属历史共识）。
#   这两类不在「必须登记」范畴内，本脚本对它们保持静默（不放警告，避免噪音）。
#
# 与 line-table.sh 的关系：
#   line-table.sh 的「登记列表」= 它从 scripts/arch-guard.sh 派生的受闸门文件集，其唯一
#   真源是 arch-guard.sh 的「总行数 ≤」守卫族（见 line-table.sh 单源原则注释）。本脚本
#   直接解析 arch-guard.sh 取得该集 —— 与 line-table.sh 同源同口径，且不依赖 line-table
#   的运行态（半成品 .kt / 仓内改动导致 line-table 运行异常时仍可用）。
#
# 行为契约（关键）：
#   · 本脚本是【软守卫 / 咨询级】，只打印警告，永远 exit 0 —— 不阻断正常新增文件。
#     等机制稳定（接入 CI、误报率归零）后再考虑升级为硬门禁（exit 1 / arch-guard 新守卫）。
#   · 只读 git diff 与 arch-guard 静态解析，不触碰任何 src/ 业务文件、不跑 gradle、
#     不修改仓库任何内容。
#
# 用法：
#   scripts/check-new-file-guard.sh                 # 以 HEAD 为基线，查工作树/暂存区新增
#   scripts/check-new-file-guard.sh --base <ref>    # 以指定基线上比对（如 merge-base）
#   scripts/check-new-file-guard.sh --warn-margin N # 余量告警阈值（默认 100 行）
#   scripts/check-new-file-guard.sh --help
#
# 退出码：恒 0（咨询级；即使有警告也不 exit 1）。
# =============================================================================
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel 2>/dev/null || echo "$SCRIPT_DIR/..")"
GUARD="$SCRIPT_DIR/arch-guard.sh"

BASE="HEAD"
WARN_MARGIN=100
while [[ $# -gt 0 ]]; do
  case "$1" in
    --base)        BASE="${2:-HEAD}"; shift 2 ;;
    --warn-margin) WARN_MARGIN="${2:-100}"; shift 2 ;;
    -h|--help)     sed -n '2,48p' "$0"; exit 0 ;;
    *)             echo "::warning::未知参数：$1（忽略；可用 --base / --warn-margin / --help）" >&2; shift ;;
  esac
done

# ---- 解析行数守卫登记列表（与 line-table.sh 同源：arch-guard 的「总行数 ≤」族） ----
# 每个守卫两行：
#   check "<文件> 总行数 ≤ <阈值>（…）" \
#     bash -c 'f=<仓库相对路径>
# 故：行号取自 `check "…"` 行，仓库相对路径取自其下一行的 `f=`（与 line-table.sh 同款提取）。
declare -A GUARDED_THR=()
if [[ -f "$GUARD" ]]; then
  while IFS=: read -r ln text; do
    [[ -z "$ln" ]] && continue
    thr="$(printf '%s' "$text" | grep -oE '总行数 ≤ [0-9]+' | grep -oE '[0-9]+$')"
    path="$(sed -n "$((ln + 1))p" "$GUARD" | sed -nE 's/^[^f]*f=//p')"
    [[ -n "$path" && -n "$thr" ]] && GUARDED_THR["$path"]="$thr"
  done < <(grep -nE '总行数 ≤' "$GUARD")
fi
GUARDED_COUNT=${#GUARDED_THR[@]}

is_excluded() {
  case "$1" in
    *"/build/"*|*"/build"*|*"/.gradle/"*) return 0 ;;
    *"/test/"*|*"/androidTest/"*|*"/testDebug/"*|*"/testRelease/"*) return 0 ;;
    *"Test.kt"|*"Tests.kt"|*"Spec.kt"|*"Mock.kt") return 0 ;;
  esac
  return 1
}

warn_count=0
note_count=0

# ---- ① 新增 .kt：是否进登记列表（脱闸警告） ----
mapfile -t new_files < <(git -C "$ROOT" diff --diff-filter=A --name-only "$BASE" -- '*.kt' 2>/dev/null)
if ((${#new_files[@]})); then
  for f in "${new_files[@]}"; do
    [[ -z "$f" ]] && continue
    is_excluded "$f" && continue   # 测试/生成文件：不强制登记
    if [[ -z "${GUARDED_THR[$f]:-}" ]]; then
      echo "::warning::新文件 $f 未进 line-table，增长将脱离行数守卫（建议：在 scripts/arch-guard.sh 加「总行数 ≤」闸门并归档 line-table）" >&2
      warn_count=$((warn_count + 1))
    fi
  done
fi

# ---- ② 既有 .kt 净增长 >50 行：余量是否逼近上限（辅助提示） ----
mapfile -t numstat < <(git -C "$ROOT" diff --numstat "$BASE" -- '*.kt' 2>/dev/null)
if ((${#numstat[@]})); then
  declare -A growth_map=()
  for row in "${numstat[@]}"; do
    [[ -z "$row" ]] && continue
    added="$(printf '%s' "$row" | cut -f1)"
    removed="$(printf '%s' "$row" | cut -f2)"
    p="$(printf '%s' "$row" | cut -f3)"
    [[ "$p" != *.kt ]] && continue
    [[ "$added" =~ ^[0-9]+$ && "$removed" =~ ^[0-9]+$ ]] || continue
    # 纯新增（removed=0 且未登记）已在 ① 处理，这里跳过避免重复提示
    if [[ "${GUARDED_THR[$p]:-__none__}" == "__none__" && "$removed" == "0" ]]; then
      continue
    fi
    is_excluded "$p" && continue
    net=$(( added - removed ))
    growth_map["$p"]="$net"
  done

  if ((${#growth_map[@]})); then
    for p in "${!growth_map[@]}"; do
      net="${growth_map[$p]}"
      [[ "$net" -le 50 ]] && continue
      thr="${GUARDED_THR[$p]:-}"
      [[ -z "$thr" ]] && continue   # 未登记文件：② 不负责（属 ① 之外，保持静默）
      cur="$(wc -l < "$ROOT/$p" 2>/dev/null | tr -d '[:space:]')"
      [[ -z "$cur" ]] && cur=0
      margin=$(( thr - cur ))
      if [[ "$margin" -le "$WARN_MARGIN" ]]; then
        echo "::warning::既有文件 $p 增长 $net 行，余量不足（阈值 $thr / 当前 $cur / 余 $margin）；下一波动它前先拆分，勿改阈值" >&2
        note_count=$((note_count + 1))
      fi
    done
  fi
fi

# ---- 汇总（咨询级，exit 0） ----
if [[ "$warn_count" -eq 0 && "$note_count" -eq 0 ]]; then
  echo "[check-new-file-guard] OK：无新增 .kt 脱闸、无大增长逼近闸门（登记列表共 $GUARDED_COUNT 个受闸门文件）" >&2
else
  echo "[check-new-file-guard] 发现 $warn_count 处新增脱闸警告 + $note_count 处大增长余量提示（咨询级，不阻断；登记列表共 $GUARDED_COUNT 个受闸门文件）" >&2
fi
exit 0
