package com.aimediaeditor.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

/**
 * Shared entry point for the on-device AI model, used from both the editor's AI
 * prompt bar and the AI search screen -- a one-time download, gated behind a Hugging
 * Face access token (see [com.aimediaeditor.app.ai.LocalLlmModelManager]'s doc
 * comment for why a token is needed at all -- the model repo requires accepting
 * Google's Gemma license). The token itself is stored by each caller via
 * [com.aimediaeditor.app.data.settings.ModelAccessTokenStore]
 * ([androidx.security.crypto.EncryptedSharedPreferences]) purely so an interrupted
 * download can be retried without re-pasting it -- it is never sent anywhere except
 * huggingface.co, and never touched again once the model file exists. Once
 * [isModelReady] is true, every AI feature in this app runs with no network
 * involvement at all. Originally written inline in `EditorScreen.kt`; extracted here
 * once [com.aimediaeditor.app.ui.search.MediaSearchScreen] needed the exact same flow
 * for AI search -- a genuine second caller, not speculative reuse.
 */
@Composable
fun ModelDownloadDialog(
    isModelReady: Boolean,
    isDownloading: Boolean,
    progress: Float?,
    error: String?,
    initialToken: String,
    onDismiss: () -> Unit,
    onDownload: (String) -> Unit
) {
    var token by remember { mutableStateOf(initialToken) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("On-device AI model") },
        text = {
            Column {
                when {
                    isModelReady -> Text(
                        "Model downloaded and ready. AI edits and AI search now run " +
                            "entirely on this device, with no network calls.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    isDownloading -> {
                        Text("Downloading model...", style = MaterialTheme.typography.bodySmall)
                        val pct = progress
                        if (pct != null) {
                            Text("${(pct * 100).toInt()}%", modifier = Modifier.padding(top = 4.dp))
                        } else {
                            CircularProgressIndicator(modifier = Modifier.padding(top = 8.dp).size(24.dp))
                        }
                    }
                    else -> {
                        Text(
                            "Downloads Google's Gemma model (roughly 500MB-1GB, one time only) " +
                                "so AI features work fully offline afterward -- no project data or " +
                                "search query is ever sent anywhere. The model is gated behind a " +
                                "free Hugging Face account: sign in at huggingface.co, accept the " +
                                "Gemma license on the model page, then paste an access token below.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        OutlinedTextField(
                            value = token,
                            onValueChange = { token = it },
                            modifier = Modifier.padding(top = 8.dp),
                            singleLine = true,
                            placeholder = { Text("hf_...") },
                            visualTransformation = PasswordVisualTransformation()
                        )
                    }
                }
                error?.let {
                    Text(
                        "Error: $it",
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            if (!isModelReady) {
                TextButton(onClick = { onDownload(token) }, enabled = !isDownloading && token.isNotBlank()) {
                    Text("Download")
                }
            } else {
                TextButton(onClick = onDismiss) { Text("Done") }
            }
        },
        dismissButton = {
            if (!isModelReady) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}
