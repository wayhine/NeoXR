package com.neoxr.player

import org.junit.Assert.*
import org.junit.Test

class SwipeSeekGestureTest {
    @Test fun horizontalDragUsesTouchDownPositionWithoutAccumulatingUpdates() {
        val gesture = SwipeSeekGesture(300_000, 600_000, 1000)
        assertEquals(360_000L, gesture.move(500f, 10f))
        assertEquals(330_000L, gesture.move(250f, 10f))
        assertEquals(240_000L, gesture.move(-500f, 10f))
    }

    @Test fun shortVideosUseTheirDurationAsTheSwipeRange() {
        assertEquals(20_000L, SwipeSeekGesture(5_000, 30_000, 1000).move(500f, 0f))
    }

    @Test fun previewStaysInsideVideoBounds() {
        val gesture = SwipeSeekGesture(5_000, 30_000, 1000)
        assertEquals(0L, gesture.move(-1000f, 0f))
        assertEquals(30_000L, gesture.move(2000f, 0f))
    }

    @Test fun verticalDragDoesNotBecomeASeekLater() {
        val gesture = SwipeSeekGesture(5_000, 30_000, 1000)
        assertNull(gesture.move(2f, 20f))
        assertTrue(gesture.vertical)
        assertNull(gesture.move(1000f, 20f))
        assertTrue(gesture.vertical)
        assertNull(gesture.finish(cancelled = false))
    }

    @Test fun horizontalDragNeverSwitchesToVolumeOrBrightness() {
        val gesture = SwipeSeekGesture(5_000, 30_000, 1000)
        assertNotNull(gesture.move(20f, 2f))
        assertNotNull(gesture.move(20f, 1000f))
        assertFalse(gesture.vertical)
    }

    @Test fun verticalControlsRemainAvailableWithoutASeekableTimeline() {
        val gesture = SwipeSeekGesture(0, -1, 1000)
        assertNull(gesture.move(2f, 20f))
        assertTrue(gesture.vertical)
        assertNull(gesture.finish(cancelled = false))
    }

    @Test fun volumeUsesTotalDistanceFromTouchDownInBothDirections() {
        val gesture = VerticalPlaybackSwipe(true, 5f, 0f, 15f, 1000)
        assertEquals(8f, gesture.move(-200f)!!, 0.001f)
        assertEquals(6.5f, gesture.move(-100f)!!, 0.001f)
        assertEquals(2f, gesture.move(200f)!!, 0.001f)
    }

    @Test fun volumeClampsAndCanReverseAfterHittingEitherLimit() {
        val gesture = VerticalPlaybackSwipe(true, 5f, 0f, 15f, 1000)
        assertEquals(15f, gesture.move(-2000f)!!, 0.001f)
        assertEquals(0f, gesture.move(2000f)!!, 0.001f)
        assertEquals(5f, gesture.move(0f)!!, 0.001f)
    }

    @Test fun brightnessHasASafeMinimumAndFullBrightnessMaximum() {
        val gesture = VerticalPlaybackSwipe(false, 0.5f, 0.02f, 1f, 1000)
        assertEquals(0.745f, gesture.move(-250f)!!, 0.001f)
        assertEquals(1f, gesture.move(-2000f)!!, 0.001f)
        assertEquals(0.02f, gesture.move(2000f)!!, 0.001f)
    }

    @Test fun invalidVerticalGeometryAndFixedRangesDoNotChangeAValue() {
        assertNull(VerticalPlaybackSwipe(true, 5f, 0f, 15f, 0).move(-200f))
        assertNull(VerticalPlaybackSwipe(true, 5f, 5f, 5f, 1000).move(-200f))
        assertNull(VerticalPlaybackSwipe(true, 5f, 0f, 15f, 1000).move(Float.NaN))
        assertNull(VerticalPlaybackSwipe(true, 5f, 0f, 15f, 1000).move(Float.POSITIVE_INFINITY))
    }

    @Test fun cancellingPreviewNeverCommitsASeek() {
        val gesture = SwipeSeekGesture(5_000, 30_000, 1000)
        assertNotNull(gesture.move(200f, 0f))
        assertNull(gesture.finish(cancelled = true))
        assertNull(gesture.finish(cancelled = false))
    }

    @Test fun releasingCommitsOnlyTheLastPreviewOnce() {
        val gesture = SwipeSeekGesture(5_000, 30_000, 1000)
        gesture.move(100f, 0f)
        gesture.move(300f, 0f)
        assertEquals(14_000L, gesture.finish(cancelled = false))
        assertNull(gesture.finish(cancelled = false))
        assertNull(gesture.move(500f, 0f))
    }

    @Test fun missingDurationOrWidthDisablesGestureSeeking() {
        assertNull(SwipeSeekGesture(0, -1, 1000).move(100f, 0f))
        assertNull(SwipeSeekGesture(0, 30_000, 0).move(100f, 0f))
    }

    @Test fun holdSettingKeepsDisabledAndSixTimesValuesAndRejectsInvalidSavedValues() {
        assertEquals(0f, PlaybackGestureOptions.savedHoldSpeed(0f))
        assertEquals(6f, PlaybackGestureOptions.savedHoldSpeed(6f))
        assertEquals(2f, PlaybackGestureOptions.savedHoldSpeed(9f))
        assertEquals(2f, PlaybackGestureOptions.savedHoldSpeed(Float.NaN))
        assertEquals("6×", PlaybackGestureOptions.speedLabel(6f))
    }
}
