package com.aimediaeditor.app.data.index

import java.util.Calendar

/**
 * Pure, Android-free summary of one indexed media item -- what [MediaSearchQuery]
 * matches against. Deliberately NOT the Room entity ([MediaIndexEntity]): keeping this
 * file free of Room/Android imports is what makes the actual search logic below
 * unit-testable on a plain JVM, the same reason [com.aimediaeditor.app.editor.model.ProjectState]
 * stays Android-free despite being used throughout an Android app. [MediaIndexRepository]
 * is the (untestable-here) glue that converts real Room rows into this shape.
 */
data class IndexedMediaSummary(
    val mediaId: Long,
    val isVideo: Boolean,
    val dateAddedSeconds: Long,
    val width: Int,
    val height: Int,
    val localityName: String?,
    val hasFaces: Boolean,
    val labels: List<String>, // lowercase
    val qualityScore: Float // 0f..1f
)

enum class MediaTypeFilter { PHOTO, VIDEO }

data class SearchFilter(
    val keywords: List<String>,
    val mediaTypeFilter: MediaTypeFilter?,
    val requirePortrait: Boolean,
    val requireFaces: Boolean,
    val dateAfterSeconds: Long?,
    val dateBeforeSeconds: Long?,
    val sortByQuality: Boolean
)

/**
 * MVP natural-language search over the local media index (spec sections 5/6) --
 * deliberately deterministic (tokenize + match), not an LLM call: this is "Stage 1" of
 * the search/select/assemble chain, meant to work instantly, for free, with no network
 * or API key required, before an AI-driven query layer is built on top of it in a later
 * round. A query like "find my best travel photos from Goa" becomes: mediaType=PHOTO,
 * sortByQuality=true, keywords=["travel","goa"] (matched against ML Kit labels and the
 * reverse-geocoded locality name) -- covers a real slice of the spec's example queries
 * ("Find pictures from Goa," "Find sunset photos," "Find my best portrait photos")
 * without needing a model call at all.
 */
object MediaSearchQuery {

    private val STOPWORDS = setOf(
        "find", "my", "show", "me", "give", "get", "search", "for",
        "photos", "photo", "pictures", "picture", "pics", "pic",
        "videos", "video", "clips", "clip",
        "of", "from", "the", "a", "an", "with", "and", "in", "on", "at", "all"
    )
    private val MONTH_NAMES = listOf(
        "january", "february", "march", "april", "may", "june",
        "july", "august", "september", "october", "november", "december"
    )
    private val PHOTO_WORDS = setOf("photo", "photos", "picture", "pictures", "pic", "pics", "image", "images")
    private val VIDEO_WORDS = setOf("video", "videos", "clip", "clips", "footage")
    // Deliberately does NOT include "portrait"/"portraits" -- that word is handled
    // separately as an ORIENTATION signal (height > width), not a "photo of a person"
    // signal. The two are related but distinct: "my best portrait photos" (spec
    // section 5's own example) means tall-format shots for a Reel/Story, not
    // necessarily a face closeup. Conflating them was a real bug caught by
    // runMediaSearchQueryChecks: a portrait-ORIENTED test photo with no face
    // detected was wrongly excluded because "portrait" was also requiring hasFaces.
    private val FACE_WORDS = setOf("face", "faces", "people", "person", "selfie", "selfies")
    private val QUALITY_WORDS = setOf("best", "favorite", "favorites", "favourite", "favourites", "top", "great")

    fun parse(query: String, nowMs: Long): SearchFilter {
        val words = query.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        val wordSet = words.toSet()

        val mediaTypeFilter = when {
            wordSet.any { it in VIDEO_WORDS } -> MediaTypeFilter.VIDEO
            wordSet.any { it in PHOTO_WORDS } -> MediaTypeFilter.PHOTO
            else -> null
        }
        val requirePortrait = "portrait" in wordSet || "portraits" in wordSet
        val requireFaces = wordSet.any { it in FACE_WORDS }
        val sortByQuality = wordSet.any { it in QUALITY_WORDS }

        var dateAfter: Long? = null
        var dateBefore: Long? = null
        val monthName = words.firstOrNull { it in MONTH_NAMES }
        when {
            "today" in wordSet -> dateAfter = startOfDay(nowMs, daysAgo = 0)
            "yesterday" in wordSet -> {
                dateAfter = startOfDay(nowMs, daysAgo = 1)
                dateBefore = startOfDay(nowMs, daysAgo = 0)
            }
            "week" in wordSet -> dateAfter = addToNow(nowMs) { add(Calendar.DAY_OF_YEAR, -7) }
            "month" in wordSet && monthName == null -> dateAfter = addToNow(nowMs) { add(Calendar.MONTH, -1) }
            "year" in wordSet -> dateAfter = addToNow(nowMs) { add(Calendar.YEAR, -1) }
        }
        if (monthName != null) {
            val monthIndex = MONTH_NAMES.indexOf(monthName) // 0 = January, matches Calendar.MONTH
            val cal = Calendar.getInstance().apply { timeInMillis = nowMs }
            cal.set(Calendar.MONTH, monthIndex)
            cal.set(Calendar.DAY_OF_MONTH, 1)
            cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
            // A month later than "now" almost certainly meant last year's occurrence
            // (e.g. searching "December photos" in March means last December, not a
            // future one).
            if (cal.timeInMillis > nowMs) cal.add(Calendar.YEAR, -1)
            dateAfter = cal.timeInMillis / 1000
            cal.add(Calendar.MONTH, 1)
            dateBefore = cal.timeInMillis / 1000
        }

        val specialWords = VIDEO_WORDS + PHOTO_WORDS + FACE_WORDS + QUALITY_WORDS +
            MONTH_NAMES + setOf("today", "yesterday", "week", "month", "year", "portrait", "portraits")
        val keywords = words.filter { it !in STOPWORDS && it !in specialWords }.distinct()

        return SearchFilter(
            keywords = keywords,
            mediaTypeFilter = mediaTypeFilter,
            requirePortrait = requirePortrait,
            requireFaces = requireFaces,
            dateAfterSeconds = dateAfter,
            dateBeforeSeconds = dateBefore,
            sortByQuality = sortByQuality
        )
    }

    /** True if [item] satisfies every criterion [filter] specifies (AND across criteria; a
     *  criterion the filter doesn't ask for is never a reason to reject). Each keyword must
     *  match at least one label or the locality name (AND across keywords, OR within a
     *  keyword's possible matches) -- "sunset beach" should mean both, not either. */
    fun matches(item: IndexedMediaSummary, filter: SearchFilter): Boolean {
        if (filter.mediaTypeFilter == MediaTypeFilter.PHOTO && item.isVideo) return false
        if (filter.mediaTypeFilter == MediaTypeFilter.VIDEO && !item.isVideo) return false
        if (filter.requirePortrait && item.height <= item.width) return false
        if (filter.requireFaces && !item.hasFaces) return false
        filter.dateAfterSeconds?.let { if (item.dateAddedSeconds < it) return false }
        filter.dateBeforeSeconds?.let { if (item.dateAddedSeconds >= it) return false }
        for (keyword in filter.keywords) {
            val matchesLabel = item.labels.any { it.contains(keyword) }
            val matchesLocality = item.localityName?.lowercase()?.contains(keyword) == true
            if (!matchesLabel && !matchesLocality) return false
        }
        return true
    }

    /** Applies [matches] across the whole index, then sorts: quality-first if the query
     *  asked for it ("best"/"favorite"), newest-first otherwise -- matching the same
     *  recency-first convention [com.aimediaeditor.app.data.media.MediaRepository] already
     *  uses for the plain media grid. */
    fun search(index: List<IndexedMediaSummary>, query: String, nowMs: Long): List<IndexedMediaSummary> {
        val filter = parse(query, nowMs)
        val matched = index.filter { matches(it, filter) }
        return if (filter.sortByQuality) {
            matched.sortedByDescending { it.qualityScore }
        } else {
            matched.sortedByDescending { it.dateAddedSeconds }
        }
    }

    private fun startOfDay(nowMs: Long, daysAgo: Int): Long {
        val cal = Calendar.getInstance().apply { timeInMillis = nowMs }
        cal.add(Calendar.DAY_OF_YEAR, -daysAgo)
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis / 1000
    }

    private fun addToNow(nowMs: Long, adjust: Calendar.() -> Unit): Long {
        val cal = Calendar.getInstance().apply { timeInMillis = nowMs }
        cal.adjust()
        return cal.timeInMillis / 1000
    }
}
