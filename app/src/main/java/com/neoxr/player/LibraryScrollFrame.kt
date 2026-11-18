package com.neoxr.player

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.AdapterView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.OverScroller

/** One vertical page containing the header and a recycled list of media cards. */
class LibraryScrollFrame @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    FrameLayout(context, attrs) {
    private var headerOffset = 0
    private var headerHeight = 0
    private var pendingHeader: Float? = null
    private val fling = OverScroller(context)
    private val configuration = ViewConfiguration.get(context)
    private var lastFlingY = 0
    private var downX = 0f
    private var downY = 0f
    private var lastY = 0f
    private var dragging = false
    private var pageGesture = false
    private var velocity: VelocityTracker? = null

    private fun activeList(): AbsListView? =
        if (findViewById<View>(R.id.libraryPanel)?.visibility == View.VISIBLE)
            findViewById(R.id.libraryGrid) else findViewById(R.id.sites)

    fun expand() {
        stopScroll()
        pendingHeader = null
        setHeaderOffset(0)
    }

    fun stopScroll() = fling.abortAnimation()

    internal val collapsedFraction get() = pendingHeader
        ?: if (headerHeight == 0) 0f else headerOffset.toFloat() / headerHeight
    internal fun followHeader(fraction: Float) {
        if (headerHeight == 0) pendingHeader = fraction
        else setHeaderOffset(kotlin.math.round(fraction * headerHeight).toInt())
    }

    private fun setHeaderOffset(value: Int): Int {
        val before = headerOffset
        headerOffset = value.coerceIn(0, headerHeight)
        getChildAt(0)?.let { it.offsetTopAndBottom(-headerOffset - it.top) }
        return headerOffset - before
    }

    /** Shared by direct finger drags, flings and the phone's two-finger touchpad. */
    fun scrollPage(distance: Int): Int {
        val list = activeList() ?: return 0
        var remaining = distance
        if (remaining > 0) remaining -= setHeaderOffset(headerOffset + remaining)
        // Small steps retain an overlapping row, allowing exact boundary consumption
        // even with variable-height website entries and partially visible first rows.
        val stepSize = (16 * resources.displayMetrics.density).toInt().coerceAtLeast(1)
        while (remaining != 0 && list.childCount > 0 && list.canScrollVertically(if (remaining > 0) 1 else -1)) {
            val step = remaining.coerceIn(-stepSize, stepSize)
            val first = list.firstVisiblePosition
            val tops = (0 until list.childCount).map { list.getChildAt(it).top }
            list.scrollListBy(step)
            val anchor = list.firstVisiblePosition - first
            val consumed = if (anchor in tops.indices)
                tops[anchor] - list.getChildAt(0).top else step
            remaining -= consumed
            if (consumed == 0) break
        }
        if (remaining < 0 && !list.canScrollVertically(-1)) remaining -= setHeaderOffset(headerOffset + remaining)
        return distance - remaining
    }

    private fun forceContentMeasure(view: View) {
        view.forceLayout()
        if (view is ViewGroup && view !is AdapterView<*>) {
            for (i in 0 until view.childCount) forceContentMeasure(view.getChildAt(i))
        }
    }

    private fun measureHeader(list: View, content: View): Int {
        var child = list
        var result = 0
        while (child !== content) {
            val parent = child.parent as? ViewGroup ?: break
            result += parent.paddingTop + ((child.layoutParams as? MarginLayoutParams)?.topMargin ?: 0)
            if (parent is LinearLayout && parent.orientation == LinearLayout.VERTICAL) {
                for (i in 0 until parent.childCount) {
                    val sibling = parent.getChildAt(i)
                    if (sibling === child) break
                    if (sibling.visibility == GONE) continue
                    val margins = sibling.layoutParams as? MarginLayoutParams
                    result += sibling.measuredHeight + (margins?.topMargin ?: 0) + (margins?.bottomMargin ?: 0)
                }
            }
            child = parent
        }
        return result
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val height = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), height)
        val content = getChildAt(0) ?: return
        val list = activeList()
        for (pass in 0 until 3) {
            forceContentMeasure(content)
            content.measure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(height + headerHeight, MeasureSpec.EXACTLY))
            val measuredHeader = list?.let { measureHeader(it, content) } ?: 0
            if (measuredHeader == headerHeight) break
            headerHeight = measuredHeader
        }
        if (content.measuredHeight != height + headerHeight) {
            forceContentMeasure(content)
            content.measure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(height + headerHeight, MeasureSpec.EXACTLY))
        }
        headerOffset = headerOffset.coerceIn(0, headerHeight)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        val fraction = pendingHeader
        if (fraction != null && headerHeight > 0) {
            pendingHeader = null
            followHeader(fraction)
        }
        setHeaderOffset(headerOffset)
    }

    private fun inPanel(id: Int, x: Float, y: Float): Boolean {
        val panel = findViewById<View>(id) ?: return false
        if (panel.visibility != VISIBLE) return false
        val rect = Rect(0, 0, panel.width, panel.height)
        offsetDescendantRectToMyCoords(panel, rect)
        return rect.contains(x.toInt(), y.toInt())
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                fling.abortAnimation()
                downX = event.x
                downY = event.y
                lastY = downY
                dragging = false
                pageGesture = activeList() != null &&
                    !listOf(R.id.librarySortMenu, R.id.libraryFolders, R.id.libraryManager).any { inPanel(it, downX, downY) }
                velocity?.recycle()
                velocity = VelocityTracker.obtain().apply { addMovement(event) }
            }
            MotionEvent.ACTION_MOVE -> {
                val vertical = kotlin.math.abs(event.y - downY)
                if (pageGesture && vertical > configuration.scaledTouchSlop && vertical > kotlin.math.abs(event.x - downX)) {
                    dragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
                velocity?.addMovement(event)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> finishGesture()
        }
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!pageGesture) return false
        velocity?.addMovement(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                dragging = true
                scrollPage((lastY - event.y).toInt())
                lastY = event.y
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val remaining = if (event.actionIndex == 0) 1 else 0
                lastY = event.getY(remaining)
                downY = lastY
                velocity?.clear()
            }
            MotionEvent.ACTION_UP -> {
                if (dragging) {
                    velocity?.computeCurrentVelocity(1000, configuration.scaledMaximumFlingVelocity.toFloat())
                    val speed = -(velocity?.yVelocity ?: 0f).toInt()
                    if (kotlin.math.abs(speed) >= configuration.scaledMinimumFlingVelocity) {
                        lastFlingY = 0
                        fling.fling(0, 0, 0, speed, 0, 0, Int.MIN_VALUE, Int.MAX_VALUE)
                        postInvalidateOnAnimation()
                    }
                }
                finishGesture()
            }
            MotionEvent.ACTION_CANCEL -> finishGesture()
        }
        return true
    }

    override fun computeScroll() {
        if (!fling.computeScrollOffset()) return
        val distance = fling.currY - lastFlingY
        lastFlingY = fling.currY
        if (scrollPage(distance) != distance) fling.abortAnimation()
        else postInvalidateOnAnimation()
    }

    private fun finishGesture() {
        dragging = false
        velocity?.recycle()
        velocity = null
    }

    override fun onDetachedFromWindow() {
        fling.abortAnimation()
        finishGesture()
        super.onDetachedFromWindow()
    }
}
