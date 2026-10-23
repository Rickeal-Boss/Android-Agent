package com.rickeal.agent
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.core.view.WindowCompat
import com.rickeal.agent.ui.LiquidAgentApp

/**
 * 唯一 Activity。
 *
 * `setDecorFitsSystemWindows(false)` 让壁纸贯穿到状态栏/导航栏之下 —— 这是 Liquid Glass
 * 观感的前提（玻璃必须能"透出"背后的内容）。各页面自行用 `statusBarsPadding()` /
 * `navigationBarsPadding()` 处理避让。
 *
 * 刻意**不**给 Manifest 声明 `android:configChanges`：折叠屏展开/旋转时让 Activity 正常重建，
 * 由 `LocalConfiguration` 驱动的 `rememberWindowSizeClass()` 自然拿到新断点。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            // 冷启到首帧基线锚点：供 `adb shell am start -W` 与 logcat
            // `ActivityTaskManager: Fully drawn` 读取；OEM 权限收紧时可能抛
            // SecurityException，runCatching 吞掉不影响 UI。
            LaunchedEffect(Unit) {
                runCatching { reportFullyDrawn() }
            }
            LiquidAgentApp()
        }
    }
}
