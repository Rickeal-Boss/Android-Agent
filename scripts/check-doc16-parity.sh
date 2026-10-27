#!/usr/bin/env bash
# =============================================================================
# check-doc16-parity.sh — docs/16「双向一致性规则」的**最小机械版**（W89 D-3）
#
# 为什么需要：`docs/16-acceptance-criteria.md` §三 写了「文档有而脚本不抛 = FAIL；
#   脚本抛而文档无 = FAIL」，但全仓 `scripts/*.sh` 对 docs/16 **零引用** ⇒ 这条规则
#   到 W88 为止是纯文档声明（`grep -rn 'docs/16' scripts/` 此前零命中）。
#
# 判据（两条方向 + 一条只许缩）：
#   ① 文档 → 脚本：`docs/16` 里每个 `###/#### <AREA>.<NNN> - ` 条目，其 NOTE 段落里
#      出现的每个 `#N` 必须满足「arch-guard.sh 有 `# N)` 编号注释」**且**「该编号块内
#      有 `^check "` 行」—— 只有注释没有 check = 装饰性规则（会给人已被保护的错觉）。
#      未写 `#N` 的条目必须显式写 `[guard] —`（声明未机械化），并计入冻结白名单
#      DOC_UNMECH=4（DL.001~004）。白名单**只许缩不许涨**：涨 = 新规则只写文档不落判据。
#   ② 脚本 → 文档：`arch-guard.sh` 的 `^check "` 条数 − 文档已引用的守卫号数 = 待迁移数，
#      冻结 UNID_BASELINE=47，同样只许缩不许涨（防「顺手加守卫、不登记 id」）。
#   ③ fail-closed：任一侧文件缺失 / 解析出 0 条 ⇒ 判红，绝不静默放行。
#
# ⚠️ 判据钉**行首形态**，不裸子串匹配（docs/16 §三 硬要求 1）：
#   条目行 `^#{3,4} [A-Z]+\.[0-9]{3} - `；守卫号只从 `^>` 开头的 NOTE 行里取 ——
#   正文里提到 `ARCH.001` 不会被算成「有这条规则」，注释里提到 `#25` 也不会被算成
#   「有这条守卫」（本仓已有同类事故：line-table.sh 曾把注释里的连写当成守卫）。
# ⚠️ 成本纪律：全程 2 次 awk + 1 次 grep -c（单进程、无逐行 fork），实测 < 0.2s。
#    「4 分 52 秒的工具没人会跑 = 守卫自动作废」（W86 事故记录）。
#
# 退出码：0 = 两向均无新增缺口；1 = 判红（附逐条事实行）。
# =============================================================================
set -uo pipefail

DOC="docs/16-acceptance-criteria.md"
GUARD="scripts/arch-guard.sh"
DOC_UNMECH=4       # 已显式声明「[guard] —」的条目数（DL.001~004）；只许缩不许涨
UNID_BASELINE=47   # arch-guard check 数 − 文档已引用守卫号数（61 − 14）；只许缩不许涨
fail=0

for f in "$DOC" "$GUARD"; do
  [ -f "$f" ] || { echo "::error::[docs16-parity] 缺少 $f（判据面缺失 ⇒ 禁 fail-open）"; exit 1; }
done

# ① 定义面：每个 `# N)` 块内**是否真的有 check 行**（只有注释 = 装饰性规则）
defined="$(awk '
  /^# [0-9]+\)/ { if (id != "" && has) print id; id = $2; sub(/\)$/, "", id); has = 0; next }
  /^check "/ { if (id != "") has = 1 }
  END { if (id != "" && has) print id }
' "$GUARD" | sort -un | paste -sd' ' -)"
[ -z "$defined" ] && { echo "::error::[docs16-parity] arch-guard.sh 未解析出任何「编号注释 + check」守卫（提取面失效）"; exit 1; }

# ② 文档面：条目 → 其 `[guard]` token 段落里引用的守卫号（空 = 声明未机械化）
#    ⚠️ 只取 `[guard]` 之后、到 `[count]`/`[commit]` 为止的片段：NOTE 散文里顺带提到的
#    「锚 #1」（ARCH.005 的台账说明）不是守卫引用，全段抓 `#N` 会把它算进来。
#    ⚠️ 跳过 ``` 围栏内的示例（§2.1 的格式范例里有一行 `### ARCH.001 - …`，不跳过会
#    多解析出一个「无守卫条目」⇒ 白名单计数假涨）。
entries="$(awk '
  /^```/ { fence = !fence; next }
  fence { next }
  /^#{3,4} [A-Z]+\.[0-9]{3} - / { if (id != "") print id "\t" g; id = $2; g = ""; cap = 0; next }
  /^>/ {
    s = $0
    if (!cap) { if (!match(s, /`\[guard\]`/)) next; s = substr(s, RSTART + RLENGTH); cap = 1 }
    while (match(s, /#[0-9]+/)) { g = g " " substr(s, RSTART + 1, RLENGTH - 1); s = substr(s, RSTART + RLENGTH) }
    if ($0 ~ /\[(count|commit)\]/) cap = 0
  }
  END { if (id != "") print id "\t" g }
' "$DOC")"
[ -z "$entries" ] && { echo "::error::[docs16-parity] docs/16 未解析出任何 AREA.NNN 条目（提取面失效）"; exit 1; }

unmech=0
refs=""
while IFS=$'\t' read -r id guards; do
  [ -z "$id" ] && continue
  if [ -z "${guards// /}" ]; then
    unmech=$((unmech + 1))
    continue
  fi
  for g in $guards; do
    case " $defined " in
      *" $g "*) ;;
      *) echo "::error::[docs16-parity] $id 引用了 #$g，但 arch-guard.sh 无「# $g) + check」守卫（文档有而脚本不抛）"; fail=1 ;;
    esac
    case " $refs " in *" $g "*) ;; *) refs="$refs $g" ;; esac
  done
done <<EOF
$entries
EOF

checks="$(grep -c '^check "' "$GUARD")"
nref="$(printf '%s\n' $refs | grep -c . || true)"
unid=$((checks - nref))

if [ "$unmech" -gt "$DOC_UNMECH" ]; then
  echo "::error::[docs16-parity] 声明未机械化的条目 $unmech > 冻结白名单 $DOC_UNMECH（新规则只写文档不落判据）"; fail=1
fi
if [ "$unid" -gt "$UNID_BASELINE" ]; then
  echo "::error::[docs16-parity] 待迁移 check 数 $unid > 冻结白名单 $UNID_BASELINE（新增守卫未登记 AREA.NNN）"; fail=1
fi

if [ "$fail" -ne 0 ]; then
  echo "[docs16-parity] 失败：文档 ↔ 守卫 存在新增缺口（详见上方 ::error:: 行）"
  exit 1
fi
echo "[docs16-parity] OK：条目 $(printf '%s\n' "$entries" | grep -c .) 条（已机械化 $(( $(printf '%s\n' "$entries" | grep -c .) - unmech )) / 声明未机械化 $unmech = 白名单 $DOC_UNMECH）；引用守卫 $nref 个；arch-guard check $checks 条 ⇒ 待迁移 $unid = 白名单 $UNID_BASELINE（两向 0 新增缺口）"
exit 0
