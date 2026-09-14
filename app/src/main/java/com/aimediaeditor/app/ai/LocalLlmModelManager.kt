package com.aimediaeditor.app.ai

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads and caches the on-device Gemma model file (MediaPipe's `.task` format, on
 * the order of several hundred MB to ~1GB) so [LocalLlmEngine] has a local file path
 * to load. This is now the ONLY network call anywhere in this app, and it's a
 * ONE-TIME thing: once [downloadModel] succeeds, every AI feature
 * ([LocalEditCommandService], [LocalMediaSelectionService]) runs entirely offline from
 * then on -- no user data (project contents, search queries, media metadata) is ever
 * sent anywhere; the download fetches only the model's own static weights, once, and
 * nothing else this app does ever touches the network again.
 *
 * The model repository is GATED on Hugging Face -- Google requires being signed in
 * and having accepted the Gemma license before any file in it can be downloaded, so
 * there is no anonymous public URL to hit. That's why this needs the user's own
 * Hugging Face access token, the same "bring your own credential, stored via
 * EncryptedSharedPreferences, never bundled into the APK" pattern this app's earlier
 * Anthropic API key flow used (see
 * [com.aimediaeditor.app.data.settings.ModelAccessTokenStore]) -- except this token is
 * used ONLY as this one download request's Authorization header, never logged, never
 * sent anywhere except huggingface.co, and never touched again once the model file
 * exists on disk.
 *
 * UNVERIFIED from this sandbox: the exact [MODEL_DOWNLOAD_URL]/[MODEL_FILENAME].
 * Hugging Face repo file listings can change over time, and this sandbox's network
 * policy blocks reaching huggingface.co directly to confirm the current filename --
 * confirm against the live file listing at
 * https://huggingface.co/litert-community/Gemma3-1B-IT before shipping. See the
 * README's verification section.
 */
class LocalLlmModelManager(context: Context) {

    private val appContext = context.applicationContext
    private val modelDir = File(appContext.filesDir, "models")
    private val modelFile = File(modelDir, MODEL_FILENAME)
    private val partialFile = File(modelDir, "$MODEL_FILENAME.part")

    fun isModelReady(): Boolean = modelFile.exists() && modelFile.length() > 0

    fun modelFilePath(): String = modelFile.absolutePath

    fun deleteModel() {
        modelFile.delete()
        partialFile.delete()
        LocalLlmEngine.unload()
    }

    /**
     * No-ops if the model is already downloaded. Downloads to a `.part` file first and
     * only renames to the final name on full success, so a killed/interrupted download
     * never leaves a truncated file [isModelReady] would wrongly treat as usable.
     * [onProgress] reports (bytes downloaded, total bytes) -- total is -1 if the server
     * response doesn't include a Content-Length.
     */
    suspend fun downloadModel(
        hfToken: String,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        if (isModelReady()) return@withContext Result.success(Unit)
        var connection: HttpURLConnection? = null
        try {
            modelDir.mkdirs()
            connection = (URL(MODEL_DOWNLOAD_URL).openConnection() as HttpURLConnection).apply {
                setRequestProperty("Authorization", "Bearer $hfToken")
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
            }
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                val message = if (responseCode == 401 || responseCode == 403) {
                    "Access denied (HTTP $responseCode) -- check the token is valid and the " +
                        "Gemma license has been accepted on huggingface.co."
                } else {
                    "Download failed with HTTP $responseCode."
                }
                return@withContext Result.failure(IOException(message))
            }
            val totalBytes = connection.contentLengthLong
            var downloaded = 0L
            connection.inputStream.use { input ->
                partialFile.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        onProgress(downloaded, totalBytes)
                    }
                }
            }
            if (!partialFile.renameTo(modelFile)) {
                throw IOException("Failed to finalize the downloaded model file.")
            }
            Result.success(Unit)
        } catch (e: Exception) {
            partialFile.delete()
            Result.failure(e)
        } finally {
            connection?.disconnect()
        }
    }

    private companion object {
        // UNVERIFIED -- see this class's doc comment. Confirm the exact filename
        // against the repo's current file listing before shipping; the download
        // itself is written generically (any HTTPS URL + bearer token), so fixing a
        // wrong filename here is a one-line change, not a redesign.
        const val MODEL_DOWNLOAD_URL =
            "https://huggingface.co/litert-community/Gemma3-1B-IT/resolve/main/gemma3-1b-it-int4.task?download=true"
        const val MODEL_FILENAME = "gemma3-1b-it-int4.task"
        const val CONNECT_TIMEOUT_MS = 30_000
        const val READ_TIMEOUT_MS = 120_000
    }
}
