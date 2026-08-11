package com.xssh.feature.sftp

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import com.xssh.core.ssh.InteractiveHostKeyVerifier
import com.xssh.core.ssh.SftpEntry
import com.xssh.design.components.ChangedHostKeyDialog
import com.xssh.design.components.DeleteConfirmationDialog
import com.xssh.design.components.EmptyState
import com.xssh.design.components.GlassCard
import com.xssh.design.components.KeyboardInteractiveDialog
import com.xssh.design.components.PageContainer
import com.xssh.design.components.StatusPill
import com.xssh.design.components.UnknownHostKeyDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SftpBrowserScreen(
    connectionId: String,
    onBack: () -> Unit,
    vm: SftpViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsState()
    val hostKeyPrompt by vm.hostKeyPrompt.collectAsState()
    val hostKeyEvent by vm.hostKeyEvents.collectAsState()
    val keyboardPrompt by vm.keyboardPrompt.collectAsState()
    val overwritePrompt by vm.overwritePrompt.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingDownload by remember { mutableStateOf<SftpEntry?>(null) }
    var renameEntry by remember { mutableStateOf<SftpEntry?>(null) }
    var deleteEntry by remember { mutableStateOf<SftpEntry?>(null) }
    var showNewFolder by remember { mutableStateOf(false) }
    var confirmEditorDiscard by rememberSaveable { mutableStateOf(false) }

    val createDocument =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/octet-stream"),
        ) { uri: Uri? ->
            val entry = pendingDownload
            pendingDownload = null
            if (uri != null && entry != null) {
                vm.download(entry, uri)
            }
        }

    val openDocument =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            uri ?: return@rememberLauncherForActivityResult
            scope.launch(Dispatchers.IO) {
                runCatching {
                    val metadata = queryDocumentMetadata(context, uri)
                    vm.upload(metadata.first, uri, metadata.second)
                }.onFailure { vm.reportError(it.message ?: "Unable to queue this upload.") }
            }
        }

    val externalEditorLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            vm.externalEditorReturned()
        }
    LaunchedEffect(state.externalEditor?.localPath) {
        val edit = state.externalEditor ?: return@LaunchedEffect
        if (edit.returned || edit.launched) return@LaunchedEffect
        runCatching {
            val uri =
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    File(edit.localPath),
                )
            val intent =
                Intent(Intent.ACTION_EDIT)
                    .setDataAndType(uri, "text/plain")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    .apply { clipData = ClipData.newRawUri(edit.entryName, uri) }
            vm.externalEditorLaunched()
            externalEditorLauncher.launch(Intent.createChooser(intent, "Edit ${edit.entryName}"))
        }.onFailure { failure ->
            vm.externalEditorLaunchFailed(failure.message ?: "No compatible external editor is installed.")
        }
    }

    LaunchedEffect(connectionId) { vm.attach(connectionId) }
    DisposableEffect(connectionId) {
        onDispose { vm.detach() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Remote SFTP Browser", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(
                            state.path,
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        vm.detach()
                        onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = vm::goUp, enabled = state.connected && state.path != "/" && !state.loading) {
                        Icon(Icons.Filled.ArrowUpward, contentDescription = "Parent folder")
                    }
                    IconButton(onClick = { showNewFolder = true }, enabled = state.connected && !state.loading) {
                        Icon(Icons.Filled.Add, contentDescription = "New folder")
                    }
                    IconButton(
                        onClick = { openDocument.launch(arrayOf("*/*")) },
                        enabled = state.connected && !state.loading,
                    ) {
                        Icon(Icons.Filled.Upload, contentDescription = "Upload file", tint = MaterialTheme.colorScheme.primary)
                    }
                    IconButton(onClick = { vm.refresh() }, enabled = state.connected && !state.loading) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                    }
                },
            )
        },
    ) { inner ->
        PageContainer(modifier = Modifier.fillMaxSize().padding(inner)) {
            Column(modifier = Modifier.fillMaxSize()) {
                PathBreadcrumbBar(
                    path = state.path,
                    onNavigateTo = { targetPath ->
                        if (targetPath != state.path) {
                            vm.refresh(targetPath)
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
                )

                if (state.loading || state.externalEditorPreparing) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.primary)
                }

                state.error?.let { error ->
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(error, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            if (state.connected) {
                                IconButton(onClick = vm::clearError) {
                                    Icon(Icons.Filled.Close, contentDescription = "Dismiss error")
                                }
                            } else {
                                TextButton(onClick = { vm.reconnect(connectionId) }) { Text("Retry", fontWeight = FontWeight.Bold) }
                            }
                        }
                    }
                }

                state.transfer?.let { transfer ->
                    GlassCard(modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(transfer.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                    Text(
                                        if (transfer.totalBytes > 0) {
                                            "${Formatter.formatFileSize(
                                                context,
                                                transfer.bytesTransferred,
                                            )} of ${Formatter.formatFileSize(context, transfer.totalBytes)}"
                                        } else {
                                            "${Formatter.formatFileSize(
                                                context,
                                                transfer.bytesTransferred,
                                            )} transferred"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                TextButton(onClick = vm::cancelCurrent) { Text("Cancel") }
                            }
                            if (transfer.totalBytes > 0) {
                                LinearProgressIndicator(
                                    progress = {
                                        (transfer.bytesTransferred.toFloat() / transfer.totalBytes).coerceIn(
                                            0f,
                                            1f,
                                        )
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            } else {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }

                if (state.queue.isNotEmpty()) {
                    TransferQueueSection(
                        queue = state.queue,
                        onRetry = vm::retryTransfer,
                        onClearEntry = vm::clearQueueEntry,
                        onClearFinished = vm::clearFinishedQueue,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    )
                }

                when {
                    state.loading && state.entries.isEmpty() ->
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    state.connected && state.entries.isEmpty() && state.error == null ->
                        EmptyState(
                            icon = Icons.Filled.FolderOpen,
                            title = "This folder is empty",
                            body = "Upload a file or create a folder here.",
                            actionLabel = "Upload File",
                            onAction = { openDocument.launch(arrayOf("*/*")) },
                        )
                    state.connected ->
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(state.entries, key = { it.name }) { entry ->
                                FileRow(
                                    entry = entry,
                                    onOpen = {
                                        if (entry.isDir) {
                                            vm.enter(entry.name)
                                        } else {
                                            pendingDownload = entry
                                            createDocument.launch(entry.name)
                                        }
                                    },
                                    onDownload = {
                                        pendingDownload = entry
                                        createDocument.launch(entry.name)
                                    },
                                    onEdit = { vm.openTextEditor(entry) },
                                    onExternalEdit = { vm.prepareExternalEditor(entry) },
                                    onRename = { renameEntry = entry },
                                    onDelete = { deleteEntry = entry },
                                )
                            }
                        }
                    else ->
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("Connect to browse remote files", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                }
            }
        }
    }

    if (showNewFolder) {
        NameDialog(
            title = "Create New Folder",
            initial = "",
            confirmLabel = "Create",
            onDismiss = { showNewFolder = false },
            onConfirm = {
                vm.mkdir(it)
                showNewFolder = false
            },
        )
    }
    renameEntry?.let { entry ->
        NameDialog(
            title = "Rename ${entry.name}",
            initial = entry.name,
            confirmLabel = "Rename",
            onDismiss = { renameEntry = null },
            onConfirm = {
                vm.rename(entry, it)
                renameEntry = null
            },
        )
    }
    deleteEntry?.let { entry ->
        DeleteConfirmationDialog(
            title = "Delete ${entry.name}?",
            body =
                if (entry.isDir) {
                    "The remote folder must be empty. This action cannot be undone."
                } else {
                    "The remote file will be permanently deleted."
                },
            onConfirm = {
                vm.delete(entry)
                deleteEntry = null
            },
            onDismiss = { deleteEntry = null },
        )
    }

    state.editor?.let { editor ->
        val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val requestEditorClose = {
            if (editor.text != editor.originalText) confirmEditorDiscard = true else vm.closeEditor()
        }
        ModalBottomSheet(onDismissRequest = requestEditorClose, sheetState = sheet) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Quick Text Editor", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    editor.entryName,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.primary,
                )
                editor.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                OutlinedTextField(
                    value = editor.text,
                    onValueChange = vm::updateEditorText,
                    label = { Text("UTF-8 Content") },
                    minLines = 12,
                    maxLines = 20,
                    keyboardOptions =
                        KeyboardOptions(
                            autoCorrectEnabled = false,
                            keyboardType = KeyboardType.Ascii,
                        ),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !editor.saving,
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = requestEditorClose, enabled = !editor.saving) { Text("Cancel") }
                    TextButton(onClick = vm::saveEditor, enabled = !editor.saving) {
                        Text(if (editor.saving) "Saving to Server…" else "Save to Server", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
    if (confirmEditorDiscard) {
        AlertDialog(
            onDismissRequest = { confirmEditorDiscard = false },
            title = { Text("Discard Remote Edits?", fontWeight = FontWeight.Bold) },
            text = { Text("Your unsaved changes to this remote file will be lost.") },
            dismissButton = {
                TextButton(onClick = { confirmEditorDiscard = false }) { Text("Keep Editing") }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmEditorDiscard = false
                        vm.closeEditor()
                    },
                ) { Text("Discard", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold) }
            },
        )
    }

    state.externalEditor?.takeIf { it.returned }?.let { edit ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Save External Edits?", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Upload the edited copy of ${edit.entryName} back to the server?")
                    Text(
                        "xSSH will refuse to overwrite the remote file if it changed while the editor was open.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    edit.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            dismissButton = {
                TextButton(onClick = vm::discardExternalEdit, enabled = !edit.saving) {
                    Text("Discard Local Copy")
                }
            },
            confirmButton = {
                TextButton(onClick = vm::saveExternalEdit, enabled = !edit.saving) {
                    Text(if (edit.saving) "Uploading…" else "Save to Server", fontWeight = FontWeight.Bold)
                }
            },
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
                vm.detach()
                onBack()
            },
        )
    }
    when (val event = hostKeyEvent) {
        is InteractiveHostKeyVerifier.VerificationEvent.Changed ->
            ChangedHostKeyDialog(
                hostPort = state.endpoint ?: "SFTP server",
                expected = event.expected,
                actual = event.actual,
                onDismiss = {
                    vm.clearHostKeyEvent()
                    vm.detach()
                    onBack()
                },
                onForgetOldKey = { vm.forgetHostKey(event.hostPort, connectionId) },
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
                text = { Text("The fingerprint was not confirmed in time, so xSSH refused the SFTP connection.") },
            )
        else -> Unit
    }
    keyboardPrompt?.let { prompt ->
        KeyboardInteractiveDialog(
            prompts = prompt.prompts,
            onSubmit = prompt::respond,
            onCancel = {
                prompt.cancel()
                vm.detach()
                onBack()
            },
        )
    }
    overwritePrompt?.let { prompt ->
        AlertDialog(
            onDismissRequest = prompt::cancel,
            title = { Text("Replace Remote File?", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "${prompt.name} already exists in this folder. xSSH will upload to a " +
                        "temporary file and replace it only after the transfer succeeds.",
                )
            },
            dismissButton = { TextButton(onClick = prompt::cancel) { Text("Keep Existing") } },
            confirmButton = { TextButton(onClick = prompt::replace) { Text("Replace", fontWeight = FontWeight.Bold) } },
        )
    }
}

@Composable
private fun PathBreadcrumbBar(
    path: String,
    onNavigateTo: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val segments = remember(path) {
        val parts = path.split('/').filter(String::isNotEmpty)
        val list = mutableListOf<Pair<String, String>>()
        list.add("Root" to "/")
        var current = ""
        parts.forEach { part ->
            current += "/$part"
            list.add(part to current)
        }
        list
    }
    LazyRow(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(segments) { (name, target) ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = if (target == path) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.clickable { onNavigateTo(target) },
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        if (name == "Root") {
                            Icon(Icons.Filled.Home, contentDescription = "Root", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
                        } else {
                            Text(name, style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace), fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                if (target != path) {
                    Icon(
                        Icons.Filled.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun FileRow(
    entry: SftpEntry,
    onOpen: () -> Unit,
    onDownload: () -> Unit,
    onEdit: () -> Unit,
    onExternalEdit: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = if (entry.isDir) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Icon(
                if (entry.isDir) Icons.Filled.Folder else Icons.AutoMirrored.Filled.InsertDriveFile,
                contentDescription = null,
                tint = if (entry.isDir) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(10.dp).size(22.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(entry.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (entry.isDir) {
                    "Folder"
                } else {
                    "${Formatter.formatFileSize(
                        context,
                        entry.size,
                    )} · ${DateUtils.getRelativeTimeSpanString(entry.mtime)}"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        Box {
            IconButton(onClick = { expanded = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = "Actions for ${entry.name}")
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                if (!entry.isDir) {
                    DropdownMenuItem(
                        text = { Text("Download") },
                        leadingIcon = { Icon(Icons.Filled.Download, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                        onClick = {
                            expanded = false
                            onDownload()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Quick Edit") },
                        leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                        onClick = {
                            expanded = false
                            onEdit()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Edit in External App") },
                        leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                        onClick = {
                            expanded = false
                            onExternalEdit()
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text("Rename") },
                    leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                    onClick = {
                        expanded = false
                        onRename()
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
                        expanded = false
                        onDelete()
                    },
                )
            }
        }
    }
}

@Composable
private fun NameDialog(
    title: String,
    initial: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text("Name") },
                singleLine = true,
                isError = !isValidRemoteName(value),
            )
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        confirmButton = {
            TextButton(onClick = { onConfirm(value.trim()) }, enabled = isValidRemoteName(value)) {
                Text(confirmLabel, fontWeight = FontWeight.Bold)
            }
        },
    )
}

@Composable
private fun TransferQueueSection(
    queue: List<QueuedTransfer>,
    onRetry: (String) -> Unit,
    onClearEntry: (String) -> Unit,
    onClearFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val finished =
        queue.count {
            it.status == TransferStatus.DONE ||
                it.status == TransferStatus.FAILED ||
                it.status == TransferStatus.CANCELLED
        }
    GlassCard(modifier = modifier) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Transfer Queue", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (finished > 0) TextButton(onClick = onClearFinished) { Text("Clear Finished") }
            }
            queue.take(4).forEach { item ->
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            item.label,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        StatusPill(
                            text = item.status.name.lowercase().replaceFirstChar { it.uppercase() },
                            positive = item.status == TransferStatus.DONE,
                            color =
                                if (item.status == TransferStatus.FAILED) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.primary
                                },
                        )
                        item.error?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    if (item.status in listOf(TransferStatus.FAILED, TransferStatus.CANCELLED)) {
                        TextButton(onClick = { onRetry(item.id) }) { Text("Retry") }
                    }
                    if (item.status !in listOf(TransferStatus.RUNNING, TransferStatus.QUEUED)) {
                        IconButton(onClick = { onClearEntry(item.id) }) {
                            Icon(Icons.Filled.Close, contentDescription = "Dismiss ${item.label}")
                        }
                    }
                }
            }
            if (queue.size > 4) {
                Text("${queue.size - 4} more queued transfers", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun queryDocumentMetadata(
    context: android.content.Context,
    uri: Uri,
): Pair<String, Long> {
    var displayName: String? = null
    var size = -1L
    context.contentResolver.query(
        uri,
        arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
        null,
        null,
        null,
    )?.use { cursor ->
        if (cursor.moveToFirst()) {
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (nameIndex >= 0 && !cursor.isNull(nameIndex)) displayName = cursor.getString(nameIndex)
            if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
        }
    }
    val fallback = uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':')
    return (displayName?.takeIf { it.isNotBlank() } ?: fallback ?: "upload.bin") to size
}

private fun isValidRemoteName(name: String): Boolean {
    val trimmed = name.trim()
    return trimmed.isNotEmpty() &&
        trimmed != "." &&
        trimmed != ".." &&
        '/' !in name &&
        '\u0000' !in name &&
        name.toByteArray(Charsets.UTF_8).size <= 255
}
