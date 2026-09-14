package com.aimediaeditor.app.ai

import com.aimediaeditor.app.data.index.IndexedMediaSummary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * "Stage 2" of the user's own stated chain ("Media indexing/search -> AI library
 * selection -> AI first-cut assembly -> polished killer workflow"): builds the prompt
 * an LLM uses to pick media out of the user's library -- same closed-taxonomy
 * discipline [EditCommandToolSchema] established for project edits (a fixed, simple
 * output shape -- here, just a JSON array of ids -- rather than free-form text),
 * applied to media selection instead. Runs on the on-device model
 * ([LocalMediaSelectionService]/[LocalLlmEngine]), so unlike a cloud tool-calling API
 * there's nothing that structurally forces this shape; [LlmJsonExtractor] and
 * [MediaSelectionParser]'s defensive parsing are what actually keep the response
 * honest. Unlike [EditCommandToolSchema] (19 distinct command shapes, genuinely
 * complex enough to warrant a formal embedded JSON Schema), the output shape here is
 * a single array of integers -- simple enough that [systemPrompt]'s plain-English
 * instruction is the whole spec; no separate schema document is embedded.
 *
 * Deliberately does NOT see the whole library -- [com.aimediaeditor.app.data.index.MediaIndexRepository.aiSearch]
 * narrows the full index down to a bounded candidate list first (Stage 1's own
 * deterministic [com.aimediaeditor.app.data.index.MediaSearchQuery] for recall, or the
 * most recent items if that finds nothing), both to keep the on-device model's prompt
 * a reasonable length on a library of any real size and because Stage 1 already
 * exists and doing so re-uses it rather than duplicating a second parallel recall
 * mechanism.
 */
object MediaSelectionToolSchema {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    /**
     * Plain text, not JSON -- read by an LLM, not parsed by code, same reasoning
     * [EditCommandToolSchema.describeProject] already documents for why prose beats a
     * nested JSON structure here.
     */
    fun describeCandidates(candidates: List<IndexedMediaSummary>): String = buildString {
        appendLine("Candidate media items (already narrowed down from the full library):")
        candidates.forEach { item ->
            val kind = if (item.isVideo) "video" else "photo"
            val orientation = when {
                item.height > item.width -> "portrait"
                item.width > item.height -> "landscape"
                else -> "square"
            }
            val date = dateFormat.format(Date(item.dateAddedSeconds * 1000))
            val location = item.localityName?.let { " location=$it" }.orEmpty()
            val faces = if (item.hasFaces) " hasFaces=true" else ""
            val labels = item.labels.joinToString(", ").ifEmpty { "none" }
            appendLine(
                "- id=${item.mediaId} type=$kind orientation=$orientation date=$date$location$faces " +
                    "qualityScore=${"%.2f".format(item.qualityScore)} labels=[$labels]"
            )
        }
    }

    fun systemPrompt(): String = """
        You help find media (photos and videos) in the user's personal library that match
        their natural-language request. This runs entirely on their device. You are given
        a candidate list of already-indexed media items, each with known attributes: type
        (photo/video), orientation, the date it was added, its reverse-geocoded location if
        known, whether a face was detected, a rough quality score, and ML-generated content
        labels.

        Rules:
        - Respond with ONLY a single JSON array of matching media ids (integers), in
          ranked order (best match first) -- no other text, no markdown code fences,
          nothing before or after it. If nothing plausibly matches, respond with exactly
          [].
        - Only include ids that appear in the candidate list below -- never invent one.
        - Use your judgement about labels, dates, and locations to interpret requests a
          simple keyword match would miss -- a named holiday or event, a general mood or
          vibe implied by the labels, an approximate location. The candidate list was
          already narrowed down by a simpler keyword/date search pass before it reached
          you, so lean toward including a plausible match rather than being overly strict.
        - The candidate list may not contain everything in the user's actual library (it
          was narrowed down before you saw it) -- work only with what you're given.
    """.trimIndent()

    /**
     * Assembles one flat prompt string for [LocalLlmEngine.generate] -- concatenates
     * the instructions, the candidate list, and the user's own query into one string,
     * the same "no separate system/user message split" shape [EditCommandToolSchema.buildFullPrompt]
     * uses for the edit-command flow.
     */
    fun buildFullPrompt(candidates: List<IndexedMediaSummary>, userQuery: String): String = buildString {
        appendLine(systemPrompt())
        appendLine()
        append(describeCandidates(candidates))
        appendLine()
        appendLine("User request: $userQuery")
    }
}
