package com.rickeal.agent

import android.app.Application
import com.rickeal.agent.core.data.AppContainer
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
            runCatching { container.bootstrap() }
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
