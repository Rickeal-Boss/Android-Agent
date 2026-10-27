#!/usr/bin/env bash
# =============================================================================
# check-new-file-guard.sh — 新文件 / 大增长「行数守卫脱闸」机械检查（W84-14 / W85）
#
# 背景（外审 A-2）：「新文件 / 增长 >50 行的 .kt 必须进 line-table」这条纪律长期
#   靠人记 —— #35/#37/#48/#51/#56 五次「拆分新文件裸奔」全是人肉漏检后才补。本脚本
#   把这条纪律机械化，作为「软守卫 / 咨询级」工具。
#
# 扫描面（scope）：
#   · 默认模式（diff 模式）：新增 .kt 文件（git diff --diff-filter=A），逐个核对是否落在
#     「行数守卫登记列表」；未登记 ⇒ 打印警告「新文件 X 未进 line-table，增长将脱离行数守卫」。
#   · 默认模式：既有 .kt 文件（git diff --numstat）净增长 >50 行，若其已逼近该行数闸门上限
#     （读 arch-guard 的「总行数 ≤」阈值，余量 ≤ WARN_MARGIN）⇒ 提示「X 增长 N 行，
#     余量不足，下一波动它前先拆分」（辅助信息，不判红）。
#   · `--all` 存量模式（W86-05）：**不依赖 git diff**，直接 `find` 全仓 *.kt 逐个核对是否
#     已被任何行数闸门覆盖（文件级「总行数 ≤」登记 + 包级闸门的目录前缀，如 #45/#58），
#     未覆盖 ⇒ 打警告。为什么必须补这个模式：diff 模式只看**未提交的新文件**，一旦漏网
#     文件被提交，`git diff --diff-filter=A` 就再也扫不到它 —— W85 的两处拆分脱闸
#     （FileArgNormalizer.kt / DiagnosticsLogRow.kt）就是这样在「已提交」状态下沉默的。
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
#     ⚠️ **advisory 是终态，不翻硬门禁**（W87-19 方案 B，登记于 `docs/15-backlog.md`
#     「治理注记 1」）—— 此前的措辞是「等机制稳定（接入 CI、误报率归零）后再考虑升级为
#     硬门禁」，那是一句**永远不会被执行**的毕业判据（没人定义什么叫「稳定」），与
#     「永远 exit 1 的检查」是同一个病：没人再读它。结论与理由：本仓受闸门文件只有 **19**
#     个，其余 **123** 个（≥100 行未登记）是「从未打算加闸门」的常规件 ⇒ 判红会制造
#     100+ 条假红（红灯含义变成「你没给这个文件加闸门」，没人会照做）。翻案判据 = **闸门
#     覆盖率**变化（如受闸门文件扩到覆盖全部 ≥300 行文件），**不是波数**。
#     当前 CI 接的是 advisory 步骤（W86-05，`.github/workflows/build.yml`）：报告先行、
#     门禁后置 —— 新机制早期误报会阻断正常开发，故先只出报告（沿 W84-14 的取舍）。
#   · 只读 git diff / find 与 arch-guard 静态解析，不触碰任何 src/ 业务文件、不跑 gradle、
#     不修改仓库任何内容。
#
# 用法：
#   scripts/check-new-file-guard.sh                 # 以 HEAD 为基线，查工作树/暂存区新增
#   scripts/check-new-file-guard.sh --base <ref>    # 以指定基线上比对（如 merge-base）
#   scripts/check-new-file-guard.sh --all           # 存量模式：全仓 *.kt 逐个核对登记面
#   scripts/check-new-file-guard.sh --warn-margin N # 余量告警阈值（默认 100 行）
#   scripts/check-new-file-guard.sh --help
#
# 退出码：恒 0（咨询级；即使有警告也不 exit 1。**这是终态**，见文件头「advisory 是终态」与
#         `docs/15-backlog.md` 治理注记 1 —— 翻硬门禁的判据是闸门覆盖率，不是波数）。
# =============================================================================
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel 2>/dev/null || echo "$SCRIPT_DIR/..")"
GUARD="$SCRIPT_DIR/arch-guard.sh"

BASE="HEAD"
WARN_MARGIN=100
ALL=0
# `--all` 逐条告警的行数下限（见 ⓪ 段的「206 条 = 没有告警」论证）。
ALL_MIN_LINES=100
# `--all` 逐条告警的条数上限（见 ⓪ 段「123 条仍需封顶」论证）。
ALL_TOP_N=10
while [[ $# -gt 0 ]]; do
  case "$1" in
    --base)          BASE="${2:-HEAD}"; shift 2 ;;
    --warn-margin)   WARN_MARGIN="${2:-100}"; shift 2 ;;
    --all-min-lines) ALL_MIN_LINES="${2:-100}"; shift 2 ;;
    --all)           ALL=1; shift ;;
    -h|--help)       sed -n '2,56p' "$0"; exit 0 ;;
    *)               echo "::warning::未知参数：$1（忽略；可用 --base / --all / --all-min-lines / --warn-margin / --help）" >&2; shift ;;
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

# ---- 解析**包级**行数闸门的目录前缀（#45 background/ / #58 oem/） ----
# 为什么需要这一面：包级闸门不登记具体文件名（它 `find` 整个目录递归），若 --all 只认
# 文件级登记，包内每个 .kt 都会被误报「未登记」—— 误报会把人训练成忽略警告。
# 提取口径：`bash -c 'D=<仓库相对路径>` 的 `D=` 值（与 #45/#58 的实现形态同源）。
declare -a GUARDED_DIRS=()
if [[ -f "$GUARD" ]]; then
  while IFS= read -r d; do
    # 只收**实际存在的目录**：`bash -c 'D=…` 也被文档类守卫使用（值是 .md 路径），
    # 不加这条会把文件路径当成「包级闸门目录」混进来（无害但会污染计数与可读性）。
    [[ -n "$d" && -d "$ROOT/$d" ]] && GUARDED_DIRS+=("$d")
  done < <(grep -oE "bash -c 'D=[^[:space:]]+" "$GUARD" | sed -E "s/^bash -c 'D=//" | sort -u)
fi

is_excluded() {
  case "$1" in
    *"/build/"*|*"/build"*|*"/.gradle/"*) return 0 ;;
    *"/test/"*|*"/androidTest/"*|*"/testDebug/"*|*"/testRelease/"*) return 0 ;;
    *"Test.kt"|*"Tests.kt"|*"Spec.kt"|*"Mock.kt") return 0 ;;
  esac
  return 1
}

# 文件是否被任一闸门覆盖（文件级登记 ∪ 包级目录前缀）。
is_guarded() {
  local f="$1" d
  [[ -n "${GUARDED_THR[$f]:-}" ]] && return 0
  for d in "${GUARDED_DIRS[@]}"; do
    case "$f" in "$d"/*) return 0 ;; esac
  done
  return 1
}

warn_count=0
note_count=0

if [[ "$ALL" -eq 1 ]]; then
  # ---- ⓪ 存量模式（--all）：全仓 *.kt 逐个核对是否被闸门覆盖 ----
  # 与 diff 模式互补：diff 模式看不见**已提交**的漏网文件（`--diff-filter=A` 只匹配
  # 尚未提交的新增），存量扫描是这条盲区的唯一机械兜底。
  #
  # ⚠️ 为什么逐条告警要带**行数下限**（W86-05 首次实跑的教训）：
  #   首版对全部未登记文件逐条打 `::warning::`，真实树实测 **206 条** —— 其中绝大多数是
  #   十几行的 sealed class / 枚举 / 小工具件。这种量级的告警等于**没有告警**（没人会读，
  #   CI summary 也被刷屏），正是本仓反复批判的「永远报警 = 永远不听」。
  #   判据改为：只有 **≥ ALL_MIN_LINES 行**的未登记文件才逐条告警（体量到了才可能「增长
  #   脱离守卫」有意义）；小件只汇总一行计数。下限取 100 = 本仓行数闸门的最小阈值
  #   （FrameStatsSession #56 = 100），即「小到连最紧的闸门都不会先撞上」的文件不逐条报。
  mapfile -t all_files < <(cd "$ROOT" && find . -name '*.kt' -type f | sed 's|^\./||' | sort)
  if ((${#all_files[@]} == 0)); then
    echo "::warning::[--all] 未扫到任何 .kt（find 面失效？禁静默通过）" >&2
  fi
  small_unguarded=0
  large_unguarded=()
  for f in "${all_files[@]}"; do
    [[ -z "$f" ]] && continue
    is_excluded "$f" && continue
    is_guarded "$f" && continue
    n="$(wc -l < "$ROOT/$f" 2>/dev/null | tr -d '[:space:]')"
    case "${n:-}" in ''|*[!0-9]*) n=0 ;; esac
    if [[ "$n" -lt "$ALL_MIN_LINES" ]]; then
      small_unguarded=$((small_unguarded + 1))
      continue
    fi
    large_unguarded+=("$n $f")
  done
  # 再封顶：按行数降序只逐条报前 ALL_TOP_N 个（W86-05 二次实跑：仅加行数下限后仍有
  # **123 条** —— 本仓受闸门文件只有 19 个，其余全是「从未打算加闸门」的常规文件。
  # 逐条列 123 条依然等于没有告警，故再封顶到 top-N，其余只报计数与最大值。
  # 判读方式：**top-N 里出现「拆分产物」才是真信号**（它本该继承母文件的闸门）。
  if ((${#large_unguarded[@]} > 0)); then
    mapfile -t sorted_large < <(printf '%s\n' "${large_unguarded[@]}" | sort -rn -k1,1)
    shown=0
    for entry in "${sorted_large[@]}"; do
      n="${entry%% *}"; f="${entry#* }"
      if [[ "$shown" -lt "$ALL_TOP_N" ]]; then
        echo "::warning::存量文件 $f（${n} 行）未进 line-table，增长将脱离行数守卫（--all top-${ALL_TOP_N}；若它是**拆分产物**，必须继承母文件的闸门）" >&2
        warn_count=$((warn_count + 1))
        shown=$((shown + 1))
      fi
    done
    if ((${#large_unguarded[@]} > ALL_TOP_N)); then
      echo "::notice::[--all] 另有 $(( ${#large_unguarded[@]} - ALL_TOP_N )) 个未登记文件 ≥ ${ALL_MIN_LINES} 行未逐条列出（共 ${#large_unguarded[@]} 个；最大 ${sorted_large[0]%% *} 行）" >&2
    fi
  fi
  if [[ "$small_unguarded" -gt 0 ]]; then
    echo "::notice::[--all] 另有 $small_unguarded 个未登记文件 < ${ALL_MIN_LINES} 行（小件不逐条告警；体量到 ${ALL_MIN_LINES} 行时才可能脱离守卫有意义）" >&2
  fi
else
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
fi   # ← ALL=1 存量模式 / diff 模式（① ②）的分支收口

# ---- 汇总（咨询级，exit 0） ----
if [[ "$warn_count" -eq 0 && "$note_count" -eq 0 ]]; then
  echo "[check-new-file-guard] OK：无新增 .kt 脱闸、无大增长逼近闸门（登记列表共 $GUARDED_COUNT 个受闸门文件 / 包级闸门目录 ${#GUARDED_DIRS[@]} 个）" >&2
else
  echo "[check-new-file-guard] 发现 $warn_count 处新增脱闸警告 + $note_count 处大增长余量提示（咨询级，不阻断；登记列表共 $GUARDED_COUNT 个受闸门文件 / 包级闸门目录 ${#GUARDED_DIRS[@]} 个）" >&2
fi
exit 0
