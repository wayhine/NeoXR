package com.neoxr.player

import kotlin.math.abs
import kotlin.math.roundToLong

/** One preview session: lock the initial axis, calculate from touch-down, and commit once. */
internal class SwipeSeekGesture(val start: Long, val duration: Long, private val width: Int) {
    private var horizontal: Boolean? = null
    val vertical: Boolean get() = horizontal == false
    private var finished = false
    var target: Long? = null
        private set

    fun move(deltaX: Float, deltaY: Float): Long? {
        if (finished || width <= 0 || !deltaX.isFinite() || !deltaY.isFinite()) return null
        if (horizontal == null) horizontal = abs(deltaX) >= abs(deltaY)
        if (horizontal != true || duration <= 0) return null
        val span = minOf(duration, 120_000L)
        target = (start + (deltaX.toDouble() / width * span).roundToLong()).coerceIn(0, duration)
        return target
    }

    fun finish(cancelled: Boolean): Long? {
        if (finished) return null
        finished = true
        return if (cancelled) null else target
    }
}

/** Values are always relative to touch-down, with upward motion increasing them. */
internal class VerticalPlaybackSwipe(
    val volume: Boolean,
    private val start: Float,
    private val min: Float,
    private val max: Float,
    private val height: Int
) {
    fun move(deltaY: Float): Float? {
        if (height <= 0 || !deltaY.isFinite() || !start.isFinite() || !min.isFinite() ||
            !max.isFinite() || max <= min) return null
        return (start - deltaY / height * (max - min)).coerceIn(min, max)
    }
}

internal object PlaybackGestureOptions {
    val holdSpeeds = listOf(0f, 1.5f, 2f, 3f, 4f, 5f, 6f)
    fun savedHoldSpeed(value: Float) = value.takeIf { it in holdSpeeds } ?: 2f
    fun speedLabel(value: Float): String = when {
        value == 0f -> "关闭"
        value == value.toInt().toFloat() -> "${value.toInt()}×"
        else -> "$value×"
    }
}
