#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# arch-guard-selftest.sh —— arch-guard.sh 的自测网（Wave 31）
#
# 为什么需要它：arch-guard.sh 是纯 bash、无任何测试，而它自己的设计哲学写着
# 「用 grep 在 CI 里强制依赖方向，而不是靠人自觉」。可它历史上已经出过两次
# 「僵尸规则」事故：
#   ① 第 7 条曾用 `grep -q`（无输出）失败退出 1，被 check 判成 OK ——
#      把 INTERNET 权限整行删掉，守卫仍全绿（Wave 30 才发现并修正）。
#   ② 目录被改名后前置断言缺失，守卫扫不到目标 → 空守卫永远绿。
# 一条「永远绿」或「永远红」的守卫，比没有守卫更糟 —— 它给人已被保护的错觉。
# 本自测网用真实 fixture 钉住每条守卫的**触发面**：
#   · 造一棵临时的「干净」树 → 断言守卫退出 0（防「守卫永远红」）；
#   · 在干净树上注入单点违规 → 断言守卫退出 1 且打出期望的 ::error::[守卫名] 行
#     （防「守卫永远绿」/「僵尸规则」）。
#
# 设计取舍：
#   · 每条 case 在独立临时目录里跑（cwd = 临时树根），互不污染；
#   · fixture 必须是**真实代码行**，不能写成注释 —— arch-guard 的
#     EXCLUDE_COMMENT 会放行注释行，写在注释里测不出东西；
#   · 只断言「期望的守卫被触发」，不禁止其它守卫同时触发（违规树里多条守卫
#     同时命中是正常的，例如删了 INTERNET 不会影响网络栈守卫）。
#
# 作为 CI 步骤运行：全过 exit 0；任一 case 失败 exit 1 并打印是哪条。
# ---------------------------------------------------------------------------
set -uo pipefail

GUARD="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/arch-guard.sh"
if [ ! -f "$GUARD" ]; then
  echo "::error::找不到 arch-guard.sh（期望与自测脚本同目录）：$GUARD"
  exit 1
fi

TMP="$(mktemp -d)"
# 清理失败不影响判定（某些环境里 rm 被包装/受限）：吞掉错误，绝不改退出码。
trap 'rm -rf "$TMP" 2>/dev/null || true' EXIT

PASS=0
FAIL=0

# 生成一个含 executeBodyUnchecked 的 AgentRunner.kt；$2 = 方法体行数。
# arch-guard 第 12 条用「下一个 4 空格缩进的 fun 行号 − 本方法签名行号」估算体积，
# 因此方法体必须夹在签名行与 afterMethod() 之间，且 afterMethod 必须顶格 4 空格。
write_runner() {
  local root="$1" body="$2"
  local f="$root/core-agent/src/main/java/com/rickeal/agent/core/agent/AgentRunner.kt"
  mkdir -p "$(dirname "$f")"
  {
    echo 'package com.rickeal.agent.core.agent'
    echo ''
    echo 'class AgentRunner {'
    echo '    private suspend fun FlowCollector<AgentEvent>.executeBodyUnchecked(request: AgentRequest) {'
    local i=1
    while [ "$i" -le "$body" ]; do echo "        val v$i = $i"; i=$((i + 1)); done
    echo '    }'
    echo ''
    echo '    private fun afterMethod() = Unit'
    echo '}'
  } > "$f"
}

# 铺一棵「干净」的最小骨架：满足 arch-guard 的前置存在性断言与两条「必须存在」
# 型守卫（Manifest 的 INTERNET、AgentRunner 的方法体积），其余保持为空。
# 干净树上守卫应退出 0 —— 这是「守卫永远红」的回归防线。
scaffold() {
  local root="$1"
  mkdir -p "$root"/core-model "$root"/core-design "$root"/core-engine \
           "$root"/core-agent "$root"/core-data "$root"/app/src/main
  cat > "$root/app/src/main/AndroidManifest.xml" <<'XML'
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.INTERNET" />
</manifest>
XML
  write_runner "$root" 5
  # 第 13 条（Wave 32 起）在 AgentEvents.kt 缺失时会判「守卫面失效」⇒ 骨架必须提供它。
  # 这里给一个**没有可空字段**的 AgentRequest：字段数 0 == 标记数 0，干净树保持绿。
  cat > "$root/core-agent/src/main/java/com/rickeal/agent/core/agent/AgentEvents.kt" <<'KT'
package com.rickeal.agent.core.agent

data class AgentRequest(
    val userInput: ChatMessage,
)
KT
  # 第 14 条（Wave 32 起）要求 app/lint-baseline.xml 存在且条目数 <= 冻结值 83：
  # 骨架给 2 条条目（远低于冻结值），干净树保持绿。
  mkdir -p "$root/app"
  cat > "$root/app/lint-baseline.xml" <<'XML'
<?xml version="1.0" encoding="UTF-8"?>
<issues format="6" by="lint 9.3.2" type="baseline" client="gradle" dependencies="true">
    <issue
        id="AutoboxingStateCreation"
        message="Entity state should be created with primitive values">
        <location
            file="src/main/java/com/rickeal/agent/LiquidAgentApplication.kt"
            line="10"/>
    </issue>
    <issue
        id="UseKtx"
        message="Use Kotlin extensions instead">
        <location
            file="src/main/java/com/rickeal/agent/ui/LiquidAgentApp.kt"
            line="20"/>
    </issue>
</issues>
XML
}

run_guard() { ( cd "$1" && bash "$GUARD" 2>&1 ); }

# 违规 case 断言：$1=case 名，$2=实际退出码，$3=守卫 stdout，$4=期望的 ::error::[守卫名]
assert_red() {
  local name="$1" rc="$2" out="$3" want="$4"
  if [ "$rc" -ne 1 ]; then
    echo "FAIL [$name] 期望守卫退出 1（判红），实际退出 $rc"
    printf '%s\n' "$out" | sed 's/^/    | /'
    FAIL=$((FAIL + 1))
    return
  fi
  if ! printf '%s\n' "$out" | grep -qF "::error::[$want]"; then
    echo "FAIL [$name] 守卫判红但未打出期望的 ::error::[$want] 行（守卫名漂移或该条已成僵尸规则）"
    printf '%s\n' "$out" | sed 's/^/    | /'
    FAIL=$((FAIL + 1))
    return
  fi
  echo "PASS [$name] 退出 1，命中 ::error::[$want]"
  PASS=$((PASS + 1))
}

echo "=== arch-guard 自测网 ==="

# ---------------------------------------------------------------------------
# 正例：干净树必须退出 0（防「守卫永远红」/ 前置断言误伤）
# ---------------------------------------------------------------------------
d="$TMP/positive"
scaffold "$d"
out="$(run_guard "$d")"; rc=$?
if [ "$rc" -eq 0 ]; then
  echo "PASS [positive] 干净树退出 0（守卫未误红）"
  PASS=$((PASS + 1))
else
  echo "FAIL [positive] 干净树应退出 0，实际 $rc"
  printf '%s\n' "$out" | sed 's/^/    | /'
  FAIL=$((FAIL + 1))
fi

# ---------------------------------------------------------------------------
# case 1：core-model 内出现 java.net.URL ⇒ 第 5 条（全仓禁网络栈）必须报红
# ---------------------------------------------------------------------------
d="$TMP/case1-network"
scaffold "$d"
echo 'import java.net.URL' > "$d/core-model/Violation.kt"
out="$(run_guard "$d")"; rc=$?
assert_red "case1 core-model 网络栈 (第 5 条)" "$rc" "$out" \
  "全仓禁网络栈（java.net/javax.net/okhttp/okio/HttpClient/SocketChannel）"

# ---------------------------------------------------------------------------
# case 2：core-model 内出现 import android.os.Build ⇒ 第 3 条（core-model 无 android 依赖）报红
# ---------------------------------------------------------------------------
d="$TMP/case2-android"
scaffold "$d"
printf 'import android.os.Build\n' > "$d/core-model/Violation.kt"
out="$(run_guard "$d")"; rc=$?
assert_red "case2 core-model android 依赖 (第 3 条)" "$rc" "$out" \
  "core-model 无 android/androidx 依赖"

# ---------------------------------------------------------------------------
# case 3：executeBodyUnchecked 631 行 ⇒ 第 12 条（主循环方法体积）报红
#         （n = 签名行到下一个方法行 = body + 3 ⇒ body=628 得 631 行）
# ---------------------------------------------------------------------------
d="$TMP/case3-size"
scaffold "$d"
write_runner "$d" 628
out="$(run_guard "$d")"; rc=$?
assert_red "case3 主循环方法体积 631 行 (第 12 条)" "$rc" "$out" \
  "主循环方法体积未超预算（executeBodyUnchecked ≤ 550 行）"

# ---------------------------------------------------------------------------
# case 4：删掉 Manifest 的 INTERNET 行 ⇒ 第 7 条（Manifest 有 INTERNET）报红
#         （这条正是 Wave 30 修掉的僵尸规则，必须能测出）
# ---------------------------------------------------------------------------
d="$TMP/case4-manifest"
scaffold "$d"
cat > "$d/app/src/main/AndroidManifest.xml" <<'XML'
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
</manifest>
XML
out="$(run_guard "$d")"; rc=$?
assert_red "case4 Manifest 缺 INTERNET (第 7 条)" "$rc" "$out" \
  "Manifest 有 INTERNET（系统下载链路必需，Wave 12）"

# ---------------------------------------------------------------------------
# case 5：AgentRequest 含「宿主侧零赋值点」的可空字段 ⇒ 第 13 条（孤儿守卫）报红
#         其中 ghostGeneric 故意用泛型类型 `Map<String, Int>? = null` ——
#         旧抽取正则（字符类不含逗号/空格）会**静默漏检**该形态，本 case 正是钉死它。
# ---------------------------------------------------------------------------
d="$TMP/case5-orphan"
scaffold "$d"
cat > "$d/core-agent/src/main/java/com/rickeal/agent/core/agent/AgentEvents.kt" <<'KT'
package com.rickeal.agent.core.agent

data class AgentRequest(
    val ghostField: String? = null,
    val ghostGeneric: Map<String, Int>? = null,
)
KT
out="$(run_guard "$d")"; rc=$?
assert_red "case5 AgentRequest 孤儿字段 (第 13 条)" "$rc" "$out" \
  "AgentRequest 可空字段无孤儿（代码完备但未接线）"
if printf '%s\n' "$out" | grep -qF "AgentRequest.ghostGeneric"; then
  echo "PASS [case5b] 泛型字段 ghostGeneric 被捕获（sed 抽取不再漏检泛型/函数类型）"
  PASS=$((PASS + 1))
else
  echo "FAIL [case5b] 泛型字段 ghostGeneric 未被捕获（抽取仍漏检泛型类型 ⇒ P2-C1 未修好）"
  printf '%s\n' "$out" | sed 's/^/    | /'
  FAIL=$((FAIL + 1))
fi

# ---------------------------------------------------------------------------
# case 6：executeBodyUnchecked 是类内**最后一个**方法（无后置方法边界）⇒ 第 12 条
#         的 e 非空断言应判「守卫面失效」报红（否则 n 变负会静默通过 = 僵尸规则）。
# ---------------------------------------------------------------------------
d="$TMP/case6-tail"
scaffold "$d"
cat > "$d/core-agent/src/main/java/com/rickeal/agent/core/agent/AgentRunner.kt" <<'KT'
package com.rickeal.agent.core.agent

class AgentRunner {
    private suspend fun FlowCollector<AgentEvent>.executeBodyUnchecked(request: AgentRequest) {
        val x = 1
    }
}
KT
out="$(run_guard "$d")"; rc=$?
assert_red "case6 方法在类尾 (第 12 条守卫面失效)" "$rc" "$out" \
  "主循环方法体积未超预算（executeBodyUnchecked ≤ 550 行）"

# ---------------------------------------------------------------------------
# case 7：可空字段**有**宿主侧赋值点、但**没有** @wire-owner 标记 ⇒ 第 13 条必须报红。
#         钉死「① 无标记即红」是独立断言 —— 若只靠旧的「零赋值点」判定，本 case
#         会因为赋值点存在而漏判（新增字段忘了申报就静默过关）。
# ---------------------------------------------------------------------------
d="$TMP/case7-unmarked"
scaffold "$d"
mkdir -p "$d/feature-chat/src/main/java/com/rickeal/agent/feature/chat"
cat > "$d/core-agent/src/main/java/com/rickeal/agent/core/agent/AgentEvents.kt" <<'KT'
package com.rickeal.agent.core.agent

data class AgentRequest(
    val wiredButUnmarked: String? = null,
)
KT
cat > "$d/feature-chat/src/main/java/com/rickeal/agent/feature/chat/ChatViewModel.kt" <<'KT'
package com.rickeal.agent.feature.chat

val req = AgentRequest(wiredButUnmarked = "x")
KT
out="$(run_guard "$d")"; rc=$?
assert_red "case7 可空字段无 @wire-owner 标记 (第 13 条①)" "$rc" "$out" \
  "AgentRequest 可空字段无孤儿（代码完备但未接线）"
if printf '%s\n' "$out" | grep -qF "缺 @wire-owner 标记"; then
  echo "PASS [case7b] 报出「缺 @wire-owner 标记」（无标记即红，与是否有赋值点无关）"
  PASS=$((PASS + 1))
else
  echo "FAIL [case7b] 未报出「缺 @wire-owner 标记」（① 断言失效：忘申报的新字段会静默过关）"
  printf '%s\n' "$out" | sed 's/^/    | /'
  FAIL=$((FAIL + 1))
fi

# ---------------------------------------------------------------------------
# case 8：`internal` 标记 + 赋值点**只在 core-agent/** ⇒ 第 13 条必须**不红**。
#         钉死 `deadlineNanos` 的真实形态（宿主侧刻意不传、由父 run 透传给子 run）——
#         旧实现「全局 grep」对它只是碰巧正确；收紧搜索面到宿主侧会让它恒红。
# ---------------------------------------------------------------------------
d="$TMP/case8-internal"
scaffold "$d"
cat > "$d/core-agent/src/main/java/com/rickeal/agent/core/agent/AgentEvents.kt" <<'KT'
package com.rickeal.agent.core.agent

data class AgentRequest(
    // @wire-owner: internal
    val deadlineNanos: Long? = null,
)
KT
cat > "$d/core-agent/src/main/java/com/rickeal/agent/core/agent/SubRun.kt" <<'KT'
package com.rickeal.agent.core.agent

val subRequest = AgentRequest(deadlineNanos = 1L)
KT
out="$(run_guard "$d")"; rc=$?
if [ "$rc" -eq 0 ]; then
  echo "PASS [case8 internal 标记 + core-agent 内赋值点 (第 13 条②)] 退出 0（未误红）"
  PASS=$((PASS + 1))
else
  echo "FAIL [case8] internal 标记且 core-agent 内有赋值点应不红，实际退出 $rc"
  printf '%s\n' "$out" | sed 's/^/    | /'
  FAIL=$((FAIL + 1))
fi

# ---------------------------------------------------------------------------
# case 9：`pending:理由串` 但该理由串**不在** README 挂账台账节 ⇒ 第 13 条必须报红
#         （③ 交叉断言的「红」面）。case9b 再钉「绿」面，防本条变成永远红的僵尸规则。
# ---------------------------------------------------------------------------
d="$TMP/case9-pending-unledgered"
scaffold "$d"
cat > "$d/core-agent/src/main/java/com/rickeal/agent/core/agent/AgentEvents.kt" <<'KT'
package com.rickeal.agent.core.agent

data class AgentRequest(
    // @wire-owner: pending:示例未接线理由
    val pendingField: String? = null,
)
KT
cat > "$d/README.md" <<'MD'
# 项目

## 挂账台账（🟡 已实现未接线）

- 完整 i18n：strings.xml 只有 1 条串
MD
out="$(run_guard "$d")"; rc=$?
assert_red "case9 pending 理由未挂账 (第 13 条③)" "$rc" "$out" \
  "AgentRequest 可空字段无孤儿（代码完备但未接线）"
if printf '%s\n' "$out" | grep -qF "未在 README.md 的挂账台账节出现"; then
  echo "PASS [case9b] 报出「未在 README.md 的挂账台账节出现」（标记 ↔ 挂账交叉生效）"
  PASS=$((PASS + 1))
else
  echo "FAIL [case9b] 未报出挂账缺失（③ 交叉断言失效）"
  printf '%s\n' "$out" | sed 's/^/    | /'
  FAIL=$((FAIL + 1))
fi

# ---------------------------------------------------------------------------
# case 10：同一 fixture 里把理由串**补进** README 挂账台账节 ⇒ 第 13 条必须**不红**。
#          钉死 ③ 的「绿」面：只有红面会让它成为永远红的僵尸规则（挂着账就必须放行）。
# ---------------------------------------------------------------------------
d="$TMP/case10-pending-ledgered"
scaffold "$d"
cat > "$d/core-agent/src/main/java/com/rickeal/agent/core/agent/AgentEvents.kt" <<'KT'
package com.rickeal.agent.core.agent

data class AgentRequest(
    // @wire-owner: pending:示例未接线理由
    val pendingField: String? = null,
)
KT
cat > "$d/README.md" <<'MD'
# 项目

## 挂账台账（🟡 已实现未接线）

- 示例未接线理由：已挂账，待接线
MD
out="$(run_guard "$d")"; rc=$?
if [ "$rc" -eq 0 ]; then
  echo "PASS [case10 pending 理由已挂账 (第 13 条③绿面)] 退出 0（未误红）"
  PASS=$((PASS + 1))
else
  echo "FAIL [case10] pending 理由已在 README 挂账台账节出现，应不红，实际退出 $rc"
  printf '%s\n' "$out" | sed 's/^/    | /'
  FAIL=$((FAIL + 1))
fi

# ---------------------------------------------------------------------------
# case 11：baseline 条目数超冻结值 ⇒ 第 14 条必须报红（防 baseline 变成
#          「顺手把新问题 regen 进去」的僵尸豁免入口 —— 那会让整条 lint 门禁失效）。
#          fixture 用 84 条 issue：验证计数只数 <issue 元素、不计根元素 <issues
#          （若把 <issues 也算进去，85 > 83 恒红会让本 case 的判据失真）。
# case11b：条目数低于冻结值（清了存量）⇒ 必须不红（「只许缩不许涨」的另一面）。
# ---------------------------------------------------------------------------
d="$TMP/case11-baseline-overage"
scaffold "$d"
python - "$d/app/lint-baseline.xml" <<'PYGEN'
import sys
p = sys.argv[1]
one_issue = (
    '    <issue\n'
    '        id="AutoboxingStateCreation"\n'
    '        message="Entity state should be created with primitive values">\n'
    '        <location\n'
    '            file="src/main/java/com/rickeal/agent/LiquidAgentApplication.kt"\n'
    '            line="10"/>\n'
    '    </issue>\n'
)
# 直接重写为恰好 84 条（骨架自带的 2 条不计入 —— 总数必须与断言的「现有 84 条」一致）
open(p, 'w', encoding='utf-8').write(
    '<?xml version="1.0" encoding="UTF-8"?>\n'
    '<issues format="6" by="lint 9.3.2" type="baseline" client="gradle" dependencies="true">\n'
    + one_issue * 84
    + '</issues>\n'
)
PYGEN
out="$(run_guard "$d")"; rc=$?
assert_red "case11 baseline 超冻结值 (第 14 条)" "$rc" "$out" \
  "lint baseline 条目数未超冻结值（83，只许清障不许新增豁免）"
if printf '%s\n' "$out" | grep -qF "现有 84 条"; then
  echo "PASS [case11b] 报出「现有 84 条」（计数恰为 84 个 issue 元素，根元素 <issues 未被计入）"
  PASS=$((PASS + 1))
else
  echo "FAIL [case11b] 未报出「现有 84 条」（第 14 条红面或计数模式失效）"
  printf '%s\n' "$out" | sed 's/^/    | /'
  FAIL=$((FAIL + 1))
fi

d="$TMP/case11b-baseline-shrunk"
scaffold "$d"
python - "$d/app/lint-baseline.xml" <<'PYGEN'
import sys, re
p = sys.argv[1]
s = open(p, encoding='utf-8').read()
s = re.sub(r'    <issue\n(?:.|\n)*?</issue>\n', '', s)
open(p, 'w', encoding='utf-8').write(s)
PYGEN
out="$(run_guard "$d")"; rc=$?
if [ "$rc" -eq 0 ]; then
  echo "PASS [case11c] baseline 缩到 0 条不红（清障合法；只有文件缺失或超冻结值才红）"
  PASS=$((PASS + 1))
else
  echo "FAIL [case11c] baseline 缩小被误红（第 14 条把「只许缩」实现成了「不许变」）"
  printf '%s\n' "$out" | sed 's/^/    | /'
  FAIL=$((FAIL + 1))
fi

# ---------------------------------------------------------------------------
echo "-----------------------------------------"
echo "自测结果：PASS=$PASS FAIL=$FAIL"
if [ "$FAIL" -ne 0 ]; then
  echo "arch-guard 自测未通过：有 $FAIL 条 case 失败（守卫可能已成僵尸规则或误红）。"
  exit 1
fi
echo "arch-guard 自测全部通过。"
exit 0
