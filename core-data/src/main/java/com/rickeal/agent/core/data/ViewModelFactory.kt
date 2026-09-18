package com.rickeal.agent.core.data

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

/**
 * 无 Hilt 的标准 ViewModel 工厂（架构文档 §7.4）。
 *
 * 用法：`viewModel(factory = viewModelFactory { ChatViewModel(container, conversationId) })`
 *
 * 注意：本文件只能存在一份。如果 dev-B 侧也写了同名 helper，删掉其中之一即可
 * （重复声明会在 Kotlin 编译期直接报 redeclaration）。
 */
inline fun <reified VM : ViewModel> viewModelFactory(
    crossinline creator: () -> VM,
): ViewModelProvider.Factory =
    object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = creator() as T
    }
