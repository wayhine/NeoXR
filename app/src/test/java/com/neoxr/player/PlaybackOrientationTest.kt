package com.neoxr.player

import android.content.pm.ActivityInfo
import org.junit.Assert.*
import org.junit.Test

class PlaybackOrientationTest {
    @Test fun newPhonePlaybackIsLandscapeAndControllerStartsPortrait() {
        assertEquals("landscape", PlaybackOrientation.saved(null, false))
        assertEquals("portrait", PlaybackOrientation.saved(null, true))
    }

    @Test fun fixedChoicesSurviveRestartAndButtonTogglesAlternate() {
        for (start in listOf("portrait", "landscape")) {
            assertEquals(start, PlaybackOrientation.saved(start, false))
            assertEquals(start, PlaybackOrientation.saved(start, true))
            var mode = start
            repeat(20) { index ->
                mode = PlaybackOrientation.next(mode)
                assertEquals(if (index % 2 == 0) (if (start == "portrait") "landscape" else "portrait") else start, mode)
            }
        }
    }

    @Test fun legacyAutomaticAndLockedPreferencesMigrateToFixedDirections() {
        assertEquals("landscape", PlaybackOrientation.saved("auto", false))
        assertEquals("portrait", PlaybackOrientation.saved("auto", true))
        assertEquals("portrait", PlaybackOrientation.saved("locked", false, ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT))
        assertEquals("landscape", PlaybackOrientation.saved("locked", false, ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE))
        assertEquals("landscape", PlaybackOrientation.saved("locked", false, -99))
        assertEquals("landscape", PlaybackOrientation.saved("invalid", false))
    }

    @Test fun eachTapRequestsAFixedDirectionWithoutAnAutomaticRotationMode() {
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, PlaybackOrientation.requested(PlaybackOrientation.next("landscape")))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, PlaybackOrientation.requested(PlaybackOrientation.next("portrait")))
    }

    @Test fun enabledSystemRotationFollowsTheDeviceRegardlessOfSavedManualDirection() {
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_FULL_USER, PlaybackOrientation.requested("portrait", true))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_FULL_USER, PlaybackOrientation.requested("landscape", true))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, PlaybackOrientation.requested("portrait", false))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, PlaybackOrientation.requested("landscape", false))
    }
}
