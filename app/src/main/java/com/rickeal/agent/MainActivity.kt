package com.rickeal.agent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text

/**
 * 阶段 A 的**临时占位** Activity。
 *
 * 存在的唯一目的：让 `:app:assembleDebug` 能在最小代码量下跑通，
 * 从而提前验证 Gradle 9.7.1 / AGP 9.3.2 / Kotlin 2.3.0 / Compose BOM 2026.02.00
 * 这套版本矩阵是否可编译。
 *
 * dev-B 之后会整体替换本文件，并新增 NavHost / LiquidAgentTheme 等 UI 结构。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Text("bootstrap")
        }
    }
}
