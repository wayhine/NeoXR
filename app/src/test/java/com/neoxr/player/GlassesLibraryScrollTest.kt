package com.neoxr.player

import android.app.Activity
import android.content.Context
import android.hardware.display.DisplayManager
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.GridView
import android.widget.LinearLayout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDisplayManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class GlassesLibraryScrollTest {
    @Test fun touchpadScrollWorksOverNavigationAndRemembersModeSpecificSbsChoice() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            activity.setTheme(R.style.Theme_NeoXR)
            val id = ShadowDisplayManager.addDisplay("w3840dp-h1080dp-land-mdpi")
            val display = (activity.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).getDisplay(id)
            val wrap = SbsFrameLayout(activity)
            LayoutInflater.from(activity).inflate(R.layout.activity_main, wrap, true)
            val output = GlassesOut(activity, display, wrap) { fail("Unexpected output failure") }
            assertTrue("Wide display modes default to SBS", wrap.sbs)
            wrap.measure(View.MeasureSpec.makeMeasureSpec(3840, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY))
            wrap.layout(0, 0, 3840, 1080)
            val tab = wrap.findViewById<View>(R.id.btnLibrary)
            val position = android.graphics.Rect(0, 0, tab.width, tab.height)
            wrap.offsetDescendantRectToMyCoords(tab, position)
            output.move(position.centerX().toFloat(), position.centerY().toFloat())
            val content = wrap.findViewById<LibraryScrollFrame>(R.id.libraryScroller).getChildAt(0)
            val before = content.top
            output.scroll(-60f)
            output.scrollEnd()
            assertEquals("Two fingers scroll even over the top navigation", before - 60, content.top)
            assertEquals("Scrolling does not select a different tab", View.VISIBLE, wrap.findViewById<View>(R.id.libraryPanel).visibility)
            assertEquals(0, wrap.findViewById<GridView>(R.id.libraryGrid).firstVisiblePosition)
            output.scroll(60f)
            output.scrollEnd()
            assertEquals(before, content.top)
            val cursor = wrap.getChildAt(wrap.childCount - 1)
            val cursorX = cursor.translationX
            val cursorY = cursor.translationY
            val remote = GlassesOut.controller(activity, output) as LinearLayout
            val pad = remote.getChildAt(remote.childCount - 2)
            fun touch(action: Int, ids: IntArray, ys: FloatArray, time: Long) {
                val properties = ids.map { id -> MotionEvent.PointerProperties().apply {
                    this.id = id; toolType = MotionEvent.TOOL_TYPE_FINGER
                } }.toTypedArray()
                val coordinates = ys.map { value -> MotionEvent.PointerCoords().apply {
                    x = 100f; y = value; pressure = 1f; size = 1f
                } }.toTypedArray()
                MotionEvent.obtain(100, time, action, ids.size, properties, coordinates,
                    0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0).also {
                    pad.dispatchTouchEvent(it); it.recycle()
                }
            }
            touch(MotionEvent.ACTION_DOWN, intArrayOf(0), floatArrayOf(100f), 100)
            touch(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                intArrayOf(0, 1), floatArrayOf(100f, 300f), 120)
            touch(MotionEvent.ACTION_MOVE, intArrayOf(0, 1), floatArrayOf(80f, 280f), 140)
            assertEquals("Adding a second finger does not introduce a scroll jump", before - 20, content.top)
            touch(MotionEvent.ACTION_POINTER_UP, intArrayOf(0, 1), floatArrayOf(80f, 280f), 160)
            touch(MotionEvent.ACTION_MOVE, intArrayOf(1), floatArrayOf(900f), 180)
            touch(MotionEvent.ACTION_UP, intArrayOf(1), floatArrayOf(900f), 200)
            assertEquals("Remaining finger keeps the pointer in place", cursorX, cursor.translationX, 0f)
            assertEquals(cursorY, cursor.translationY, 0f)
            assertFalse(output.toggleSbs())
            val restored = SbsFrameLayout(activity)
            GlassesOut(activity, display, restored) { fail("Unexpected output failure") }
            assertFalse("Explicit choice is saved for the same display mode", restored.sbs)
        } finally { controller.pause().stop().destroy() }
    }
}
