package com.xssh.feature.connections

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.xssh.core.ssh.AuthMethod
import com.xssh.core.ssh.SshConnectionProfile
import com.xssh.core.ssh.TransportOptions
import com.xssh.design.components.GlassCard
import com.xssh.design.components.PageContainer
import com.xssh.design.components.SectionCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionEditScreen(
    id: String,
    onDone: () -> Unit,
    vm: ConnectionEditViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ui by vm.state.collectAsState()

    var name by rememberSaveable { mutableStateOf("") }
    var host by rememberSaveable { mutableStateOf("") }
    var port by rememberSaveable { mutableStateOf("22") }
    var user by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showPassword by rememberSaveable { mutableStateOf(false) }
    var keyPassphrase by remember { mutableStateOf("") }
    var showKeyPassphrase by rememberSaveable { mutableStateOf(false) }
    var ephemeral by rememberSaveable { mutableStateOf(false) }
    var agentForwarding by rememberSaveable { mutableStateOf(false) }
    var compression by rememberSaveable { mutableStateOf(true) }
    var keepAliveSeconds by rememberSaveable { mutableStateOf("30") }
    var connectTimeoutSeconds by rememberSaveable { mutableStateOf("10") }
    var tagsText by rememberSaveable { mutableStateOf("") }
    var auth by remember { mutableStateOf<AuthMethod>(AuthMethod.Password) }
    var existingAuth by remember { mutableStateOf<AuthMethod?>(null) }
    var existingId by remember { mutableStateOf<String?>(null) }
    var existingLastUsed by remember { mutableStateOf<Long?>(null) }
    var hasStoredPassword by remember { mutableStateOf(false) }
    var hasStoredPrivateKey by remember { mutableStateOf(false) }
    var storedKeyFingerprint by remember { mutableStateOf<String?>(null) }
    var draftKeyBytes by remember { mutableStateOf<ByteArray?>(null) }
    var draftKeyFingerprint by remember { mutableStateOf<String?>(null) }
    var draftAuthorizedKey by remember { mutableStateOf<String?>(null) }
    var draftKeyLabel by remember { mutableStateOf<String?>(null) }
    var localError by remember { mutableStateOf<String?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var initialSnapshot by remember { mutableStateOf("") }
    var showDiscardDialog by remember { mutableStateOf(false) }

    fun clearDraftKey() {
        draftKeyBytes?.fill(0)
        draftKeyBytes = null
        draftKeyFingerprint = null
        draftAuthorizedKey = null
        draftKeyLabel = null
        keyPassphrase = ""
    }

    fun snapshot(): String =
        listOf(
            name, host, port, user, auth.toString(), ephemeral.toString(), agentForwarding.toString(),
            compression.toString(), keepAliveSeconds, connectTimeoutSeconds, tagsText,
        ).joinToString("\u0001")

    val importKeyLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            uri ?: return@rememberLauncherForActivityResult
            scope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            input.readNBytes(1024 * 1024 + 1)
                        }
                    }
                }
                    .onSuccess { bytes ->
                        if (bytes == null || bytes.isEmpty()) {
                            localError = "The selected key file is empty or unreadable."
                        } else if (bytes.size > 1024 * 1024) {
                            bytes.fill(0)
                            localError = "Private key files must be smaller than 1 MiB."
                        } else {
                            clearDraftKey()
                            draftKeyBytes = bytes
                            draftKeyLabel = uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':')
                                ?: "Imported private key"
                            localError = null
                        }
                    }
                    .onFailure { localError = it.message ?: "Unable to import this key file." }
            }
        }

    LaunchedEffect(id) {
        loaded = false
        clearDraftKey()
        vm.clearError()
        localError = null
        if (id == "new") {
            existingId = null
            existingAuth = null
            loaded = true
            initialSnapshot = snapshot()
            return@LaunchedEffect
        }
        val profile = vm.load(id)
        if (profile == null) {
            localError = "This connection no longer exists."
            loaded = true
            return@LaunchedEffect
        }
        existingId = profile.id
        existingLastUsed = profile.lastUsedEpochMs
        name = profile.name
        host = profile.host
        port = profile.port.toString()
        user = profile.username
        auth = profile.auth
        existingAuth = profile.auth
        ephemeral = profile.ephemeral
        agentForwarding = profile.agentForwarding
        compression = profile.options.compression
        keepAliveSeconds = profile.options.keepAliveSeconds.toString()
        connectTimeoutSeconds = (profile.options.connectTimeoutMs / 1000).coerceAtLeast(1).toString()
        tagsText = profile.tags.joinToString(", ")
        hasStoredPassword = vm.hasStoredPassword(id)
        hasStoredPrivateKey = vm.hasStoredPrivateKey(id)
        storedKeyFingerprint = vm.fingerprintForStoredKey(id)
        initialSnapshot = snapshot()
        loaded = true
    }

    val portNumber = port.toIntOrNull()
    val keepAliveNumber = keepAliveSeconds.toIntOrNull()
    val timeoutNumber = connectTimeoutSeconds.toIntOrNull()
    val parsedTags =
        tagsText.split(',').map(String::trim).filter(String::isNotEmpty)
            .distinctBy { it.lowercase() }
    val resolvedName = name.trim().ifBlank { "$user@$host" }
    val needsPassword = auth == AuthMethod.Password
    val needsPrivateKey = auth == AuthMethod.PublicKey || auth == AuthMethod.Agent
    val passwordReady = !needsPassword || password.isNotBlank() || (existingId != null && hasStoredPassword)
    val keyReady = !needsPrivateKey || draftKeyBytes != null || (existingId != null && hasStoredPrivateKey)
    val validationError =
        when {
            host.isBlank() -> "Host is required."
            host.length > 253 || host.any(Char::isWhitespace) -> "Host must be at most 253 characters with no spaces."
            user.isBlank() -> "Username is required."
            user.length > 256 || user.any { it == '\r' || it == '\n' || it == '\u0000' } -> "Username is invalid."
            resolvedName.length > 256 || resolvedName.any(Char::isISOControl) ->
                "Display name must be at most 256 characters with no control characters."
            portNumber == null || portNumber !in 1..65_535 -> "Port must be between 1 and 65535."
            !passwordReady -> "Enter a password or keep the existing saved password."
            !keyReady -> "Import or generate a private key."
            keepAliveNumber == null || keepAliveNumber !in 0..3600 -> "Keepalive must be between 0 and 3600 seconds."
            timeoutNumber == null || timeoutNumber !in 1..120 -> "Connect timeout must be between 1 and 120 seconds."
            parsedTags.size > 100 || parsedTags.any { tag -> tag.length > 128 || tag.any(Char::isISOControl) } ->
                "Use at most 100 tags, each no longer than 128 characters."
            else -> null
        }
    val dirty = loaded && (snapshot() != initialSnapshot || password.isNotEmpty() || draftKeyBytes != null)

    fun requestExit() {
        if (dirty) {
            showDiscardDialog = true
        } else {
            clearDraftKey()
            onDone()
        }
    }
    BackHandler(enabled = dirty && !ui.saving) { showDiscardDialog = true }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (id == "new") "New Connection" else "Edit Connection",
                        fontWeight = FontWeight.Bold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = ::requestExit, enabled = !ui.saving) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { inner ->
        PageContainer(modifier = Modifier.fillMaxSize().padding(inner)) {
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    if (id == "new") "Add a Server Profile" else "Connection Settings",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "Credentials are encrypted locally with Android Keystore before reaching database storage.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                SectionCard("Server Endpoint", subtitle = "Destination hostname and port") {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it.take(256) },
                        label = { Text("Display Label (optional)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = host,
                        onValueChange = { host = it.trim().take(253) },
                        label = { Text("Host or IP Address") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            user,
                            { user = it.trim().take(256) },
                            label = { Text("Username") },
                            modifier = Modifier.weight(2f),
                            singleLine = true,
                        )
                        OutlinedTextField(
                            port,
                            { port = it.filter(Char::isDigit).take(5) },
                            label = { Text("Port") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            isError = port.isNotEmpty() && (portNumber == null || portNumber !in 1..65_535),
                        )
                    }
                    OutlinedTextField(
                        tagsText,
                        { tagsText = it.take(12_800) },
                        label = { Text("Tags (comma separated)") },
                        supportingText = { Text("Example: production, web, staging") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                }

                SectionCard("Authentication", subtitle = "Choose how this server verifies your identity") {
                    AUTH_OPTIONS.forEach { option ->
                        AuthenticationOption(
                            auth = option,
                            selected = auth == option,
                            onSelect = {
                                auth = option
                                if (option != AuthMethod.Password) password = ""
                                localError = null
                                vm.clearError()
                            },
                        )
                    }

                    if (existingAuth != null && auth != existingAuth) {
                        Text(
                            "Saving with a different authentication method removes the previously saved credential.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

                    when (auth) {
                        AuthMethod.Password ->
                            OutlinedTextField(
                                value = password,
                                onValueChange = { password = it.take(4_096) },
                                label = {
                                    Text(
                                        if (hasStoredPassword) {
                                            "New Password (blank keeps saved password)"
                                        } else {
                                            "Password"
                                        },
                                    )
                                },
                                visualTransformation =
                                    if (showPassword) {
                                        VisualTransformation.None
                                    } else {
                                        PasswordVisualTransformation()
                                    },
                                keyboardOptions =
                                    KeyboardOptions(
                                        autoCorrectEnabled = false,
                                        keyboardType = KeyboardType.Password,
                                    ),
                                trailingIcon = {
                                    IconButton(onClick = { showPassword = !showPassword }) {
                                        Icon(
                                            if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                            contentDescription = if (showPassword) "Hide password" else "Show password",
                                        )
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                            )
                        AuthMethod.PublicKey, AuthMethod.Agent -> {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = { importKeyLauncher.launch(arrayOf("*/*")) },
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text("Import Key")
                                }
                                OutlinedButton(
                                    onClick = {
                                        scope.launch {
                                            runCatching { vm.generateEd25519Draft(user.ifBlank { "xssh" }) }
                                                .onSuccess { draft ->
                                                    clearDraftKey()
                                                    draftKeyBytes = draft.privateKeyPem
                                                    draftKeyFingerprint = draft.fingerprintSha256
                                                    draftAuthorizedKey = draft.authorizedKey
                                                    draftKeyLabel = "Generated Ed25519 Key"
                                                }
                                                .onFailure { localError = it.message ?: "Key generation failed." }
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                ) { Text("Generate Ed25519") }
                            }
                            Text(
                                draftKeyLabel
                                    ?: if (hasStoredPrivateKey) {
                                        "Stored Private Key"
                                    } else {
                                        "No private key selected"
                                    },
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            (draftKeyFingerprint ?: storedKeyFingerprint)?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (draftKeyBytes != null) {
                                OutlinedTextField(
                                    keyPassphrase,
                                    { keyPassphrase = it.take(4_096) },
                                    label = { Text("Key Passphrase (if encrypted)") },
                                    visualTransformation =
                                        if (showKeyPassphrase) {
                                            VisualTransformation.None
                                        } else {
                                            PasswordVisualTransformation()
                                        },
                                    keyboardOptions =
                                        KeyboardOptions(
                                            autoCorrectEnabled = false,
                                            keyboardType = KeyboardType.Password,
                                        ),
                                    trailingIcon = {
                                        IconButton(onClick = { showKeyPassphrase = !showKeyPassphrase }) {
                                            Icon(
                                                if (showKeyPassphrase) {
                                                    Icons.Filled.VisibilityOff
                                                } else {
                                                    Icons.Filled.Visibility
                                                },
                                                contentDescription =
                                                    if (showKeyPassphrase) "Hide passphrase" else "Show passphrase",
                                            )
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                )
                            } else if (hasStoredPrivateKey) {
                                Text(
                                    "Re-import the key to update its passphrase.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            draftAuthorizedKey?.let {
                                OutlinedTextField(
                                    it,
                                    {},
                                    readOnly = true,
                                    label = { Text("Public Key (add to server's authorized_keys)") },
                                    minLines = 3,
                                    maxLines = 5,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                        AuthMethod.Interactive ->
                            Text(
                                "The server's prompts will appear interactively when you connect.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                    }
                }

                SectionCard("Transport & Privacy", subtitle = "Keepalive, timeouts, and private session mode") {
                    ToggleRow(
                        "Compression",
                        "Reduce network bandwidth on slow connections",
                        compression,
                    ) { compression = it }
                    ToggleRow(
                        "Private Session Mode",
                        "Do not record last-used timestamp or save unknown host keys",
                        ephemeral,
                    ) { ephemeral = it }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            keepAliveSeconds,
                            { keepAliveSeconds = it.filter(Char::isDigit).take(4) },
                            label = { Text("Keepalive (sec)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                        )
                        OutlinedTextField(
                            connectTimeoutSeconds,
                            { connectTimeoutSeconds = it.filter(Char::isDigit).take(3) },
                            label = { Text("Timeout (sec)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                        )
                    }
                }

                (localError ?: ui.error)?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
                validationError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Button(
                    onClick = {
                        val privateKeyCopy = draftKeyBytes?.takeIf { needsPrivateKey }?.copyOf()
                        val profile =
                            SshConnectionProfile(
                                id = existingId ?: java.util.UUID.randomUUID().toString(),
                                name = resolvedName,
                                host = host.trim(),
                                port = requireNotNull(portNumber),
                                username = user.trim(),
                                auth = auth,
                                options =
                                    TransportOptions(
                                        compression = compression,
                                        keepAliveSeconds = requireNotNull(keepAliveNumber),
                                        connectTimeoutMs = requireNotNull(timeoutNumber) * 1000,
                                    ),
                                ephemeral = ephemeral,
                                agentForwarding = false,
                                lastUsedEpochMs = existingLastUsed,
                                tags = parsedTags,
                            )
                        vm.save(
                            profile = profile,
                            plaintextPassword = password.takeIf { needsPassword && it.isNotBlank() }?.toCharArray(),
                            plaintextPrivateKey = privateKeyCopy,
                            plaintextKeyPassphrase =
                                keyPassphrase
                                    .takeIf { privateKeyCopy != null && it.isNotBlank() }
                                    ?.toCharArray(),
                            onDone = {
                                clearDraftKey()
                                onDone()
                            },
                        )
                    },
                    enabled = validationError == null && loaded && !ui.saving,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    if (ui.saving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp).padding(end = 6.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Text("Saving Connection…")
                    } else {
                        Icon(Icons.Filled.Save, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                        Text("Save Connection Profile", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text("Discard Unsaved Changes?", fontWeight = FontWeight.Bold) },
            text = { Text("Your unsaved connection profile changes will be lost.") },
            dismissButton = { TextButton(onClick = { showDiscardDialog = false }) { Text("Keep Editing") } },
            confirmButton = {
                TextButton(onClick = {
                    showDiscardDialog = false
                    clearDraftKey()
                    onDone()
                }) {
                    Text("Discard", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                }
            },
        )
    }
}

@Composable
private fun AuthenticationOption(
    auth: AuthMethod,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    val (title, body, icon) =
        when (auth) {
            AuthMethod.Password ->
                Triple(
                    "Password",
                    "Encrypted locally and used only at connection time",
                    Icons.Filled.Password,
                )
            AuthMethod.PublicKey ->
                Triple(
                    "Private Key",
                    "Authenticate directly with an imported or generated key",
                    Icons.Filled.Key,
                )
            AuthMethod.Agent ->
                Triple(
                    "Agent Key",
                    "Sign the SSH handshake using the encrypted in-app agent",
                    Icons.Filled.Security,
                )
            AuthMethod.Interactive ->
                Triple(
                    "Keyboard-Interactive",
                    "Answer dynamic challenge prompts sent by the SSH server",
                    Icons.Filled.Security,
                )
        }
    GlassCard(
        onClick = onSelect,
        containerColor =
            if (selected) {
                MaterialTheme.colorScheme.primaryContainer.copy(
                    alpha = 0.5f,
                )
            } else {
                MaterialTheme.colorScheme.surface
            },
        borderColor =
            if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outlineVariant.copy(
                    alpha = 0.5f,
                )
            },
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 12.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            RadioButton(selected = selected, onClick = null)
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    body: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier =
            Modifier.fillMaxWidth().toggleable(
                value = checked,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            ).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

private val AUTH_OPTIONS: List<AuthMethod> =
    listOf(
        AuthMethod.Password,
        AuthMethod.PublicKey,
        AuthMethod.Agent,
        AuthMethod.Interactive,
    )
