#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# 架构守卫（思路借鉴 Edge0：用 grep 在 CI 里强制依赖方向，而不是靠人自觉）
#
# 编译能过 ≠ 架构没被破坏。下面三条是本项目最容易在不知不觉中被打破的边界，
# 一旦被打破，后续会付出「引擎细节泄漏到 UI」「设计系统反过来依赖业务模型」等返工代价。
# ---------------------------------------------------------------------------
set -uo pipefail

fail=0

check() {
  local name="$1"
  shift
  if out="$("$@" 2>/dev/null)"; then
    if [ -n "$out" ]; then
      echo "::error::[$name] 违反约束："
      echo "$out" | head -20
      fail=1
    else
      echo "OK  $name"
    fi
  else
    echo "OK  $name"
  fi
}

# 1) LiteRT-LM 只允许出现在 :core-engine（引擎可替换性的底线）
check "litertlm 仅存在于 core-engine" \
  bash -c 'grep -rl "com.google.ai.edge.litertlm" --include="*.kt" . | grep -v "/core-engine/" || true'

# 2) :core-design 必须零业务依赖（否则每次改领域模型都会触发全量 UI 重编译）
check "core-design 不依赖领域/功能模块" \
  bash -c 'grep -rn "com.rickeal.agent.core.model\|com.rickeal.agent.feature\|com.rickeal.agent.core.data" --include="*.kt" core-design/ || true'

# 3) :core-model 必须保持无框架（纯 Kotlin，便于将来做 JVM 单测）
#
# 匹配所有 "android." / "androidx." 出现，**不只是 import 行**：Kotlin 允许全限定名直接引用
# （如 `val s = android.os.Build.SOC_MODEL`），那样写不需要 import，只查 import 会漏过去。
# 但注释里提到 android 是允许的（例如解释某字段对应 Android 平台的什么），所以排除
# 以 `*` / `//` / `/*` 开头的行，避免误伤——真正要拦的是**代码里的引用**。
check "core-model 无 android/androidx 依赖" \
  bash -c 'grep -rn "android\.\|androidx\." --include="*.kt" core-model/ \
    | grep -vE "^[^:]+:[0-9]+:[[:space:]]*(\*|//|/\*)" || true'

# 4) 禁止被明令禁止的依赖
check "禁止的依赖（KSP/Room/Hilt/Koin/Retrofit/Coil）" \
  bash -c 'grep -rn "androidx.room\|com.google.dagger\|org.koin\|com.squareup.retrofit\|io.coil-kt\|com.google.devtools.ksp" --include="*.kts" . || true'

# 5) 全仓禁网络栈（纯端侧收敛：远程引擎已整体移除，任何直连网络栈的代码
#    都是对「模型任务不出设备」承诺的破坏）。android.net.Uri / ConnectivityManager
#    这类平台 API 不在拦截面（附件 SAF 与下载流量提示是合法用途）。
check "全仓禁网络栈（okhttp/HttpURLConnection/java.net/javax.net）" \
  bash -c 'grep -rn "okhttp3\.\|HttpURLConnection\|java\.net\.Socket\|java\.net\.URL\|java\.net\.URI\|javax\.net\|okio\." --include="*.kt" . \
    | grep -vE "^[^:]+:[0-9]+:[[:space:]]*(\*|//|/\*)" || true'

# 6) 全仓禁进程执行（模型工具面不得拉起子进程 —— 沙箱的最后一道边界）
check "全仓禁进程执行（ProcessBuilder/Runtime.exec）" \
  bash -c 'grep -rn "ProcessBuilder\|Runtime\.getRuntime()\.exec" --include="*.kt" . \
    | grep -vE "^[^:]+:[0-9]+:[[:space:]]*(\*|//|/\*)" || true'

# 5) 禁止在业务代码里吞异常的裸 catch（经验性检查，仅提示）
echo "-----------------------------------------"
if [ "$fail" -ne 0 ]; then
  echo "架构守卫未通过，请修复上述问题后再合并。"
  exit 1
fi
echo "架构守卫全部通过。"
