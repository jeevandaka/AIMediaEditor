package com.aimediaeditor.app.ai

import com.aimediaeditor.app.editor.model.EditCommand
import com.aimediaeditor.app.editor.model.FilterType
import com.aimediaeditor.app.editor.model.ProjectState
import com.aimediaeditor.app.editor.model.VideoClip
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Runs against the REAL production [ClaudeEditService]'s pure request/response logic
 * ([ClaudeEditService.buildRequestBody], [ClaudeEditService.parseSuccessResponse],
 * [ClaudeEditService.describeError] -- `internal`, not `private`, specifically so this
 * test can reach them directly). [ClaudeEditService.requestEdit] itself -- the actual
 * `HttpURLConnection` call -- is NOT covered here or anywhere else in this project: it
 * needs a real network path to api.anthropic.com, which this sandbox doesn't have and
 * which a JVM unit test shouldn't depend on anyway (see the README's "AI layer"
 * section for the full honesty note on this).
 *
 * One of these cases (a malformed/non-JSON response body) caught a real bug during
 * development: `Json.parseToJsonElement` throws on invalid JSON syntax rather than
 * returning something an `as?` cast could safely reject, so the original
 * implementation would have crashed instead of returning [AiEditResult.Failure] for a
 * response body that wasn't JSON at all.
 */
class ClaudeEditServiceTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `buildRequestBody produces a valid request with the project and prompt embedded, tool choice forced`() {
        val project = ProjectState(
            id = "p",
            clips = listOf(VideoClip(id = "c1", sourceUri = "content://x", sourceDurationMs = 5000L, trimStartMs = 0L, trimEndMs = 5000L))
        )
        val bodyStr = ClaudeEditService.buildRequestBody(project, "make it black and white")
        val body = json.parseToJsonElement(bodyStr) as JsonObject

        val messages = body.getValue("messages") as JsonArray
        assertEquals(1, messages.size)
        val userContent = (messages[0] as JsonObject).getValue("content").let {
            (it as kotlinx.serialization.json.JsonPrimitive).content
        }
        assertTrue("expected the project summary embedded in the user message", userContent.contains("c1"))
        assertTrue("expected the user's own prompt embedded in the user message", userContent.contains("make it black and white"))

        val tools = body.getValue("tools") as JsonArray
        assertEquals(1, tools.size)

        val toolChoice = body.getValue("tool_choice") as JsonObject
        assertEquals(
            EditCommandToolSchema.TOOL_NAME,
            (toolChoice.getValue("name") as kotlinx.serialization.json.JsonPrimitive).content
        )
    }

    @Test
    fun `parseSuccessResponse extracts both the text explanation and the tool_use commands`() {
        val responseJson = """
            {"id":"msg_1","type":"message","role":"assistant","content":[
              {"type":"text","text":"I'll make clip_1 black and white."},
              {"type":"tool_use","id":"toolu_1","name":"apply_edit_commands","input":{"commands":[
                {"type":"ToggleEffect","clipId":"clip_1","filter":"MONOCHROME"}
              ]}}
            ],"stop_reason":"tool_use"}
        """.trimIndent()
        val result = ClaudeEditService.parseSuccessResponse(responseJson) as AiEditResult.Success
        assertEquals(listOf(EditCommand.ToggleEffect("clip_1", FilterType.MONOCHROME)), result.commands)
        assertEquals("I'll make clip_1 black and white.", result.assistantMessage)
    }

    @Test
    fun `parseSuccessResponse surfaces a text-only response with no commands`() {
        val responseJson = """
            {"id":"msg_1","type":"message","role":"assistant","content":[
              {"type":"text","text":"I can't add new audio -- I don't have access to your media library."}
            ],"stop_reason":"end_turn"}
        """.trimIndent()
        val result = ClaudeEditService.parseSuccessResponse(responseJson) as AiEditResult.Success
        assertTrue(result.commands.isEmpty())
        assertEquals("I can't add new audio -- I don't have access to your media library.", result.assistantMessage)
    }

    @Test
    fun `parseSuccessResponse reports Failure, never throws, on a malformed response`() {
        val noContent = ClaudeEditService.parseSuccessResponse("""{"id":"msg_1"}""")
        assertTrue(noContent is AiEditResult.Failure)

        // Regression case: Json.parseToJsonElement throws on text that isn't JSON at
        // all -- this must not propagate out of parseSuccessResponse as an exception.
        val notJson = ClaudeEditService.parseSuccessResponse("not json at all")
        assertTrue(notJson is AiEditResult.Failure)
    }

    @Test
    fun `describeError extracts the API's own error message, falls back to a generic one`() {
        val errorJson = """{"type":"error","error":{"type":"invalid_request_error","message":"messages: at least one message is required"}}"""
        assertEquals("messages: at least one message is required", ClaudeEditService.describeError(400, errorJson))

        assertEquals("Request failed with HTTP 500.", ClaudeEditService.describeError(500, "not json"))
    }
}
