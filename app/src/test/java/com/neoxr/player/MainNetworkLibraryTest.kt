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
import android.widget.GridView
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
    private fun layout(view: View, w: Int, h: Int) {
        view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, w, h)
    }

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
            files.viewTreeObserver.dispatchOnPreDraw()
            val eyeFiles = window.findViewById<ListView>(R.id.fileList)!!
            assertNotSame(files, eyeFiles)
            assertSame(files.adapter, eyeFiles.adapter)
            assertTrue(window.findViewById<View>(R.id.fileOverlay)!!.isShown)
            tap(1)
            val launched = shadowOf(phone).nextStartedActivity
            assertEquals(PlayerActivity::class.java.name, launched.component?.className)
            assertEquals("The starting window must retain the portrait library orientation",
                ActivityInfo.SCREEN_ORIENTATION_BEHIND, phone.packageManager.getActivityInfo(launched.component!!, 0).screenOrientation)
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
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, phone.requestedOrientation)
            val mirror = window.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0) as LandscapeLibraryView
            assertTrue(mirror.stereo)
            val sites = phone.findViewById<ListView>(R.id.sites)
            val source = generateSequence(sites as View) { it.parent as? View }.filterIsInstance<SbsFrameLayout>().last()
            layout(source, 420, 900)
            source.viewTreeObserver.dispatchOnPreDraw()
            layout(mirror, 3840, 1080)
            val eyePage = mirror.getChildAt(0)
            assertEquals(16f/9, eyePage.width.toFloat() / eyePage.height, 0.01f)
            assertTrue(source.width < source.height)
            assertNotSame(sites, mirror.findViewById<ListView>(R.id.sites))
            assertSame(sites.adapter, mirror.findViewById<ListView>(R.id.sites).adapter)
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
            source.viewTreeObserver.dispatchOnPreDraw()
            assertEquals("missing", mirror.findViewById<EditText>(R.id.librarySearch).text.toString())
            assertSame(network.findViewById<GridView>(R.id.libraryGrid).adapter,
                mirror.findViewById<GridView>(R.id.libraryGrid).adapter)
            network.findViewById<View>(R.id.librarySort).performClick()
            source.viewTreeObserver.dispatchOnPreDraw()
            assertTrue(mirror.findViewById<View>(R.id.librarySortPicker).isShown)
            phone.onBackPressedDispatcher.onBackPressed()
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
            network.findViewById<View>(R.id.librarySort).performClick()
            assertTrue(network.findViewById<View>(R.id.librarySortPicker).isShown)
            phone.onBackPressedDispatcher.onBackPressed()
            assertFalse(network.findViewById<View>(R.id.librarySortPicker).isShown)
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

    @Test fun portraitGridScrollsTheWiderGlassesGridToTheSameMedia() {
        connectGlasses()
        val home = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            shadowOf(Looper.getMainLooper()).idle()
            val phone = home.get()
            phone.findViewById<View>(R.id.btnLibrary).performClick()
            val grid = phone.findViewById<GridView>(R.id.libraryGrid)
            val root = generateSequence(grid as View) { it.parent as? View }.filterIsInstance<SbsFrameLayout>().first()
            val eye = presentation().findViewById<android.view.ViewGroup>(android.R.id.content)!!.getChildAt(0) as LandscapeLibraryView
            grid.adapter = object : android.widget.BaseAdapter() {
                override fun getCount() = 200
                override fun getItem(i: Int) = i
                override fun getItemId(i: Int) = i.toLong()
                override fun getView(i: Int, recycled: View?, parent: android.view.ViewGroup) =
                    ((recycled as? TextView) ?: TextView(phone).apply { height = 100 }).apply { text = "Movie " + i }
            }
            layout(root, 420, 900)
            root.viewTreeObserver.dispatchOnPreDraw()
            layout(eye, 3840, 1080)
            val eyeGrid = eye.findViewById<GridView>(R.id.libraryGrid)
            assertTrue(eyeGrid.numColumns > grid.numColumns)
            root.findViewById<LibraryScrollFrame>(R.id.libraryScroller).scrollPage(1800)
            root.viewTreeObserver.dispatchOnPreDraw()
            layout(eye, 3840, 1080)
            val first = grid.firstVisiblePosition
            assertTrue(first > 0)
            assertTrue("Phone anchor $first outside glasses range " +
                "${eyeGrid.firstVisiblePosition}..${eyeGrid.lastVisiblePosition}",
                first in eyeGrid.firstVisiblePosition..eyeGrid.lastVisiblePosition)
            fun eyePosition(): Int = eyeGrid.firstVisiblePosition / eyeGrid.numColumns *
                (eyeGrid.getChildAt(0).height + eyeGrid.verticalSpacing) - eyeGrid.getChildAt(0).top
            fun step(px: Int) {
                root.findViewById<LibraryScrollFrame>(R.id.libraryScroller).scrollPage(px)
                root.viewTreeObserver.dispatchOnPreDraw()
                layout(eye, 3840, 1080)
            }
            for (direction in listOf(1, -1)) {
                val start = eyePosition()
                repeat(90) {
                    val before = eyePosition()
                    step(11 * direction)
                    val advance = (eyePosition() - before) * direction
                    assertTrue("Eye moved backwards or jumped a row: $advance", advance in 0..15)
                }
                assertTrue((eyePosition() - start) * direction > 100)
            }
            step(50000)
            assertEquals(grid.count - 1, eyeGrid.lastVisiblePosition)
            val tailBottom = eyeGrid.getChildAt(eyeGrid.childCount - 1).bottom
            assertTrue("Last card bottom $tailBottom outside viewport ${eyeGrid.height} (padding ${eyeGrid.paddingBottom})",
                tailBottom <= eyeGrid.height && tailBottom >= eyeGrid.height - eyeGrid.paddingBottom - 1)
            step(-50000)
            assertFalse(eyeGrid.canScrollList(-1))
        } finally { home.pause().stop().destroy() }
    }

    @Test fun webPreviewKeepsOneLandscapeBrowserInsideThePortraitPhone() {
        connectGlasses()
        val controller = Robolectric.buildActivity(android.app.Activity::class.java).create().start()
        try {
            val activity = controller.get()
            val browser = SbsFrameLayout(activity)
            LibraryMirror.attach(activity, browser)
            controller.resume().visible()
            shadowOf(Looper.getMainLooper()).idle()
            val phone = browser.parent as android.view.ViewGroup
            layout(phone, 420, 900)
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, activity.requestedOrientation)
            assertEquals(1, phone.childCount)
            assertEquals(420, browser.width)
            assertEquals(16f/9, browser.width.toFloat()/browser.height, 0.01f)
            assertTrue(presentation().findViewById<android.view.ViewGroup>(android.R.id.content)!!.getChildAt(0) is LibraryMirrorView)
        } finally { controller.pause().stop().destroy() }
    }
}
