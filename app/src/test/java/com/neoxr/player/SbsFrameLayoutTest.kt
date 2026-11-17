package com.neoxr.player

import android.app.Activity
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SbsFrameLayoutTest {
    @Test fun fullAndHalfSbsUseTheSameSingleEyeLayout() {
        for ((width, height) in listOf(1920 to 1080, 3840 to 1080, 1920 to 1200, 3840 to 1200)) {
            val controller = Robolectric.buildActivity(Activity::class.java).setup()
            try {
                val wrap = SbsFrameLayout(controller.get()).apply {
                    sbs = true
                    eyeAspect = VideoGeometry.glassesEyeAspect(width, height)
                }
                val content = View(controller.get())
                wrap.addView(content, FrameLayout.LayoutParams(-1, -1))
                val rightAligned = View(controller.get())
                wrap.addView(rightAligned, FrameLayout.LayoutParams(100, 50, Gravity.RIGHT or Gravity.BOTTOM))
                wrap.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                wrap.layout(0, 0, width, height)
                assertEquals(width, wrap.width)
                assertEquals(1920, wrap.contentWidth)
                assertEquals(1920, content.width)
                assertEquals(height, content.height)
                assertEquals(1920, rightAligned.right)
                assertEquals(height, rightAligned.bottom)
                var touchX = -1f
                content.setOnTouchListener { _, event -> touchX = event.x; true }
                for (eye in 0..1) {
                    val event = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, width * (eye + 0.25f) / 2, 100f, 0)
                    wrap.dispatchTouchEvent(event)
                    event.recycle()
                    assertEquals("Both eyes map to the same logical position", 480f, touchX, 0.1f)
                    MotionEvent.obtain(0, 1, MotionEvent.ACTION_CANCEL, 0f, 0f, 0).also {
                        wrap.dispatchTouchEvent(it); it.recycle()
                    }
                }
                wrap.sbs = false
                wrap.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                wrap.layout(0, 0, width, height)
                assertEquals("Mono output uses the entire display", width, content.width)
            } finally { controller.pause().stop().destroy() }
        }
    }
}
