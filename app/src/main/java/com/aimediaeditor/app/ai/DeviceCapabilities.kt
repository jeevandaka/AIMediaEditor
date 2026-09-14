package com.aimediaeditor.app.ai

import android.app.ActivityManager
import android.content.Context

/**
 * A device-specific read of total RAM, via `ActivityManager.MemoryInfo` -- a
 * confirmed, long-standing platform API (API 16+, no permission needed), not a new
 * dependency or a guess. Used to make [com.aimediaeditor.app.ui.settings.ModelDownloadBanner]/
 * [com.aimediaeditor.app.ui.settings.ModelDownloadDialog]'s recommendation read as
 * specific to THIS device rather than generic copy ("Recommended for your device (6.2
 * GB RAM): ...").
 *
 * Deliberately does NOT select between multiple model variants -- this app only has
 * ONE model wired up with a confirmed download URL ([LocalLlmModelManager]'s own doc
 * comment explains why: this sandbox has no network path to huggingface.co to verify
 * a second, smaller variant's exact filename, and shipping a guessed URL risks a 404
 * for exactly the low-RAM users a smaller-variant feature would be meant to help). So
 * [isLikelySuitable] is a soft, honest signal ("this may run slowly on your device"),
 * not a gate that blocks the only download this app actually offers.
 */
object DeviceCapabilities {

    // Below this, the on-device model (~500MB+ resident once loaded, per the
    // litert-community benchmark figures LocalLlmModelManager's doc comment cites) is
    // a large fraction of the device's entire RAM -- likely to still run, but slowly,
    // or to get killed by the OS under memory pressure while another app is open.
    // Not a hard science, a rough "flag it honestly" threshold.
    private const val LOW_RAM_THRESHOLD_GB = 3.0

    fun totalRamGb(context: Context): Double {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return 0.0
        val info = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(info)
        return info.totalMem / (1024.0 * 1024.0 * 1024.0)
    }

    fun isLikelySuitable(context: Context): Boolean = isLikelySuitable(totalRamGb(context))

    /** Overload for callers that already read [totalRamGb] once and want to reuse it
     *  (e.g. to show the same number in a message) rather than reading it twice. */
    fun isLikelySuitable(ramGb: Double): Boolean = ramGb >= LOW_RAM_THRESHOLD_GB
}
