# 返回键与导航栈：根因、证据与可复制的修复模板

> **配套**：[`docs/07-fix-rationale.md`](07-fix-rationale.md) §五 D1（结论摘要，两行看完）
>
> **为什么单独立一篇**：UI-01「按返回键在三个页签间倒着走」在本仓库挂了很久，
> 而且它是 CAM-P 项目里**反复出现**的同一类问题。它**被修过一次但没修对**——
> 第一版只加了 `popUpTo(graph.startDestinationId)`，而那条 `popUpTo` 从写出来那一刻起
> 就是**失效的**，且失效方式是「打一行日志然后什么都不做」。
> 所以这里不写结论，把**证据链**留下来：每个判断都给到 `navigation 2.8.9` 的源码行号。
>
> **版本前提**：本仓库锁定 `navigation-compose = 2.8.9`（`gradle/libs.versions.toml:28`）。
> 下文所有行号**只对这一版本成立**，升级 Navigation 后必须重新核实。

---

## 0. TL;DR —— 三条硬规则

改动任何 Navigation 代码前，先过这三条：

| # | 规则 | 违反后果 |
|---|---|---|
| 1 | `startDestination` 必须与 composable 注册的 route **完全同源**（包括 `?arg={arg}` 占位符） | `popUpTo(graph.startDestinationId)` **静默失效**：打日志、`return false`、一条都不 pop |
| 2 | 不要只靠 `popUpTo` 保证返回栈收敛，必须有一个**不依赖它**的兜底 | 一旦规则 1 被破坏，按返回键会变成「毫无反应」，且无任何报错 |
| 3 | `BackHandler` 必须注册在 `NavHost` **之后** | 放在前面会被 NavHost 自己的返回回调吃掉，回调一次都不执行，同样无报错 |

三条的共同点：**失败时都是静默的**。这也是这个坑能反复踩四次的原因。

---

## 1. 症状 → 根因：识别特征

看到下面这组现象，不用再排查别的，直接查 §2：

| 观察到的现象 | 指向 |
|---|---|
| 切页签 N 次后，要按 N+ 次返回才能退出 | 回退栈随切页签**增长**（正常应恒为 1） |
| 按返回键不是回主页面，而是「在上一个页签间倒着走」 | 返回键被导航层消费，等于按栈倒序重放切页签历史 |
| 切回对话页后草稿 / 滚动位置丢失 | 每个 `chat` entry 有独立 `ViewModelStore`，又被重建了 |
| 正在流式生成的回答从界面消失（但日志还在跑） | 旧 `ChatViewModel` 还在后台，界面上的是新 VM |
| 加日志发现 `popBackStack()` 返回 `false` | 见 §2，`popUpTo` 的目标 id 在栈里不存在 |

**本仓库的对应**：`docs/03-overview.md:217` 挂了很久的 UI-01，完整符合上面前四条。

---

## 2. 根因：同一个目的地，两个不同的 id

### 2.1 id 是怎么算出来的

Navigation 里 `destination.id` 不是资源 id，而是 **route 字符串的 hash**：

- `NavDestination.kt:240` —— `route` setter 里 `id = tempRoute.hashCode()`
- `NavGraph.kt:522` —— `startDestinationRoute` setter 里 `startDestId = internalRoute.hashCode()`

两处的 `createRoute(x)` 都是 `"android-app://androidx.navigation/$x"`，所以**只要字符串不同，id 就不同**。

本仓库修复前的状态：

```
composable 注册 ：route = "chat?conversationId={conversationId}"   → id = H1
startDestination：route = "chat"                                   → id = H0   (H0 ≠ H1)
```

`graph.startDestinationId` 返回的是 **H0**，而回退栈里那条 chat entry 的 `destination.id` 是 **H1**。

### 2.2 失效方式是「静默」的

`NavController.kt:611-621`，`popBackStackInternal()` 遍历回退栈找不到该 id 时：

```kotlin
if (foundDestination == null) {
    // We were passed a destinationId that doesn't exist on our back stack.
    // Better to ignore the popBackStack than accidentally popping the entire stack
    val destinationName = NavDestination.getDisplayName(context, destinationId)
    Log.i(TAG, "Ignoring popBackStack to destination $destinationName as it was not found " +
        "on the current back stack")
    return false          // ← 一条都不 pop
}
```

**不抛异常、不崩溃、不影响功能**，只有一条 `Log.i`。
这就是它挂这么久的直接原因：**`popUpTo` 写对了也照样失效，而且没人看得出它失效了。**

### 2.3 为什么「首启」却是好的

这一点很关键，它解释了「bug 存在但应用能用」，也解释了为什么排查时容易误判：

`NavGraphNavigator.kt:74-79`，导航到 NavGraph 时找起始目的地**不走 id**，走的是 **route 字符串匹配**：

```kotlin
val startDestination =
    if (startRoute != null) {
        destination.findNode(startRoute, false)      // ← 按字符串找
    } else {
        destination.nodes[startId]
    }
```

而 `NavGraph.kt:325` 的匹配规则是 `it.route.equals(route) || it.matchRoute(route) != null`，
`"chat"` 能匹配到 `"chat?conversationId={conversationId}"`（可选 query 参数可以省略）。

**结论**：启动走字符串、返回栈操作走 id —— **两条路径用了两套判据**，所以「能启动」和「能正确 pop」是两件事。
只验证「首启正常」是发现不了这个 bug 的。

---

## 3. 为什么「改 popUpTo」还不够

就算 `popUpTo` 修好了（§4），返回键仍然不是用户要的语义：

- 从「设置 → 某个子页」按返回，是**逐级 pop**：子页 → 设置 → 对话 → 退出。
- 用户要的是**两段式**：第一次回对话页，再一次退出。

所以必须再加一层显式策略，不能只依赖导航栈的天然行为。

另有一处**必须靠兜底堵死的退化路径**：若将来有人把 `startDestination` 改回 `"chat"`，
`popUpTo` 再次静默失效，栈会长成 `[chat, models, chat]`。此时：
`navigateTop("chat")` → `launchSingleTop` 命中栈顶的 chat → **原地替换，栈不变** →
**按返回键毫无反应，且没有任何报错**。这比「在页签间倒着走」更难发现。

---

## 4. 为什么 `startDestination` 改成 PATTERN 不会让首启崩

这是当时唯一有真实风险的点（改 `startDestination` 是行为变更），逐条论证：

1. **能匹配到**：`NavGraphNavigator.kt:74-79` 按字符串找，`startRoute == "chat?conversationId={conversationId}"`
   与 `startDestination.route` **完全相等**，`NavGraph.kt:325` 第一个判据 `it.route.equals(route)` 直接命中。
2. **不会走参数合并**：`NavGraphNavigator.kt:90` 的 `if (startRoute != startDestination.route)` 为 false，跳过。
3. **不会抛「缺少必填参数」**：`NavGraphNavigator.kt:104` 会检查 `missingRequiredArguments`，
   而 `NavArgument.kt:234` 的判据是 `!it?.isNullable!! && !it.isDefaultValuePresent` ——
   本仓库的 `conversationId` 是 `nullable = true` + `defaultValue = null`，**不属于必填**，结果为空集合。

> 反过来说：如果某个参数**没有** `nullable = true` 也没有 `defaultValue`，
> 把它写进 `startDestination` 的 PATTERN 里会**直接抛 `IllegalArgumentException`**。
> 「带参数的 route 能不能当 startDestination」的答案不是「不能」，而是**「看参数是否可缺省」**。

---

## 5. 修复模板（可直接复制）

现网实现见 `app/src/main/java/com/rickeal/agent/ui/LiquidAgentApp.kt`（`MainShell()`）。

### 5.1 顶层页签跳转：保持不变

```kotlin
private fun NavHostController.navigateTop(route: String) {
    navigate(route) {
        popUpTo(graph.startDestinationId) { saveState = true; inclusive = false }
        launchSingleTop = true
        restoreState = true
    }
}
```

结果确定为「只留一个 chat 条目」而不是 `[chat, chat]`，依据是
`NavController.kt:1985` 的 `navigate()` 里 **`popUpTo` 先执行（1997-2026）、single-top 后判定（2032）**。

### 5.2 关键前提：startDestination 同源

```kotlin
NavHost(
    navController = navController,
    startDestination = ChatRoute.PATTERN,   // 不是 ChatRoute.ROUTE
) { ... }
```

### 5.3 两段式 BackHandler

```kotlin
// 必须写在 NavHost 之后 —— 见 §6
BackHandler(enabled = true) {
    val route = navController.currentBackStackEntry?.destination?.route
    if (route != null && isChatRoute(route) && navController.previousBackStackEntry == null) {
        // 第二步：已在对话页且栈里只剩它 → 退出
        if (activity != null) activity.finish()
        else Log.w(TAG, "返回键退出失败：LocalActivity 为 null")
    } else {
        navController.navigateTop(ChatRoute.ROUTE)   // 第一步：一律先回对话页
        // 兜底：让「再按一次退出」不依赖 popUpTo 是否生效。
        // 用固定次数上限，不用 while(true) —— 理由见下。
        for (i in 0 until MAX_BACK_STACK_DRAIN) {
            if (navController.previousBackStackEntry == null) break
            if (!navController.popBackStack()) break
        }
        if (navController.previousBackStackEntry != null) {
            Log.w(TAG, "返回键兜底收敛失败：优先检查 startDestination 与 route 是否同源")
        }
    }
}

private fun isChatRoute(route: String): Boolean =
    route == ChatRoute.ROUTE ||          // "chat"
    route == ChatRoute.PATTERN ||        // "chat?conversationId={conversationId}"（栈底那条的形态）
    route.startsWith("${ChatRoute.ROUTE}?")   // "chat?conversationId=xxx"
```

### 5.4 兜底为什么用「固定次数上限」而不是 `while (true)`

这里走过一次弯路：最初写的是 `while (previousBackStackEntry != null) { if (!popBackStack()) break }`，
并把收敛性押在「`popBackStack()` 同步移除 `backQueue`」上。qa-review 指出这条属于 Navigation
**内部实现**，不该作为正确性前提。核对源码后的结论（两条都成立，但结论是**仍然改用上限**）：

**同步性这条成立**（可以证明）：
`popBackStack()`(`NavController.kt:450`) → `popBackStackInternal`(588) → `executePopOperations`
→ `navigator.popBackStackInternal`(283，设置 `popFromBackStackHandler`) → `Navigator.popBackStack`
→ `ComposeNavigator`(63) → `state.popWithTransition`(119) → **`pop()`(139，同步)**
→ `NavControllerNavigatorState.pop`(328) `handler(popUpTo)` → `popEntryFromBackStack`(792)
→ **`backQueue.removeLastKt()`(802，同步)**。
所以 `previousBackStackEntry`（读 `backQueue`）确实同步递减，不会死循环。

**但仍然改用上限**，两个理由：
1. `NavigatorState.popWithTransition:122-127` 有一条**早退分支**——
   目标条目已在 `transitionsInProgress` 中时直接 `return`，`pop()` 根本不会被调用
   → handler 不触发 → `receivedPop = false` → `popBackStack()` 返回 false。
   结果是「兜底实际 pop 0 条、静默退化成三段式」。虽然不会 ANR，但**正是本文通篇在防的静默失效**。
2. 正确性不该依赖 Navigation 内部实现细节；升级 Navigation 时这类前提最容易悄悄失效。

**收敛失败的代价从「静默」变成「有 Log」**，这才是关键改动：
出问题时 logcat 会直接指向「startDestination 与 route 是否同源」，而不是让人看着"返回键要按三次"发呆。

另：`activity` 用 `LocalActivity.current`（activity-compose 1.10.1，非新依赖）而不是
`LocalContext.current as? Activity` —— 后者解析失败时 `activity?.finish()` 会**静默什么都不做**，
用户按返回没反应且无迹可寻。显式判空 + `Log.w` 让这件事有线索。

### 5.5 切会话（抽屉）后：栈底即对话页，按返回 = 退出应用

`navigateConversation()` 走 `popUpTo(startDestinationId, inclusive = true)`，之后栈里
**只剩新的 chat 条目** —— 栈底就是对话页 ⇒ **切完会话按返回会直接退出应用**。

这是**刻意保留**的：**栈不增长**优先（UI-01 的教训正是回退栈随切换无限涨）。

⚠️ **不要"反向修"成 `inclusive = false`**：目标与栈底是**同一个 `destination.id`**，
弹不掉（找不到"另一个" chat 条目）⇒ 反而变成栈增长、退出要按多次，正是本文通篇在防的
静默退化。要改只能整体重做会话切换的栈策略，不是改一个布尔值的事。

（顺带：切会话 / 新建会销毁旧 `ChatViewModel`，而 `onCleared()` **不走 `onStop()` 的收尾
路径** ⇒ 半截回答不落库。抽屉侧已加 `engineBusy` 门禁；顶栏「新对话」是同类的**既有缺陷**，
尚未收敛。详见 `LiquidAgentApp.kt` 里 `navigateConversation` 的 KDoc。）

---

## 6. `BackHandler` 的注册顺序

- `OnBackPressedDispatcher` 的回调是 **后注册的先执行**（LIFO）。
- `NavHost.kt:514` 内部注册了 `PredictiveBackHandler(currentBackStack.size > 1)`，
  **只在栈 > 1 时启用**：
  - 栈 == 1：它被 disable，自定义 `BackHandler` 必然执行（对应「第二步 → 退出」）。
  - 栈 > 1：两者都启用，靠「后注册先执行」压过它 —— **所以必须写在 NavHost 之后**。

放在 NavHost **之前**的后果：返回键被导航层先消费，自定义回调**一次都不会执行，且不报错**。

> 同理，任何在**某个屏幕内部**加的 `BackHandler` 也要注意与 `NavHost` 的相对顺序。

---

## 7. 回归自检清单

改动 Navigation / 返回逻辑后，逐项确认（CI 只跑 `assembleDebug`，**验不到运行时**，必须人工过）：

| 场景 | 期望 |
|---|---|
| 首启 | 直接进对话页，**不崩** |
| 对话 → 模型 → 按返回 | 回对话页；再按一次退出 |
| 设置 → 任意子页 → 按返回 | **直接回对话页**（不是先回设置页）；再按一次退出 |
| 连续切页签 10 次 | 按 2 次返回即退出（栈没有增长） |
| 抽屉切会话后 → 按返回 | **直接退出应用**（栈底即对话页，见 §5.5） |
| 生成中 → 抽屉里切会话 / 新建 | 会话行与「新建任务」禁用 + 列表顶部提示「正在生成，暂不可切换会话」 |
| 抽屉打开 → 按返回 | **先关抽屉**（不是回对话页）；抽屉关着时才是两段式 |
| 设置页顶栏返回箭头 | 仍是「返回上一级」语义，**不与系统返回键合并** |
| 对话页输入一半草稿 → 切到模型 → 返回 | 草稿仍在（若走了 `restoreState`） |

---

## 8. 证据怎么来的（可复现）

本机**没有 JDK / Android SDK**，所以没有跑起来验证，全部结论来自源码：

```bash
# 取 navigation-common / navigation-runtime 的 sources jar（版本必须对齐 libs.versions.toml）
curl -sSL -o nav-common.jar \
  https://dl.google.com/dl/android/maven2/androidx/navigation/navigation-common/2.8.9/navigation-common-2.8.9-sources.jar
curl -sSL -o nav-runtime.jar \
  https://dl.google.com/dl/android/maven2/androidx/navigation/navigation-runtime/2.8.9/navigation-runtime-2.8.9-sources.jar
unzip -q -o nav-common.jar -d common && unzip -q -o nav-runtime.jar -d runtime
```

然后按本文行号 grep 即可。`navigation-compose` 的 `NavHost.kt` 同理
（`https://dl.google.com/dl/android/maven2/androidx/navigation/navigation-compose/2.8.9/navigation-compose-2.8.9-sources.jar`）。

> **诚实标注**：以上是源码级论证，不是真机验证。
> 运行时行为请以 `docs/06-device-verification-checklist.md` 的实测为准。
