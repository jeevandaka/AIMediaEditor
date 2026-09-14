package com.aimediaeditor.app.ai

import com.aimediaeditor.app.editor.model.AudioTrack
import com.aimediaeditor.app.editor.model.ClipTransition
import com.aimediaeditor.app.editor.model.ProjectState
import com.aimediaeditor.app.editor.model.TextOverlay
import com.aimediaeditor.app.editor.model.VideoClip
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Runs against the REAL production [EditCommandToolSchema]. See [EditCommandParserTest]. */
class EditCommandToolSchemaTest {

    @Test
    fun `toolDefinition builds valid, non-duplicated, AddAudio-excluded variants`() {
        val tool = EditCommandToolSchema.toolDefinition()
        assertEquals(EditCommandToolSchema.TOOL_NAME, tool.getValue("name").jsonPrimitive.content)

        val variants = schemaVariants(tool)
        // 20 EditCommand cases minus AddAudio, which needs a device URI the model
        // can't know -- see EditCommandParser's doc comment.
        assertEquals(19, variants.size)

        val types = variants.map { variant ->
            val typeSchema = variant.getValue("properties").let { it as JsonObject }.getValue("type") as JsonObject
            typeSchema.getValue("const").jsonPrimitive.content
        }
        assertEquals("expected no duplicate 'type' discriminators", types.size, types.toSet().size)
        assertFalse("AddAudio must not be exposed to the AI", "AddAudio" in types)
    }

    @Test
    fun `describeProject runs cleanly and references every real id`() {
        val project = ProjectState(
            id = "p",
            clips = listOf(
                VideoClip(id = "clip_1", sourceUri = "content://x/1", sourceDurationMs = 10000L, trimStartMs = 0L, trimEndMs = 5000L),
                VideoClip(id = "clip_2", sourceUri = "content://x/2", sourceDurationMs = 8000L, trimStartMs = 1000L, trimEndMs = 4000L)
            ),
            audioTracks = listOf(AudioTrack(id = "audio_1", sourceUri = "content://a/1", startMs = 0L, durationMs = 3000L)),
            textOverlays = listOf(TextOverlay(id = "text_1", text = "hi", startMs = 0L, endMs = 1000L, yPositionFraction = 0.5f)),
            transitions = listOf(ClipTransition(afterClipId = "clip_1"))
        )
        val description = EditCommandToolSchema.describeProject(project)
        assertTrue(description.contains("clip_1"))
        assertTrue(description.contains("clip_2"))
        assertTrue(description.contains("audio_1"))
        assertTrue(description.contains("text_1"))
        assertTrue(description.contains("after clip id=clip_1"))
    }

    @Test
    fun `describeProject does not throw on an empty project`() {
        val description = EditCommandToolSchema.describeProject(ProjectState(id = "empty"))
        assertTrue(description.contains("Total duration: 0ms"))
    }

    private fun schemaVariants(tool: JsonObject): List<JsonObject> {
        val inputSchema = tool.getValue("input_schema") as JsonObject
        val properties = inputSchema.getValue("properties") as JsonObject
        val commands = properties.getValue("commands") as JsonObject
        val items = commands.getValue("items") as JsonObject
        val oneOf = items.getValue("oneOf") as JsonArray
        return oneOf.map { it as JsonObject }
    }
}
