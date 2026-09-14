package com.aimediaeditor.app.ai

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Runs against the REAL production [LlmJsonExtractor]. */
class LlmJsonExtractorTest {

    @Test
    fun `extracts a bare JSON array unchanged`() {
        val result = LlmJsonExtractor.extractJsonArray("[1, 2, 3]") as JsonArray
        assertEquals(listOf(1L, 2L, 3L), result.map { it.jsonPrimitive.content.toLong() })
    }

    @Test
    fun `extracts an array wrapped in prose and a markdown code fence`() {
        val text = "Sure, here you go:\n```json\n[1, 2, 3]\n```\nHope that helps!"
        val result = LlmJsonExtractor.extractJsonArray(text) as JsonArray
        assertEquals(3, result.size)
    }

    @Test
    fun `returns null when there is no array at all`() {
        assertNull(LlmJsonExtractor.extractJsonArray("I'm not sure what you mean."))
    }

    @Test
    fun `returns null for an empty string`() {
        assertNull(LlmJsonExtractor.extractJsonArray(""))
    }

    @Test
    fun `returns null when brackets are present but the content between them isn't valid JSON`() {
        // A stray '[' or ']' in surrounding prose (e.g. "options [a, b] or [c, d]") is
        // exactly the case this heuristic can't perfectly handle -- documented in
        // LlmJsonExtractor's own doc comment as a known, accepted limitation.
        assertNull(LlmJsonExtractor.extractJsonArray("pick one of [this] or that ]"))
    }

    @Test
    fun `extracts an empty array`() {
        val result = LlmJsonExtractor.extractJsonArray("[]") as JsonArray
        assertTrue(result.isEmpty())
    }
}
