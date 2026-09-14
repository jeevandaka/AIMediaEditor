package com.aimediaeditor.app.data.index

import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Runs against the REAL production [MediaSearchQuery]. This is the MVP "natural-
 * language search" the spec describes (sections 5/6) -- deliberately deterministic
 * (tokenize + match), not an LLM call, so it works instantly with no network/API key.
 *
 * One case here (the portrait-orientation test) caught a real bug during development:
 * "portrait" was originally classified as BOTH an orientation signal and a
 * "photo of a person" signal, so a portrait-ORIENTED test photo with no detected face
 * was wrongly excluded by an unintended `requireFaces` check riding along with it.
 * Fixed by keeping "portrait"/"portraits" as an orientation-only signal.
 */
class MediaSearchQueryTest {

    private fun fixedNow(year: Int, month: Int, day: Int, hour: Int = 12): Long {
        val cal = Calendar.getInstance()
        cal.set(year, month - 1, day, hour, 0, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private fun secondsAt(year: Int, month: Int, day: Int, hour: Int = 12): Long =
        fixedNow(year, month, day, hour) / 1000

    private fun sampleItem(
        id: Long,
        isVideo: Boolean = false,
        dateAddedSeconds: Long = 0L,
        width: Int = 1920,
        height: Int = 1080,
        localityName: String? = null,
        hasFaces: Boolean = false,
        labels: List<String> = emptyList(),
        qualityScore: Float = 0.5f
    ) = IndexedMediaSummary(id, isVideo, dateAddedSeconds, width, height, localityName, hasFaces, labels, qualityScore)

    private val now = fixedNow(2026, 6, 15) // fixed "today" so date-relative checks are deterministic

    @Test
    fun `keyword search matches labels, excludes non-matching items`() {
        val dogPhoto = sampleItem(1, labels = listOf("dog", "pet", "outdoor"))
        val catPhoto = sampleItem(2, labels = listOf("cat", "indoor"))
        val result = MediaSearchQuery.search(listOf(dogPhoto, catPhoto), "photos of my dog", now)
        assertEquals(listOf(1L), result.map { it.mediaId })
    }

    @Test
    fun `keyword search matches locality name`() {
        val goaPhoto = sampleItem(1, localityName = "Goa", labels = listOf("beach"))
        val delhiPhoto = sampleItem(2, localityName = "Delhi", labels = listOf("city"))
        val result = MediaSearchQuery.search(listOf(goaPhoto, delhiPhoto), "pictures from Goa", now)
        assertEquals(listOf(1L), result.map { it.mediaId })
    }

    @Test
    fun `multi-keyword search requires all keywords to match, not any`() {
        val sunsetBeach = sampleItem(1, labels = listOf("sunset", "beach"))
        val sunsetOnly = sampleItem(2, labels = listOf("sunset", "mountain"))
        val beachOnly = sampleItem(3, labels = listOf("city", "beach"))
        val result = MediaSearchQuery.search(listOf(sunsetBeach, sunsetOnly, beachOnly), "sunset beach photos", now)
        assertEquals(listOf(1L), result.map { it.mediaId })
    }

    @Test
    fun `media type filter excludes the other type`() {
        val photo = sampleItem(1, isVideo = false, labels = listOf("dog"))
        val video = sampleItem(2, isVideo = true, labels = listOf("dog"))
        assertEquals(listOf(2L), MediaSearchQuery.search(listOf(photo, video), "videos of my dog", now).map { it.mediaId })
        assertEquals(listOf(1L), MediaSearchQuery.search(listOf(photo, video), "photos of my dog", now).map { it.mediaId })
    }

    @Test
    fun `portrait filter matches orientation only, not face presence`() {
        val portrait = sampleItem(1, width = 1080, height = 1920) // no face detected
        val landscape = sampleItem(2, width = 1920, height = 1080)
        val result = MediaSearchQuery.search(listOf(portrait, landscape), "my best portrait photos", now)
        assertEquals(listOf(1L), result.map { it.mediaId })
    }

    @Test
    fun `face requirement filter matches hasFaces only`() {
        val withFace = sampleItem(1, hasFaces = true)
        val withoutFace = sampleItem(2, hasFaces = false)
        val result = MediaSearchQuery.search(listOf(withFace, withoutFace), "photos of people", now)
        assertEquals(listOf(1L), result.map { it.mediaId })
    }

    @Test
    fun `best triggers quality-sort, otherwise defaults to recency-sort`() {
        val oldHighQuality = sampleItem(1, dateAddedSeconds = 1000L, qualityScore = 0.9f)
        val newLowQuality = sampleItem(2, dateAddedSeconds = 5000L, qualityScore = 0.2f)
        assertEquals(
            listOf(1L, 2L),
            MediaSearchQuery.search(listOf(oldHighQuality, newLowQuality), "my best photos", now).map { it.mediaId }
        )
        assertEquals(
            listOf(2L, 1L),
            MediaSearchQuery.search(listOf(oldHighQuality, newLowQuality), "my photos", now).map { it.mediaId }
        )
    }

    @Test
    fun `today date filter matches only items added today`() {
        val today = sampleItem(1, dateAddedSeconds = secondsAt(2026, 6, 15, hour = 8))
        val yesterday = sampleItem(2, dateAddedSeconds = secondsAt(2026, 6, 14, hour = 20))
        val result = MediaSearchQuery.search(listOf(today, yesterday), "photos from today", now)
        assertEquals(listOf(1L), result.map { it.mediaId })
    }

    @Test
    fun `yesterday date filter is exclusive of today and two days ago`() {
        val today = sampleItem(1, dateAddedSeconds = secondsAt(2026, 6, 15, hour = 8))
        val yesterday = sampleItem(2, dateAddedSeconds = secondsAt(2026, 6, 14, hour = 20))
        val twoDaysAgo = sampleItem(3, dateAddedSeconds = secondsAt(2026, 6, 13, hour = 20))
        val result = MediaSearchQuery.search(listOf(today, yesterday, twoDaysAgo), "photos from yesterday", now)
        assertEquals(listOf(2L), result.map { it.mediaId })
    }

    @Test
    fun `month-name filter resolves to this year's occurrence when already past`() {
        val marchPhoto = sampleItem(1, dateAddedSeconds = secondsAt(2026, 3, 15))
        val aprilPhoto = sampleItem(2, dateAddedSeconds = secondsAt(2026, 4, 15))
        // "now" is June 2026 -- March has already happened this year.
        val result = MediaSearchQuery.search(listOf(marchPhoto, aprilPhoto), "photos from march", now)
        assertEquals(listOf(1L), result.map { it.mediaId })
    }

    @Test
    fun `month-name filter resolves to last year's occurrence when not yet happened this year`() {
        val decemberLastYear = sampleItem(1, dateAddedSeconds = secondsAt(2025, 12, 15))
        val decemberThisYearFuture = sampleItem(2, dateAddedSeconds = secondsAt(2026, 12, 15))
        // "now" is June 2026 -- December hasn't happened yet this year.
        val result = MediaSearchQuery.search(listOf(decemberLastYear, decemberThisYearFuture), "photos from december", now)
        assertEquals(listOf(1L), result.map { it.mediaId })
    }

    @Test
    fun `a query with no real keywords matches everything of the requested type`() {
        val video1 = sampleItem(1, isVideo = true, labels = listOf("anything"))
        val video2 = sampleItem(2, isVideo = true, labels = listOf("something else"))
        val photo1 = sampleItem(3, isVideo = false)
        val result = MediaSearchQuery.search(listOf(video1, video2, photo1), "show me videos", now)
        assertEquals(setOf(1L, 2L), result.map { it.mediaId }.toSet())
    }

    @Test
    fun `a query matching nothing returns an empty list, not a crash`() {
        val photo = sampleItem(1, labels = listOf("dog"))
        val result = MediaSearchQuery.search(listOf(photo), "underwater submarine photography", now)
        assertTrue(result.isEmpty())
    }
}
