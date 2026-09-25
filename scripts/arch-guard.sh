#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# 架构守卫（思路借鉴 Edge0：用 grep 在 CI 里强制依赖方向，而不是靠人自觉）
#
# 编译能过 ≠ 架构没被破坏。下面这些是本项目最容易在不知不觉中被打破的边界，
# 一旦被打破，后续会付出「引擎细节泄漏到 UI」「设计系统反过来依赖业务模型」
# 「网络栈悄悄回流」等返工与信任代价。
#
# Wave4 六路审查（D-P0-4/P1-6、C 增补）加固记录：
#  - check() 三态：0=无命中(OK)、1=命中(违规)、≥2=守卫命令自身出错 —— 后者必须
#    判红而不是判 OK。旧实现 `bash -c '... || true'` 把所有非零都吞成 OK，
#    「目录被改名 → 空守卫永远绿」；「grep 自身报错 → 假绿」。
#  - 注释排除从「行首是注释」放宽为「行内含 // 或 /* 或 *  开头的注释段」：
#    行尾注释（`val x = 1 // uses java.net.URL internally`）此前会假红，逼开发者
#    把注释改得更难懂。grep 做不了完整词法，宁可放过个别、不让真违规无处可藏 ——
#    真要写网络栈的人得故意写成 `val u = "java" + ".net.URL"`，这已经是有意为之。
#  - 扫描面排除 .git/build/.gradle/.kotlin（本地跑守卫时 build/ 生成代码不再假红）；
#    纳入 AndroidManifest.xml、*.pro 与 gradle/*.toml（版本目录声明的依赖此前完全盲区）。
# ---------------------------------------------------------------------------
set -uo pipefail

fail=0

# 三态检查：$1=名称，其余=命令。退出码语义：
#   0 且输出空 = OK；输出非空 = 违规；≥2 = 守卫自身故障（必须红，绝不能静默 OK）
check() {
  local name="$1"
  shift
  local rc=0
  out="$("$@" 2>/dev/null)" || rc=$?
  if [ "$rc" -ge 2 ]; then
    echo "::error::[$name] 守卫命令自身执行失败（exit $rc），按失败处理（防静默失效）"
    fail=1
  elif [ -n "$out" ]; then
    echo "::error::[$name] 违反约束："
    echo "$out" | head -20
    fail=1
  else
    echo "OK  $name"
  fi
}

# 前置断言：被守卫依赖的模块目录必须存在。目录被改名/删除会让对应守卫变成
# 「永远通过的空守卫」—— 那比没有守卫更糟（给人已被保护的错觉）。
for d in core-model core-design core-engine core-agent core-data app; do
  if [ ! -d "$d" ]; then
    echo "::error::[模块存在性] 守卫目标目录不存在：$d（被改名或删除？守卫面已失效）"
    fail=1
  fi
done

# grep 公共参数：排除构建产物与 VCS 目录；注释行过滤统一走 grep -vE EXCLUDE
EXCL=(--exclude-dir=.git --exclude-dir=build --exclude-dir=.gradle --exclude-dir=.kotlin)
# 行首整段注释（* / // /*）或行尾注释（// ...）都放行 —— 假红比漏报更伤守卫公信力
EXCLUDE_COMMENT='^[^:]+:[0-9]+:.*(^|[[:space:]])(//|/\*|\*)'

# 1) LiteRT-LM 只允许出现在 :core-engine（引擎可替换性的底线）
check "litertlm 仅存在于 core-engine" \
  bash -c 'grep -rl "com.google.ai.edge.litertlm" --include="*.kt" '"${EXCL[*]}"' . | grep -v "/core-engine/"'

# 2) :core-design 必须零业务依赖（否则每次改领域模型都会触发全量 UI 重编译）
check "core-design 不依赖领域/功能模块" \
  bash -c 'grep -rn "com.rickeal.agent.core.model\|com.rickeal.agent.feature\|com.rickeal.agent.core.data" --include="*.kt" core-design/'

# 3) :core-model 必须保持无框架（纯 Kotlin，便于将来做 JVM 单测）
#
# 匹配所有 "android." / "androidx." 出现，**不只是 import 行**：Kotlin 允许全限定名直接引用
# （如 `val s = android.os.Build.SOC_MODEL`），那样写不需要 import，只查 import 会漏过去。
check "core-model 无 android/androidx 依赖" \
  bash -c 'grep -rn "android\.\|androidx\." --include="*.kt" core-model/ | grep -vE "'"$EXCLUDE_COMMENT"'"'

# 4) 禁止被明令禁止的依赖（.kts 字面量 + gradle 版本目录双覆盖）
#    C 增补：版本目录（libs.versions.toml）声明的依赖在 .kts 里只剩 libs.xxx 访问器，
#    只扫 .kts 时 retrofit/koin 换个声明方式就完全失明 —— .toml 必须同扫。
check "禁止的依赖（KSP/Room/Hilt/Koin/Retrofit/Coil，含版本目录）" \
  bash -c 'grep -rn "androidx\.room\|com\.google\.dagger\|io\.insert-koin\|org\.koin\|com\.squareup\.retrofit\|io\.coil-kt\|com\.google\.devtools\.ksp" --include="*.kts" --include="*.toml" '"${EXCL[*]}"' .'

# 5) 全仓禁网络栈（纯端侧收敛：远程引擎已整体移除，任何直连网络栈的代码
#    都是对「模型任务不出设备」承诺的破坏）。android.net.Uri / ConnectivityManager
#    这类平台 API 不在拦截面（附件 SAF 与下载流量提示是合法用途）。
#    模式覆盖（D-P0-4）：通配 import（java.net.*）、HttpsURLConnection、
#    SocketChannel、InetAddress、okio、java.net.http（J11 HttpClient）。
check "全仓禁网络栈（java.net/javax.net/okhttp/okio/HttpClient/SocketChannel）" \
  bash -c 'grep -rnE "okhttp3?\.|com\.squareup\.okhttp|okio\.|Http(s)?URLConnection|java\.net\.(Socket|URL|URI|http|InetAddress|DatagramSocket)|java\.nio\.channels\.(Socket|AsynchronousSocket)Channel|javax\.net|import java\.net\.\*" --include="*.kt" '"${EXCL[*]}"' . | grep -vE "'"$EXCLUDE_COMMENT"'"'

# 6) 全仓禁进程执行（模型工具面不得拉起子进程 —— 沙箱的最后一道边界）。
#    两步走（`val rt = Runtime.getRuntime()` 换行 `rt.exec(...)`）此前完全绕过：
#    拆成「Runtime.getRuntime()」与「.exec(」双锚点，分别命中即报。
check "全仓禁进程执行（ProcessBuilder/Runtime.exec 两段锚点）" \
  bash -c 'grep -rnE "ProcessBuilder|Runtime\.getRuntime|\bexec\(" --include="*.kt" '"${EXCL[*]}"' . | grep -vE "'"$EXCLUDE_COMMENT"'"'

# 7) 声明面守卫（Wave 12 修订，方向反转）：INTERNET 必须**存在** —— 系统下载服务
#    的 enqueue() 会校验请求方权限，Wave 4 误判「请求方不需要」导致真机 SecurityException、
#    模型直链下载从未成功过（详见 AndroidManifest.xml 的 INTERNET 注释）。
#    纯端侧承诺的实质在代码面：第 5 条禁网络栈 / 第 6 条禁进程执行不变 ——
#    INTERNET 只用于系统 DownloadManager 替我们取回模型文件。
check "Manifest 有 INTERNET（系统下载链路必需，Wave 12）" \
  bash -c 'grep -q "android.permission.INTERNET" app/src/main/AndroidManifest.xml'

# 7b) 明文流量禁令保留：下载源全部 https（Wave 6 口径：HF/hf-mirror/ModelScope 39 直链实测）。
check "Manifest 无明文流量（下载源必须 https）" \
  bash -c '! grep -rn "usesCleartextTraffic=\"true\"" --include="AndroidManifest.xml" '"${EXCL[*]}"' .'

# 8) R8 规则守卫：proguard 不得残留网络栈 -dontwarn（D-P1-3：死配置会把
#    传递回流进来的 okhttp 静默吞掉，构建照样绿）
check "proguard 无网络栈残留" \
  bash -c 'grep -rn "okhttp3\|okio\." --include="*.pro" '"${EXCL[*]}"' .'

# 9) 禁动态代码加载（DexClassLoader 系 = 任意代码注入面，端侧 Agent 无正当用途）
check "全仓禁动态代码加载（DexClassLoader/loadLibrary）" \
  bash -c 'grep -rnE "DexClassLoader|PathClassLoader|InMemoryDexClassLoader|System\.loadLibrary|System\.load\(" --include="*.kt" '"${EXCL[*]}"' . | grep -vE "'"$EXCLUDE_COMMENT"'"'

echo "-----------------------------------------"
if [ "$fail" -ne 0 ]; then
  echo "架构守卫未通过，请修复上述问题后再合并。"
  exit 1
fi
echo "架构守卫全部通过。"
