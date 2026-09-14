package com.aimediaeditor.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.util.Locale

/**
 * A hard-to-miss call to action for the on-device model download -- shown IN PLACE OF
 * the feature it unlocks (EditorScreen's AI prompt field, MediaSearchScreen's AI
 * Search results area) rather than requiring the user to notice and tap a small
 * settings button first, which is exactly the discoverability gap that prompted this:
 * a disabled prompt field with a small "AI model" text button next to it was too easy
 * to miss entirely.
 *
 * [ramGb] is the device's own total RAM (see [com.aimediaeditor.app.ai.DeviceCapabilities]),
 * shown so the recommendation reads as specific to the device and the app's own use
 * case, not generic boilerplate -- "recommended for your device" backed by an actual
 * number read from that device, not a marketing claim.
 */
@Composable
fun ModelDownloadBanner(ramGb: Double, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("Unlock on-device AI", style = MaterialTheme.typography.titleSmall)
            Text(
                String.format(
                    Locale.US,
                    "Recommended for your device (%.1f GB RAM): Google's Gemma 3 1B model, " +
                        "about 500MB, free, one-time download -- built specifically for this " +
                        "app's edit-prompt and search use cases. Runs fully offline afterward.",
                    ramGb
                ),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp)
            )
            Button(onClick = onClick, modifier = Modifier.padding(top = 8.dp)) {
                Text("Download AI Model")
            }
        }
    }
}
