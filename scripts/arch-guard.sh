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
#
# ⚠️ 上述「rc>=2 = 守卫自身故障」对**带管道的守卫命令失效**：管道会把 grep 的 rc=2
#    洗成 rc=1（输出空 ⇒ 判 OK），多路径时甚至洗成 rc=0。实测数据与完整分析见下方
#    「模块存在性」前置断言处的注释块 —— 那里也说明了为什么目录存在性必须显式兜。
# ⚠️ 管道守卫 rc 洗白矩阵（速查；完整实测数据与分析见下方「模块存在性」前置断言处的注释块）：
#   ① `grep -rn X a/ b/ c/ | grep -vE '...'` —— 多路径其一缺失 ⇒ rc=2（即使有命中，stdout 仍带命中行）；
#   ② `pipefail` 两层都救不了：本脚本顶部的 `set -uo pipefail` 不继承进 `bash -c` 子壳，且
#      pipefail 取「最右的那个非零退出码」而非最大值 —— ① 的 rc=2 会被右侧的 rc=1 盖成 1；
#   ③ `| grep -vE` 右侧有命中时可把 rc 洗成 0 —— 完全静默 ⇒ 新守卫一律用单根 `.` 扫描 +
#      被扫描的模块目录由「模块存在性」显式前置断言兜底（清单见下方 `for d in …` 循环，
#      必须与 settings.gradle.kts 的 include 保持同步）。
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
#
# ⚠️ 本清单**必须与 settings.gradle.kts 的 include 保持一致**（9 个，顺序也对齐）：
#    增 / 删 / 改名模块时**两处必须同步** —— 只改一处就会留下「守卫以为自己在守、
#    其实扫的是空气」的静默缺口。
#    Wave 39 前这里只列了 6 个（缺 feature-chat / feature-models / feature-settings），
#    而这 3 个恰好是第 13 条宿主源集的实际扫描面 ⇒ 它们被改名时本断言一声不吭，
#    守卫面**悄悄缩小**（不是判绿 —— 是缩面，比判绿更难被发现）。
#
# ---------- 为什么目录存在性必须靠本断言兜，而不能指望 grep 的退出码 ----------
# 本机实测退出码（Git Bash + GNU grep 3.0 / bash 5.3 实测；CI 的 Ubuntu 同为 GNU grep，
# 退出码语义一致，但**本波未在 CI 上单独复测** —— 若将来 CI 换了 busybox grep，
# 需重测 ②/④ 两条再信本节结论）：
#   ① grep -rn X nodir/                                  ⇒ rc=2
#   ② grep -rn X nodir/ | grep -vE '...'                 ⇒ rc=1   ← 关键
#   ③ grep -rn X nodir/ exist.txt（另一路径有命中）        ⇒ rc=2，且 stdout 仍有命中行
#   ④ ③ 再接 | grep -vE '...'（右侧有命中）               ⇒ rc=0   ← 完全静默
#   ⑤ 目录存在但为空：grep -rn X empty/                   ⇒ rc=1
#   含 `| grep -vE` 的守卫落在 ② / ④ 两种形态：② 输出空 ⇒ check() 判「无命中 = OK」；
#   ④ 连输出都有 ⇒ 看起来像正常在扫。两种都不触发 check() 的「rc>=2 = 守卫自身故障」。
#
#   `set -o pipefail` 也救不了，而且是**两层**都救不了（均实测）：
#     · 本脚本顶部的 `set -uo pipefail` **不继承进 `bash -c` 子壳** —— 守卫命令都是
#       `bash -c '...'` 起的，子壳里管道仍按「取最右退出码」求值（实测 rc 仍为 1）。
#     · pipefail 取的是「**最右的那个非零退出码**」而不是最大值 —— 形态 ② 里右侧的
#       `grep -vE` 自己也是 1 ⇒ 管道 rc 依旧是 1。只有当右侧**有命中 rc=0** 时
#       （形态 ④）pipefail 才会把 rc 拉回 2 —— 而 ④ 恰恰是输出非空、最像正常的那一种。
#   ⇒ 结论：**目录存在性只能靠本断言显式兜**。（可选加固：把守卫命令改成
#     `bash -c 'set -o pipefail; ...'`，能把 ④ 从「静默 rc=0」救回 rc=2；本波未做 ——
#     它会一次性改变现有 5 条带管道守卫的判据，需单独评估假红风险。）
#
# ---------- 当前暴露面盘点（Wave 39）----------
# 带 `| grep -vE` 的守卫共 5 条，扫描目标分别是：
#   第 3 条 → core-model/（在本断言清单内）
#   第 5 条 → .（仓库根，不会被改名）
#   第 6 条 → .（同上）
#   第 9 条 → .（同上）
#   第 10 条 → core-agent/（在本断言清单内）
# ⇒ **静态暴露面 ≈ 0**（两条指向模块目录的，都在本断言的覆盖内）。
# 真正的缺口在第 13 条宿主源集里的 3 个 feature-*：它们被改名时 app/ 与 core-data/
# 仍在 ⇒ 赋值点照样能被 grep 到 ⇒ 守卫是**缩面**而不是判绿（见上文 Wave 39 那条）。
# 故本波把清单补到 9 个即已堵住这条缺口。第 14 条的 lint baseline 冻结值本波不动。
for d in core-model core-engine core-agent core-data core-design \
         feature-chat feature-models feature-settings app; do
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

# 注：编号 11 空缺（历史原因，勿重排 —— 编号仅存在于注释，selftest 按守卫名匹配、
#     历史 handoff 与知识库按号引用，重排会造成大面积文档失配）。

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
#       → **Wave 38 清障后 4 条**：D1~D4 四路把 35 条里 **31 条**的**底层代码问题真修掉**
#       （UseKtx 换 `toUri`/`scale`、ObsoleteSdkInt 删恒真版本判断与 `-v26` 冗余目录、
#       AnnotateVersionCheck 补 `@ChecksSdkIntAtLeast`、NewApi 改 `removeAt(0)`/显式抑制、
#       ConfigurationScreenWidthHeight 与 ModifierParameter 就地 `@Suppress`/重排、
#       Manifest 的 DiscouragedApi/DataExtractionRules、monochrome 图标、
#       MissingPermission 补 `@SuppressLint`）。这 31 条随后会变成 lint 的 `LintBaselineFixed`
#       （lint 主动建议删除）—— 留着会让「被重新引入的问题」静默命中旧条目而**永不报出**，
#       故必须随之删除。清障依据 = 上一轮 CI 的 `lint-reports-<sha>` artifact（见上，纪律不变）。
#       ⚠️ **本波实案：`NotShrinkingResources` 试删失败，已回滚并留在豁免里** —— 教训是
#       **只读 lint 的 id 会误判**：该检查在「显式 `isShrinkResources = false`」与「缺省但
#       开了 `isMinifyEnabled`」**两种情形都会报**（消息分别是 "Avoid setting
#       isShrinkResources = false" 与 "If enabling minification, also set isShrinkResources
#       = true"）。删掉显式 false **并不会消掉 issue**，只会换一条消息；而翻 true 属 release
#       行为变更（R8 资源收缩会删掉「只被动态引用」的资源），必须真机验证后才可动。
#       ⇒ **凡清障前必须读 artifact 里的完整 message，不能只看 id。**
#       保留的 4 条均为「需真机/行为变更才能修」的诚实豁免：
#         · 2 条 `OldTargetApi`（build.gradle.kts / libs.versions.toml 的 `targetSdk = 36`）——
#           升 targetSdk 是**行为变更**，必须真机验证后才动；
#         · 1 条 `AutoboxingStateCreation`（OnboardingScreen.kt）——`rememberSaveable` 与
#           `mutableIntStateOf` 的 saver 语义**不可离线验证**，盲改有状态恢复回归风险；
#         · 1 条 `NotShrinkingResources`（build.gradle.kts）—— 见上，翻 true 是 release 行为变更。
#     语义：条目数 > 4 即红（新增了豁免）；< 4 合法（清了存量，请顺手把这里的
#     4 改成新值）；文件缺失即红（门禁面失效）。
check "lint baseline 条目数未超冻结值（4，只许清障不许新增豁免）" \
  bash -c 'f=app/lint-baseline.xml
           if [ ! -f "$f" ]; then echo "app/lint-baseline.xml 不存在（lint 门禁面失效：abortOnError=true 会拦掉全部存量）"; exit 0; fi
           n=$(grep -cE "^[[:space:]]*<issue[[:space:]]*$" "$f")
           if [ "$n" -gt 4 ]; then echo "lint baseline 现有 $n 条，超冻结值 4（⛔ 基线只许缩不许涨 —— 新问题应该修掉，而不是 regen 进豁免清单；确属应豁免的存量需主理人改本守卫的冻结值并写明理由）"; fi'

# 15) 测试源集：@Test 方法必须返回 void，且反引号名不得含 JVM 非法字符。
#     ① void（Wave 37 实案）：JUnit 4 要求测试方法 void，而 Kotlin 表达式体
#        `fun x() = ...` 的返回类型由**末表达式**决定 —— `kotlin.test` 里
#        assertNotNull / assertIs / assertFailsWith / assertFails 会**返回值**，放在末语句
#        会让方法非 void ⇒ **整个测试类 initializationError** ⇒ 该类**所有用例一个都不跑**，
#        而 Gradle 只报「N tests completed, 1 failed」（「只挂 1 个」是假象）。白烧一轮 CI。
#     ② JVM 非法字符（Wave 38 实案）：JVM 规范 §4.2.2 禁止方法名含 `. ; [ /`（`<` `>` 亦不可）。
#        反引号里写「A / B」这类中文名会让 Kotlin 直接报
#        `e: ... Name contains illegal characters: /` ⇒ compileDebugUnitTestKotlin 失败
#        ⇒ **整个 Build job 红**。本机无 JDK **完全查不出**（CI 是唯一通道）。
#        修法：中文名里的「A / B」改成「A 与 B」「A、B」。
#     断言「必须不存在」的守卫天然合规：命中即输出，干净时零输出。
#     扫描器按**本脚本所在目录**定位（不能用相对 cwd 的路径：自测网会在脚手架树里
#     以 cwd=脚手架 运行本守卫，相对路径会找不到脚本 ⇒ 守卫命令自身 exit 2 假红）。
#     扫描器自身异常也会打到 stdout，按「stdout 非空 = 违规」的契约判红，绝不静默通过。
GUARD_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# 扫描器缺失 = 守卫面失效 —— 必须判红，绝不能静默通过（僵尸规则的典型形态）
if [ ! -f "$GUARD_DIR/check-test-void.py" ]; then
  echo "::error::[测试源集 @Test 方法必须返回 void 且反引号名不含 JVM 非法字符（. ; [ / < >）] 扫描器缺失：$GUARD_DIR/check-test-void.py（守卫面已失效）"
  fail=1
fi
check "测试源集 @Test 方法必须返回 void 且反引号名不含 JVM 非法字符（. ; [ / < >）" \
  bash -c "python \"$GUARD_DIR/check-test-void.py\" || python3 \"$GUARD_DIR/check-test-void.py\""

# 16) AgentLogStore.setSink 调用点数守卫（Wave 40 / 外部复评报告 v10 I4）：setSink 是
#     **单槽覆盖式** API（core-model/AgentLogStore.kt）—— 它内部只持有一个 sink 引用，
#     第二次 setSink 会**静默顶掉**第一个，而唯一的 sink 挂着 ERROR 落盘
#     （core-data/AgentLogFileStore.kt 的 KDoc 自认这是回退级事故）。全仓唯一合法
#     调用点 = AgentLogFileStore.install() 内那 1 处。
#     ⚠️ 实测勘误（裁决稿判据 `.setSink(` 在本仓 0 命中）：唯一真调用点用的是
#     Kotlin 尾随 lambda 语法 `AgentLogStore.setSink { … }`，带括号形态一个都匹配
#     不到 ⇒ 判据改为 `\.setSink[[:space:]]*[( {]`，同时覆盖 `setSink(` 与 `setSink {`
#     两种调用形态。定义处 `fun setSink(` 无点前缀不命中；KDoc 提及（`AgentLogStore.setSink`
#     后跟反引号 / `]`）也不命中，实测恰命中 1 处真调用。注释行仍走 EXCLUDE_COMMENT
#     统一过滤（防将来有人在注释里贴调用示例造成假红）。
#     扫描面是单根 `.`（见顶部「管道守卫 rc 洗白矩阵」③：避开多路径 rc 洗白）；
#     n≤1 合法（0 = 尚未接线也放行），n≥2 即红。
check "AgentLogStore.setSink( 全仓只允许 1 处调用点（AgentLogFileStore.install，单槽覆盖式 API）" \
  bash -c 'n=$(grep -rnE "\.setSink[[:space:]]*[( {]" --include="*.kt" '"${EXCL[*]}"' . | grep -vE "'"$EXCLUDE_COMMENT"'" | wc -l)
           [ "$n" -le 1 ] || echo "发现 $n 处 .setSink( 调用：第二个 sink 会静默顶掉第一个（ERROR 落盘丢失），见 AgentLogFileStore.install KDoc"'

# 17) ViewModel 直构造点全仓 ≤ 1（Wave 42）：正规路径必须走 core-data 的
#     viewModelFactory（ViewModelStore 接管 clear / viewModelScope.cancel 生命周期）。
#     全仓唯一合法白名单 = LiquidAgentApp.kt:378 的 SandboxFilesViewModel(container)
#     （工作区覆盖层「首次打开才创建 + 旋转即关」的刻意取舍，见该处注释与挂账台账）。
#     第二处直构造一旦出现，等于悄悄复制了「绕过 ViewModelStore」的生命周期债务
#     （DisposableEffect 手工补偿清理），故 n≥2 即红。
#     判据三层过滤（按本仓实际形态实测调通，Wave 42）：
#       ① class 声明行排除 —— `class XxxViewModel(` 的定义行全命中构造模式，
#          不过滤则恒红；
#       ② 单行 viewModelFactory 排除 —— SettingsRoute.kt:82
#          `viewModel(factory = viewModelFactory { StorageViewModel(container) })`
#          等正规路，factory 块与构造同行（实测全仓 8 处 factory 构造全部单行）；
#       ③ EXCLUDE_COMMENT 过滤 KDoc / 注释里的提及。
#     ⚠️ 已知局限（如实记录）：② 的过滤是**行级**的 —— 若将来格式化把
#        viewModelFactory 块拆成多行（构造落在不含该关键字的行上），那行会缺关键字
#        被**误红**。届时把 factory 块改回单行，或把判据升级为跨行状态机。
#     扫描面是单根 `.`（见顶部「管道守卫 rc 洗白矩阵」③：避开多路径 rc 洗白）。
check "ViewModel 直构造点全仓 ≤ 1（LiquidAgentApp.kt:378 白名单，正规路径须走 viewModelFactory）" \
  bash -c 'n=$(grep -rnE "[A-Za-z][A-Za-z0-9]*ViewModel\(" --include="*.kt" '"${EXCL[*]}"' . | grep -vE "class [A-Za-z0-9]*ViewModel\(" | grep -vF "viewModelFactory" | grep -vE "'"$EXCLUDE_COMMENT"'" | wc -l)
           [ "$n" -le 1 ] || echo "发现 $n 处 ViewModel 直构造点：正规路径必须走 viewModelFactory（唯一白名单 = LiquidAgentApp.kt:378，第二处直构造 = 复制绕过 ViewModelStore 的生命周期债务）"'

# 18) ChatViewModel.kt 总行数上限（Wave 42）：Wave 41 落地后实测 1682 行，本波
#     三处 AgentPolicy 构造收敛（P2-4）+ 纯函数组迁出（J3）后 **1510 行**，≤ 1550
#     故冻结值取 1600（口径：向上取整到 50）。行数是「ViewModel 职责堆积」的代理
#     指标 —— 它涨回 1600 意味着该类又在吞本该外提的职责。
#     ⚠️ **触顶不是改数字，是启动「onSend / onSendFrom 合并」候选评审**：两函数的
#     journal 回灌 + engineHistory 组装已高度趋同（见两处 Wave 41 P2-1 注释），
#     评审通过后合并/拆分，再按实际行数下调本值（与第 14 条 lint baseline 同款
#     「只许缩不许涨」精神，但触顶动作是评审而非 regen）。
#
#     Wave 48 加固（F1）：文件缺失分支原为 `echo "$f 不存在…"; exit 0`。**实测勘误**：
#     check() 的契约是「stdout 非空 = 违规」，该 echo 已经让本分支判红（并非静默绿，
#     「exit 0 ⇒ 假绿」的说法在现行 check() 下不成立）。但 `exit 0` 在语义上声明
#     「成功」，与「守卫面失效必须判红」的铁律相悖，且一旦后人删掉/改写那句 echo
#     （例如误把说明挪到 stderr）就会静默放行。故改为 `exit 1`，并给违规事实加上
#     `::error::` 前缀（GitHub Actions 注解，违规原因本身仍完整可见）；由
#     arch-guard-selftest.sh 的 case17 钉住「目标文件缺失 ⇒ 判红且违规事实可见」。
check "ChatViewModel.kt 总行数 ≤ 1600（触顶 = 启动 onSend/onSendFrom 合并候选评审，非改阈值）" \
  bash -c 'f=feature-chat/src/main/java/com/rickeal/agent/feature/chat/ChatViewModel.kt
           if [ ! -f "$f" ]; then echo "::error::$f 不存在（被改名/删除？守卫面已失效）"; exit 1; fi
           n=$(wc -l < "$f")
           [ "$n" -le 1600 ] || echo "ChatViewModel.kt 当前 $n 行，超 1600 行上限（触顶不是改数字，是启动「onSend/onSendFrom 合并」候选评审）"'

# 19) --continue 纪律的机械载体（Wave 49 R-B）：全量 JVM 单测必须固定带
#     `--continue` —— Gradle 默认 fail-fast，第一个失败的模块会**中止后续模块**，
#     gradle 只报「N tests completed」（**残缺口径**：没跑到的模块一个用例都不计入）。
#     W48 实证：core-data 失败掩盖了 feature-chat 的长期 flaky —— 门禁看起来
#     「只挂 1 个」，实际有模块根本没被跑到。该教训此前只落在 handoff 文档，而承载
#     它的 `_ci-tools/localbuild.sh` 在**仓库外**（git 外）⇒ 下个接手者 / 换会话直接
#     `gradle test`，残缺口径就会回来。故本波把纪律固化进仓库（`scripts/fulltest.sh`），
#     并用本条守卫钉住「该文件存在且含 `--continue`」—— 防止纪律文件自己蒸发
#     （本仓已有多次「描述不成立」的前科）。
#     判红契约：`check()` 是「stdout 非空 = 违规」。「文件不存在」分支用
#     `exit 1` + `::error::`（显式 echo 违规事实，绝不写成 fail-open 的空输出）。
#     ⚠️ 判据用 `^[^#]*--continue`（**行内第一个 `#` 之前**出现该开关），而不是裸
#     `grep -F --continue`：后者会被**注释里的提及**骗过（本波实测 —— case19 的
#     fixture 注释写了「没有 --continue」，裸 grep 命中注释 ⇒ 假绿）。真实文件里
#     `--continue` 既出现在 KDoc/注释、也出现在命令行的 `"$GRADLE_CMD" … --continue`，
#     故必须只认「代码位」的那一处。
check "fulltest.sh 存在且固定带 --continue（全量单测禁用 fail-fast 残缺口径）" \
  bash -c 'f=scripts/fulltest.sh
           if [ ! -f "$f" ]; then echo "::error::$f 不存在（--continue 纪律的机械载体蒸发 ⇒ 下个接手者会退回 gradle test 的残缺口径：fail-fast 只报「N tests completed」，漏跑模块无从察觉）"; exit 1; fi
           grep -qE "^[^#]*--continue" "$f" || echo "$f 未包含 --continue（全量单测会被 fail-fast 截断，gradle 的「N tests completed」变残缺口径 —— W48 实证 core-data 失败掩盖了 feature-chat 的长期 flaky）"'

# 20) ChatRunCoordinator.kt 总行数上限（Wave 49 R-C）：与第 18 条共同构成
#     **feature-chat 两文件行数表** —— #18 钉 ChatViewModel（≤1600），本条钉
#     ChatRunCoordinator（≤1300）。W48 把 run 编排从 ChatViewModel 外提到
#     ChatRunCoordinator 后它已 1152 行，且后续的复合熔断 rescue / journal 补 thinking /
#     MaxRounds 兜底都落在它身上 —— 若不加守卫，一年后 1593 行会在这里长出来。
#     ⚠️ **不改阈值、先减后增**：触顶动作 = 走 `_plans/wave48-design.md` 已列的
#     「ChatRunCoordinator 第二拆分候选」评审（把纯函数 / 状态机再外提），**不是改数字**
#     （与第 18 条、第 14 条 lint baseline 同款「只许缩不许涨」精神）。
#     文件缺失分支同 #18：`exit 1` + `::error::`（守卫面失效必须判红，绝不静默放行）。
check "ChatRunCoordinator.kt 总行数 ≤ 1300（feature-chat 两文件行数表，触顶 = 走 wave48-design 第二拆分候选，非改阈值）" \
  bash -c 'f=feature-chat/src/main/java/com/rickeal/agent/feature/chat/ChatRunCoordinator.kt
           if [ ! -f "$f" ]; then echo "::error::$f 不存在（被改名/删除？守卫面已失效）"; exit 1; fi
           n=$(wc -l < "$f")
           [ "$n" -le 1300 ] || echo "ChatRunCoordinator.kt 当前 $n 行，超 1300 行上限（触顶不是改数字，是走 wave48-design 的第二拆分候选评审）"'

# 21) CapabilitySource.USER 章印唯一写入路径（Wave 49 R-D）：`ModelRepository.setCapabilities`
#     是全仓**唯一**盖 `CapabilitySource.USER` 章的持久入口 —— 它把「用户显式编辑 ⇒
#     此后启发式（applyTo / probe）不再改写能力位」这一语义固定下来（见该方法 KDoc 与
#     `ModelHeuristics.resolveCapabilities`）。若新增第二条盖 USER 章的写入路径（例如批量
#     导入 / 云端同步 / 调试后门），该语义会被绕过且**难以察觉**，故用守卫冻结。
#     判据用**赋值形态** `capabilitiesSource = CapabilitySource.USER`（即「盖章」动作本身），
#     而不是裸 `CapabilitySource.USER` —— 后者会误伤生产源码里的**合法读取/比较**：
#       · `ModelHeuristics.kt` 的 `if (source == CapabilitySource.USER)`（读，不是盖章）
#       · `ModelDescriptor.kt` 的 KDoc `[CapabilitySource.USER]`
#     ⚠️ 测试源集（`src/test/`）必须排除 —— `ModelHeuristicsTest.kt` 会用它构造用例，
#     不该判红（本波实测：`ModelHeuristicsTest.kt:42/45/95` 均含 `CapabilitySource.USER`）。
#     语义：生产源码里该赋值必须**恰好出现 1 次**，且文件必须是 ModelRepository.kt；
#     出现面为零（章印点消失）或落到别的文件（新增盖章点）都判红。
check "CapabilitySource.USER 章印唯一写入路径（仅 ModelRepository.setCapabilities，排除测试源集）" \
  bash -c 'out=$(grep -rnE "capabilitiesSource[[:space:]]*=[[:space:]]*CapabilitySource\.USER" --include="*.kt" '"${EXCL[*]}"' . | grep -v "/src/test/" | grep -vE "'"$EXCLUDE_COMMENT"'")
           if [ -z "$out" ]; then echo "::error::生产源码里找不到 capabilitiesSource = CapabilitySource.USER 章印点（ModelRepository.setCapabilities 的唯一写入路径消失，守卫面已失效）"; exit 1; fi
           nonrepo=$(printf "%s\n" "$out" | cut -d: -f1 | grep -v "ModelRepository\.kt$" || true)
           if [ -n "$nonrepo" ]; then echo "CapabilitySource.USER 章印点出现在 ModelRepository.kt 之外（应唯一收敛到 setCapabilities）："; printf "%s\n" "$nonrepo"; fi
           n=$(printf "%s\n" "$out" | wc -l)
           if [ "$n" -ne 1 ]; then echo "CapabilitySource.USER 章印点共 $n 处（应恰好 1 处 —— 新增能力写入路径必须走 ModelRepository.setCapabilities，见其 KDoc）："; printf "%s\n" "$out"; fi'

# 22) fulltest.sh 的 --summary-only 子命令必须存在（Wave 50）：build.yml 的 unit-tests job
#     新增了一步「非阻断汇总用例数」步骤，它**依赖** `scripts/fulltest.sh --summary-only`
#     存在（只汇总现有 TEST-*.xml、不重跑、永远 exit 0）。该步骤是
#     `continue-on-error: true` + `if: always()` + `run` 内 `|| true`（刻意非阻断），
#     ⇒ 若后人删掉该子命令，summary 步骤会**静默失效**（不报错、只是 summary 空了）
#     —— 正是本仓「守卫网假绿陷阱」的形态：非阻断步骤的依赖被删，没有任何东西会响。
#     故用守卫钉住该子命令的**分派臂**仍在。
#     判据 `^[^#]*--summary-only[[:space:]]*\)`：只认**代码位**的 case 分派臂
#     `--summary-only)`，行内第一个 `#` 之后的注释提及不算 —— 沿第 19 条（R-B）的教训：
#     裸 grep 会被 fixture 注释里的字样骗成假绿。
#     判红契约：`check()` 是「stdout 非空 = 违规」。「文件不存在」分支用
#     `exit 1` + `::error::`（显式 echo 违规事实，绝不写成 fail-open 的空输出）。
check "fulltest.sh 含 --summary-only 子命令（build.yml 非阻断汇总步骤的依赖，缺失即静默失效）" \
  bash -c 'f=scripts/fulltest.sh
           if [ ! -f "$f" ]; then echo "::error::$f 不存在（--summary-only 子命令的载体蒸发 ⇒ build.yml 的汇总步骤会静默失效）"; exit 1; fi
           grep -qE "^[^#]*--summary-only[[:space:]]*\)" "$f" || echo "$f 未包含 --summary-only 子命令分派臂（build.yml 的 unit-tests summary 步骤依赖它；缺失 ⇒ 该非阻断步骤静默失效，summary 永远为空）"'

# 23) fulltest.sh 的 --summary-only 退出码契约（Wave 51）：与第 22 条**互补** ——
#     #22 钉「分派臂存在」，本条钉「该分支的退出码恒为 0」。契约（build.yml 的
#     unit-tests summary 步骤依赖它）：
#       · `--summary-only` 分支必须**恒 `exit 0`** —— 它是 `continue-on-error: true` 的
#         非阻断步骤，一旦变成非零退出，「永远 exit 0」的口径即被破坏（虽然不红 job）；
#       · 且**不得**因「找不到 gradle」走 `exit 2` 路径 —— 即 gradle 解析块（含 `exit 2`）
#         必须被 `SUMMARY_ONLY == 0` 分支包住。`0f6506e` 修掉的「误印 gradle 退出码」若回归，
#         summary-only 在无 gradle 环境会以 exit 2 结束（而非恒 exit 0）。
#     ⚠️ 本条防的是「脚本自身退出码」，**防不住 `files==0` 的静默场景**（脚本仍 exit 0、
#     但汇总为空）—— 后者由 fulltest.sh 内的 `::warning::` 注解 + build.yml 的「低于基线」
#     soft-check 负责。三者互补、不重复。
#     实现：用 awk 追踪 if/elif/fi 嵌套（沿用第 12 条的结构化判据范式），在**代码位**上校验：
#       ① 存在一个 `SUMMARY_ONLY == 1` 的 if 分支，其**首语句**为 `exit 0`（恒 exit 0 的早退载体）；
#       ② 每个 `exit 2` 都处在「含 SUMMARY_ONLY 且判 0」的 if 块内。
#     判据只认代码位（行首 `#` 注释行由 awk 跳过），不会被注释里的 `--summary-only` /
#     `exit 0` 字样骗过（沿第 19/22 条 R-B 的教训 —— 裸 grep 会被 fixture 注释骗成假绿）。
#     判红契约同 #19/#22：文件不存在用 `exit 1` + `::error::`（显式 echo 违规事实，绝不
#     写成 fail-open 的空输出）。
check "fulltest.sh 的 --summary-only 分支恒 exit 0 且不经 exit 2（非阻断汇总步骤的退出码契约）" \
  bash -c 'f=scripts/fulltest.sh
           if [ ! -f "$f" ]; then echo "::error::$f 不存在（--summary-only 退出码契约的载体蒸发 ⇒ build.yml 非阻断汇总步骤的退出码不再受保护）"; exit 1; fi
           awk "
             BEGIN { d=0; pending=0; sum1ok=0; bad=0 }
             /^[[:space:]]*(#|\$)/ { next }
             /^[[:space:]]*if([[:space:]]|\$)/ { d++; cond[d]=\$0; pending=(\$0 ~ /SUMMARY_ONLY/ && \$0 ~ /(-eq|==)[[:space:]]*1/)?1:0; next }
             /^[[:space:]]*elif([[:space:]]|\$)/ { cond[d]=\$0; pending=(\$0 ~ /SUMMARY_ONLY/ && \$0 ~ /(-eq|==)[[:space:]]*1/)?1:0; next }
             /^[[:space:]]*fi([[:space:]]*;|[[:space:]]*\$)/ { pending=0; if(d>0)d--; next }
             { if(pending){ if(\$0 ~ /^[[:space:]]*exit[[:space:]]+0([[:space:]]*;|[[:space:]]*\$)/) sum1ok=1; pending=0 }
               if(\$0 ~ /^[[:space:]]*exit[[:space:]]+2([[:space:]]*;|[[:space:]]*\$)/){ enc=0; for(i=1;i<=d;i++) if(cond[i] ~ /SUMMARY_ONLY/ && cond[i] ~ /(-eq|==)[[:space:]]*0/) enc=1; if(!enc){ print FILENAME \":\" NR \": exit 2 未被 SUMMARY_ONLY==0 分支包住（--summary-only 在无 gradle 环境会走 exit 2 而非恒 exit 0）\"; bad=1 } } }
             END { if(!sum1ok){ print FILENAME \": --summary-only 恒 exit 0 的早退载体缺失（SUMMARY_ONLY==1 的 if 分支首语句必须是 exit 0；删掉它 summary-only 会落到默认模式的失败退出码）\"; bad=1 } exit bad }
           " "$f"'

# 24) LiteRtLmEngine.kt 「fold 收口不变式」（Wave 52）：P1（Qwen2.5 容器模板渲染失败）
#     的修法是让下发 content **恰好剩 1 个元素** —— 在 4 处 `Message.user(...)` 下发点
#     **全部**包一层 `foldAdjacentText(...)`（折叠相邻连续 `Content.Text`；非 Text 原样
#     透传）。这是一条**靠枚举维护的不变式**：将来任何新增 `Message.user(...)` 下发点若
#     忘包 fold，就会**静默重新打开 P1**（触发条件 = 单轮内出现 ≥2 相邻 Text，开发期
#     单轮根本测不出）。故用守卫把「下发点数 == fold 调用数」冻结下来。
#     ⚠️ 判据必须排除「非调用位」：naive `grep -c` 得 5==5 是**巧合**（fold 多 1 个 def 行
#     `:456`；Message.user 多 1 个 KDoc 行 `:499`）。排除 def 行（`fun foldAdjacentText`）
#     与注释/KDoc 位（行首 `*` 或 `//`）后得 4==4（Wave 52 本会话实测）。
#     ⚠️ 多行调用：`:2087`(Message.user) 与 `:2088`(fold) 跨行 ⇒ 必须用**计数法**，
#     不能用「同行配对」判据（后者会漏掉这一对，从而漏报）。
#     语义：fold_n == msg_n。新增无 fold 的 Message.user ⇒ msg_n+1 ⇒ 判红（P1 静默重开）；
#     新增 fold 调用（无对应 user）⇒ fold_n+1 ⇒ 判红（fail-closed 假阳性，可接受）。
#     计数**动态**（读文件行数），不硬编码 4（否则队友新增/调整 fold 点会误红）。
#     文件缺失分支用 `exit 1` + `::error::`（守卫面失效必须判红，绝不静默放行）。
#     ⚠️ **扫描面边界（P2-6，Wave 52 审查）**：本守卫的扫描面 = `core-engine/src/main/
#     java/com/rickeal/agent/core/engine/local/LiteRtLmEngine.kt` **单文件**（当前全仓
#     `Message.user(` 仅此文件出现 ⇒ 判据成立）。**本守卫不覆盖跨文件形态** —— 若 W53+
#     在别的文件（例如新引擎 / 新模块）新增 `Message.user(...)`，本守卫**静默漏报**。
#     届时必须**扩面或改为跨文件扫描**（把 E 换成全仓 `grep -rn` 并按文件分组配对），
#     不能只靠本条。这里显式声明边界，避免「通用不变式」的错觉。
#     ⚠️ **已知局限（P3-1 备忘，Wave 52 审查）**：行尾注释形如 `foo() // Message.user(`
#     不被 `^[0-9]+:[[:space:]]*[*/]` 排除（该正则只认**行首** `*`/`//`）⇒ 会计入 msg_n
#     ⇒ **fail-closed 假阳性**（判红偏严，绝不漏报）。属本守卫设计已认可的取舍，不修。
check "LiteRtLmEngine.kt fold 收口不变式（Message.user 下发点数 == foldAdjacentText 调用数，新增下发点必须包 fold）" \
  bash -c 'E=core-engine/src/main/java/com/rickeal/agent/core/engine/local/LiteRtLmEngine.kt
           if [ ! -f "$E" ]; then echo "::error::$E 不存在（被改名/删除？第 24 条守卫面已失效）"; exit 1; fi
           fold_n=$(grep -nE "foldAdjacentText\(" "$E" | grep -vE "^[0-9]+:[[:space:]]*[*/]" | grep -v "fun foldAdjacentText" | wc -l)
           msg_n=$(grep -nE "Message\.user\(" "$E" | grep -vE "^[0-9]+:[[:space:]]*[*/]" | wc -l)
           [ "$fold_n" -eq "$msg_n" ] || echo "$E 的 foldAdjacentText 调用数($fold_n) != Message.user 下发点数($msg_n)：新增 Message.user 下发点必须包 foldAdjacentText 收口（否则单轮内 ≥2 相邻 Text 会静默重开 P1 模板渲染失败）"'

# 25) LiteRtLmEngine.kt 总行数上限（Wave 52）：与 #18（ChatViewModel ≤1600）/
#     #20（ChatRunCoordinator ≤1300）共同构成「god-file 行数表」。LiteRtLmEngine.kt
#     是已知 god-file，此前**无文件级守卫** ⇒ 加本条。
#     阈值取 2400（**冻结值**，≈+2.5% 余量、≈60 行）：① W52 的 KDoc 订正净增 ≤15 行 ⇒
#     2400 不阻塞本波；② 余量 ≤60 行 ⇒ 再涨即触顶，强制启动 god-file 拆分评审。
#     ⚠️ **实测行数以本守卫违规输出 `当前 $n 行` 为准，注释不写死**（教训：注释里写死
#     「实测 N 行 / 余量 M」会随同 commit 改码当场过期 = 活 stale）。
#     ⚠️ **触顶不是改数字，是启动拆分评审**（与 #18/#20、#14 lint baseline 同款
#     「只许缩不许涨」精神）。
#     文件缺失分支用 `exit 1` + `::error::`（守卫面失效必须判红，绝不静默放行）。
check "LiteRtLmEngine.kt 总行数 ≤ 2400（core-engine god-file 行数表，触顶 = 启动拆分评审，非改阈值）" \
  bash -c 'f=core-engine/src/main/java/com/rickeal/agent/core/engine/local/LiteRtLmEngine.kt
           if [ ! -f "$f" ]; then echo "::error::$f 不存在（被改名/删除？守卫面已失效）"; exit 1; fi
           n=$(wc -l < "$f")
           [ "$n" -le 2400 ] || echo "LiteRtLmEngine.kt 当前 $n 行，超 2400 行上限（触顶不是改数字，是启动 god-file 拆分评审）"'

# 26) 测试基线双向同步（Wave 53）：`build.yml` 声明的用例数基线必须与全仓 `@Test`
#     代码位机械实数**双向一致**。任一方向漂移都会让 `build.yml` 的「低于基线
#     soft-check」（unit-tests job 的非阻断汇总步骤）失真：
#       · 实数 > 基线 = 有人加测试忘同步（**滞后**）⇒ soft-check 阈值偏低、永不告警；
#       · 实数 < 基线 = 有人删测试未同步（**删例**）⇒ soft-check 天天误报、告警钝化。
#     W52 已发生一次（`build.yml` 基线 619 滞后于实测 621 —— 复审15「三连犯第三击」）。
#
#     ⚠️ **口径声明（必须显式）**：本守卫的**规范口径 = 全仓 `^[[:space:]]*@Test`
#     代码位计数**（`grep -rnE "^[[:space:]]*@Test" --include=*.kt` 再排除**行首**
#     注释 / KDoc 行，命令见下）。而 `build.yml` 的 `baseline=N` 在 CI 里是 **Gradle
#     汇总的 XML `tests=` 计数**（`fulltest.sh --summary-only` 累加 `TEST-*.xml`）。
#     两口径**当前巧合相等**（Wave 53 实测均 = 623）。一旦分叉即会误报，最典型：
#       · 引入 `@Ignore` / `@Disabled` —— 代码位仍在、XML 不计（实数 > XML）；
#       · 参数化 / 动态测试 —— XML 计 N、代码位只 1（XML > 实数）。
#     ⇒ 届时须把 build.yml 的 soft-check 也改为同口径（数代码位），或在本守卫内
#     扣除 `@Ignore` 用例 —— **不要默默把 baseline 调成 XML 数**（那会让本守卫恒红）。
#
#     ⚠️ **扫描面边界声明**：扫描面 = **单根 `.` 全仓** `--include="*.kt"`
#     （排除 .git/build/.gradle/.kotlin，见顶部 EXCL）。**只数 .kt 里的 `@Test`
#     代码位** —— 不覆盖：非 .kt 载体（如 Java 测试）、被 `@Ignore` 注解的用例
#     （代码位仍计入）、以及 CI XML 口径与代码位的天然差（见上）。新增模块只要落
#     `.kt` 即被覆盖，无需改本守卫；「口径分叉」需人工介入（见上）。
#
#     判红契约（`check()` = stdout 非空即违规）：断言「**相等**」的守卫必须**自己
#     echo** 违规行（见脚本顶部纪律「必须存在的断言必须自己 echo」）。相等时零输出 ⇒ OK。
#     build.yml 缺失 / 提不出 baseline ⇒ `exit 1` + `::error::`（守卫面失效必须判红）。
check "测试基线双向同步（build.yml baseline == 全仓 @Test 代码位机械实数）" \
  bash -c 'B=.github/workflows/build.yml
           if [ ! -f "$B" ]; then echo "::error::$B 不存在（测试基线自动同步守卫面失效）"; exit 1; fi
           decl=$(grep -oE "baseline=[0-9]+" "$B" | head -n 1 | cut -d= -f2)
           if [ -z "$decl" ]; then echo "::error::$B 未声明 baseline=N（测试基线自动同步守卫面失效）"; exit 1; fi
           real=$(grep -rnE "^[[:space:]]*@Test" --include="*.kt" '"${EXCL[*]}"' . | grep -vE "^[^:]+:[0-9]+:[[:space:]]*[/*]" | wc -l)
           if [ "$real" -gt "$decl" ]; then echo "全仓 @Test 代码位实数($real) > build.yml baseline($decl)：新增测试未同步基线（滞后 $((real - decl)) 例）⇒ soft-check 阈值偏低永不告警。请把 $B 的 baseline 改为 $real"; fi
           if [ "$real" -lt "$decl" ]; then echo "全仓 @Test 代码位实数($real) < build.yml baseline($decl)：删例未同步基线（少 $((decl - real)) 例）⇒ soft-check 天天误报。若确为删例，请把 $B 的 baseline 改为 $real"; fi'

# 27) A5 台账聚合句守卫（Wave 53）：README 与 `docs/10-device-acceptance.md` 的
#     「真机验收台账」聚合句，必须与 §11.0.1 逐条台账的**结论列三态实数**一致。
#     动机：W52 已发生一次「聚合句与逐条台账各说各话」（聚合句写「31 条 / 27 ✅ / 4」，
#     逐条实数却是「35 = 28 ✅ / 5 ⚠️ / 2 ⛔」，**三项全错**）。纯文档改动不触发 CI
#     （`paths-ignore` 忽略 `*.md`）⇒ 这类漂移**只能靠守卫在 push 代码时顺带拦下**
#     （本守卫在 assemble-debug job 内，push 非 docs-only 即跑）。
#
#     ⚠️ **口径声明**：三态只认**结论列**（`awk -F'|'` 的第 4 字段），**不数整行** ——
#     证据列里合法地含 ✅/⛔（例：第 23 行证据列同现「✅ 已覆盖…⛔ 未覆盖」）⇒
#     整行计数会**误计**。归类：结论列含 ✅ = 回收 / ⚠ = 部分 / ⛔ = 不适用
#     （含无法验证 / vacuous）。**非精确表述（「≈N 条」等）无法解析 ⇒ 请先统一为精确数字**。
#
#     ⚠️ **扫描面边界声明**：台账范围 = `### 11.0.1` 节内 `| <数字> | … |` 形态的行；
#     聚合句范围 = 各文件含锚点短语的行（README「真机验收台账」/ docs「起累计的验收项」）。
#     **不覆盖**：§11 其它小节里的同类计数句、以及 W50 段等**子区间聚合**（如
#     README:324 的 W50 段计数 —— 该处按逐条台账手工订正，不由本守卫机械核验）；
#     `README.md` 的「台账聚合计数订正」行含**双四元组**（历史订正陈述），不在本守卫扫描面
#     （锚点短语形态差异）——已申报；**不要**给它加第二个锚点（历史订正行将来会合理地与
#     现状不符 ⇒ 扩扫描面会引入假红）。
#     锚点行缺失 / §11.0.1 解析不出任何行 ⇒ 判红（守卫面失效）。
#
#     判红契约：断言「相等」的守卫必须**自己 echo** 违规行（见脚本顶部纪律）。
check "A5 台账聚合句与 §11.0.1 逐条三态一致（README / docs 聚合句 == 台账实数）" \
  bash -c 'D=docs/10-device-acceptance.md; R=README.md
           for f in "$D" "$R"; do [ -f "$f" ] || { echo "::error::$f 不存在（A5 台账聚合守卫面失效）"; exit 1; }; done
           read -r n ok warn no < <(awk -F"|" "
             /^### 11\.0\.1/ { on=1; next }
             on && /^### / { on=0 }
             on && \$2 ~ /^[[:space:]]*[0-9]+[[:space:]]*\$/ {
               n++; c=\$4
               if (c ~ /✅/) ok++
               if (c ~ /⚠/) warn++
               if (c ~ /⛔/) no++
             }
             END { print n+0, ok+0, warn+0, no+0 }" "$D")
           if [ "${n:-0}" -eq 0 ]; then echo "::error::$D §11.0.1 未解析到台账行（格式漂移 / 改成了非精确表述？A5 台账聚合守卫面失效）"; exit 1; fi
           bad=0
           for spec in "$R|真机验收台账" "$D|起累计的验收项"; do
             f=${spec%%|*}; anc=${spec#*|}
             line=$(grep -F "$anc" "$f" | grep -F "✅" | grep -F "⚠" | grep -F "⛔" | head -n 1)
             if [ -z "$line" ]; then echo "::error::$f 未找到含「$anc」的三态聚合句（守卫面失效）"; bad=1; continue; fi
             ft=$(printf "%s" "$line" | grep -oE "累计[^0-9]*[0-9]+" | grep -oE "[0-9]+")
             fok=$(printf "%s" "$line" | grep -oE "[0-9]+[[:space:]]*条[^0-9]*✅" | grep -oE "^[0-9]+")
             fwarn=$(printf "%s" "$line" | grep -oE "[0-9]+[[:space:]]*条[^0-9]*⚠" | grep -oE "^[0-9]+")
             fno=$(printf "%s" "$line" | grep -oE "[0-9]+[[:space:]]*条[^0-9]*⛔" | grep -oE "^[0-9]+")
             if [ "${ft:-x}" != "$n" ] || [ "${fok:-x}" != "$ok" ] || [ "${fwarn:-x}" != "$warn" ] || [ "${fno:-x}" != "$no" ]; then
               echo "$f 聚合句与 §11.0.1 逐条台账不一致："
               echo "  台账实数：总 $n / ✅ $ok / ⚠️ $warn / ⛔ $no"
               echo "  $f 聚合句：总 ${ft:-?} / ✅ ${fok:-?} / ⚠️ ${fwarn:-?} / ⛔ ${fno:-?}"
               bad=1
             fi
           done
           [ "$bad" -eq 0 ] || echo "修法：把聚合句改成与台账实数一致（改一个数字必须全仓 grep 该数字的所有形态再改）"'

# 28) LiteRtLmEngine.kt 「进程级韧性 store 双写双读接线」（Wave 57 建 / W58 改判据②）：
#     读回点（adoptResilienceFromStore，ensureConversation 首行）与写点
#     （persistResilienceToStore：① ensureConversation 成功路径 cid 赋值后 +
#     ② handleTemplateRenderFailure 收尾，W58 起两个写点）是**承重接线**：
#     读回点若被删 ⇒ P2#1（「自愈置位随 evict 清零」的证伪）静默复发；写点若被删 ⇒
#     跨实例计数永不落 store（写点①缺 = 建会话期证伪不带 store，W58 修补 A 的主修面）。
#     二者在开发期**单轮根本测不出**（行使需 evict + 重建），
#     与 #24（fold 收口）同属「靠枚举维护的承重不变式」⇒ 用守卫冻结其存在性。
#     判据（同 #24 计数法，排除 def 行与注释/KDoc 位）：
#       ① `adoptResilienceFromStore(` 调用数 == 1（不含 `private fun adoptResilienceFromStore(` def 行）
#       ② `persistResilienceToStore(`  调用数 == 2（不含 def 行；写点①建会话成功路径 + 写点②模板失败收尾）
#       ③ 读回点行号 < 首次 `nativeToolChannelActive()` **代码位**调用行号（钉「读回先于通道判定」）
#     ⚠️ 判据③的**脆性边界（显式声明 —— 任务书要求留痕）**：③ 取的是**全文件首个**
#        `nativeToolChannelActive()` 代码位调用行号，而读回点（ensureConversation 首行）
#        恰好在其之前 ⇒ 当前成立。但它**对重构敏感**：若将来在 adopt
#        调用点之前新增任何 `nativeToolChannelActive()` 调用（例如把某辅助方法挪到
#        ensureConversation 之上），③ 会**误红**。届时请人工确认「读回先于通道判定」的语义
#        是否仍成立，或把判据收窄到 ensureConversation 作用域内 —— **不要**默默删掉 ③
#        （它是当前唯一钉住读回顺序的机械载体；纯函数单测覆盖不到调用点顺序）。
#     ⚠️ ③ **必须排除注释位**：本文件 KDoc/注释里多处提及 `nativeToolChannelActive()`
#        （首处在文件头类 KDoc，远早于首个真调用）；若不排除注释位，③ 会把注释行当锚点
#        ⇒ 读回点反而「晚于」注释锚点 ⇒ **恒红**。故两次 grep 都走
#        `grep -vE "^[0-9]+:[[:space:]]*[*/]"`（与 #24 同款注释排除）。
#     ⚠️ ③ 的**锚点缺失面**：若全文件找不到 `nativeToolChannelActive()` 代码位调用，判红
#        （读回顺序判据的锚点消失 = 守卫面失效，绝不静默 fail-open）。
#     口径声明：本守卫钉**调用点存在性 + 读回顺序**；**不**钉合并语义（OR/max，由纯函数
#     单测负责）、**不**钉 KDoc 措辞 —— 二者互补（「语义 + 接线」双钉），勿夸大任一方为全覆盖。
#     文件缺失 ⇒ exit 1 + ::error::（守卫面失效必须判红，绝不静默 fail-open）。
check "LiteRtLmEngine.kt 韧性 store 双写双读接线（adopt 调用点 1、persist 调用点 2，且读回先于通道判定）" \
  bash -c 'E=core-engine/src/main/java/com/rickeal/agent/core/engine/local/LiteRtLmEngine.kt
           if [ ! -f "$E" ]; then echo "::error::$E 不存在（被改名/删除？第 28 条守卫面已失效）"; exit 1; fi
           adopt_n=$(grep -nE "adoptResilienceFromStore\(" "$E" | grep -vE "^[0-9]+:[[:space:]]*[*/]" | grep -v "fun adoptResilienceFromStore" | wc -l)
           persist_n=$(grep -nE "persistResilienceToStore\(" "$E" | grep -vE "^[0-9]+:[[:space:]]*[*/]" | grep -v "fun persistResilienceToStore" | wc -l)
           [ "$adopt_n" -eq 1 ] || echo "$E 的 adoptResilienceFromStore( 调用数($adopt_n) != 1：读回点（ensureConversation 首行）被删/重复 ⇒ P2#1（自愈置位随 evict 清零）静默复发"
           [ "$persist_n" -eq 2 ] || echo "$E 的 persistResilienceToStore( 调用数($persist_n) != 2：写点（① ensureConversation 成功路径 + ② handleTemplateRenderFailure 收尾）被删/重复 ⇒ 跨实例计数永不落 store"
           adopt_line=$(grep -nE "adoptResilienceFromStore\(" "$E" | grep -vE "^[0-9]+:[[:space:]]*[*/]" | grep -v "fun adoptResilienceFromStore" | head -1 | cut -d: -f1)
           chan_line=$(grep -nE "nativeToolChannelActive\(\)" "$E" | grep -vE "^[0-9]+:[[:space:]]*[*/]" | head -1 | cut -d: -f1)
           if [ -z "$chan_line" ]; then echo "$E 找不到 nativeToolChannelActive() 代码位调用（读回顺序判据的锚点缺失，守卫面已失效）"
           elif [ -n "$adopt_line" ] && [ "$adopt_line" -ge "$chan_line" ]; then echo "$E 读回点行号($adopt_line) >= 首次 nativeToolChannelActive() 调用行号($chan_line)：读回必须先于通道判定（否则同一轮读回状态对通道判定不可见）"; fi'

# 29) litertlm 版本钉死 0.17.1（W58，治外部审查 F-1 P0）：litertlm bump 过「1 元素 text
#     数组→string」收敛删除点 ⇒ fold 失效 ⇒ Qwen2.5 系每轮必炸（非发图才炸）。依据 =
#     W55 真机 A/B 定案（元素数是唯一区分维度）+ W56 实证 v0.18.0 全树零命中。
#     版本目录行是 bump 的**机械锚点**：判据用行首锚 `^litertlm =`（`[libraries]` 段的
#     `litertlm-android = …` 与注释区提及均不命中；钉取值、不钉措辞）。
#     fail-closed：文件缺失 / 提不出版本行均 exit 1 判红（防锚被改名后成空守卫 ——
#     同 #26 baseline 提不出即红范式）。
#     红输出 = F-1 后果摘要 + bump 前置清单提示（有意识动作化）。
#     ⚠️ check() 契约 = stdout 非空即判红：判绿路径必须无输出，红路径必须 echo。
check "litertlm 版本必须为 0.17.1（H-A 收敛语义兼容；bump 前必读四前置+前置⑤）" \
  bash -c 'f=gradle/libs.versions.toml
           if [ ! -f "$f" ]; then echo "::error::$f 不存在（版本目录蒸发，守卫面已失效）"; exit 1; fi
           v=$(grep -oE "^litertlm[[:space:]]*=[[:space:]]*\"[^\"]+\"" "$f" | head -1 | cut -d\" -f2)
           if [ -z "$v" ]; then echo "::error::$f 提不出 litertlm 版本行（行首锚被改名 ⇒ litertlm-android 访问器将解析失败，守卫面已失效）"; exit 1; fi
           [ "$v" = "0.17.1" ] || echo "litertlm 已改为 $v：v0.18.0 起「1 元素 text 数组→string」收敛点已删且无等价替代（W56 实证 tag b2f686e2e 全树零命中）⇒ fold 失效 ⇒ Qwen2.5 系每轮必炸（非发图才炸）。bump 前必须按 W56 交接「四前置」+ W58 前置⑤（临时 K=3）逐条复核，并同步更新本守卫期望值。0.17.1 行为锚定面清单（前置⑥）见 docs/12-litertlm-0171-anchors.md（W59 P3-Ⓐ 指向行，只追加不改动既有子串）"'

# 30) ChatRunCoordinator「run 级 model 来源冻结」（W59，方案 §3）：AgentRequest.model 的
#     取值点必须仍为全局 activeModel 读点（b8b9446 实测 3 处 = AgentRequest 传参位）。
#     动机：若引入「会话级 model」（per-run 换模型），EngineResilienceStore 的键 (cid)
#     必须先扩 (cid, model)（复合键迁移是前置，见其 KDoc 复合键申报）—— 否则同一会话
#     换模型后证伪/计数快照跨模型串键。红 = 有意识动作化（同 #26/#28 哲学）。
#     ⚠️ 判据边界申报：只钉「run 的 model 传参来源」这一形态
#    （`model = uiState.value.activeModel`）；config/policy 位
#    （thermallyCappedConfig(..., uiState.value.activeModel) 等不含该形态）是合法
#     演化面，不钉。三读点若因重构改名/变形，本守卫红 = 有意识动作化，改判据前必须
#     先评估复合键前置是否已解。计数动态输出（不写死行号，历史坑：注释写死行号必 stale）。
#     W60：判据加**注释位排除**（`grep -vE "^[0-9]+:[[:space:]]*[*/]"`，与 #24/#28 同款）
#     —— 防 KDoc/注释提及该形态造成假红；此前 #30 是唯一漏排除者。
#     fail-closed（沿 #29 范式）：文件缺失 → exit 1 + ::error::。
#     红输出自带处置指引（判红契约：stdout 非空即红；相等断言自己 echo 违规事实行）。
check "ChatRunCoordinator 的 AgentRequest.model 取值点必须为全局 activeModel 读点（3 处；引入会话级 model 前须先解 EngineResilienceStore 复合键前置）" \
  bash -c 'f=feature-chat/src/main/java/com/rickeal/agent/feature/chat/ChatRunCoordinator.kt
           if [ ! -f "$f" ]; then echo "::error::$f 不存在（被改名/删除？第 30 条守卫面已失效）"; exit 1; fi
           n=$(grep -nE "^.*model = uiState\.value\.activeModel" "$f" | grep -vE "^[0-9]+:[[:space:]]*[*/]" | wc -l)
           [ "$n" -eq 3 ] || echo "ChatRunCoordinator 的 AgentRequest.model 取值点(实数 $n) != 3：run 级 model 来源必须仍为全局 activeModel 读点。若引入「会话级 model」（per-run 换模型），复合键迁移是前置——EngineResilienceStore 键 (cid) 须先扩 (cid, model)（见其 KDoc 复合键申报），并同步更新本守卫"'

# 31) AgentRunner.kt 总行数上限（W59，治 P3-α）：#25 家族第 4 员
#     （#18 ChatViewModel 1600 / #20 ChatRunCoordinator 1300 / #25 LiteRtLmEngine 2400 /
#     #31 AgentRunner 2900）。core-agent 现有文件级守卫仅第 12 条（主循环**方法**体积），
#     本条补「文件级」盲区（P3-α 本体）。阈值 2900 的推导：b8b9446 实测 2802 行，
#     ≈+3.5% 余量取整（与 #25 的 ≈+2.5% 取整哲学同族，多给一档以容纳 W59+ 挂账多项
#     落其邻域的一次波次增量）。⚠️ 触顶语义 = 启动 AgentRunner 拆分评审
#    （onSend/onToolLoop 候选），**不是改阈值**（与 #18/#20/#25 同款「只许缩不许涨」）。
#     文件缺失 → exit 1 + ::error::（守卫面失效必须判红，绝不静默放行）。
check "AgentRunner.kt 总行数 ≤ 2900（core-agent 文件级行数表 #25 家族第 4 员，触顶 = 启动 AgentRunner 拆分评审，非改阈值）" \
  bash -c 'f=core-agent/src/main/java/com/rickeal/agent/core/agent/AgentRunner.kt
           if [ ! -f "$f" ]; then echo "::error::$f 不存在（被改名/删除？第 31 条守卫面已失效）"; exit 1; fi
           n=$(wc -l < "$f")
           [ "$n" -le 2900 ] || echo "AgentRunner.kt 当前 $n 行，超 2900 行上限（触顶 = 启动 AgentRunner 拆分评审（onSend/onToolLoop 候选），非改阈值）"'

# 32) LiteRtLmEngineLoader.kt 总行数上限（W60）：#25 家族第 5 员
#     （#18 ChatViewModel 1600 / #20 ChatRunCoordinator 1300 / #25 LiteRtLmEngine 2400 /
#     #31 AgentRunner 2900 / #32 LiteRtLmEngineLoader 900）。Loader 是 W59 拆分**新产物**，
#     此前无任何文件级守卫（`grep -rn "LiteRtLmEngineLoader" scripts/arch-guard.sh` = 0）。
#     ⚠️ **阈值张力如实申报**：#25 家族哲学 ≈ +2.5% 余量（704 × 1.025 ≈ 722），本条取
#     900（+27.8%）**显著宽于家族哲学**。辩护理由：① Loader 是**机械支撑件**（加载集群，
#     非状态机），增长面受加载逻辑天然约束；② 704 行文件上 +2.5% 仅 18 行余量、不实用；
#     ③ 与 #31 同为 W59 拆分新产物，给一档宽余量容纳 W60+ 邻域挂账。
#     触顶语义 = 启动 Loader 拆分评审，**不是改阈值**（与 #18/#20/#25/#31 同款「只许缩不许涨」）。
#     文件缺失 → exit 1 + ::error::（守卫面失效必须判红，绝不静默放行）。
check "LiteRtLmEngineLoader.kt 总行数 ≤ 900（core-engine 行数表 #25 家族第 5 员，触顶 = 启动 Loader 拆分评审，非改阈值）" \
  bash -c 'f=core-engine/src/main/java/com/rickeal/agent/core/engine/local/LiteRtLmEngineLoader.kt
           if [ ! -f "$f" ]; then echo "::error::$f 不存在（被改名/删除？第 32 条守卫面已失效）"; exit 1; fi
           n=$(wc -l < "$f")
           [ "$n" -le 900 ] || echo "LiteRtLmEngineLoader.kt 当前 $n 行，超 900 行上限（触顶 = 启动 Loader 拆分评审，非改阈值）"'

# 33) LiteRtLmEngine 世代门控接线（W60，沿 #28「语义 + 接线双钉」哲学）：项 1 外提的
#     shouldDropStaleDisposal 纯函数单测只钉**判据语义**，钉不住**调用点存在性与传参** ——
#     若有人删掉 handler 内的门控（纯函数变死码）或删掉一个调用点，A4 幂等静默失效。
#     判据（两条，均 fail-closed）：
#       ① shouldDropStaleDisposal( **调用点数 == 1**（不含 def 行）⇒ 钉「门控未被删」；
#       ② handleTemplateRenderFailure(token, **调用点数 == 2** ⇒ 钉「两调用点都传 token」。
#     ⚠️ 判据② 的健壮性（实测）：def 行为 `handleTemplateRenderFailure(token: Long, ...)`
#    （`token` 后是 `:` 非 `,`）⇒ 正则 `handleTemplateRenderFailure\(token,` 天然不匹配 def；
#     KDoc 引用均为 `[handleTemplateRenderFailure]` 形态、不含 `(token,` ⇒ 无需注释排除即精确命中 2。
#     口径声明：本守卫钉**调用点存在性 + 传参形态**；**不**钉 token 取值语义正确性
#    （grep 覆盖不到，由纯函数单测负责，与 #28 同类边界，勿夸大任一方为全覆盖）。
#     fail-closed：文件缺失 → exit 1 + ::error::（守卫面失效必须判红）。
check "LiteRtLmEngine 世代门控接线（shouldDropStaleDisposal 调用 1；handleTemplateRenderFailure(token,…) 调用 2）" \
  bash -c 'E=core-engine/src/main/java/com/rickeal/agent/core/engine/local/LiteRtLmEngine.kt
           if [ ! -f "$E" ]; then echo "::error::$E 不存在（第 33 条守卫面已失效）"; exit 1; fi
           g=$(grep -nE "shouldDropStaleDisposal\(" "$E" | grep -v "fun shouldDropStaleDisposal" | wc -l)
           c=$(grep -cE "handleTemplateRenderFailure\(token," "$E")
           [ "$g" -eq 1 ] || echo "$E 的 shouldDropStaleDisposal( 调用数($g) != 1：世代门控被删/重复 ⇒ A4 幂等静默失效"
           [ "$c" -eq 2 ] || echo "$E 的 handleTemplateRenderFailure(token,…) 调用数($c) != 2：两调用点（异步回调/同步下发）必须都传 token"'

echo "-----------------------------------------"
if [ "$fail" -ne 0 ]; then
  echo "架构守卫未通过，请修复上述问题后再合并。"
  exit 1
fi
echo "架构守卫全部通过。"
