package com.neoxr.player

import android.app.Activity
import android.content.res.Configuration
import android.graphics.Rect
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
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
class LibraryScrollFrameTest {
    private fun checkLayout(layout: Int, widthDp: Int, heightDp: Int, websites: Boolean = false) {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            val configuration = Configuration(activity.resources.configuration).apply { fontScale = 1.3f }
            val context = ContextThemeWrapper(activity.createConfigurationContext(configuration), R.style.Theme_NeoXR)
            val root = FrameLayout(context)
            LayoutInflater.from(context).inflate(layout, root, true)
            val scroller = root.findViewById<LibraryScrollFrame>(R.id.libraryScroller)
            val list: AbsListView = if (websites) {
                root.findViewById<View>(R.id.libraryPanel).visibility = View.GONE
                root.findViewById<View>(R.id.siteControls).visibility = View.VISIBLE
                root.findViewById<ListView>(R.id.sites).apply { visibility = View.VISIBLE }
            } else root.findViewById<GridView>(R.id.libraryGrid)
            var cardBinds = 0
            list.adapter = object : BaseAdapter() {
                override fun getCount() = 200
                override fun getItem(position: Int) = position
                override fun getItemId(position: Int) = position.toLong()
                override fun getView(position: Int, convert: View?, parent: ViewGroup): View {
                    cardBinds++
                    return ((convert as? TextView) ?: TextView(context).apply {
                        height = (100 * resources.displayMetrics.density).toInt()
                    }).apply { text = "影片 $position" }
                }
            }
            val d = context.resources.displayMetrics.density
            val w = (widthDp * d).toInt()
            val h = (heightDp * d).toInt()
            fun measure() = repeat(3) {
                root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
                root.layout(0, 0, w, h)
            }
            fun bounds(view: View): Rect = Rect(0, 0, view.width, view.height).also {
                root.offsetDescendantRectToMyCoords(view, it)
            }
            measure()
            val header = root.findViewById<View>(if (layout == R.layout.activity_main) R.id.btnLibrary else R.id.networkTitle)
            assertTrue("First opening keeps the top navigation visible", bounds(header).top >= 0)
            val before = bounds(header).top
            val listHeight = list.height
            val binds = cardBinds
            repeat(5) { step ->
                assertEquals(4, scroller.scrollPage(4))
                assertEquals(before - (step + 1) * 4, bounds(header).top)
                assertFalse("Header scrolling does not remeasure the page", scroller.isLayoutRequested)
                assertEquals(listHeight, list.height)
                assertEquals(binds, cardBinds)
            }
            scroller.scrollPage(h * 8)
            assertTrue("The complete header scrolls offscreen", bounds(header).bottom < 0)
            assertTrue("The list scrolls after the header", list.firstVisiblePosition > 0)
            assertTrue("Large libraries retain recycled cards", list.childCount < list.adapter.count)
            assertEquals(0, bounds(list).top)
            val offscreen = bounds(header).top
            assertEquals(-20, scroller.scrollPage(-20))
            assertEquals("Reversing scroll returns through media first", offscreen, bounds(header).top)
            scroller.scrollPage(-h * 20)
            assertEquals(0, list.firstVisiblePosition)
            assertFalse(list.canScrollVertically(-1))
            assertEquals("Navigation returns at the start of the same page", before, bounds(header).top)
            assertEquals(0, scroller.scrollPage(-100))

            // Start a real drag on a header button, where the old nested-grid
            // implementation never received scrolling events from the touchpad.
            val rect = bounds(header)
            val startY = rect.centerY().toFloat()
            fun send(action: Int, y: Float, time: Long) {
                MotionEvent.obtain(10, time, action, rect.centerX().toFloat(), y, 0).also {
                    scroller.dispatchTouchEvent(it)
                    it.recycle()
                }
            }
            send(MotionEvent.ACTION_DOWN, startY, 10)
            send(MotionEvent.ACTION_MOVE, startY - 60 * d, 60)
            send(MotionEvent.ACTION_CANCEL, startY - 60 * d, 80)
            assertTrue("Dragging the header scrolls the entire page", bounds(header).top < before)
            scroller.expand()
            assertEquals(before, bounds(header).top)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun wholeHomePageScrollsInShortLandscapeAndNarrowSbsLayouts() {
        checkLayout(R.layout.activity_main, 640, 320)
        checkLayout(R.layout.activity_main, 480, 270)
    }
    @Test fun networkLibraryUsesTheSameWholePageScroll() {
        checkLayout(R.layout.activity_network_library, 640, 320)
    }
    @Test fun portraitLibraryKeepsCardsVirtualized() {
        checkLayout(R.layout.activity_main, 360, 780)
    }
    @Test fun websiteHeadersAndSourcesShareThePageScroll() {
        checkLayout(R.layout.activity_main, 640, 320, websites = true)
    }
}
