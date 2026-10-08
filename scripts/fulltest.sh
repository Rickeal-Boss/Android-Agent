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
#   scripts/fulltest.sh --summary-only  # 【Wave 50】只汇总现有 TEST-*.xml（不重跑、不判失败、
#                                       # 永远 exit 0）—— 供 CI 的非阻断 job-summary 步骤复用
#                                       # （见 .github/workflows/build.yml 的 unit-tests job）。
#                                       # 为什么拆成子命令而不是让 CI 整脚本调用：默认模式会先
#                                       # 清 test-results/ 再重跑全量测试、且失败时 exit 非零
#                                       # —— 两者都不适合「跑完测试后只出一份报告」的场景。
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
SUMMARY_ONLY=0
while [ $# -gt 0 ]; do
  case "$1" in
    --task)         TASK="${2:-test}"; shift 2 ;;
    --task=*)       TASK="${1#--task=}"; shift ;;
    --summary-only) SUMMARY_ONLY=1; shift ;;
    -h|--help)
      sed -n '2,40p' "$0"
      exit 0 ;;
    *)              TASK="$1"; shift ;;
  esac
done

# ---- 跑测试（仅默认模式）----
# ① rm 失败目录收集（Wave 52）：仅默认模式填充（rm 段在其中）；汇总段据此前置 ::warning::。
rm_failed=()
if [ "$SUMMARY_ONLY" -eq 0 ]; then
  # 解析 Gradle 启动器（仓库 wrapper 优先，其次 PATH 上的 gradle）
  if [ -x ./gradlew ]; then
    GRADLE_CMD="./gradlew"
  elif command -v gradle >/dev/null 2>&1; then
    GRADLE_CMD="gradle"
  else
    echo "::error::既找不到 ./gradlew 也找不到 gradle —— 无法运行单测（CI 上应有 ./gradlew；本地请先注入环境）" >&2
    exit 2
  fi

  echo "[fulltest] launcher=$GRADLE_CMD  task=$TASK  （固定带 --continue，禁用 fail-fast 残缺口径）"

  # 清掉上一轮的测试结果，避免旧 TEST-*.xml 污染本轮汇总。
  # ① rm 失败**可见化**（Wave 52）：不再用 `2>/dev/null || true` 静默吞删除失败 ——
  #    Windows 文件锁 / 只读 / 权限会让 rm 失败，此时若 gradle 也失败，汇总段会拿
  #    **陈旧 XML** 当权威（g_files>0 ⇒ 不打印 warning）⇒ 这是「假全量」唯一真实的
  #    fail-open 面（报告说的「gradle 未跑仍汇总残留」在现行脚本下不复现：本段自脚本
  #    创建起就在 gradle 之前）。收集失败目录，汇总段（**仅默认模式**）前置 ::warning::。
  #    ⚠️ 必须用进程替换 `< <(find …)` 而非管道 `| while`：管道的 while 跑在子壳里，
  #    rm_failed 的累积**不会回传父壳**（Wave 52 实测）。
  while IFS= read -r d; do
    rm -rf "$d" 2>/dev/null || rm_failed+=("$d")
  done < <(find . -path ./.git -prune -o -type d -name 'test-results' -print 2>/dev/null)

  "$GRADLE_CMD" --no-daemon --stacktrace "$TASK" --continue
  gradle_rc=$?
else
  gradle_rc=0
  echo "[fulltest] --summary-only：仅汇总现有 TEST-*.xml（不重跑测试、不判失败）"
fi

# ---- 汇总 TEST-*.xml（真实用例数口径，非 gradle 的「N tests completed」）----
# 结果经全局变量回传：g_total / g_failures / g_errors / g_skipped / g_files
# （抽成函数是为了让「默认模式」与「--summary-only 报告模式」共用同一段汇总逻辑，
#   杜绝两份实现漂移 —— Wave 50 引入 --summary-only 时的核心取舍。）
# 时间戳格式化（Wave 52）：GNU date 与 BSD date 双兼容（CI=ubuntu GNU；本地 Git-Bash GNU）。
# $1 = epoch 秒；两种 date 都失败时回退打印 epoch（绝不因格式化失败而中断汇总）。
fmt_ts() {
  date -d "@$1" '+%Y-%m-%d %H:%M:%S' 2>/dev/null \
    || date -r "$1" '+%Y-%m-%d %H:%M:%S' 2>/dev/null \
    || echo "epoch $1"
}

summarize_tests() {
  g_total=0; g_failures=0; g_errors=0; g_skipped=0; g_files=0
  local -A M_TOTAL=() M_FAIL=() M_ERR=()
  local -a MTIMES=()
  local xml head_tag t f e s mod m nmod mt
  while IFS= read -r xml; do
    [ -z "$xml" ] && continue
    g_files=$((g_files + 1))
    # ② 收集每个 TEST-*.xml 的 mtime（Wave 52）：供人眼判「这些 XML 是否本轮产出」。
    mt="$(stat -c %Y "$xml" 2>/dev/null || stat -f %m "$xml" 2>/dev/null || true)"
    [ -n "$mt" ] && MTIMES+=("$mt")
    head_tag="$(grep -m1 -oE '<testsuite[^>]*>' "$xml" 2>/dev/null || true)"
    t="$(printf '%s' "$head_tag" | grep -oE 'tests="[0-9]+"'    | head -1 | tr -cd '0-9')"
    f="$(printf '%s' "$head_tag" | grep -oE 'failures="[0-9]+"' | head -1 | tr -cd '0-9')"
    e="$(printf '%s' "$head_tag" | grep -oE 'errors="[0-9]+"'   | head -1 | tr -cd '0-9')"
    s="$(printf '%s' "$head_tag" | grep -oE 'skipped="[0-9]+"'  | head -1 | tr -cd '0-9')"
    g_total=$((g_total + ${t:-0}))
    g_failures=$((g_failures + ${f:-0}))
    g_errors=$((g_errors + ${e:-0}))
    g_skipped=$((g_skipped + ${s:-0}))
    mod="$(printf '%s' "$xml" | sed -E 's#^\./##; s#/.*##')"
    M_TOTAL[$mod]=$(( ${M_TOTAL[$mod]:-0} + ${t:-0} ))
    M_FAIL[$mod]=$((  ${M_FAIL[$mod]:-0}  + ${f:-0} ))
    M_ERR[$mod]=$((   ${M_ERR[$mod]:-0}   + ${e:-0} ))
  done < <(find . -path ./.git -prune -o -type f -name 'TEST-*.xml' -print 2>/dev/null)

  echo "-------------------------------------------------------------------"
  # ① rm 失败可见化（Wave 52，**仅默认模式**）：删除失败 + gradle 失败 ⇒ 汇总可能来自
  #    陈旧 XML。--summary-only 分支绝不打印（它本就不跑 rm、不改退出码 —— 见 #23 契约）。
  if [ "$SUMMARY_ONLY" -eq 0 ] && [ "${#rm_failed[@]}" -gt 0 ]; then
    echo "[fulltest] ⚠️ 以下 test-results 目录未能清除（文件锁/只读/权限？）："
    printf '[fulltest]   - %s\n' "${rm_failed[@]}"
    echo "::warning::有 ${#rm_failed[@]} 个 test-results 目录未能清除 —— 若 gradle 也失败，下方汇总可能来自**残留的陈旧 XML**（非本轮结果，请人工核对 mtime）"
  fi
  if [ "$g_files" -eq 0 ]; then
    echo "[fulltest] 未找到任何 TEST-*.xml —— 用例数无法汇总。"
    # GitHub 注解：CI 上会以**黄色 warning** 显示在 PR 页面 / Annotations 面板，
    # 人眼可见 —— 比 arch-guard 第 23 条（只防脚本自身退出码、防不住 files==0 静默）
    # 更快堵住「测试根本没跑、用例数汇总为空却无人察觉」的场景。二者互补。
    echo "::warning::未找到任何 TEST-*.xml —— 用例数无法汇总（可能是测试根本没跑）"
    if [ "$SUMMARY_ONLY" -eq 1 ]; then
      # 报告模式**没有跑 gradle** ⇒ 绝不能打印「gradle 退出码」（会让读者以为跑过且成功）。
      echo "[fulltest] （--summary-only 模式未执行 gradle，仅汇总现有 XML；请先跑一次默认模式产生结果）"
    else
      echo "[fulltest] gradle 退出码 = ${gradle_rc:-n/a}（多为编译/配置失败，未产生测试结果）"
    fi
  else
    echo "[fulltest] 汇总 $g_files 个 TEST-*.xml（真实用例数口径，非 gradle 的「N tests completed」）："
    echo "[fulltest]   tests=$g_total  failures=$g_failures  errors=$g_errors  skipped=$g_skipped"
    if [ "${#M_TOTAL[@]}" -gt 0 ]; then
      for m in $(printf '%s\n' "${!M_TOTAL[@]}" | LC_ALL=C sort); do
        echo "[fulltest]   - ${m}: tests=${M_TOTAL[$m]} failures=${M_FAIL[$m]} errors=${M_ERR[$m]}"
      done
    fi
    # ② TEST-*.xml 的 mtime 最早/最晚（Wave 52）：供人眼判「这些 XML 是否本轮产出」。
    if [ "${#MTIMES[@]}" -gt 0 ]; then
      local mt_min mt_max
      mt_min="$(printf '%s\n' "${MTIMES[@]}" | sort -n | head -1)"
      mt_max="$(printf '%s\n' "${MTIMES[@]}" | sort -n | tail -1)"
      echo "[fulltest]   TEST-*.xml mtime 范围：最早 $(fmt_ts "$mt_min")  最晚 $(fmt_ts "$mt_max")"
    fi
    # ③ 默认模式下模块数 != 9 ⇒ 前置 ::warning::（Wave 52，**不改退出码** —— 与 #23 契约、
    #    与「非阻断」设计一致；只把「静默缩面」变人眼可见）。期望 9 = settings.gradle.kts
    #    的 include 数（与 arch-guard 前置断言的清单同源）。
    nmod="${#M_TOTAL[@]}"
    echo "[fulltest]   覆盖模块数：$nmod（预期 9）"
    if [ "$SUMMARY_ONLY" -eq 0 ] && [ "$nmod" -ne 9 ]; then
      echo "::warning::默认模式汇总到的模块数 = $nmod（预期 9）—— 可能有模块未跑 / 结果目录缺失 / rm 未清干净致陈旧 XML 混入，请人工核对"
    fi
  fi
  echo "-------------------------------------------------------------------"
}

summarize_tests

# --summary-only：纯报告模式，永远 exit 0（不 gate；供 CI 非阻断步骤复用）
if [ "$SUMMARY_ONLY" -eq 1 ]; then
  exit 0
fi

if [ "$gradle_rc" -ne 0 ]; then
  echo "[fulltest] 失败：gradle 退出码 $gradle_rc（编译/配置/用例失败，详见上方输出）"
  exit "$gradle_rc"
fi
if [ "$g_failures" -gt 0 ] || [ "$g_errors" -gt 0 ]; then
  echo "[fulltest] 失败：failures=$g_failures errors=$g_errors（真实用例失败数）"
  exit 1
fi
if [ "$g_files" -eq 0 ]; then
  echo "[fulltest] 失败：gradle 成功但没有任何 TEST-*.xml（task '$TASK' 可能不含测试，或结果目录被改）"
  exit 1
fi
echo "[fulltest] 通过：全部用例绿（tests=$g_total failures=0 errors=0）。"
exit 0
