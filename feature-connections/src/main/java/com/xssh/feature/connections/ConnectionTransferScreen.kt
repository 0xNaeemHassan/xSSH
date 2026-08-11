package com.xssh.feature.connections

import android.content.Context
import android.net.Uri
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.ImportExport
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.xssh.design.components.GlassCard
import com.xssh.design.components.PageContainer
import com.xssh.design.components.SectionCard
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
                title = { Text("Transfer & Migrate", fontWeight = FontWeight.Bold) },
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
                GlassCard(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                    borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(
                                Icons.Filled.ImportExport,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                "Migration & Backup Toolkit",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        Text(
                            "Export full xSSH metadata bundles, import prior backups, or migrate profiles from " +
                                "OpenSSH / JuiceSSH config files. Secret credentials are intentionally excluded " +
                                "from plaintext export files for security.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                SectionCard("Export Options", subtitle = "Save connection profiles to local files") {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = { exportBundleLauncher.launch("xssh-connections-bundle.json") },
                            enabled = !state.busy,
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.medium,
                        ) {
                            Icon(
                                Icons.Filled.FileUpload,
                                contentDescription = null,
                                modifier = Modifier.padding(end = 8.dp),
                            )
                            Text("Export xSSH Bundle (.json)", fontWeight = FontWeight.SemiBold)
                        }
                        OutlinedButton(
                            onClick = { exportConfigLauncher.launch("xssh-open-ssh-config.txt") },
                            enabled = !state.busy,
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.medium,
                        ) {
                            Icon(
                                Icons.Filled.Upload,
                                contentDescription = null,
                                modifier = Modifier.padding(end = 8.dp),
                            )
                            Text("Export OpenSSH Config (.txt)", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }

                SectionCard("Import & Restore", subtitle = "Load connections from external sources") {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = { importBundleLauncher.launch(arrayOf("application/json", "text/*")) },
                            enabled = !state.busy,
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.medium,
                        ) {
                            Icon(
                                Icons.Filled.FileDownload,
                                contentDescription = null,
                                modifier = Modifier.padding(end = 8.dp),
                            )
                            Text("Import xSSH Bundle", fontWeight = FontWeight.SemiBold)
                        }
                        OutlinedButton(
                            onClick = { importConfigLauncher.launch(arrayOf("text/*", "application/octet-stream")) },
                            enabled = !state.busy,
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.medium,
                        ) {
                            Icon(
                                Icons.Filled.Download,
                                contentDescription = null,
                                modifier = Modifier.padding(end = 8.dp),
                            )
                            Text("Import OpenSSH / JuiceSSH Config", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }

                SectionCard("Transfer Activity Log") {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (state.busy) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                Text("Processing transfer…", style = MaterialTheme.typography.bodyMedium)
                            }
                        } else {
                            Text(
                                state.status.ifBlank { "No transfer operation run yet." },
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                        state.details.forEach { detail ->
                            Text(
                                "• $detail",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
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
