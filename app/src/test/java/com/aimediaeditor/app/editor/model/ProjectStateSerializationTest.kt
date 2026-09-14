package com.aimediaeditor.app.editor.model

import com.aimediaeditor.app.data.media.MediaType
import com.aimediaeditor.app.data.project.ProjectRecord
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Runs against the REAL production model classes (ProjectState, VideoClip,
 * ProjectRecord, etc.) -- not a hand-copied mirror -- so this can never drift from
 * what actually ships. This is the checked-in, `./gradlew test`-runnable version of
 * checks a standalone throwaway Kotlin/JVM script (used repeatedly through this
 * project's development, before an automated suite existed) already ran successfully
 * against a faithful copy of these classes, pinned to this repo's exact Kotlin/
 * kotlinx-serialization versions. Porting them here closes the gap the README used to
 * flag: "No automated test suite wired into the Gradle build itself."
 *
 * `editor/model/` is deliberately Android-free (see ProjectState.kt's own doc
 * comment) except for the [MediaType] enum, which has no Android dependency of its
 * own even though it lives in an Android-touching package -- so this whole file runs
 * as a genuine local JVM unit test, no emulator or Robolectric needed.
 */
class ProjectStateSerializationTest {

    private val json = Json { ignoreUnknownKeys = true } // matches ProjectRepository's own Json config exactly

    private fun sampleRecord() = ProjectRecord(
        project = ProjectState(
            id = "project_12345",
            aspectRatio = AspectRatio.RATIO_4_5,
            clips = listOf(
                VideoClip(
                    id = "clip_1",
                    sourceUri = "content://media/external/video/media/1",
                    sourceDurationMs = 12_000L,
                    trimStartMs = 1_000L,
                    trimEndMs = 9_000L,
                    speed = 1.5f,
                    volume = 0.8f,
                    effects = listOf(FilterType.CINEMATIC, FilterType.VIVID),
                    focalPoint = FocalPoint(0.25f, 0.75f),
                    sourceType = MediaType.VIDEO,
                    sourceWidth = 1920,
                    sourceHeight = 1080
                ),
                VideoClip(
                    id = "clip_2_photo",
                    sourceUri = "content://media/external/images/media/2",
                    sourceDurationMs = 3_000L,
                    trimStartMs = 0L,
                    trimEndMs = 3_000L,
                    focalPoint = null, // exercises the nullable branch
                    sourceType = MediaType.IMAGE
                )
            ),
            audioTracks = listOf(
                AudioTrack(
                    id = "audio_1",
                    sourceUri = "content://media/external/audio/3",
                    startMs = 500L,
                    volume = 0.5f,
                    sourceDurationMs = 20_000L,
                    isLooping = true,
                    durationMs = 6_000L
                )
            ),
            textOverlays = listOf(
                TextOverlay(
                    id = "text_1",
                    text = "Hello, world! -- unicode: café 日本語",
                    startMs = 500L,
                    endMs = 4_500L,
                    yPositionFraction = 0.1f,
                    xPositionFraction = 0.75f
                )
            ),
            transitions = listOf(
                ClipTransition(afterClipId = "clip_1", type = TransitionType.FADE_TO_BLACK, durationMs = 750L)
            )
        ),
        name = "My \"Trip\" / Reel",
        createdAtMs = 1_757_000_000_000L,
        updatedAtMs = 1_757_000_005_000L
    )

    @Test
    fun `round-trips a fully populated project unchanged`() {
        val record = sampleRecord()
        val decoded = json.decodeFromString<ProjectRecord>(json.encodeToString(record))
        assertEquals(record, decoded)
    }

    @Test
    fun `ignores an unknown field instead of failing to decode`() {
        val record = sampleRecord()
        val encoded = json.encodeToString(record)
        val withExtraField = encoded.replaceFirst("\"name\":", "\"futureField\":\"x\",\"name\":")
        val decoded = json.decodeFromString<ProjectRecord>(withExtraField)
        assertEquals(record, decoded)
    }

    @Test
    fun `round-trips an empty project`() {
        val record = ProjectRecord(ProjectState(id = "empty"), "Untitled", 0L, 0L)
        val decoded = json.decodeFromString<ProjectRecord>(json.encodeToString(record))
        assertEquals(record, decoded)
    }

    @Test
    fun `AudioTrack durationMs defaults to 0L when absent from old JSON`() {
        val oldFormatJson = """
            {"project":{"id":"old_project","clips":[],"audioTracks":[
              {"id":"audio_old","sourceUri":"content://x","startMs":0,"sourceDurationMs":15000}
            ],"textOverlays":[]},"name":"Old","createdAtMs":1,"updatedAtMs":2}
        """.trimIndent()
        val decoded = json.decodeFromString<ProjectRecord>(oldFormatJson)
        assertEquals(0L, decoded.project.audioTracks.single().durationMs)
    }

    @Test
    fun `TextOverlay xPositionFraction defaults to 0-5f when absent from old JSON`() {
        val oldTextJson = """
            {"project":{"id":"old_text_project","clips":[],"audioTracks":[],"textOverlays":[
              {"id":"text_old","text":"hi","startMs":0,"endMs":1000,"yPositionFraction":0.9}
            ]},"name":"Old","createdAtMs":1,"updatedAtMs":2}
        """.trimIndent()
        val decoded = json.decodeFromString<ProjectRecord>(oldTextJson)
        // JUnit4 has no delta-less assertEquals(float, float) overload -- floats always
        // need an explicit delta, unlike the (deprecated but present) double overload.
        assertEquals(0.5f, decoded.project.textOverlays.single().xPositionFraction, 0.0001f)
    }

    @Test
    fun `VideoClip effects defaults to empty list, legacy filter survives, when absent from old JSON`() {
        val oldClipJson = """
            {"project":{"id":"old_clip_project","clips":[
              {"id":"clip_old","sourceUri":"content://x","sourceDurationMs":5000,
               "trimStartMs":0,"trimEndMs":5000,"filter":"CINEMATIC"}
            ],"audioTracks":[],"textOverlays":[]},"name":"Old","createdAtMs":1,"updatedAtMs":2}
        """.trimIndent()
        val decoded = json.decodeFromString<ProjectRecord>(oldClipJson)
        val clip = decoded.project.clips.single()
        assertTrue(clip.effects.isEmpty())
        assertEquals(FilterType.CINEMATIC, clip.filter)
        // The extension is what production code actually reads -- confirm it recovers
        // the legacy value, not just that the raw fields decoded correctly.
        assertEquals(listOf(FilterType.CINEMATIC), clip.effectiveEffects())
    }

    @Test
    fun `ProjectState transitions defaults to empty list when absent from old JSON`() {
        val oldNoTransitionsJson = """
            {"project":{"id":"old_no_transitions_project","clips":[],"audioTracks":[],
             "textOverlays":[]},"name":"Old","createdAtMs":1,"updatedAtMs":2}
        """.trimIndent()
        val decoded = json.decodeFromString<ProjectRecord>(oldNoTransitionsJson)
        assertTrue(decoded.project.transitions.isEmpty())
    }
}
