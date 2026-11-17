package com.neoxr.player

import android.content.Context
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ScaleGestureDetector

/** Claims a pinch through the final finger-up, keeping its tail out of single-finger gestures. */
internal class PhonePinchZoom(
    context: Context,
    private val enabled: () -> Boolean,
    private val currentScale: () -> Float,
    private val onScale: (Float) -> Unit,
    private val onClaim: (MotionEvent) -> Unit
) {
    private var claimed = false
    private var scale = 1f
    private val detector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                if (!enabled()) return false
                scale = currentScale().takeIf { it.isFinite() && it > 0f }?.coerceIn(0.5f, 3f) ?: 1f
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (!enabled()) return false
                val factor = detector.scaleFactor
                if (factor.isFinite() && factor > 0f) {
                    scale = (scale * factor).coerceIn(0.5f, 3f)
                    this@PhonePinchZoom.onScale(scale)
                }
                return true
            }
        }).apply {
            isQuickScaleEnabled = false
            isStylusScaleEnabled = false
        }

    fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) cancel()
        if (enabled()) {
            if (event.pointerCount > 1 && !claimed && event.actionMasked != MotionEvent.ACTION_CANCEL) {
                claimed = true
                onClaim(event)
            }
            detector.onTouchEvent(event)
        } else {
            cancelDetector()
        }
        val handled = claimed
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            claimed = false
        }
        return handled
    }

    private fun cancelDetector() {
        val now = SystemClock.uptimeMillis()
        MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0).also {
            detector.onTouchEvent(it)
            it.recycle()
        }
    }

    fun cancel() {
        cancelDetector()
        claimed = false
    }
}
