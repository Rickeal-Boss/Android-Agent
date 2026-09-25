package com.rickeal.agent

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.rickeal.agent.core.data.AppContainer
import com.rickeal.agent.core.data.notify.AndroidGenerationNotifier
import com.rickeal.agent.core.model.AgentLogStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * LiquidAgent 应用入口。
 *
 * 职责只有一个：持有全局唯一的 [AppContainer]（手写 DI 容器），并在启动时预热仓库快照。
 */
class LiquidAgentApplication : Application() {

    /** 进程级作用域：与 Application 同生命周期，用于冷启动预热这类「不该被取消」的任务。 */
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val container: AppContainer by lazy {
        AppContainer(this, generationIconRes = R.drawable.ic_stat_generation)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        // 「端侧生成速度」通知渠道（Wave 9 需求 5）。
        // **幂等**：渠道已存在时 createNotificationChannel 是 no-op，重复调用无害 ——
        // onCreate 每次进程冷启动都会跑，不能依赖「只建一次」。
        // IMPORTANCE_LOW = 无声、无横幅、只出现在通知栏与收起抽屉里：
        // 这是观测窗口不是提醒，每秒刷一次速度如果还要响就是骚扰。
        // 渠道重要性创建后只能降不能升，所以宁可先给低。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
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
        }
        // 进程死亡兜底（审查 P2-1）：冷启动时界面上不可能有合法的「生成中」，
        // 任何残留通知都是脏数据 —— 且 ongoing 通知在 Android 13 及以下用户划不掉，
        // 不能指望用户手动清理。stop() 幂等，无残留时 no-op。
        container.generationNotifier.stop()
        applicationScope.launch {
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
        runCatching { container.close() }
        super.onTerminate()
    }

    companion object {
        @Volatile
        private var instance: LiquidAgentApplication? = null

        /** 便于非 Compose 场景（如通知、前台服务）拿到容器；Compose 内请用 LocalAppContainer。 */
        fun containerOrNull(): AppContainer? = instance?.container
    }
}
