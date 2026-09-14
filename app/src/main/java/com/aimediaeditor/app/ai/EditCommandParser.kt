package com.aimediaeditor.app.ai

import com.aimediaeditor.app.editor.model.AspectRatio
import com.aimediaeditor.app.editor.model.EditCommand
import com.aimediaeditor.app.editor.model.FilterType
import com.aimediaeditor.app.editor.model.FocalPoint
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Turns Claude's `apply_edit_commands` tool-call input (see [EditCommandToolSchema])
 * back into real [EditCommand] instances -- the one place LLM output crosses from
 * "untrusted JSON" into the app's actual command taxonomy.
 *
 * This is deliberately the FIRST line of defense, before [com.aimediaeditor.app.editor.model.ProjectSanitizer]
 * ever sees anything: a field that's missing, the wrong JSON type, or an unrecognized
 * enum value means that ONE command entry is silently dropped, never a thrown
 * exception and never a null/garbage value smuggled into an [EditCommand] instance.
 * ProjectSanitizer's own contract ("never trust the caller's numbers") already
 * assumes a hallucinated ID or an out-of-range timestamp; this layer's job is
 * narrower and stricter -- reject anything that isn't even SHAPED like a valid
 * command, since ProjectSanitizer has no way to validate a field that was never
 * parsed into the right Kotlin type in the first place (there's no sanitizing a
 * clipId that's actually a JSON number, or a startMs that's actually the string
 * "soon").
 *
 * [EditCommand.AddAudio] is deliberately NOT part of this taxonomy -- it needs a
 * real `content://` URI to an audio file already on the device, which the model has
 * no way to know (it only ever sees the text summary [EditCommandToolSchema] builds
 * from the current project, not the device's media library). Every other command
 * operates on ids the model CAN see in that summary (existing clips, text overlays,
 * audio tracks), so this is the one deliberate exclusion, not an oversight.
 */
object EditCommandParser {

    /**
     * Parses the tool input's `commands` array. Each element that doesn't resolve to
     * a valid, fully-populated [EditCommand] is dropped -- the return list can be
     * shorter than the input array, and can be empty, but this function never throws
     * on malformed input.
     */
    fun parse(commandsJson: JsonElement): List<EditCommand> {
        val array = commandsJson as? JsonArray ?: return emptyList()
        return array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            parseOne(obj)
        }
    }

    private fun parseOne(obj: JsonObject): EditCommand? {
        val type = obj.string("type") ?: return null
        return when (type) {
            "TrimClip" -> EditCommand.TrimClip(
                clipId = obj.string("clipId") ?: return null,
                startMs = obj.long("startMs") ?: return null,
                endMs = obj.long("endMs") ?: return null
            )
            "SplitClip" -> EditCommand.SplitClip(
                clipId = obj.string("clipId") ?: return null,
                splitAtMs = obj.long("splitAtMs") ?: return null
            )
            "ReorderClips" -> EditCommand.ReorderClips(
                orderedClipIds = obj.stringList("orderedClipIds") ?: return null
            )
            "DeleteClip" -> EditCommand.DeleteClip(
                clipId = obj.string("clipId") ?: return null
            )
            "SetAspectRatio" -> EditCommand.SetAspectRatio(
                ratio = obj.enum<AspectRatio>("ratio") ?: return null
            )
            "ToggleEffect" -> EditCommand.ToggleEffect(
                clipId = obj.string("clipId") ?: return null,
                filter = obj.enum<FilterType>("filter") ?: return null
            )
            "ReorderEffects" -> EditCommand.ReorderEffects(
                clipId = obj.string("clipId") ?: return null,
                orderedFilters = obj.enumList<FilterType>("orderedFilters") ?: return null
            )
            "SmartReframe" -> EditCommand.SmartReframe(
                clipId = obj.string("clipId") ?: return null,
                focalPoint = FocalPoint(
                    x = obj.float("focalX") ?: return null,
                    y = obj.float("focalY") ?: return null
                )
            )
            "AddText" -> EditCommand.AddText(
                text = obj.string("text") ?: return null,
                startMs = obj.long("startMs") ?: return null,
                endMs = obj.long("endMs") ?: return null,
                yPositionFraction = obj.float("yPositionFraction") ?: return null
            )
            "SetSpeed" -> EditCommand.SetSpeed(
                clipId = obj.string("clipId") ?: return null,
                speed = obj.float("speed") ?: return null
            )
            "SetClipVolume" -> EditCommand.SetClipVolume(
                clipId = obj.string("clipId") ?: return null,
                volume = obj.float("volume") ?: return null
            )
            "SetAudioVolume" -> EditCommand.SetAudioVolume(
                trackId = obj.string("trackId") ?: return null,
                volume = obj.float("volume") ?: return null
            )
            "SetAudioLooping" -> EditCommand.SetAudioLooping(
                trackId = obj.string("trackId") ?: return null,
                isLooping = obj.boolean("isLooping") ?: return null
            )
            "SetAudioPosition" -> EditCommand.SetAudioPosition(
                trackId = obj.string("trackId") ?: return null,
                startMs = obj.long("startMs") ?: return null,
                durationMs = obj.long("durationMs") ?: return null
            )
            "SetTextPosition" -> EditCommand.SetTextPosition(
                overlayId = obj.string("overlayId") ?: return null,
                xFraction = obj.float("xFraction") ?: return null,
                yFraction = obj.float("yFraction") ?: return null
            )
            "RemoveTextOverlay" -> EditCommand.RemoveTextOverlay(
                overlayId = obj.string("overlayId") ?: return null
            )
            "RemoveAudioTrack" -> EditCommand.RemoveAudioTrack(
                trackId = obj.string("trackId") ?: return null
            )
            "ToggleTransition" -> EditCommand.ToggleTransition(
                afterClipId = obj.string("afterClipId") ?: return null
            )
            "SetTransitionDuration" -> EditCommand.SetTransitionDuration(
                afterClipId = obj.string("afterClipId") ?: return null,
                durationMs = obj.long("durationMs") ?: return null
            )
            else -> null // unrecognized type -- e.g. a hallucinated command name, or AddAudio
        }
    }

    // --- Small, defensive JsonObject field readers -----------------------------------
    // Every one of these returns null (never throws) on a missing key, a JSON null, or
    // a value of the wrong shape -- the ONLY way a caller above is allowed to react to
    // bad input is "drop this command," never a crash.

    private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitiveOrNull?.contentOrNull

    private fun JsonObject.long(key: String): Long? = this[key]?.jsonPrimitiveOrNull?.longOrNull

    private fun JsonObject.float(key: String): Float? = this[key]?.jsonPrimitiveOrNull?.floatOrNull

    private fun JsonObject.boolean(key: String): Boolean? = this[key]?.jsonPrimitiveOrNull?.booleanOrNull

    private fun JsonObject.stringList(key: String): List<String>? {
        val arr = this[key] as? JsonArray ?: return null
        val result = arr.map { it.jsonPrimitiveOrNull?.contentOrNull ?: return null }
        return result
    }

    private inline fun <reified T : Enum<T>> JsonObject.enum(key: String): T? {
        val name = string(key) ?: return null
        return enumValueOrNull<T>(name)
    }

    private inline fun <reified T : Enum<T>> JsonObject.enumList(key: String): List<T>? {
        val arr = this[key] as? JsonArray ?: return null
        val result = arr.map { element ->
            val name = element.jsonPrimitiveOrNull?.contentOrNull ?: return null
            enumValueOrNull<T>(name) ?: return null
        }
        return result
    }

    private inline fun <reified T : Enum<T>> enumValueOrNull(name: String): T? =
        enumValues<T>().firstOrNull { it.name == name }

    /** [JsonElement.jsonPrimitive] throws for a non-primitive (object/array) element -- this doesn't. */
    private val JsonElement.jsonPrimitiveOrNull
        get() = try {
            jsonPrimitive
        } catch (e: Exception) {
            null
        }
}
