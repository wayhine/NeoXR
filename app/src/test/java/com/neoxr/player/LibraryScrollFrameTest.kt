package com.neoxr.player

import android.app.Activity
import android.content.res.Configuration
import android.graphics.Rect
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
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
class LibraryScrollFrameTest {
    private fun checkLayout(layout: Int, widthDp: Int, heightDp: Int) {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            val configuration = Configuration(activity.resources.configuration).apply { fontScale = 1.3f }
            val context = ContextThemeWrapper(activity.createConfigurationContext(configuration), R.style.Theme_NeoXR)
            val root = FrameLayout(context)
            LayoutInflater.from(context).inflate(layout, root, true)
            val scroller = root.findViewById<LibraryScrollFrame>(R.id.libraryScroller)
            val grid = root.findViewById<GridView>(R.id.libraryGrid)
            grid.isNestedScrollingEnabled = true
            grid.adapter = object : BaseAdapter() {
                override fun getCount() = 200
                override fun getItem(position: Int) = position
                override fun getItemId(position: Int) = position.toLong()
                override fun getView(position: Int, convert: View?, parent: ViewGroup): View =
                    ((convert as? TextView) ?: TextView(context).apply { height = dp(100) }).apply { text = "影片 $position" }
                private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
            }
            val d = context.resources.displayMetrics.density
            val w = (widthDp * d).toInt(); val h = (heightDp * d).toInt()
            fun measure() = repeat(4) {
                root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
                root.layout(0, 0, w, h)
            }
            fun bounds(view: View): Rect = Rect(0, 0, view.width, view.height).also {
                root.offsetDescendantRectToMyCoords(view, it)
            }
            measure()
            if (heightDp < 440) assertTrue("Short view reserves thumbnail space: ${grid.height}/$h", grid.height >= h * 0.59f)
            scroller.expand(); measure()
            val expandedGridHeight = grid.height
            val browse = root.findViewById<View>(R.id.libraryBrowse)
            assertTrue(bounds(browse).top >= 0)
            assertTrue(grid.startNestedScroll(View.SCROLL_AXIS_VERTICAL))
            val consumed = IntArray(2)
            assertTrue(grid.dispatchNestedPreScroll(0, h * 2, consumed, null))
            assertTrue(consumed[1] > 0)
            measure()
            // Several move events occur during a real drag. At 270dp the expanded
            // headers initially exceed the viewport; measuring the enlarged body
            // reveals their remaining natural height on the next move.
            repeat(2) { grid.dispatchNestedPreScroll(0, h, IntArray(2), null); measure() }
            assertTrue("Header leaves the viewport: ${bounds(browse)}, grid=${bounds(grid)}, consumed=${consumed[1]}, size=$w/$h", bounds(browse).bottom < 0)
            assertTrue("Grid gets the released height", grid.height > expandedGridHeight)
            assertTrue("Grid fills height: ${bounds(grid)}, size=$w/$h, consumed=${consumed[1]}", grid.height > h * 0.9f)
            assertTrue("Large libraries remain virtualized", grid.childCount < grid.adapter.count)
            val reveal = (0 until scroller.childCount).map { scroller.getChildAt(it) }
                .filterIsInstance<TextView>().single()
            assertEquals(View.VISIBLE, reveal.visibility)
            val button = bounds(reveal)
            assertTrue(button.top >= 0 && button.right <= w && button.bottom <= h)
            grid.dispatchNestedPreScroll(0, -h * 2, IntArray(2), null); measure()
            assertTrue("Downward scroll restores controls", bounds(browse).top >= 0)
            grid.dispatchNestedPreScroll(0, h * 2, IntArray(2), null); measure()
            reveal.performClick(); measure()
            assertTrue("Reveal button restores controls", bounds(browse).top >= 0)
            grid.stopNestedScroll()
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun homeLibraryHeadersCollapseInShortLandscapeAndNarrowSbsLayouts() {
        checkLayout(R.layout.activity_main, 640, 320)
        checkLayout(R.layout.activity_main, 480, 270)
    }
    @Test fun networkLibraryUsesTheSameScrollingAndRestoresAllControls() {
        checkLayout(R.layout.activity_network_library, 640, 320)
    }
    @Test fun portraitLibraryCanAlsoScrollItsControlsAwayWithoutExpandingAllCards() {
        checkLayout(R.layout.activity_main, 360, 780)
    }
}
