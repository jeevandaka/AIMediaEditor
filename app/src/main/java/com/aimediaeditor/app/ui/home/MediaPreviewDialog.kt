@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.aimediaeditor.app.ui.home

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.MediaItem as Media3Item
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.material3.buttons.PlayPauseButton
import androidx.media3.ui.compose.material3.indicator.ProgressSlider
import coil3.compose.AsyncImage
import com.aimediaeditor.app.data.media.MediaItem
import com.aimediaeditor.app.data.media.MediaType

/**
 * "Look before you add" -- tapping a grid item opens this instead of
 * immediately selecting it. Deliberately separate from the editor's
 * ClipPreview: different input type (MediaItem, not VideoClip), no crop
 * overlay, no EDL commands, so neither file needs to change for the other.
 */
@Composable
fun MediaPreviewDialog(
    item: MediaItem,
    onDismiss: () -> Unit,
    onAddToProject: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(color = Color.Black, modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxSize()) {
                when (item.type) {
                    MediaType.IMAGE -> AsyncImage(
                        model = item.uri,
                        contentDescription = item.displayName,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                    MediaType.VIDEO -> VideoPreviewPlayer(
                        uri = item.uri,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                ) {
                    Text("\u2715 Close", color = Color.White)
                }

                Button(
                    onClick = onAddToProject,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(24.dp)
                ) {
                    Text("Add to Project")
                }
            }
        }
    }
}

@Composable
private fun VideoPreviewPlayer(uri: Uri, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // A separate, short-lived player instance scoped to this dialog only --
    // never concurrent with the editor's player, since Home and Editor are
    // different nav destinations and only one is ever composed at a time.
    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(Media3Item.fromUri(uri))
            prepare()
            playWhenReady = true
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) exoPlayer.pause()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            exoPlayer.release()
        }
    }

    Box(modifier = modifier) {
        PlayerSurface(player = exoPlayer, modifier = Modifier.fillMaxSize())
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = 72.dp) // clear room above the "Add to Project" button
        ) {
            ProgressSlider(exoPlayer)
            Row(horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
                PlayPauseButton(exoPlayer)
            }
        }
    }
}
