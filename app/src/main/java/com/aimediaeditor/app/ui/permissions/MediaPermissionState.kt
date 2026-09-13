package com.aimediaeditor.app.ui.permissions

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * Android's media-permission model has shifted release to release --
 * scoped storage, granular media permissions in 13, the partial-access
 * picker in 14 -- so this centralizes the version check in one place
 * (architecture notes section 4.1) instead of scattering it through the UI.
 */
data class MediaPermissionState(
    val hasFullAccess: Boolean,
    val hasPartialAccess: Boolean,
    val hasAudioAccess: Boolean,
    val request: () -> Unit
) {
    val isGranted: Boolean get() = hasFullAccess || hasPartialAccess
}

private fun requiredPermissions(): Array<String> = when {
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> arrayOf(
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VIDEO,
        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
        Manifest.permission.READ_MEDIA_AUDIO
    )
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VIDEO,
        Manifest.permission.READ_MEDIA_AUDIO
    )
    // Pre-33: one blanket permission covers images, video, and audio alike.
    else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
}

@Composable
fun rememberMediaPermissionState(): MediaPermissionState {
    val context = LocalContext.current
    val permissions = remember { requiredPermissions() }

    fun currentlyGranted(): Set<String> = permissions.filter {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }.toSet()

    var granted by remember { mutableStateOf(currentlyGranted()) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        granted = granted + result.filterValues { it }.keys
    }

    val hasFullAccess = granted.contains(Manifest.permission.READ_MEDIA_IMAGES) ||
        granted.contains(Manifest.permission.READ_EXTERNAL_STORAGE)
    val hasPartialAccess = granted.contains(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
    val hasAudioAccess = granted.contains(Manifest.permission.READ_MEDIA_AUDIO) ||
        granted.contains(Manifest.permission.READ_EXTERNAL_STORAGE)

    return MediaPermissionState(
        hasFullAccess = hasFullAccess,
        hasPartialAccess = hasPartialAccess,
        hasAudioAccess = hasAudioAccess,
        request = { launcher.launch(permissions) }
    )
}
