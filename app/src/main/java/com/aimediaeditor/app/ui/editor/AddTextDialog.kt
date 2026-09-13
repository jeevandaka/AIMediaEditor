@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.aimediaeditor.app.ui.editor

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

data class TextOverlayInput(val text: String, val durationMs: Long, val yPositionFraction: Float)

private val DURATION_PRESETS = listOf(2000L to "2s", 5000L to "5s", 10000L to "10s")
private val POSITION_PRESETS = listOf(0.1f to "Top", 0.5f to "Center", 0.9f to "Bottom")

@Composable
fun AddTextDialog(onDismiss: () -> Unit, onConfirm: (TextOverlayInput) -> Unit) {
    var text by remember { mutableStateOf("") }
    var durationMs by remember { mutableFloatStateOf(5000f) }
    var yFraction by remember { mutableFloatStateOf(0.9f) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add text") },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Text") },
                    modifier = Modifier.fillMaxWidth()
                )
                Text("Duration", modifier = Modifier.padding(top = 12.dp))
                Row {
                    DURATION_PRESETS.forEach { (ms, label) ->
                        FilterChip(
                            selected = durationMs.toLong() == ms,
                            onClick = { durationMs = ms.toFloat() },
                            label = { Text(label) },
                            modifier = Modifier.padding(end = 6.dp)
                        )
                    }
                }
                Text("Position", modifier = Modifier.padding(top = 12.dp))
                Row {
                    POSITION_PRESETS.forEach { (fraction, label) ->
                        FilterChip(
                            selected = yFraction == fraction,
                            onClick = { yFraction = fraction },
                            label = { Text(label) },
                            modifier = Modifier.padding(end = 6.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(TextOverlayInput(text, durationMs.toLong(), yFraction)) },
                enabled = text.isNotBlank()
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
