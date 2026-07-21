package com.xssh.feature.connections

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.xssh.design.components.PageContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionTransferScreen(
    onBack: (() -> Unit)?,
    vm: ConnectionTransferViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val exportBundleLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/json"),
        ) { uri: Uri? ->
            uri ?: return@rememberLauncherForActivityResult
            vm.exportBundle { text ->
                writeText(context, uri, text)
            }
        }

    val exportConfigLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("text/plain"),
        ) { uri: Uri? ->
            uri ?: return@rememberLauncherForActivityResult
            vm.exportOpenSshConfig { text ->
                writeText(context, uri, text)
            }
        }

    val importBundleLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument(),
        ) { uri: Uri? ->
            uri ?: return@rememberLauncherForActivityResult
            scope.launch {
                runCatching { readText(context, uri) }
                    .onSuccess { text ->
                        if (text == null) {
                            vm.reportIoFailure(
                                "Unable to read the selected bundle.",
                            )
                        } else {
                            vm.importBundle(text)
                        }
                    }
                    .onFailure { vm.reportIoFailure(it.message ?: "Unable to read the selected bundle.") }
            }
        }

    val importConfigLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument(),
        ) { uri: Uri? ->
            uri ?: return@rememberLauncherForActivityResult
            scope.launch {
                runCatching { readText(context, uri) }
                    .onSuccess { text ->
                        if (text == null) {
                            vm.reportIoFailure(
                                "Unable to read the selected config.",
                            )
                        } else {
                            vm.importOpenSshConfig(text)
                        }
                    }
                    .onFailure { vm.reportIoFailure(it.message ?: "Unable to read the selected config.") }
            }
        }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Transfer & migrate") },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
            )
        },
    ) { inner ->
        PageContainer(modifier = Modifier.fillMaxSize().padding(inner)) {
            Column(
                modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("Migration toolkit", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Export a full xSSH metadata bundle, import prior xSSH bundles, or migrate " +
                                "from OpenSSH/JuiceSSH-style config files. Secrets are deliberately " +
                                "excluded from exports and must be re-entered after import.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Export", style = MaterialTheme.typography.titleSmall)
                    Button(
                        onClick = { exportBundleLauncher.launch("xssh-connections-bundle.json") },
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Export xSSH bundle (.json)")
                    }
                    Button(
                        onClick = { exportConfigLauncher.launch("xssh-open-ssh-config.txt") },
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Export OpenSSH config (.txt)")
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Import", style = MaterialTheme.typography.titleSmall)
                    Button(
                        onClick = { importBundleLauncher.launch(arrayOf("application/json", "text/*")) },
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Import xSSH bundle")
                    }
                    Button(
                        onClick = { importConfigLauncher.launch(arrayOf("text/*", "application/octet-stream")) },
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Import OpenSSH / JuiceSSH config")
                    }
                }

                Card {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("Last result", style = MaterialTheme.typography.titleSmall)
                        if (state.busy) {
                            CircularProgressIndicator()
                        }
                        Text(
                            state.status.ifBlank { "No transfer operation has run yet." },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        state.details.forEach { detail ->
                            Text("• $detail", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

private suspend fun writeText(
    context: Context,
    uri: Uri,
    text: String,
) {
    withContext(Dispatchers.IO) {
        context.contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { writer ->
            writer.write(text)
        } ?: error("Unable to open destination file")
    }
}

private suspend fun readText(
    context: Context,
    uri: Uri,
): String? =
    withContext(Dispatchers.IO) {
        context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
            require(descriptor.length < 0 || descriptor.length <= MAX_IMPORT_BYTES) {
                "Import files must be 5 MiB or smaller."
            }
        }
        context.contentResolver.openInputStream(uri)?.use { input ->
            val bytes = input.readNBytes(MAX_IMPORT_BYTES.toInt() + 1)
            try {
                require(bytes.size <= MAX_IMPORT_BYTES) { "Import files must be 5 MiB or smaller." }
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString()
            } finally {
                bytes.fill(0)
            }
        }
    }

private const val MAX_IMPORT_BYTES = 5L * 1024L * 1024L
