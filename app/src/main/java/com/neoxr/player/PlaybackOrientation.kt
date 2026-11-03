package com.neoxr.player

import android.content.pm.ActivityInfo

internal object PlaybackOrientation {
    /** Retain existing fixed choices and migrate the removed automatic/lock menu. */
    fun saved(value: String?, remote: Boolean, legacyLock: Int = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE): String = when (value) {
        "portrait", "landscape" -> value
        "locked" -> if (legacyLock == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT ||
            legacyLock == ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT) "portrait" else "landscape"
        else -> if (remote) "portrait" else "landscape"
    }

    fun next(mode: String) = if (mode == "portrait") "landscape" else "portrait"

    fun requested(mode: String, autoRotate: Boolean = false): Int = if (autoRotate)
        ActivityInfo.SCREEN_ORIENTATION_FULL_USER else when (mode) {
        "portrait" -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        else -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
    }
}
