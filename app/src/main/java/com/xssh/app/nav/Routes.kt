package com.xssh.app.nav

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.ImportExport
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import com.xssh.feature.connections.ConnectionEditScreen
import com.xssh.feature.connections.ConnectionListScreen
import com.xssh.feature.connections.ConnectionTransferScreen
import com.xssh.feature.session.SessionScreen
import com.xssh.feature.sftp.SftpBrowserScreen
import com.xssh.feature.snippets.SnippetsScreen
import com.xssh.feature.tunnels.TunnelsScreen

object Routes {
    const val LIST = "connections"
    const val EDIT = "connections/edit/{id}"
    const val SESSION = "session/{connectionId}"
    const val SFTP = "sftp/{connectionId}"
    const val TUNNELS = "tunnels"
    const val SNIPPETS = "snippets"
    const val TRANSFER = "connections/transfer"

    fun edit(id: String) = "connections/edit/$id"

    fun session(id: String) = "session/$id"

    fun sftp(id: String) = "sftp/$id"
}

private data class TopLevelDestination(val route: String, val label: String, val icon: ImageVector)

@Composable
fun XSshNavHost(navController: NavHostController) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val destinations =
        listOf(
            TopLevelDestination(Routes.LIST, "Connections", Icons.Filled.Terminal),
            TopLevelDestination(Routes.TUNNELS, "Tunnels", Icons.Filled.Cable),
            TopLevelDestination(Routes.SNIPPETS, "Snippets", Icons.AutoMirrored.Filled.LibraryBooks),
            TopLevelDestination(Routes.TRANSFER, "Transfer", Icons.Filled.ImportExport),
        )
    val showNavigation = destinations.any { it.route == currentRoute }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (showNavigation) {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    tonalElevation = 8.dp,
                ) {
                    destinations.forEach { destination ->
                        val selected = currentRoute == destination.route
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(destination.route) {
                                    popUpTo(Routes.LIST) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(destination.icon, contentDescription = null) },
                            label = {
                                Text(
                                    destination.label,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                )
                            },
                            colors =
                                NavigationBarItemDefaults.colors(
                                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                    selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                    selectedTextColor = MaterialTheme.colorScheme.primary,
                                ),
                        )
                    }
                }
            }
        },
    ) { rootPadding ->
        NavHost(
            navController = navController,
            startDestination = Routes.LIST,
            modifier = Modifier.padding(rootPadding),
        ) {
            composable(Routes.LIST) {
                ConnectionListScreen(
                    onOpenSession = { navController.navigate(Routes.session(it)) },
                    onOpenSftp = { navController.navigate(Routes.sftp(it)) },
                    onEdit = { navController.navigate(Routes.edit(it)) },
                    onNew = { navController.navigate(Routes.edit("new")) },
                )
            }
            composable(Routes.EDIT) { entry ->
                val id = entry.arguments?.getString("id") ?: "new"
                ConnectionEditScreen(id = id, onDone = { navController.popBackStack() })
            }
            composable(Routes.SESSION) { entry ->
                val id = entry.arguments?.getString("connectionId") ?: return@composable
                SessionScreen(
                    connectionId = id,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(Routes.SFTP) { entry ->
                val id = entry.arguments?.getString("connectionId") ?: return@composable
                SftpBrowserScreen(connectionId = id, onBack = { navController.popBackStack() })
            }
            composable(Routes.TUNNELS) { TunnelsScreen(onBack = null) }
            composable(Routes.SNIPPETS) { SnippetsScreen(onBack = null) }
            composable(Routes.TRANSFER) { ConnectionTransferScreen(onBack = null) }
        }
    }
}
