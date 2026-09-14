package com.aimediaeditor.app.ai

import com.aimediaeditor.app.editor.model.EditCommand
import com.aimediaeditor.app.editor.model.ProjectState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * The one call this app makes to an outside service: turns a user's typed prompt into
 * [EditCommand]s via the Anthropic Messages API's tool-use feature, forced to always
 * call [EditCommandToolSchema]'s single tool so the response is always structured JSON,
 * never free text to guess at.
 *
 * HONEST RISK, the biggest of this whole project: this is the only network call this
 * app makes, and this sandbox has no way to actually exercise it -- no device, and
 * (unlike kotlinx.serialization.json's Maven Central dependency) no confirmed network
 * path to api.anthropic.com from here either. Every other piece of this app that
 * couldn't be device-tested at least had ITS specific uncertainty narrowed down (an
 * unconfirmed method name, an untested Compose gesture); this file's request/response
 * shape is written from the Messages API's publicly documented format, but has not
 * been exercised against the live API even once. See the README's "AI layer" section.
 *
 * `java.net.HttpURLConnection`, not a new HTTP client dependency -- available on every
 * API level this app targets, and every other piece of new networking-adjacent code
 * this session added (the audio waveform decoder) followed the same "prefer a
 * confirmed platform API over an unverifiable new dependency" reasoning.
 */
object ClaudeEditService {

    private const val API_URL = "https://api.anthropic.com/v1/messages"
    private const val ANTHROPIC_VERSION = "2023-06-01"

    // UNVERIFIED against the live API from this sandbox -- see this object's doc
    // comment. This is a real, current model id as of when it was written, but
    // Anthropic's model lineup changes over time; confirm against
    // https://docs.anthropic.com/en/docs/about-claude/models before shipping.
    private const val MODEL = "claude-sonnet-5"
    private const val MAX_TOKENS = 4096
    private const val CONNECT_TIMEOUT_MS = 30_000
    private const val READ_TIMEOUT_MS = 90_000

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun requestEdit(apiKey: String, project: ProjectState, userPrompt: String): AiEditResult =
        withContext(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            try {
                val requestBody = buildRequestBody(project, userPrompt)
                connection = (URL(API_URL).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("x-api-key", apiKey)
                    setRequestProperty("anthropic-version", ANTHROPIC_VERSION)
                    setRequestProperty("content-type", "application/json")
                    doOutput = true
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                }
                connection.outputStream.use { it.write(requestBody.toByteArray(Charsets.UTF_8)) }

                val responseCode = connection.responseCode
                val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
                val responseBody = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()

                if (responseCode !in 200..299) {
                    AiEditResult.Failure(describeError(responseCode, responseBody))
                } else {
                    parseSuccessResponse(responseBody)
                }
            } catch (e: Exception) {
                AiEditResult.Failure(e.message ?: "Request failed: ${e::class.simpleName}")
            } finally {
                connection?.disconnect()
            }
        }

    // internal, not private: buildRequestBody/parseSuccessResponse/describeError are the
    // pure, no-network-I/O pieces of this object -- genuinely unit-testable, unlike
    // requestEdit()'s actual HttpURLConnection call. See ClaudeEditServiceTest.
    internal fun buildRequestBody(project: ProjectState, userPrompt: String): String {
        val userMessage = buildString {
            appendLine("Current project:")
            append(EditCommandToolSchema.describeProject(project))
            appendLine()
            appendLine("User request: $userPrompt")
        }
        val body = buildJsonObject {
            put("model", MODEL)
            put("max_tokens", MAX_TOKENS)
            put("system", EditCommandToolSchema.systemPrompt())
            putJsonArray("messages") {
                add(
                    buildJsonObject {
                        put("role", "user")
                        put("content", userMessage)
                    }
                )
            }
            putJsonArray("tools") { add(EditCommandToolSchema.toolDefinition()) }
            putJsonObject("tool_choice") {
                put("type", "tool")
                put("name", EditCommandToolSchema.TOOL_NAME)
            }
        }
        return body.toString() // JsonElement.toString() IS its compact JSON form -- confirmed equivalent to Json.encodeToString in the JVM harness this was verified against
    }

    /**
     * Anthropic's Messages API returns `content` as a list of blocks -- normally a
     * `text` block (the model's own explanation) followed by a `tool_use` block (the
     * structured command list), since `tool_choice` forces the tool call but doesn't
     * forbid accompanying text. Both are optional in principle, so this reads whatever
     * is actually present rather than assuming a fixed shape.
     */
    internal fun parseSuccessResponse(responseBody: String): AiEditResult {
        // Json.parseToJsonElement THROWS on syntactically invalid JSON (not just a
        // caught-by-`as?` shape mismatch on otherwise-valid JSON) -- caught here rather
        // than relying on parseOne()'s no-op-on-bad-shape contract, which only covers
        // valid JSON in an unexpected shape, not text that isn't JSON at all.
        val root = try {
            json.parseToJsonElement(responseBody) as? JsonObject
        } catch (e: Exception) {
            null
        } ?: return AiEditResult.Failure("Unexpected response shape from the API.")
        val content = root["content"] as? JsonArray
            ?: return AiEditResult.Failure("Response had no content.")

        var assistantText: String? = null
        var commands: List<EditCommand> = emptyList()
        var foundToolUse = false

        for (block in content) {
            val obj = block as? JsonObject ?: continue
            when (obj["type"]?.jsonPrimitive?.content) {
                "text" -> assistantText = obj["text"]?.jsonPrimitive?.content
                "tool_use" -> {
                    if (obj["name"]?.jsonPrimitive?.content == EditCommandToolSchema.TOOL_NAME) {
                        val input = obj["input"] as? JsonObject
                        val commandsJson = input?.get("commands")
                        if (commandsJson != null) {
                            commands = EditCommandParser.parse(commandsJson)
                            foundToolUse = true
                        }
                    }
                }
            }
        }

        return if (foundToolUse) {
            AiEditResult.Success(commands, assistantText)
        } else {
            // The model responded but never called the tool at all (possible even with
            // tool_choice forced, e.g. if it refuses the request) -- not a network/parse
            // failure, so surface whatever text it gave as the "result" instead of
            // treating this as an error.
            AiEditResult.Success(emptyList(), assistantText ?: "The assistant didn't suggest any changes.")
        }
    }

    internal fun describeError(responseCode: Int, responseBody: String): String {
        val message = try {
            (json.parseToJsonElement(responseBody) as? JsonObject)
                ?.get("error")?.let { it as? JsonObject }
                ?.get("message")?.jsonPrimitive?.content
        } catch (e: Exception) {
            null // error body wasn't valid JSON at all -- fall back below, same as a valid-but-unexpected shape
        }
        return message ?: "Request failed with HTTP $responseCode."
    }
}

/** Outcome of one [ClaudeEditService.requestEdit] call. */
sealed interface AiEditResult {
    /**
     * [commands] can be empty even on success -- e.g. the model explained why it
     * couldn't do what was asked instead of calling the tool. [assistantMessage] is
     * the model's own text, shown to the user alongside whatever was (or wasn't)
     * applied.
     */
    data class Success(val commands: List<EditCommand>, val assistantMessage: String?) : AiEditResult
    data class Failure(val message: String) : AiEditResult
}
