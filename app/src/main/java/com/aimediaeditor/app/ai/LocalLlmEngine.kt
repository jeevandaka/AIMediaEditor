package com.aimediaeditor.app.ai

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Wraps MediaPipe's `LlmInference` (the on-device Gemma runtime) so every AI-facing
 * caller in this app just sees "give it a prompt, get text back" -- the same shape
 * the now-removed `ClaudeEditService`'s HTTP call used to present, replacing a cloud
 * request with local inference. [LocalEditCommandService] and
 * [LocalMediaSelectionService] both go through this one object, so there's exactly one
 * place in the app that touches the native inference library, and exactly one loaded
 * model instance at a time (a multi-hundred-megabyte model has no business being
 * loaded twice into a phone's RAM at once).
 *
 * HONEST RISK, now the single biggest unverified surface in this project: this is the
 * FIRST time this app has ever loaded a model for open-ended text generation (as
 * opposed to ML Kit's pre-built, narrow labeling/face-detection models). There is no
 * Android SDK, device, or emulator in this sandbox to load a real `.task` file into,
 * so `generateResponse()`'s actual behavior here -- does it load without OOM-ing on a
 * mid-range phone, does it produce coherent/schema-following text, how long does one
 * call actually take -- has never been exercised even once. The
 * `LlmInference.LlmInferenceOptions` builder shape and `generateResponse(prompt):
 * String` call are written from MediaPipe's publicly documented/sampled Android API
 * surface (this sandbox has no network path to the official docs either, only to
 * search results and cached pages -- see the README), not confirmed against a
 * compiled build. See the README's "On-device AI" verification section for the full
 * list of what this round could not test.
 */
object LocalLlmEngine {

    private const val MAX_TOKENS = 1024

    private var inference: LlmInference? = null
    private var loadedModelPath: String? = null
    private val loadLock = Mutex()

    /** Loads the model from [modelFilePath] on first call (or if the path changed since
     *  the last load), then reuses it for every subsequent call. */
    suspend fun generate(context: Context, modelFilePath: String, prompt: String): String =
        withContext(Dispatchers.Default) {
            val engine = ensureLoaded(context, modelFilePath)
            engine.generateResponse(prompt)
        }

    private suspend fun ensureLoaded(context: Context, modelFilePath: String): LlmInference =
        loadLock.withLock {
            val current = inference
            if (current != null && loadedModelPath == modelFilePath) return@withLock current
            current?.close()
            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelFilePath)
                .setMaxTokens(MAX_TOKENS)
                .build()
            LlmInference.createFromOptions(context.applicationContext, options).also {
                inference = it
                loadedModelPath = modelFilePath
            }
        }

    /** Releases the loaded model -- called when the model file is deleted/replaced
     *  ([com.aimediaeditor.app.ai.LocalLlmModelManager.deleteModel]) so a stale native
     *  handle to a file that no longer exists is never left around. */
    fun unload() {
        inference?.close()
        inference = null
        loadedModelPath = null
    }
}
