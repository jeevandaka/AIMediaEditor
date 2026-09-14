package com.aimediaeditor.app.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Local, non-tool-calling models (unlike Anthropic's Messages API with `tool_choice`
 * forced, which STRUCTURALLY guarantees a JSON response) return plain text that
 * USUALLY contains the requested JSON array somewhere in it -- but sometimes wrapped
 * in a markdown code fence, sometimes preceded or followed by a sentence of commentary
 * despite being asked not to. This is the belt-and-suspenders layer that finds and
 * extracts that array before handing it to [EditCommandParser] or
 * [MediaSelectionParser] -- both of which already tolerate a malformed COMMAND inside
 * an otherwise-valid array; this handles the array not being cleanly isolated in the
 * response text in the first place.
 *
 * Deliberately a simple first-`[`-to-last-`]` heuristic, not a real recovery parser --
 * if the model's own prose happens to contain a stray `]` before the real array, or
 * the array's own string values contain unbalanced brackets in an unusual way, this
 * can grab the wrong span. Acceptable because both downstream parsers already fail
 * closed (empty result, never a crash or a garbage value) on anything that doesn't
 * parse cleanly -- this is an accuracy improvement over "give up unless the whole
 * response is pure JSON," not a security boundary.
 */
object LlmJsonExtractor {

    private val json = Json { ignoreUnknownKeys = true }

    fun extractJsonArray(rawText: String): JsonElement? {
        val start = rawText.indexOf('[')
        val end = rawText.lastIndexOf(']')
        if (start == -1 || end == -1 || end < start) return null
        return try {
            json.parseToJsonElement(rawText.substring(start, end + 1))
        } catch (e: Exception) {
            null
        }
    }
}
