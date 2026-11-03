package com.neoxr.player

import android.content.res.Configuration
import android.graphics.Rect
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PlayerControlRowsTest {
    private val buttonIds = listOf(R.id.btnRw, R.id.btnPlay, R.id.btnFf, R.id.btnHoldSpeed,
        R.id.btn3d, R.id.btnPicture, R.id.btnTracks, R.id.btnQuality)

    private fun layoutRoot(fontScale: Float = 1f): SbsFrameLayout {
        val base = RuntimeEnvironment.getApplication()
        val configuration = Configuration(base.resources.configuration).apply { this.fontScale = fontScale }
        val context = ContextThemeWrapper(base.createConfigurationContext(configuration), R.style.Theme_NeoXR)
        val root = SbsFrameLayout(context)
        LayoutInflater.from(context).inflate(R.layout.player_controls, root, true)
        val center = root.findViewById<View>(R.id.btnCenter)
        (center.parent as ViewGroup).removeView(center)
        root.findViewById<LinearLayout>(R.id.playerActions).addView(center)
        root.findViewById<View>(R.id.controlsBar).visibility = View.VISIBLE
        buttonIds.forEach { root.findViewById<View>(it).visibility = View.VISIBLE }
        return root
    }

    private fun measure(root: SbsFrameLayout, width: Int, height: Int) {
        val d = root.resources.displayMetrics.density
        val w = (width * d).toInt(); val h = (height * d).toInt()
        root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, w, h)
    }
    private fun bounds(root: SbsFrameLayout, id: Int): Rect {
        val view = root.findViewById<View>(id)
        return Rect(0, 0, view.width, view.height).also { root.offsetDescendantRectToMyCoords(view, it) }
    }
    // The measured tree deliberately has no Window/ViewRootImpl; check visibility
    // through its actual parents instead of View.isShown's window-attachment check.
    private fun visibleInTree(view: View): Boolean =
        generateSequence(view) { it.parent as? View }.all { it.visibility == View.VISIBLE }
    private fun checkPortrait(root: SbsFrameLayout, width: Int, height: Int, remote: Boolean) {
        PlayerControlRows.arrange(root, true, remote)
        measure(root, width, height)
        val timeline = bounds(root, R.id.rowMain)
        for (id in buttonIds) {
            val view = root.findViewById<View>(id)
            val rect = bounds(root, id)
            assertTrue("button $id must be visible", visibleInTree(view))
            assertTrue("button $id must have size", rect.width() > 0 && rect.height() > 0)
            assertTrue("button $id must be below progress row", rect.top >= timeline.bottom)
            assertTrue("button $id must fit horizontally", rect.left >= 0 && rect.right <= root.width)
            assertTrue("button $id must fit vertically", rect.top >= 0 && rect.bottom <= root.height)
        }
        val transport = bounds(root, R.id.quickControls)
        val tuning = bounds(root, R.id.rowTop)
        assertTrue(tuning.top >= transport.bottom)
    }

    @Test fun portraitShowsEveryButtonBelowTheSeekBar() {
        checkPortrait(layoutRoot(), 360, 720, false)
    }

    @Test fun narrowPortraitAndLargeFontsKeepTheBottomButtonsOnScreen() {
        checkPortrait(layoutRoot(1.3f), 320, 640, false)
    }

    @Test fun navigationBarSpaceCannotCoverTheBottomTools() {
        val root = layoutRoot()
        val nav = (48 * root.resources.displayMetrics.density).toInt()
        PlayerControlRows.arrange(root, true, false, nav)
        measure(root, 360, 720)
        for (id in buttonIds) assertTrue(bounds(root, id).bottom <= root.height - nav)
    }

    @Test fun repeatedTurnsNeverLoseOrDuplicateAnyControls() {
        val root = layoutRoot()
        repeat(10) {
            checkPortrait(root, 360, 720, false)
            PlayerControlRows.arrange(root, false, false)
            measure(root, 720, 360)
            for (id in buttonIds.filter { it != R.id.btnHoldSpeed }) {
                val view = root.findViewById<View>(id)
                assertTrue(visibleInTree(view))
                assertTrue(bounds(root, id).width() > 0)
            }
            assertEquals(View.GONE, root.findViewById<View>(R.id.quickControls).visibility)
        }
    }

    @Test fun glassesControllerKeepsItsTransportAndToolsInPortrait() {
        checkPortrait(layoutRoot(), 360, 720, true)
    }
}
