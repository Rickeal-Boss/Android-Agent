package com.rickeal.agent

import android.app.Application

/**
 * LiquidAgent 应用入口。
 *
 * 阶段 A 占位实现：`AppContainer`（手写 DI 容器）由 :core-data 在阶段 B 提供，
 * 届时这里会补充 `val container: AppContainer by lazy { AppContainer(this) }`。
 */
class LiquidAgentApplication : Application() {

    override fun onCreate() {
        super.onCreate()
    }
}
