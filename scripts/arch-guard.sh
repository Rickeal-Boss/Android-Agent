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

echo "-----------------------------------------"
if [ "$fail" -ne 0 ]; then
  echo "架构守卫未通过，请修复上述问题后再合并。"
  exit 1
fi
echo "架构守卫全部通过。"
