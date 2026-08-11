/*
 * xSSH — Tunnels screen.
 *
 * Manages LOCAL / REMOTE / DYNAMIC (SOCKS5) port forwards backed by
 * [TunnelManager]. UI concerns only — all SSH-side logic lives in
 * TunnelManager / TunnelRepository.
 */
package com.xssh.feature.tunnels

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.xssh.core.ssh.InteractiveHostKeyVerifier
import com.xssh.core.ssh.Tunnel
import com.xssh.design.components.ChangedHostKeyDialog
import com.xssh.design.components.DeleteConfirmationDialog
import com.xssh.design.components.EmptyState
import com.xssh.design.components.GlassCard
import com.xssh.design.components.KeyboardInteractiveDialog
import com.xssh.design.components.PageContainer
import com.xssh.design.components.StatusPill
import com.xssh.design.components.UnknownHostKeyDialog
import com.xssh.design.components.VisualTunnelDiagramCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TunnelsScreen(
    onBack: (() -> Unit)?,
    vm: TunnelsViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsState()
    val hostKeyPrompt by vm.hostKeyPrompt.collectAsState()
    val verificationEvent by vm.verificationEvent.collectAsState()
    val keyboardPrompt by vm.keyboardPrompt.collectAsState()
    var editing by remember { mutableStateOf<TunnelRecord?>(null) }
    var pendingDelete by remember { mutableStateOf<TunnelRecord?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Port Forwards & Tunnels", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            val firstConn = state.connections.firstOrNull()
            if (firstConn != null) {
                ExtendedFloatingActionButton(
                    onClick = { editing = vm.newTunnel(firstConn.id) },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("New Tunnel", fontWeight = FontWeight.Bold) },
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        },
    ) { inner ->
        PageContainer(modifier = Modifier.fillMaxSize().padding(inner)) {
            Column(modifier = Modifier.fillMaxSize()) {
                state.error?.let { error ->
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(error, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = vm::clearError) { Text("Dismiss") }
                        }
                    }
                }
                if (state.connections.isEmpty()) {
                    EmptyState(
                        icon = Icons.Filled.Warning,
                        title = "Server Connection Required",
                        body = "Add an SSH server profile first, then return here to configure port forwards.",
                    )
                } else if (state.rows.isEmpty()) {
                    EmptyState(
                        icon = Icons.Filled.Add,
                        title = "No Tunnels Configured",
                        body = "Create a local (-L), remote (-R), or dynamic SOCKS5 (-D) port forward.",
                        actionLabel = "Create Tunnel",
                        onAction = { editing = vm.newTunnel(state.connections.first().id) },
                    )
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 104.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(state.rows, key = { it.record.tunnel.id }) { row ->
                            TunnelRowCard(
                                row = row,
                                onToggle = { vm.toggle(row) },
                                onEdit = { editing = row.record },
                                onDelete = { pendingDelete = row.record },
                            )
                        }
                    }
                }
            }
        }
    }

    editing?.let { rec ->
        TunnelEditSheet(
            initial = rec,
            connections = state.connections.map { it.id to it.name },
            onDismiss = { editing = null },
            onSave = { updated ->
                vm.save(updated)
                editing = null
            },
        )
    }

    pendingDelete?.let { record ->
        DeleteConfirmationDialog(
            title = "Delete this tunnel?",
            body = "The forward will stop immediately and its saved definition will be removed.",
            onConfirm = {
                vm.delete(record)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }

    hostKeyPrompt?.let { prompt ->
        UnknownHostKeyDialog(
            hostPort = prompt.key.hostPort,
            fingerprint = prompt.key.fingerprintSha256,
            keyType = prompt.key.keyType,
            onAccept = { vm.acceptHostKey(prompt.tunnelId, prompt.key) },
            onReject = { vm.rejectHostKey(prompt.tunnelId, prompt.key) },
        )
    }
    verificationEvent?.let { wrapped ->
        when (val event = wrapped.event) {
            is InteractiveHostKeyVerifier.VerificationEvent.Changed ->
                ChangedHostKeyDialog(
                    hostPort =
                        state.rows.firstOrNull { it.record.tunnel.id == wrapped.tunnelId }
                            ?.record?.tunnel?.connectionId
                            ?.let { connectionId -> state.connections.firstOrNull { it.id == connectionId } }
                            ?.let { "${it.host}:${it.port}" }
                            ?: "Tunnel server",
                    expected = event.expected,
                    actual = event.actual,
                    onDismiss = vm::clearVerificationEvent,
                    onForgetOldKey = { vm.forgetHostKey(wrapped.tunnelId, event.hostPort) },
                )
            InteractiveHostKeyVerifier.VerificationEvent.TimedOut ->
                AlertDialog(
                    onDismissRequest = vm::clearVerificationEvent,
                    confirmButton = { TextButton(onClick = vm::clearVerificationEvent) { Text("OK") } },
                    title = { Text("Verification Timed Out", fontWeight = FontWeight.Bold) },
                    text = {
                        Text(
                            "The tunnel was not started because its server fingerprint was not confirmed in time.",
                        )
                    },
                )
            else -> Unit
        }
    }
    keyboardPrompt?.let { prompt ->
        KeyboardInteractiveDialog(
            prompts = prompt.prompts,
            onSubmit = prompt::respond,
            onCancel = prompt::cancel,
        )
    }
}

@Composable
private fun TunnelRowCard(
    row: TunnelRow,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val t = row.record.tunnel
    GlassCard {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        row.record.label.ifBlank { defaultLabel(t) },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "${kindLabel(t.kind)}  •  ${row.connectionName}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                IconButton(onClick = onToggle) {
                    if (row.runtime.starting) {
                        CircularProgressIndicator(modifier = Modifier.padding(10.dp), strokeWidth = 2.dp)
                    } else if (row.runtime.running) {
                        Icon(Icons.Filled.Stop, contentDescription = "Stop", tint = MaterialTheme.colorScheme.error)
                    } else {
                        Icon(Icons.Filled.PlayArrow, contentDescription = "Start", tint = MaterialTheme.colorScheme.primary)
                    }
                }
                IconButton(onClick = onEdit) { Icon(Icons.Filled.Edit, contentDescription = "Edit") }
                IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error) }
            }

            VisualTunnelDiagramCard(
                kind = kindLabel(t.kind),
                bind = "${t.bindHost}:${t.bindPort}",
                dest = if (t.kind == Tunnel.Kind.DYNAMIC) null else "${t.destHost}:${t.destPort}",
                active = row.runtime.running,
            )

            row.runtime.error?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

private fun kindLabel(k: Tunnel.Kind) =
    when (k) {
        Tunnel.Kind.LOCAL -> "LOCAL (-L)"
        Tunnel.Kind.REMOTE -> "REMOTE (-R)"
        Tunnel.Kind.DYNAMIC -> "DYNAMIC SOCKS5 (-D)"
    }

private fun defaultLabel(t: Tunnel): String =
    when (t.kind) {
        Tunnel.Kind.LOCAL -> "-L ${t.bindHost}:${t.bindPort} ➔ ${t.destHost}:${t.destPort}"
        Tunnel.Kind.REMOTE -> "-R ${t.bindHost}:${t.bindPort} 🡨 ${t.destHost}:${t.destPort}"
        Tunnel.Kind.DYNAMIC -> "-D ${t.bindHost}:${t.bindPort} (SOCKS5)"
    }

// --- Edit sheet ---------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TunnelEditSheet(
    initial: TunnelRecord,
    connections: List<Pair<String, String>>,
    onDismiss: () -> Unit,
    onSave: (TunnelRecord) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var connectionId by remember(initial.tunnel.id) { mutableStateOf(initial.tunnel.connectionId) }
    var kind by remember(initial.tunnel.id) { mutableStateOf(initial.tunnel.kind) }
    var label by remember(initial.tunnel.id) { mutableStateOf(initial.label) }
    var bindHost by remember(initial.tunnel.id) { mutableStateOf(initial.tunnel.bindHost) }
    var bindPort by remember(initial.tunnel.id) { mutableStateOf(initial.tunnel.bindPort.toString()) }
    var destHost by remember(initial.tunnel.id) { mutableStateOf(initial.tunnel.destHost.orEmpty()) }
    var destPort by remember(initial.tunnel.id) { mutableStateOf(initial.tunnel.destPort?.toString().orEmpty()) }
    var exposeOnLan by remember(initial.tunnel.id) { mutableStateOf(bindHost == "0.0.0.0") }
    var autoStart by remember(initial.tunnel.id) { mutableStateOf(initial.tunnel.autoStart) }
    var validationError by remember(initial.tunnel.id) { mutableStateOf<String?>(null) }
    var confirmDiscard by remember(initial.tunnel.id) { mutableStateOf(false) }

    LaunchedEffect(exposeOnLan) {
        bindHost =
            when {
                exposeOnLan -> "0.0.0.0"
                !exposeOnLan && bindHost == "0.0.0.0" -> "127.0.0.1"
                else -> bindHost
            }
    }

    val dirty =
        connectionId != initial.tunnel.connectionId ||
            kind != initial.tunnel.kind ||
            label != initial.label ||
            bindHost != initial.tunnel.bindHost ||
            bindPort != initial.tunnel.bindPort.toString() ||
            destHost != initial.tunnel.destHost.orEmpty() ||
            destPort != initial.tunnel.destPort?.toString().orEmpty() ||
            autoStart != initial.tunnel.autoStart ||
            exposeOnLan != (initial.tunnel.bindHost == "0.0.0.0")
    val requestDismiss = { if (dirty) confirmDiscard = true else onDismiss() }

    ModalBottomSheet(onDismissRequest = requestDismiss, sheetState = sheetState) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                if (initial.label.isNotBlank()) "Edit Tunnel" else "New Port Forward",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )

            // Kind picker
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                Tunnel.Kind.entries.forEachIndexed { idx, k ->
                    SegmentedButton(
                        selected = k == kind,
                        onClick = { kind = k },
                        shape = SegmentedButtonDefaults.itemShape(idx, Tunnel.Kind.entries.size),
                    ) { Text(shortKind(k), fontWeight = FontWeight.Bold) }
                }
            }

            // Connection picker
            ConnectionPicker(
                value = connectionId,
                options = connections,
                onChange = { connectionId = it },
            )

            OutlinedTextField(
                value = label,
                onValueChange = { label = it.take(256) },
                label = { Text("Label (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = bindHost,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(if (kind == Tunnel.Kind.REMOTE) "Remote Bind Host" else "Local Bind Host") },
                    singleLine = true,
                    modifier = Modifier.weight(2f),
                )
                OutlinedTextField(
                    value = bindPort,
                    onValueChange = { bindPort = it.filter(Char::isDigit).take(5) },
                    label = { Text("Port") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
            }

            if (kind != Tunnel.Kind.DYNAMIC) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = destHost,
                        onValueChange = { destHost = it.trim().take(253) },
                        label = { Text(if (kind == Tunnel.Kind.LOCAL) "Dest Host (remote)" else "Dest Host (local)") },
                        singleLine = true,
                        modifier = Modifier.weight(2f),
                    )
                    OutlinedTextField(
                        value = destPort,
                        onValueChange = { destPort = it.filter(Char::isDigit).take(5) },
                        label = { Text("Port") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            // LAN-bind warning
            Row(
                modifier =
                    Modifier.fillMaxWidth().toggleable(
                        value = exposeOnLan,
                        role = Role.Switch,
                        onValueChange = { exposeOnLan = it },
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Switch(checked = exposeOnLan, onCheckedChange = null)
                Text("Expose on LAN (0.0.0.0)", modifier = Modifier.padding(start = 8.dp), fontWeight = FontWeight.SemiBold)
            }
            if (exposeOnLan) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Warning,
                        contentDescription = "Security warning",
                        tint = MaterialTheme.colorScheme.error,
                    )
                    Text(
                        if (kind == Tunnel.Kind.REMOTE) {
                            "  Anyone able to reach the SSH server may connect to this remote port."
                        } else {
                            "  Anyone on your local Wi-Fi/LAN will be able to reach this local port."
                        },
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            Row(
                modifier =
                    Modifier.fillMaxWidth().toggleable(
                        value = autoStart,
                        role = Role.Switch,
                        onValueChange = { autoStart = it },
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Switch(checked = autoStart, onCheckedChange = null)
                Text("Auto-start with app", modifier = Modifier.padding(start = 8.dp), fontWeight = FontWeight.SemiBold)
            }

            validationError?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = requestDismiss) { Text("Cancel") }
                TextButton(
                    onClick = {
                        val port = bindPort.toIntOrNull()
                        if (port == null || port <= 0 || port > 65_535) {
                            validationError = "Bind port must be 1..65535"
                            return@TextButton
                        }
                        if (kind != Tunnel.Kind.DYNAMIC) {
                            val dp = destPort.toIntOrNull()
                            val invalidDestination =
                                listOf(
                                    dp == null,
                                    dp != null && dp !in 1..65_535,
                                    destHost.isBlank(),
                                ).any { it }
                            if (invalidDestination) {
                                validationError =
                                    "LOCAL/REMOTE tunnels need a destination host and port"
                                return@TextButton
                            }
                        }
                        if (label.any(Char::isISOControl)) {
                            validationError = "Label cannot contain control characters"
                            return@TextButton
                        }
                        if (
                            kind != Tunnel.Kind.DYNAMIC &&
                            (destHost.any(Char::isWhitespace) || destHost.any(Char::isISOControl))
                        ) {
                            validationError = "Destination host cannot contain spaces or control characters"
                            return@TextButton
                        }
                        val built =
                            runCatching {
                                Tunnel(
                                    id = initial.tunnel.id,
                                    connectionId = connectionId,
                                    kind = kind,
                                    bindHost = bindHost.ifBlank { "127.0.0.1" },
                                    bindPort = port,
                                    destHost = if (kind == Tunnel.Kind.DYNAMIC) null else destHost,
                                    destPort = if (kind == Tunnel.Kind.DYNAMIC) null else destPort.toIntOrNull(),
                                    autoStart = autoStart,
                                )
                            }
                        built
                            .onSuccess { onSave(TunnelRecord(it, label = label)) }
                            .onFailure { validationError = it.message ?: "Invalid tunnel" }
                    },
                ) { Text("Save Tunnel", fontWeight = FontWeight.Bold) }
            }
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard Tunnel Changes?", fontWeight = FontWeight.Bold) },
            text = { Text("Your unsaved tunnel configuration will be lost.") },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) { Text("Keep Editing") }
            },
            confirmButton = {
                TextButton(onClick = onDismiss) { Text("Discard", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold) }
            },
        )
    }
}

private fun shortKind(k: Tunnel.Kind) =
    when (k) {
        Tunnel.Kind.LOCAL -> "-L"
        Tunnel.Kind.REMOTE -> "-R"
        Tunnel.Kind.DYNAMIC -> "-D"
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConnectionPicker(
    value: String,
    options: List<Pair<String, String>>,
    onChange: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedName = options.firstOrNull { it.first == value }?.second ?: "—"
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
        OutlinedTextField(
            value = selectedName,
            onValueChange = {},
            readOnly = true,
            label = { Text("Target SSH Connection") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable, true),
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (id, name) ->
                DropdownMenuItem(text = { Text(name) }, onClick = {
                    onChange(id)
                    expanded = false
                })
            }
        }
    }
}
