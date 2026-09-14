package com.aimediaeditor.app.ai

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Turns the on-device model's media-selection response (a bare JSON array of ids, see
 * [MediaSelectionToolSchema]) into real media ids -- the same "untrusted JSON in,
 * defensively-parsed value out, never throw" discipline [EditCommandParser] already
 * established for edit commands.
 * A single [Long] field is a much smaller parsing surface than [EditCommandParser]'s
 * many command shapes, so this doesn't need that file's per-field helper machinery --
 * one non-numeric or missing entry is simply dropped, never a thrown exception, and
 * [com.aimediaeditor.app.data.index.MediaIndexRepository.aiSearch] additionally drops
 * any id that doesn't resolve to a real candidate row, so a hallucinated id can never
 * surface as a search result even if it slipped past this layer.
 */
object MediaSelectionParser {

    fun parse(mediaIdsJson: JsonElement): List<Long> {
        val array = mediaIdsJson as? JsonArray ?: return emptyList()
        return array.mapNotNull { element ->
            try {
                element.jsonPrimitive.longOrNull
            } catch (e: Exception) {
                null // element wasn't even a JSON primitive (e.g. a nested object) -- drop it
            }
        }
    }
}
