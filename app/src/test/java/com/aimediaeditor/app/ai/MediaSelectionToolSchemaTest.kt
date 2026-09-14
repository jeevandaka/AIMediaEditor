package com.aimediaeditor.app.ai

import com.aimediaeditor.app.data.index.IndexedMediaSummary
import org.junit.Assert.assertTrue
import org.junit.Test

/** Runs against the REAL production [MediaSelectionToolSchema]. */
class MediaSelectionToolSchemaTest {

    private fun sampleItem(id: Long) = IndexedMediaSummary(
        mediaId = id,
        isVideo = false,
        dateAddedSeconds = 1_700_000_000L,
        width = 1920,
        height = 1080,
        localityName = "Goa, India",
        hasFaces = true,
        labels = listOf("beach", "sunset"),
        qualityScore = 0.8f
    )

    @Test
    fun `describeCandidates references every candidate's id and known attributes`() {
        val description = MediaSelectionToolSchema.describeCandidates(listOf(sampleItem(42)))
        assertTrue(description.contains("id=42"))
        assertTrue(description.contains("Goa, India"))
        assertTrue(description.contains("beach"))
        assertTrue(description.contains("hasFaces=true"))
    }

    @Test
    fun `describeCandidates does not throw on an empty candidate list`() {
        val description = MediaSelectionToolSchema.describeCandidates(emptyList())
        assertTrue(description.contains("Candidate media items"))
    }

    @Test
    fun `buildFullPrompt embeds both the candidate list and the user's query`() {
        val prompt = MediaSelectionToolSchema.buildFullPrompt(listOf(sampleItem(7)), "find my best beach photos")
        assertTrue(prompt.contains("id=7"))
        assertTrue(prompt.contains("find my best beach photos"))
    }
}
