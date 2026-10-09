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
  # 提供它（否则干净树会被本条误红）。给一个只有类声明的最小文件，行数远低于 1300。
  cat > "$root/feature-chat/src/main/java/com/rickeal/agent/feature/chat/ChatRunCoordinator.kt" <<'KT'
package com.rickeal.agent.feature.chat

class ChatRunCoordinator
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
  # 第 24 条（Wave 52，fold 收口不变式）要求 LiteRtLmEngine.kt 存在且满足
  # 「Message.user 下发点数 == foldAdjacentText 调用数」；第 25 条（Wave 52）要求其
  # 行数 ≤ 2400；第 28 条（Wave 57，韧性 store 双写双读接线）要求 adopt/persist 各 1
  # 调用点，且读回点（adopt）行号 < 首个 nativeToolChannelActive() 调用行号
  # ⇒ 骨架必须同时满足这三条（否则干净树会被误红，正例失守）。
  # fixture 给 1 处 Message.user 下发点 + 1 处 foldAdjacentText 调用（1 == 1，绿），
  # 且行数远低于 2400。def 行（`internal fun foldAdjacentText`）会被守卫的
  # `grep -v "fun foldAdjacentText"` 排除，不计入 fold_n —— 与真实文件同款。
  # Wave 57 追加：`adoptResilienceFromStore(cid)` 与 `persistResilienceToStore()` 各 1 处
  # **调用**（对应 def 行由守卫的 `grep -v "fun …"` 排除，与真实文件同款），且
  # `nativeToolChannelActive()` 的**首个代码位调用**在 adopt 调用**之后**（钉读回顺序）。
  mkdir -p "$root/core-engine/src/main/java/com/rickeal/agent/core/engine/local"
  cat > "$root/core-engine/src/main/java/com/rickeal/agent/core/engine/local/LiteRtLmEngine.kt" <<'KT'
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

# ---------------------------------------------------------------------------
# case 11：baseline 条目数超冻结值 ⇒ 第 14 条必须报红（防 baseline 变成
#          「顺手把新问题 regen 进去」的僵尸豁免入口 —— 那会让整条 lint 门禁失效）。
#          fixture 用 84 条 issue：验证计数只数 <issue 元素、不计根元素 <issues
#          （若把 <issues 也算进去，85 > 4 恒红会让本 case 的判据失真）。
#          ⚠️ 84 是相对冻结值 4 取的「明显超出」样本（Wave 38 冻结值 35 → 4 后，
#          84 > 4 仍成立，故样本无需改）。若冻结值涨到 84 以上，必须同步调大本样本。
# case11b：条目数低于冻结值（清了存量）⇒ 必须不红（「只许缩不许涨」的另一面）。
# ---------------------------------------------------------------------------
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

# ---------------------------------------------------------------------------
# case 12：测试源集里 @Test 方法以「返回值型断言」收尾 ⇒ 第 15 条必须报红。
#          Wave 37 实案：JUnit 4 要求测试方法返回 void，而 Kotlin 表达式体的返回类型
#          由末表达式决定；assertNotNull/assertIs/assertFailsWith/assertFails 会返回值
#          ⇒ 方法非 void ⇒ 整个测试类 initializationError（该类所有用例静默不跑，
#          Gradle 只报「N tests completed, 1 failed」，极具误导性）。
# ---------------------------------------------------------------------------
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

# ---------------------------------------------------------------------------
# case 12c：反引号方法名含 JVM 非法字符 ⇒ 第 15 条必须报红。
#          Wave 38 实案：JVM 规范 §4.2.2 禁止方法名含 `. ; [ /`（`<` `>` 亦不可）。
#          反引号里写「A / B」这类中文名 ⇒ Kotlin 报
#          `e: ... Name contains illegal characters: /` ⇒ compileDebugUnitTestKotlin 失败
#          ⇒ **整个 Build job 红**。本机无 JDK 完全查不出（CI 是唯一通道），故必须静态拦。
# ---------------------------------------------------------------------------
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

# ---------------------------------------------------------------------------
# case 16：注入超阈的 ChatViewModel.kt（1705 行 > 1600）⇒ 第 18 条
#          （ChatViewModel.kt 总行数）必须报红。骨架默认只有 5 行（≤1600 绿），
#          本 case 用「类声明 + 参数行」灌到 1705 行。
# case16b：红必须来自**真命中**（输出含「超 1600 行」计数行），而不是「文件不存在
#          判守卫面失效」或「守卫命令自身执行失败」—— 前者同样含守卫名，会让
#          assert_red 假绿（fixture 没铺对路径时就是这种形态，与 case12b 范式一致）。
# ---------------------------------------------------------------------------
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

# ---------------------------------------------------------------------------
# case 20 / case 21：第 20 条（ChatRunCoordinator.kt ≤ 1300，Wave 49 R-C）两面：
#   case20 —— 灌到超 1300 行 ⇒ 判红，断言红来自真命中（输出含「超 1300 行上限」计数行）。
#   case21 —— 目标文件缺失（mv 改名）⇒ 判红，断言输出含「不存在（被改名/删除？守卫面
#             已失效）」违规事实行。制造缺失用 mv 且只在 $TMP 内（与 case17 同范式）。
# ---------------------------------------------------------------------------
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
d="$TMP/case22-user-second-stamp"
scaffold "$d"
mkdir -p "$d/feature-models/src/main/java/com/rickeal/agent/feature/models"
cat > "$d/feature-models/src/main/java/com/rickeal/agent/feature/models/RogueCaps.kt" <<'KT'
package com.rickeal.agent.feature.models

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

# ---------------------------------------------------------------------------
# case 31 / case 32：第 25 条（LiteRtLmEngine.kt ≤ 2400，Wave 52）两面：
#   case31 —— 灌到超 2400 行 ⇒ 判红，断言红来自真命中（输出含「超 2400 行上限」计数行）。
#   case32 —— 目标文件缺失（mv 改名）⇒ 判红，断言输出含「不存在（被改名/删除？守卫面
#             已失效）」违规事实行。制造缺失用 mv 且只在 $TMP 内（与 case17/case21 同范式）。
# ---------------------------------------------------------------------------
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

# ---------------------------------------------------------------------------
# case 33 / case 34：第 26 条（测试基线双向同步，Wave 53）两面：
#   case33 —— 把 build.yml 的 baseline 改小（3 → 2）⇒ 实数(3) > 基线(2) ⇒「滞后」判红，
#             断言红来自真命中（输出含「滞后 1 例」违规事实行），而非「守卫命令自身执行失败」。
#   case34 —— 删掉一条 @Test 载体（3 → 2）⇒ 实数(2) < 基线(3) ⇒「删例」判红，
#             断言输出含「少 1 例」违规事实行。
#   两面都只在 $TMP 脚手架树内改（sed / 覆写），绝不碰生产文件。
#   骨架默认 build.yml `baseline=3` 且恰有 3 条 @Test ⇒ 相等（绿），由 positive case 兜住。
# ---------------------------------------------------------------------------
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

# ---------------------------------------------------------------------------
# case 35：第 27 条（A5 台账聚合句守卫，Wave 53）红面 —— 把 README 聚合句的总数
#         从「累计 3 条」改成「累计 4 条」⇒ 与 §11.0.1 逐条台账（3 条）不等 ⇒ 判红，
#         断言红来自真命中（输出含「聚合句与 §11.0.1 逐条台账不一致」违规事实行）。
#         骨架默认两份聚合句均与台账（1 ✅ / 1 ⚠️ / 1 ⛔）一致 ⇒ 绿（由 positive case 兜住）。
#         只在 $TMP 脚手架树内 sed，绝不碰生产文件。
# ---------------------------------------------------------------------------
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

# ---------------------------------------------------------------------------
# case 36 / case 37 / case 38：第 28 条（LiteRtLmEngine.kt 韧性 store 双写双读接线，
# Wave 57）三面：
#   case36 —— 从脚手架 fixture 删掉 `adoptResilienceFromStore(` **调用**（保留 def 行）
#             ⇒ adopt_n=0 ⇒ 判红；断言红来自真命中（输出含「adoptResilienceFromStore(
#             调用数(0) != 1」违规事实行），而非「守卫命令自身执行失败」。
#             ⚠️ 删的是**调用**、def 行仍在 —— 正好钉住「def 行被 `grep -v "fun …"` 排除、
#             只有真调用计入」这一判据（若排除失效，本 case 会因 adopt_n=1 而漏判）。
#   case37 —— 行序颠倒（把 `nativeToolChannelActive()` 调用挪到 adopt 调用**之前**）
#             ⇒ 判据③ 触发 ⇒ 判红；断言输出含「读回必须先于通道判定」违规事实行。
#   case38 —— 脚手架 fixture 原样（含 adopt/persist 各 1 调用 + `nativeToolChannelActive()`
#             在 adopt 之后）⇒ 必须**不红**（绿面；否则第 28 条会成为永远红的僵尸规则）；
#             并追加一段 **KDoc 提及** `adoptResilienceFromStore(` / `persistResilienceToStore(`
#             / `nativeToolChannelActive()` ⇒ 必须**仍绿**：钉死判据只认代码位（注释位被
#             `grep -vE "^[0-9]+:[[:space:]]*[*/]"` 排除，与 #24 同款）。若不排除注释位，
#             判据③ 会把真实文件 :224 那种 KDoc 提及当锚点 ⇒ **恒红**（本 case 正是防它）。
#   三面都只在 $TMP 脚手架树内覆写 fixture，绝不碰生产文件。
#   绿面（脚手架原样 → exit 0）同时由 positive case 兜住。
# ---------------------------------------------------------------------------
d="$TMP/case36-adopt-call-missing"
scaffold "$d"
cat > "$d/core-engine/src/main/java/com/rickeal/agent/core/engine/local/LiteRtLmEngine.kt" <<'KT'
package com.rickeal.agent.core.engine.local

internal fun foldAdjacentText(contents: List<Content>): List<Content> = contents

fun downlink(fresh: List<Content>): Message =
    Message.user(Contents.of(foldAdjacentText(fresh)))

class LiteRtLmEngine {
    fun ensureConversation(cid: String?) {
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
  "LiteRtLmEngine.kt 韧性 store 双写双读接线（adopt/persist 调用点各 1，且读回先于通道判定）"
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
  "LiteRtLmEngine.kt 韧性 store 双写双读接线（adopt/persist 调用点各 1，且读回先于通道判定）"
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

# ---------------------------------------------------------------------------
echo "-----------------------------------------"
echo "自测结果：PASS=$PASS FAIL=$FAIL"
if [ "$FAIL" -ne 0 ]; then
  echo "arch-guard 自测未通过：有 $FAIL 条 case 失败（守卫可能已成僵尸规则或误红）。"
  exit 1
fi
echo "arch-guard 自测全部通过。"
exit 0
