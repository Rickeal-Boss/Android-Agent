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
#
# ⚠️ 契约是「**输出非空 = 违规**」，不是「退出码非 0 = 违规」。写守卫命令时踩过两次：
#   ① `grep -q`（无输出）失败时退出 1，被 check 判成 OK —— 第 7 条就因此成了
#      「永远通过」的僵尸规则（实测：把 INTERNET 权限整行删掉，守卫仍全绿退出 0）。
#   ② 同理，`! grep -q ...` 在「该出现的东西没出现」时也是静默 OK。
#   结论：**断言「必须存在」的守卫，必须自己 echo 一行**，例如
#      `bash -c 'grep -q "X" f || echo "f 缺少 X"'`
#   而断言「必须不存在」的守卫沿用 `grep -rn`（命中即输出），天然合规。
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
#    Wave 30 增补：app.cash.sqldelight 走 Gradle 插件 codegen（无 KSP/Kapt 字面），
#    上述坐标拦不住它 —— 它属于「被禁的那一类」（生成物无法本地预演），
#    显式入列堵住守卫盲区（三项决策建议复评 v4 ②）。
check "禁止的依赖（KSP/Room/Hilt/Koin/Retrofit/Coil/SQLDelight，含版本目录）" \
  bash -c 'grep -rn "androidx\.room\|com\.google\.dagger\|io\.insert-koin\|org\.koin\|com\.squareup\.retrofit\|io\.coil-kt\|com\.google\.devtools\.ksp\|app\.cash\.sqldelight" --include="*.kts" --include="*.toml" '"${EXCL[*]}"' .'

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
#
#    Wave 30 复审修正：原写法 `grep -q` 让本条成为**僵尸规则** —— grep -q 无输出，
#    权限被删时退出 1，check() 只看输出，结果「权限不存在」也判 OK（实测删掉整行后
#    守卫仍全绿退出 0，与 Wave 4 那次真机 SecurityException 的成因正好对上）。
#    「必须存在」型断言改成失败时自己 echo，见 check() 上方契约说明。
check "Manifest 有 INTERNET（系统下载链路必需，Wave 12）" \
  bash -c 'grep -q "android.permission.INTERNET" app/src/main/AndroidManifest.xml || echo "app/src/main/AndroidManifest.xml 缺少 android.permission.INTERNET（系统 DownloadManager 下载模型会 SecurityException，见本条注释）"'

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

# 10) :core-agent 不得依赖 :core-data / :core-design（Wave 26 增补）
#     core-agent 是纯 Kotlin 的 agent 内核（Tool / ToolRegistry / AgentRunner / 记忆 / 计划），
#     它必须能在 JVM 上被单测直接拉起。一旦反向依赖 core-data（DataStore、文件仓库）
#     或 core-design（Compose 令牌），内核测试就得先造 Android 环境，「内核可移植」的前提作废。
#
#     动机是 Operit 从一代到二代的架构教训：其一代把 UI 状态、持久化模型、原生接入三类
#     职责装进同一个 app 模块，最终只能靠整体重写拆开（其官方重构计划自述「当前系统不是
#     一棵树，而是多个宿主手工组装出来的共享引用图」）。这条守卫是防止我们走到那一步的
#     最低成本手段 —— 依赖方向一旦错，返工代价是指数级的。
#     注：grep 型守卫对全限定名拼接（"com.rickeal" + ".core.data"）无效，这是已知盲区；
#     本仓无此写法，且真要绕过的人已在有意为之（与第 5/6 条同款取舍）。
check "core-agent 不依赖 core-data/core-design（Wave 26）" \
  bash -c 'grep -rn "com\.rickeal\.agent\.core\.data\|com\.rickeal\.agent\.core\.design" --include="*.kt" core-agent/ | grep -vE "'"$EXCLUDE_COMMENT"'"'

# 12) 主循环方法体积守卫（Wave 31）：JVM 单方法 64KB bytecode 是硬上限，
#     Wave 27 曾因 `e: Method too large: AgentRunner.executeBodyUnchecked` 真实炸过
#     两个 job。源码行数不是字节数，但本仓实测密度约 66 B/行（517 行 ≈ 34.4 KB）⇒
#     550 行 ≈ 36 KB，约为上限的 56%，留 ~1.8x 安全边际。
#     这是本项目唯一一个「已经真实导致过 CI 红」的架构约束，故与依赖方向守卫同级。
#
#     边界匹配（Wave 31 P2-C2 加固）：只认 `private/internal/suspend fun` 会让
#     `override fun` / `inline fun` / `tailrec fun` / 无修饰 `fun` 漏过 ⇒ e 跳到更远、
#     n 虚增**误红**。改为「行首 4 空格 + 非注释（首字符是字母或 @）+ 含 ` fun `」。
#     另：若 executeBodyUnchecked 是类内**最后一个**方法，e 为空会让 n 变负而静默通过
#     （僵尸规则）—— 故对 e 也做非空断言（缺失即判守卫面失效）。
check "主循环方法体积未超预算（executeBodyUnchecked ≤ 550 行）" \
  bash -c 'f=core-agent/src/main/java/com/rickeal/agent/core/agent/AgentRunner.kt
           s=$(grep -n "fun FlowCollector<AgentEvent>.executeBodyUnchecked" "$f" | head -1 | cut -d: -f1)
           if [ -z "$s" ]; then echo "AgentRunner.kt 未找到 executeBodyUnchecked（被改名/删除？守卫面已失效）"; exit 0; fi
           e=$(awk -v s="$s" "NR>s && /^    [a-zA-Z@]/ && /(^|[ ])fun /{print NR; exit}" "$f")
           if [ -z "$e" ]; then echo "AgentRunner.kt 未找到 executeBodyUnchecked 之后的方法边界（方法被挪到类尾？守卫面已失效）"; exit 0; fi
           n=$((e - s))
           if [ "$n" -gt 550 ]; then echo "executeBodyUnchecked 当前 $n 行，超 550 行预算（JVM 单方法 64KB 上限风险，请外提为 FlowCollector<AgentEvent> 扩展方法）"; fi'

# 13) AgentRequest 可空字段接线台账守卫（Wave 31 建 / Wave 32 改「字段自申报意图」三态）：
#     `AgentRequest` 每个 `= null` 字段都必须**自己申报接线归属** —— 标记与字段同处一行上方：
#       // @wire-owner: host          = 由宿主装配 ⇒ 赋值点必须在 app/ feature-*/ core-data/
#       // @wire-owner: internal      = 内核内部透传/子 run 自装配 ⇒ 只在 core-agent/ 查
#       // @wire-owner: pending:理由串 = 已挂账未接线 ⇒ 理由串必须能在 README 挂账台账节 grep 到
#
#     为什么从「全局 grep 找赋值点」改成「字段自申报」：旧实现用 `grep -rn "$fld = "` 全仓找，
#     对 `deadlineNanos` 命中的其实是 core-agent 内部透传点（AgentRunner 父 run 传给子 run）。
#     宿主侧（ChatViewModel 的 3 个构造点）一个都不传 —— 那是**by design**（宿主不知道绝对截止
#     时刻）。所以旧守卫今天是「碰巧正确」：谁把搜索面收紧到宿主侧，deadlineNanos 就恒红，
#     逼后来者加白名单或写无用实参。让字段自己说清「我该在哪儿被赋值」，守卫才是在校验意图。
#
#     四段断言（每段都由 arch-guard-selftest.sh 的 case 钉住触发面）：
#       ① 无 `@wire-owner` 标记即红 —— 防新字段忘了申报（case5 / case7）
#       ② 按标记在**对应源集**查赋值点 —— host 查宿主源集、internal 查 core-agent（case8 钉
#          住「internal + 只在 core-agent 有赋值点」不红，即 deadlineNanos 形态）
#       ③ `pending:` 的理由串必须能在 README 挂账台账节 grep 到（case9）—— 标记 ↔ 挂账双向
#          交叉：挂账清掉、标记还在（或反之）立刻红，杜绝「挂账台账与代码各说各话」
#       ④ 标记总数 == 可空字段总数 —— 双向：既防漏标，也防字段删了标记留下变僵尸标记
#     字段抽取沿用 Wave 31 P2-C1 加固后的 sed（按「`val <名字>: … = null`」只抽名字，不限类型
#     字符集 —— 泛型 / 函数类型字段也能抽到，由 case5b 钉住）。
#     ⚠️ 仍是 grep 型启发式「防新增孤儿 / 防标记漂移」，不是「证明已接线」；同名字段赋值点
#     造成假阴性是已知局限（例如 `model = `）。
check "AgentRequest 可空字段无孤儿（代码完备但未接线）" \
  bash -c 'f=core-agent/src/main/java/com/rickeal/agent/core/agent/AgentEvents.kt
           if [ ! -f "$f" ]; then echo "找不到 $f（被改名/删除？第 13 条守卫面已失效）"; exit 0; fi
           blk=$(sed -n "/^data class AgentRequest(/,/^)/p" "$f")
           # ④ 标记总数 == 可空字段总数（双向：漏标 / 僵尸标记都在此暴露）
           nf=$(printf "%s\n" "$blk" | grep -cE "^ *val [A-Za-z][A-Za-z0-9]*: .*= null")
           nm=$(printf "%s\n" "$blk" | grep -cF "@wire-owner:")
           if [ "$nf" -ne "$nm" ]; then
             echo "AgentRequest 的 @wire-owner 标记数($nm) != 可空字段数($nf)：新增可空字段必须申报归属，删字段必须同时删标记"
           fi
           # ①②③：逐字段（顺序扫描，标记归属于紧随其后的那个可空字段）
           owner=""
           printf "%s\n" "$blk" | while IFS= read -r line; do
             if printf "%s" "$line" | grep -qF "@wire-owner:"; then
               owner=$(printf "%s" "$line" | sed -n "s/.*@wire-owner:[[:space:]]*\([^[:space:]]*\).*/\1/p")
               continue
             fi
             fld=$(printf "%s" "$line" | sed -n "s/^ *val \([A-Za-z][A-Za-z0-9]*\): .*= null.*/\1/p")
             if [ -z "$fld" ]; then continue; fi
             if [ -z "$owner" ]; then
               echo "AgentRequest.$fld 缺 @wire-owner 标记：新增可空字段必须先申报归属（host / internal / pending:理由）"
             elif [ "$owner" = "host" ]; then
               grep -rn --include="*.kt" "$fld = " app/ feature-chat/ feature-models/ feature-settings/ core-data/ 2>/dev/null | grep -v "AgentEvents.kt" | grep -q . \
                 || echo "AgentRequest.$fld 申报 host 但宿主源集（app/ feature-*/ core-data/）零赋值点（代码完备但未接线）"
             elif [ "$owner" = "internal" ]; then
               grep -rn --include="*.kt" "$fld = " core-agent/ 2>/dev/null | grep -v "AgentEvents.kt" | grep -q . \
                 || echo "AgentRequest.$fld 申报 internal 但 core-agent/ 内零赋值点（标记与事实不符：改判 host 或 pending:理由）"
             elif [ "${owner#pending:}" != "$owner" ]; then
               reason=${owner#pending:}
               if [ -z "$reason" ]; then
                 echo "AgentRequest.$fld 的 pending 标记缺理由串（写法：// @wire-owner: pending:理由串）"
               elif [ ! -f README.md ]; then
                 echo "AgentRequest.$fld 的 pending 理由「$reason」无处挂账：README.md 不存在（守卫面失效）"
               else
                 sed -n "/^## 挂账台账/,/^## /p" README.md | grep -qF "$reason" \
                   || echo "AgentRequest.$fld 的 pending 理由「$reason」未在 README.md 的挂账台账节出现（标记与挂账必须双向交叉）"
               fi
             else
               echo "AgentRequest.$fld 的 @wire-owner 取值「$owner」非法（只允许 host / internal / pending:理由串）"
             fi
             owner=""
           done'

# 14) lint baseline 只许缩不许涨（Wave 32）：baseline 是门禁的豁免清单 —— 它一旦
#     变成「顺手把新问题也 regen 进去」的入口，整条 lint 门禁就死了（僵尸豁免）。
#     冻结数沿革：Wave 32 首轮实测 **83** 条（3 Error + 63 Warning + 17 Hints，lint 9.3.2）
#       → **Wave 37 清障后 35 条**。清障依据 = 上一轮 CI 的 `lint-reports-<sha>` artifact
#       （唯一零 CI 成本的真实 issue 来源）：83 条里 28 条是**对已 disable 检查的失效条目**
#       （GradleDependency 19 / NewerVersionAvailable 6 / AndroidGradlePluginVersion 3，
#       lint 自己在报告里以 LintBaselineFixed 建议删除），另 16 条 AutoboxingStateCreation
#       + 4 条 RenderEffect 的冗余 @RequiresApi(S)（minSdk 31 = S ⇒ 恒真）已在同波修掉。
#       ⚠️ 教训：清障前**必须先读 lint artifact** —— 否则无从知道 baseline 是否已失配。
#     语义：条目数 > 35 即红（新增了豁免）；< 35 合法（清了存量，请顺手把这里的
#     35 改成新值）；文件缺失即红（门禁面失效）。
check "lint baseline 条目数未超冻结值（35，只许清障不许新增豁免）" \
  bash -c 'f=app/lint-baseline.xml
           if [ ! -f "$f" ]; then echo "app/lint-baseline.xml 不存在（lint 门禁面失效：abortOnError=true 会拦掉全部存量）"; exit 0; fi
           n=$(grep -cE "^[[:space:]]*<issue[[:space:]]*$" "$f")
           if [ "$n" -gt 35 ]; then echo "lint baseline 现有 $n 条，超冻结值 35（⛔ 基线只许缩不许涨 —— 新问题应该修掉，而不是 regen 进豁免清单；确属应豁免的存量需主理人改本守卫的冻结值并写明理由）"; fi'

# 15) 测试源集的 @Test 方法必须返回 void（Wave 37 实案）：JUnit 4 要求测试方法 void，
#     而 Kotlin 表达式体 `fun x() = ...` 的返回类型由**末表达式**决定 —— `kotlin.test`
#     里 assertNotNull / assertIs / assertFailsWith / assertFails 会**返回值**，放在末语句
#     会让方法非 void ⇒ **整个测试类 initializationError** ⇒ 该类**所有用例一个都不跑**，
#     而 Gradle 只报「N tests completed, 1 failed」（「只挂 1 个」是假象）。Wave 37 因此
#     白烧一轮 CI。断言「必须不存在」的守卫天然合规：命中即输出，干净时零输出。
#     扫描器按**本脚本所在目录**定位（不能用相对 cwd 的路径：自测网会在脚手架树里
#     以 cwd=脚手架 运行本守卫，相对路径会找不到脚本 ⇒ 守卫命令自身 exit 2 假红）。
#     扫描器自身异常也会打到 stdout，按「stdout 非空 = 违规」的契约判红，绝不静默通过。
GUARD_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# 扫描器缺失 = 守卫面失效 —— 必须判红，绝不能静默通过（僵尸规则的典型形态）
if [ ! -f "$GUARD_DIR/check-test-void.py" ]; then
  echo "::error::[测试源集 @Test 方法必须返回 void（末语句不得是返回值型断言）] 扫描器缺失：$GUARD_DIR/check-test-void.py（守卫面已失效）"
  fail=1
fi
check "测试源集 @Test 方法必须返回 void（末语句不得是返回值型断言）" \
  bash -c "python \"$GUARD_DIR/check-test-void.py\" || python3 \"$GUARD_DIR/check-test-void.py\""

echo "-----------------------------------------"
if [ "$fail" -ne 0 ]; then
  echo "架构守卫未通过，请修复上述问题后再合并。"
  exit 1
fi
echo "架构守卫全部通过。"
