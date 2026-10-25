package com.rickeal.agent

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentCallbacks2
import com.rickeal.agent.core.data.AppContainer
import com.rickeal.agent.core.data.background.AgentForegroundService
import com.rickeal.agent.core.data.notify.AndroidGenerationNotifier
import com.rickeal.agent.core.model.AgentLogStore
import kotlinx.coroutines.launch

/**
 * LiquidAgent 应用入口。
 *
 * 职责只有一个：持有全局唯一的 [AppContainer]（手写 DI 容器），并在启动时预热仓库快照。
 */
class LiquidAgentApplication : Application() {

    // W77：原先这里自持一个 applicationScope，现统一收编到 AppContainer.processScope ——
    // 进程级作用域**全仓只能有一个**。理由不是洁癖：ChatRunCoordinator 的落库收尾
    // （onCleared 之后仍要写盘）也用同一个 scope，两处各自 new 一个 SupervisorJob
    // 会让「进程退出时还有哪些协程在跑」无法从单点回答。
    // 字段删除后行为不变：容器在 onCreate 里已被强制创建（generationNotifier.stop()）。

    val container: AppContainer by lazy {
        AppContainer(this, generationIconRes = R.drawable.ic_stat_generation)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        // W83-08：未捕获崩溃现场留痕（外审 P2-7：全仓原无 UncaughtExceptionHandler
        // ⇒ 崩溃瞬间堆栈无采集点，ERROR 落盘只覆盖主动埋点）。装在 instance 赋值后：
        // 留痕需要 containerOrNull() 兜底安装落盘 sink。同步写、链式转交原 handler，
        // 细节见 CrashSceneLogger 类 KDoc。
        CrashSceneLogger.install()
        // 「端侧生成速度」通知渠道（Wave 9 需求 5）。
        // **幂等**：渠道已存在时 createNotificationChannel 是 no-op，重复调用无害 ——
        // onCreate 每次进程冷启动都会跑，不能依赖「只建一次」。
        // IMPORTANCE_LOW = 无声、无横幅、只出现在通知栏与收起抽屉里：
        // 这是观测窗口不是提醒，每秒刷一次速度如果还要响就是骚扰。
        // 渠道重要性创建后只能降不能升，所以宁可先给低。
        // Wave 38：原先外面包着 `if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)`，
        // 但 minSdk 31 ⇒ 该判断恒真（lint ObsoleteSdkInt），故去掉判断、块体无条件执行。
        val channel = NotificationChannel(
            AndroidGenerationNotifier.CHANNEL_ID,
            "端侧生成速度",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "端侧推理运行时的实时生成速度（token/s）与首字延迟"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
        // 「后台运行」常驻通知渠道（W78 D2）。**必须独立于上面的速度通知渠道**：
        // 用户关掉「端侧生成速度」时，FGS 的常驻通知不能跟着消失 —— 那正是
        // 「已前台化但用户不可见」的形态。IMPORTANCE_LOW = 无声、无横幅；
        // 幂等（渠道已存在时 createNotificationChannel 是 no-op）。
        val backgroundChannel = NotificationChannel(
            AgentForegroundService.NOTIFICATION_CHANNEL_ID,
            "后台运行",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "生成期间保持进程与 CPU 不被系统冻结"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(backgroundChannel)
        // 进程死亡兜底（审查 P2-1）：冷启动时界面上不可能有合法的「生成中」，
        // 任何残留通知都是脏数据 —— 且 ongoing 通知在 Android 13 及以下用户划不掉，
        // 不能指望用户手动清理。stop() 幂等，无残留时 no-op。
        container.generationNotifier.stop()
        // W78 D10①：FGS 常驻通知（id 1002）的同口径清理。FGS 通知通常随服务销毁由 AMS
        // 撤掉 ⇒ 本调用是**纵深防御**而非缺陷修复（kill -9 后若本无残留即为 vacuous）。
        AgentForegroundService.clearStaleNotification(this)
        container.processScope.launch {
            // 绝不裸吞：`bootstrap()` 内部虽然已把三个仓库各自隔离（见 AppContainer），
            // 但它自己并不覆盖「三个都成功、却在别处炸了」以及协程被取消之外的异常。
            // 这里补一条 ERROR —— 冷启动预热是**静默失败**的典型：用户只会看到
            // 「端点列表空了 / 会话列表空了」，不会收到任何提示，日志是唯一的线索。
            runCatching { container.bootstrap() }
                .onFailure { t ->
                    // 不记 t.message：异常消息里可能带文件路径或配置内容。
                    AgentLogStore.error("冷启动预热失败（${t.javaClass.simpleName}）")
                }
        }
    }

    override fun onTerminate() {
        // ⚠️ 本回调在真实设备上永远不会被调用（官方文档：emulated process
        // environments only）。保留仅覆盖模拟器环境；生产环境的内存压力响应
        // 走下方 onTrimMemory（Wave 30，Operit 启发分析 B.3——此前 close()
        // 唯一调用点挂在这里，等于生产环境死代码）。
        runCatching { container.releaseEngineIfIdle() }
        super.onTerminate()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // UI 不可见 = 用户已离开，此时释放 4B 引擎的收益最大、代价最小；
        // 回前台后引擎按需重载（EngineLoadCoordinator 对加载中引擎有延迟
        // evict 保护，此处调用天然安全）。引擎忙（isBusy）时由硬闸门跳过，
        // 下一次 TRIM 再试。更高档位（MODERATE/SEVERE/CRITICAL）的热响应
        // 策略见 Wave 30 后续的 ThermalGovernor——与本方法共用同一条释放路径。
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            runCatching { container.releaseEngineIfIdle() }
                .onFailure { t ->
                    AgentLogStore.warn("TRIM 释放引擎失败（${t.javaClass.simpleName}）")
                }
        }
    }

    companion object {
        @Volatile
        private var instance: LiquidAgentApplication? = null

        /** 便于非 Compose 场景（如通知、前台服务）拿到容器；Compose 内请用 LocalAppContainer。 */
        fun containerOrNull(): AppContainer? = instance?.container
    }
}
