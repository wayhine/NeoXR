package com.neoxr.player

import org.junit.Assert.*
import org.junit.Test

class DisplayRecoveryTest {
    private val full = DisplayRoute(2, 3840, 1080, 0)
    private val half = DisplayRoute(3, 1920, 1080, 0)

    @Test fun modeSwitchDoesNotTemporarilyMoveVideoOntoThePhone() {
        val state = DisplayRecovery(full)
        assertEquals(4000L, state.waitMs(null, 100))
        assertEquals(2000L, state.waitMs(null, 2100))
        assertEquals(800L, state.waitMs(half, 2300))
        assertEquals(0L, state.waitMs(half, 3100))
    }
    @Test fun aCancelledPresentationIsRebuiltEvenWhenTheDisplayReusesItsIdAndMode() {
        val state = DisplayRecovery(full)
        assertNull(state.waitMs(full, 100))
        state.lostPresentation(150)
        assertEquals(800L, state.waitMs(full, 150))
        assertEquals(0L, state.waitMs(full, 950))
    }
    @Test fun actualUnplugFallsBackButTransientEventsAndUnchangedModesDoNotRecreate() {
        val state = DisplayRecovery(full)
        state.waitMs(null, 100)
        assertNull(state.waitMs(full, 500))
        assertNull(state.waitMs(full, 600))
        assertEquals(4000L, state.waitMs(null, 1000))
        assertEquals(0L, state.waitMs(null, 5000))
        val phone = DisplayRecovery(null)
        assertNull(phone.waitMs(null, 100))
        assertEquals(800L, phone.waitMs(half, 200))
        assertEquals(0L, phone.waitMs(half, 1000))
    }
}
