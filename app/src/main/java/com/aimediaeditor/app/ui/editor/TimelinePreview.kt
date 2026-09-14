@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.aimediaeditor.app.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.media3.transformer.Composition
import androidx.media3.transformer.CompositionPlayer
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.material3.buttons.PlayPauseButton
import androidx.media3.ui.compose.material3.indicator.ProgressSlider
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Whole-project sequential playback -- every clip in order, respecting
 * trim/reorder/delete, via the same [Composition] Phase 3's export already
 * builds (see CompositionBuilder) -- photos included since they landed,
 * because this reuses that exact same Composition object rather than
 * building its own.
 *
 * Text overlays are NOT drawn by Compose here any more. They are burned
 * into the Composition itself (see CompositionBuilder), so this surface
 * shows them exactly as the exported file will -- one renderer, one
 * result. Drawing them separately in Compose would both double them up
 * and reintroduce the preview/export divergence that let the aspect-ratio
 * bug hide for several rounds.
 *
 * This is the single most experimental Media3 API in the whole app.
 * CompositionPlayer is real and documented, but Google's own release notes
 * describe it as "available for experimentation, but still under
 * development" -- treat anything unexpected here as the library's own
 * rough edges, not just this integration's, and see the README's
 * device-testing list.
 */
@Composable
fun TimelinePreview(
    composition: Composition?,
    aspectRatio: Float,
    modifier: Modifier = Modifier,
    onPositionChanged: (Long) -> Unit = {}
) {
    val context = LocalContext.current
    val player = remember { CompositionPlayer.Builder(context).build() }

    DisposableEffect(Unit) {
        onDispose { player.release() }
    }

    LaunchedEffect(composition) {
        player.stop()
        if (composition != null) {
            player.setComposition(composition)
            player.prepare()
        }
    }

    // Player doesn't push continuous position updates via listener callbacks (only
    // discrete events like media-item transitions) -- polling is the standard way to
    // drive a live-updating position, same as what ProgressSlider below almost certainly
    // does internally for its own seek bar. 100ms is frequent enough for a playhead to
    // read as smooth without polling every frame.
    LaunchedEffect(player) {
        while (isActive) {
            onPositionChanged(player.currentPosition)
            delay(100)
        }
    }

    Box(
        modifier = modifier
            // Follows the project's target ratio -- the composition really
            // crops to it now, so a hardcoded 16:9 frame here would show a
            // letterboxed lie.
            .aspectRatio(aspectRatio)
            .background(Color.Black)
    ) {
        if (composition == null) {
            Text(
                "Add at least one video clip to preview the timeline",
                color = Color.White,
                modifier = Modifier.align(Alignment.Center)
            )
        } else {
            PlayerSurface(player = player, modifier = Modifier.fillMaxSize())
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
            ) {
                ProgressSlider(player)
                Row(horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
                    PlayPauseButton(player)
                }
            }
        }
    }
}
