package com.aimediaeditor.app.data.media

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.max

/**
 * Decodes an audio file's amplitude envelope for waveform display -- MediaExtractor +
 * MediaCodec (core android.media, same "stable since early API, not Media3" category as
 * VideoThumbnailLoader's MediaMetadataRetriever) rather than reading the whole decoded
 * PCM stream into memory, since a multi-minute audio file's raw samples would dwarf the
 * handful of bars actually drawn on screen.
 *
 * Deliberately no caching beyond what the caller's own `remember` gives it for one
 * composable's lifetime -- same scale caveat as VideoThumbnailLoader.
 */
object AudioWaveformLoader {

    /**
     * Upper bound on how long the decode loop below is allowed to run, checked once per
     * iteration against wall-clock time. Exists purely so a malformed file or an unusual
     * codec/stream combination that never signals end-of-stream can't spin this loop
     * forever -- a real gap this round's initial version left open (flagged in the
     * README as "no timeout... could be genuinely slow"). Deliberately a plain elapsed-
     * time check, not `kotlinx.coroutines.withTimeoutOrNull` -- this loop's body is pure
     * blocking Android-framework calls with no suspension points in it, so cooperative
     * coroutine cancellation would have nothing to actually interrupt; a wall-clock check
     * on every iteration targets the real risk (how long the caller waits) directly and
     * needs no cancellation-cooperation reasoning to be confident it works.
     */
    private const val DECODE_TIMEOUT_MS = 20_000L

    /**
     * Returns [bucketCount] peak-amplitude values in [0f, 1f], one per equal-width time
     * slice of the file's FULL duration (not a trimmed range -- the caller decides how
     * many leading buckets are actually "played", since an audio track here can only be
     * trimmed from the end, always starting at the source's own beginning; see
     * AudioTrackStrip). Returns null on any failure (corrupt file, no audio track,
     * unsupported codec, revoked permission, zero-length duration) rather than throwing
     * -- a missing waveform should never be why an audio track fails to render.
     */
    suspend fun loadWaveform(context: Context, uri: Uri, bucketCount: Int): FloatArray? =
        withContext(Dispatchers.IO) {
            if (bucketCount <= 0) return@withContext null
            val extractor = MediaExtractor()
            var codec: MediaCodec? = null
            try {
                extractor.setDataSource(context, uri, null)
                val trackIndex = (0 until extractor.trackCount).firstOrNull { i ->
                    extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                } ?: return@withContext null

                val format = extractor.getTrackFormat(trackIndex)
                // MediaFormat.containsKey()/getLong(name, default) are API 29+, but this
                // app's minSdk is 26 -- a plain getLong() throwing is the portable way to
                // detect a missing key back to API 16.
                val durationUs = try {
                    format.getLong(MediaFormat.KEY_DURATION)
                } catch (e: Exception) {
                    0L
                }
                if (durationUs <= 0L) return@withContext null
                extractor.selectTrack(trackIndex)

                val mime = format.getString(MediaFormat.KEY_MIME) ?: return@withContext null
                codec = MediaCodec.createDecoderByType(mime)
                codec.configure(format, null, null, 0)
                codec.start()

                val buckets = FloatArray(bucketCount)
                val bufferInfo = MediaCodec.BufferInfo()
                var sawInputEos = false
                var sawOutputEos = false
                val decodeStartedAtMs = System.currentTimeMillis()

                while (!sawOutputEos) {
                    if (System.currentTimeMillis() - decodeStartedAtMs > DECODE_TIMEOUT_MS) {
                        // Never reached end-of-stream in time -- treat exactly like any
                        // other decode failure (corrupt file, unsupported codec): return
                        // null, don't hand back a partial/misleading waveform.
                        return@withContext null
                    }
                    if (!sawInputEos) {
                        val inputIndex = codec.dequeueInputBuffer(10_000L)
                        if (inputIndex >= 0) {
                            val inputBuffer = codec.getInputBuffer(inputIndex)
                            val sampleSize = if (inputBuffer != null) extractor.readSampleData(inputBuffer, 0) else -1
                            if (sampleSize < 0) {
                                codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                sawInputEos = true
                            } else {
                                val presentationTimeUs = extractor.sampleTime
                                codec.queueInputBuffer(inputIndex, 0, sampleSize, presentationTimeUs, 0)
                                extractor.advance()
                            }
                        }
                    }

                    val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000L)
                    if (outputIndex >= 0) {
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            sawOutputEos = true
                        }
                        if (bufferInfo.size > 0) {
                            val outputBuffer = codec.getOutputBuffer(outputIndex)
                            if (outputBuffer != null) {
                                val bucketIndex = ((bufferInfo.presentationTimeUs.toDouble() / durationUs) * bucketCount)
                                    .toInt().coerceIn(0, bucketCount - 1)
                                val peak = peakAmplitude(outputBuffer, bufferInfo)
                                buckets[bucketIndex] = max(buckets[bucketIndex], peak)
                            }
                        }
                        codec.releaseOutputBuffer(outputIndex, false)
                    }
                }

                // Normalize against the loudest bucket found rather than a fixed scale --
                // absolute PCM amplitude isn't what a viewer reads a waveform shape by,
                // relative loudness across the clip is, so a quiet recording still
                // produces a visible shape instead of a near-flat line.
                val loudest = buckets.maxOrNull()?.takeIf { it > 0f } ?: return@withContext buckets
                for (i in buckets.indices) {
                    buckets[i] = (buckets[i] / loudest).coerceIn(0f, 1f)
                }
                buckets
            } catch (e: Exception) {
                null
            } finally {
                try {
                    codec?.stop()
                } catch (e: Exception) {
                    // already stopped/never started past configure -- nothing to clean up
                }
                codec?.release()
                extractor.release()
            }
        }

    /**
     * Assumes 16-bit PCM output, which is what MediaCodec's built-in audio decoders
     * produce unless KEY_PCM_ENCODING is explicitly set to float (not done here) --
     * reads every sample in the buffer since one decoded buffer here is a single
     * compressed frame's worth of audio (a few milliseconds), not the whole file.
     */
    private fun peakAmplitude(buffer: ByteBuffer, info: MediaCodec.BufferInfo): Float {
        buffer.position(info.offset)
        buffer.limit(info.offset + info.size)
        val shortBuffer = buffer.asShortBuffer()
        var peak = 0
        while (shortBuffer.hasRemaining()) {
            val sample = abs(shortBuffer.get().toInt())
            if (sample > peak) peak = sample
        }
        return peak / 32768f
    }
}
