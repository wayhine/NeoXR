package com.neoxr.player

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Rect
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LibraryPresentationTest {
    private class Fixture(fontScale: Float = 1f, layout: Int = R.layout.activity_network_library) : AutoCloseable {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val configuration = Configuration(activity.resources.configuration).apply { this.fontScale = fontScale }
        val context = ContextThemeWrapper(activity.createConfigurationContext(configuration), R.style.Theme_NeoXR)
        val wrap = FrameLayout(context).apply { LayoutInflater.from(context).inflate(layout, this, true) }
        val metadata = LocalVideoAdapter(activity)
        val video = LibraryVideo("content://test/clip", "旅行.mp4", "旅途/山间", 1234, 100)
        val folders = listOf(LibraryFolder("root", "本地影片", listOf(video)))
        var selected: String? = null
        var path = ""
        var played: LibraryVideo? = null
        lateinit var view: LibraryPresentation
        init {
            activity.setContentView(wrap)
            activity.getSharedPreferences("library_view_test", Context.MODE_PRIVATE).edit().clear().putBoolean("folders", true).commit()
            view = makeView()
            render()
        }
        fun makeView() = LibraryPresentation(activity, wrap, "test", metadata, {}, { render() },
            { root, directory -> selected = root; path = directory; render() }, { played = it })
        fun render() { view.render(folders, selected, path, "") }
        override fun close() { view.close(); metadata.close(); controller.pause().stop().destroy() }
    }

    @Test fun folderCardsNavigateOneLevelAtATimeAndUpRestoresTheirParent() {
        Fixture().use { f ->
            val grid = f.wrap.findViewById<GridView>(R.id.libraryGrid)
            assertEquals(1, grid.adapter.count)
            grid.performItemClick(null, 0, 0)
            assertEquals("root", f.selected); assertEquals("", f.path)
            grid.performItemClick(null, 0, 0)
            assertEquals("旅途", f.path)
            grid.performItemClick(null, 0, 0)
            assertEquals("旅途/山间", f.path)
            grid.performItemClick(null, 0, 0)
            assertEquals(f.video, f.played)
            f.wrap.findViewById<View>(R.id.libraryUp).performClick()
            assertEquals("旅途", f.path)
            f.wrap.findViewById<View>(R.id.libraryUp).performClick()
            assertEquals("", f.path)
            f.wrap.findViewById<View>(R.id.libraryUp).performClick()
            assertNull(f.selected)
        }
    }

    @Test fun viewAndSortPreferencesSurviveReopeningTheLibrary() {
        Fixture().use { f ->
            f.wrap.findViewById<View>(R.id.libraryFilesView).performClick()
            f.wrap.findViewById<View>(R.id.librarySort).performClick()
            val menu = f.wrap.findViewById<GridView>(R.id.librarySortMenu)
            assertTrue(menu.isShown)
            menu.performItemClick(null, 5, 5) // File size descending.
            assertFalse(menu.isShown)
            f.view.close(); f.view = f.makeView(); f.render()
            val prefs = f.activity.getSharedPreferences("library_view_test", Context.MODE_PRIVATE)
            assertFalse(prefs.getBoolean("folders", true))
            assertEquals("SIZE", prefs.getString("sort", null))
            assertTrue(prefs.getBoolean("descending", false))
            assertTrue(f.wrap.findViewById<TextView>(R.id.librarySort).text.contains("文件大小 ↓"))
        }
    }

    @Test fun localAndNetworkSortPickersUseTheViewportAndTakeDragsOverOptions() {
        for (layout in listOf(R.layout.activity_main, R.layout.activity_network_library)) {
            Fixture(1.3f, layout).use { f ->
                val density = f.wrap.resources.displayMetrics.density
                fun resize(heightDp: Int) {
                    val w = (640 * density).toInt(); val h = (heightDp * density).toInt()
                    f.wrap.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
                    f.wrap.layout(0, 0, w, h)
                }
                val page = f.wrap.findViewById<LibraryScrollFrame>(R.id.libraryScroller)
                val button = f.wrap.findViewById<TextView>(R.id.librarySort)
                val menu = f.wrap.findViewById<GridView>(R.id.librarySortMenu)
                fun bounds(v: View) = Rect(0, 0, v.width, v.height).also { f.wrap.offsetDescendantRectToMyCoords(v, it) }
                resize(270)
                page.scrollPage((24 * density).toInt())
                val before = bounds(button)
                button.performClick(); resize(270)
                assertEquals(8, menu.count)
                assertTrue("viewport=${f.wrap.height}, menu=${bounds(menu)}, first=${bounds(menu.getChildAt(0))}, last=${bounds(menu.getChildAt(7))}, density=$density", bounds(menu.getChildAt(7)).bottom <= f.wrap.height)
                assertFalse(menu.canScrollVertically(1))
                f.wrap.findViewById<View>(R.id.librarySortCancel).performClick(); resize(270)
                assertEquals(before, bounds(button))
                // A short window cannot fit all four rows. Start the drag *inside* the options.
                resize(162); button.performClick(); resize(162)
                assertTrue("short viewport=${f.wrap.height}, menu=${bounds(menu)}, children=${menu.childCount}, first=${menu.firstVisiblePosition}, last=${bounds(menu.getChildAt(menu.childCount-1))}", menu.lastVisiblePosition < 7 || bounds(menu.getChildAt(menu.childCount-1)).bottom > bounds(menu).bottom)
                val headerTop = bounds(button).top
                val area = bounds(menu)
                for (fraction in listOf(0.65f, 0.88f, 0.88f)) {
                    val down = android.os.SystemClock.uptimeMillis()
                    fun send(action: Int, t: Long, y: Float) {
                        MotionEvent.obtain(down, down + t, action, area.exactCenterX() / 2, area.top + area.height() * y, 0).also {
                            f.wrap.dispatchTouchEvent(it); it.recycle()
                        }
                    }
                    send(MotionEvent.ACTION_DOWN, 0, fraction)
                    send(MotionEvent.ACTION_MOVE, 50, fraction - 0.18f)
                    send(MotionEvent.ACTION_MOVE, 100, 0.08f)
                    send(MotionEvent.ACTION_CANCEL, 120, 0.08f)
                }
                assertEquals("Reached final row by dragging inside sort choices", 7, menu.lastVisiblePosition)
                assertEquals(headerTop, bounds(button).top)
                val last = menu.getChildAt(7 - menu.firstVisiblePosition)
                assertTrue(bounds(last).bottom <= f.wrap.height)
                menu.performItemClick(last, 7, 7)
                assertTrue(button.text.contains("时长 ↓")); assertFalse(menu.isShown)
                assertNull(f.played)
            }
        }
    }

    @Test fun narrowLibraryToolbarFitsTheViewAndSortButtonsWithLargerFonts() {
        Fixture(1.3f).use { f ->
            val d = f.wrap.resources.displayMetrics.density
            val width = (320 * d).toInt(); val height = (640 * d).toInt()
            f.wrap.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            f.wrap.layout(0, 0, width, height)
            var right = 0
            for (id in listOf(R.id.libraryFilesView, R.id.libraryFolderView, R.id.librarySort)) {
                val button = f.wrap.findViewById<TextView>(id)
                val bounds = Rect(0, 0, button.width, button.height)
                f.wrap.offsetDescendantRectToMyCoords(button, bounds)
                assertTrue(bounds.width() > 0 && bounds.height() > 0)
                assertTrue(bounds.left >= right && bounds.right <= width)
                assertTrue(bounds.bottom <= height)
                assertTrue(button.layout.getEllipsisCount(0) == 0)
                right = bounds.right
            }
        }
    }
}
