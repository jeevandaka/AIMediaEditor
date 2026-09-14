package com.aimediaeditor.app.ai

import com.aimediaeditor.app.editor.model.EditCommand
import com.aimediaeditor.app.editor.model.FilterType
import com.aimediaeditor.app.editor.model.FocalPoint
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Runs against the REAL production [EditCommandParser]. This is the single highest-
 * stakes piece of new code the AI layer adds: a bug here means an LLM's response gets
 * silently mis-translated into the wrong [EditCommand], and unlike a hand-authored UI
 * gesture, nothing about that would look obviously broken on screen until the wrong
 * edit actually happens. Every case here was first proven to pass for real in a
 * standalone Kotlin/JVM harness before being ported -- see the README's "AI layer"
 * section for why that was possible here (kotlinx.serialization.json is the parser's
 * only real dependency, and it resolves from Maven Central, unlike Media3).
 */
class EditCommandParserTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun commandsFrom(rawJson: String): List<EditCommand> {
        val parsed = json.parseToJsonElement(rawJson) as JsonObject
        return EditCommandParser.parse(parsed.getValue("commands"))
    }

    @Test
    fun `round-trips a realistic multi-command tool_use response exactly`() {
        val commands = commandsFrom(
            """
            {"commands": [
              {"type":"ToggleEffect","clipId":"clip_1","filter":"MONOCHROME"},
              {"type":"ToggleTransition","afterClipId":"clip_1"},
              {"type":"AddText","text":"Hello!","startMs":0,"endMs":2000,"yPositionFraction":0.1},
              {"type":"SetSpeed","clipId":"clip_2","speed":1.5},
              {"type":"SmartReframe","clipId":"clip_1","focalX":0.25,"focalY":0.75},
              {"type":"ReorderClips","orderedClipIds":["clip_2","clip_1"]},
              {"type":"SetAudioLooping","trackId":"audio_1","isLooping":true}
            ]}
            """.trimIndent()
        )
        assertEquals(
            listOf(
                EditCommand.ToggleEffect("clip_1", FilterType.MONOCHROME),
                EditCommand.ToggleTransition("clip_1"),
                EditCommand.AddText("Hello!", 0L, 2000L, 0.1f),
                EditCommand.SetSpeed("clip_2", 1.5f),
                EditCommand.SmartReframe("clip_1", FocalPoint(0.25f, 0.75f)),
                EditCommand.ReorderClips(listOf("clip_2", "clip_1")),
                EditCommand.SetAudioLooping("audio_1", true)
            ),
            commands
        )
    }

    @Test
    fun `drops a command missing a required field, keeps the rest`() {
        val commands = commandsFrom(
            """
            {"commands": [
              {"type":"ToggleEffect","clipId":"clip_1"},
              {"type":"SetSpeed","clipId":"clip_2","speed":1.5}
            ]}
            """.trimIndent()
        )
        assertEquals(listOf(EditCommand.SetSpeed("clip_2", 1.5f)), commands)
    }

    @Test
    fun `drops a command with a wrong-typed field`() {
        val commands = commandsFrom("""{"commands": [{"type":"SetSpeed","clipId":"clip_1","speed":"fast"}]}""")
        assertTrue(commands.isEmpty())
    }

    @Test
    fun `drops a command with an unrecognized enum value`() {
        val commands = commandsFrom("""{"commands": [{"type":"ToggleEffect","clipId":"clip_1","filter":"SEPIA"}]}""")
        assertTrue(commands.isEmpty())
    }

    @Test
    fun `drops AddAudio and any hallucinated command type`() {
        // AddAudio is deliberately excluded from the taxonomy exposed to the model (see
        // EditCommandParser's doc comment) -- it needs a real device URI the model has
        // no way to know, so even if the model hallucinates one anyway, it must not
        // parse into a real command.
        val commands = commandsFrom(
            """
            {"commands": [
              {"type":"AddAudio","sourceUri":"content://fake","startMs":0,"volume":1.0},
              {"type":"DeleteEverything","clipId":"clip_1"}
            ]}
            """.trimIndent()
        )
        assertTrue(commands.isEmpty())
    }

    @Test
    fun `handles a non-array commands value without crashing`() {
        val commands = commandsFrom("""{"commands": "not an array"}""")
        assertTrue(commands.isEmpty())
    }

    @Test
    fun `skips a non-object array entry, keeps the rest`() {
        val commands = commandsFrom("""{"commands": ["not an object", {"type":"DeleteClip","clipId":"clip_1"}]}""")
        assertEquals(listOf(EditCommand.DeleteClip("clip_1")), commands)
    }

    @Test
    fun `every EditCommand case the schema exposes has a working parser branch`() {
        // Exercises one minimal, valid JSON entry per command type the AI is allowed to
        // emit (everything except AddAudio) -- a regression test against a command
        // being added to the schema without a matching parser branch (or vice versa),
        // which EditCommandToolSchemaTest checks from the schema's side.
        val allTypesJson = """
            {"commands": [
              {"type":"TrimClip","clipId":"c","startMs":0,"endMs":100},
              {"type":"SplitClip","clipId":"c","splitAtMs":50},
              {"type":"ReorderClips","orderedClipIds":["c"]},
              {"type":"DeleteClip","clipId":"c"},
              {"type":"SetAspectRatio","ratio":"RATIO_1_1"},
              {"type":"ToggleEffect","clipId":"c","filter":"NONE"},
              {"type":"ReorderEffects","clipId":"c","orderedFilters":["NONE"]},
              {"type":"SmartReframe","clipId":"c","focalX":0.5,"focalY":0.5},
              {"type":"AddText","text":"t","startMs":0,"endMs":100,"yPositionFraction":0.5},
              {"type":"SetSpeed","clipId":"c","speed":1.0},
              {"type":"SetClipVolume","clipId":"c","volume":1.0},
              {"type":"SetAudioVolume","trackId":"a","volume":1.0},
              {"type":"SetAudioLooping","trackId":"a","isLooping":false},
              {"type":"SetAudioPosition","trackId":"a","startMs":0,"durationMs":100},
              {"type":"SetTextPosition","overlayId":"o","xFraction":0.5,"yFraction":0.5},
              {"type":"RemoveTextOverlay","overlayId":"o"},
              {"type":"RemoveAudioTrack","trackId":"a"},
              {"type":"ToggleTransition","afterClipId":"c"},
              {"type":"SetTransitionDuration","afterClipId":"c","durationMs":500}
            ]}
        """.trimIndent()
        val commands = commandsFrom(allTypesJson)
        assertEquals(19, commands.size)
    }
}
