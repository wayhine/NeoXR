package com.neoxr.player

import android.app.Presentation
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Looper
import android.view.Display
import android.view.View
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowDisplayManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class MainNetworkLibraryTest {
    private fun presentation(): Presentation = ShadowDialog.getShownDialogs()
        .filterIsInstance<Presentation>().last { it.isShowing }

    @Test fun networkLibraryUsesTheHomeWindowAndRestoresAfterRecreation() {
        RuntimeEnvironment.getApplication().getSharedPreferences("neoxr", Context.MODE_PRIVATE).edit()
            .putString("sites", "[\"smb://127.0.0.1\"]")
            .putBoolean("homeLibraryMode", false).commit()
        val displayId = ShadowDisplayManager.addDisplay("w3840dp-h1080dp-land-mdpi")
        val displays = RuntimeEnvironment.getApplication().getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        shadowOf(displays.getDisplay(displayId)).setFlags(Display.FLAG_PRESENTATION)
        val home = Robolectric.buildActivity(MainActivity::class.java).create().start().resume().visible()
        val state = Bundle()
        try {
            shadowOf(Looper.getMainLooper()).idle()
            val window = presentation()
            val sites = window.findViewById<ListView>(R.id.sites)
            sites.performItemClick(sites.adapter.getView(0, null, sites), 0, 0)
            shadowOf(Looper.getMainLooper()).idle()
            assertSame(window, presentation())
            val title = window.findViewById<TextView>(R.id.networkTitle)
            assertNotNull(title)
            val network = generateSequence(title as View) { it.parent as? View }
                .filterIsInstance<SbsFrameLayout>().first()
            val homeSearch = window.findViewById<EditText>(R.id.librarySearch)
            val networkSearch = network.findViewById<EditText>(R.id.librarySearch)
            assertNotSame(homeSearch, networkSearch)
            networkSearch.setText("missing")
            assertEquals("", homeSearch.text.toString())
            assertNull(shadowOf(home.get()).nextStartedActivity)
            home.saveInstanceState(state)
        } finally { home.pause().stop().destroy() }

        val restored = Robolectric.buildActivity(MainActivity::class.java).create(state).start().resume().visible()
        try {
            shadowOf(Looper.getMainLooper()).idle()
            val window = presentation()
            val title = window.findViewById<TextView>(R.id.networkTitle)
            assertNotNull(title)
            val network = generateSequence(title as View) { it.parent as? View }
                .filterIsInstance<SbsFrameLayout>().first()
            assertEquals("missing", network.findViewById<EditText>(R.id.librarySearch).text.toString())
            restored.get().onBackPressedDispatcher.onBackPressed()
            assertEquals("", network.findViewById<EditText>(R.id.librarySearch).text.toString())
            restored.get().onBackPressedDispatcher.onBackPressed()
            shadowOf(Looper.getMainLooper()).idle()
            assertSame(window, presentation())
            assertNull(window.findViewById<TextView>(R.id.networkTitle))
            assertTrue(window.findViewById<ListView>(R.id.sites).isShown)
            assertNull(shadowOf(restored.get()).nextStartedActivity)
        } finally { restored.pause().stop().destroy() }
    }
}
