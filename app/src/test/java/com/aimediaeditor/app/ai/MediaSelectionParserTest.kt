package com.aimediaeditor.app.ai

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Runs against the REAL production [MediaSelectionParser]. */
class MediaSelectionParserTest {

    @Test
    fun `parses a clean array of integers`() {
        val json = Json.parseToJsonElement("[1, 2, 3]")
        assertEquals(listOf(1L, 2L, 3L), MediaSelectionParser.parse(json))
    }

    @Test
    fun `drops a non-numeric entry but keeps the valid ones`() {
        val json = Json.parseToJsonElement("""[1, "not a number", 3]""")
        assertEquals(listOf(1L, 3L), MediaSelectionParser.parse(json))
    }

    @Test
    fun `drops a nested object entry`() {
        val json = Json.parseToJsonElement("""[1, {"id": 2}, 3]""")
        assertEquals(listOf(1L, 3L), MediaSelectionParser.parse(json))
    }

    @Test
    fun `returns an empty list for a JSON element that isn't an array`() {
        val json = Json.parseToJsonElement("""{"mediaIds": [1, 2]}""")
        assertTrue(MediaSelectionParser.parse(json).isEmpty())
    }

    @Test
    fun `returns an empty list for an empty array`() {
        val json = Json.parseToJsonElement("[]")
        assertTrue(MediaSelectionParser.parse(json).isEmpty())
    }
}
