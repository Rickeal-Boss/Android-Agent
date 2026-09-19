package com.rickeal.agent

import android.app.Application
import com.rickeal.agent.core.data.AppContainer
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

    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        instance = this
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
