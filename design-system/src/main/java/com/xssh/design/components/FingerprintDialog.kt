/*
 * xSSH — Reusable dialog for host-key acceptance and MITM alarms.
 */
package com.xssh.design.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

@Composable
fun UnknownHostKeyDialog(
    hostPort: String,
    fingerprint: String,
    keyType: String,
    onAccept: () -> Unit,
    onReject: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onReject,
        title = { Text("Unknown host key") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("First time connecting to:", style = MaterialTheme.typography.bodyMedium)
                Text(hostPort, style = MaterialTheme.typography.titleMedium)
                Text("Type: $keyType", style = MaterialTheme.typography.bodySmall)
                SelectionContainer {
                    Text(
                        fingerprint,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
                Text(
                    "Only accept if this matches the fingerprint you have from another trusted source.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = { TextButton(onClick = onAccept) { Text("Accept and continue") } },
        dismissButton = { TextButton(onClick = onReject) { Text("Cancel") } },
    )
}

@Composable
fun ChangedHostKeyDialog(
    hostPort: String,
    expected: String,
    actual: String,
    onDismiss: () -> Unit,
    onForgetOldKey: (() -> Unit)? = null,
) {
    var confirmForget by remember(hostPort, expected, actual) { mutableStateOf(false) }
    if (confirmForget) {
        AlertDialog(
            onDismissRequest = { confirmForget = false },
            title = { Text("Forget the old host key?") },
            text = {
                Text(
                    "Only continue if you independently confirmed that $hostPort was rebuilt or rotated its key. " +
                        "The next connection will ask you to verify and accept the new fingerprint.",
                )
            },
            dismissButton = {
                TextButton(onClick = { confirmForget = false }) { Text("Keep old key") }
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmForget = false
                    onForgetOldKey?.invoke()
                }) {
                    Text("Forget old key", color = MaterialTheme.colorScheme.error)
                }
            },
        )
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Host key CHANGED", color = MaterialTheme.colorScheme.error) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "The host key for $hostPort does not match the one xSSH recorded on " +
                        "first connection. This can indicate a man-in-the-middle attack.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "Expected: $expected",
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        )
                        Text(
                            "Actual: $actual",
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        )
                    }
                }
                Text(
                    "If the change is legitimate, independently verify the new fingerprint " +
                        "before forgetting the old key.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
        dismissButton =
            onForgetOldKey?.let {
                {
                    TextButton(onClick = { confirmForget = true }) {
                        Text("Forget old key", color = MaterialTheme.colorScheme.error)
                    }
                }
            },
    )
}
