package com.neoxr.player

/** A hold changes speed only: it never starts playback or overwrites the original speed twice. */
internal class TemporaryPlaybackSpeed(
    private val readSpeed: () -> Float,
    private val writeSpeed: (Float) -> Unit
) {
    private var original: Float? = null
    val active: Boolean get() = original != null

    fun begin(playing: Boolean, speed: Float = 2f): Boolean {
        if (!playing || active || !speed.isFinite() || speed <= 0) return false
        original = readSpeed()
        try { writeSpeed(speed.coerceIn(1f, 6f)) } catch (error: Exception) { original = null; throw error }
        return true
    }

    fun end() {
        val restore = original ?: return
        original = null
        writeSpeed(restore)
    }
}

internal fun relativeSeekTarget(position: Long, duration: Long, delta: Long): Long {
    val target = (position + delta).coerceAtLeast(0)
    return if (duration > 0) target.coerceAtMost((duration - 1000).coerceAtLeast(0)) else target
}
