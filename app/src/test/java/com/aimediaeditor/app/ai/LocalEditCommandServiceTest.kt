package com.aimediaeditor.app.ai

import com.aimediaeditor.app.editor.model.EditCommand
import com.aimediaeditor.app.editor.model.FilterType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Runs against the REAL production [LocalEditCommandService]'s pure parsing logic
 * ([LocalEditCommandService.parseResponse] -- `internal`, not `private`, specifically
 * so this test can reach it directly). [LocalEditCommandService.requestEdit] itself --
 * the actual local model call through [LocalLlmEngine] -- is NOT covered here or
 * anywhere else in this project: it needs a loaded native model on a real device,
 * which this sandbox doesn't have and which a JVM unit test shouldn't depend on
 * anyway (see the README's "On-device AI" section for the full honesty note on this).
 *
 * Replaces `ClaudeEditServiceTest`, which covered the equivalent cloud-response
 * parsing before the AI layer moved on-device this round. The response shape is
 * simpler now (a bare JSON array, not an Anthropic tool_use block), but the same
 * defensive "never let malformed AI output crash or reach ProjectSanitizer unchecked"
 * cases still matter -- if anything more so, since a local model's output isn't
 * structurally guaranteed the way a forced tool_choice response was.
 */
class LocalEditCommandServiceTest {

    @Test
    fun `parseResponse extracts commands from a clean JSON array`() {
        val response = """[{"type":"ToggleEffect","clipId":"clip_1","filter":"MONOCHROME"}]"""
        val result = LocalEditCommandService.parseResponse(response) as AiEditResult.Success
        assertEquals(listOf(EditCommand.ToggleEffect("clip_1", FilterType.MONOCHROME)), result.commands)
    }

    @Test
    fun `parseResponse extracts commands even when wrapped in prose and a markdown fence`() {
        // Small on-device models aren't structurally forced into pure JSON the way a
        // tool_choice-forced cloud response was -- this is the realistic failure mode
        // LlmJsonExtractor exists for.
        val response = """
            Sure, here's the edit:
            ```json
            [{"type":"ToggleEffect","clipId":"clip_1","filter":"MONOCHROME"}]
            ```
            Let me know if you'd like anything else!
        """.trimIndent()
        val result = LocalEditCommandService.parseResponse(response) as AiEditResult.Success
        assertEquals(listOf(EditCommand.ToggleEffect("clip_1", FilterType.MONOCHROME)), result.commands)
    }

    @Test
    fun `parseResponse returns an empty command list for an explicit empty array`() {
        val result = LocalEditCommandService.parseResponse("[]") as AiEditResult.Success
        assertTrue(result.commands.isEmpty())
    }

    @Test
    fun `parseResponse reports a fallback message, never throws, when no array is found at all`() {
        val result = LocalEditCommandService.parseResponse("I'm not sure what you mean.") as AiEditResult.Success
        assertTrue(result.commands.isEmpty())
        assertTrue(result.assistantMessage != null)
    }

    @Test
    fun `parseResponse drops a malformed command but keeps valid ones in the same array`() {
        val response = """
            [
              {"type":"ToggleEffect","clipId":"clip_1","filter":"MONOCHROME"},
              {"type":"ToggleEffect","clipId":"clip_2"}
            ]
        """.trimIndent()
        val result = LocalEditCommandService.parseResponse(response) as AiEditResult.Success
        assertEquals(listOf(EditCommand.ToggleEffect("clip_1", FilterType.MONOCHROME)), result.commands)
    }
}
