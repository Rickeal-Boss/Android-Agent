#!/usr/bin/env bash
# =============================================================================
# fulltest.sh — 全量 JVM 单测入口（固定带 --continue，禁用 Gradle fail-fast 残缺口径）
#
# 为什么必须固定带 --continue（本脚本存在的唯一理由）：
#   Gradle 默认 fail-fast —— 第一个失败的模块会**中止后续模块**，于是 gradle 只报
#   「N tests completed」，这是**残缺口径**：没跑到的模块一个用例都不计入。
#   W48 实证：core-data 失败掩盖了 feature-chat 的长期 flaky —— 单测门禁看起来
#   「只挂 1 个」，实际有模块根本没被跑到。
#   这条教训此前只落在 handoff 文档、承载它的 _ci-tools/localbuild.sh 在**仓库外**
#   （git 外）⇒ 下个接手者 / 换会话直接 `gradle test`，残缺口径就会回来。
#   故把这条纪律**固化进仓库**（本文件），并由 scripts/arch-guard.sh 新增守卫钉住
#   「本文件存在且含 --continue」—— 防止纪律文件自己蒸发（本仓已有多次「描述不成立」
#   的前科）。
#
# 用法：
#   scripts/fulltest.sh                 # 跑聚合 task `test`（覆盖全部 9 个模块）
#   scripts/fulltest.sh --task test     # 显式指定聚合 task
#   scripts/fulltest.sh <gradle-task>   # 透传任意 task
#
# 运行环境（不依赖任何仓库外文件）：
#   · CI（ubuntu runner）：仓库 wrapper 可用，脚本直接用 ./gradlew —— 与 build.yml 的
#     unit-tests job 同口径（固定 `test --continue`）。
#   · 本地无系统 JDK / SDK：先按你本地的方式注入 JAVA_HOME / ANDROID_HOME /
#     GRADLE_USER_HOME，再运行本脚本即可（本仓本地环境见仓库外 _ci-tools/LOCALBUILD.md）。
#
# 判定口径（关键）：
#   本脚本**不信** gradle 打印的「N tests completed」——那是残缺口径。
#   改为汇总各模块 build/test-results/**/TEST-*.xml 里 testsuite 的
#   tests / failures / errors 属性（**真实用例数**），任一 failures>0 或 errors>0
#   即判失败；gradle 自身非零退出（编译失败 / 配置失败）同样判失败。
#
# 退出码：0 = 全绿；非 0 = 有用例失败 / 构建失败 / 环境缺失。
# =============================================================================
set -uo pipefail

TASK="test"
while [ $# -gt 0 ]; do
  case "$1" in
    --task)   TASK="${2:-test}"; shift 2 ;;
    --task=*) TASK="${1#--task=}"; shift ;;
    -h|--help)
      sed -n '2,40p' "$0"
      exit 0 ;;
    *)        TASK="$1"; shift ;;
  esac
done

# ---- 解析 Gradle 启动器（仓库 wrapper 优先，其次 PATH 上的 gradle）----
if [ -x ./gradlew ]; then
  GRADLE_CMD="./gradlew"
elif command -v gradle >/dev/null 2>&1; then
  GRADLE_CMD="gradle"
else
  echo "::error::既找不到 ./gradlew 也找不到 gradle —— 无法运行单测（CI 上应有 ./gradlew；本地请先注入环境）" >&2
  exit 2
fi

echo "[fulltest] launcher=$GRADLE_CMD  task=$TASK  （固定带 --continue，禁用 fail-fast 残缺口径）"

# 清掉上一轮的测试结果，避免旧 TEST-*.xml 污染本轮汇总
find . -path ./.git -prune -o -type d -name 'test-results' -print 2>/dev/null | while IFS= read -r d; do
  rm -rf "$d" 2>/dev/null || true
done

"$GRADLE_CMD" --no-daemon --stacktrace "$TASK" --continue
gradle_rc=$?

# ---- 汇总 TEST-*.xml（真实用例数口径，非 gradle 的「N tests completed」）----
total=0; failures=0; errors=0; skipped=0; files=0
while IFS= read -r xml; do
  [ -z "$xml" ] && continue
  files=$((files + 1))
  head_tag="$(grep -m1 -oE '<testsuite[^>]*>' "$xml" 2>/dev/null || true)"
  t="$(printf '%s' "$head_tag" | grep -oE 'tests="[0-9]+"'    | head -1 | tr -cd '0-9')"
  f="$(printf '%s' "$head_tag" | grep -oE 'failures="[0-9]+"' | head -1 | tr -cd '0-9')"
  e="$(printf '%s' "$head_tag" | grep -oE 'errors="[0-9]+"'   | head -1 | tr -cd '0-9')"
  s="$(printf '%s' "$head_tag" | grep -oE 'skipped="[0-9]+"'  | head -1 | tr -cd '0-9')"
  total=$((total + ${t:-0}))
  failures=$((failures + ${f:-0}))
  errors=$((errors + ${e:-0}))
  skipped=$((skipped + ${s:-0}))
done < <(find . -path ./.git -prune -o -type f -name 'TEST-*.xml' -print 2>/dev/null)

echo "-------------------------------------------------------------------"
if [ "$files" -eq 0 ]; then
  echo "[fulltest] 未找到任何 TEST-*.xml —— 用例数无法汇总（多为编译/配置失败，未产生测试结果）。"
  echo "[fulltest] gradle 退出码 = $gradle_rc"
else
  echo "[fulltest] 汇总 $files 个 TEST-*.xml（真实用例数口径，非 gradle 的「N tests completed」）："
  echo "[fulltest]   tests=$total  failures=$failures  errors=$errors  skipped=$skipped"
fi
echo "-------------------------------------------------------------------"

if [ "$gradle_rc" -ne 0 ]; then
  echo "[fulltest] 失败：gradle 退出码 $gradle_rc（编译/配置/用例失败，详见上方输出）"
  exit "$gradle_rc"
fi
if [ "$failures" -gt 0 ] || [ "$errors" -gt 0 ]; then
  echo "[fulltest] 失败：failures=$failures errors=$errors（真实用例失败数）"
  exit 1
fi
if [ "$files" -eq 0 ]; then
  echo "[fulltest] 失败：gradle 成功但没有任何 TEST-*.xml（task '$TASK' 可能不含测试，或结果目录被改）"
  exit 1
fi
echo "[fulltest] 通过：全部用例绿（tests=$total failures=0 errors=0）。"
exit 0
