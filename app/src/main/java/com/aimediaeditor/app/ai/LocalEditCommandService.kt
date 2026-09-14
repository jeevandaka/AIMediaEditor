package com.aimediaeditor.app.ai

import android.content.Context
import com.aimediaeditor.app.editor.model.EditCommand
import com.aimediaeditor.app.editor.model.ProjectState

/**
 * Turns a user's typed prompt into [EditCommand]s using the on-device Gemma model
 * ([LocalLlmEngine]) instead of a cloud API call -- replaces the earlier
 * `ClaudeEditService`, which sent the project summary and prompt to Anthropic's
 * Messages API over the network. Nothing about this app's edit-application path
 * changed: the result still flows through the exact same
 * [com.aimediaeditor.app.editor.EditorViewModel.submitAiPrompt] -> `ProjectHistory` ->
 * `ProjectSanitizer` pipeline every manual edit uses (architecture notes section 7).
 * Only the source of the commands moved from a network response to a local one.
 *
 * Same HONEST RISK category as [LocalLlmEngine] itself: this sandbox cannot exercise
 * `requestEdit()` even once. What's specifically NEW risk here, beyond the engine
 * itself: a local, non-tool-calling model is meaningfully less likely to reliably
 * follow a "respond with exactly this JSON shape" instruction than Claude was with
 * `tool_choice` forced -- [EditCommandToolSchema.systemPrompt]'s doc comment flags
 * this as a real capability reduction, not just a wording change. See the README.
 */
object LocalEditCommandService {

    suspend fun requestEdit(context: Context, modelFilePath: String, project: ProjectState, userPrompt: String): AiEditResult =
        try {
            val prompt = EditCommandToolSchema.buildFullPrompt(project, userPrompt)
            val rawResponse = LocalLlmEngine.generate(context, modelFilePath, prompt)
            parseResponse(rawResponse)
        } catch (e: Exception) {
            AiEditResult.Failure(e.message ?: "Local model request failed: ${e::class.simpleName}")
        }

    // internal, not private, so LocalEditCommandServiceTest can exercise the pure
    // parsing half without needing a loaded model -- the same split ClaudeEditService
    // used to keep buildRequestBody/parseSuccessResponse genuinely unit-testable.
    internal fun parseResponse(rawResponse: String): AiEditResult {
        val arrayJson = LlmJsonExtractor.extractJsonArray(rawResponse)
            ?: return AiEditResult.Success(
                emptyList(),
                "The assistant's response didn't contain a recognizable list of edits."
            )
        val commands = EditCommandParser.parse(arrayJson)
        return AiEditResult.Success(commands, null)
    }
}

/** Outcome of one [LocalEditCommandService.requestEdit] call. */
sealed interface AiEditResult {
    /**
     * [commands] can be empty even on success -- e.g. the model produced `[]`, or
     * produced nothing [LlmJsonExtractor] could find a JSON array in.
     * [assistantMessage] is a fallback note for that second case; unlike the previous
     * cloud version, a local model has no separate free-text "explanation" channel
     * alongside its JSON response, so there's normally nothing to show here on a
     * genuine success.
     */
    data class Success(val commands: List<EditCommand>, val assistantMessage: String?) : AiEditResult
    data class Failure(val message: String) : AiEditResult
}
