package com.neoxr.player

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Rect
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ListView
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
    private class Fixture(fontScale: Float = 1f) : AutoCloseable {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val configuration = Configuration(activity.resources.configuration).apply { this.fontScale = fontScale }
        val context = ContextThemeWrapper(activity.createConfigurationContext(configuration), R.style.Theme_NeoXR)
        val wrap = FrameLayout(context).apply { LayoutInflater.from(context).inflate(R.layout.local_library_panel, this, true) }
        val metadata = LocalVideoAdapter(activity)
        val video = LibraryVideo("content://test/clip", "旅行.mp4", "旅途/山间", 1234, 100)
        val folders = listOf(LibraryFolder("root", "本地影片", listOf(video)))
        var selected: String? = null
        var path = ""
        var played: LibraryVideo? = null
        lateinit var view: LibraryPresentation
        init {
            activity.getSharedPreferences("library_view_test", Context.MODE_PRIVATE).edit().putBoolean("folders", true).commit()
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
            val menu = f.wrap.findViewById<ListView>(R.id.librarySortMenu)
            assertEquals(View.VISIBLE, menu.visibility)
            menu.performItemClick(null, 5, 5) // File size descending.
            assertEquals(View.GONE, menu.visibility)
            f.view.close(); f.view = f.makeView(); f.render()
            val prefs = f.activity.getSharedPreferences("library_view_test", Context.MODE_PRIVATE)
            assertFalse(prefs.getBoolean("folders", true))
            assertEquals("SIZE", prefs.getString("sort", null))
            assertTrue(prefs.getBoolean("descending", false))
            assertTrue(f.wrap.findViewById<TextView>(R.id.librarySort).text.contains("文件大小 ↓"))
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
