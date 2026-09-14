package com.aimediaeditor.app.ai

import com.aimediaeditor.app.editor.model.AspectRatio
import com.aimediaeditor.app.editor.model.FilterType
import com.aimediaeditor.app.editor.model.ProjectState
import com.aimediaeditor.app.editor.model.clipStartOffsetMs
import com.aimediaeditor.app.editor.model.effectiveEffects
import com.aimediaeditor.app.editor.model.effectiveTransitions
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The JSON Schema for an `EditCommand` -- the ONLY way this app lets an LLM affect a
 * project (architecture notes section 7, spec section 21). Originally written as an
 * Anthropic Messages API "tool" definition with `tool_choice` forced to it, which
 * structurally guaranteed a matching response; now that the AI layer runs fully
 * on-device (see [LocalEditCommandService]), there is no tool-calling mechanism to
 * force a shape -- [toolDefinition] instead gets embedded as reference documentation
 * inside [buildFullPrompt], and the model is asked in plain instructions to respond
 * with a bare JSON array matching it. [LlmJsonExtractor] and [EditCommandParser]'s
 * existing defensive parsing are what actually keep that promise now that nothing
 * structurally enforces it.
 *
 * One JSON Schema `oneOf` branch per [com.aimediaeditor.app.editor.model.EditCommand]
 * case, discriminated by a `type` string const matching the Kotlin class's simple
 * name exactly -- [EditCommandParser] reads that same string back. Every property
 * name here matches the corresponding EditCommand constructor parameter name (with
 * FocalPoint flattened to focalX/focalY, since a nested object adds schema complexity
 * for no parsing benefit). Kept in sync BY HAND with EditCommand.kt -- there is no
 * compiler-enforced link between the two, which is exactly why [EditCommandParserTest]
 * and [EditCommandToolSchemaTest] both exist: to catch a drift here from actually
 * breaking parsing, since nothing else would.
 *
 * [com.aimediaeditor.app.editor.model.EditCommand.AddAudio] is deliberately excluded
 * -- see [EditCommandParser]'s doc comment for why.
 */
object EditCommandToolSchema {

    const val TOOL_NAME = "apply_edit_commands"

    private val aspectRatioNames = AspectRatio.entries.map { it.name }
    private val filterTypeNames = FilterType.entries.map { it.name }

    private fun stringProp(description: String) = buildJsonObject {
        put("type", "string")
        put("description", description)
    }

    private fun numberProp(description: String) = buildJsonObject {
        put("type", "number")
        put("description", description)
    }

    private fun booleanProp(description: String) = buildJsonObject {
        put("type", "boolean")
        put("description", description)
    }

    private fun enumProp(description: String, values: List<String>) = buildJsonObject {
        put("type", "string")
        put("description", description)
        putJsonArray("enum") { values.forEach { add(JsonPrimitive(it)) } }
    }

    private fun stringArrayProp(description: String) = buildJsonObject {
        put("type", "array")
        put("description", description)
        putJsonObject("items") { put("type", "string") }
    }

    private fun enumArrayProp(description: String, values: List<String>) = buildJsonObject {
        put("type", "array")
        put("description", description)
        putJsonObject("items") {
            put("type", "string")
            putJsonArray("enum") { values.forEach { add(JsonPrimitive(it)) } }
        }
    }

    private fun commandVariant(
        type: String,
        description: String,
        properties: Map<String, JsonObject>,
        required: List<String>
    ) = buildJsonObject {
        put("type", "object")
        put("description", description)
        putJsonObject("properties") {
            putJsonObject("type") {
                put("const", type)
            }
            properties.forEach { (name, schema) -> put(name, schema) }
        }
        putJsonArray("required") {
            add(JsonPrimitive("type"))
            required.forEach { add(JsonPrimitive(it)) }
        }
    }

    /** The full tool definition, ready to drop into the Messages API request's `tools` array. */
    fun toolDefinition(): JsonObject = buildJsonObject {
        put("name", TOOL_NAME)
        put(
            "description",
            "Applies one or more edits to the currently open video project. Every command " +
                "operates on an id (clip, text overlay, or audio track) that already exists in " +
                "the project -- reference ids exactly as given in the project summary, never " +
                "invent one. Multiple commands may be given in one call to satisfy a single " +
                "request (e.g. \"make it black and white and add a fade between the clips\" is " +
                "a ToggleEffect plus a ToggleTransition)."
        )
        putJsonObject("input_schema") {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("commands") {
                    put("type", "array")
                    put("description", "The edits to apply, in the order they should be applied.")
                    putJsonObject("items") {
                        putJsonArray("oneOf") { commandVariants().forEach { add(it) } }
                    }
                }
            }
            putJsonArray("required") { add(JsonPrimitive("commands")) }
        }
    }

    private fun commandVariants(): List<JsonObject> = listOf(
        commandVariant(
            "TrimClip", "Change which part of its source a video clip plays (start/end are milliseconds into the ORIGINAL source file).",
            mapOf(
                "clipId" to stringProp("The clip's id."),
                "startMs" to numberProp("New trim start, in milliseconds into the source file."),
                "endMs" to numberProp("New trim end, in milliseconds into the source file.")
            ),
            listOf("clipId", "startMs", "endMs")
        ),
        commandVariant(
            "SplitClip", "Split one clip into two at a point on the PROJECT timeline.",
            mapOf(
                "clipId" to stringProp("The clip's id."),
                "splitAtMs" to numberProp("Where to split, in milliseconds into the clip's OWN current trim range (not the whole project).")
            ),
            listOf("clipId", "splitAtMs")
        ),
        commandVariant(
            "ReorderClips", "Change the order clips play in.",
            mapOf("orderedClipIds" to stringArrayProp("Every clip id in the project, in the new desired order.")),
            listOf("orderedClipIds")
        ),
        commandVariant(
            "DeleteClip", "Remove a clip from the project entirely.",
            mapOf("clipId" to stringProp("The clip's id.")),
            listOf("clipId")
        ),
        commandVariant(
            "SetAspectRatio", "Change the whole project's output aspect ratio/crop shape.",
            mapOf("ratio" to enumProp("The new aspect ratio.", aspectRatioNames)),
            listOf("ratio")
        ),
        commandVariant(
            "ToggleEffect", "Add a filter to a clip's effect stack if it isn't already applied, or remove it if it is.",
            mapOf(
                "clipId" to stringProp("The clip's id."),
                "filter" to enumProp("The filter to toggle.", filterTypeNames)
            ),
            listOf("clipId", "filter")
        ),
        commandVariant(
            "ReorderEffects", "Change the order a clip's already-applied filters are layered in (does not add or remove any).",
            mapOf(
                "clipId" to stringProp("The clip's id."),
                "orderedFilters" to enumArrayProp("The clip's currently-applied filters, in the new desired order.", filterTypeNames)
            ),
            listOf("clipId", "orderedFilters")
        ),
        commandVariant(
            "SmartReframe", "Move the crop/reframe focal point on a clip (e.g. to keep a subject centred after changing aspect ratio).",
            mapOf(
                "clipId" to stringProp("The clip's id."),
                "focalX" to numberProp("Horizontal focal point, 0.0 (left) to 1.0 (right)."),
                "focalY" to numberProp("Vertical focal point, 0.0 (top) to 1.0 (bottom).")
            ),
            listOf("clipId", "focalX", "focalY")
        ),
        commandVariant(
            "AddText", "Add a new text overlay to the project.",
            mapOf(
                "text" to stringProp("The text to display."),
                "startMs" to numberProp("When the text appears, in milliseconds on the PROJECT timeline."),
                "endMs" to numberProp("When the text disappears, in milliseconds on the PROJECT timeline."),
                "yPositionFraction" to numberProp("Vertical position, 0.0 (top) to 1.0 (bottom).")
            ),
            listOf("text", "startMs", "endMs", "yPositionFraction")
        ),
        commandVariant(
            "SetSpeed", "Change a video clip's playback speed (has no effect on a photo).",
            mapOf(
                "clipId" to stringProp("The clip's id."),
                "speed" to numberProp("Playback speed multiplier, e.g. 0.5 for half speed, 2.0 for double.")
            ),
            listOf("clipId", "speed")
        ),
        commandVariant(
            "SetClipVolume", "Change a video clip's own audio volume.",
            mapOf(
                "clipId" to stringProp("The clip's id."),
                "volume" to numberProp("Volume multiplier, 0.0 (silent) to 2.0 (double).")
            ),
            listOf("clipId", "volume")
        ),
        commandVariant(
            "SetAudioVolume", "Change an added audio track's volume.",
            mapOf(
                "trackId" to stringProp("The audio track's id."),
                "volume" to numberProp("Volume multiplier, 0.0 (silent) to 2.0 (double).")
            ),
            listOf("trackId", "volume")
        ),
        commandVariant(
            "SetAudioLooping", "Turn looping on/off for an added audio track.",
            mapOf(
                "trackId" to stringProp("The audio track's id."),
                "isLooping" to booleanProp("Whether the track should loop to fill the project's length.")
            ),
            listOf("trackId", "isLooping")
        ),
        commandVariant(
            "SetAudioPosition", "Move and/or resize (trim from the end) an added audio track on the project timeline.",
            mapOf(
                "trackId" to stringProp("The audio track's id."),
                "startMs" to numberProp("Where the track starts, in milliseconds on the PROJECT timeline."),
                "durationMs" to numberProp("How long the track plays, in milliseconds.")
            ),
            listOf("trackId", "startMs", "durationMs")
        ),
        commandVariant(
            "SetTextPosition", "Move a text overlay in the frame.",
            mapOf(
                "overlayId" to stringProp("The text overlay's id."),
                "xFraction" to numberProp("Horizontal position, 0.0 (left) to 1.0 (right)."),
                "yFraction" to numberProp("Vertical position, 0.0 (top) to 1.0 (bottom).")
            ),
            listOf("overlayId", "xFraction", "yFraction")
        ),
        commandVariant(
            "RemoveTextOverlay", "Delete a text overlay.",
            mapOf("overlayId" to stringProp("The text overlay's id.")),
            listOf("overlayId")
        ),
        commandVariant(
            "RemoveAudioTrack", "Delete an added audio track.",
            mapOf("trackId" to stringProp("The audio track's id.")),
            listOf("trackId")
        ),
        commandVariant(
            "ToggleTransition", "Add a fade-to-black transition between a clip and the one after it, or remove it if one is already there.",
            mapOf("afterClipId" to stringProp("The id of the clip the transition sits AFTER (there must be another clip following it).")),
            listOf("afterClipId")
        ),
        commandVariant(
            "SetTransitionDuration", "Change how long an existing transition takes (only has an effect if ToggleTransition already added one).",
            mapOf(
                "afterClipId" to stringProp("The id of the clip the transition sits after."),
                "durationMs" to numberProp("New duration in milliseconds, clamped to a 200-2000ms range.")
            ),
            listOf("afterClipId", "durationMs")
        )
    )

    /**
     * A compact, human/model-readable summary of the current project -- goes in the
     * user turn alongside the actual prompt, so the model has real ids to reference
     * instead of inventing them. Deliberately plain text, not JSON: this is read by an
     * LLM, not parsed by code, and prose is both cheaper in tokens and easier for the
     * model to reference correctly than a nested JSON structure would be.
     */
    fun describeProject(project: ProjectState): String = buildString {
        appendLine("Aspect ratio: ${project.aspectRatio.label}")
        appendLine("Total duration: ${project.durationMs}ms")
        appendLine()
        appendLine("Clips (in order):")
        project.clips.forEach { clip ->
            val startMs = project.clipStartOffsetMs(clip.id)
            val effects = clip.effectiveEffects().joinToString(", ") { it.name }.ifEmpty { "none" }
            appendLine(
                "- id=${clip.id} type=${clip.sourceType} projectTimeline=${startMs}-${startMs + clip.durationMs}ms " +
                    "sourceTrim=${clip.trimStartMs}-${clip.trimEndMs}ms (of ${clip.sourceDurationMs}ms source) " +
                    "speed=${clip.speed}x volume=${clip.volume} effects=[$effects]"
            )
        }
        if (project.audioTracks.isNotEmpty()) {
            appendLine()
            appendLine("Audio tracks:")
            project.audioTracks.forEach { track ->
                appendLine(
                    "- id=${track.id} start=${track.startMs}ms duration=${track.durationMs}ms " +
                        "volume=${track.volume} looping=${track.isLooping}"
                )
            }
        }
        if (project.textOverlays.isNotEmpty()) {
            appendLine()
            appendLine("Text overlays:")
            project.textOverlays.forEach { overlay ->
                appendLine(
                    "- id=${overlay.id} text=\"${overlay.text}\" ${overlay.startMs}-${overlay.endMs}ms " +
                        "position=(${overlay.xPositionFraction}, ${overlay.yPositionFraction})"
                )
            }
        }
        val transitions = project.effectiveTransitions()
        if (transitions.isNotEmpty()) {
            appendLine()
            appendLine("Transitions:")
            transitions.forEach { transition ->
                appendLine("- after clip id=${transition.afterClipId}, duration=${transition.durationMs}ms")
            }
        }
    }

    /**
     * The app's editing model, in the model's own words, not the user's. Rewritten this
     * round for a local, non-tool-calling model: the ORIGINAL version (kept in this
     * project's git history) told the model to call an Anthropic tool named
     * $TOOL_NAME; a small on-device model has no such mechanism, so this instead asks
     * for a bare JSON array directly in its text response, matching the schema
     * embedded by [buildFullPrompt]. This is a REAL, honest capability reduction, not
     * just a wording change -- Claude's `tool_choice` structurally guaranteed a
     * matching response; nothing here does, which is why [LlmJsonExtractor] and
     * [EditCommandParser]'s defensive parsing now carry more of the actual weight of
     * "never let malformed AI output reach [com.aimediaeditor.app.editor.model.ProjectSanitizer]."
     */
    fun systemPrompt(): String = """
        You are the editing assistant inside a mobile video editor app that runs
        entirely on this device. The user describes an edit in plain language; you
        translate it into a JSON array of edit commands.

        Rules:
        - Respond with ONLY a single JSON array -- no other text, no markdown code
          fences, nothing before or after it. If there is nothing to do, respond with
          exactly [].
        - Each array element must be a JSON object matching one of the command shapes
          given below, with a "type" field naming which one exactly.
        - Every command must reference a real id from the project summary you're given
          -- never invent a clip, text overlay, or audio track id.
        - All timestamps are in MILLISECONDS. Read carefully whether a field wants a
          timestamp on the PROJECT timeline (the whole edited sequence) or in SOURCE
          time (a position inside one clip's own original file) -- they are not the
          same number line, and each field's description below says which one it means.
        - You cannot add new video, photo, or audio source material to the project --
          only edit what's already there.
        - If the request is ambiguous or can't be done with the available commands,
          respond with [] rather than guessing -- there is no way to explain why in
          this response format, so doing nothing is safer than a wrong edit.
    """.trimIndent()

    /**
     * Assembles one flat prompt string for [LocalLlmEngine.generate] -- local models
     * (unlike the Anthropic Messages API) take a single text prompt, not a separate
     * system/user message split, so this concatenates the instructions, the command
     * schema (embedded as reference JSON, not sent as a real "tool" anywhere), the
     * current project, and the user's own request into one string.
     */
    fun buildFullPrompt(project: ProjectState, userPrompt: String): String = buildString {
        appendLine(systemPrompt())
        appendLine()
        appendLine("Command shapes you may use (reference schema -- respond with an array")
        appendLine("of objects shaped like these, never with this schema itself):")
        appendLine(toolDefinition().toString())
        appendLine()
        appendLine("Current project:")
        append(describeProject(project))
        appendLine()
        appendLine("User request: $userPrompt")
    }
}
