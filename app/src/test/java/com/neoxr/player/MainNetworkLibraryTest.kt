package com.neoxr.player

import android.app.Presentation
import android.content.Context
import android.content.pm.ActivityInfo
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

    private fun connectGlasses() {
        val displayId = ShadowDisplayManager.addDisplay("w3840dp-h1080dp-land-mdpi")
        val displays = RuntimeEnvironment.getApplication().getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        shadowOf(displays.getDisplay(displayId)).setFlags(Display.FLAG_PRESENTATION)
    }

    @Test fun phoneSelectionHandsOneVideoToPlayerAndMirrorsAgainOnReturn() {
        connectGlasses()
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        val videos = android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        shadowOf(app.contentResolver).setCursor(videos, org.robolectric.fakes.RoboCursor().apply {
            setResults(arrayOf(arrayOf<Any>(42L, "Example.mp4", 60000L, "Movies")))
        })
        val home = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            shadowOf(Looper.getMainLooper()).idle()
            val window = presentation()
            val phone = home.get()
            phone.findViewById<View>(R.id.btnFile).performClick()
            val files = phone.findViewById<ListView>(R.id.fileList)
            fun tap(index: Int) = files.performItemClick(files.adapter.getView(index, null, files), index, index.toLong())
            tap(0)
            tap(1)
            val launched = shadowOf(phone).nextStartedActivity
            assertEquals(PlayerActivity::class.java.name, launched.component?.className)
            assertEquals(phone.packageName, launched.component?.packageName)
            assertEquals("$videos/42", launched.dataString)
            assertNull(shadowOf(phone).nextStartedActivity)
            home.pause().stop()
            assertFalse(window.isShowing)
            home.restart().start().resume()
            shadowOf(Looper.getMainLooper()).idle()
            assertSame(window, presentation())
            assertTrue(phone.findViewById<View>(R.id.fileOverlay).isShown)
            assertEquals("Example.mp4", (files.adapter.getView(1, null, files) as TextView).text.toString().substringBefore("   ·"))
        } finally { home.pause().stop().destroy() }
    }

    @Test fun networkLibraryUsesTheHomeWindowAndRestoresAfterRecreation() {
        RuntimeEnvironment.getApplication().getSharedPreferences("neoxr", Context.MODE_PRIVATE).edit()
            .putString("sites", "[\"smb://127.0.0.1\"]")
            .putBoolean("homeLibraryMode", false).commit()
        connectGlasses()
        val home = Robolectric.buildActivity(MainActivity::class.java).create().start().resume().visible()
        val state = Bundle()
        try {
            shadowOf(Looper.getMainLooper()).idle()
            val window = presentation()
            val phone = home.get()
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, phone.requestedOrientation)
            assertNull(window.findViewById<ListView>(R.id.sites))
            val mirror = window.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0) as LibraryMirrorView
            assertTrue(mirror.stereo)
            val sites = phone.findViewById<ListView>(R.id.sites)
            val source = generateSequence(sites as View) { it.parent as? View }.filterIsInstance<SbsFrameLayout>().last()
            assertFalse(source.sbs)
            source.onLongPressToggle!!.invoke()
            assertFalse(mirror.stereo)
            assertFalse(source.sbs)
            sites.performItemClick(sites.adapter.getView(0, null, sites), 0, 0)
            shadowOf(Looper.getMainLooper()).idle()
            assertSame(window, presentation())
            val title = phone.findViewById<TextView>(R.id.networkTitle)
            assertNotNull(title)
            val network = generateSequence(title as View) { it.parent as? View }
                .filterIsInstance<SbsFrameLayout>().first()
            val homeSearch = phone.findViewById<EditText>(R.id.librarySearch)
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
            val phone = restored.get()
            val title = phone.findViewById<TextView>(R.id.networkTitle)
            assertNotNull(title)
            val network = generateSequence(title as View) { it.parent as? View }
                .filterIsInstance<SbsFrameLayout>().first()
            assertEquals("missing", network.findViewById<EditText>(R.id.librarySearch).text.toString())
            restored.get().onBackPressedDispatcher.onBackPressed()
            assertEquals("", network.findViewById<EditText>(R.id.librarySearch).text.toString())
            restored.get().onBackPressedDispatcher.onBackPressed()
            shadowOf(Looper.getMainLooper()).idle()
            assertSame(window, presentation())
            assertNull(phone.findViewById<TextView>(R.id.networkTitle))
            assertTrue(phone.findViewById<ListView>(R.id.sites).isShown)
            assertNull(shadowOf(restored.get()).nextStartedActivity)
        } finally { restored.pause().stop().destroy() }
    }
}
