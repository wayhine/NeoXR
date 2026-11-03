package com.neoxr.player

import org.junit.Assert.*
import org.junit.Test

class RotationFollowStateTest {
    @Test fun manualButtonHoldsWhilePhoneStaysInItsOriginalDirection() {
        val state = RotationFollowState()
        state.onOrientation(0, 0); state.onOrientation(0, 300)
        state.holdManual()
        assertFalse(state.onOrientation(8, 700))
        assertFalse(state.onOrientation(359, 1000))
        assertTrue(state.manualOverride)
    }

    @Test fun movingToAnotherStableDirectionResumesSystemRotationOnce() {
        val state = RotationFollowState()
        state.onOrientation(0, 0); state.onOrientation(0, 300); state.holdManual()
        assertFalse(state.onOrientation(90, 500))
        assertFalse(state.onOrientation(91, 799))
        assertTrue(state.onOrientation(92, 800))
        assertFalse(state.manualOverride)
        assertFalse(state.onOrientation(90, 1100))
    }

    @Test fun flatUnknownAndDiagonalMotionCannotReleaseAManualChoice() {
        val state = RotationFollowState()
        state.onOrientation(0, 0); state.onOrientation(0, 300); state.holdManual()
        assertFalse(state.onOrientation(-1, 1000))
        assertFalse(state.onOrientation(50, 1300))
        assertFalse(state.onOrientation(90, 1400))
        assertFalse(state.onOrientation(50, 1700))
        assertFalse(state.onOrientation(90, 1800))
        assertTrue(state.manualOverride)
        assertTrue(state.onOrientation(90, 2100))
    }

    @Test fun aButtonBeforeTheFirstSensorReadingWaitsForARealSubsequentTurn() {
        val state = RotationFollowState(); state.holdManual()
        state.onOrientation(270, 0)
        assertFalse(state.onOrientation(270, 300))
        state.onOrientation(180, 600)
        assertTrue(state.onOrientation(180, 900))
    }

    @Test fun resettingForSystemSettingOrLifecycleChangeClearsOverrides() {
        val state = RotationFollowState(); state.holdManual(); state.reset()
        assertFalse(state.manualOverride)
        state.onOrientation(0, 0)
        assertFalse(state.onOrientation(0, 300))
    }
}
