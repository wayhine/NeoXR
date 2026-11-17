package com.neoxr.player

import kotlin.math.asin
import kotlin.math.atan2

/** RayNeo SDK quaternion order is x, y, z, w in a right-handed frame. */
object RayNeoPose {
    fun angles(quaternion: FloatArray): Pair<Float, Float>? {
        if (quaternion.size != 4 || quaternion.any { !it.isFinite() }) return null
        val x = quaternion[0].toDouble()
        val y = quaternion[1].toDouble()
        val z = quaternion[2].toDouble()
        val w = quaternion[3].toDouble()
        val norm = x * x + y * y + z * z + w * w
        if (norm < 0.5 || norm > 1.5) return null
        // Extract the optical forward direction. Roll must not steer yaw or pitch.
        val yaw = atan2(2 * (x * z + w * y), norm - 2 * (x * x + y * y))
        val pitch = asin((2 * (w * x - y * z) / norm).coerceIn(-1.0, 1.0))
        return Math.toDegrees(yaw).toFloat() to Math.toDegrees(pitch).toFloat()
    }
}
