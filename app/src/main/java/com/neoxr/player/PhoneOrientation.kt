package com.neoxr.player

import kotlin.math.asin
import kotlin.math.atan2

/** Stable phone heading for both handheld viewing and a flat phone controller. */
internal class PhoneOrientation {
    private var useBack = true
    private var previousYaw: Float? = null
    private var headingOffset = 0f

    fun update(rotation: FloatArray): Pair<Float, Float>? {
        if (rotation.size != 9 || rotation.any { !it.isFinite() }) return null
        val backLength = rotation[2] * rotation[2] + rotation[5] * rotation[5]
        val topLength = rotation[1] * rotation[1] + rotation[4] * rotation[4]
        val wasBack = useBack
        // When the camera points vertically, its horizontal projection has no
        // stable heading. The phone's top edge supplies the horizontal reference.
        // Hysteresis prevents repeatedly changing axes near the boundary.
        if (useBack && backLength < 0.25f) useBack = false
        else if (!useBack && topLength < 0.25f) useBack = true
        val east = if (useBack) -rotation[2] else rotation[1]
        val north = if (useBack) -rotation[5] else rotation[4]
        val heading = -Math.toDegrees(atan2(east.toDouble(), north.toDouble())).toFloat()
        if (wasBack != useBack) previousYaw?.let { headingOffset = it - heading }
        val yaw = ((heading + headingOffset + 540f) % 360f) - 180f
        previousYaw = yaw
        val pitch = Math.toDegrees(asin((-rotation[8]).coerceIn(-1f, 1f).toDouble())).toFloat()
        return yaw to pitch
    }
}
