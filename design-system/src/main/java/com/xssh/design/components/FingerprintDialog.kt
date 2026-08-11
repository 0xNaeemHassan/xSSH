/*
 * xSSH — Reusable dialog for host-key acceptance and MITM alarms.
 */
package com.xssh.design.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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
        icon = { Icon(Icons.Filled.Security, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        title = { Text("Unknown host key", fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("First time connecting to:", style = MaterialTheme.typography.bodyMedium)
                Text(hostPort, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    "Key type: $keyType",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    SelectionContainer {
                        Text(
                            fingerprint,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }
                Text(
                    "Verify this SHA-256 fingerprint matches your server before continuing.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onAccept) { Text("Accept & Continue", fontWeight = FontWeight.Bold) } },
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
            icon = { Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Forget the old host key?", fontWeight = FontWeight.SemiBold) },
            text = {
                Text(
                    "Only continue if you independently confirmed that $hostPort was rebuilt or rotated its key. " +
                        "The next connection will ask you to verify and accept the new fingerprint.",
                    style = MaterialTheme.typography.bodyMedium,
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
                    Text("Forget old key", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                }
            },
        )
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
        title = { Text("HOST KEY CHANGED", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "The host key for $hostPort does not match the one recorded on first connection. " +
                        "This could indicate a Man-In-The-Middle attack!",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    SelectionContainer {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                "Expected: $expected",
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            )
                            Text(
                                "Actual:   $actual",
                                style =
                                    MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                    ),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
                Text(
                    "Independently verify the new fingerprint before forgetting the old key.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK", fontWeight = FontWeight.Bold) } },
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
