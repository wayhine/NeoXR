package com.neoxr.player

import org.junit.Assert.*
import org.junit.Test

class TemporaryPlaybackSpeedTest {
    @Test fun holdAppliesDoubleSpeedAndReleaseRestoresIt() {
        var speed = 1f
        val control = TemporaryPlaybackSpeed({ speed }, { speed = it })
        assertTrue(control.begin(playing = true))
        assertEquals(2f, speed)
        assertTrue(control.active)
        control.end()
        assertEquals(1f, speed)
        assertFalse(control.active)
    }

    @Test fun holdingPausedVideoDoesNotChangeSpeed() {
        val writes = mutableListOf<Float>()
        val control = TemporaryPlaybackSpeed({ 1f }, { writes.add(it) })
        assertFalse(control.begin(playing = false))
        assertFalse(control.active)
        assertTrue(writes.isEmpty())
    }

    @Test fun overlappingHoldsDoNotReplaceTheOriginalSpeed() {
        var speed = 1.5f
        val control = TemporaryPlaybackSpeed({ speed }, { speed = it })
        assertTrue(control.begin(true))
        assertFalse(control.begin(true))
        control.end()
        assertEquals(1.5f, speed)
    }

    @Test fun cancellationAndRepeatedCleanupRestoreOnce() {
        var speed = 1.25f
        val writes = mutableListOf<Float>()
        val control = TemporaryPlaybackSpeed({ speed }, { speed = it; writes.add(it) })
        control.end()
        control.begin(true)
        control.end() // ACTION_CANCEL, focus loss or lifecycle cleanup.
        control.end()
        assertEquals(listOf(2f, 1.25f), writes)
    }

    @Test fun aNewHoldCapturesTheNewNormalSpeed() {
        var speed = 1f
        val control = TemporaryPlaybackSpeed({ speed }, { speed = it })
        control.begin(true); control.end()
        speed = 1.75f
        control.begin(true); control.end()
        assertEquals(1.75f, speed)
    }

    @Test fun rejectedSpeedChangeDoesNotLeaveAHoldActive() {
        val control = TemporaryPlaybackSpeed({ 1f }, { throw IllegalStateException("Unavailable") })
        try { control.begin(true); fail("Expected rejection") } catch (_: IllegalStateException) {}
        assertFalse(control.active)
        control.end()
    }

    @Test fun transportSeeksByRequestedAmounts() {
        assertEquals(20_000L, relativeSeekTarget(30_000, 120_000, -10_000))
        assertEquals(45_000L, relativeSeekTarget(30_000, 120_000, 15_000))
    }

    @Test fun configuredSixTimesSpeedRestoresTheOriginalSpeed() {
        var speed = 1.25f
        val control = TemporaryPlaybackSpeed({ speed }, { speed = it })
        assertTrue(control.begin(true, 6f))
        assertEquals(6f, speed)
        control.end()
        assertEquals(1.25f, speed)
    }

    @Test fun disabledOrInvalidHoldSpeedDoesNotStartAHold() {
        val writes = mutableListOf<Float>()
        val control = TemporaryPlaybackSpeed({ 1f }, { writes.add(it) })
        assertFalse(control.begin(true, 0f))
        assertFalse(control.begin(true, Float.NaN))
        assertFalse(control.begin(true, Float.POSITIVE_INFINITY))
        assertTrue(writes.isEmpty())
    }

    @Test fun holdSpeedCannotExceedSixTimes() {
        var speed = 1f
        val control = TemporaryPlaybackSpeed({ speed }, { speed = it })
        control.begin(true, 10f)
        assertEquals(6f, speed)
        control.end()
        assertEquals(1f, speed)
    }

    @Test fun seeksClampToStartAndBeforeKnownEnd() {
        assertEquals(0L, relativeSeekTarget(2_000, 120_000, -10_000))
        assertEquals(119_000L, relativeSeekTarget(118_000, 120_000, 15_000))
    }

    @Test fun unknownDurationDoesNotPreventForwardSeeking() {
        assertEquals(45_000L, relativeSeekTarget(30_000, -9223372036854775807L, 15_000))
    }

    @Test fun shortVideosNeverProduceANegativeSeek() {
        assertEquals(0L, relativeSeekTarget(200, 500, 15_000))
    }
}
