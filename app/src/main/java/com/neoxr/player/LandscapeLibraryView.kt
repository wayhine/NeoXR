package com.neoxr.player

import android.app.Activity
import android.graphics.Canvas
import android.text.TextUtils
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.*
import kotlin.math.roundToInt

/** A passive second layout. Only the phone owns navigation, repositories, permissions and playback. */
internal class LandscapeLibraryView(
    private val activity: Activity, private val phone: SbsFrameLayout, private val layout: Int,
    private val eyeAspect: Float, stereo: Boolean
) : FrameLayout(activity), LibraryOutput {
    override var stereo = stereo
        set(value) { if (field != value) { field = value; requestLayout(); invalidate() } }
    private var source: View? = null
    private var lastPhoneWidth = 0
    private val bindings = mutableListOf<() -> Unit>()
    private val lists = mutableListOf<AbsListView>()
    private val gridScrolls = mutableListOf<() -> Unit>()
    private val syncMeasuredGrids = Runnable { gridScrolls.forEach { it() } }
    private var observer: ViewTreeObserver? = null
    private val beforeDraw = ViewTreeObserver.OnPreDrawListener { sync(); true }

    init {
        setBackgroundColor(activity.getColor(R.color.backdrop))
        descendantFocusability = FOCUS_BLOCK_DESCENDANTS
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        clipChildren = false
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        observer = phone.viewTreeObserver.also { it.addOnPreDrawListener(beforeDraw) }
        sync()
    }

    override fun onDetachedFromWindow() {
        observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(beforeDraw)
        observer = null
        clearPage()
        super.onDetachedFromWindow()
    }

    private fun clearPage() {
        bindings.clear()
        removeCallbacks(syncMeasuredGrids)
        gridScrolls.clear()
        lists.forEach { it.adapter = null }
        lists.clear()
        removeAllViews()
        source = null
    }

    private fun sync() {
        if (lastPhoneWidth != phone.width) { lastPhoneWidth = phone.width; requestLayout() }
        val home = phone.getChildAt(0) ?: return
        val network = if (home.visibility == GONE)
            (0 until phone.childCount).map { phone.getChildAt(it) }.filterIsInstance<SbsFrameLayout>()
                .firstOrNull { it.visibility == VISIBLE }?.getChildAt(0) else null
        val page = network ?: home
        if (source !== page) {
            clearPage()
            val replica = activity.layoutInflater.inflate(if (network == null) layout else R.layout.activity_network_library, this, false)
            addView(replica)
            source = page
            bind(page, replica)
        }
        bindings.forEach { it() }
    }

    private fun bind(a: View, b: View) {
        var background = a.background
        fun copyBackground() { b.background = a.background?.constantState?.newDrawable(b.resources)?.mutate() }
        if (a.id != View.NO_ID) {
            // Preserve themed widget internals (notably MaterialButton); copy backgrounds
            // only for controls whose selected style is changed by our screen code.
            val styled = a.id in styledControls
            if (styled) copyBackground()
            bindings += {
                if (b.visibility != a.visibility) b.visibility = a.visibility
                b.isEnabled = a.isEnabled
                b.isSelected = a.isSelected
                if (b.alpha != a.alpha) b.alpha = a.alpha
                if (styled && background !== a.background) { background = a.background; copyBackground() }
                if (a is TextView && b is TextView) {
                    if (!TextUtils.equals(a.text, b.text)) b.text = a.text.toString()
                    if (b.currentTextColor != a.currentTextColor) b.setTextColor(a.currentTextColor)
                }
            }
        }
        when {
            a is AbsListView && b is AbsListView -> {
                lists += b
                val followGrid: (() -> Unit)? = if (a is GridView && b is GridView)
                    ({ followGridScroll(a, b) }) else null
                if (followGrid != null) gridScrolls += followGrid
                var lastFirst = -1; var lastTop = Int.MIN_VALUE; var visible = false
                bindings += {
                    if (b.adapter !== a.adapter) {
                        b.adapter = a.adapter
                        lastFirst = -1
                    }
                    val showing = a.isShown
                    if (showing && !visible) { b.invalidateViews(); lastFirst = -1 }
                    visible = showing
                    if (showing && followGrid != null) followGrid()
                    else if (showing && a.childCount > 0) {
                        val first = a.firstVisiblePosition
                        val top = a.getChildAt(0).top - a.paddingTop
                        if (first != lastFirst || top != lastTop) {
                            b.setSelectionFromTop(first, top)
                            lastFirst = first; lastTop = top
                        }
                    }
                }
            }
            a is LinearLayout && b is LinearLayout && a.id in dynamicRows -> {
                // These panels replace all their rows together (folder management / feed filters).
                var first: View? = null
                bindings += {
                    if (first !== a.getChildAt(0) || a.childCount != b.childCount) {
                        b.removeAllViews()
                        for (i in 0 until a.childCount) b.addView(copyRow(a.getChildAt(i)))
                        first = a.getChildAt(0)
                    }
                }
            }
            a is ViewGroup && b is ViewGroup -> {
                if (a is LibraryScrollFrame && b is LibraryScrollFrame)
                    bindings += { b.followHeader(a.collapsedFraction) }
                if (a is ScrollView && b is ScrollView) bindings += { b.scrollTo(0, a.scrollY) }
                for (i in 0 until minOf(a.childCount, b.childCount)) bind(a.getChildAt(i), b.getChildAt(i))
            }
        }
    }

    /** Map continuous scroll progress, rather than copying the phone's within-row
     * offset (which resets to zero at every phone row even if the eye is on the same row). */
    private fun followGridScroll(phoneGrid: GridView, eyeGrid: GridView) {
        if (!phoneGrid.isShown || !eyeGrid.isShown || phoneGrid.childCount == 0 || eyeGrid.childCount == 0 ||
            phoneGrid.count != eyeGrid.count || eyeGrid.adapter !== phoneGrid.adapter) return
        fun pitch(grid: GridView, cols: Int): Int {
            val row = grid.getChildAt(0)
            return (grid.getChildAt(cols)?.let { it.top - row.top }
                ?: (row.height + grid.verticalSpacing)).coerceAtLeast(1)
        }
        fun range(grid: GridView, cols: Int, step: Int): Double {
            val rows = (grid.count + cols - 1) / cols
            return (rows - (grid.height - grid.paddingTop - grid.paddingBottom + grid.verticalSpacing).toDouble() / step)
                .coerceAtLeast(0.0)
        }
        val srcCols = phoneGrid.numColumns.coerceAtLeast(1)
        val dstCols = eyeGrid.numColumns.coerceAtLeast(1)
        val srcPitch = pitch(phoneGrid, srcCols)
        val dstPitch = pitch(eyeGrid, dstCols)
        val srcRange = range(phoneGrid, srcCols, srcPitch)
        val srcRow = phoneGrid.firstVisiblePosition / srcCols +
            (phoneGrid.paddingTop - phoneGrid.getChildAt(0).top).toDouble() / srcPitch
        val fraction = when {
            srcRange <= 0 || !phoneGrid.canScrollList(-1) -> 0.0
            !phoneGrid.canScrollList(1) ||
                (phoneGrid.lastVisiblePosition == phoneGrid.count - 1 && !phoneGrid.canScrollVertically(1)) -> 1.0
            else -> (srcRow / srcRange).coerceIn(0.0, 1.0)
        }
        if (fraction == 1.0) {
            val last = eyeGrid.count - 1
            val footer = eyeGrid.getChildAt(last - eyeGrid.firstVisiblePosition)
            val bottom = eyeGrid.height - eyeGrid.paddingBottom
            if (footer == null) {
                val lastRow = last / dstCols * dstCols
                eyeGrid.setSelectionFromTop(lastRow, bottom - dstPitch + eyeGrid.verticalSpacing - eyeGrid.paddingTop)
            } else if (footer.bottom > bottom) {
                eyeGrid.scrollListBy(footer.bottom - bottom)
            }
            return
        }
        val dstRange = range(eyeGrid, dstCols, dstPitch)
        // Different column counts and viewport heights can make equal percentages
        // point at different videos. Follow the actual adapter position on return
        // from playback and during scrolling, clamped at the end of the eye list.
        val dstRow = if (phoneGrid.id == R.id.libraryGrid) when (fraction) {
            0.0 -> 0.0
            else -> (srcRow * srcCols / dstCols).coerceIn(0.0, dstRange)
        } else fraction * dstRange
        val dstFirst = dstRow.toInt() * dstCols
        val top = -((dstRow - dstRow.toInt()) * dstPitch).roundToInt()
        val actual = eyeGrid.getChildAt(0)
        val target = eyeGrid.getChildAt(dstFirst - eyeGrid.firstVisiblePosition)
        val actualTop = target?.top ?: (actual.top + (dstFirst - eyeGrid.firstVisiblePosition) / dstCols * dstPitch)
        val delta = actualTop - eyeGrid.paddingTop - top
        if (delta == 0) return
        // Normal drags retain recycled rows. Only a viewport-sized jump (e.g. a
        // search reset) needs a new layout anchored at a specific adapter position.
        if (kotlin.math.abs(delta) < (eyeGrid.height - eyeGrid.paddingTop - eyeGrid.paddingBottom - 1))
            eyeGrid.scrollListBy(delta)
        else eyeGrid.setSelectionFromTop(dstFirst, top)
    }

    private fun copyRow(v: View): View {
        val copy = when (v) {
            is LinearLayout -> LinearLayout(context).apply {
                gravity = v.gravity; orientation = v.orientation
                for (i in 0 until v.childCount) addView(copyRow(v.getChildAt(i)))
            }
            is TextView -> TextView(context).apply {
                text = v.text
                setTextSize(TypedValue.COMPLEX_UNIT_PX, v.textSize)
                setTextColor(v.currentTextColor)
                typeface = v.typeface; gravity = v.gravity
            }
            else -> View(context)
        }
        copy.layoutParams = LinearLayout.LayoutParams(v.layoutParams as LinearLayout.LayoutParams)
        copy.background = v.background?.constantState?.newDrawable(resources)?.mutate()
        copy.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, v.paddingBottom)
        copy.alpha = v.alpha
        return copy
    }

    override fun onMeasure(w: Int, h: Int) {
        val width = MeasureSpec.getSize(w); val height = MeasureSpec.getSize(h)
        setMeasuredDimension(width, height)
        if (width == 0 || height == 0) return
        // Use the phone's short side as the landscape layout height, independent of the
        // glasses' DPI. Half-SBS still lays out a full-width page for each eye.
        val logicalH = phone.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val aspect = if (stereo) eyeAspect else width.toFloat() / height
        getChildAt(0)?.measure(MeasureSpec.makeMeasureSpec((logicalH * aspect).roundToInt(), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(logicalH, MeasureSpec.EXACTLY))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        getChildAt(0)?.let { it.layout(0, 0, it.measuredWidth, it.measuredHeight) }
        // The first sync can precede measurement (header height / grid columns unknown).
        source?.findViewById<LibraryScrollFrame>(R.id.libraryScroller)?.let { src ->
            getChildAt(0)?.findViewById<LibraryScrollFrame>(R.id.libraryScroller)?.followHeader(src.collapsedFraction)
        }
        gridScrolls.forEach { it() }
        removeCallbacks(syncMeasuredGrids)
        post(syncMeasuredGrids)
    }

    override fun dispatchDraw(canvas: Canvas) {
        val page = getChildAt(0) ?: return
        if (page.width == 0 || page.height == 0) return
        val eyes = if (stereo) 2 else 1
        val eyeWidth = width.toFloat() / eyes
        for (i in 0 until eyes) {
            val save = canvas.save()
            canvas.translate(i * eyeWidth, 0f)
            canvas.clipRect(0f, 0f, eyeWidth, height.toFloat())
            canvas.scale(eyeWidth / page.width, height.toFloat() / page.height)
            super.dispatchDraw(canvas)
            canvas.restoreToCount(save)
        }
    }

    companion object {
        private val dynamicRows = setOf(R.id.libraryManageRows, R.id.browseRows)
        private val styledControls = setOf(R.id.btnLibrary, R.id.btnSources, R.id.libraryManage,
            R.id.libraryFilesView, R.id.libraryFolderView)
    }
}
