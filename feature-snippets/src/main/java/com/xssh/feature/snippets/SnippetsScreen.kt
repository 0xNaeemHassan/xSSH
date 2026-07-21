package com.xssh.feature.snippets

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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.xssh.core.data.entity.SnippetEntity
import com.xssh.design.components.DeleteConfirmationDialog
import com.xssh.design.components.EmptyState
import com.xssh.design.components.PageContainer
import com.xssh.design.components.StatusPill

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SnippetsScreen(
    onBack: (() -> Unit)?,
    vm: SnippetsViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsState()
    var editing by remember { mutableStateOf<SnippetEntity?>(null) }
    var pendingDelete by remember { mutableStateOf<SnippetEntity?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    val visibleItems =
        remember(state.items, query) {
            val needle = query.trim()
            if (needle.isEmpty()) {
                state.items
            } else {
                state.items.filter {
                    it.label.contains(needle, ignoreCase = true) ||
                        it.body.contains(needle, ignoreCase = true) ||
                        it.tags.any { tag -> tag.contains(needle, ignoreCase = true) }
                }
            }
        }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Snippets") },
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
            ExtendedFloatingActionButton(
                onClick = { editing = vm.blank() },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("New snippet") },
            )
        },
    ) { inner ->
        PageContainer(modifier = Modifier.fillMaxSize().padding(inner)) {
            Column(modifier = Modifier.fillMaxSize()) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(Icons.Filled.Clear, contentDescription = "Clear search")
                            }
                        }
                    },
                    placeholder = { Text("Search commands or tags") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                )

                when {
                    state.loading ->
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    visibleItems.isEmpty() && query.isNotBlank() ->
                        EmptyState(
                            icon = Icons.Filled.Search,
                            title = "No matching snippets",
                            body = "Search by label, command text, or tag.",
                            actionLabel = "Clear search",
                            onAction = { query = "" },
                        )
                    visibleItems.isEmpty() ->
                        EmptyState(
                            icon = Icons.Filled.Code,
                            title = "Save commands you reuse",
                            body = "Snippets stay on device and can be reviewed before pasting into a session.",
                            actionLabel = "Create snippet",
                            onAction = { editing = vm.blank() },
                        )
                    else ->
                        LazyColumn(
                            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 104.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            items(visibleItems, key = { it.id }) { snippet ->
                                Card(
                                    onClick = { editing = snippet },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors =
                                        CardDefaults.cardColors(
                                            containerColor = MaterialTheme.colorScheme.surfaceContainer,
                                        ),
                                    border = CardDefaults.outlinedCardBorder(),
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Column(
                                            modifier = Modifier.weight(1f),
                                            verticalArrangement = Arrangement.spacedBy(6.dp),
                                        ) {
                                            Text(snippet.label, style = MaterialTheme.typography.titleMedium)
                                            Text(
                                                snippet.body,
                                                style =
                                                    MaterialTheme.typography.bodySmall.copy(
                                                        fontFamily = FontFamily.Monospace,
                                                    ),
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 3,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                if (snippet.executeOnPaste) StatusPill("Runs on paste")
                                                snippet.tags.take(2).forEach { StatusPill(it) }
                                            }
                                        }
                                        IconButton(onClick = { editing = snippet }) {
                                            Icon(Icons.Filled.Edit, contentDescription = "Edit ${snippet.label}")
                                        }
                                        IconButton(onClick = { pendingDelete = snippet }) {
                                            Icon(
                                                Icons.Filled.DeleteOutline,
                                                contentDescription = "Delete ${snippet.label}",
                                            )
                                        }
                                    }
                                }
                            }
                        }
                }
            }
        }
    }

    editing?.let { snippet ->
        SnippetEditSheet(
            initial = snippet,
            onDismiss = { editing = null },
            onSave = { updated ->
                vm.save(updated)
                editing = null
            },
        )
    }
    pendingDelete?.let { snippet ->
        DeleteConfirmationDialog(
            title = "Delete ${snippet.label}?",
            body = "This command snippet will be permanently removed.",
            onConfirm = {
                vm.delete(snippet)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SnippetEditSheet(
    initial: SnippetEntity,
    onDismiss: () -> Unit,
    onSave: (SnippetEntity) -> Unit,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var label by remember(initial.id) { mutableStateOf(initial.label) }
    var body by remember(initial.id) { mutableStateOf(initial.body) }
    var tags by remember(initial.id) { mutableStateOf(initial.tags.joinToString(", ")) }
    var execute by remember(initial.id) { mutableStateOf(initial.executeOnPaste) }
    var confirmDiscard by remember(initial.id) { mutableStateOf(false) }
    val parsedTags =
        tags.split(',').map(String::trim).filter(String::isNotEmpty)
            .distinctBy { it.lowercase() }
    val validationError =
        when {
            label.isBlank() -> "Label is required."
            label.length > 256 || label.any(Char::isISOControl) -> "Label must be 256 characters or fewer."
            body.isBlank() -> "Command or script is required."
            body.length > MAX_SNIPPET_BODY_CHARS || '\u0000' in body -> "Command must be 256 KiB or smaller."
            parsedTags.size > 100 || parsedTags.any { tag -> tag.length > 128 || tag.any(Char::isISOControl) } ->
                "Use at most 100 tags, each 128 characters or fewer."
            else -> null
        }
    val dirty =
        label != initial.label || body != initial.body ||
            tags != initial.tags.joinToString(", ") || execute != initial.executeOnPaste
    val requestDismiss = { if (dirty) confirmDiscard = true else onDismiss() }

    ModalBottomSheet(onDismissRequest = requestDismiss, sheetState = sheet) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                if (initial.label.isBlank()) "New snippet" else "Edit snippet",
                style = MaterialTheme.typography.titleLarge,
            )
            OutlinedTextField(
                value = label,
                onValueChange = { label = it.take(256) },
                label = { Text("Label") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = body,
                onValueChange = { body = it.take(MAX_SNIPPET_BODY_CHARS) },
                label = { Text("Command or script") },
                minLines = 5,
                keyboardOptions =
                    KeyboardOptions(
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Ascii,
                    ),
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = tags,
                onValueChange = { tags = it.take(12_800) },
                label = { Text("Tags") },
                supportingText = { Text("Comma-separated") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier =
                    Modifier.fillMaxWidth().toggleable(
                        value = execute,
                        role = Role.Switch,
                        onValueChange = { execute = it },
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Run immediately after paste", style = MaterialTheme.typography.titleSmall)
                    Text("Appends one newline to execute the command.", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = execute, onCheckedChange = null)
            }
            validationError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = requestDismiss) { Text("Cancel") }
                TextButton(
                    onClick = {
                        onSave(
                            initial.copy(
                                label = label.trim(),
                                body = body,
                                tags = parsedTags,
                                executeOnPaste = execute,
                            ),
                        )
                    },
                    enabled = validationError == null,
                ) { Text("Save") }
            }
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard snippet changes?") },
            text = { Text("Your unsaved command changes will be lost.") },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) { Text("Keep editing") }
            },
            confirmButton = {
                TextButton(onClick = onDismiss) { Text("Discard", color = MaterialTheme.colorScheme.error) }
            },
        )
    }
}

private const val MAX_SNIPPET_BODY_CHARS = 256 * 1024
