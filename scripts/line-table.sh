#!/usr/bin/env bash
# =============================================================================
# line-table.sh — 行数守卫表生成器（W66 E1b 工具化）
#
# 目的：把 arch-guard.sh 里的「总行数 ≤」守卫（即 #25 家族：文件级行数上限）
#       生成 markdown 行（文件 / 当前行数 / 阈值 / 余量），供 README / handoff
#       直接粘贴，**不再人手誊写行数表**。
#
# 单源原则（本脚本存在的唯一理由）：
#   清单**动态派生自 scripts/arch-guard.sh**，不维护第二张手写表 —— 守卫增删 /
#   阈值改动，本表自动跟随。历史坑「计数链断点（E1）」= 聚合数字手写必漂，
#   故本脚本与守卫共享同一份「路径 + 阈值」，杜绝两表漂移。
#
# 为什么不做成 selftest case（方案 §Q3 裁定）：
#   selftest 无 warn 档（全 pass/fail，见 arch-guard-selftest.sh 的 guard_selected），
#   加「非阻断」= 新机制；且 handoff 每波换名 ⇒ case 要 glob「最新交接」⇒ 文档格式
#   脆弱。故落为独立生成脚本。
#
# 用法：
#   scripts/line-table.sh            # 输出 markdown 表（stdout）
#   scripts/line-table.sh --check    # 自证：派生行数 == 守卫条数、每文件存在；
#                                     # 通过时把 OK 打到 stderr（不污染 markdown）
#
# 退出码：0 = 正常 / --check 通过；非 0 = 派生失败 / 格式漂移 / --check 不符。
# =============================================================================
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
GUARD="$SCRIPT_DIR/arch-guard.sh"

if [ ! -f "$GUARD" ]; then
  echo "::error::找不到守卫脚本：$GUARD（行数表派生源缺失，禁 fail-open）" >&2
  exit 1
fi

CHECK=0
case "${1:-}" in
  --check)   CHECK=1 ;;
  -h|--help) sed -n '2,25p' "$0"; exit 0 ;;
  "")        : ;;
  *)         echo "::error::未知参数：$1（可用：--check / --help）" >&2; exit 2 ;;
esac

# ---- 单源派生：抓 arch-guard.sh 的「总行数 ≤」守卫描述行 ----
# 每个守卫形态（两行）：
#   check "<文件> 总行数 ≤ <阈值>（…）" \
#     bash -c 'f=<仓库相对路径>
# 故：行号取自 `check "..."` 行，仓库相对路径取自其**下一行**的 `f=`。
mapfile -t guard_lines < <(grep -nE '总行数 ≤' "$GUARD")

if [ "${#guard_lines[@]}" -eq 0 ]; then
  echo "::error::未从 $GUARD 派生出任何「总行数 ≤」守卫（提取正则漂移？禁 fail-open）" >&2
  exit 1
fi

printf '| 文件 | 当前行数 | 阈值 | 余量 |\n'
printf '|---|---:|---:|---:|\n'

n_rows=0
bad=0
for entry in "${guard_lines[@]}"; do
  lineno="${entry%%:*}"
  text="${entry#*:}"

  # 阈值：`总行数 ≤ <N>`
  thr="$(printf '%s' "$text" | grep -oE '总行数 ≤ [0-9]+' | grep -oE '[0-9]+$')"
  # 仓库相对路径：下一行的 `f=<path>`（`^[^f]*f=` 非贪婪到第一个 f，避开路径内 'f'）
  path="$(sed -n "$((lineno + 1))p" "$GUARD" | sed -nE 's/^[^f]*f=//p')"

  if [ -z "$thr" ] || [ -z "$path" ]; then
    echo "::error::守卫解析失败（arch-guard.sh:$lineno：thr='$thr' path='$path'）—— 格式漂移" >&2
    bad=1
    continue
  fi

  if [ ! -f "$ROOT/$path" ]; then
    echo "::error::目标文件不存在：$path（arch-guard.sh:$lineno 守卫面失效）" >&2
    bad=1
    continue
  fi

  # 当前行数 = wc -l 实测（与守卫同源同口径：守卫亦 `wc -l < "$f"`，cwd=仓库根）
  cur="$(wc -l < "$ROOT/$path" | tr -d '[:space:]')"
  margin=$(( thr - cur ))

  printf '| `%s` | %s | %s | %s |\n' "$path" "$cur" "$thr" "$margin"
  n_rows=$((n_rows + 1))
done

# ---- --check 自证：行数齐全 + 无解析失败 ----
if [ "$CHECK" -eq 1 ]; then
  if [ "$bad" -ne 0 ]; then
    echo "::error::line-table --check 失败：存在解析失败 / 文件缺失的守卫（见上）" >&2
    exit 1
  fi
  if [ "$n_rows" -ne "${#guard_lines[@]}" ]; then
    echo "::error::line-table --check 失败：派生行数($n_rows) != 守卫条数(${#guard_lines[@]})" >&2
    exit 1
  fi
  # OK 打到 stderr，保持 stdout 为纯净 markdown
  echo "[line-table] OK：$n_rows 行，全部由 wc -l 实测派生自 arch-guard.sh（自证通过）" >&2
fi

exit "$bad"
