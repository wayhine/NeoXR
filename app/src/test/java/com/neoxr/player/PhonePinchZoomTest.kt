package com.neoxr.player

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "mdpi")
class PhonePinchZoomTest {
    private class Gesture {
        var enabled = true
        var scale = 1f
        var claims = 0
        var updates = 0
        var onClaimed: () -> Unit = {}
        val zoom = PhonePinchZoom(RuntimeEnvironment.getApplication(),
            enabled = { enabled }, currentScale = { scale },
            onScale = { scale = it; updates++ },
            onClaim = { claims++; onClaimed() })
        private var time = SystemClock.uptimeMillis()
        private var downTime = time

        fun event(action: Int, vararg xs: Float, firstId: Int = 0): Boolean {
            time += 20
            if (action == MotionEvent.ACTION_DOWN) downTime = time
            val properties = Array(xs.size) { index -> MotionEvent.PointerProperties().apply {
                id = firstId + index; toolType = MotionEvent.TOOL_TYPE_FINGER
            } }
            val coords = Array(xs.size) { index -> MotionEvent.PointerCoords().apply {
                x = xs[index]; y = 300f; pressure = 1f; size = 1f
            } }
            val event = MotionEvent.obtain(downTime, time, action, xs.size, properties, coords,
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            return try { zoom.onTouchEvent(event) } finally { event.recycle() }
        }

        fun begin() {
            event(MotionEvent.ACTION_DOWN, 400f)
            event(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 400f, 800f)
        }

        fun span(value: Float): Boolean = event(MotionEvent.ACTION_MOVE, 600f - value / 2, 600f + value / 2)
    }

    @Test fun spreadingMagnifiesAndPinchingReducesTheCurrentPicture() {
        val gesture = Gesture()
        gesture.begin()
        gesture.span(600f)
        gesture.span(900f)
        assertTrue("Spreading must magnify", gesture.scale > 1f)
        val magnified = gesture.scale
        gesture.span(450f)
        assertTrue("Pinching must shrink", gesture.scale < magnified)
        assertEquals(1, gesture.claims)
    }

    @Test fun scalingStopsAtTheExistingFiftyAndThreeHundredPercentLimits() {
        val gesture = Gesture().apply { scale = 2.9f }
        gesture.begin()
        gesture.span(600f)
        gesture.span(4000f)
        assertEquals(3f, gesture.scale, 0.0001f)
        gesture.span(200f)
        assertEquals(0.5f, gesture.scale, 0.0001f)
    }

    @Test fun aPinchConsumesTheRemainingFingerAndEndsOnlyWhenAllFingersAreUp() {
        val gesture = Gesture()
        gesture.begin()
        gesture.span(600f)
        gesture.span(900f)
        val updates = gesture.updates
        assertTrue(gesture.event(MotionEvent.ACTION_POINTER_UP, 150f, 1050f))
        assertTrue(gesture.event(MotionEvent.ACTION_MOVE, 1100f, firstId = 1))
        assertTrue(gesture.event(MotionEvent.ACTION_UP, 1100f, firstId = 1))
        assertEquals(updates, gesture.updates)
        assertFalse(gesture.event(MotionEvent.ACTION_DOWN, 500f))
        assertFalse(gesture.event(MotionEvent.ACTION_MOVE, 600f))
    }

    @Test fun disabledOutputModesDoNotClaimOrScaleThePicture() {
        val gesture = Gesture().apply { enabled = false }
        gesture.begin()
        assertFalse(gesture.span(600f))
        assertFalse(gesture.span(1200f))
        assertEquals(1f, gesture.scale, 0f)
        assertEquals(0, gesture.claims)
        assertEquals(0, gesture.updates)
    }

    @Test fun losingTheEligibleOutputDuringAPinchStopsScalingButConsumesItsTail() {
        val gesture = Gesture()
        gesture.begin()
        gesture.span(600f)
        gesture.span(900f)
        val before = gesture.scale
        gesture.enabled = false
        assertTrue(gesture.span(1200f))
        assertEquals(before, gesture.scale, 0f)
        assertTrue(gesture.event(MotionEvent.ACTION_CANCEL, 400f))
        gesture.enabled = true
        assertFalse(gesture.event(MotionEvent.ACTION_DOWN, 400f))
    }

    @Test fun cancellingAndStartingAgainUsesTheLatestZoomWithoutAJump() {
        val gesture = Gesture()
        gesture.begin()
        gesture.span(600f)
        gesture.span(900f)
        gesture.zoom.cancel()
        gesture.scale = 0.75f
        gesture.begin()
        gesture.span(600f)
        assertEquals(0.75f, gesture.scale, 0.0001f)
        gesture.span(800f)
        assertTrue(gesture.scale > 0.75f && gesture.scale < 1.5f)
    }

    @Test fun secondFingerCancelsSpeedAndSeekBeforeAnyScalingMovement() {
        val gesture = Gesture()
        var speed = 1.25f
        val hold = TemporaryPlaybackSpeed({ speed }, { speed = it })
        val seek = SwipeSeekGesture(30_000, 300_000, 1000)
        seek.move(400f, 0f)
        var committed = false
        gesture.onClaimed = {
            hold.end()
            committed = seek.finish(cancelled = true) != null
        }
        gesture.event(MotionEvent.ACTION_DOWN, 400f)
        assertTrue(hold.begin(true, 6f))
        assertTrue(gesture.event(MotionEvent.ACTION_POINTER_DOWN or
            (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 400f, 800f))
        assertFalse(hold.active)
        assertEquals(1.25f, speed, 0f)
        assertFalse(committed)
        assertNull(seek.finish(cancelled = false))
        assertEquals(0, gesture.updates)
    }
}
