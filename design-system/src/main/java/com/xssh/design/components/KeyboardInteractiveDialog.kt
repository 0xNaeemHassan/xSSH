package com.xssh.design.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
fun KeyboardInteractiveDialog(
    prompts: List<String>,
    onSubmit: (List<String>) -> Unit,
    onCancel: () -> Unit,
) {
    val answers =
        remember(prompts) {
            mutableStateListOf<String>().apply { repeat(prompts.size) { add("") } }
        }
    val cancel = {
        answers.indices.forEach { answers[it] = "" }
        onCancel()
    }
    AlertDialog(
        onDismissRequest = cancel,
        title = { Text("Server authentication challenge") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("The server sent ${prompts.size} prompt${if (prompts.size == 1) "" else "s"}:")
                prompts.forEachIndexed { i, prompt ->
                    OutlinedTextField(
                        value = answers[i],
                        onValueChange = { answers[i] = it.take(4_096) },
                        label = { Text(prompt.trim().ifBlank { "Response" }.take(512)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions =
                            KeyboardOptions(
                                autoCorrectEnabled = false,
                                keyboardType = KeyboardType.Password,
                            ),
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val submitted = answers.toList()
                answers.indices.forEach { answers[it] = "" }
                onSubmit(submitted)
            }) { Text("Send") }
        },
        dismissButton = {
            TextButton(onClick = cancel) { Text("Cancel") }
        },
    )
}
