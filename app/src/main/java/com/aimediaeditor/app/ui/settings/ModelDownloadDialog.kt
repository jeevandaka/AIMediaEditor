package com.aimediaeditor.app.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.aimediaeditor.app.ai.DeviceCapabilities
import java.util.Locale

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
 * involvement at all -- and with no ongoing cost either: the model itself and
 * MediaPipe's runtime are both free, so this download is the only "setup" of any
 * kind, ever. Two buttons open the device's own browser straight to the model's
 * license page and the token-creation page (real `ACTION_VIEW` intents, not a
 * WebView -- this app never touches the Hugging Face login flow itself), so the user
 * doesn't have to go find either page on their own. The download explanation also
 * reads the device's own total RAM ([com.aimediaeditor.app.ai.DeviceCapabilities]) so
 * the recommendation is specific to the device it's showing on, not generic copy --
 * see [ModelDownloadBanner], which surfaces this same recommendation even more
 * prominently (in place of the feature it unlocks, not behind a settings button) as
 * the entry point into this dialog. Originally written inline in
 * `EditorScreen.kt`; extracted here once [com.aimediaeditor.app.ui.search.MediaSearchScreen]
 * needed the exact same flow for AI search -- a genuine second caller, not
 * speculative reuse.
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
    val context = LocalContext.current
    var token by remember { mutableStateOf(initialToken) }
    val ramGb = remember { DeviceCapabilities.totalRamGb(context) }
    val isLikelySuitable = remember(ramGb) { DeviceCapabilities.isLikelySuitable(ramGb) }

    fun openUrl(url: String) {
        // A plain ACTION_VIEW intent, not a WebView -- opens the device's own browser
        // (where the user's existing Hugging Face login/session, if any, already
        // applies), so this app never touches the account flow or the token itself
        // beyond the field below. Every real Android device ships something that
        // handles a plain https:// ACTION_VIEW, but this degrades to a silent no-op
        // rather than crashing on the (essentially theoretical) device that doesn't.
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            // No app on the device can handle this -- nothing more this dialog can do.
        }
    }

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
                            String.format(
                                Locale.US,
                                "Downloads Google's Gemma 3 1B model (about 500MB, one time only) " +
                                    "so AI features work fully offline afterward -- no project data " +
                                    "or search query is ever sent anywhere. It's completely free -- " +
                                    "no subscription, no per-use cost -- but the model file itself " +
                                    "is gated behind a free Hugging Face account and Google's Gemma " +
                                    "license, so a one-time sign-up is required before the first " +
                                    "use.\n\nYour device has %.1f GB of RAM.%s",
                                ramGb,
                                if (isLikelySuitable) {
                                    " This model is sized for phones like yours -- built for this " +
                                        "app's edit-prompt and search use cases specifically."
                                } else {
                                    " That's on the lower end for this model -- it will likely " +
                                        "still work, but may run slowly or occasionally fail under " +
                                        "memory pressure. Still safe to try; there's currently no " +
                                        "smaller model variant offered as an alternative."
                                }
                            ),
                            style = MaterialTheme.typography.bodySmall
                        )
                        Row(modifier = Modifier.padding(top = 8.dp)) {
                            TextButton(onClick = {
                                openUrl("https://huggingface.co/litert-community/Gemma3-1B-IT")
                            }) { Text("1. Accept license") }
                            TextButton(onClick = {
                                // The bare /settings/tokens page (with its own "+
                                // Create new token" button) is the confirmed URL --
                                // a query param to auto-open that button's dialog
                                // wasn't confirmed against Hugging Face's own docs
                                // from this sandbox, so this deliberately doesn't
                                // guess at one.
                                openUrl("https://huggingface.co/settings/tokens")
                            }) { Text("2. Get token") }
                        }
                        OutlinedTextField(
                            value = token,
                            onValueChange = { token = it },
                            modifier = Modifier.padding(top = 8.dp),
                            singleLine = true,
                            placeholder = { Text("3. Paste token: hf_...") },
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
