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
trap 'rm -rf "$TMP"' EXIT

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
echo "-----------------------------------------"
echo "自测结果：PASS=$PASS FAIL=$FAIL"
if [ "$FAIL" -ne 0 ]; then
  echo "arch-guard 自测未通过：有 $FAIL 条 case 失败（守卫可能已成僵尸规则或误红）。"
  exit 1
fi
echo "arch-guard 自测全部通过。"
exit 0
