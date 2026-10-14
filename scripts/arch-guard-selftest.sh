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

# ===========================================================================
# W64 Task C：选择性执行（--only / --affected）—— 本地 pre-push 提速，CI 口径不变
# ---------------------------------------------------------------------------
# 背景：全量 selftest 在本机约 50 分钟（Windows Git-Bash 下每次 `bash -c` + grep 的
#   进程启动开销被放大），pre-push 全跑不现实。本机制让本地只跑「受本次改动影响的
#   守卫子集」；**CI 仍以无参数全量运行** —— 不带参数时默认路径的 case 集合、
#   PASS/FAIL 计数与退出码**完全不变**（下面的 `guard_selected` 在全量模式下恒真）。
#
# --affected 口径与回退（务必读）：
#   · 改动集 = 「未推送提交」∪「未提交改动」：
#       git diff --name-only '@{u}...HEAD'   （无 upstream 时该式不可用 ⇒ 回退全量）
#       git diff --name-only HEAD            （工作区/暂存区 vs HEAD）
#   · 每个改动文件经下方映射表 → 守卫编号；**任一文件映射不到子集 ⇒ 整体回退全量**
#     （绝不静默少跑：宁可多跑，不可漏跑）。
#   · `scripts/*` 命中 ⇒ 直接回退全量（守卫链 / 自测网自身改动会牵动全部守卫）。
#   · 非 git 仓库 / 无改动文件 ⇒ 回退全量。
#   ⚠️ 已知局限（如实申报）：映射表只覆盖**本仓现存的、有专属守卫的文件**；
#      未列出的 .kt（例如新增模块）一律回退全量。仓库级扫描型守卫
#      （#1/#5/#6/#9/#15/#16/#17/#21/#26/#27 扫 `.`）不随单个文件改动被选中 ——
#      它们的兜底是「改动 scripts/* 或无映射文件 ⇒ 全量」以及 **CI 恒全量**。
# 测试缝：环境变量 ARCH_SELFTEST_AFFECTED_FILES（换行或冒号分隔的相对路径）可覆盖
#   git 检测，仅供人工 / 自测验证映射表；不设则为空，走真实 git。仅在 --affected 下生效。
# ===========================================================================
ONLY_SET=""
AFFECTED=0

usage() {
  cat <<'USAGE'
用法: bash scripts/arch-guard-selftest.sh [选项]

  （无参数）            运行全部 case（CI 口径，默认；计数与退出码与既往一致）
  --only <n>[,<n>…]     只运行「守的是第 <n> 条守卫」的 case；其余整体跳过
                        （fixture 不建、守卫不跑、PASS/FAIL 不计数）。
                        例：--only 34,35   （只跑 #34 / #35 的 case）
  --affected            依 git 改动文件映射到守卫编号，等价于 --only <映射结果>；
                        映射不全 / 改动 scripts/* / 无 upstream ⇒ 回退全量
  -h, --help            显示本帮助并退出
USAGE
}

# 已知「有 selftest case」的守卫编号（用于校验 --only 参数，防拼错后静默零跑）。
# 新增守卫 case 时同步追加（否则 --only <新号> 会被判为未知编号而报错）。
KNOWN_GUARDS="3 5 7 12 13 14 15 16 17 18 19 20 21 22 23 24 25 26 27 28 29 30 31 32 33 34 35 36 37 38 39 40 41"

# 自洽性 case 编号（不对应任何 arch-guard 守卫；守的是自测网自身的一致性）。
# ⚠️ 与 KNOWN_GUARDS **故意分开两张表**：这些号在 arch-guard.sh 里没有 `# N)` 定义，
#    放进 KNOWN_GUARDS 会被 case53 的「KNOWN_GUARDS↔arch-guard 双向一致」断言判红。
#    新增自洽性 case 时同步追加本表（否则 --only <新号> 报错而非静默零跑）。
META_CASES="53"
# 本波自洽性 case 的编号（case53 块用 `guard_selected $META_CASE` 变量形态引用自己，
# 使下方 case53 的静态提取面 —— `guard_selected +[0-9]` 模式 —— 不会把它算进
# 「有 case 的守卫号」集合）。新增自洽性 case 时与 META_CASES 同步。
META_CASE=53

while [ $# -gt 0 ]; do
  case "$1" in
    -h|--help)  usage; exit 0 ;;
    --only)     ONLY_SET="${2:-}"; shift 2 ;;
    --only=*)   ONLY_SET="${1#--only=}"; shift ;;
    --affected) AFFECTED=1; shift ;;
    *) echo "::error::未知参数：$1（用 --help 查看用法）" >&2; exit 2 ;;
  esac
done

# 文件 → 守卫编号映射表（--affected 用）。输出 "FULL" 表示回退全量。
# ⚠️ 只列「该文件专属守卫」；未列出的文件 ⇒ 调用方回退全量（保守，绝不静默少跑）。
affected_guards_for_file() {
  case "$1" in
    scripts/*)                                                                    echo FULL ;;
    core-engine/src/main/java/com/rickeal/agent/core/engine/local/LiteRtLmEngine.kt)       echo "24 25 28 33" ;;
    core-engine/src/main/java/com/rickeal/agent/core/engine/local/ThinkingBudget.kt)      echo "36" ;;
    core-engine/src/main/java/com/rickeal/agent/core/engine/local/LiteRtLmEngineLoader.kt) echo "32" ;;
    core-agent/src/main/java/com/rickeal/agent/core/agent/AgentRunner.kt)          echo "12 31" ;;
    core-agent/src/main/java/com/rickeal/agent/core/agent/AgentRunnerToolSetup.kt) echo "35" ;;
    core-agent/src/main/java/com/rickeal/agent/core/agent/AgentRunnerLoopHelpers.kt) echo "37" ;;
    core-agent/src/main/java/com/rickeal/agent/core/agent/AgentEvents.kt)          echo "13" ;;
    core-model/src/main/java/com/rickeal/agent/core/model/ModelPresets.kt) echo "34 39 40 41" ;;
    feature-chat/src/main/java/com/rickeal/agent/feature/chat/ChatViewModel.kt)    echo "18" ;;
    feature-chat/src/main/java/com/rickeal/agent/feature/chat/ChatRunCoordinator.kt) echo "20 30" ;;
    feature-chat/src/main/java/com/rickeal/agent/feature/chat/ChatRunRecovery.kt) echo "38" ;;
    .github/workflows/build.yml)                                                   echo "22 23 26" ;;
    core-model/*)                                                                  echo "3 15 26 40 41" ;;
    *)                                                                             echo FULL ;;
  esac
}

# 依改动文件集解析出 ONLY_SET；无法收敛到子集则置空（= 全量）。
resolve_affected() {
  local files="" f guards acc="" full=0 root=""
  if [ -n "${ARCH_SELFTEST_AFFECTED_FILES:-}" ]; then
    files="$(printf '%s' "$ARCH_SELFTEST_AFFECTED_FILES" | tr ':' '\n')"
  else
    root="$(git rev-parse --show-toplevel 2>/dev/null)" || root=""
    if [ -z "$root" ]; then
      echo "::warning::[--affected] 非 git 仓库 ⇒ 回退全量" >&2
      ONLY_SET=""; return 0
    fi
    if git -C "$root" rev-parse --abbrev-ref '@{u}' >/dev/null 2>&1; then
      files="$( { git -C "$root" diff --name-only '@{u}...HEAD'
                  git -C "$root" diff --name-only HEAD
                } | sort -u )"
    else
      echo "::warning::[--affected] 无 upstream（@{u} 不可解析）⇒ 回退全量（无法判定未推送范围）" >&2
      ONLY_SET=""; return 0
    fi
  fi
  if [ -z "$(printf '%s' "$files" | tr -d '[:space:]')" ]; then
    echo "::notice::[--affected] 无改动文件 ⇒ 回退全量" >&2
    ONLY_SET=""; return 0
  fi
  while IFS= read -r f; do
    [ -z "$f" ] && continue
    guards="$(affected_guards_for_file "$f")"
    if [ "$guards" = "FULL" ]; then
      echo "::notice::[--affected] $f ⇒ 映射不到守卫子集，回退全量" >&2
      full=1; break
    fi
    acc="$acc $guards"
  done <<EOF
$files
EOF
  if [ "$full" -eq 1 ]; then ONLY_SET=""; return 0; fi
  ONLY_SET="$(printf '%s' "$acc" | tr ' ' '\n' | grep -v '^$' | sort -un | paste -sd, -)"
  echo "::notice::[--affected] 选定守卫：#$ONLY_SET（仅本地；CI 仍全量）" >&2
}

[ "$AFFECTED" -eq 1 ] && resolve_affected

# 校验 --only / --affected 选出的守卫编号都是已知的（防拼错后静默零跑）。
# META_CASES 里的自洽性 case 号一并放行（它们的违例是「自测网自身漂移」，见 case53）。
if [ -n "$ONLY_SET" ]; then
  for g in ${ONLY_SET//,/ }; do
    case " $KNOWN_GUARDS $META_CASES " in
      *" $g "*) ;;
      *) echo "::error::--only 含未知守卫编号：$g（已知守卫：$KNOWN_GUARDS；自洽性 case：$META_CASES）" >&2; exit 2 ;;
    esac
  done
fi

# 谓词：当前 case 是否被选中。$1 = 该 case 的守卫标签（数字，或 ALL=仅全量模式运行）。
# 全量模式（ONLY_SET 为空）恒真 ⇒ 默认行为与既往逐 case 一致（计数 / 退出码不变）。
guard_selected() {
  [ -z "$ONLY_SET" ] && return 0
  [ "$1" = "ALL" ] && return 1
  local g
  for g in $1; do
    case ",$ONLY_SET," in *",$g,"*) return 0 ;; esac
  done
  return 1
}

GUARD="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/arch-guard.sh"
if [ ! -f "$GUARD" ]; then
  echo "::error::找不到 arch-guard.sh（期望与自测脚本同目录）：$GUARD"
  exit 1
fi

# D4（Wave 49）：解释器探测 —— 优先 python3、回退 python。
# 下方 case11 / case11b 用 heredoc 生成 lint-baseline.xml fixture，此前写的是裸
# `python`：CI（ubuntu）与本机 Git-Bash 都带 `python` 别名，故一直没暴露；但**裸
# Linux（仅 python3）会让这两条 case 假 FAIL**（外部审查已实测复现）。改探测式后
# 两种环境都稳；两个都找不到则显式判红，不静默放行。
PY="$(command -v python3 || command -v python)"
if [ -z "$PY" ]; then
  echo "::error::找不到 python3 / python —— 自测网 case11/case11b 依赖它生成 fixture"
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
# 第 31 条（W59，AgentRunner.kt ≤ 2900）绿面：默认 body=5，总行数远低于阈值。
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
#
# ⚠️ 9 个模块目录必须**全部**铺齐（与 settings.gradle.kts 的 include、以及
#    arch-guard.sh 前置断言的清单三方一致）：漏铺任何一个，positive 与所有「绿面」
#    case 都会被模块存在性断言误红 —— 这本身就是那条断言在工作的证据。
#    Wave 39 前这里只铺 6 个（缺 3 个 feature-*），与当时守卫侧的 6 个恰好对齐 ⇒
#    两边一起漏，谁都发现不了。
scaffold() {
  local root="$1"
  mkdir -p "$root"/core-model "$root"/core-design "$root"/core-engine \
           "$root"/core-agent "$root"/core-data \
           "$root"/feature-chat "$root"/feature-models "$root"/feature-settings \
           "$root"/app/src/main
  # W67 B2 迁移刀：ModelPresets 已从 feature-models 迁到 core-model（与 ModelGpuSupport 同层）
  # ⇒ #34/#39 的夹具写盘路径改为 core-model 嵌套目录，须在此统一铺出该目录。
  mkdir -p "$root/core-model/src/main/java/com/rickeal/agent/core/model"
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
  # 第 18 条（Wave 42 起）在 ChatViewModel.kt 缺失时判「守卫面失效」⇒ 骨架必须提供它。
  # 给一个只有类声明的最小文件：类声明行被第 17 条的 class 过滤器排除（非直构造），
  # 行数远低于冻结值 ⇒ 干净树对 17 / 18 两条新守卫都保持绿。
  mkdir -p "$root/feature-chat/src/main/java/com/rickeal/agent/feature/chat"
  cat > "$root/feature-chat/src/main/java/com/rickeal/agent/feature/chat/ChatViewModel.kt" <<'KT'
package com.rickeal.agent.feature.chat

class ChatViewModel(
    val container: com.rickeal.agent.core.data.AppContainer,
)
KT
  # 第 20 条（Wave 49 R-C）在 ChatRunCoordinator.kt 缺失时判「守卫面失效」⇒ 骨架必须
  # 提供它（否则干净树会被本条误红）。行数远低于 1300。
  # 第 30 条（W59，run 级 model 读点冻结）要求文件内恰有 **3 处**
  # `model = uiState.value.activeModel` 形态（AgentRequest 传参位）⇒ 骨架必须铺满 3 处
  #（判据 ==3；漏铺/多铺都会让干净树误红 —— case41c 独立钉死这个同步关系）。
  cat > "$root/feature-chat/src/main/java/com/rickeal/agent/feature/chat/ChatRunCoordinator.kt" <<'KT'
package com.rickeal.agent.feature.chat

class ChatRunCoordinator {
    fun run1() = AgentRequest(model = uiState.value.activeModel)
    fun run2() = AgentRequest(model = uiState.value.activeModel)
    fun run3() = AgentRequest(model = uiState.value.activeModel)
}
KT
  # 第 38 条（W65，ChatRunRecovery.kt ≤ 400）为 fail-closed：文件缺失即红 ⇒ 骨架必须提供
  # 它，否则正例 + 所有绿面 case 会被本条误红（漏铺 ⇒ 全红）。给一个远低于 400 行的最小
  # 文件（与 #20 同款同目录铺点；不含 `XxxViewModel(` 形态 ⇒ 不误触第 17 条直构造守卫）。
  cat > "$root/feature-chat/src/main/java/com/rickeal/agent/feature/chat/ChatRunRecovery.kt" <<'KT'
package com.rickeal.agent.feature.chat

class ChatRunRecovery {
    fun shouldReplay(journalHasEntry: Boolean): Boolean = journalHasEntry
}
KT
  # 第 21 条（Wave 49 R-D）要求生产源码里存在**唯一**的 USER 章印点 ⇒ 骨架必须提供它
  # （否则干净树会被本条误红）。给一个含 `capabilitiesSource = CapabilitySource.USER`
  # 的最小 ModelRepository.kt。
  mkdir -p "$root/core-data/src/main/java/com/rickeal/agent/core/data"
  cat > "$root/core-data/src/main/java/com/rickeal/agent/core/data/ModelRepository.kt" <<'KT'
package com.rickeal.agent.core.data

class ModelRepository {
    fun setCapabilities(id: String, capabilities: ModelCapabilities) {
        val current = find(id) ?: return
        upsert(
            current.copy(
                capabilities = capabilities,
                capabilitiesSource = CapabilitySource.USER,
            ),
        )
    }
}
KT
  # 第 14 条（Wave 32 起）要求 app/lint-baseline.xml 存在且条目数 <= 冻结值 4
  # （沿革 83 → Wave 37 后 35 → Wave 38 清障后 3）：骨架给 2 条 dummy 条目，
  # 2 <= 3 仍低于冻结值，干净树保持绿 —— 故 scaffold() 无需随冻结值下调而改动。
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
  # 第 19 条（Wave 49 R-B）要求 scripts/fulltest.sh 存在且含 --continue；第 22 条
  # （Wave 50）要求它含 --summary-only 分派臂；第 23 条（Wave 51）要求其 --summary-only
  # 分支恒 exit 0 且 gradle 解析块（含 exit 2）被 SUMMARY_ONLY==0 包住 ⇒ 骨架必须
  # **三条都满足**（否则干净树会被这几条误红，正例失守）。
  mkdir -p "$root/scripts"
  cat > "$root/scripts/fulltest.sh" <<'SH'
#!/usr/bin/env bash
# scaffold 用的最小载体：含 --continue（第 19 条）、--summary-only 分派臂（第 22 条）、
# 以及 --summary-only 恒 exit 0 的早退载体 + 被 SUMMARY_ONLY==0 包住的 exit 2（第 23 条）。
set -uo pipefail
SUMMARY_ONLY=0
while [ $# -gt 0 ]; do
  case "$1" in
    --summary-only) SUMMARY_ONLY=1; shift ;;
    *) shift ;;
  esac
done
if [ "$SUMMARY_ONLY" -eq 0 ]; then
  if [ -x ./gradlew ]; then
    GRADLE_CMD=./gradlew
  elif command -v gradle >/dev/null 2>&1; then
    GRADLE_CMD=gradle
  else
    echo "::error::找不到 gradle" >&2
    exit 2
  fi
  "$GRADLE_CMD" test --continue
fi
if [ "$SUMMARY_ONLY" -eq 1 ]; then
  exit 0
fi
exit 0
SH
  # 第 29 条（W58，litertlm 版本钉死 0.17.1）要求 gradle/libs.versions.toml 存在且
  # 行首锚 `litertlm = "0.17.1"` 可提取 ⇒ 骨架必须提供**最小 toml**（只含一个版本行；
  # 不含任何禁依赖关键词 —— 不触发第 4 条的版本目录扫描面）。
  mkdir -p "$root/gradle"
  cat > "$root/gradle/libs.versions.toml" <<'TOML'
[versions]
# 最小版本目录件（守卫 #29 的扫描面）；版本沿革说明可自由增改（守卫不钉注释）。
litertlm = "0.17.1"
TOML
  # 第 24 条（Wave 52，fold 收口不变式）要求 LiteRtLmEngine.kt 存在且满足
  # 「Message.user 下发点数 == foldAdjacentText 调用数」；第 25 条（Wave 52）要求其
  # 行数 ≤ 2400；第 28 条（Wave 57 建 / W58 改判据②）要求 adopt 调用 1、persist 调用 2
  #（写点① ensureConversation 成功路径 + 写点② handleTemplateRenderFailure 收尾），
  # 且读回点（adopt）行号 < 首个 nativeToolChannelActive() 调用行号
  # ⇒ 骨架必须同时满足这三条（否则干净树会被误红，正例失守）。
  # fixture 给 1 处 Message.user 下发点 + 1 处 foldAdjacentText 调用（1 == 1，绿），
  # 且行数远低于 2400。def 行（`internal fun foldAdjacentText`）会被守卫的
  # `grep -v "fun foldAdjacentText"` 排除，不计入 fold_n —— 与真实文件同款。
  # Wave 57 追加：`adoptResilienceFromStore(cid)` 与 `persistResilienceToStore()` 各 1 处
  # **调用**（对应 def 行由守卫的 `grep -v "fun …"` 排除，与真实文件同款），且
  # `nativeToolChannelActive()` 的**首个代码位调用**在 adopt 调用**之后**（钉读回顺序）。
  # W58 追加：ensureConversation 内补 `persistResilienceToStore()`（写点①，adopt 之后、
  # 通道判定之前）⇒ persist 调用共 2 处，与判据② 的「== 2」对齐。
  # W60 追加（第 33 条世代门控接线）：fixture 必须含 `shouldDropStaleDisposal(` 的
  # def（被 `grep -v "fun shouldDropStaleDisposal"` 排除）+ **1 处调用**（门控），且
  # `handleTemplateRenderFailure(token,` 恰 **2 处调用**（两调用点：异步回调 / 同步下发）；
  # def 行为 `token: Long`（`token` 后是 `:` 非 `,`）⇒ 天然不被判据② 计入 —— 与真实文件同款。
  mkdir -p "$root/core-engine/src/main/java/com/rickeal/agent/core/engine/local"
  cat > "$root/core-engine/src/main/java/com/rickeal/agent/core/engine/local/LiteRtLmEngine.kt" <<'KT'
package com.rickeal.agent.core.engine.local

internal fun foldAdjacentText(contents: List<Content>): List<Content> = contents

internal fun shouldDropStaleDisposal(token: Long, current: Long): Boolean = token != current

fun downlink(fresh: List<Content>): Message =
    Message.user(Contents.of(foldAdjacentText(fresh)))

class LiteRtLmEngine {
    fun ensureConversation(cid: String?) {
        adoptResilienceFromStore(cid)
        persistResilienceToStore()
        val nativeToolsActive = nativeToolChannelActive()
    }

    fun runAsync(token: Long) {
        handleTemplateRenderFailure(token, "raw", "user", "异步回调")
    }

    fun runSync(token: Long) {
        handleTemplateRenderFailure(token, "raw", "user", "同步下发")
    }

    fun handleTemplateRenderFailure(token: Long, raw: String, role: String, source: String) {
        if (shouldDropStaleDisposal(token, currentGenerationToken)) return
        persistResilienceToStore()
    }

    private fun adoptResilienceFromStore(cid: String?) = Unit

    private fun persistResilienceToStore() = Unit

    private fun nativeToolChannelActive(): Boolean = true

    private var currentGenerationToken: Long = -1
}
KT
  # 第 32 条（W60，LiteRtLmEngineLoader.kt ≤ 900）要求该文件存在 ⇒ 骨架必须提供它，
  # 否则正例 + 所有绿面 case 会被本条 fail-closed 误红（守卫假绿陷阱的镜像：漏铺 ⇒ 全红）。
  # 行数远低于 900。
  cat > "$root/core-engine/src/main/java/com/rickeal/agent/core/engine/local/LiteRtLmEngineLoader.kt" <<'KT'
package com.rickeal.agent.core.engine.local

class LiteRtLmEngineLoader {
    fun load(): Int = 0
}
KT
  # 第 36 条（W65，ThinkingBudget.kt ≤ 150）同为 fail-closed：文件缺失即红 ⇒ 骨架必须提供
  # 它，否则正例 + 所有绿面 case 会被本条误红（漏铺 ⇒ 全红）。fixture 用与生产同款形态的
  # 常量 + 两条派生函数（真实代码行，非注释）；行数远低于 150。
  cat > "$root/core-engine/src/main/java/com/rickeal/agent/core/engine/local/ThinkingBudget.kt" <<'KT'
package com.rickeal.agent.core.engine.local

internal fun resolveThinkingBudget(configured: Int, maxTokens: Int): Int {
    val budget = if (configured > 0) configured else (maxTokens / 2).coerceAtLeast(1)
    return budget.coerceIn(1, (maxTokens - 1).coerceAtLeast(1))
}

internal const val THINKING_CHARS_PER_TOKEN_CEIL = 16

fun thinkingCharBudgetFor(maxTokens: Int, configuredThinkingBudget: Int = 0): Int =
    resolveThinkingBudget(configuredThinkingBudget, maxTokens) * THINKING_CHARS_PER_TOKEN_CEIL
KT
  # 第 35 条（W64，AgentRunnerToolSetup.kt ≤ 400）为 fail-closed：文件缺失即红 ⇒
  # 骨架必须提供它，否则正例 + 所有绿面 case 会被本条误红（守卫假绿陷阱的镜像：
  # 漏铺 ⇒ 全红）。给一个远低于 400 行的最小文件。
  cat > "$root/core-agent/src/main/java/com/rickeal/agent/core/agent/AgentRunnerToolSetup.kt" <<'KT'
package com.rickeal.agent.core.agent

class AgentRunnerToolSetup {
    fun setup(): Int = 0
}
KT
  # 第 37 条（W65，AgentRunnerLoopHelpers.kt ≤ 400）同为 fail-closed：文件缺失即红 ⇒
  # 骨架必须提供它，否则正例 + 所有绿面 case 会被本条误红（漏铺 ⇒ 全红）。给一个远低于
  # 400 行的最小文件（与 #35 同款同目录铺点）。
  cat > "$root/core-agent/src/main/java/com/rickeal/agent/core/agent/AgentRunnerLoopHelpers.kt" <<'KT'
package com.rickeal.agent.core.agent

class AgentRunnerLoopHelpers {
    fun nextDelay(attempt: Int): Long = 100L * (attempt + 1)
}
KT
  # 第 34 条（W63 刀C，knownBrokenOn 互斥 recommended）要求 feature-models 的
  # ModelPresets.kt 存在且含 `val knownBrokenOn: String?` 声明 ⇒ 骨架必须提供一份
  # **全合法组合**的最小预设表（推荐项不标坏 / 坏容器不推荐），否则干净树会被
  # fail-closed 判红（正例失守）。组合覆盖：a = recommended 无 knownBrokenOn（缺省）；
  # b = knownBrokenOn 非空 + recommended=false；c = 显式 knownBrokenOn=null +
  # recommended=true（null 与 true 合法共存）。
  # 第 39 条（W66 B3，evidenceLevel 全 schema）另要求：存在 `val evidenceLevel` 声明 +
  # **每个构造块显式赋值 evidenceLevel ∈ {MEASURED,UPSTREAM,UNVERIFIED}**，且 MEASURED
  # 块的 `backendBasis`/`note` 赋值行内须含 `W##` 波次实测锚点（注释/label 里的 W## **不算**，
  # W66 深审 D2）⇒ 骨架三条预设分别用 MEASURED（锚点写在 `note` 里）与 UPSTREAM（无需锚点），
  # 并铺 enum 声明 + `note` 字段。漏铺任一 ⇒ 干净树被本条 fail-closed 判红（正例失守）。
  # W68 A3 追加（第 41 条）：data class 补 `val url: String = ""` 字段，且 clean-recommended
  # 显式赋 url = ".../fixture-preset.litertlm"（与下方 ModelSamplingProfiles 骨架臂同名）
  # ⇒ 方向A（有 URL 的预设命中档案）与方向B（档案臂有预设锚定）双向皆绿；不铺 ⇒ 骨架臂
  # 孤儿 ⇒ 所有绿面 case 被第 41 条误红。
  mkdir -p "$root/feature-models/src/main/java/com/rickeal/agent/feature/models"
  cat > "$root/core-model/src/main/java/com/rickeal/agent/core/model/ModelPresets.kt" <<'KT'
package com.rickeal.agent.core.model

enum class EvidenceLevel { MEASURED, UPSTREAM, UNVERIFIED }

data class ModelPreset(
    val label: String,
    val url: String = "",
    val recommended: Boolean = false,
    val knownBrokenOn: String? = null,
    val evidenceLevel: EvidenceLevel = EvidenceLevel.UNVERIFIED,
    val note: String = "",
)

object ModelPresets {
    val all: List<ModelPreset> = listOf(
        ModelPreset(
            label = "clean-recommended",
            url = "https://fixture.invalid/fixture-preset.litertlm",
            recommended = true,
            evidenceLevel = EvidenceLevel.MEASURED,
            note = "W60 实测（fixture 锚点）：MEASURED 合法需 backendBasis/note 内出现 W## 波次标记。",
        ),
        ModelPreset(
            label = "broken-not-recommended",
            recommended = false,
            knownBrokenOn = "0.17.1 假签名（fixture）",
            evidenceLevel = EvidenceLevel.UPSTREAM,
        ),
        ModelPreset(
            label = "explicit-null-recommended",
            recommended = true,
            knownBrokenOn = null,
            evidenceLevel = EvidenceLevel.UPSTREAM,
        ),
    )
}
KT
  # 第 41 条（W68 A3，预设↔档案双向一致）要求 ModelSamplingProfiles.kt 存在且含
  # `when (fileName) {` 提取面 ⇒ 骨架必须提供它，否则正例 + 所有绿面 case 会被本条
  # fail-closed 判红（漏铺 ⇒ 全红）。铺**一臂非空注册表**（fixture-preset.litertlm），
  # 与上方 clean-recommended 预设的 URL 同名 ⇒ 方向A（有 URL 的预设命中档案）与方向B
  # （档案臂有预设锚定）双向皆绿。
  # ⚠️ 为何不能铺空注册表（只有 else -> null）：扫描器对「when 区在但零臂」返回空列表
  #   （合法空注册表：方向B 空过、方向A 仍硬判）——但那样 scaffold 正例的方向B 覆盖面就
  #   vacuous 了，且 clean-recommended 一带 URL 就会被方向A 判红 ⇒ 仍铺一臂非空注册表。
  # ⚠️ 覆写 ModelPresets.kt 的**绿面** case 必须自带同名 URL（fixture-preset.litertlm）
  #   或同文件改注册表（case61e 已带 fixture-preset URL；case65e/case41c 双文件同覆）——
  #   否则骨架臂变孤儿 ⇒ 方向B 误红。红面 case 不受影响（多一条红不断言，见文件头设计取舍）。
  cat > "$root/core-model/src/main/java/com/rickeal/agent/core/model/ModelSamplingProfiles.kt" <<'KT'
package com.rickeal.agent.core.model

object ModelSamplingProfiles {
    fun forFileName(fileName: String?): ModelSamplingProfile? = when (fileName) {
        "fixture-preset.litertlm" -> ModelSamplingProfile(
            recommendedTemperature = 0.7f,
            temperatureRange = 0.3f..1.2f,
            recommendedTopK = 40,
            maxTopK = 128,
            maxContextLength = 4096,
            evidence = "fixture：骨架唯一档案臂，与 clean-recommended URL 同名",
        )
        else -> null
    }
}
KT
  # 第 26 条（Wave 53，测试基线双向同步）要求 build.yml 声明 `baseline=N` 且与全仓
  # `@Test` 代码位实数**相等** ⇒ 骨架必须提供**一份含匹配 baseline 的 build.yml** +
  # **恰好 N 条 @Test 载体**（否则干净树会被本条误红，正例失守）。
  # N 取 3：够小（脚手架轻）且够大（能分别造「滞后」「删例」两个方向）。
  # 载体放 core-model/src/test/ ⇒ 顺带被第 15 条（void / 反引号非法字符）扫描，
  # 故必须是合法的 block-body void 方法（与真实测试同款）。
  mkdir -p "$root/.github/workflows" "$root/core-model/src/test/java/com/x"
  cat > "$root/.github/workflows/build.yml" <<'YML'
name: Build
# scaffold 用最小 build.yml：第 26 条守卫仅需其中一行 `baseline=N`（提取面）。
jobs:
  unit-tests:
    runs-on: ubuntu-latest
    steps:
      - run: |
          baseline=3
YML
  cat > "$root/core-model/src/test/java/com/x/BaselineTest.kt" <<'KT'
package com.x

import kotlin.test.Test
import kotlin.test.assertTrue

class BaselineTest {
    @Test
    fun caseOne() {
        assertTrue(true)
    }

    @Test
    fun caseTwo() {
        assertTrue(true)
    }

    @Test
    fun caseThree() {
        assertTrue(true)
    }
}
KT
  # 第 27 条（Wave 53，A5 台账聚合句守卫）要求 README 与 docs 的聚合句与
  # docs §11.0.1 逐条台账**结论列三态实数**一致 ⇒ 骨架必须提供**一份 §11.0.1 台账 +
  # 两份口径一致的聚合句**（否则干净树会被本条误红）。台账取 3 条 = 1 ✅ / 1 ⚠️ / 1 ⛔；
  # 两处聚合句均写「累计 3 条（其中 1 条 ✅回收 / 1 条 ⚠️部分 / 1 条 ⛔不适用）」。
  mkdir -p "$root/docs"
  cat > "$root/docs/10-device-acceptance.md" <<'MD'
# 设备验收

## 11. 跨波次积压

### 11.0 取证前提

Wave 33 起累计的验收项 **3 条**（逐条见 §11.0.1 台账）—— 其中 **1 条 ✅回收 / 1 条 ⚠️部分 / 1 条 ⛔不适用**；剩余项见台账末。

### 11.0.1 已回收台账

| # | 验收项 | 结论 | 证据锚点 |
|---|---|---|---|
| 1 | 项A | ✅ 回收 | cid a |
| 2 | 项B | ⚠️ 部分 | cid b |
| 3 | 项C | ⛔ 不适用 | cid c |

### 11.1 回显与引擎

（略）
MD
  cat > "$root/README.md" <<'MD'
# 项目

## 挂账台账（🟡 已实现未接线）

（暂无）

> - ⚠️ **真机验收台账：Wave 33 起累计 3 条（其中 1 条 `✅回收` / 1 条 `⚠️部分` / 1 条 `⛔不适用`）**（逐条台账见 `docs/10-device-acceptance.md` §11.0.1）。
MD
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
# 选择性执行时的显式提示（全量模式下 ONLY_SET 为空 ⇒ 不打印，默认输出不变）。
[ -n "$ONLY_SET" ] && echo "（选择性执行：仅运行守卫 #$ONLY_SET 的 case；CI 仍以无参数全量运行）"

# ---------------------------------------------------------------------------
# 正例：干净树必须退出 0（防「守卫永远红」/ 前置断言误伤）
# ---------------------------------------------------------------------------
if guard_selected ALL; then
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

fi
# ---------------------------------------------------------------------------
# case 1：core-model 内出现 java.net.URL ⇒ 第 5 条（全仓禁网络栈）必须报红
# ---------------------------------------------------------------------------
if guard_selected 5; then
d="$TMP/case1-network"
scaffold "$d"
echo 'import java.net.URL' > "$d/core-model/Violation.kt"
out="$(run_guard "$d")"; rc=$?
assert_red "case1 core-model 网络栈 (第 5 条)" "$rc" "$out" \
  "全仓禁网络栈（java.net/javax.net/okhttp/okio/HttpClient/SocketChannel）"

fi
# ---------------------------------------------------------------------------
# case 2：core-model 内出现 import android.os.Build ⇒ 第 3 条（core-model 无 android 依赖）报红
# ---------------------------------------------------------------------------
if guard_selected 3; then
d="$TMP/case2-android"
scaffold "$d"
printf 'import android.os.Build\n' > "$d/core-model/Violation.kt"
out="$(run_guard "$d")"; rc=$?
assert_red "case2 core-model android 依赖 (第 3 条)" "$rc" "$out" \
  "core-model 无 android/androidx 依赖"

fi
# ---------------------------------------------------------------------------
# case 3：executeBodyUnchecked 631 行 ⇒ 第 12 条（主循环方法体积）报红
#         （n = 签名行到下一个方法行 = body + 3 ⇒ body=628 得 631 行）
# ---------------------------------------------------------------------------
if guard_selected 12; then
d="$TMP/case3-size"
scaffold "$d"
write_runner "$d" 628
out="$(run_guard "$d")"; rc=$?
assert_red "case3 主循环方法体积 631 行 (第 12 条)" "$rc" "$out" \
  "主循环方法体积未超预算（executeBodyUnchecked ≤ 550 行）"

fi
# ---------------------------------------------------------------------------
# case 4：删掉 Manifest 的 INTERNET 行 ⇒ 第 7 条（Manifest 有 INTERNET）报红
#         （这条正是 Wave 30 修掉的僵尸规则，必须能测出）
# ---------------------------------------------------------------------------
if guard_selected 7; then
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

fi
# ---------------------------------------------------------------------------
# case 5：AgentRequest 含「宿主侧零赋值点」的可空字段 ⇒ 第 13 条（孤儿守卫）报红
#         其中 ghostGeneric 故意用泛型类型 `Map<String, Int>? = null` ——
#         旧抽取正则（字符类不含逗号/空格）会**静默漏检**该形态，本 case 正是钉死它。
# ---------------------------------------------------------------------------
if guard_selected 13; then
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

fi
# ---------------------------------------------------------------------------
# case 6：executeBodyUnchecked 是类内**最后一个**方法（无后置方法边界）⇒ 第 12 条
#         的 e 非空断言应判「守卫面失效」报红（否则 n 变负会静默通过 = 僵尸规则）。
# ---------------------------------------------------------------------------
if guard_selected 12; then
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

fi
# ---------------------------------------------------------------------------
# case 7：可空字段**有**宿主侧赋值点、但**没有** @wire-owner 标记 ⇒ 第 13 条必须报红。
#         钉死「① 无标记即红」是独立断言 —— 若只靠旧的「零赋值点」判定，本 case
#         会因为赋值点存在而漏判（新增字段忘了申报就静默过关）。
# ---------------------------------------------------------------------------
if guard_selected 13; then
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

fi
# ---------------------------------------------------------------------------
# case 8：`internal` 标记 + 赋值点**只在 core-agent/** ⇒ 第 13 条必须**不红**。
#         钉死 `deadlineNanos` 的真实形态（宿主侧刻意不传、由父 run 透传给子 run）——
#         旧实现「全局 grep」对它只是碰巧正确；收紧搜索面到宿主侧会让它恒红。
# ---------------------------------------------------------------------------
if guard_selected 13; then
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

fi
# ---------------------------------------------------------------------------
# case 9：`pending:理由串` 但该理由串**不在** README 挂账台账节 ⇒ 第 13 条必须报红
#         （③ 交叉断言的「红」面）。case9b 再钉「绿」面，防本条变成永远红的僵尸规则。
# ---------------------------------------------------------------------------
if guard_selected 13; then
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

> - ⚠️ **真机验收台账：Wave 33 起累计 3 条（其中 1 条 `✅回收` / 1 条 `⚠️部分` / 1 条 `⛔不适用`）**（逐条台账见 `docs/10-device-acceptance.md` §11.0.1）。
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

fi
# ---------------------------------------------------------------------------
# case 10：同一 fixture 里把理由串**补进** README 挂账台账节 ⇒ 第 13 条必须**不红**。
#          钉死 ③ 的「绿」面：只有红面会让它成为永远红的僵尸规则（挂着账就必须放行）。
# ---------------------------------------------------------------------------
if guard_selected 13; then
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

> - ⚠️ **真机验收台账：Wave 33 起累计 3 条（其中 1 条 `✅回收` / 1 条 `⚠️部分` / 1 条 `⛔不适用`）**（逐条台账见 `docs/10-device-acceptance.md` §11.0.1）。
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

fi
# ---------------------------------------------------------------------------
# case 11：baseline 条目数超冻结值 ⇒ 第 14 条必须报红（防 baseline 变成
#          「顺手把新问题 regen 进去」的僵尸豁免入口 —— 那会让整条 lint 门禁失效）。
#          fixture 用 84 条 issue：验证计数只数 <issue 元素、不计根元素 <issues
#          （若把 <issues 也算进去，85 > 4 恒红会让本 case 的判据失真）。
#          ⚠️ 84 是相对冻结值 4 取的「明显超出」样本（Wave 38 冻结值 35 → 4 后，
#          84 > 4 仍成立，故样本无需改）。若冻结值涨到 84 以上，必须同步调大本样本。
# case11b：条目数低于冻结值（清了存量）⇒ 必须不红（「只许缩不许涨」的另一面）。
# ---------------------------------------------------------------------------
if guard_selected 14; then
d="$TMP/case11-baseline-overage"
scaffold "$d"
"$PY" - "$d/app/lint-baseline.xml" <<'PYGEN'
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
  "lint baseline 条目数未超冻结值（4，只许清障不许新增豁免）"
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
"$PY" - "$d/app/lint-baseline.xml" <<'PYGEN'
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

fi
# ---------------------------------------------------------------------------
# case 12：测试源集里 @Test 方法以「返回值型断言」收尾 ⇒ 第 15 条必须报红。
#          Wave 37 实案：JUnit 4 要求测试方法返回 void，而 Kotlin 表达式体的返回类型
#          由末表达式决定；assertNotNull/assertIs/assertFailsWith/assertFails 会返回值
#          ⇒ 方法非 void ⇒ 整个测试类 initializationError（该类所有用例静默不跑，
#          Gradle 只报「N tests completed, 1 failed」，极具误导性）。
# ---------------------------------------------------------------------------
if guard_selected 15; then
d="$TMP/case12-test-nonvoid"
scaffold "$d"
mkdir -p "$d/core-agent/src/test/java/com/x"
cat > "$d/core-agent/src/test/java/com/x/NonVoidTest.kt" <<'KT'
package com.x

import kotlin.test.Test
import kotlin.test.assertNotNull

class NonVoidTest {
    @Test
    fun `末语句返回值型断言`() = helper {
        assertNotNull(value, "m")
    }
}
KT
out="$(run_guard "$d")"; rc=$?
assert_red "case12 @Test 方法非 void (第 15 条)" "$rc" "$out" \
  "测试源集 @Test 方法必须返回 void 且反引号名不含 JVM 非法字符（. ; [ / < >）"
# case12b：红必须来自**真命中**，而不是「守卫命令自身执行失败」——
#          后者那行也含守卫名，会让 assert_red 假绿（本 case 首版就踩过：扫描器用
#          相对 cwd 路径，在脚手架树里找不到文件 ⇒ exit 2 ⇒ 假绿）。
if printf '%s\n' "$out" | grep -qF "守卫命令自身执行失败"; then
  echo "FAIL [case12b] 第 15 条报的是「守卫命令自身执行失败」而非真命中（本 case 假绿）"
  printf '%s\n' "$out" | sed 's/^/    | /'
  FAIL=$((FAIL + 1))
elif printf '%s\n' "$out" | grep -qF "assertNotNull"; then
  echo "PASS [case12b] 红来自真命中（输出含违规行 assertNotNull）"
  PASS=$((PASS + 1))
else
  echo "FAIL [case12b] 输出里看不到违规行，判据可疑"
  printf '%s\n' "$out" | sed 's/^/    | /'
  FAIL=$((FAIL + 1))
fi

fi
# ---------------------------------------------------------------------------
# case 12c：反引号方法名含 JVM 非法字符 ⇒ 第 15 条必须报红。
#          Wave 38 实案：JVM 规范 §4.2.2 禁止方法名含 `. ; [ /`（`<` `>` 亦不可）。
#          反引号里写「A / B」这类中文名 ⇒ Kotlin 报
#          `e: ... Name contains illegal characters: /` ⇒ compileDebugUnitTestKotlin 失败
#          ⇒ **整个 Build job 红**。本机无 JDK 完全查不出（CI 是唯一通道），故必须静态拦。
# ---------------------------------------------------------------------------
if guard_selected 15; then
d="$TMP/case12c-illegal-name"
scaffold "$d"
mkdir -p "$d/core-agent/src/test/java/com/x"
cat > "$d/core-agent/src/test/java/com/x/IllegalNameTest.kt" <<'KT'
package com.x

import kotlin.test.Test
import kotlin.test.assertTrue

class IllegalNameTest {
    @Test
    fun `读不了 upsert / remove 均回 Unreadable`() {
        assertTrue(true)
    }
}
KT
out="$(run_guard "$d")"; rc=$?
assert_red "case12c 反引号名含 JVM 非法字符 (第 15 条)" "$rc" "$out" \
  "测试源集 @Test 方法必须返回 void 且反引号名不含 JVM 非法字符（. ; [ / < >）"
# case12d：红必须来自**真命中**（输出里能看到非法字符判定行），而不是守卫自身故障。
if printf '%s\n' "$out" | grep -qF "守卫命令自身执行失败"; then
  echo "FAIL [case12d] 第 15 条报的是「守卫命令自身执行失败」而非真命中（本 case 假绿）"
  printf '%s\n' "$out" | sed 's/^/    | /'
  FAIL=$((FAIL + 1))
elif printf '%s\n' "$out" | grep -qF "非法字符"; then
  echo "PASS [case12d] 红来自真命中（输出含「非法字符」判定行）"
  PASS=$((PASS + 1))
else
  echo "FAIL [case12d] 输出里看不到非法字符判定行，判据可疑"
  printf '%s\n' "$out" | sed 's/^/    | /'
  FAIL=$((FAIL + 1))
fi

fi
# ---------------------------------------------------------------------------
# case 13：一个模块目录被改名/删除（目录不再存在）⇒ 前置「模块存在性」断言必须报红。
#          这条断言存在的全部意义，是兜住「带 `| grep -vE` 的守卫在目标目录缺失时
#          静默判绿」这个僵尸规则风险（退出码实测见 arch-guard.sh 该断言处的注释）。
#          可它自己此前**一个 selftest case 都没有** ⇒ 它坏了没人知道 —— 这正是
#          「守卫的守卫」最讽刺的失效形态。
#          选 feature-settings 下手：它是第 13 条宿主源集的成员（正是 Wave 39 补进
#          清单的那 3 个之一），而骨架的 AgentRequest 无可空字段 ⇒ 动它只会触发
#          模块存在性断言，不会牵动第 13 条本身的判定。
#          「绿面」（9 个目录齐全 ⇒ 不红）由 positive case 兜住 —— scaffold() 已铺齐
#          全部 9 个模块，少铺一个 positive 立刻红。
#
#          动手方式用 **mv 改名**而不是 rm 删除：① 守卫自己的报错文案就是
#          「（被改名或删除？）」，而真实事故里**改名**远比整目录消失常见 ——
#          改名正是本波要堵的形态；② 改名后的目录留在树里是惰性的（空目录，
#          不被任何一条守卫扫到），不污染判定；③ 不依赖 `rm`：受限环境里 rm 可能被
#          包装成拒绝带盘符路径（本机实测 `rm`/`rmdir` 均被 safe-delete 拦下），
#          而改名在任何 POSIX 环境都成立 —— 判据不该挂在会被环境拦掉的动作上。
# ---------------------------------------------------------------------------
if guard_selected ALL; then
d="$TMP/case13-module-missing"
scaffold "$d"
mv "$d/feature-settings" "$d/.feature-settings-renamed"
out="$(run_guard "$d")"; rc=$?
assert_red "case13 模块目录缺失 (前置断言·模块存在性)" "$rc" "$out" "模块存在性"
# case13b：红必须来自**真命中**（输出里能看到违规行本身 —— 且点名是哪一个模块），
#          而不是「守卫自身执行失败」—— 后者那行同样含守卫名，会让 assert_red 假绿
#          （与 case12b / case12d 同一范式）。
if printf '%s\n' "$out" | grep -qF "守卫命令自身执行失败"; then
  echo "FAIL [case13b] 模块存在性报的是「守卫命令自身执行失败」而非真命中（本 case 假绿）"
  printf '%s\n' "$out" | sed 's/^/    | /'
  FAIL=$((FAIL + 1))
elif printf '%s\n' "$out" | grep -qF "守卫目标目录不存在：feature-settings"; then
  echo "PASS [case13b] 红来自真命中（输出含违规行「守卫目标目录不存在：feature-settings」）"
  PASS=$((PASS + 1))
else
  echo "FAIL [case13b] 输出里看不到「守卫目标目录不存在：feature-settings」违规行，判据可疑"
  printf '%s\n' "$out" | sed 's/^/    | /'
  FAIL=$((FAIL + 1))
fi

fi
# ---------------------------------------------------------------------------
# case 14：全仓出现第 2 处 .setSink 调用 ⇒ 第 16 条（AgentLogStore.setSink 调用点数）
#          必须报红。setSink 是单槽覆盖式 API：第二个 sink 会静默顶掉第一个，
#          而唯一的 sink 挂着 ERROR 落盘（AgentLogFileStore.install KDoc 自认回退级
#          事故）。骨架默认 .setSink 调用数为 0（≤1 绿），本 case 注入 2 处调用 ⇒ n=2 红。
#          fixture 用**尾随 lambda** 形态 `AgentLogStore.setSink { … }` —— 这正是裁决稿
#          判据 `.setSink(`（带括号）在本仓 0 命中的原因（唯一真调用点
#          AgentLogFileStore.kt:88 就是这个形态），钉住修正后的判据
#          `\.setSink[[:space:]]*[( {]` 对尾随 lambda 的覆盖。
# case14b：红必须来自**真命中**（输出含「处 .setSink(」计数行），而不是「守卫命令
#          自身执行失败」—— 后者那行也含守卫名，会让 assert_red 假绿（与
#          case12b / case12d / case13b 同一范式）。
# ---------------------------------------------------------------------------
if guard_selected 16; then
d="$TMP/case14-second-sink"
scaffold "$d"
mkdir -p "$d/core-data/src/main/java/com/rickeal/agent/core/data"
cat > "$d/core-data/src/main/java/com/rickeal/agent/core/data/SinkThief.kt" <<'KT'
package com.rickeal.agent.core.data

fun stealSink() {
    AgentLogStore.setSink { _, _, _ -> }
    AgentLogStore.setSink { _, _, _ -> }
}
KT
out="$(run_guard "$d")"; rc=$?
assert_red "case14 第二个 setSink 调用 (第 16 条)" "$rc" "$out" \
  "AgentLogStore.setSink( 全仓只允许 1 处调用点（AgentLogFileStore.install，单槽覆盖式 API）"
if printf '%s\n' "$out" | grep -qF "守卫命令自身执行失败"; then
  echo "FAIL [case14b] 第 16 条报的是「守卫命令自身执行失败」而非真命中（本 case 假绿）"
  printf '%s\n' "$out" | sed 's/^/    | /'
  FAIL=$((FAIL + 1))
elif printf '%s\n' "$out" | grep -qF "处 .setSink("; then
  echo "PASS [case14b] 红来自真命中（输出含「处 .setSink(」计数行）"
  PASS=$((PASS + 1))
else
  echo "FAIL [case14b] 输出里看不到「处 .setSink(」计数行，判据可疑"
  printf '%s\n' "$out" | sed 's/^/    | /'
  FAIL=$((FAIL + 1))
fi

fi
# ---------------------------------------------------------------------------
# case 15：注入 2 处 ViewModel **直构造**（真代码行，非注释）⇒ 第 17 条
#          （ViewModel 直构造点全仓 ≤ 1）必须报红。骨架默认直构造数为 0（白名单
#          未注入，≤1 绿），本 case 注入 2 处 ⇒ n=2 红。
#          fixture 用裸 `new` 形态（无 viewModelFactory、非 class 声明行）——
#          钉住三层过滤全穿透：class 过滤器与 factory 关键字都救不了它。
# case15b：红必须来自**真命中**（输出含「处 ViewModel 直构造点」计数行），而不是
#          「守卫命令自身执行失败」—— 后者那行也含守卫名，会让 assert_red 假绿
#          （与 case12b / case13b / case14b 同一范式）。
# case15c：绿面 —— 1 处**单行 factory 形态**的构造 + 0 处直构造 ⇒ 必须不红。
#          钉死「正规路放行」：若 ② 的单行 factory 过滤失效（例如误把关键词过滤
#          写成只匹配文件名），本 case 会误红。绿面缺失会让第 17 条成为永远红的
#          僵尸规则（挂着正规路也过不了）。
# ---------------------------------------------------------------------------
if guard_selected 17; then
d="$TMP/case15-second-viewmodel"
scaffold "$d"
cat > "$d/feature-chat/src/main/java/com/rickeal/agent/feature/chat/RogueVm.kt" <<'KT'
package com.rickeal.agent.feature.chat

fun rogueConstruct(container: com.rickeal.agent.core.data.AppContainer): ChatViewModel {
    val a = ChatViewModel(container)
    val b = ChatViewModel(container)
    return if (a.hashCode() > b.hashCode()) a else b
}
KT
out="$(run_guard "$d")"; rc=$?
assert_red "case15 第二处 ViewModel 直构造 (第 17 条)" "$rc" "$out" \
  "ViewModel 直构造点全仓 ≤ 1（LiquidAgentApp.kt:378 白名单，正规路径须走 viewModelFactory）"
if printf '%s\n' "$out" | grep -qF "守卫命令自身执行失败"; then
  echo "FAIL [case15b] 第 17 条报的是「守卫命令自身执行失败」而非真命中（本 case 假绿）"
  printf '%s\n' "$out" | sed 's/^/    | /'
  FAIL=$((FAIL + 1))
elif printf '%s\n' "$out" | grep -qF "处 ViewModel 直构造点"; then
  echo "PASS [case15b] 红来自真命中（输出含「处 ViewModel 直构造点」计数行）"
  PASS=$((PASS + 1))
else
  echo "FAIL [case15b] 输出里看不到「处 ViewModel 直构造点」计数行，判据可疑"
  printf '%s\n' "$out" | sed 's/^/    | /'
  FAIL=$((FAIL + 1))
fi

d="$TMP/case15c-factory-form-green"
scaffold "$d"
mkdir -p "$d/feature-settings/src/main/java/com/rickeal/agent/feature/settings"
cat > "$d/feature-settings/src/main/java/com/rickeal/agent/feature/settings/LegitVm.kt" <<'KT'
package com.rickeal.agent.feature.settings

val legit = viewModel(factory = viewModelFactory { MemoryViewModel(container) })
KT
out="$(run_guard "$d")"; rc=$?
if [ "$rc" -eq 0 ]; then
  echo "PASS [case15c 单行 factory 形态 + 0 直构造 (第 17 条绿面)] 退出 0（未误红）"
  PASS=$((PASS + 1))
else
  echo "FAIL [case15c] 单行 viewModelFactory 正规构造被误红（第 17 条② 过滤失效）"
  printf '%s\n' "$out" | sed 's/^/    | /'
  FAIL=$((FAIL + 1))
fi

fi
# ---------------------------------------------------------------------------
# case 16：注入超阈的 ChatViewModel.kt（1705 行 > 1600）⇒ 第 18 条
#          （ChatViewModel.kt 总行数）必须报红。骨架默认只有 5 行（≤1600 绿），
#          本 case 用「类声明 + 参数行」灌到 1705 行。
# case16b：红必须来自**真命中**（输出含「超 1600 行」计数行），而不是「文件不存在
#          判守卫面失效」或「守卫命令自身执行失败」—— 前者同样含守卫名，会让
#          assert_red 假绿（fixture 没铺对路径时就是这种形态，与 case12b 范式一致）。
# ---------------------------------------------------------------------------
if guard_selected 18; then
d="$TMP/case16-viewmodel-oversize"
scaffold "$d"
{ echo 'package com.rickeal.agent.feature.chat'
  echo ''
  echo 'class ChatViewModel('
  echo '    val container: com.rickeal.agent.core.data.AppContainer,'
  local_i=1
  while [ "$local_i" -le 1700 ]; do echo "    val v$local_i: Int = $local_i,"; local_i=$((local_i + 1)); done
  echo ')'
} > "$d/feature-chat/src/main/java/com/rickeal/agent/feature/chat/ChatViewModel.kt"
out="$(run_guard "$d")"; rc=$?
assert_red "case16 ChatViewModel.kt 超行数上限 (第 18 条)" "$rc" "$out" \
  "ChatViewModel.kt 总行数 ≤ 1600（触顶 = 启动 onSend/onSendFrom 合并候选评审，非改阈值）"
if printf '%s\n' "$out" | grep -qF "守卫命令自身执行失败"; then
  echo "FAIL [case16b] 第 18 条报的是「守卫命令自身执行失败」而非真命中（本 case 假绿）"
  printf '%s\n' "$out" | sed 's/^/    | /'
  FAIL=$((FAIL + 1))
elif printf '%s\n' "$out" | grep -qF "超 1600 行上限"; then
  echo "PASS [case16b] 红来自真命中（输出含「超 1600 行上限」计数行，fixture 确被 wc -l 计到）"
  PASS=$((PASS + 1))
else
  echo "FAIL [case16b] 输出里看不到「超 1600 行上限」计数行，判据可疑"
  printf '%s\n' "$out" | sed 's/^/    | /'
  FAIL=$((FAIL + 1))
fi

fi
# ---------------------------------------------------------------------------
# case 17：第 18 条的目标文件 ChatViewModel.kt **缺失**（被改名/移动）⇒ 第 18 条必须
#          判红，且输出里能看到「文件不存在」这一违规事实本身。
#          动机（Wave 48 F1）：该分支原为 `echo …; exit 0`。现行 check() 的契约是
#          「stdout 非空 = 违规」⇒ 那句 echo 已使它判红；但 case16 只钉了「超行数」面，
#          **没有任何 case 钉住「文件缺失」面** —— 若后人删掉/改写那句 echo（例如把
#          说明挪到 stderr），该分支会静默放行而自测网依旧全绿（自测网的盲区）。
#          故本 case 补上：既钉「判红」，也钉「违规事实可见」（不只断言 rc≠0）。
#          制造缺失用 **mv 改名**而非 rm，且只在 $TMP 脚手架树内操作 —— 绝不碰生产文件
#          （与 case13 同一范式）。改名后的文件不以 .kt 结尾，不会被其它守卫扫到。
# case17b：红必须来自**真命中**（输出含「ChatViewModel.kt 不存在（被改名/删除？守卫面
#          已失效）」违规事实行），而不是「守卫命令自身执行失败」—— 后者那行也含守卫名，
#          会让 assert_red 假绿（与 case12b / case13b / case14b / case15b / case16b 同一范式）。
# ---------------------------------------------------------------------------
if guard_selected 18; then
d="$TMP/case17-chatvm-missing"
scaffold "$d"
mv "$d/feature-chat/src/main/java/com/rickeal/agent/feature/chat/ChatViewModel.kt" \
   "$d/feature-chat/src/main/java/com/rickeal/agent/feature/chat/.ChatViewModel.kt.renamed"
out="$(run_guard "$d")"; rc=$?
assert_red "case17 ChatViewModel.kt 缺失 (第 18 条守卫面失效)" "$rc" "$out" \
  "ChatViewModel.kt 总行数 ≤ 1600（触顶 = 启动 onSend/onSendFrom 合并候选评审，非改阈值）"
if printf '%s\n' "$out" | grep -qF "守卫命令自身执行失败"; then
  echo "FAIL [case17b] 第 18 条报的是「守卫命令自身执行失败」而非真命中（本 case 假绿）"
  printf '%s\n' "$out" | sed 's/^/    | /'
  FAIL=$((FAIL + 1))
elif printf '%s\n' "$out" | grep -qF "ChatViewModel.kt 不存在（被改名/删除？守卫面已失效）"; then
  echo "PASS [case17b] 红来自真命中（输出含「ChatViewModel.kt 不存在（被改名/删除？守卫面已失效）」违规事实行）"
  PASS=$((PASS + 1))
else
  echo "FAIL [case17b] 输出里看不到「ChatViewModel.kt 不存在」违规事实行，判据可疑"
  printf '%s\n' "$out" | sed 's/^/    | /'
  FAIL=$((FAIL + 1))
fi

fi
# ---------------------------------------------------------------------------
# case 18 / case 19：第 19 条（fulltest.sh --continue 纪律，Wave 49 R-B）两面：
#   case18 —— 临时把 scripts/fulltest.sh 改名（模拟「纪律文件被删/改名」）⇒ 判红，
#             并断言红来自真命中（输出含「scripts/fulltest.sh 不存在」违规事实行），
#             而非「守卫命令自身执行失败」。
#   case19 —— fulltest.sh 存在但**代码位不含 --continue**（模拟纪律被悄悄改回 fail-fast）
#             ⇒ 判红，断言输出含「未包含 --continue」违规事实行。
#             ⚠️ fixture 故意在**注释里**写「--continue」：钉住守卫判据是
#             `^[^#]*--continue`（只认行内第一个 `#` 之前的代码位），**注释里的提及
#             不得**让守卫假绿（本波首版用裸 `grep -F --continue` 就栽在这里 —— 注释
#             命中 ⇒ case19 假 FAIL）。
#   两面都只在 $TMP 脚手架树内操作（mv / 覆写），绝不碰生产文件。
# ---------------------------------------------------------------------------
if guard_selected 19; then
d="$TMP/case18-fulltest-missing"
scaffold "$d"
mv "$d/scripts/fulltest.sh" "$d/scripts/.fulltest.sh.renamed"
out="$(run_guard "$d")"; rc=$?
assert_red "case18 fulltest.sh 缺失 (第 19 条守卫面失效)" "$rc" "$out" \
  "fulltest.sh 存在且固定带 --continue（全量单测禁用 fail-fast 残缺口径）"
if printf '%s\n' "$out" | grep -qF "守卫命令自身执行失败"; then
  echo "FAIL [case18b] 第 19 条报的是「守卫命令自身执行失败」而非真命中（本 case 假绿）"
  printf '%s\n' "$out" | sed 's/^/    | /'
  FAIL=$((FAIL + 1))
elif printf '%s\n' "$out" | grep -qF "scripts/fulltest.sh 不存在"; then
  echo "PASS [case18b] 红来自真命中（输出含「scripts/fulltest.sh 不存在」违规事实行）"
  PASS=$((PASS + 1))
else
  echo "FAIL [case18b] 输出里看不到「scripts/fulltest.sh 不存在」违规事实行，判据可疑"
  printf '%s\n' "$out" | sed 's/^/    | /'
  FAIL=$((FAIL + 1))
fi

d="$TMP/case19-fulltest-nocontinue"
scaffold "$d"
cat > "$d/scripts/fulltest.sh" <<'SH'
#!/usr/bin/env bash
# 纪律被改回 fail-fast：注释里提到 --continue 但命令里没有（守卫必须忽略注释位）
set -uo pipefail
./gradlew test
SH
out="$(run_guard "$d")"; rc=$?
assert_red "case19 fulltest.sh 缺 --continue (第 19 条)" "$rc" "$out" \
  "fulltest.sh 存在且固定带 --continue（全量单测禁用 fail-fast 残缺口径）"
if printf '%s\n' "$out" | grep -qF "守卫命令自身执行失败"; then
  echo "FAIL [case19b] 第 19 条报的是「守卫命令自身执行失败」而非真命中（本 case 假绿）"
  printf '%s\n' "$out" | sed 's/^/    | /'
  FAIL=$((FAIL + 1))
elif printf '%s\n' "$out" | grep -qF "未包含 --continue"; then
  echo "PASS [case19b] 红来自真命中（输出含「未包含 --continue」违规事实行）"
  PASS=$((PASS + 1))
else
  echo "FAIL [case19b] 输出里看不到「未包含 --continue」违规事实行，判据可疑"
  printf '%s\n' "$out" | sed 's/^/    | /'
  FAIL=$((FAIL + 1))
fi

fi
# ---------------------------------------------------------------------------
# case 20 / case 21：第 20 条（ChatRunCoordinator.kt ≤ 1300，Wave 49 R-C）两面：
#   case20 —— 灌到超 1300 行 ⇒ 判红，断言红来自真命中（输出含「超 1300 行上限」计数行）。
#   case21 —— 目标文件缺失（mv 改名）⇒ 判红，断言输出含「不存在（被改名/删除？守卫面
#             已失效）」违规事实行。制造缺失用 mv 且只在 $TMP 内（与 case17 同范式）。
# ---------------------------------------------------------------------------
if guard_selected 20; then
d="$TMP/case20-coordinator-oversize"
scaffold "$d"
{ echo 'package com.rickeal.agent.feature.chat'
  echo ''
  echo 'class ChatRunCoordinator {'
  local_j=1
  while [ "$local_j" -le 1305 ]; do echo "    val c$local_j: Int = $local_j"; local_j=$((local_j + 1)); done
  echo '}'
} > "$d/feature-chat/src/main/java/com/rickeal/agent/feature/chat/ChatRunCoordinator.kt"
out="$(run_guard "$d")"; rc=$?
assert_red "case20 ChatRunCoordinator.kt 超行数上限 (第 20 条)" "$rc" "$out" \
  "ChatRunCoordinator.kt 总行数 ≤ 1300（feature-chat 两文件行数表，触顶 = 走 wave48-design 第二拆分候选，非改阈值）"
if printf '%s\n' "$out" | grep -qF "守卫命令自身执行失败"; then
  echo "FAIL [case20b] 第 20 条报的是「守卫命令自身执行失败」而非真命中（本 case 假绿）"
  printf '%s\n' "$out" | sed 's/^/    | /'
  FAIL=$((FAIL + 1))
elif printf '%s\n' "$out" | grep -qF "超 1300 行上限"; then
  echo "PASS [case20b] 红来自真命中（输出含「超 1300 行上限」计数行）"
  PASS=$((PASS + 1))
else
  echo "FAIL [case20b] 输出里看不到「超 1300 行上限」计数行，判据可疑"
  printf '%s\n' "$out" | sed 's/^/    | /'
  FAIL=$((FAIL + 1))
fi

d="$TMP/case21-coordinator-missing"
scaffold "$d"
mv "$d/feature-chat/src/main/java/com/rickeal/agent/feature/chat/ChatRunCoordinator.kt" \
   "$d/feature-chat/src/main/java/com/rickeal/agent/feature/chat/.ChatRunCoordinator.kt.renamed"
out="$(run_guard "$d")"; rc=$?
assert_red "case21 ChatRunCoordinator.kt 缺失 (第 20 条守卫面失效)" "$rc" "$out" \
  "ChatRunCoordinator.kt 总行数 ≤ 1300（feature-chat 两文件行数表，触顶 = 走 wave48-design 第二拆分候选，非改阈值）"
if printf '%s\n' "$out" | grep -qF "守卫命令自身执行失败"; then
  echo "FAIL [case21b] 第 20 条报的是「守卫命令自身执行失败」而非真命中（本 case 假绿）"
  printf '%s\n' "$out" | sed 's/^/    | /'
  FAIL=$((FAIL + 1))
elif printf '%s\n' "$out" | grep -qF "ChatRunCoordinator.kt 不存在（被改名/删除？守卫面已失效）"; then
  echo "PASS [case21b] 红来自真命中（输出含「ChatRunCoordinator.kt 不存在（被改名/删除？守卫面已失效）」违规事实行）"
  PASS=$((PASS + 1))
else
  echo "FAIL [case21b] 输出里看不到「ChatRunCoordinator.kt 不存在」违规事实行，判据可疑"
  printf '%s\n' "$out" | sed 's/^/    | /'
  FAIL=$((FAIL + 1))
fi

fi
# ---------------------------------------------------------------------------
# case 22 / case 23：第 21 条（CapabilitySource.USER 章印唯一写入路径，Wave 49 R-D）两面：
#   case22 —— 在**另一个生产文件**里再盖一次 USER 章 ⇒ 判红，断言输出含「出现在
#             ModelRepository.kt 之外」违规事实行（红来自真命中，非守卫命令自身失败）。
#   case23 —— 把骨架的 ModelRepository.kt 换成**不含章印**的版本（章印点消失）⇒ 判红，
#             断言输出含「找不到 … 章印点」违规事实行（守卫面失效必须判红，不静默绿）。
#   测试源集（src/test/）里的 CapabilitySource.USER 不判红：本波骨架不含 src/test，
#   且守卫显式 `grep -v "/src/test/"`；生产源码里的**读取/比较**（`== CapabilitySource.USER`）
#   也不判红 —— 判据只认赋值形态（`capabilitiesSource = CapabilitySource.USER`）。
# ---------------------------------------------------------------------------
if guard_selected 21; then
d="$TMP/case22-user-second-stamp"
scaffold "$d"
mkdir -p "$d/feature-models/src/main/java/com/rickeal/agent/feature/models"
cat > "$d/feature-models/src/main/java/com/rickeal/agent/feature/models/RogueCaps.kt" <<'KT'
package com.rickeal.agent.core.model

fun rogueStamp(d: Any) = d.let {
    it.copy(
        capabilitiesSource = CapabilitySource.USER,
    )
}
KT
out="$(run_guard "$d")"; rc=$?
assert_red "case22 第二处 USER 章印 (第 21 条)" "$rc" "$out" \
  "CapabilitySource.USER 章印唯一写入路径（仅 ModelRepository.setCapabilities，排除测试源集）"
if printf '%s\n' "$out" | grep -qF "守卫命令自身执行失败"; then
  echo "FAIL [case22b] 第 21 条报的是「守卫命令自身执行失败」而非真命中（本 case 假绿）"
  printf '%s\n' "$out" | sed 's/^/    | /'
  FAIL=$((FAIL + 1))
elif printf '%s\n' "$out" | grep -qF "出现在 ModelRepository.kt 之外"; then
  echo "PASS [case22b] 红来自真命中（输出含「出现在 ModelRepository.kt 之外」违规事实行）"
  PASS=$((PASS + 1))
else
  echo "FAIL [case22b] 输出里看不到「出现在 ModelRepository.kt 之外」违规事实行，判据可疑"
  printf '%s\n' "$out" | sed 's/^/    | /'
  FAIL=$((FAIL + 1))
fi

d="$TMP/case23-user-stamp-missing"
scaffold "$d"
cat > "$d/core-data/src/main/java/com/rickeal/agent/core/data/ModelRepository.kt" <<'KT'
package com.rickeal.agent.core.data

class ModelRepository {
    fun setCapabilities(id: String, capabilities: Any) = Unit
}
KT
out="$(run_guard "$d")"; rc=$?
assert_red "case23 USER 章印点消失 (第 21 条守卫面失效)" "$rc" "$out" \
  "CapabilitySource.USER 章印唯一写入路径（仅 ModelRepository.setCapabilities，排除测试源集）"
if printf '%s\n' "$out" | grep -qF "守卫命令自身执行失败"; then
  echo "FAIL [case23b] 第 21 条报的是「守卫命令自身执行失败」而非真命中（本 case 假绿）"
  printf '%s\n' "$out" | sed 's/^/    | /'
  FAIL=$((FAIL + 1))
elif printf '%s\n' "$out" | grep -qF "找不到 capabilitiesSource = CapabilitySource.USER 章印点"; then
  echo "PASS [case23b] 红来自真命中（输出含「找不到 … 章印点」违规事实行）"
  PASS=$((PASS + 1))
else
  echo "FAIL [case23b] 输出里看不到「找不到 … 章印点」违规事实行，判据可疑"
  printf '%s\n' "$out" | sed 's/^/    | /'
  FAIL=$((FAIL + 1))
fi

fi
# ---------------------------------------------------------------------------
# case 24 / case 25：第 22 条（fulltest.sh 含 --summary-only 子命令，Wave 50）两面：
#   case24 —— 把骨架的 fulltest.sh 覆写成**没有 --summary-only 分派臂**的版本 ⇒ 判红，
#             断言红来自真命中（输出含「未包含 --summary-only 子命令分派臂」违规事实行），
#             而非「守卫命令自身执行失败」。
#             ⚠️ fixture **故意在注释里**写「--summary-only」：钉住守卫判据是
#             `^[^#]*--summary-only[[:space:]]*\)`（只认代码位的 case 分派臂），
#             **注释里的提及不得**让守卫假绿（与 case19 同一范式 —— R-B 的教训）。
#   case25 —— fulltest.sh 含 --summary-only 分派臂 ⇒ 必须不红（绿面；否则第 22 条
#             会成为永远红的僵尸规则）。
#   两面都只在 $TMP 脚手架树内覆写，绝不碰生产文件。
# ---------------------------------------------------------------------------
if guard_selected 22; then
d="$TMP/case24-summaryonly-missing"
scaffold "$d"
cat > "$d/scripts/fulltest.sh" <<'SH'
#!/usr/bin/env bash
# 注释里提到 --summary-only 但 case 分派臂没有（守卫必须忽略注释位）
set -uo pipefail
./gradlew test --continue
SH
out="$(run_guard "$d")"; rc=$?
assert_red "case24 fulltest.sh 缺 --summary-only 分派臂 (第 22 条)" "$rc" "$out" \
  "fulltest.sh 含 --summary-only 子命令（build.yml 非阻断汇总步骤的依赖，缺失即静默失效）"
if printf '%s\n' "$out" | grep -qF "守卫命令自身执行失败"; then
  echo "FAIL [case24b] 第 22 条报的是「守卫命令自身执行失败」而非真命中（本 case 假绿）"
  printf '%s\n' "$out" | sed 's/^/    | /'
  FAIL=$((FAIL + 1))
elif printf '%s\n' "$out" | grep -qF "未包含 --summary-only 子命令分派臂"; then
  echo "PASS [case24b] 红来自真命中（输出含「未包含 --summary-only 子命令分派臂」违规事实行）"
  PASS=$((PASS + 1))
else
  echo "FAIL [case24b] 输出里看不到「未包含 --summary-only 子命令分派臂」违规事实行，判据可疑"
  printf '%s\n' "$out" | sed 's/^/    | /'
  FAIL=$((FAIL + 1))
fi

d="$TMP/case25-summaryonly-present"
scaffold "$d"
cat > "$d/scripts/fulltest.sh" <<'SH'
#!/usr/bin/env bash
set -uo pipefail
SUMMARY_ONLY=0
while [ $# -gt 0 ]; do
  case "$1" in
    --summary-only) SUMMARY_ONLY=1; shift ;;
    *) shift ;;
  esac
done
if [ "$SUMMARY_ONLY" -eq 0 ]; then
  "$GRADLE_CMD" test --continue
fi
if [ "$SUMMARY_ONLY" -eq 1 ]; then
  exit 0
fi
SH
out="$(run_guard "$d")"; rc=$?
if [ "$rc" -eq 0 ]; then
  echo "PASS [case25 fulltest.sh 含 --summary-only 分派臂 (第 22 条绿面)] 退出 0（未误红）"
  PASS=$((PASS + 1))
else
  echo "FAIL [case25] fulltest.sh 含 --summary-only 分派臂应不红，实际退出 $rc"
  printf '%s\n' "$out" | sed 's/^/    | /'
  FAIL=$((FAIL + 1))
fi

fi
# ---------------------------------------------------------------------------
# case 26 / case 27 / case 28：第 23 条（fulltest.sh 的 --summary-only 退出码契约，
# Wave 51）三面 —— 与第 22 条**互补**：#22 钉「分派臂存在」，本条钉「该分支恒 exit 0
# 且不经 exit 2」：
#   case26 —— gradle 解析块（含 `exit 2`）被移出 `SUMMARY_ONLY==0` 分支 ⇒ 判红，断言红
#             来自真命中（输出含「exit 2 未被 SUMMARY_ONLY==0 分支包住」违规事实行）。
#   case27 —— `SUMMARY_ONLY==1` 的早退载体（首语句 `exit 0`）被删 ⇒ 判红，断言输出含
#             「恒 exit 0 的早退载体缺失」违规事实行。
#   case28 —— 合规载体（exit 2 被 SUMMARY_ONLY==0 包住 + 早退 exit 0）⇒ 必须不红（绿面；
#             否则第 23 条会成为永远红的僵尸规则）。
#   ⚠️ case26 / case27 的 fixture **故意保留**注释里对 --summary-only / exit 0 的提及 ——
#   钉住判据只认代码位（与 case19 / case24 同一范式：裸 grep 会被注释骗成假绿）。
#   三面都只在 $TMP 脚手架树内覆写，绝不碰生产文件。
# ---------------------------------------------------------------------------
if guard_selected 23; then
d="$TMP/case26-exit2-unguarded"
scaffold "$d"
cat > "$d/scripts/fulltest.sh" <<'SH'
#!/usr/bin/env bash
# 注释里提到 --summary-only 的 exit 0 早退，但 exit 2 被移出了 SUMMARY_ONLY==0 分支（守卫必须判红）
set -uo pipefail
if [ -x ./gradlew ]; then
  GRADLE_CMD=./gradlew
elif command -v gradle >/dev/null 2>&1; then
  GRADLE_CMD=gradle
else
  echo "::error::找不到 gradle" >&2
  exit 2
fi
if [ "$SUMMARY_ONLY" -eq 1 ]; then
  exit 0
fi
SH
out="$(run_guard "$d")"; rc=$?
assert_red "case26 exit 2 未被 SUMMARY_ONLY==0 包住 (第 23 条)" "$rc" "$out" \
  "fulltest.sh 的 --summary-only 分支恒 exit 0 且不经 exit 2（非阻断汇总步骤的退出码契约）"
if printf '%s\n' "$out" | grep -qF "守卫命令自身执行失败"; then
  echo "FAIL [case26b] 第 23 条报的是「守卫命令自身执行失败」而非真命中（本 case 假绿）"
  printf '%s\n' "$out" | sed 's/^/    | /'
  FAIL=$((FAIL + 1))
elif printf '%s\n' "$out" | grep -qF "exit 2 未被 SUMMARY_ONLY==0 分支包住"; then
  echo "PASS [case26b] 红来自真命中（输出含「exit 2 未被 SUMMARY_ONLY==0 分支包住」违规事实行）"
  PASS=$((PASS + 1))
else
  echo "FAIL [case26b] 输出里看不到「exit 2 未被 … 包住」违规事实行，判据可疑"
  printf '%s\n' "$out" | sed 's/^/    | /'
  FAIL=$((FAIL + 1))
fi

d="$TMP/case27-early-return-missing"
scaffold "$d"
cat > "$d/scripts/fulltest.sh" <<'SH'
#!/usr/bin/env bash
# 注释里提到 --summary-only 的 exit 0 早退，但代码里没有（守卫必须判红）
set -uo pipefail
SUMMARY_ONLY=0
while [ $# -gt 0 ]; do
  case "$1" in
    --summary-only) SUMMARY_ONLY=1; shift ;;
    *) shift ;;
  esac
done
if [ "$SUMMARY_ONLY" -eq 0 ]; then
  if [ -x ./gradlew ]; then
    GRADLE_CMD=./gradlew
  else
    echo "::error::找不到 gradle" >&2
    exit 2
  fi
  "$GRADLE_CMD" test --continue
fi
exit 0
SH
out="$(run_guard "$d")"; rc=$?
assert_red "case27 --summary-only 早退载体缺失 (第 23 条)" "$rc" "$out" \
  "fulltest.sh 的 --summary-only 分支恒 exit 0 且不经 exit 2（非阻断汇总步骤的退出码契约）"
if printf '%s\n' "$out" | grep -qF "守卫命令自身执行失败"; then
  echo "FAIL [case27b] 第 23 条报的是「守卫命令自身执行失败」而非真命中（本 case 假绿）"
  printf '%s\n' "$out" | sed 's/^/    | /'
  FAIL=$((FAIL + 1))
elif printf '%s\n' "$out" | grep -qF "恒 exit 0 的早退载体缺失"; then
  echo "PASS [case27b] 红来自真命中（输出含「恒 exit 0 的早退载体缺失」违规事实行）"
  PASS=$((PASS + 1))
else
  echo "FAIL [case27b] 输出里看不到「恒 exit 0 的早退载体缺失」违规事实行，判据可疑"
  printf '%s\n' "$out" | sed 's/^/    | /'
  FAIL=$((FAIL + 1))
fi

d="$TMP/case28-summary-only-contract-ok"
scaffold "$d"
cat > "$d/scripts/fulltest.sh" <<'SH'
#!/usr/bin/env bash
set -uo pipefail
SUMMARY_ONLY=0
while [ $# -gt 0 ]; do
  case "$1" in
    --summary-only) SUMMARY_ONLY=1; shift ;;
    *) shift ;;
  esac
done
if [ "$SUMMARY_ONLY" -eq 0 ]; then
  if [ -x ./gradlew ]; then
    GRADLE_CMD=./gradlew
  else
    echo "::error::找不到 gradle" >&2
    exit 2
  fi
  "$GRADLE_CMD" test --continue
fi
if [ "$SUMMARY_ONLY" -eq 1 ]; then
  exit 0
fi
exit 0
SH
out="$(run_guard "$d")"; rc=$?
if [ "$rc" -eq 0 ]; then
  echo "PASS [case28 合规 --summary-only 契约 (第 23 条绿面)] 退出 0（未误红）"
  PASS=$((PASS + 1))
else
  echo "FAIL [case28] 合规载体应不红，实际退出 $rc"
  printf '%s\n' "$out" | sed 's/^/    | /'
  FAIL=$((FAIL + 1))
fi

fi
# ---------------------------------------------------------------------------
# case 29 / case 30：第 24 条（LiteRtLmEngine.kt fold 收口不变式，Wave 52）两面：
#   case29 —— 追加第 2 处 **未包 fold** 的 `Message.user(Contents.of(x))` ⇒
#             msg_n=2 != fold_n=1 ⇒ 判红，断言红来自真命中（输出含「!= Message.user 下发点数」
#             违规事实行），而非「守卫命令自身执行失败」。
#             骨架 fixture 默认 1 处 user + 1 处 fold（1==1 绿）；本 case 造出不等 ⇒ 红。
#   case30 —— 只在 **KDoc/注释** 里提 `Message.user(contents)` ⇒ 必须**仍绿**（防被注释骗，
#             沿 #19/#22 教训）：注释位被 `^[0-9]+:[[:space:]]*[*/]` 排除 ⇒ msg_n 不变。
#   两面都只在 $TMP 脚手架树内追加，绝不碰生产文件。
# ---------------------------------------------------------------------------
if guard_selected 24; then
d="$TMP/case29-fold-unbalanced"
scaffold "$d"
cat >> "$d/core-engine/src/main/java/com/rickeal/agent/core/engine/local/LiteRtLmEngine.kt" <<'KT'

fun downlinkUnfolded(x: List<Content>): Message =
    Message.user(Contents.of(x))
KT
out="$(run_guard "$d")"; rc=$?
assert_red "case29 第 2 处 Message.user 未包 fold (第 24 条)" "$rc" "$out" \
  "LiteRtLmEngine.kt fold 收口不变式（Message.user 下发点数 == foldAdjacentText 调用数，新增下发点必须包 fold）"
if printf '%s\n' "$out" | grep -qF "守卫命令自身执行失败"; then
  echo "FAIL [case29b] 第 24 条报的是「守卫命令自身执行失败」而非真命中（本 case 假绿）"
  printf '%s\n' "$out" | sed 's/^/    | /'
  FAIL=$((FAIL + 1))
elif printf '%s\n' "$out" | grep -qF "!= Message.user 下发点数"; then
  echo "PASS [case29b] 红来自真命中（输出含「!= Message.user 下发点数」违规事实行）"
  PASS=$((PASS + 1))
else
  echo "FAIL [case29b] 输出里看不到「!= Message.user 下发点数」违规事实行，判据可疑"
  printf '%s\n' "$out" | sed 's/^/    | /'
  FAIL=$((FAIL + 1))
fi

d="$TMP/case30-fold-kdoc-green"
scaffold "$d"
cat >> "$d/core-engine/src/main/java/com/rickeal/agent/core/engine/local/LiteRtLmEngine.kt" <<'KT'

/**
 * 说明：历史上曾用 `Message.user(contents)` 直接下发（未包 fold），W51 已收口。
 * 注意 `Message.user(` 出现在 KDoc 里，不得被守卫计入下发点数。
 */
val kdocOnlyNote = 1
KT
out="$(run_guard "$d")"; rc=$?
if [ "$rc" -eq 0 ]; then
  echo "PASS [case30 KDoc 提及 Message.user( (第 24 条绿面)] 退出 0（注释位被排除，未误红）"
  PASS=$((PASS + 1))
else
  echo "FAIL [case30] KDoc 里的 Message.user( 被误计入下发点数（第 24 条判据未排除注释位 ⇒ 会被注释骗）"
  printf '%s\n' "$out" | sed 's/^/    | /'
  FAIL=$((FAIL + 1))
fi

fi
# ---------------------------------------------------------------------------
# case 31 / case 32：第 25 条（LiteRtLmEngine.kt ≤ 2400，Wave 52）两面：
#   case31 —— 灌到超 2400 行 ⇒ 判红，断言红来自真命中（输出含「超 2400 行上限」计数行）。
#   case32 —— 目标文件缺失（mv 改名）⇒ 判红，断言输出含「不存在（被改名/删除？守卫面
#             已失效）」违规事实行。制造缺失用 mv 且只在 $TMP 内（与 case17/case21 同范式）。
# ---------------------------------------------------------------------------
if guard_selected 25; then
d="$TMP/case31-engine-oversize"
scaffold "$d"
{ echo 'package com.rickeal.agent.core.engine.local'
  echo ''
  echo 'internal fun foldAdjacentText(contents: List<Content>): List<Content> = contents'
  echo ''
  echo 'fun downlink(fresh: List<Content>): Message ='
  echo '    Message.user(Contents.of(foldAdjacentText(fresh)))'
  local_k=1
  while [ "$local_k" -le 2400 ]; do echo "val v$local_k = $local_k"; local_k=$((local_k + 1)); done
} > "$d/core-engine/src/main/java/com/rickeal/agent/core/engine/local/LiteRtLmEngine.kt"
out="$(run_guard "$d")"; rc=$?
assert_red "case31 LiteRtLmEngine.kt 超行数上限 (第 25 条)" "$rc" "$out" \
  "LiteRtLmEngine.kt 总行数 ≤ 2400（core-engine god-file 行数表，触顶 = 启动拆分评审，非改阈值）"
if printf '%s\n' "$out" | grep -qF "守卫命令自身执行失败"; then
  echo "FAIL [case31b] 第 25 条报的是「守卫命令自身执行失败」而非真命中（本 case 假绿）"
  printf '%s\n' "$out" | sed 's/^/    | /'
  FAIL=$((FAIL + 1))
elif printf '%s\n' "$out" | grep -qF "超 2400 行上限"; then
  echo "PASS [case31b] 红来自真命中（输出含「超 2400 行上限」计数行）"
  PASS=$((PASS + 1))
else
  echo "FAIL [case31b] 输出里看不到「超 2400 行上限」计数行，判据可疑"
  printf '%s\n' "$out" | sed 's/^/    | /'
  FAIL=$((FAIL + 1))
fi

d="$TMP/case32-engine-missing"
scaffold "$d"
mv "$d/core-engine/src/main/java/com/rickeal/agent/core/engine/local/LiteRtLmEngine.kt" \
   "$d/core-engine/src/main/java/com/rickeal/agent/core/engine/local/.LiteRtLmEngine.kt.renamed"
out="$(run_guard "$d")"; rc=$?
assert_red "case32 LiteRtLmEngine.kt 缺失 (第 25 条守卫面失效)" "$rc" "$out" \
  "LiteRtLmEngine.kt 总行数 ≤ 2400（core-engine god-file 行数表，触顶 = 启动拆分评审，非改阈值）"
if printf '%s\n' "$out" | grep -qF "守卫命令自身执行失败"; then
  echo "FAIL [case32b] 第 25 条报的是「守卫命令自身执行失败」而非真命中（本 case 假绿）"
  printf '%s\n' "$out" | sed 's/^/    | /'
  FAIL=$((FAIL + 1))
elif printf '%s\n' "$out" | grep -qF "LiteRtLmEngine.kt 不存在（被改名/删除？守卫面已失效）"; then
  echo "PASS [case32b] 红来自真命中（输出含「LiteRtLmEngine.kt 不存在（被改名/删除？守卫面已失效）」违规事实行）"
  PASS=$((PASS + 1))
else
  echo "FAIL [case32b] 输出里看不到「LiteRtLmEngine.kt 不存在」违规事实行，判据可疑"
  printf '%s\n' "$out" | sed 's/^/    | /'
  FAIL=$((FAIL + 1))
fi

fi
# ---------------------------------------------------------------------------
# case 33 / case 34：第 26 条（测试基线双向同步，Wave 53）两面：
#   case33 —— 把 build.yml 的 baseline 改小（3 → 2）⇒ 实数(3) > 基线(2) ⇒「滞后」判红，
#             断言红来自真命中（输出含「滞后 1 例」违规事实行），而非「守卫命令自身执行失败」。
#   case34 —— 删掉一条 @Test 载体（3 → 2）⇒ 实数(2) < 基线(3) ⇒「删例」判红，
#             断言输出含「少 1 例」违规事实行。
#   两面都只在 $TMP 脚手架树内改（sed / 覆写），绝不碰生产文件。
#   骨架默认 build.yml `baseline=3` 且恰有 3 条 @Test ⇒ 相等（绿），由 positive case 兜住。
# ---------------------------------------------------------------------------
if guard_selected 26; then
d="$TMP/case33-baseline-lag"
scaffold "$d"
sed -i 's/baseline=3/baseline=2/' "$d/.github/workflows/build.yml"
out="$(run_guard "$d")"; rc=$?
assert_red "case33 build.yml 基线滞后 (第 26 条)" "$rc" "$out" \
  "测试基线双向同步（build.yml baseline == 全仓 @Test 代码位机械实数）"
if printf '%s\n' "$out" | grep -qF "守卫命令自身执行失败"; then
  echo "FAIL [case33b] 第 26 条报的是「守卫命令自身执行失败」而非真命中（本 case 假绿）"
  printf '%s\n' "$out" | sed 's/^/    | /'
  FAIL=$((FAIL + 1))
elif printf '%s\n' "$out" | grep -qF "滞后 1 例"; then
  echo "PASS [case33b] 红来自真命中（输出含「滞后 1 例」违规事实行）"
  PASS=$((PASS + 1))
else
  echo "FAIL [case33b] 输出里看不到「滞后 1 例」违规事实行，判据可疑"
  printf '%s\n' "$out" | sed 's/^/    | /'
  FAIL=$((FAIL + 1))
fi

d="$TMP/case34-test-deleted"
scaffold "$d"
cat > "$d/core-model/src/test/java/com/x/BaselineTest.kt" <<'KT'
package com.x

import kotlin.test.Test
import kotlin.test.assertTrue

class BaselineTest {
    @Test
    fun caseOne() {
        assertTrue(true)
    }

    @Test
    fun caseTwo() {
        assertTrue(true)
    }
}
KT
out="$(run_guard "$d")"; rc=$?
assert_red "case34 删一条 @Test 未同步基线 (第 26 条)" "$rc" "$out" \
  "测试基线双向同步（build.yml baseline == 全仓 @Test 代码位机械实数）"
if printf '%s\n' "$out" | grep -qF "守卫命令自身执行失败"; then
  echo "FAIL [case34b] 第 26 条报的是「守卫命令自身执行失败」而非真命中（本 case 假绿）"
  printf '%s\n' "$out" | sed 's/^/    | /'
  FAIL=$((FAIL + 1))
elif printf '%s\n' "$out" | grep -qF "少 1 例"; then
  echo "PASS [case34b] 红来自真命中（输出含「少 1 例」违规事实行）"
  PASS=$((PASS + 1))
else
  echo "FAIL [case34b] 输出里看不到「少 1 例」违规事实行，判据可疑"
  printf '%s\n' "$out" | sed 's/^/    | /'
  FAIL=$((FAIL + 1))
fi

fi
# ---------------------------------------------------------------------------
# case 35：第 27 条（A5 台账聚合句守卫，Wave 53）红面 —— 把 README 聚合句的总数
#         从「累计 3 条」改成「累计 4 条」⇒ 与 §11.0.1 逐条台账（3 条）不等 ⇒ 判红，
#         断言红来自真命中（输出含「聚合句与 §11.0.1 逐条台账不一致」违规事实行）。
#         骨架默认两份聚合句均与台账（1 ✅ / 1 ⚠️ / 1 ⛔）一致 ⇒ 绿（由 positive case 兜住）。
#         只在 $TMP 脚手架树内 sed，绝不碰生产文件。
# ---------------------------------------------------------------------------
if guard_selected 27; then
d="$TMP/case35-ledger-aggregate-drift"
scaffold "$d"
sed -i 's/累计 3 条/累计 4 条/' "$d/README.md"
out="$(run_guard "$d")"; rc=$?
assert_red "case35 README 聚合句与台账不一致 (第 27 条)" "$rc" "$out" \
  "A5 台账聚合句与 §11.0.1 逐条三态一致（README / docs 聚合句 == 台账实数）"
if printf '%s\n' "$out" | grep -qF "守卫命令自身执行失败"; then
  echo "FAIL [case35b] 第 27 条报的是「守卫命令自身执行失败」而非真命中（本 case 假绿）"
  printf '%s\n' "$out" | sed 's/^/    | /'
  FAIL=$((FAIL + 1))
elif printf '%s\n' "$out" | grep -qF "聚合句与 §11.0.1 逐条台账不一致"; then
  echo "PASS [case35b] 红来自真命中（输出含「聚合句与 §11.0.1 逐条台账不一致」违规事实行）"
  PASS=$((PASS + 1))
else
  echo "FAIL [case35b] 输出里看不到「聚合句与 §11.0.1 逐条台账不一致」违规事实行，判据可疑"
  printf '%s\n' "$out" | sed 's/^/    | /'
  FAIL=$((FAIL + 1))
fi

fi
# ---------------------------------------------------------------------------
# case 36 / case 37 / case 38：第 28 条（LiteRtLmEngine.kt 韧性 store 双写双读接线，
# Wave 57）三面：
#   case36 —— 从脚手架 fixture 删掉 `adoptResilienceFromStore(` **调用**（保留 def 行）
#             ⇒ adopt_n=0 ⇒ 判红；断言红来自真命中（输出含「adoptResilienceFromStore(
#             调用数(0) != 1」违规事实行），而非「守卫命令自身执行失败」。
#             ⚠️ 删的是**调用**、def 行仍在 —— 正好钉住「def 行被 `grep -v "fun …"` 排除、
#             只有真调用计入」这一判据（若排除失效，本 case 会因 adopt_n=1 而漏判）。
#             ⚠️ W58：fixture 保留写点①②两处 persist（persist_n=2）⇒ 本 case 的红**只**
#             来自判据①（adopt），不混入判据②（若 fixture 漏补写点①，会多出一条
#             persist 红线 —— 仍是红，但触发面失真）。
#   case37 —— 行序颠倒（把 `nativeToolChannelActive()` 调用挪到 adopt 调用**之前**）
#             ⇒ 判据③ 触发 ⇒ 判红；断言输出含「读回必须先于通道判定」违规事实行。
#   case38 —— 脚手架 fixture 原样（含 adopt 调用 1 + persist 调用 2 + `nativeToolChannelActive()`
#             在 adopt 之后）⇒ 必须**不红**（绿面；否则第 28 条会成为永远红的僵尸规则）；
#             并追加一段 **KDoc 提及** `adoptResilienceFromStore(` / `persistResilienceToStore(`
#             / `nativeToolChannelActive()` ⇒ 必须**仍绿**：钉死判据只认代码位（注释位被
#             `grep -vE "^[0-9]+:[[:space:]]*[*/]"` 排除，与 #24 同款）。若不排除注释位，
#             判据③ 会把真实文件头类 KDoc 那种提及当锚点 ⇒ **恒红**（本 case 正是防它）。
#   三面都只在 $TMP 脚手架树内覆写 fixture，绝不碰生产文件。
#   绿面（脚手架原样 → exit 0）同时由 positive case 兜住。
# ---------------------------------------------------------------------------
if guard_selected 28; then
d="$TMP/case36-adopt-call-missing"
scaffold "$d"
cat > "$d/core-engine/src/main/java/com/rickeal/agent/core/engine/local/LiteRtLmEngine.kt" <<'KT'
package com.rickeal.agent.core.engine.local

internal fun foldAdjacentText(contents: List<Content>): List<Content> = contents

fun downlink(fresh: List<Content>): Message =
    Message.user(Contents.of(foldAdjacentText(fresh)))

class LiteRtLmEngine {
    fun ensureConversation(cid: String?) {
        persistResilienceToStore()
        val nativeToolsActive = nativeToolChannelActive()
    }

    fun handleTemplateRenderFailure() {
        persistResilienceToStore()
    }

    private fun adoptResilienceFromStore(cid: String?) = Unit

    private fun persistResilienceToStore() = Unit

    private fun nativeToolChannelActive(): Boolean = true
}
KT
out="$(run_guard "$d")"; rc=$?
assert_red "case36 删掉 adopt 调用 (第 28 条)" "$rc" "$out" \
  "LiteRtLmEngine.kt 韧性 store 双写双读接线（adopt 调用点 1、persist 调用点 2，且读回先于通道判定）"
if printf '%s\n' "$out" | grep -qF "守卫命令自身执行失败"; then
  echo "FAIL [case36b] 第 28 条报的是「守卫命令自身执行失败」而非真命中（本 case 假绿）"
  printf '%s\n' "$out" | sed 's/^/    | /'
  FAIL=$((FAIL + 1))
elif printf '%s\n' "$out" | grep -qF "adoptResilienceFromStore( 调用数(0) != 1"; then
  echo "PASS [case36b] 红来自真命中（输出含「adoptResilienceFromStore( 调用数(0) != 1」违规事实行）"
  PASS=$((PASS + 1))
else
  echo "FAIL [case36b] 输出里看不到「adoptResilienceFromStore( 调用数(0) != 1」违规事实行，判据可疑"
  printf '%s\n' "$out" | sed 's/^/    | /'
  FAIL=$((FAIL + 1))
fi

d="$TMP/case37-readback-order-reversed"
scaffold "$d"
cat > "$d/core-engine/src/main/java/com/rickeal/agent/core/engine/local/LiteRtLmEngine.kt" <<'KT'
package com.rickeal.agent.core.engine.local

internal fun foldAdjacentText(contents: List<Content>): List<Content> = contents

fun downlink(fresh: List<Content>): Message =
    Message.user(Contents.of(foldAdjacentText(fresh)))

class LiteRtLmEngine {
    fun ensureConversation(cid: String?) {
        val nativeToolsActive = nativeToolChannelActive()
        adoptResilienceFromStore(cid)
        persistResilienceToStore()
    }

    fun handleTemplateRenderFailure() {
        persistResilienceToStore()
    }

    private fun adoptResilienceFromStore(cid: String?) = Unit

    private fun persistResilienceToStore() = Unit

    private fun nativeToolChannelActive(): Boolean = true
}
KT
out="$(run_guard "$d")"; rc=$?
assert_red "case37 读回点晚于通道判定 (第 28 条判据③)" "$rc" "$out" \
  "LiteRtLmEngine.kt 韧性 store 双写双读接线（adopt 调用点 1、persist 调用点 2，且读回先于通道判定）"
if printf '%s\n' "$out" | grep -qF "守卫命令自身执行失败"; then
  echo "FAIL [case37b] 第 28 条报的是「守卫命令自身执行失败」而非真命中（本 case 假绿）"
  printf '%s\n' "$out" | sed 's/^/    | /'
  FAIL=$((FAIL + 1))
elif printf '%s\n' "$out" | grep -qF "读回必须先于通道判定"; then
  echo "PASS [case37b] 红来自真命中（输出含「读回必须先于通道判定」违规事实行）"
  PASS=$((PASS + 1))
else
  echo "FAIL [case37b] 输出里看不到「读回必须先于通道判定」违规事实行，判据可疑"
  printf '%s\n' "$out" | sed 's/^/    | /'
  FAIL=$((FAIL + 1))
fi

d="$TMP/case38-wiring-comment-green"
scaffold "$d"
cat >> "$d/core-engine/src/main/java/com/rickeal/agent/core/engine/local/LiteRtLmEngine.kt" <<'KT'

/**
 * 说明（Wave 57）：KDoc 里提及 `adoptResilienceFromStore(` 与 `persistResilienceToStore(`
 * 以及 `nativeToolChannelActive()` 都**不得**被第 28 条计入调用数 / 当作读回顺序锚点 ——
 * 否则会被注释骗成假红（与 #24 的 KDoc 排除同款）。本段落须让守卫**仍绿**。
 */
val wiringKdocNote = 1
KT
out="$(run_guard "$d")"; rc=$?
if [ "$rc" -eq 0 ]; then
  echo "PASS [case38 接线齐备 + KDoc 提及 (第 28 条绿面)] 退出 0（注释位被排除，未误红）"
  PASS=$((PASS + 1))
else
  echo "FAIL [case38] KDoc 里的 adopt/persist/nativeToolChannelActive 提及被误计入（第 28 条判据未排除注释位 ⇒ 会被注释骗）"
  printf '%s\n' "$out" | sed 's/^/    | /'
  FAIL=$((FAIL + 1))
fi

fi
# ---------------------------------------------------------------------------
# case 40 / case 40b：第 28 条判据② 的**独立红面**（W58 修补 A 同步）——
#   case40 —— 从脚手架 fixture 只删掉 **写点①**（ensureConversation 成功路径）的
#             `persistResilienceToStore()`，**保留**写点②（handleTemplateRenderFailure
#             收尾）⇒ persist_n=1 != 2 ⇒ 判红。这正是 W58 修补 A 的主修面：
#             建会话期的证伪置位（证伪重试 / legacy 连带）不再落 store，单调计数
#             跨建会话失真 —— 开发期单轮测不出，只有守卫拦得住。
#             adopt 仍 1 处、读回顺序不变 ⇒ 红**只**来自判据②（触发面纯净）。
#   case40b —— 红必须来自**真命中**（输出含「persistResilienceToStore( 调用数(1) != 2」
#             违规事实行），而非「守卫命令自身执行失败」（与 case36b / case37b 同一范式）。
#   两面都只在 $TMP 脚手架树内覆写 fixture，绝不碰生产文件。
# ---------------------------------------------------------------------------
if guard_selected 28; then
d="$TMP/case40-persist-writer1-missing"
scaffold "$d"
cat > "$d/core-engine/src/main/java/com/rickeal/agent/core/engine/local/LiteRtLmEngine.kt" <<'KT'
package com.rickeal.agent.core.engine.local

internal fun foldAdjacentText(contents: List<Content>): List<Content> = contents

fun downlink(fresh: List<Content>): Message =
    Message.user(Contents.of(foldAdjacentText(fresh)))

class LiteRtLmEngine {
    fun ensureConversation(cid: String?) {
        adoptResilienceFromStore(cid)
        val nativeToolsActive = nativeToolChannelActive()
    }

    fun handleTemplateRenderFailure() {
        persistResilienceToStore()
    }

    private fun adoptResilienceFromStore(cid: String?) = Unit

    private fun persistResilienceToStore() = Unit

    private fun nativeToolChannelActive(): Boolean = true
}
KT
out="$(run_guard "$d")"; rc=$?
assert_red "case40 只删写点①建会话侧 persist (第 28 条判据②)" "$rc" "$out" \
  "LiteRtLmEngine.kt 韧性 store 双写双读接线（adopt 调用点 1、persist 调用点 2，且读回先于通道判定）"
if printf '%s\n' "$out" | grep -qF "守卫命令自身执行失败"; then
  echo "FAIL [case40b] 第 28 条报的是「守卫命令自身执行失败」而非真命中（本 case 假绿）"
  printf '%s\n' "$out" | sed 's/^/    | /'
  FAIL=$((FAIL + 1))
elif printf '%s\n' "$out" | grep -qF "persistResilienceToStore( 调用数(1) != 2"; then
  echo "PASS [case40b] 红来自真命中（输出含「persistResilienceToStore( 调用数(1) != 2」违规事实行）"
  PASS=$((PASS + 1))
else
  echo "FAIL [case40b] 输出里看不到「persistResilienceToStore( 调用数(1) != 2」违规事实行，判据可疑"
  printf '%s\n' "$out" | sed 's/^/    | /'
  FAIL=$((FAIL + 1))
fi

fi
# ---------------------------------------------------------------------------
# case 39 / case 39b / case 39c / case 39d / case 39e：第 29 条（litertlm 版本钉死
# 0.17.1，W58 治外部审查 F-1 P0）三红一绿：
#   case39 —— sed 把版本 0.17.1 → 0.18.0 ⇒ 判红（版本漂移 = F-1 主修面）；
#             断言红来自真命中（输出含「收敛点已删」后果摘要行），而非「守卫命令
#             自身执行失败」。
#   case39c —— toml 文件缺失（mv 改名，与 case17/case21/case32 同范式 —— 不用 rm，
#             受限环境里 rm 可能被包装拦下）⇒ fail-closed 判红；
#             断言输出含「版本目录蒸发」违规事实行（case39d）。
#   case39e —— 绿面：[libraries] 段的相似行 `litertlm-android = …` 与**注释里**的
#             `litertlm = "0.18.0"` 提及 ⇒ 必须**仍绿**（行首锚只认代码位、只钉取值；
#             若锚误伤相似行/注释，正向 case 之外的第二绿面就没了）。
#   骨架默认 toml 原样 0.17.1 ⇒ 绿（由 positive case 兜住）。
#   各面都只在 $TMP 脚手架树内改（sed / mv / 追加），绝不碰生产文件。
# ---------------------------------------------------------------------------
if guard_selected 29; then
d="$TMP/case39-litertlm-bumped"
scaffold "$d"
sed -i 's/0\.17\.1/0.18.0/' "$d/gradle/libs.versions.toml"
out="$(run_guard "$d")"; rc=$?
assert_red "case39 litertlm 版本漂移 (第 29 条)" "$rc" "$out" \
  "litertlm 版本必须为 0.17.1（H-A 收敛语义兼容；bump 前必读四前置+前置⑤）"
if printf '%s\n' "$out" | grep -qF "守卫命令自身执行失败"; then
  echo "FAIL [case39b] 第 29 条报的是「守卫命令自身执行失败」而非真命中（本 case 假绿）"
  printf '%s\n' "$out" | sed 's/^/    | /'
  FAIL=$((FAIL + 1))
elif printf '%s\n' "$out" | grep -qF "收敛点已删"; then
  echo "PASS [case39b] 红来自真命中（输出含「收敛点已删」后果摘要行，非守卫自身故障）"
  PASS=$((PASS + 1))
else
  echo "FAIL [case39b] 输出里看不到「收敛点已删」后果摘要行，判据可疑"
  printf '%s\n' "$out" | sed 's/^/    | /'
  FAIL=$((FAIL + 1))
fi

d="$TMP/case39c-litertlm-toml-missing"
scaffold "$d"
mv "$d/gradle/libs.versions.toml" "$d/gradle/.libs.versions.toml.renamed"
out="$(run_guard "$d")"; rc=$?
assert_red "case39c libs.versions.toml 缺失 (第 29 条守卫面失效)" "$rc" "$out" \
  "litertlm 版本必须为 0.17.1（H-A 收敛语义兼容；bump 前必读四前置+前置⑤）"
if printf '%s\n' "$out" | grep -qF "守卫命令自身执行失败"; then
  echo "FAIL [case39d] 第 29 条报的是「守卫命令自身执行失败」而非真命中（本 case 假绿）"
  printf '%s\n' "$out" | sed 's/^/    | /'
  FAIL=$((FAIL + 1))
elif printf '%s\n' "$out" | grep -qF "版本目录蒸发"; then
  echo "PASS [case39d] 红来自真命中（输出含「版本目录蒸发」违规事实行，fail-closed 生效）"
  PASS=$((PASS + 1))
else
  echo "FAIL [case39d] 输出里看不到「版本目录蒸发」违规事实行，判据可疑"
  printf '%s\n' "$out" | sed 's/^/    | /'
  FAIL=$((FAIL + 1))
fi

d="$TMP/case39e-litertlm-anchor-green"
scaffold "$d"
cat >> "$d/gradle/libs.versions.toml" <<'TOML'

[libraries]
litertlm-android = "0.17.1"
# 历史版本沿革注释：litertlm = "0.18.0"（注释里的提及不得命中行首锚，防被注释骗成假红）
TOML
out="$(run_guard "$d")"; rc=$?
if [ "$rc" -eq 0 ]; then
  echo "PASS [case39e 相似行 + 注释提及 (第 29 条绿面)] 退出 0（行首锚只认代码位取值，未误红）"
  PASS=$((PASS + 1))
else
  echo "FAIL [case39e] [libraries] 相似行 / 注释里的版本提及被误判（第 29 条锚未排除非代码位 ⇒ 假红）"
  printf '%s\n' "$out" | sed 's/^/    | /'
  FAIL=$((FAIL + 1))
fi

fi
# ---------------------------------------------------------------------------
# case 41 / 41b / 41c：第 30 条（ChatRunCoordinator run 级 model 读点冻结，W59）两面：
#   case41 —— 从脚手架 fixture 删 1 处 `model = uiState.value.activeModel`（3 → 2）
#             ⇒ 计数 2 != 3 ⇒ 判红；case41b 断言红来自真命中（输出含
#             「取值点(实数 2) != 3」违规事实行），而非「守卫命令自身执行失败」。
#   case41c —— 脚手架原样（恰 3 处）⇒ 必须不红（绿面；独立钉死 scaffold 铺点数与
#             判据 ==3 的同步关系 —— 漏铺会让正例与绿面一起恒红）。
#   各面都只在 $TMP 脚手架树内改（sed / 删行），绝不碰生产文件。
# ---------------------------------------------------------------------------
if guard_selected 30; then
d="$TMP/case41-model-readpoint-missing"
scaffold "$d"
sed -i '/fun run2/d' "$d/feature-chat/src/main/java/com/rickeal/agent/feature/chat/ChatRunCoordinator.kt"
out="$(run_guard "$d")"; rc=$?
assert_red "case41 删 1 处 model 读点 (第 30 条)" "$rc" "$out" \
  "ChatRunCoordinator 的 AgentRequest.model 取值点必须为全局 activeModel 读点（3 处；引入会话级 model 前须先解 EngineResilienceStore 复合键前置）"
if printf '%s\n' "$out" | grep -qF "守卫命令自身执行失败"; then
  echo "FAIL [case41b] 第 30 条报的是「守卫命令自身执行失败」而非真命中（本 case 假绿）"
  printf '%s\n' "$out" | sed 's/^/    | /'
  FAIL=$((FAIL + 1))
elif printf '%s\n' "$out" | grep -qF "取值点(实数 2) != 3"; then
  echo "PASS [case41b] 红来自真命中（输出含「取值点(实数 2) != 3」违规事实行）"
  PASS=$((PASS + 1))
else
  echo "FAIL [case41b] 输出里看不到「取值点(实数 2) != 3」违规事实行，判据可疑"
  printf '%s\n' "$out" | sed 's/^/    | /'
  FAIL=$((FAIL + 1))
fi

d="$TMP/case41c-model-readpoint-green"
scaffold "$d"
out="$(run_guard "$d")"; rc=$?
if [ "$rc" -eq 0 ]; then
  echo "PASS [case41c scaffold 恰 3 处 model 读点 (第 30 条绿面)] 退出 0（scaffold 与判据 ==3 同步）"
  PASS=$((PASS + 1))
else
  echo "FAIL [case41c] scaffold 的 3 处 model 读点未对齐判据 ==3（第 30 条误红 ⇒ 正例失守）"
  printf '%s\n' "$out" | sed 's/^/    | /'
  FAIL=$((FAIL + 1))
fi

fi
# ---------------------------------------------------------------------------
# case 42 / 42b / 42c / 42d：第 31 条（AgentRunner.kt ≤ 2900，W59 治 P3-α）两面：
#   case42 —— 向 fixture 灌到超 2900 行（沿 case20 对 ChatRunCoordinator 的追行手法）
#             ⇒ 判红；case42b 断言红来自真命中（输出含「超 2900 行上限」计数行）。
#   case42c —— 目标文件缺失（mv 改名，与 case17/case21/case32 同范式）⇒ fail-closed
#             判红；case42d 断言输出含「不存在（被改名/删除？第 31 条守卫面已失效）」
#             违规事实行。
#   各面都只在 $TMP 脚手架树内改（覆写 / mv），绝不碰生产文件。
#   绿面（write_runner 默认 body=5，远低于阈值）由 positive case 兜住。
# ---------------------------------------------------------------------------
if guard_selected 31; then
d="$TMP/case42-runner-oversize"
scaffold "$d"
{ echo 'package com.rickeal.agent.core.agent'
  echo ''
  echo 'class AgentRunner {'
  local_r=1
  while [ "$local_r" -le 2905 ]; do echo "    val r$local_r: Int = $local_r"; local_r=$((local_r + 1)); done
  echo '}'
} > "$d/core-agent/src/main/java/com/rickeal/agent/core/agent/AgentRunner.kt"
out="$(run_guard "$d")"; rc=$?
assert_red "case42 AgentRunner.kt 超行数上限 (第 31 条)" "$rc" "$out" \
  "AgentRunner.kt 总行数 ≤ 2900（core-agent 文件级行数表 #25 家族第 4 员，触顶 = 启动 AgentRunner 拆分评审，非改阈值）"
if printf '%s\n' "$out" | grep -qF "守卫命令自身执行失败"; then
  echo "FAIL [case42b] 第 31 条报的是「守卫命令自身执行失败」而非真命中（本 case 假绿）"
  printf '%s\n' "$out" | sed 's/^/    | /'
  FAIL=$((FAIL + 1))
elif printf '%s\n' "$out" | grep -qF "超 2900 行上限"; then
  echo "PASS [case42b] 红来自真命中（输出含「超 2900 行上限」计数行）"
  PASS=$((PASS + 1))
else
  echo "FAIL [case42b] 输出里看不到「超 2900 行上限」计数行，判据可疑"
  printf '%s\n' "$out" | sed 's/^/    | /'
  FAIL=$((FAIL + 1))
fi

d="$TMP/case42c-runner-missing"
scaffold "$d"
mv "$d/core-agent/src/main/java/com/rickeal/agent/core/agent/AgentRunner.kt" \
   "$d/core-agent/src/main/java/com/rickeal/agent/core/agent/.AgentRunner.kt.renamed"
out="$(run_guard "$d")"; rc=$?
assert_red "case42c AgentRunner.kt 缺失 (第 31 条守卫面失效)" "$rc" "$out" \
  "AgentRunner.kt 总行数 ≤ 2900（core-agent 文件级行数表 #25 家族第 4 员，触顶 = 启动 AgentRunner 拆分评审，非改阈值）"
if printf '%s\n' "$out" | grep -qF "守卫命令自身执行失败"; then
  echo "FAIL [case42d] 第 31 条报的是「守卫命令自身执行失败」而非真命中（本 case 假绿）"
  printf '%s\n' "$out" | sed 's/^/    | /'
  FAIL=$((FAIL + 1))
elif printf '%s\n' "$out" | grep -qF "AgentRunner.kt 不存在（被改名/删除？第 31 条守卫面已失效）"; then
  echo "PASS [case42d] 红来自真命中（输出含「AgentRunner.kt 不存在（被改名/删除？第 31 条守卫面已失效）」违规事实行）"
  PASS=$((PASS + 1))
else
  echo "FAIL [case42d] 输出里看不到「AgentRunner.kt 不存在」违规事实行，判据可疑"
  printf '%s\n' "$out" | sed 's/^/    | /'
  FAIL=$((FAIL + 1))
fi

fi
# ---------------------------------------------------------------------------
# case 43 / 43b / 44 / 44b：第 32 条（LiteRtLmEngineLoader.kt ≤ 900，W60）两面：
#   case43 —— 向 fixture 灌到超 900 行（沿 case31/case42 追行手法）⇒ 判红；
#             case43b 断言红来自真命中（输出含「超 900 行上限」计数行），而非
#             「守卫命令自身执行失败」。
#   case44 —— 目标文件缺失（mv 改名，与 case17/case21/case32/case42c 同范式）⇒
#             fail-closed 判红；case44b 断言输出含「LiteRtLmEngineLoader.kt 不存在
#             （被改名/删除？第 32 条守卫面已失效）」违规事实行。
#   各面都只在 $TMP 脚手架树内改（覆写 / mv），绝不碰生产文件。
#   绿面（scaffold fixture 行数远低于 900）由 positive case 兜住。
# ---------------------------------------------------------------------------
if guard_selected 32; then
d="$TMP/case43-loader-oversize"
scaffold "$d"
{ echo 'package com.rickeal.agent.core.engine.local'
  echo ''
  echo 'class LiteRtLmEngineLoader {'
  local_l=1
  while [ "$local_l" -le 905 ]; do echo "    val l$local_l: Int = $local_l"; local_l=$((local_l + 1)); done
  echo '}'
} > "$d/core-engine/src/main/java/com/rickeal/agent/core/engine/local/LiteRtLmEngineLoader.kt"
out="$(run_guard "$d")"; rc=$?
assert_red "case43 LiteRtLmEngineLoader.kt 超行数上限 (第 32 条)" "$rc" "$out" \
  "LiteRtLmEngineLoader.kt 总行数 ≤ 900（core-engine 行数表 #25 家族第 5 员，触顶 = 启动 Loader 拆分评审，非改阈值）"
if printf '%s\n' "$out" | grep -qF "守卫命令自身执行失败"; then
  echo "FAIL [case43b] 第 32 条报的是「守卫命令自身执行失败」而非真命中（本 case 假绿）"
  printf '%s\n' "$out" | sed 's/^/    | /'
  FAIL=$((FAIL + 1))
elif printf '%s\n' "$out" | grep -qF "超 900 行上限"; then
  echo "PASS [case43b] 红来自真命中（输出含「超 900 行上限」计数行）"
  PASS=$((PASS + 1))
else
  echo "FAIL [case43b] 输出里看不到「超 900 行上限」计数行，判据可疑"
  printf '%s\n' "$out" | sed 's/^/    | /'
  FAIL=$((FAIL + 1))
fi

d="$TMP/case44-loader-missing"
scaffold "$d"
mv "$d/core-engine/src/main/java/com/rickeal/agent/core/engine/local/LiteRtLmEngineLoader.kt" \
   "$d/core-engine/src/main/java/com/rickeal/agent/core/engine/local/.LiteRtLmEngineLoader.kt.renamed"
out="$(run_guard "$d")"; rc=$?
assert_red "case44 LiteRtLmEngineLoader.kt 缺失 (第 32 条守卫面失效)" "$rc" "$out" \
  "LiteRtLmEngineLoader.kt 总行数 ≤ 900（core-engine 行数表 #25 家族第 5 员，触顶 = 启动 Loader 拆分评审，非改阈值）"
if printf '%s\n' "$out" | grep -qF "守卫命令自身执行失败"; then
  echo "FAIL [case44b] 第 32 条报的是「守卫命令自身执行失败」而非真命中（本 case 假绿）"
  printf '%s\n' "$out" | sed 's/^/    | /'
  FAIL=$((FAIL + 1))
elif printf '%s\n' "$out" | grep -qF "LiteRtLmEngineLoader.kt 不存在（被改名/删除？第 32 条守卫面已失效）"; then
  echo "PASS [case44b] 红来自真命中（输出含「LiteRtLmEngineLoader.kt 不存在（被改名/删除？第 32 条守卫面已失效）」违规事实行）"
  PASS=$((PASS + 1))
else
  echo "FAIL [case44b] 输出里看不到「LiteRtLmEngineLoader.kt 不存在」违规事实行，判据可疑"
  printf '%s\n' "$out" | sed 's/^/    | /'
  FAIL=$((FAIL + 1))
fi

fi
# ---------------------------------------------------------------------------
# case 45 / 45b / 45c：第 33 条（LiteRtLmEngine 世代门控接线，W60）：
#   case45 —— 从 scaffold fixture 删 1 处 `handleTemplateRenderFailure(token,`（2 → 1，
#             sed 删「同步下发」行）⇒ 计数 1 != 2 ⇒ 判红；case45b 断言红来自真命中
#            （输出含「handleTemplateRenderFailure(token,…) 调用数(1) != 2」违规事实行）。
#   case45c —— scaffold 原样（1 门控调用 + 2 调用点）⇒ 必须不红（绿面；独立钉死 scaffold
#             铺点与判据的同步关系 —— 漏铺会让正例与绿面一起恒红）。
#   各面都只在 $TMP 脚手架树内改（sed 删行），绝不碰生产文件。
# ---------------------------------------------------------------------------
if guard_selected 33; then
d="$TMP/case45-gate-callsite-missing"
scaffold "$d"
sed -i '/同步下发/d' "$d/core-engine/src/main/java/com/rickeal/agent/core/engine/local/LiteRtLmEngine.kt"
out="$(run_guard "$d")"; rc=$?
assert_red "case45 删 1 处 handleTemplateRenderFailure(token, 调用点 (第 33 条)" "$rc" "$out" \
  "LiteRtLmEngine 世代门控接线（shouldDropStaleDisposal 调用 1；handleTemplateRenderFailure(token,…) 调用 2）"
if printf '%s\n' "$out" | grep -qF "守卫命令自身执行失败"; then
  echo "FAIL [case45b] 第 33 条报的是「守卫命令自身执行失败」而非真命中（本 case 假绿）"
  printf '%s\n' "$out" | sed 's/^/    | /'
  FAIL=$((FAIL + 1))
elif printf '%s\n' "$out" | grep -qF "handleTemplateRenderFailure(token,…) 调用数(1) != 2"; then
  echo "PASS [case45b] 红来自真命中（输出含「handleTemplateRenderFailure(token,…) 调用数(1) != 2」违规事实行）"
  PASS=$((PASS + 1))
else
  echo "FAIL [case45b] 输出里看不到「handleTemplateRenderFailure(token,…) 调用数(1) != 2」违规事实行，判据可疑"
  printf '%s\n' "$out" | sed 's/^/    | /'
  FAIL=$((FAIL + 1))
fi

d="$TMP/case45c-gate-green"
scaffold "$d"
out="$(run_guard "$d")"; rc=$?
if [ "$rc" -eq 0 ]; then
  echo "PASS [case45c scaffold 世代门控接线 (第 33 条绿面)] 退出 0（scaffold 与判据 ==1/==2 同步）"
  PASS=$((PASS + 1))
else
  echo "FAIL [case45c] scaffold 的门控调用/调用点未对齐判据（第 33 条误红 ⇒ 正例失守）"
  printf '%s\n' "$out" | sed 's/^/    | /'
  FAIL=$((FAIL + 1))
fi

fi
# ---------------------------------------------------------------------------
# case 46：第 30 条（ChatRunCoordinator run 级 model 读点冻结，W59；W60 加注释位排除）
#   绿面 —— 只在 **KDoc/注释** 里提 `model = uiState.value.activeModel` ⇒ 必须**仍绿**
#   （防被注释骗，沿 case30（#24 的 KDoc 绿面）范式）：注释位被
#   `^[0-9]+:[[:space:]]*[*/]` 排除 ⇒ 计数仍 3。仅 $TMP 内追加，绝不碰生产文件。
# ---------------------------------------------------------------------------
if guard_selected 30; then
d="$TMP/case46-model-kdoc-green"
scaffold "$d"
cat >> "$d/feature-chat/src/main/java/com/rickeal/agent/feature/chat/ChatRunCoordinator.kt" <<'KT'

/**
 * 说明：AgentRequest 的 `model = uiState.value.activeModel` 出现在 KDoc 里，
 * 不得被第 30 条守卫计入 run 级 model 读点（注释位必须排除）。
 */
val kdocOnlyModelNote = 1
KT
out="$(run_guard "$d")"; rc=$?
if [ "$rc" -eq 0 ]; then
  echo "PASS [case46 KDoc 提及 model = uiState.value.activeModel (第 30 条绿面)] 退出 0（注释位被排除，未误红）"
  PASS=$((PASS + 1))
else
  echo "FAIL [case46] KDoc 里的 model 读点被误计入（第 30 条判据未排除注释位 ⇒ 会被注释骗）"
  printf '%s\n' "$out" | sed 's/^/    | /'
  FAIL=$((FAIL + 1))
fi

fi
# ---------------------------------------------------------------------------
# case 47：第 33 条（LiteRtLmEngine 世代门控接线，W60；W61 判据① 加注释位排除）
#   绿面 —— 只在 **KDoc/注释** 里提 `shouldDropStaleDisposal(token, current)` 调用形态
#   ⇒ 必须**仍绿**（防被注释骗，沿 case46（#30 的 KDoc 绿面）范式）：注释位被
#   `^[0-9]+:[[:space:]]*[*/]` 排除 ⇒ 调用点数仍 1。仅 $TMP 内追加，绝不碰生产文件。
# ---------------------------------------------------------------------------
if guard_selected 33; then
d="$TMP/case47-gate-kdoc-green"
scaffold "$d"
cat >> "$d/core-engine/src/main/java/com/rickeal/agent/core/engine/local/LiteRtLmEngine.kt" <<'KT'

/**
 * 说明：注释位出现 shouldDropStaleDisposal(token, current) 调用形态，
 * 不得被第 33 条守卫判据① 计入调用点数（注释位必须排除）。
 */
val kdocOnlyGateNote = 1
KT
out="$(run_guard "$d")"; rc=$?
if [ "$rc" -eq 0 ]; then
  echo "PASS [case47 KDoc 提及 shouldDropStaleDisposal(token, current) (第 33 条绿面)] 退出 0（注释位被排除，未误红）"
  PASS=$((PASS + 1))
else
  echo "FAIL [case47] KDoc 里的门控调用形态被误计入（第 33 条判据① 未排除注释位 ⇒ 会被注释骗）"
  printf '%s\n' "$out" | sed 's/^/    | /'
  FAIL=$((FAIL + 1))
fi

fi
# ---------------------------------------------------------------------------
# case 48：第 34 条（knownBrokenOn 互斥 recommended，W63 刀C）红面 —— 同一构造块内
#   `knownBrokenOn = "…"` 与 `recommended = true` 共存 ⇒ 判红，且输出必须含违规行
#   本身（预设 label），不能只匹配守卫名（KB：守卫自身执行失败也含守卫名 ⇒ 假 PASS）。
# ---------------------------------------------------------------------------
if guard_selected 34; then
d="$TMP/case48-known-broken-red"
scaffold "$d"
cat > "$d/core-model/src/main/java/com/rickeal/agent/core/model/ModelPresets.kt" <<'KT'
package com.rickeal.agent.core.model

data class ModelPreset(
    val label: String,
    val recommended: Boolean = false,
    val knownBrokenOn: String? = null,
)

object ModelPresets {
    val all: List<ModelPreset> = listOf(
        ModelPreset(
            label = "broken-but-recommended",
            recommended = true,
            knownBrokenOn = "0.17.1 引擎初始化 NOT_FOUND",
        ),
    )
}
KT
out="$(run_guard "$d")"; rc=$?
assert_red "case48 knownBrokenOn 与 recommended 同真 (第 34 条)" "$rc" "$out" \
  "knownBrokenOn 非空的预设不得 recommended（已知坏容器不得置顶推荐）"
if printf '%s\n' "$out" | grep -qF "broken-but-recommended"; then
  echo "PASS [case48b] 输出含违规预设 label（真命中，非守卫自身故障的假红）"
  PASS=$((PASS + 1))
else
  echo "FAIL [case48b] 输出不含违规预设 label（可能是守卫自身故障被判红 ⇒ 假 PASS）"
  printf '%s\n' "$out" | sed 's/^/    | /'
  FAIL=$((FAIL + 1))
fi

fi
# ---------------------------------------------------------------------------
# case 49：第 34 条 fail-closed —— `val knownBrokenOn` 声明被删（字段被整个拆掉）
#   ⇒ 必须判红「守卫面失效」，不能静默变绿（僵尸规则防线）。
# ---------------------------------------------------------------------------
if guard_selected 34; then
d="$TMP/case49-known-broken-decl-gone"
scaffold "$d"
cat > "$d/core-model/src/main/java/com/rickeal/agent/core/model/ModelPresets.kt" <<'KT'
package com.rickeal.agent.core.model

data class ModelPreset(
    val label: String,
    val recommended: Boolean = false,
)

object ModelPresets {
    val all: List<ModelPreset> = listOf(
        ModelPreset(
            label = "no-field",
            recommended = true,
        ),
    )
}
KT
out="$(run_guard "$d")"; rc=$?
assert_red "case49 knownBrokenOn 声明被删 (第 34 条面失效)" "$rc" "$out" \
  "knownBrokenOn 非空的预设不得 recommended（已知坏容器不得置顶推荐）"
# case49b：红必须来自**真命中**（输出里能看到 check-known-broken.py 对「声明被删」
#          打出的逐字违规行），而不是「守卫命令自身执行失败」—— 后者那行同样含守卫名，
#          会让 assert_red 假绿（与 case48b / case12b / case12d 同一范式）。
#          逐字原文取自 scripts/check-known-broken.py 的 DECL 未命中分支：
#            "<path> 不存在 `val knownBrokenOn: String?` 声明（字段被删 = 第 34 条守卫面失效）"
#          模式串含反引号 ⇒ 必须用**单引号**包裹（双引号会触发命令替换）。
if printf '%s\n' "$out" | grep -qF '不存在 `val knownBrokenOn: String?` 声明（字段被删 = 第 34 条守卫面失效）'; then
  echo "PASS [case49b] 红来自真命中（输出含 check-known-broken.py 的「声明被删」逐字违规行）"
  PASS=$((PASS + 1))
else
  echo "FAIL [case49b] 输出里看不到「声明被删」逐字违规行（可能是守卫自身故障被判红 ⇒ 假 PASS）"
  printf '%s\n' "$out" | sed 's/^/    | /'
  FAIL=$((FAIL + 1))
fi

fi
# ---------------------------------------------------------------------------
# case 50：第 34 条绿面 —— 显式 `knownBrokenOn = null` 与 `recommended = true`
#   合法共存（null = 未见已知失败，推荐资格保留）⇒ 必须仍绿（防把 null 判成非空）。
#   （scaffold 的 clean-recommended / explicit-null-recommended / broken-not-recommended
#   三组合已由 positive case 覆盖绿面；本 case 钉「显式 null 不被误判」这一形态。）
# ---------------------------------------------------------------------------
if guard_selected 34; then
d="$TMP/case50-known-broken-null-green"
scaffold "$d"
out="$(run_guard "$d")"; rc=$?
if [ "$rc" -eq 0 ]; then
  echo "PASS [case50 显式 knownBrokenOn=null 与 recommended=true 共存 (第 34 条绿面)] 退出 0"
  PASS=$((PASS + 1))
else
  echo "FAIL [case50] 显式 null 被误判为非空（第 34 条误红）"
  printf '%s\n' "$out" | sed 's/^/    | /'
  FAIL=$((FAIL + 1))
fi

fi
# ---------------------------------------------------------------------------
# case 51 / 51b / 51c / 51d / 51e：第 35 条（AgentRunnerToolSetup.kt ≤ 400，W64）两面：
#   case51 —— 向 fixture 灌到超 400 行（沿 case42/case43 追行手法）⇒ 判红；
#             case51b 断言红来自真命中（输出含「超 400 行上限」计数行），而非
#             「守卫命令自身执行失败」（后者也含守卫名 ⇒ 会让 assert_red 假绿）。
#   case51c —— 目标文件缺失（mv 改名，与 case17/case32/case42c/case44 同范式）⇒
#             fail-closed 判红；case51d 断言输出含「AgentRunnerToolSetup.kt 不存在
#             （被改名/删除？第 35 条守卫面已失效）」违规事实行。
#   case51e —— scaffold 原样（AgentRunnerToolSetup.kt 远低于 400 行）⇒ 必须不红
#             （绿面；独立钉死 scaffold 铺点与 fail-closed 判据的同步关系 —— 漏铺会让
#             正例与所有绿面一起恒红）。--only 35 会跳过正例，故本条自带绿面。
#   各面都只在 $TMP 脚手架树内改（覆写 / mv），绝不碰生产文件。
# ---------------------------------------------------------------------------
if guard_selected 35; then
d="$TMP/case51-toolsetup-oversize"
scaffold "$d"
{ echo 'package com.rickeal.agent.core.agent'
  echo ''
  echo 'class AgentRunnerToolSetup {'
  local_t=1
  while [ "$local_t" -le 405 ]; do echo "    val t$local_t: Int = $local_t"; local_t=$((local_t + 1)); done
  echo '}'
} > "$d/core-agent/src/main/java/com/rickeal/agent/core/agent/AgentRunnerToolSetup.kt"
out="$(run_guard "$d")"; rc=$?
assert_red "case51 AgentRunnerToolSetup.kt 超行数上限 (第 35 条)" "$rc" "$out" \
  "AgentRunnerToolSetup.kt 总行数 ≤ 400（core-agent 行数表 #25 家族第 6 员，触顶 = 启动 AgentRunnerToolSetup 拆分评审，非改阈值）"
if printf '%s\n' "$out" | grep -qF "守卫命令自身执行失败"; then
  echo "FAIL [case51b] 第 35 条报的是「守卫命令自身执行失败」而非真命中（本 case 假绿）"
  printf '%s\n' "$out" | sed 's/^/    | /'
  FAIL=$((FAIL + 1))
elif printf '%s\n' "$out" | grep -qF "超 400 行上限"; then
  echo "PASS [case51b] 红来自真命中（输出含「超 400 行上限」计数行）"
  PASS=$((PASS + 1))
else
  echo "FAIL [case51b] 输出里看不到「超 400 行上限」计数行，判据可疑"
  printf '%s\n' "$out" | sed 's/^/    | /'
  FAIL=$((FAIL + 1))
fi

d="$TMP/case51c-toolsetup-missing"
scaffold "$d"
mv "$d/core-agent/src/main/java/com/rickeal/agent/core/agent/AgentRunnerToolSetup.kt" \
   "$d/core-agent/src/main/java/com/rickeal/agent/core/agent/.AgentRunnerToolSetup.kt.renamed"
out="$(run_guard "$d")"; rc=$?
assert_red "case51c AgentRunnerToolSetup.kt 缺失 (第 35 条守卫面失效)" "$rc" "$out" \
  "AgentRunnerToolSetup.kt 总行数 ≤ 400（core-agent 行数表 #25 家族第 6 员，触顶 = 启动 AgentRunnerToolSetup 拆分评审，非改阈值）"
if printf '%s\n' "$out" | grep -qF "守卫命令自身执行失败"; then
  echo "FAIL [case51d] 第 35 条报的是「守卫命令自身执行失败」而非真命中（本 case 假绿）"
  printf '%s\n' "$out" | sed 's/^/    | /'
  FAIL=$((FAIL + 1))
elif printf '%s\n' "$out" | grep -qF "AgentRunnerToolSetup.kt 不存在（被改名/删除？第 35 条守卫面已失效）"; then
  echo "PASS [case51d] 红来自真命中（输出含「AgentRunnerToolSetup.kt 不存在（被改名/删除？第 35 条守卫面已失效）」违规事实行）"
  PASS=$((PASS + 1))
else
  echo "FAIL [case51d] 输出里看不到「AgentRunnerToolSetup.kt 不存在」违规事实行，判据可疑"
  printf '%s\n' "$out" | sed 's/^/    | /'
  FAIL=$((FAIL + 1))
fi

d="$TMP/case51e-toolsetup-green"
scaffold "$d"
out="$(run_guard "$d")"; rc=$?
if [ "$rc" -eq 0 ]; then
  echo "PASS [case51e AgentRunnerToolSetup.kt 行数远低于 400 (第 35 条绿面)] 退出 0"
  PASS=$((PASS + 1))
else
  echo "FAIL [case51e] scaffold 的 AgentRunnerToolSetup.kt 被第 35 条误红（绿面失守）"
  printf '%s\n' "$out" | sed 's/^/    | /'
  FAIL=$((FAIL + 1))
fi

fi
# ---------------------------------------------------------------------------
# case 52 / 52b / 52c / 52d / 52e：第 36 条（ThinkingBudget.kt ≤ 150，W65，外部审查 D3）两面：
#   case52 —— 向 fixture 灌到超 150 行（沿 case42/case43/case51 追行手法）⇒ 判红；
#             case52b 断言红来自真命中（输出含「超 150 行上限」计数行），而非
#             「守卫命令自身执行失败」（后者也含守卫名 ⇒ 会让 assert_red 假绿）。
#   case52c —— 目标文件缺失（mv 改名，与 case17/case32/case42c/case44/case51c 同范式）⇒
#             fail-closed 判红；case52d 断言输出含「ThinkingBudget.kt 不存在
#             （被改名/删除？第 36 条守卫面已失效）」违规事实行。
#   case52e —— scaffold 原样（ThinkingBudget.kt 远低于 150 行）⇒ 必须不红（绿面；
#             scaffold 为该 fail-closed 守卫铺的点与判据的同步关系由本条钉死）。
#   各面都只在 $TMP 脚手架树内改（覆写 / mv），绝不碰生产文件。
# ---------------------------------------------------------------------------
if guard_selected 36; then
d="$TMP/case52-thinkingbudget-oversize"
scaffold "$d"
{ echo 'package com.rickeal.agent.core.engine.local'
  echo ''
  echo 'class ThinkingBudget {'
  tb_i=1
  while [ "$tb_i" -le 155 ]; do echo "    val t$tb_i: Int = $tb_i"; tb_i=$((tb_i + 1)); done
  echo '}'
} > "$d/core-engine/src/main/java/com/rickeal/agent/core/engine/local/ThinkingBudget.kt"
out="$(run_guard "$d")"; rc=$?
assert_red "case52 ThinkingBudget.kt 超行数上限 (第 36 条)" "$rc" "$out" \
  "ThinkingBudget.kt 总行数 ≤ 150（core-engine 行数表 #25 家族第 7 员，触顶 = 启动 ThinkingBudget 拆分评审，非改阈值）"
if printf '%s\n' "$out" | grep -qF "守卫命令自身执行失败"; then
  echo "FAIL [case52b] 第 36 条报的是「守卫命令自身执行失败」而非真命中（本 case 假绿）"
  printf '%s\n' "$out" | sed 's/^/    | /'
  FAIL=$((FAIL + 1))
elif printf '%s\n' "$out" | grep -qF "超 150 行上限"; then
  echo "PASS [case52b] 红来自真命中（输出含「超 150 行上限」计数行）"
  PASS=$((PASS + 1))
else
  echo "FAIL [case52b] 输出里看不到「超 150 行上限」计数行，判据可疑"
  printf '%s\n' "$out" | sed 's/^/    | /'
  FAIL=$((FAIL + 1))
fi

d="$TMP/case52c-thinkingbudget-missing"
scaffold "$d"
mv "$d/core-engine/src/main/java/com/rickeal/agent/core/engine/local/ThinkingBudget.kt" \
   "$d/core-engine/src/main/java/com/rickeal/agent/core/engine/local/.ThinkingBudget.kt.renamed"
out="$(run_guard "$d")"; rc=$?
assert_red "case52c ThinkingBudget.kt 缺失 (第 36 条守卫面失效)" "$rc" "$out" \
  "ThinkingBudget.kt 总行数 ≤ 150（core-engine 行数表 #25 家族第 7 员，触顶 = 启动 ThinkingBudget 拆分评审，非改阈值）"
if printf '%s\n' "$out" | grep -qF "守卫命令自身执行失败"; then
  echo "FAIL [case52d] 第 36 条报的是「守卫命令自身执行失败」而非真命中（本 case 假绿）"
  printf '%s\n' "$out" | sed 's/^/    | /'
  FAIL=$((FAIL + 1))
elif printf '%s\n' "$out" | grep -qF "ThinkingBudget.kt 不存在（被改名/删除？第 36 条守卫面已失效）"; then
  echo "PASS [case52d] 红来自真命中（输出含「ThinkingBudget.kt 不存在（被改名/删除？第 36 条守卫面已失效）」违规事实行）"
  PASS=$((PASS + 1))
else
  echo "FAIL [case52d] 输出里看不到「ThinkingBudget.kt 不存在」违规事实行，判据可疑"
  printf '%s\n' "$out" | sed 's/^/    | /'
  FAIL=$((FAIL + 1))
fi

d="$TMP/case52e-thinkingbudget-green"
scaffold "$d"
out="$(run_guard "$d")"; rc=$?
if [ "$rc" -eq 0 ]; then
  echo "PASS [case52e ThinkingBudget.kt 行数远低于 150 (第 36 条绿面)] 退出 0"
  PASS=$((PASS + 1))
else
  echo "FAIL [case52e] scaffold 的 ThinkingBudget.kt 被第 36 条误红（绿面失守）"
  printf '%s\n' "$out" | sed 's/^/    | /'
  FAIL=$((FAIL + 1))
fi

fi
# ---------------------------------------------------------------------------
# case 54 / 54b / 54c / 54d / 54e：第 37 条（AgentRunnerLoopHelpers.kt ≤ 400，W65，
#     外部审查 D3 纪律 —— 结构批新文件拆出同波补守）两面（照 case52 组五面范式）：
#   case54 —— 向 fixture 灌到超 400 行 ⇒ 判红；case54b 断言红来自真命中（输出含
#             「超 400 行上限」计数行），而非「守卫命令自身执行失败」假绿。
#   case54c —— 目标文件缺失（mv 改名）⇒ fail-closed 判红；case54d 断言输出含
#             「AgentRunnerLoopHelpers.kt 不存在（被改名/删除？第 37 条守卫面已失效）」。
#   case54e —— scaffold 原样（远低于 400 行）⇒ 必须不红（绿面）。
#   各面都只在 $TMP 脚手架树内改（覆写 / mv），绝不碰生产文件。
# ---------------------------------------------------------------------------
if guard_selected 37; then
d="$TMP/case54-loophelpers-oversize"
scaffold "$d"
{ echo 'package com.rickeal.agent.core.agent'
  echo ''
  echo 'class AgentRunnerLoopHelpers {'
  tb_i=1
  while [ "$tb_i" -le 405 ]; do echo "    val t$tb_i: Int = $tb_i"; tb_i=$((tb_i + 1)); done
  echo '}'
} > "$d/core-agent/src/main/java/com/rickeal/agent/core/agent/AgentRunnerLoopHelpers.kt"
out="$(run_guard "$d")"; rc=$?
assert_red "case54 AgentRunnerLoopHelpers.kt 超行数上限 (第 37 条)" "$rc" "$out" \
  "AgentRunnerLoopHelpers.kt 总行数 ≤ 400（core-agent 行数表 #25 家族第 8 员，触顶 = 启动 AgentRunnerLoopHelpers 拆分评审，非改阈值）"
if printf '%s\n' "$out" | grep -qF "守卫命令自身执行失败"; then
  echo "FAIL [case54b] 第 37 条报的是「守卫命令自身执行失败」而非真命中（本 case 假绿）"
  printf '%s\n' "$out" | sed 's/^/    | /'
  FAIL=$((FAIL + 1))
elif printf '%s\n' "$out" | grep -qF "超 400 行上限"; then
  echo "PASS [case54b] 红来自真命中（输出含「超 400 行上限」计数行）"
  PASS=$((PASS + 1))
else
  echo "FAIL [case54b] 输出里看不到「超 400 行上限」计数行，判据可疑"
  printf '%s\n' "$out" | sed 's/^/    | /'
  FAIL=$((FAIL + 1))
fi

d="$TMP/case54c-loophelpers-missing"
scaffold "$d"
mv "$d/core-agent/src/main/java/com/rickeal/agent/core/agent/AgentRunnerLoopHelpers.kt" \
   "$d/core-agent/src/main/java/com/rickeal/agent/core/agent/.AgentRunnerLoopHelpers.kt.renamed"
out="$(run_guard "$d")"; rc=$?
assert_red "case54c AgentRunnerLoopHelpers.kt 缺失 (第 37 条守卫面失效)" "$rc" "$out" \
  "AgentRunnerLoopHelpers.kt 总行数 ≤ 400（core-agent 行数表 #25 家族第 8 员，触顶 = 启动 AgentRunnerLoopHelpers 拆分评审，非改阈值）"
if printf '%s\n' "$out" | grep -qF "守卫命令自身执行失败"; then
  echo "FAIL [case54d] 第 37 条报的是「守卫命令自身执行失败」而非真命中（本 case 假绿）"
  printf '%s\n' "$out" | sed 's/^/    | /'
  FAIL=$((FAIL + 1))
elif printf '%s\n' "$out" | grep -qF "AgentRunnerLoopHelpers.kt 不存在（被改名/删除？第 37 条守卫面已失效）"; then
  echo "PASS [case54d] 红来自真命中（输出含「AgentRunnerLoopHelpers.kt 不存在（被改名/删除？第 37 条守卫面已失效）」违规事实行）"
  PASS=$((PASS + 1))
else
  echo "FAIL [case54d] 输出里看不到「AgentRunnerLoopHelpers.kt 不存在」违规事实行，判据可疑"
  printf '%s\n' "$out" | sed 's/^/    | /'
  FAIL=$((FAIL + 1))
fi

d="$TMP/case54e-loophelpers-green"
scaffold "$d"
out="$(run_guard "$d")"; rc=$?
if [ "$rc" -eq 0 ]; then
  echo "PASS [case54e AgentRunnerLoopHelpers.kt 行数远低于 400 (第 37 条绿面)] 退出 0"
  PASS=$((PASS + 1))
else
  echo "FAIL [case54e] scaffold 的 AgentRunnerLoopHelpers.kt 被第 37 条误红（绿面失守）"
  printf '%s\n' "$out" | sed 's/^/    | /'
  FAIL=$((FAIL + 1))
fi

fi
# ---------------------------------------------------------------------------
# case 55 / 55b / 55c / 55d / 55e：第 38 条（ChatRunRecovery.kt ≤ 400，W65，
#     外部审查 D3 纪律 —— 结构批新文件拆出同波补守）两面（照 case52 组五面范式）：
#   case55 —— 向 fixture 灌到超 400 行 ⇒ 判红；case55b 断言红来自真命中（输出含
#             「超 400 行上限」计数行），而非「守卫命令自身执行失败」假绿。
#   case55c —— 目标文件缺失（mv 改名）⇒ fail-closed 判红；case55d 断言输出含
#             「ChatRunRecovery.kt 不存在（被改名/删除？第 38 条守卫面已失效）」。
#   case55e —— scaffold 原样（远低于 400 行）⇒ 必须不红（绿面）。
#   各面都只在 $TMP 脚手架树内改（覆写 / mv），绝不碰生产文件。
# ---------------------------------------------------------------------------
if guard_selected 38; then
d="$TMP/case55-recovery-oversize"
scaffold "$d"
{ echo 'package com.rickeal.agent.feature.chat'
  echo ''
  echo 'class ChatRunRecovery {'
  tb_i=1
  while [ "$tb_i" -le 405 ]; do echo "    val t$tb_i: Int = $tb_i"; tb_i=$((tb_i + 1)); done
  echo '}'
} > "$d/feature-chat/src/main/java/com/rickeal/agent/feature/chat/ChatRunRecovery.kt"
out="$(run_guard "$d")"; rc=$?
assert_red "case55 ChatRunRecovery.kt 超行数上限 (第 38 条)" "$rc" "$out" \
  "ChatRunRecovery.kt 总行数 ≤ 400（feature-chat 行数表 #25 家族第 9 员，触顶 = 启动 ChatRunRecovery 拆分评审，非改阈值）"
if printf '%s\n' "$out" | grep -qF "守卫命令自身执行失败"; then
  echo "FAIL [case55b] 第 38 条报的是「守卫命令自身执行失败」而非真命中（本 case 假绿）"
  printf '%s\n' "$out" | sed 's/^/    | /'
  FAIL=$((FAIL + 1))
elif printf '%s\n' "$out" | grep -qF "超 400 行上限"; then
  echo "PASS [case55b] 红来自真命中（输出含「超 400 行上限」计数行）"
  PASS=$((PASS + 1))
else
  echo "FAIL [case55b] 输出里看不到「超 400 行上限」计数行，判据可疑"
  printf '%s\n' "$out" | sed 's/^/    | /'
  FAIL=$((FAIL + 1))
fi

d="$TMP/case55c-recovery-missing"
scaffold "$d"
mv "$d/feature-chat/src/main/java/com/rickeal/agent/feature/chat/ChatRunRecovery.kt" \
   "$d/feature-chat/src/main/java/com/rickeal/agent/feature/chat/.ChatRunRecovery.kt.renamed"
out="$(run_guard "$d")"; rc=$?
assert_red "case55c ChatRunRecovery.kt 缺失 (第 38 条守卫面失效)" "$rc" "$out" \
  "ChatRunRecovery.kt 总行数 ≤ 400（feature-chat 行数表 #25 家族第 9 员，触顶 = 启动 ChatRunRecovery 拆分评审，非改阈值）"
if printf '%s\n' "$out" | grep -qF "守卫命令自身执行失败"; then
  echo "FAIL [case55d] 第 38 条报的是「守卫命令自身执行失败」而非真命中（本 case 假绿）"
  printf '%s\n' "$out" | sed 's/^/    | /'
  FAIL=$((FAIL + 1))
elif printf '%s\n' "$out" | grep -qF "ChatRunRecovery.kt 不存在（被改名/删除？第 38 条守卫面已失效）"; then
  echo "PASS [case55d] 红来自真命中（输出含「ChatRunRecovery.kt 不存在（被改名/删除？第 38 条守卫面已失效）」违规事实行）"
  PASS=$((PASS + 1))
else
  echo "FAIL [case55d] 输出里看不到「ChatRunRecovery.kt 不存在」违规事实行，判据可疑"
  printf '%s\n' "$out" | sed 's/^/    | /'
  FAIL=$((FAIL + 1))
fi

d="$TMP/case55e-recovery-green"
scaffold "$d"
out="$(run_guard "$d")"; rc=$?
if [ "$rc" -eq 0 ]; then
  echo "PASS [case55e ChatRunRecovery.kt 行数远低于 400 (第 38 条绿面)] 退出 0"
  PASS=$((PASS + 1))
else
  echo "FAIL [case55e] scaffold 的 ChatRunRecovery.kt 被第 38 条误红（绿面失守）"
  printf '%s\n' "$out" | sed 's/^/    | /'
  FAIL=$((FAIL + 1))
fi

fi
# ---------------------------------------------------------------------------
# case 56 / 56b / 57 / 58 / 59 / 59b / 60：第 39 条（evidenceLevel 全 schema，W66 B3）四面：
#   case56 —— 构造块缺 `evidenceLevel = …` 赋值 ⇒ 判红（全 schema 面）；case56b 断言红来自
#             真命中（输出含违规预设 label + 「缺 evidenceLevel 赋值」事实行）。
#   case57 —— `evidenceLevel = MEASURED` 但块内无 `W##` 锚点 ⇒ 判红（**反推测预填**核心面：
#             MEASURED 声明本仓实测，必须能指出是哪一波）。
#   case58 —— `evidenceLevel = BOGUS`（未知等级）⇒ 判红（防拼写/未知枚举）。
#   case59 —— `val evidenceLevel` 声明被删 ⇒ fail-closed 判红；case59b 断言红来自真命中
#             （输出含「缺 val evidenceLevel 声明」逐字行，而非「守卫自身执行失败」假红）。
#   case60 —— scaffold 原样（三块 evidenceLevel 合法：MEASURED 带 W60 锚点 / UPSTREAM）⇒
#             必须不红（绿面；独立钉死 scaffold 铺点与 fail-closed 判据的同步关系）。
#   各面都只在 $TMP 脚手架树内覆写 ModelPresets.kt，绝不碰生产文件。
# ---------------------------------------------------------------------------
if guard_selected 39; then
d="$TMP/case56-evidence-missing"
scaffold "$d"
cat > "$d/core-model/src/main/java/com/rickeal/agent/core/model/ModelPresets.kt" <<'KT'
package com.rickeal.agent.core.model

enum class EvidenceLevel { MEASURED, UPSTREAM, UNVERIFIED }

data class ModelPreset(
    val label: String,
    val evidenceLevel: EvidenceLevel = EvidenceLevel.UNVERIFIED,
)

object ModelPresets {
    val all: List<ModelPreset> = listOf(
        ModelPreset(
            label = "missing-level",
        ),
    )
}
KT
out="$(run_guard "$d")"; rc=$?
assert_red "case56 构造块缺 evidenceLevel 赋值 (第 39 条)" "$rc" "$out" \
  "ModelPreset 全 schema 显式声明 evidenceLevel 且 MEASURED 带本仓取证锚点（W66 B3）"
if printf '%s\n' "$out" | grep -qF "缺 evidenceLevel 赋值" && printf '%s\n' "$out" | grep -qF "missing-level"; then
  echo "PASS [case56b] 红来自真命中（输出含违规预设 label + 「缺 evidenceLevel 赋值」事实行）"
  PASS=$((PASS + 1))
else
  echo "FAIL [case56b] 输出不含「缺 evidenceLevel 赋值」+ 违规 label（可能是守卫自身故障假红）"
  printf '%s\n' "$out" | sed 's/^/    | /'
  FAIL=$((FAIL + 1))
fi

d="$TMP/case57-measured-no-anchor"
scaffold "$d"
cat > "$d/core-model/src/main/java/com/rickeal/agent/core/model/ModelPresets.kt" <<'KT'
package com.rickeal.agent.core.model

enum class EvidenceLevel { MEASURED, UPSTREAM, UNVERIFIED }

data class ModelPreset(
    val label: String,
    val evidenceLevel: EvidenceLevel = EvidenceLevel.UNVERIFIED,
)

object ModelPresets {
    val all: List<ModelPreset> = listOf(
        ModelPreset(
            label = "measured-no-anchor",
            evidenceLevel = EvidenceLevel.MEASURED,
        ),
    )
}
KT
out="$(run_guard "$d")"; rc=$?
assert_red "case57 MEASURED 无本仓取证锚点 (第 39 条反推测预填)" "$rc" "$out" \
  "ModelPreset 全 schema 显式声明 evidenceLevel 且 MEASURED 带本仓取证锚点（W66 B3）"
if printf '%s\n' "$out" | grep -qF "但 backendBasis/note 内无本仓取证锚点"; then
  echo "PASS [case57b] 红来自真命中（输出含「MEASURED 但 backendBasis/note 内无本仓取证锚点」事实行）"
  PASS=$((PASS + 1))
else
  echo "FAIL [case57b] 输出不含 MEASURED 无锚点事实行（判据可疑）"
  printf '%s\n' "$out" | sed 's/^/    | /'
  FAIL=$((FAIL + 1))
fi

d="$TMP/case58-evidence-illegal"
scaffold "$d"
cat > "$d/core-model/src/main/java/com/rickeal/agent/core/model/ModelPresets.kt" <<'KT'
package com.rickeal.agent.core.model

enum class EvidenceLevel { MEASURED, UPSTREAM, UNVERIFIED }

data class ModelPreset(
    val label: String,
    val evidenceLevel: EvidenceLevel = EvidenceLevel.UNVERIFIED,
)

object ModelPresets {
    val all: List<ModelPreset> = listOf(
        ModelPreset(
            label = "bogus-level",
            evidenceLevel = EvidenceLevel.BOGUS,
        ),
    )
}
KT
out="$(run_guard "$d")"; rc=$?
assert_red "case58 evidenceLevel 未知等级 (第 39 条)" "$rc" "$out" \
  "ModelPreset 全 schema 显式声明 evidenceLevel 且 MEASURED 带本仓取证锚点（W66 B3）"

d="$TMP/case59-evidence-decl-gone"
scaffold "$d"
cat > "$d/core-model/src/main/java/com/rickeal/agent/core/model/ModelPresets.kt" <<'KT'
package com.rickeal.agent.core.model

data class ModelPreset(
    val label: String,
)

object ModelPresets {
    val all: List<ModelPreset> = listOf(
        ModelPreset(
            label = "no-decl",
        ),
    )
}
KT
out="$(run_guard "$d")"; rc=$?
assert_red "case59 evidenceLevel 声明被删 (第 39 条面失效)" "$rc" "$out" \
  "ModelPreset 全 schema 显式声明 evidenceLevel 且 MEASURED 带本仓取证锚点（W66 B3）"
if printf '%s\n' "$out" | grep -qF "缺 val evidenceLevel 声明（字段被删 = 第 39 条守卫面已失效）"; then
  echo "PASS [case59b] 红来自真命中（输出含「缺 val evidenceLevel 声明」逐字违规行）"
  PASS=$((PASS + 1))
else
  echo "FAIL [case59b] 输出里看不到「缺 val evidenceLevel 声明」逐字违规行（可能守卫自身故障假红）"
  printf '%s\n' "$out" | sed 's/^/    | /'
  FAIL=$((FAIL + 1))
fi

d="$TMP/case60-evidence-green"
scaffold "$d"
out="$(run_guard "$d")"; rc=$?
if [ "$rc" -eq 0 ]; then
  echo "PASS [case60 scaffold 三块 evidenceLevel 合法 (第 39 条绿面)] 退出 0"
  PASS=$((PASS + 1))
else
  echo "FAIL [case60] scaffold 的 ModelPresets.kt 被第 39 条误红（绿面失守 —— 铺点与判据未同步）"
  printf '%s\n' "$out" | sed 's/^/    | /'
  FAIL=$((FAIL + 1))
fi

# case 61 / 61b / 61c / 62 / 62b / 63 / 63b：第 39 条（W66 深审 D1–D3 修补 + W67 B0 复审21
#   P2-① / P3-① 再收紧）—— 四个 fail-open 面：
#   D1 fail-open：旧判据只认「`ModelPreset(` 独占一行」，单行 `ModelPreset(…),` 整块被跳过。
#     case61 —— 单行块**缺** evidenceLevel ⇒ 必须判红（旧判据整块跳过 ⇒ 假绿）；
#     case61b 断言红来自真命中（输出含「缺 evidenceLevel 赋值」+ 违规 label）。
#   D2 fail-open：旧判据 `$0 ~ /W[0-9][0-9]/` 扫整块 ⇒ `label`/注释里写个 `W99` 就能骗过锚点。
#     case62 —— MEASURED 块的 W99 **只在 label / 注释里**、backendBasis/note 内无锚点 ⇒ 必须判红；
#     case62b 断言红来自真命中（输出含「但 backendBasis/note 内无本仓取证锚点」事实行）。
#   W67 B0 fail-open（复审21 P2-① 独立复现）：单行构造 + **行尾注释**
#     `ModelPreset(...), // 注释` 不被单行块正则命中（行尾不是 `)`）、无多行块开启、无块收尾
#     ⇒ 整块逃逸。case61c —— 一枚双杀两形态（块逃逸 + 尾注释里的 W99 不算锚点，P3-①）：
#     单行 + 尾注释块、MEASURED、note 字面量内无 W##、尾注释含 W99 ⇒ 必须判红且红来自
#     「MEASURED 但 backendBasis/note 内无本仓取证锚点」事实行（块被识别 + 注释 W99 未被
#     当锚点，二者缺一即 FAIL）。
#   D3 fail-open：旧判据块尾写死 8 空格 ⇒ 缩进漂移块闭不上、与下一块粘连，缺字段的块被吞。
#     case63 —— 漂移块（12 空格缩进，缺 evidenceLevel）后接合规块（8 空格）⇒ 漂移块必须判红；
#     case63b 断言未粘连（输出含漂移块 label、不含合规块 label）。
#   各面都只在 $TMP 脚手架树内覆写 ModelPresets.kt，绝不碰生产文件。
d="$TMP/case61-singleline-missing"
scaffold "$d"
cat > "$d/core-model/src/main/java/com/rickeal/agent/core/model/ModelPresets.kt" <<'KT'
package com.rickeal.agent.core.model

enum class EvidenceLevel { MEASURED, UPSTREAM, UNVERIFIED }

data class ModelPreset(
    val label: String,
    val recommended: Boolean = false,
    val evidenceLevel: EvidenceLevel = EvidenceLevel.UNVERIFIED,
)

object ModelPresets {
    val all: List<ModelPreset> = listOf(
        ModelPreset(label = "single-line-missing", recommended = false),
    )
}
KT
out="$(run_guard "$d")"; rc=$?
assert_red "case61 单行 ModelPreset 缺 evidenceLevel 赋值 (第 39 条 D1)" "$rc" "$out" \
  "ModelPreset 全 schema 显式声明 evidenceLevel 且 MEASURED 带本仓取证锚点（W66 B3）"
if printf '%s\n' "$out" | grep -qF "缺 evidenceLevel 赋值" && printf '%s\n' "$out" | grep -qF "single-line-missing"; then
  echo "PASS [case61b] 红来自真命中（单行块被识别 + 输出含违规 label）"
  PASS=$((PASS + 1))
else
  echo "FAIL [case61b] 单行块未被识别（D1 fail-open 未修）或输出不含违规 label"
  printf '%s\n' "$out" | sed 's/^/    | /'
  FAIL=$((FAIL + 1))
fi

d="$TMP/case61c-singleline-trailing-comment"
scaffold "$d"
cat > "$d/core-model/src/main/java/com/rickeal/agent/core/model/ModelPresets.kt" <<'KT'
package com.rickeal.agent.core.model

enum class EvidenceLevel { MEASURED, UPSTREAM, UNVERIFIED }

data class ModelPreset(
    val label: String,
    val recommended: Boolean = false,
    val evidenceLevel: EvidenceLevel = EvidenceLevel.UNVERIFIED,
    val knownBrokenOn: String? = null,
    val note: String = "",
)

object ModelPresets {
    val all: List<ModelPreset> = listOf(
        ModelPreset(
            label = "compliant-anchor",
            evidenceLevel = EvidenceLevel.MEASURED,
            note = "W60 真机实测通过",
        ),
        ModelPreset(label = "trailing-comment-escape", evidenceLevel = EvidenceLevel.MEASURED, note = "本条文本内无本仓锚点"), // W99 只在行尾注释里：不算锚点
    )
}
KT
out="$(run_guard "$d")"; rc=$?
if [ "$rc" -eq 1 ] && printf '%s\n' "$out" | grep -qF "但 backendBasis/note 内无本仓取证锚点" && printf '%s\n' "$out" | grep -qF "trailing-comment-escape"; then
  echo "PASS [case61c] 红来自真命中（单行+尾注释块被识别 + 尾注释 W99 未被当锚点，复审21 P2-①/P3-①）"
  PASS=$((PASS + 1))
else
  echo "FAIL [case61c] 单行+尾注释形态仍整块逃逸（P2-① fail-open 未修）或尾注释 W99 被误当锚点（P3-① 未修，红非真命中）"
  printf '%s\n' "$out" | sed 's/^/    | /'
  FAIL=$((FAIL + 1))
fi

# ---------------------------------------------------------------------------
# case 61d / 61e：第 39 条 + 第 34 条**双修**（W68 A1，复审22 P2-① 三线五跑实锤的逃逸形态）
#   case61d —— 一夹具三杀：形态A（`ModelPreset(label = "x",` 换行续写）块插在两块
#   **合规**块之间（#34 中部注入位）：
#     杀① #39 逃逸：旧两正则均不命中形态A（`(` 后非行尾、行内无闭 `)`）⇒ 缺
#          evidenceLevel 的块整块逃逸 ⇒ 修补后必须判红，且输出含「缺 evidenceLevel
#          赋值」+ 注入块 label「mid-escape-a」；
#     杀② #34 中部注入错位：旧 BLOCK_START 严式 ⇒ 注入块被吞进前块 ⇒ 红挂在**清白
#          预设**「innocent-first」名下（归因错位）⇒ 修补后红必须归位到「mid-escape-a」；
#     杀③ 合法块零误伤：innocent-first（显式 knownBrokenOn=null + recommended=true，
#          合法组合）与 tail-clean（UPSTREAM）必须保持清白 ⇒ 输出**不含**两者 label。
#     （「红必来自注入块事实行」恰好同时抓 ② 的错位红与 ① 的逃逸绿。）
#   case61e —— 绿面（防双修假红）：合法形态A / 形态B预设（evidenceLevel 齐全、
#   MEASURED 带锚点 / UPSTREAM，knownBrokenOn 与 recommended 不同真）⇒ 整树退出 0，
#     钉「放开块识别后不把合规新形态判违规」。
#   与 case61/case61c 同范式：只覆写脚手架树内 ModelPresets.kt，绝不碰生产文件。
#   fixture-first 证据链（现役守卫判绿/错位 → 修补 → 判红归位 + 干净树 exit 0）见
#   W68 A1 回报；本 case 把该触发面钉进自测网（guard_selected "34 39" 双编号）。
# ---------------------------------------------------------------------------
if guard_selected "34 39"; then
d="$TMP/case61d-formab-mid-injection"
scaffold "$d"
cat > "$d/core-model/src/main/java/com/rickeal/agent/core/model/ModelPresets.kt" <<'KT'
package com.rickeal.agent.core.model

enum class EvidenceLevel { MEASURED, UPSTREAM, UNVERIFIED }

data class ModelPreset(
    val label: String,
    val url: String = "",
    val recommended: Boolean = false,
    val knownBrokenOn: String? = null,
    val evidenceLevel: EvidenceLevel = EvidenceLevel.UNVERIFIED,
    val note: String = "",
)

object ModelPresets {
    val all: List<ModelPreset> = listOf(
        ModelPreset(
            label = "innocent-first",
            url = "https://fixture.invalid/fixture-preset.litertlm",
            recommended = true,
            knownBrokenOn = null,
            evidenceLevel = EvidenceLevel.MEASURED,
            note = "W60 实测（fixture 锚点）",
        ),
        ModelPreset(label = "mid-escape-a",
            url = "u2",
            recommended = true,
            knownBrokenOn = "0.17.1 引擎初始化 NOT_FOUND（fixture 注入）",
        ),
        ModelPreset(
            label = "tail-clean",
            url = "u3",
            recommended = false,
            evidenceLevel = EvidenceLevel.UPSTREAM,
        ),
    )
}
KT
out="$(run_guard "$d")"; rc=$?
assert_red "case61d 形态A续写块逃逸被识别 (第 39 条)" "$rc" "$out" \
  "ModelPreset 全 schema 显式声明 evidenceLevel 且 MEASURED 带本仓取证锚点（W66 B3）"
assert_red "case61d 中部注入红归位到注入块 (第 34 条)" "$rc" "$out" \
  "knownBrokenOn 非空的预设不得 recommended（已知坏容器不得置顶推荐）"
if printf '%s\n' "$out" | grep -qF "缺 evidenceLevel 赋值" && printf '%s\n' "$out" | grep -qF "mid-escape-a"; then
  echo "PASS [case61d-39] 红来自真命中（形态A块被识别 + 输出含「缺 evidenceLevel 赋值」与注入块 label）"
  PASS=$((PASS + 1))
else
  echo "FAIL [case61d-39] 形态A块仍整块逃逸（第 39 条 fail-open 未修）或输出不含注入块 label"
  printf '%s\n' "$out" | sed 's/^/    | /'
  FAIL=$((FAIL + 1))
fi
if printf '%s\n' "$out" | grep -qF "mid-escape-a" && ! printf '%s\n' "$out" | grep -qF "innocent-first" && ! printf '%s\n' "$out" | grep -qF "tail-clean"; then
  echo "PASS [case61d-34] 红来自注入块事实行（含 mid-escape-a、不含清白块 label ⇒ 归位不错位）"
  PASS=$((PASS + 1))
else
  echo "FAIL [case61d-34] 红未归位到注入块（仍挂清白预设名 = 中部注入错位未修）或误红合法块"
  printf '%s\n' "$out" | sed 's/^/    | /'
  FAIL=$((FAIL + 1))
fi

fi
# ---------------------------------------------------------------------------
# case 61e：第 39 条 + 第 34 条双修绿面（W68 A1）—— 合法形态A（换行续写）与形态B
#   （括号前空格 `ModelPreset (`）预设各一枚：evidenceLevel 齐全（MEASURED 带 W##
#   锚点 / UPSTREAM）、`recommended = true` 与显式 `knownBrokenOn = null` 合法共存
#   ⇒ 必须整树退出 0（放开块识别 + 去行首锚定后，合规新形态零误红）。
#   W68 A3 适配（第 41 条）：form-a-ok 的 url 用**骨架档案臂同名**的
#   fixture-preset.litertlm（本 case 只覆写 ModelPresets.kt、不覆写档案表 ⇒ 不锚定
#   骨架臂就会因方向B 孤儿红）；form-b-ok 的 url = "u2" 无 .litertlm 后缀 ⇒ 方向A
#   跳过（无可检查项），顺带覆盖「无 .litertlm 文件名的块不判」这条口径。
# ---------------------------------------------------------------------------
if guard_selected "34 39"; then
d="$TMP/case61e-formab-green"
scaffold "$d"
cat > "$d/core-model/src/main/java/com/rickeal/agent/core/model/ModelPresets.kt" <<'KT'
package com.rickeal.agent.core.model

enum class EvidenceLevel { MEASURED, UPSTREAM, UNVERIFIED }

data class ModelPreset(
    val label: String,
    val url: String = "",
    val recommended: Boolean = false,
    val knownBrokenOn: String? = null,
    val evidenceLevel: EvidenceLevel = EvidenceLevel.UNVERIFIED,
    val note: String = "",
)

object ModelPresets {
    val all: List<ModelPreset> = listOf(
        ModelPreset(label = "form-a-ok",
            url = "https://fixture.invalid/fixture-preset.litertlm",
            recommended = true,
            knownBrokenOn = null,
            evidenceLevel = EvidenceLevel.MEASURED,
            note = "W60 实测（fixture 锚点）",
        ),
        ModelPreset (
            label = "form-b-ok",
            url = "u2",
            recommended = false,
            evidenceLevel = EvidenceLevel.UPSTREAM,
        ),
    )
}
KT
out="$(run_guard "$d")"; rc=$?
if [ "$rc" -eq 0 ]; then
  echo "PASS [case61e 合法形态A/形态B预设 (第 39 条 / 第 34 条绿面)] 退出 0（放开识别不误红合规新形态）"
  PASS=$((PASS + 1))
else
  echo "FAIL [case61e] 合法形态A/形态B预设被误红（双修把合规新形态判违规 = 假红，第 39/34 条误伤）"
  printf '%s\n' "$out" | sed 's/^/    | /'
  FAIL=$((FAIL + 1))
fi

fi
d="$TMP/case62-anchor-in-label"
scaffold "$d"
cat > "$d/core-model/src/main/java/com/rickeal/agent/core/model/ModelPresets.kt" <<'KT'
package com.rickeal.agent.core.model

enum class EvidenceLevel { MEASURED, UPSTREAM, UNVERIFIED }

data class ModelPreset(
    val label: String,
    val recommended: Boolean = false,
    val evidenceLevel: EvidenceLevel = EvidenceLevel.UNVERIFIED,
    val note: String = "",
)

object ModelPresets {
    val all: List<ModelPreset> = listOf(
        ModelPreset(
            label = "W99-in-label-only",
            recommended = false,
            evidenceLevel = EvidenceLevel.MEASURED,
            // W99 只在注释里：不算锚点（W66 深审 D2 修复面）。
            note = "本条文本内无本仓锚点",
        ),
    )
}
KT
out="$(run_guard "$d")"; rc=$?
assert_red "case62 MEASURED 锚点只在 label/注释 (第 39 条 D2)" "$rc" "$out" \
  "ModelPreset 全 schema 显式声明 evidenceLevel 且 MEASURED 带本仓取证锚点（W66 B3）"
if printf '%s\n' "$out" | grep -qF "但 backendBasis/note 内无本仓取证锚点" && printf '%s\n' "$out" | grep -qF "W99-in-label-only"; then
  echo "PASS [case62b] 红来自真命中（label/注释里的 W99 未被当锚点）"
  PASS=$((PASS + 1))
else
  echo "FAIL [case62b] label/注释里的 W99 被误当锚点（D2 fail-open 未修）"
  printf '%s\n' "$out" | sed 's/^/    | /'
  FAIL=$((FAIL + 1))
fi

d="$TMP/case63-indent-drift-merge"
scaffold "$d"
cat > "$d/core-model/src/main/java/com/rickeal/agent/core/model/ModelPresets.kt" <<'KT'
package com.rickeal.agent.core.model

enum class EvidenceLevel { MEASURED, UPSTREAM, UNVERIFIED }

data class ModelPreset(
    val label: String,
    val recommended: Boolean = false,
    val evidenceLevel: EvidenceLevel = EvidenceLevel.UNVERIFIED,
)

object ModelPresets {
    val all: List<ModelPreset> = listOf(
            ModelPreset(
                label = "drifted-missing",
                recommended = false,
            ),
        ModelPreset(
            label = "canonical-ok",
            evidenceLevel = EvidenceLevel.UPSTREAM,
        ),
    )
}
KT
out="$(run_guard "$d")"; rc=$?
assert_red "case63 缩进漂移块缺 evidenceLevel 未被吞 (第 39 条 D3)" "$rc" "$out" \
  "ModelPreset 全 schema 显式声明 evidenceLevel 且 MEASURED 带本仓取证锚点（W66 B3）"
if printf '%s\n' "$out" | grep -qF "drifted-missing" && ! printf '%s\n' "$out" | grep -qF "canonical-ok"; then
  echo "PASS [case63b] 漂移块被独立结算（含缺字段块 label、不含合规块 label ⇒ 未粘连）"
  PASS=$((PASS + 1))
else
  echo "FAIL [case63b] 缩进漂移块与合规块粘连（D3 fail-open 未修）"
  printf '%s\n' "$out" | sed 's/^/    | /'
  FAIL=$((FAIL + 1))
fi

fi
# ---------------------------------------------------------------------------
# case 64 / 64b / 65 / 65b / 65c：W68 A3 新守卫两件（复检 Q-5 / Q-2；主理人预裁量⑦）。
#   #40（core-model 禁 feature-models 字样）：
#     case64 —— 向骨架 core-model 源文件注入一行含 `feature-models` 的注释 ⇒ 判红，
#               且红来自**注入的事实行**（file:line + 字样本身），非仅守卫名；
#     case64b —— 绿面：dot 形态（feature.models）与空格形态不触犯（字面量从严，防过度匹配）。
#   #41（ModelPresets ↔ ModelSamplingProfiles 双向一致）：
#     case65 —— 方向A 红面：新预设带 URL 文件名但档案表（骨架空注册表）无对应臂 ⇒ 判红，
#               输出含预设 label + 缺失文件名（预放下一条新预设静默漏档）；
#     case65b —— 方向B 红面：档案臂登记预设表不存在的文件名（孤儿臂）⇒ 判红，输出含
#               孤儿文件名事实行；
#     case65c —— 绿面：方向A 豁免（note 含「档案豁免」令牌）+ 方向B 同臂别名锚定
#               （int4/int8 同款形态）⇒ 整树退出 0。
#   各面只覆写脚手架树内文件，绝不碰生产文件。
# ---------------------------------------------------------------------------
if guard_selected 40; then
d="$TMP/case64-feature-models-mention"
scaffold "$d"
cat >> "$d/core-model/src/main/java/com/rickeal/agent/core/model/ModelPresets.kt" <<'KT'
// fixture 注入：迁移前的 stale 描述 —— 预设表曾位于 feature-models（第 40 条必须判红）
KT
out="$(run_guard "$d")"; rc=$?
assert_red "case64 core-model 残留 feature-models 字样 (第 40 条)" "$rc" "$out" \
  "core-model 源码禁出现 feature-models 字样（迁移后描述失效防复发）"
if printf '%s\n' "$out" | grep -qF "feature-models"; then
  echo "PASS [case64b] 红来自真命中（输出含注入的事实行：ModelPresets.kt 内的 feature-models 字样）"
  PASS=$((PASS + 1))
else
  echo "FAIL [case64b] 守卫判红但输出看不到注入的 feature-models 字样（守卫自身故障假红？）"
  printf '%s\n' "$out" | sed 's/^/    | /'
  FAIL=$((FAIL + 1))
fi

fi
if guard_selected 40; then
d="$TMP/case64c-feature-models-dot-form"
scaffold "$d"
mkdir -p "$d/core-model/src/main/java/com/rickeal/agent/core/model"
cat > "$d/core-model/src/main/java/com/rickeal/agent/core/model/FixtureNote.kt" <<'KT'
package com.rickeal.agent.core.model

// fixture：dot 形态（feature.models）与空格形态都不是 hyphen 字面量，第 40 条从严匹配不得误红
KT
out="$(run_guard "$d")"; rc=$?
if [ "$rc" -eq 0 ]; then
  echo "PASS [case64d 非连字符形态不误伤 (第 40 条绿面)] 退出 0（字面量从严，只钉 feature-models）"
  PASS=$((PASS + 1))
else
  echo "FAIL [case64d] 非连字符形态被误红（第 40 条匹配过宽，守卫公信力受损）"
  printf '%s\n' "$out" | sed 's/^/    | /'
  FAIL=$((FAIL + 1))
fi

fi
if guard_selected 41; then
d="$TMP/case41a-preset-missing-profile"
scaffold "$d"
cat > "$d/core-model/src/main/java/com/rickeal/agent/core/model/ModelPresets.kt" <<'KT'
package com.rickeal.agent.core.model

enum class EvidenceLevel { MEASURED, UPSTREAM, UNVERIFIED }

data class ModelPreset(
    val label: String,
    val url: String = "",
    val recommended: Boolean = false,
    val knownBrokenOn: String? = null,
    val evidenceLevel: EvidenceLevel = EvidenceLevel.UNVERIFIED,
    val note: String = "",
)

object ModelPresets {
    val all: List<ModelPreset> = listOf(
        ModelPreset(
            label = "new-preset-no-profile",
            url = "https://fixture.invalid/new-model.litertlm",
            recommended = false,
            evidenceLevel = EvidenceLevel.MEASURED,
            note = "W60 实测（fixture 锚点）",
        ),
    )
}
KT
out="$(run_guard "$d")"; rc=$?
assert_red "case65 新预设漏档案 (第 41 条方向A)" "$rc" "$out" \
  "ModelPresets 与 ModelSamplingProfiles 文件名双向一致（预设漏档 / 孤儿档案均判红）"
if printf '%s\n' "$out" | grep -qF "new-preset-no-profile" && printf '%s\n' "$out" | grep -qF "new-model.litertlm" && printf '%s\n' "$out" | grep -qF "方向A"; then
  echo "PASS [case65b] 红来自真命中（输出含预设 label + 缺失文件名 + 方向A 事实行）"
  PASS=$((PASS + 1))
else
  echo "FAIL [case65b] 方向A 报红但输出不含预设 label / 缺失文件名（假红或归因丢失）"
  printf '%s\n' "$out" | sed 's/^/    | /'
  FAIL=$((FAIL + 1))
fi

fi
if guard_selected 41; then
d="$TMP/case41b-orphan-profile-arm"
scaffold "$d"
cat > "$d/core-model/src/main/java/com/rickeal/agent/core/model/ModelSamplingProfiles.kt" <<'KT'
package com.rickeal.agent.core.model

object ModelSamplingProfiles {
    fun forFileName(fileName: String?): ModelSamplingProfile? = when (fileName) {
        "orphan-model.litertlm" -> ModelSamplingProfile(
            recommendedTemperature = 0.7f,
            temperatureRange = 0.3f..1.2f,
            recommendedTopK = 40,
            maxTopK = 128,
            maxContextLength = 4096,
            evidence = "fixture：孤儿档案臂",
        )
        else -> null
    }
}
KT
out="$(run_guard "$d")"; rc=$?
assert_red "case65c 孤儿档案臂 (第 41 条方向B)" "$rc" "$out" \
  "ModelPresets 与 ModelSamplingProfiles 文件名双向一致（预设漏档 / 孤儿档案均判红）"
if printf '%s\n' "$out" | grep -qF "orphan-model.litertlm" && printf '%s\n' "$out" | grep -qF "方向B"; then
  echo "PASS [case65d] 红来自真命中（输出含孤儿文件名 + 方向B 事实行）"
  PASS=$((PASS + 1))
else
  echo "FAIL [case65d] 方向B 报红但输出不含孤儿文件名（假红或归因丢失）"
  printf '%s\n' "$out" | sed 's/^/    | /'
  FAIL=$((FAIL + 1))
fi

fi
if guard_selected 41; then
d="$TMP/case41c-parity-green"
scaffold "$d"
cat > "$d/core-model/src/main/java/com/rickeal/agent/core/model/ModelPresets.kt" <<'KT'
package com.rickeal.agent.core.model

enum class EvidenceLevel { MEASURED, UPSTREAM, UNVERIFIED }

data class ModelPreset(
    val label: String,
    val url: String = "",
    val recommended: Boolean = false,
    val knownBrokenOn: String? = null,
    val evidenceLevel: EvidenceLevel = EvidenceLevel.UNVERIFIED,
    val note: String = "",
)

object ModelPresets {
    val all: List<ModelPreset> = listOf(
        ModelPreset(
            label = "covered-preset",
            url = "https://fixture.invalid/kept-model.litertlm",
            recommended = false,
            evidenceLevel = EvidenceLevel.UPSTREAM,
        ),
        ModelPreset(
            label = "alias-anchor",
            url = "https://fixture.invalid/alias-a.litertlm",
            recommended = false,
            evidenceLevel = EvidenceLevel.UPSTREAM,
        ),
        ModelPreset(
            label = "exempted-preset",
            url = "https://fixture.invalid/exempted-model.litertlm",
            recommended = false,
            evidenceLevel = EvidenceLevel.UPSTREAM,
            note = "实验容器：档案豁免（调参路线不同，待补档后销豁免，W68 fixture）",
        ),
    )
}
KT
cat > "$d/core-model/src/main/java/com/rickeal/agent/core/model/ModelSamplingProfiles.kt" <<'KT'
package com.rickeal.agent.core.model

object ModelSamplingProfiles {
    fun forFileName(fileName: String?): ModelSamplingProfile? = when (fileName) {
        "kept-model.litertlm" -> ModelSamplingProfile(
            recommendedTemperature = 0.7f,
            temperatureRange = 0.3f..1.2f,
            recommendedTopK = 40,
            maxTopK = 128,
            maxContextLength = 4096,
            evidence = "fixture：方向A 覆盖",
        )
        "alias-a.litertlm",
        "alias-b.litertlm",
        -> ModelSamplingProfile(
            recommendedTemperature = 0.7f,
            temperatureRange = 0.3f..1.2f,
            recommendedTopK = 40,
            maxTopK = 128,
            maxContextLength = 4096,
            evidence = "fixture：同臂别名（int4/int8 同款），alias-a 锚定",
        )
        else -> null
    }
}
KT
out="$(run_guard "$d")"; rc=$?
if [ "$rc" -eq 0 ]; then
  echo "PASS [case65e 覆盖+别名锚定+note 豁免 (第 41 条绿面)] 退出 0（双向一致不误红）"
  PASS=$((PASS + 1))
else
  echo "FAIL [case65e] 合法组合被第 41 条误红（豁免令牌 / 同臂别名锚定判据有偏）"
  printf '%s\n' "$out" | sed 's/^/    | /'
  FAIL=$((FAIL + 1))
fi

fi
# ---------------------------------------------------------------------------
# case53：映射表 / KNOWN_GUARDS / arch-guard.sh / case 四方一致性断言（外部审查 M-4）
#   —— 自测网自身防「静默少跑」。**自洽性 case**（不对应任何 arch-guard 守卫；之所以用
#   `guard_selected $META_CASE` 变量形态而非字面量：本 case 的静态提取面（下方
#   `guard_selected +[0-9]` 模式）不得把它自己算进「有 case 的守卫号」集合 ——
#   它不是守卫号，放进 KNOWN_GUARDS 反而会被本条断言自己的双向判据判红）。
#   违例场景（M-4 原始口子）：--affected 的「文件 → 守卫号」映射表与 KNOWN_GUARDS 都是
#   手写表；已映射文件新增守卫号时两张表都不会报错 ⇒ --only/--affected 口径**静默少跑**。
#   本断言把漂移变成显式判红（与 arch-guard「stdout 非空即判红」契约同款：违例非空 +
#   FAIL 计数 ⇒ 本脚本退出码非 0）：
#     ① 映射表（affected_guards_for_file 内 echo 的每个守卫号）必须在 arch-guard.sh
#        有定义（判据 = 编号注释 `# <N>)` 形态；**勿裸 grep 数字** —— 数字会撞注释 /
#        错误信息里的其它计数，假绿）；
#     ② KNOWN_GUARDS 的每个号必须同样有定义（反向：手写表漂移进已删守卫）；
#     ③ 每个有 selftest case 的守卫号（本文件中 `guard_selected <数字…>` 出现）必须在
#        KNOWN_GUARDS（新增 case 忘同步 ⇒ --only <新号> 报错而非静默零跑 —— 有意设计，
#        同步义务在新增方，本断言负责把「忘了」变成红）。
#   fail-open 防护：三个提取面（映射表 / case 集合 / KNOWN_GUARDS）任一**提取为空**即
#   判红（提取正则会因文件改名/重构失效，静默空集 = 假绿，禁）。
# ---------------------------------------------------------------------------
if guard_selected $META_CASE; then
SELF="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/$(basename "${BASH_SOURCE[0]}")"
# ① 映射表守卫号集合（只取 affected_guards_for_file 函数体内 echo 的纯数字串；
#    `echo FULL` 等非数字形态天然落空）。
map_guards="$(sed -n '/^affected_guards_for_file()/,/^}/p' "$SELF" \
              | grep -oE 'echo "[0-9][0-9 ]*"' | grep -oE '[0-9]+' | sort -un | paste -sd' ' -)"
# ③ 有 selftest case 的守卫号集合（`guard_selected <数字>` 调用形态；`guard_selected ALL`
#    与 `guard_selected $META_CASE` 都不含数字 ⇒ 天然排除，与定义处注释互为印证）。
case_guards="$(grep -oE 'guard_selected +[0-9][0-9 ]*' "$SELF" \
               | grep -oE '[0-9]+' | sort -un | paste -sd' ' -)"
viol=""
# fail-open 防护先行：提取面本身失效必须判红，绝不静默当「没有违例」。
[ -z "$map_guards" ]   && viol="$viol
[映射表] affected_guards_for_file 未提取到任何守卫号（提取正则/函数形态漂移 ⇒ 禁 fail-open）"
[ -z "$case_guards" ]  && viol="$viol
[case] 未提取到任何 guard_selected <数字> case（提取正则漂移 ⇒ 禁 fail-open）"
[ -z "$KNOWN_GUARDS" ] && viol="$viol
[KNOWN_GUARDS] 表为空（有人清空了它 ⇒ 禁 fail-open）"
# 定义存在性判据（① + ② 共用）：arch-guard.sh 的编号注释 `# <N>)`。
guard_defined() { grep -qE "^# $1\)" "$GUARD"; }
for g in $map_guards; do
  guard_defined "$g" || viol="$viol
[映射表] 守卫 #$g 在 arch-guard.sh 无编号注释定义（# $g) 缺失 ⇒ --affected 会静默少跑"
done
for g in $KNOWN_GUARDS; do
  guard_defined "$g" || viol="$viol
[KNOWN_GUARDS] 守卫 #$g 在 arch-guard.sh 无编号注释定义（手写表漂移进已删/冒名守卫）"
done
for g in $case_guards; do
  case " $KNOWN_GUARDS " in
    *" $g "*) ;;
    *) viol="$viol
[case↔KNOWN_GUARDS] 守卫 #$g 有 selftest case 但不在 KNOWN_GUARDS（新增 case 忘同步 ⇒ --only $g 报错而非静默零跑）" ;;
  esac
done
if [ -n "$viol" ]; then
  echo "FAIL [case53 映射表↔arch-guard↔KNOWN_GUARDS↔case 四方一致性] 发现违例："
  printf '%s\n' "$viol" | sed '/^[[:space:]]*$/d;s/^/    | /'
  FAIL=$((FAIL + 1))
else
  echo "PASS [case53 四方一致] 映射表 $(printf '%s\n' "$map_guards" | wc -w) 号 / KNOWN_GUARDS $(printf '%s\n' "$KNOWN_GUARDS" | wc -w) 号 / case $(printf '%s\n' "$case_guards" | wc -w) 号，全部互为子集且 arch-guard.sh 有定义"
  PASS=$((PASS + 1))
fi

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
