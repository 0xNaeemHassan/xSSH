package com.xssh.feature.connections

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.xssh.core.ssh.AuthMethod
import com.xssh.core.ssh.SshConnectionProfile
import com.xssh.design.components.DeleteConfirmationDialog
import com.xssh.design.components.EmptyState
import com.xssh.design.components.PageContainer
import com.xssh.design.components.StatusPill

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionListScreen(
    onOpenSession: (String) -> Unit,
    onOpenSftp: (String) -> Unit,
    onEdit: (String) -> Unit,
    onNew: () -> Unit,
    vm: ConnectionListViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsState()
    var pendingDelete by remember { mutableStateOf<SshConnectionProfile?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("xSSH", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "Open. Fast. Private.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onNew,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("New connection") },
            )
        },
    ) { inner ->
        PageContainer(modifier = Modifier.fillMaxSize().padding(inner)) {
            Column(modifier = Modifier.fillMaxSize()) {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = vm::onQueryChanged,
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    trailingIcon = {
                        if (state.query.isNotEmpty()) {
                            IconButton(onClick = { vm.onQueryChanged("") }) {
                                Icon(Icons.Filled.Clear, contentDescription = "Clear search")
                            }
                        }
                    },
                    placeholder = { Text("Search hosts, users, or names") },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    singleLine = true,
                    shape = MaterialTheme.shapes.large,
                )

                state.error?.let { message ->
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(message, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            IconButton(onClick = vm::clearError) {
                                Icon(Icons.Filled.Clear, contentDescription = "Dismiss error")
                            }
                        }
                    }
                }

                when {
                    state.loading ->
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    state.items.isEmpty() && state.query.isNotBlank() ->
                        EmptyState(
                            icon = Icons.Filled.Search,
                            title = "No matching connections",
                            body = "Try a host, username, profile name, or a shorter search.",
                            actionLabel = "Clear search",
                            onAction = { vm.onQueryChanged("") },
                        )
                    state.items.isEmpty() ->
                        EmptyState(
                            icon = Icons.Filled.Terminal,
                            title = "Your secure workspace starts here",
                            body =
                                "Add a server profile. Credentials stay encrypted on this device " +
                                    "and nothing is sent to a cloud account.",
                            actionLabel = "Add first connection",
                            onAction = onNew,
                        )
                    else ->
                        LazyColumn(
                            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 104.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            item {
                                Text(
                                    "${state.items.size} ${if (state.items.size == 1) "connection" else "connections"}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(vertical = 4.dp),
                                )
                            }
                            items(state.items, key = { it.id }) { profile ->
                                ConnectionCard(
                                    profile = profile,
                                    onConnect = {
                                        if (profile.username.isBlank()) {
                                            onEdit(
                                                profile.id,
                                            )
                                        } else {
                                            onOpenSession(profile.id)
                                        }
                                    },
                                    onOpenSftp = { onOpenSftp(profile.id) },
                                    onEdit = { onEdit(profile.id) },
                                    onDelete = { pendingDelete = profile },
                                )
                            }
                        }
                }
            }
        }
    }

    pendingDelete?.let { profile ->
        DeleteConfirmationDialog(
            title = "Delete ${profile.name}?",
            body = "This permanently removes the profile, its encrypted credential, and every tunnel attached to it.",
            onConfirm = {
                vm.delete(profile.id)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

@Composable
private fun ConnectionCard(
    profile: SshConnectionProfile,
    onConnect: () -> Unit,
    onOpenSftp: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Card(
        onClick = onConnect,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = CardDefaults.outlinedCardBorder(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.primaryContainer) {
                Icon(
                    Icons.Filled.Terminal,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(12.dp).size(24.dp),
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(
                    profile.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${profile.username}@${profile.host}:${profile.port}",
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatusPill(if (profile.username.isBlank()) "Needs username" else authLabel(profile.auth))
                    profile.lastUsedEpochMs?.let {
                        Text(
                            DateUtils.getRelativeTimeSpanString(it).toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "Actions for ${profile.name}")
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text("Browse files") },
                        leadingIcon = { Icon(Icons.Filled.Folder, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onOpenSftp()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Edit") },
                        leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onEdit()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                        leadingIcon = {
                            Icon(
                                Icons.Filled.DeleteOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}

private fun authLabel(auth: AuthMethod): String =
    when (auth) {
        AuthMethod.Password -> "Password"
        AuthMethod.PublicKey -> "Private key"
        AuthMethod.Agent -> "Agent key"
        AuthMethod.Interactive -> "Interactive"
    }
