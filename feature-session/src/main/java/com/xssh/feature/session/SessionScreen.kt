package com.xssh.feature.session

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import com.xssh.core.crypto.BiometricGate
import com.xssh.core.data.entity.SnippetEntity
import com.xssh.core.ssh.InteractiveHostKeyVerifier
import com.xssh.core.terminal.ModifierBar
import com.xssh.core.terminal.SpecialKey
import com.xssh.core.terminal.TerminalHost
import com.xssh.design.components.ChangedHostKeyDialog
import com.xssh.design.components.GlassCard
import com.xssh.design.components.KeyboardInteractiveDialog
import com.xssh.design.components.StatusPill
import com.xssh.design.components.UnknownHostKeyDialog
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionScreen(
    connectionId: String,
    onBack: () -> Unit,
    vm: SessionViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsState()
    val hostKeyPrompt by vm.hostKeyPrompt.collectAsState()
    val hostKeyEvent by vm.hostKeyEvents.collectAsState()
    val keyboardPrompt by vm.kbiPrompt.collectAsState()
    val snippets by vm.snippets.collectAsState()
    val context = LocalContext.current
    val biometricSetting by requireBiometricFlow(context)
        .map<Boolean, Boolean?> { it }
        .collectAsState(initial = null)
    val requireBiometric = biometricSetting ?: false
    val scope = rememberCoroutineScope()
    val activity = remember(context) { context.findFragmentActivity() }

    var showModifierBar by remember { mutableStateOf(true) }
    var showSnippets by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showCloseConfirmation by remember { mutableStateOf(false) }
    var biometricError by remember { mutableStateOf<String?>(null) }
    var connectAttempt by remember(connectionId) { mutableIntStateOf(1) }
    var gateStarted by remember(connectionId, connectAttempt) { mutableStateOf(false) }

    LaunchedEffect(connectionId, connectAttempt, biometricSetting) {
        val biometricRequired = biometricSetting ?: return@LaunchedEffect
        if (gateStarted) return@LaunchedEffect
        gateStarted = true
        biometricError = null
        if (biometricRequired) {
            val hostActivity = activity
            if (hostActivity == null) {
                biometricError = "Biometric unlock is unavailable in this window."
                return@LaunchedEffect
            }
            val gate = BiometricGate(hostActivity)
            if (!gate.canAuthenticate()) {
                biometricError = "Set up a screen lock or supported biometric before using session lock."
                return@LaunchedEffect
            }
            if (!gate.authenticate("Unlock xSSH", "Authenticate before connecting to this server")) {
                biometricError = "Authentication was cancelled or unsuccessful."
                return@LaunchedEffect
            }
        }
        vm.start(connectionId)
    }

    fun closeSession() {
        vm.disconnect()
        onBack()
    }

    fun requestSessionLockChange(enabled: Boolean) {
        scope.launch {
            if (!enabled) {
                setRequireBiometric(context, false)
                biometricError = null
                return@launch
            }
            val gate = activity?.let(::BiometricGate)
            if (gate == null || !gate.canAuthenticate()) {
                biometricError = "Set up a screen lock or supported biometric before enabling session lock."
            } else if (gate.authenticate("Enable session lock", "Confirm before protecting future connections")) {
                setRequireBiometric(context, true)
                biometricError = null
            } else {
                biometricError = "Session lock was not enabled because authentication was cancelled."
            }
        }
    }

    BackHandler {
        if (state.connected || state.connecting) showCloseConfirmation = true else closeSession()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            state.title.ifBlank { "Secure SSH Session" },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            StatusPill(
                                text = when {
                                    state.connected -> "Connected"
                                    state.connecting -> "Connecting…"
                                    else -> "Disconnected"
                                },
                                positive = state.connected,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (state.connected || state.connecting) showCloseConfirmation = true else closeSession()
                    }) {
                        Icon(Icons.Filled.Close, contentDescription = "Close session")
                    }
                },
                actions = {
                    IconButton(onClick = { showSnippets = true }, enabled = state.connected) {
                        Icon(Icons.AutoMirrored.Filled.LibraryBooks, contentDescription = "Command snippets", tint = MaterialTheme.colorScheme.primary)
                    }
                    IconButton(onClick = { showModifierBar = !showModifierBar }, enabled = state.connected) {
                        Icon(
                            Icons.Filled.Keyboard,
                            contentDescription = if (showModifierBar) "Hide modifier keys" else "Show modifier keys",
                        )
                    }
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Filled.Settings, contentDescription = "Session settings")
                    }
                },
            )
        },
    ) { inner ->
        Column(
            modifier = Modifier.fillMaxSize().padding(inner).background(MaterialTheme.colorScheme.background),
        ) {
            if (state.connecting) LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.primary)

            if (biometricError != null || state.statusMessage != null) {
                Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            StatusPill(if (biometricError != null) "Unlock needed" else "Session ended")
                            Text(
                                biometricError ?: state.statusMessage.orEmpty(),
                                modifier = Modifier.padding(top = 7.dp),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        TextButton(onClick = { connectAttempt += 1 }) { Text("Reconnect", fontWeight = FontWeight.Bold) }
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(2.dp)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f), MaterialTheme.shapes.extraSmall),
            ) {
                TerminalHost(
                    io = vm.shellIo,
                    onSession = vm::onSessionCreated,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            if (showModifierBar && state.connected) {
                ModifierBar(
                    ctrlArmed = state.ctrlArmed,
                    altArmed = state.altArmed,
                    onKey = { key ->
                        when (key) {
                            SpecialKey.CTRL_TOGGLE -> vm.toggleCtrl()
                            SpecialKey.ALT_TOGGLE -> vm.toggleAlt()
                            SpecialKey.PASTE -> vm.pasteFromClipboard(context)
                            else -> vm.writeSpecial(key)
                        }
                    },
                )
            }
        }
    }

    state.error?.let { error ->
        AlertDialog(
            onDismissRequest = vm::clearError,
            confirmButton = {
                if (state.connected) {
                    TextButton(onClick = vm::clearError) { Text("OK") }
                } else {
                    TextButton(onClick = {
                        vm.clearError()
                        connectAttempt += 1
                    }) { Text("Retry", fontWeight = FontWeight.Bold) }
                }
            },
            dismissButton = {
                if (!state.connected) {
                    TextButton(onClick = {
                        vm.clearError()
                        onBack()
                    }) { Text("Close") }
                }
            },
            title = { Text(if (state.connected) "Session Notice" else "Could Not Connect", fontWeight = FontWeight.Bold) },
            text = { Text(error) },
        )
    }

    hostKeyPrompt?.let { unknown ->
        UnknownHostKeyDialog(
            hostPort = unknown.hostPort,
            fingerprint = unknown.fingerprintSha256,
            keyType = unknown.keyType,
            onAccept = { vm.acceptHostKey(unknown) },
            onReject = {
                vm.rejectHostKey(unknown)
                onBack()
            },
        )
    }
    when (val event = hostKeyEvent) {
        is InteractiveHostKeyVerifier.VerificationEvent.Changed ->
            ChangedHostKeyDialog(
                hostPort = state.endpoint.ifBlank { connectionId },
                expected = event.expected,
                actual = event.actual,
                onDismiss = {
                    vm.clearHostKeyEvent()
                    closeSession()
                },
                onForgetOldKey = { vm.forgetHostKey(event.hostPort) },
            )
        InteractiveHostKeyVerifier.VerificationEvent.TimedOut ->
            AlertDialog(
                onDismissRequest = {
                    vm.clearHostKeyEvent()
                    onBack()
                },
                confirmButton = {
                    TextButton(onClick = {
                        vm.clearHostKeyEvent()
                        onBack()
                    }) { Text("OK") }
                },
                title = { Text("Verification Timed Out", fontWeight = FontWeight.Bold) },
                text = { Text("The fingerprint was not confirmed in time, so xSSH refused the connection.") },
            )
        else -> Unit
    }

    keyboardPrompt?.let { challenge ->
        KeyboardInteractiveDialog(
            prompts = challenge.prompts,
            onSubmit = challenge::respond,
            onCancel = {
                challenge.cancel()
                onBack()
            },
        )
    }

    if (showSnippets) {
        SnippetPasteSheet(
            snippets = snippets,
            onDismiss = { showSnippets = false },
            onPaste = { snippet ->
                vm.pasteSnippet(snippet.body, appendNewline = false)
                showSnippets = false
            },
            onPasteAndRun = { snippet ->
                vm.pasteSnippet(snippet.body, appendNewline = true)
                showSnippets = false
            },
        )
    }

    if (showSettings) {
        val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { showSettings = false }, sheetState = sheet) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("Session Controls", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Row(
                    modifier =
                        Modifier.fillMaxWidth().toggleable(
                            value = requireBiometric,
                            enabled = biometricSetting != null,
                            role = Role.Switch,
                            onValueChange = ::requestSessionLockChange,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Require biometric unlock before connecting", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Applies security gate to future connections.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = requireBiometric,
                        enabled = biometricSetting != null,
                        onCheckedChange = null,
                    )
                }
                Text(
                    "Pinch terminal screen to adjust text size. Long-press text to select, copy, or paste.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 20.dp),
                )
            }
        }
    }

    if (showCloseConfirmation) {
        AlertDialog(
            onDismissRequest = { showCloseConfirmation = false },
            title = { Text("Disconnect Session?", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    if (state.connected) {
                        "The remote SSH PTY channel will disconnect immediately."
                    } else {
                        "The active connection attempt will be cancelled."
                    },
                )
            },
            dismissButton = { TextButton(onClick = { showCloseConfirmation = false }) { Text("Keep Open") } },
            confirmButton = { TextButton(onClick = ::closeSession) { Text("Disconnect", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold) } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SnippetPasteSheet(
    snippets: List<SnippetEntity>,
    onDismiss: () -> Unit,
    onPaste: (SnippetEntity) -> Unit,
    onPasteAndRun: (SnippetEntity) -> Unit,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Command Snippets", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                "Tap Paste to send command text, or Paste & Run to append a trailing newline.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (snippets.isEmpty()) {
                Text("No snippets saved yet.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 12.dp))
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    items(snippets, key = { it.id }) { snippet ->
                        GlassCard {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(snippet.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        snippet.body,
                                        style =
                                            MaterialTheme.typography.bodySmall.copy(
                                                fontFamily = FontFamily.Monospace,
                                            ),
                                        color = MaterialTheme.colorScheme.primary,
                                        maxLines = 3,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                IconButton(onClick = { onPaste(snippet) }) {
                                    Icon(Icons.Filled.ContentPaste, contentDescription = "Paste ${snippet.label}")
                                }
                                IconButton(onClick = { onPasteAndRun(snippet) }) {
                                    Icon(Icons.Filled.PlayArrow, contentDescription = "Paste and run ${snippet.label}", tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private tailrec fun Context.findFragmentActivity(): FragmentActivity? =
    when (this) {
        is FragmentActivity -> this
        is ContextWrapper -> baseContext.findFragmentActivity()
        else -> null
    }
