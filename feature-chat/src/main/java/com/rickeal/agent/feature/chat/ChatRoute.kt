package com.rickeal.agent.feature.chat

import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.rickeal.agent.core.data.LocalAppContainer
import com.rickeal.agent.core.data.viewModelFactory

object ChatRoute {
    const val ARG_CONVERSATION_ID = "conversationId"
    const val ROUTE = "chat"
    const val PATTERN = "chat?conversationId={conversationId}"

    fun build(conversationId: String?): String =
        if (conversationId.isNullOrBlank()) ROUTE else "$ROUTE?$ARG_CONVERSATION_ID=$conversationId"
}

fun NavGraphBuilder.chatGraph(
    navController: NavController,
    onOpenModels: () -> Unit,
    onOpenSettings: () -> Unit,
    /** 空对话页「配置远程端点」按钮的出口（EndpointsRoute 是设置页子路由，不新建路由）。 */
    onOpenEndpoints: () -> Unit,
) {
    composable(
        route = ChatRoute.PATTERN,
        arguments = listOf(
            navArgument(ChatRoute.ARG_CONVERSATION_ID) {
                type = NavType.StringType
                nullable = true
                defaultValue = null
            },
        ),
    ) { backStackEntry ->
        val container = LocalAppContainer.current
        val conversationId = backStackEntry.arguments?.getString(ChatRoute.ARG_CONVERSATION_ID)
        val vm: ChatViewModel = viewModel(
            factory = viewModelFactory { ChatViewModel(container, conversationId) },
        )
        ChatScreen(
            viewModel = vm,
            onOpenModels = onOpenModels,
            onOpenSettings = onOpenSettings,
            onOpenEndpoints = onOpenEndpoints,
        )
    }
}
