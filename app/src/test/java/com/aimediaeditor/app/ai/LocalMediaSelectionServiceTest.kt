package com.aimediaeditor.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Runs against the REAL production [LocalMediaSelectionService]'s pure parsing logic
 * ([LocalMediaSelectionService.parseResponse] -- `internal`, not `private`). Same
 * "requestSelection() itself is untestable here" caveat as [LocalEditCommandServiceTest]
 * -- see that class's doc comment.
 */
class LocalMediaSelectionServiceTest {

    @Test
    fun `parseResponse extracts a ranked list of media ids from a clean array`() {
        val result = LocalMediaSelectionService.parseResponse("[42, 7, 100]") as AiMediaSelectionResult.Success
        assertEquals(listOf(42L, 7L, 100L), result.mediaIds)
    }

    @Test
    fun `parseResponse extracts ids even when wrapped in prose`() {
        val response = "Based on the labels, these look like the best matches: [42, 7]"
        val result = LocalMediaSelectionService.parseResponse(response) as AiMediaSelectionResult.Success
        assertEquals(listOf(42L, 7L), result.mediaIds)
    }

    @Test
    fun `parseResponse returns an empty list for an explicit empty array`() {
        val result = LocalMediaSelectionService.parseResponse("[]") as AiMediaSelectionResult.Success
        assertTrue(result.mediaIds.isEmpty())
    }

    @Test
    fun `parseResponse reports a fallback message, never throws, when no array is found`() {
        val result = LocalMediaSelectionService.parseResponse("nothing matches your request") as AiMediaSelectionResult.Success
        assertTrue(result.mediaIds.isEmpty())
        assertTrue(result.assistantMessage != null)
    }
}
