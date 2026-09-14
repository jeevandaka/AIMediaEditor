@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.aimediaeditor.app.editor.export

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.text.SpannableString
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.common.OverlaySettings
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.Crop
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.effect.TextureOverlay
import androidx.media3.effect.TextOverlay as Media3TextOverlay
import androidx.media3.effect.Brightness
import androidx.media3.effect.Contrast
import androidx.media3.effect.RgbFilter
import androidx.media3.transformer.Effects
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import com.aimediaeditor.app.data.media.MediaType
import com.aimediaeditor.app.editor.model.AudioTrack
import com.aimediaeditor.app.editor.model.effectiveDurationMs
import com.aimediaeditor.app.editor.model.effectiveTransitions
import com.aimediaeditor.app.editor.model.FilterType
import com.aimediaeditor.app.editor.model.FocalPoint
import com.aimediaeditor.app.editor.model.clipStartOffsetMs
import com.aimediaeditor.app.editor.model.computeCropWindow
import com.aimediaeditor.app.editor.model.ratio
import com.aimediaeditor.app.editor.model.toNdcCrop
import com.aimediaeditor.app.editor.model.ProjectState
import com.aimediaeditor.app.editor.model.TextOverlay
import com.aimediaeditor.app.editor.model.VideoClip
import com.google.common.collect.ImmutableList
import kotlin.math.abs

/**
 * Turns a [ProjectState] into the Media3 structure both Transformer
 * (export) and CompositionPlayer (timeline preview) consume -- the same
 * function feeds both, so fixing or extending it here (like this round's
 * audio support) improves both at once.
 *
 * Reorder/trim/delete fall out for free from the visual sequence's clip
 * order and each clip's ClippingConfiguration, same as before. Off-center
 * (focal-point-aware) crop is the one remaining open item from earlier
 * rounds -- see the README.
 *
 * Photo clips ARE now supported (this round). Media3 handles a still
 * image very differently from a video, and the difference is not
 * optional -- getting it wrong is a hard failure, not a subtle one:
 *   video -> EditedMediaItem.setDurationUs(sourceDuration), optional
 *            ClippingConfiguration for trims.
 *   image -> MediaItem.setImageDurationMs(displayDuration) and
 *            EditedMediaItem.setFrameRate(...), and specifically NOT
 *            setDurationUs -- Media3's own release notes state the
 *            EditedMediaItem duration "should not be set" for images
 *            because it is derived from the image duration instead.
 * Both kinds go into the SAME sequence so ordering is preserved exactly
 * as the user arranged it.
 */
object CompositionBuilder {

    /**
     * Frame rate generated for still images. 30fps matches the rate
     * Media3's own image documentation uses in its example, and is a
     * safe, universally-supported output rate.
     */
    private const val PHOTO_FRAME_RATE = 30

    fun build(project: ProjectState): Composition {
        val visualSequence = buildVisualSequence(project)
        val audioSequences = project.audioTracks.map { buildAudioSequence(it, project.durationMs) }
        val builder = Composition.Builder(listOf(visualSequence) + audioSequences)

        // Text overlays AND transitions are both positioned on the PROJECT timeline,
        // not inside any one clip -- so both attach to the Composition rather than to
        // an EditedMediaItem, and both go into the SAME OverlayEffect (Media3 composes
        // one list of TextureOverlays, not one effect per overlay type). That's what
        // makes the timestamps line up without having to work out which clip contains
        // each overlay/transition and convert to clip-relative time.
        val overlayTextures = textOverlayTextures(project.textOverlays) + transitionOverlayTextures(project)
        if (overlayTextures.isNotEmpty()) {
            builder.setEffects(Effects(/* audioProcessors= */ emptyList(), listOf(OverlayEffect(ImmutableList.copyOf(overlayTextures)))))
        }
        return builder.build()
    }

    /**
     * Builds the [TextureOverlay] list for the project's text overlays (not an
     * [OverlayEffect] directly -- [build] combines this with [transitionOverlayTextures]
     * into one shared effect, since Media3 composes one overlay list, not one effect per
     * overlay type).
     *
     * Each overlay keeps ONE constant SpannableString and toggles its alpha
     * between 1 and 0 depending on whether the current timestamp falls in its
     * window. The alternative -- returning empty text when hidden -- would make
     * Media3 rasterize a zero-size bitmap, and it would also defeat the bitmap
     * caching TextOverlay does when the text is unchanged between frames.
     *
     * yPositionFraction (0 = top) becomes an NDC anchor (+1 = top), so the
     * axis flips, same as the crop conversion. Verified for all three position
     * presets plus both extremes: top maps above centre, centre to exactly 0,
     * bottom below centre.
     *
     * xPositionFraction (0 = left) becomes an NDC anchor with NO flip needed, unlike Y:
     * NDC's X axis already increases left-to-right, the same direction xPositionFraction
     * does, so 0->-1 (left), 0.5->0 (centre), 1->+1 (right) is a plain linear map.
     */
    private fun textOverlayTextures(overlays: List<TextOverlay>): List<TextureOverlay> =
        overlays.map { overlay ->
            val spannable = SpannableString(overlay.text)
            val anchorX = 2f * overlay.xPositionFraction.coerceIn(0f, 1f) - 1f
            val anchorY = 1f - 2f * overlay.yPositionFraction.coerceIn(0f, 1f)
            val visible = StaticOverlaySettings.Builder()
                .setBackgroundFrameAnchor(anchorX, anchorY)
                .setAlphaScale(1f)
                .build()
            val hidden = StaticOverlaySettings.Builder()
                .setBackgroundFrameAnchor(anchorX, anchorY)
                .setAlphaScale(0f)
                .build()
            object : Media3TextOverlay() {
                override fun getText(presentationTimeUs: Long): SpannableString = spannable
                override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
                    val positionMs = presentationTimeUs / 1000L
                    return if (positionMs in overlay.startMs..overlay.endMs) visible else hidden
                }
            }
        }

    /**
     * Solid black, sized to at least cover any resolution this app exports at (720p or
     * 1080p -- see ExportWorker), so [transitionOverlayTextures] can rely on the
     * bitmap's own native pixel size to cover the frame at its default (no-scale)
     * overlay size, rather than depending on exactly how StaticOverlaySettings' scale
     * multiplier maps onto the target frame -- that part, unlike the alpha/anchor
     * mechanism already confirmed by the text overlay feature above, could not be
     * confirmed against this exact 1.11.0 artifact (same category of honest risk this
     * file already flags for HslAdjustment). Created once, not per frame:
     * BitmapOverlay's contract calls getBitmap(presentationTimeUs) on every frame, and
     * a fresh 1920x1080 Bitmap each time would be a real, pointless allocation/GC cost
     * for a value that's identical every time.
     */
    private val transitionBlackBitmap: Bitmap by lazy {
        Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888).apply { eraseColor(AndroidColor.BLACK) }
    }

    /**
     * Builds the [TextureOverlay] list for the project's transitions (see [build] for
     * why this returns a list to be merged with the text overlays, not its own
     * [OverlayEffect]). Only [ProjectState.effectiveTransitions] are rendered -- a
     * transition whose clip was deleted, or that's no longer followed by another clip,
     * is silently inert rather than rendering somewhere unintended.
     *
     * Each transition is a symmetric "dip to black": alpha ramps 0 -> 1 over the first
     * half of its duration (centred on the cut point) and 1 -> 0 over the second half,
     * so the frame is fully black for an instant exactly at the boundary and fully
     * transparent [durationMs]/2 on either side of it -- a linear ramp, not eased,
     * kept deliberately simple for a first version of this feature.
     */
    private fun transitionOverlayTextures(project: ProjectState): List<TextureOverlay> =
        project.effectiveTransitions().map { transition ->
            val clip = project.clips.first { it.id == transition.afterClipId }
            val boundaryMs = project.clipStartOffsetMs(transition.afterClipId) + clip.durationMs
            val halfWindowMs = (transition.durationMs / 2).coerceAtLeast(1L)
            object : BitmapOverlay() {
                override fun getBitmap(presentationTimeUs: Long): Bitmap = transitionBlackBitmap
                override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
                    val positionMs = presentationTimeUs / 1000L
                    val distanceMs = abs(positionMs - boundaryMs)
                    val alpha = if (distanceMs >= halfWindowMs) 0f else 1f - (distanceMs.toFloat() / halfWindowMs)
                    return StaticOverlaySettings.Builder()
                        .setBackgroundFrameAnchor(0f, 0f)
                        .setAlphaScale(alpha)
                        .build()
                }
            }
        }

    /**
     * Photos and videos share one sequence so the user's ordering is
     * preserved exactly; each clip is converted according to its own type.
     */
    private fun buildVisualSequence(project: ProjectState): EditedMediaItemSequence {
        val editedItems = project.clips.map { clip ->
            when (clip.sourceType) {
                MediaType.VIDEO -> buildVideoItem(clip, project.aspectRatio.ratio)
                MediaType.IMAGE -> buildImageItem(clip, project.aspectRatio.ratio)
            }
        }
        return EditedMediaItemSequence.withAudioAndVideoFrom(editedItems)
    }

    private fun buildVideoItem(clip: VideoClip, targetAspectRatio: Float): EditedMediaItem {
        val mediaItemBuilder = MediaItem.Builder()
            .setUri(Uri.parse(clip.sourceUri))

        val isTrimmed = clip.trimStartMs > 0L || clip.trimEndMs < clip.sourceDurationMs
        if (isTrimmed) {
            val startMs = clip.trimStartMs.coerceAtLeast(0L)
            val endMs = clip.trimEndMs.coerceAtMost(clip.sourceDurationMs).coerceAtLeast(startMs)
            mediaItemBuilder.setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(startMs)
                    .setEndPositionMs(endMs)
                    .build()
            )
        }

        val builder = EditedMediaItem.Builder(mediaItemBuilder.build())
            .setDurationUs(clip.sourceDurationMs * 1000L)
            .setEffects(effectsFor(clip, targetAspectRatio))

        // Speed only applies to real video -- a still has no motion to
        // re-time, and its on-screen length is already the EDL's decision.
        if (clip.speed != 1f) {
            builder.setSpeed(constantSpeedProvider(clip.speed))
        }
        return builder.build()
    }

    /**
     * Maps the EDL's [FilterType] onto Media3 effects.
     *
     * MONOCHROME/BRIGHT/CINEMATIC use the effect classes their names imply.
     * VIVID is deliberately contrast-driven rather than saturation-driven:
     * Media3's saturation control (HslAdjustment) has a builder whose exact
     * method names I could not confirm, and a filter that silently does the
     * wrong thing is worse than one built from confirmed pieces. If you want
     * true saturation later, HslAdjustment is the class to reach for.
     *
     * Effects ride on the Composition, so they appear in BOTH the timeline
     * preview and the export -- but NOT in the per-clip ExoPlayer preview,
     * which plays the raw source (see README).
     */
    private fun effectsFor(clip: VideoClip, targetAspectRatio: Float): Effects {
        val videoEffects = buildList<Effect> {
            addAll(stackedFilterEffects(clip.effectiveEffects()))
            cropEffectFor(clip, targetAspectRatio)?.let { add(it) }
        }
        val audioProcessors = buildList<AudioProcessor> {
            if (clip.sourceType == MediaType.VIDEO && clip.volume != 1f) {
                add(volumeProcessor(clip.volume))
            }
        }
        return Effects(audioProcessors, videoEffects)
    }

    /**
     * Not private: EditorScreen's live single-clip video preview reuses this exact
     * mapping via `ExoPlayer.setVideoEffects()`. Reusing it (instead of a second
     * hand-written mapping in the UI layer) is deliberate -- two implementations of
     * "what a filter looks like" is exactly the shape of bug that produced the
     * aspect-ratio/preview-vs-export divergence fixed in an earlier round.
     */
    internal fun filterEffects(filter: FilterType): List<Effect> = when (filter) {
        FilterType.NONE -> emptyList()
        FilterType.MONOCHROME -> listOf(RgbFilter.createGrayscaleFilter())
        FilterType.BRIGHT -> listOf(Brightness(0.25f))
        FilterType.CINEMATIC -> listOf(Contrast(0.25f))
        FilterType.VIVID -> listOf(Contrast(0.35f), Brightness(0.05f))
    }

    /**
     * A clip's full effect STACK (UX spec section 20, Tier 1), not just one filter --
     * concatenates each filter's own [filterEffects] in order, since Media3 already
     * applies a `List<Effect>` sequentially; stacking multiple named filters needs no
     * combination math beyond concatenation, the same mechanism VIVID already uses to
     * combine Contrast+Brightness into a single filter's own effect list. Also not
     * private for the same live-preview-reuse reason as [filterEffects] above.
     */
    internal fun stackedFilterEffects(filters: List<FilterType>): List<Effect> =
        filters.flatMap { filterEffects(it) }

    /**
     * The project's aspect ratio, applied for real -- and applied around
     * the clip's focal point, so the export matches what the crop overlay
     * in the editor shows rather than always centre-cropping.
     *
     * Returns null when no crop is needed (source already matches the
     * target), so an unnecessary identity effect never enters the pipeline.
     */
    private fun cropEffectFor(clip: VideoClip, targetAspectRatio: Float): Effect? {
        if (clip.sourceWidth <= 0 || clip.sourceHeight <= 0) return null
        val sourceAspectRatio = clip.sourceWidth.toFloat() / clip.sourceHeight.toFloat()
        val focal = clip.focalPoint ?: FocalPoint(0.5f, 0.5f)
        val ndc = computeCropWindow(sourceAspectRatio, targetAspectRatio, focal).toNdcCrop()
        if (ndc.isFullFrame) return null
        return Crop(ndc.left, ndc.right, ndc.bottom, ndc.top)
    }

    /**
     * Volume via a channel-mixing matrix scaled by the desired factor --
     * the approach Media3's own Transformer demo uses for exactly this.
     * Matrices are registered for mono and stereo input, which covers
     * essentially all phone media; Media3 leaves audio untouched for an
     * input channel count with no registered matrix, so an exotic
     * multichannel source plays at its natural volume rather than failing.
     */
    private fun volumeProcessor(volume: Float): AudioProcessor =
        ChannelMixingAudioProcessor().apply {
            for (channelCount in 1..2) {
                putChannelMixingMatrix(
                    ChannelMixingMatrix.createForConstantPower(channelCount, channelCount).scaleBy(volume)
                )
            }
        }

    /**
     * A flat, unchanging speed for the whole clip. C.TIME_UNSET from
     * getNextSpeedChangeTimeUs is Media3's way of saying "no further
     * changes" -- this mirrors the constant-speed example in Media3's own
     * release notes for setSpeed().
     */
    private fun constantSpeedProvider(speed: Float): SpeedProvider =
        object : SpeedProvider {
            override fun getSpeed(presentationTimeUs: Long): Float = speed
            override fun getNextSpeedChangeTimeUs(timeUs: Long): Long = C.TIME_UNSET
        }

    /**
     * A photo has no intrinsic length, so its on-screen time is entirely
     * the EDL's decision: trimEnd - trimStart, exactly what the timeline's
     * drag handles already edit. No ClippingConfiguration (there is no
     * source timeline to clip into) and deliberately no setDurationUs --
     * see the note on this object.
     */
    private fun buildImageItem(clip: VideoClip, targetAspectRatio: Float): EditedMediaItem {
        val displayDurationMs = (clip.trimEndMs - clip.trimStartMs).coerceAtLeast(1L)
        val imageMediaItem = MediaItem.Builder()
            .setUri(Uri.parse(clip.sourceUri))
            .setImageDurationMs(displayDurationMs)
            .build()
        return EditedMediaItem.Builder(imageMediaItem)
            .setFrameRate(PHOTO_FRAME_RATE)
            .setEffects(effectsFor(clip, targetAspectRatio))
            .build()
    }

    /**
     * One separate sequence per added track, not one sequence holding all
     * of them -- items within a single EditedMediaItemSequence are laid
     * out back-to-back with no overlap, so separate sequences are how two
     * tracks are allowed to sound at the same time (mirroring the
     * documented "background audio sequence alongside a video sequence"
     * Composition pattern, generalized to N tracks instead of exactly one).
     *
     * A leading gap positions the track at its startMs. Two specific
     * details worth flagging rather than guessing silently past:
     * - addGap's parameter is named durationUs, matching Media3's
     *   microseconds convention everywhere else in the library (confirmed
     *   unambiguous on SilenceMediaSource's own durationUs), but one
     *   official doc snippet's description text for this exact method says
     *   "milliseconds." Treated as microseconds here, trusting the
     *   library-wide naming convention over what looks like a doc typo --
     *   but a factor-of-1000 error would be very audible, so this is
     *   first on the audio testing list below.
     * - experimentalSetForceAudioTrack is, per its own documentation,
     *   "experimental and will be renamed or removed in a future release"
     *   -- Media3's own words, not a hedge added here.
     */
    private fun buildAudioSequence(track: AudioTrack, projectDurationMs: Long): EditedMediaItemSequence {
        // effectiveDurationMs is the single source of truth for "how long does this
        // track play" -- shared with the audio timeline strip's UI, so export can never
        // disagree with what the user saw and dragged.
        val effectiveDuration = track.effectiveDurationMs(projectDurationMs)
        val mediaItemBuilder = MediaItem.Builder().setUri(Uri.parse(track.sourceUri))

        // Actually clip the source to the user-trimmed length via ClippingConfiguration --
        // the same mechanism buildVideoItem uses for video trims -- rather than relying on
        // setDurationUs alone. setDurationUs is documented as a fallback for when a
        // duration can't be read from the source itself (images, an unknown-length
        // stream); for a real audio file Media3 can decode a length from, it's very
        // unlikely to be treated as an active trim, so it can't be what shortens
        // playback here. Only applied when there's an actual known source length to clip
        // against and the user has trimmed shorter than it.
        if (track.sourceDurationMs > 0L && effectiveDuration < track.sourceDurationMs) {
            mediaItemBuilder.setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(0L)
                    .setEndPositionMs(effectiveDuration)
                    .build()
            )
        }

        val audioItemBuilder = EditedMediaItem.Builder(mediaItemBuilder.build())
            .setDurationUs(effectiveDuration * 1000L)
        if (track.volume != 1f) {
            audioItemBuilder.setEffects(
                Effects(listOf(volumeProcessor(track.volume)), /* videoEffects= */ emptyList())
            )
        }
        val builder = EditedMediaItemSequence.Builder(emptyList())
        if (track.startMs > 0) {
            builder.addGap(track.startMs * 1000L) // ms -> us; see note above
            builder.experimentalSetForceAudioTrack(true)
        }
        builder.addItem(audioItemBuilder.build())
        // A looping sequence repeats for the length of the visual sequence,
        // which is exactly the "background music" behaviour -- previously a
        // track shorter than the video just stopped partway through.
        builder.setIsLooping(track.isLooping)
        return builder.build()
    }
}
