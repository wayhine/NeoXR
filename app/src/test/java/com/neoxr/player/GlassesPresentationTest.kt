package com.neoxr.player

import android.app.Activity
import android.app.Presentation
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Looper
import android.view.Display
import android.view.WindowManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowDisplayManager
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class GlassesPresentationTest {
    private class Window(activity: Activity, display: Display, var failures: Int = 0) : Presentation(activity, display) {
        var attempts = 0
        override fun show() {
            attempts++
            if (failures-- > 0) throw WindowManager.InvalidDisplayException("display not ready")
            super.show()
        }
    }

    private fun display(activity: Activity): Display {
        val id = ShadowDisplayManager.addDisplay("w1920dp-h1080dp-land-mdpi")
        return (activity.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).getDisplay(id)
    }

    @Test fun preconnectedGlassesShowOnlyAfterResumeAndReleaseTheMenuDuringPlayback() {
        val menu = Robolectric.buildActivity(Activity::class.java).create()
        val display = display(menu.get())
        val menuWindow = Window(menu.get(), display)
        var lost = 0
        val menuOutput = GlassesPresentation(menu.get(), menuWindow, { lost++ }, { throw AssertionError(it) })
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, menuWindow.attempts)
        menu.start().resume().visible()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(menuWindow.isShowing)

        menu.pause()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(menuWindow.isShowing)
        assertEquals(0, lost)

        val player = Robolectric.buildActivity(Activity::class.java).create()
        val videoWindow = Window(player.get(), display)
        val videoOutput = GlassesPresentation(player.get(), videoWindow, { lost++ }, { throw AssertionError(it) })
        player.start().resume().visible()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(videoWindow.isShowing)
        assertFalse(menuWindow.isShowing)

        player.pause().stop().destroy()
        menu.resume()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(videoWindow.isShowing)
        assertTrue(menuWindow.isShowing)
        assertEquals(0, lost)
        videoOutput.close()
        menuOutput.close()
        menu.pause().stop().destroy()
    }

    @Test fun aTransientWindowRejectionRetriesWithoutRepluggingTheGlasses() {
        val activity = Robolectric.buildActivity(Activity::class.java).create()
        val window = Window(activity.get(), display(activity.get()), failures = 2)
        var failed = 0
        val output = GlassesPresentation(activity.get(), window, {}, { failed++ })
        activity.start().resume().visible()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertEquals(3, window.attempts)
        assertTrue(window.isShowing)
        assertEquals(0, failed)
        output.close()
        activity.pause().stop().destroy()
    }

    @Test fun aPersistentRejectionFallsBackOnceWithoutAnInfiniteRetryLoop() {
        val activity = Robolectric.buildActivity(Activity::class.java).create()
        val window = Window(activity.get(), display(activity.get()), failures = 10)
        var failed = 0
        val output = GlassesPresentation(activity.get(), window, {}, { failed++ })
        activity.start().resume().visible()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))
        assertEquals(3, window.attempts)
        assertEquals(1, failed)
        assertFalse(window.isShowing)
        output.close()
        activity.pause().stop().destroy()
    }

    @Test fun leavingDuringARetryCancelsItAndReturningCanShowTheWindow() {
        val activity = Robolectric.buildActivity(Activity::class.java).create()
        val window = Window(activity.get(), display(activity.get()), failures = 1)
        val output = GlassesPresentation(activity.get(), window, {}, { throw AssertionError(it) })
        activity.start().resume().visible()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, window.attempts)
        activity.pause()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertEquals(1, window.attempts)
        activity.resume()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(window.isShowing)
        output.close()
        activity.pause().stop().destroy()
    }

    @Test fun unexpectedDismissalStillTriggersDisplayRecovery() {
        val activity = Robolectric.buildActivity(Activity::class.java).create()
        val window = Window(activity.get(), display(activity.get()))
        var lost = 0
        val output = GlassesPresentation(activity.get(), window, { lost++ }, { throw AssertionError(it) })
        activity.start().resume().visible()
        shadowOf(Looper.getMainLooper()).idle()
        window.dismiss()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, lost)
        output.close()
        activity.pause().stop().destroy()
    }
}
