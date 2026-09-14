package com.aimediaeditor.app.ai

import android.content.Context
import com.aimediaeditor.app.data.index.IndexedMediaSummary

/**
 * "Stage 2" of the search/select/assemble chain: turns a natural-language library
 * query into a ranked list of media ids using the on-device Gemma model
 * ([LocalLlmEngine]) -- replaces the earlier cloud-based `ClaudeMediaSelectionService`
 * draft (Anthropic Messages API tool-calling), which was abandoned before ever being
 * wired up once the decision was made to keep every AI feature in this app fully
 * on-device (see the README's "On-device AI" section for why). Request/response
 * shape mirrors [LocalEditCommandService] deliberately -- both are "build a prompt,
 * run it through the one shared engine, extract+parse the JSON array back out" with
 * a different schema and a different parser underneath.
 *
 * Same HONEST RISK category as [LocalLlmEngine]: this sandbox cannot exercise
 * `requestSelection()` even once. See the README's verification section.
 */
object LocalMediaSelectionService {

    suspend fun requestSelection(
        context: Context,
        modelFilePath: String,
        candidates: List<IndexedMediaSummary>,
        userQuery: String
    ): AiMediaSelectionResult =
        try {
            val prompt = MediaSelectionToolSchema.buildFullPrompt(candidates, userQuery)
            val rawResponse = LocalLlmEngine.generate(context, modelFilePath, prompt)
            parseResponse(rawResponse)
        } catch (e: Exception) {
            AiMediaSelectionResult.Failure(e.message ?: "Local model request failed: ${e::class.simpleName}")
        }

    // internal, not private -- see LocalEditCommandService.parseResponse for why.
    internal fun parseResponse(rawResponse: String): AiMediaSelectionResult {
        val arrayJson = LlmJsonExtractor.extractJsonArray(rawResponse)
            ?: return AiMediaSelectionResult.Success(
                emptyList(),
                "The assistant's response didn't contain a recognizable list of ids."
            )
        val mediaIds = MediaSelectionParser.parse(arrayJson)
        return AiMediaSelectionResult.Success(mediaIds, null)
    }
}

/** Outcome of one [LocalMediaSelectionService.requestSelection] call. */
sealed interface AiMediaSelectionResult {
    /** [mediaIds] can be empty even on success -- e.g. nothing in the candidate list matched. */
    data class Success(val mediaIds: List<Long>, val assistantMessage: String?) : AiMediaSelectionResult
    data class Failure(val message: String) : AiMediaSelectionResult
}
