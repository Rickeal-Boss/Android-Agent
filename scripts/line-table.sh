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
#   scripts/line-table.sh --check    # 自证：①派生行数 == 守卫条数；②每文件存在；
#                                     # ③第二独立提取面（编号注释头）与 check 行**等数**（防措辞漂移）；
#                                     # ④派生行数 ≥ 冻结下限 MIN_ROWS（防整族删除 / 缩面）；
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

# 行数守卫家族的**冻结下限**（W66 深审 D4 修补）：arch-guard.sh 里「总行数 ≤」家族当前 **13 员**
# （#18/#20/#25/#31/#32/#35/#36/#37/#38/#48/#49/#50/#51；W82 新增 #49 FrameStatsCollector 250 /
# #50 DiagnosticsScreen 1150 / #51 AgentPromptComposer 400，派生行 10 → 13，本值同步 9 → 13）。
# 为什么需要一个写死的下限：--check 旧判据只比
# `n_rows == ${#guard_lines[@]}`，而两者**同源于同一 grep** ⇒ 整条守卫（注释头 + check 实现）
# 被删除时两侧一起减一、仍恒等 ⇒ **永远绿**（fail-open）。下限把「家族被整族删 / 缩面」变成
# 显式判红。**新增行数守卫时同步上调本值；删守卫须同步下调并说明理由** —— 这条硬编码是「防缩面」
# 的代价，刻意保留（与 arch-guard.sh「守卫面失效必须判红」同款契约）。
# 维护口径（W82 订正，原「派生行数 − 1」经审查证伪）：MIN_ROWS **恒取当前派生行数本身**。
# 原口径（12 = 13 − 1）下删一条守卫 ⇒ 12，不满足 `< 12` ⇒ **不判红**，下限对其唯一防护对象
# （单条删减）失效；W82 审查席实测后订正为等值，删任何一条即红。新增守卫不判红但须同步上调。
MIN_ROWS=13

# ---- 单源派生：抓 arch-guard.sh 的「总行数 ≤」守卫描述行 ----
# 每个守卫形态（两行）：
#   check "<文件> 总行数 ≤ <阈值>（…）" \
#     bash -c 'f=<仓库相对路径>
# 故：行号取自 `check "..."` 行，仓库相对路径取自其**下一行**的 `f=`。
mapfile -t guard_lines < <(grep -nE '总行数 ≤' "$GUARD")

# 第二独立提取面（W66 深审 D4）：抓守卫**编号注释头** `# <N>) <File>.kt 总行数上限`。
# 与上面的 `check "…总行数 ≤…"` 行**互相独立**（一个抓 check 描述行、一个抓注释头）：
# 两面必须等数 —— 只改 / 删其中一面（措辞漂移、漏改注释）⇒ --check 判红。
mapfile -t header_lines < <(grep -nE '^# [0-9]+\) .*\.kt 总行数上限' "$GUARD")

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

# ---- 方法级条目（W67 B0，复审21 P3-②）：#12 executeBodyUnchecked ≤ 550 行 ----
# 为什么单列：文件级九员之外，真压力其实是**方法级** #12（`AgentRunner` 文件级余 127 尚宽，
# 但 `executeBodyUnchecked` 方法级余量最紧）。此前该值靠人手誊写 ⇒ W65/W66 两波照抄「余 52」
# （实测 501/550 余 49，虚低 3，复审21 P3-② 独立复算 :546→:1047）。本条目令方法级引信余量
# **机械生成**，与 #12 守卫共享同一份「目标文件 + 方法名 + 阈值 + 长度口径」。
# 单源：阈值 / 文件 / 方法名全部派生自 arch-guard.sh 的 #12 check 描述行；长度口径与守卫
# #12 逐字同款（起始 = `fun FlowCollector<AgentEvent>.executeBodyUnchecked` 行；终止 = 其后
# 首个「行首 4 空格 + 非注释（首字符字母或 @）+ 含 ` fun `」的方法边界行）。
m_line="$(grep -nE 'executeBodyUnchecked ≤ [0-9]+ 行' "$GUARD" | head -1)"
m_bad=0
if [ -n "$m_line" ]; then
  m_lineno="${m_line%%:*}"
  m_thr="$(printf '%s' "$m_line" | grep -oE '≤ [0-9]+ 行' | grep -oE '[0-9]+')"
  m_path="$(sed -n "$((m_lineno + 1))p" "$GUARD" | sed -nE 's/^[^f]*f=//p')"
  m_s="$(grep -n "fun FlowCollector<AgentEvent>.executeBodyUnchecked" "$ROOT/$m_path" 2>/dev/null | head -1 | cut -d: -f1)"
  m_e="$(awk -v s="$m_s" 'NR>s && /^    [a-zA-Z@]/ && /(^|[ ])fun /{print NR; exit}' "$ROOT/$m_path" 2>/dev/null)"
  if [ -n "$m_thr" ] && [ -n "$m_path" ] && [ -n "$m_s" ] && [ -n "$m_e" ]; then
    m_cur=$(( m_e - m_s ))
    m_margin=$(( m_thr - m_cur ))
    printf '| `%s`（**方法级** `executeBodyUnchecked`） | %s | %s | %s |\n' "$m_path" "$m_cur" "$m_thr" "$m_margin"
  else
    echo "::error::方法级行数条目解析失败（#12：thr='$m_thr' path='$m_path' s='$m_s' e='$m_e'）—— 守卫面失效" >&2
    m_bad=1
  fi
else
  echo "::error::未从 $GUARD 派生出方法级 #12 守卫（executeBodyUnchecked ≤ N 行）—— 提取正则漂移 / 守卫被删，禁 fail-open" >&2
  m_bad=1
fi

# ---- --check 自证：行数齐全 + 无解析失败 ----
if [ "$CHECK" -eq 1 ]; then
  if [ "$bad" -ne 0 ] || [ "$m_bad" -ne 0 ]; then
    echo "::error::line-table --check 失败：存在解析失败 / 文件缺失的守卫（见上）" >&2
    exit 1
  fi
  if [ "$n_rows" -ne "${#guard_lines[@]}" ]; then
    echo "::error::line-table --check 失败：派生行数($n_rows) != 守卫条数(${#guard_lines[@]})" >&2
    exit 1
  fi
  # D4 修补①：第二独立提取面交叉核对 —— check 描述行派生的行数必须等于编号注释头派生的条数。
  # 旧判据只比 n_rows 与同源的 guard_lines 长度 ⇒ 改措辞 / 删单面都恒绿；此处把两面不一致变红。
  if [ "$n_rows" -ne "${#header_lines[@]}" ]; then
    echo "::error::line-table --check 失败：check 行派生数($n_rows) != 注释头派生数(${#header_lines[@]}) —— 两面不一致（措辞漂移 / 单面漏改）" >&2
    exit 1
  fi
  # D4 修补②：冻结下限 —— 整条守卫（注释头 + check 实现）被删除时，上面两面会**一起**减一、
  # 仍恒等 ⇒ 需要下限兜住「整族删除 / 缩面」（禁 fail-open）。
  if [ "$n_rows" -lt "$MIN_ROWS" ]; then
    echo "::error::line-table --check 失败：派生行数($n_rows) < 冻结下限($MIN_ROWS) —— 行数守卫家族被整族删除 / 缩面" >&2
    exit 1
  fi
  # OK 打到 stderr，保持 stdout 为纯净 markdown
  echo "[line-table] OK：$n_rows 行（文件级）+ 1 行（方法级 #12），双提取面（check 行 == 注释头）一致且 ≥ 冻结下限 $MIN_ROWS，全部由 wc -l 实测派生自 arch-guard.sh（自证通过）" >&2
fi

if [ "$m_bad" -ne 0 ]; then exit 1; fi
exit "$bad"
