package com.aimediaeditor.app.ui.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aimediaeditor.app.data.media.AudioItem

@Composable
fun AddAudioDialog(
    availableAudio: List<AudioItem>,
    onDismiss: () -> Unit,
    onSelect: (AudioItem) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add music") },
        text = {
            if (availableAudio.isEmpty()) {
                Text("No audio files found on this device.")
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                    items(availableAudio, key = { it.id }) { audio ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(audio) }
                                .padding(vertical = 10.dp)
                        ) {
                            Text(audio.displayName, modifier = Modifier.weight(1f))
                            Text(
                                "%.0fs".format(audio.durationMs / 1000f),
                                modifier = Modifier.width(48.dp)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}
