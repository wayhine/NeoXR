package com.neoxr.player

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.LinearLayout
import android.widget.TextView

/** Collapse the whole library header while keeping the grid virtualized. */
class LibraryScrollFrame @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    FrameLayout(context, attrs) {
    private var collapsed = 0
    private var collapseLimit = 0
    private var nested = false
    private var lastHeight = 0
    private val reveal by lazy {
        TextView(context).apply {
            text = "筛选 / 排序 ↓"
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(context.getColor(R.color.on_surface))
            setBackgroundResource(R.drawable.bg_input)
            elevation = dp(4).toFloat()
            setOnClickListener { expand() }
        }.also { addView(it, LayoutParams(dp(108), dp(32), Gravity.TOP or Gravity.END).apply {
            topMargin = dp(4); marginEnd = dp(16)
        }) }
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onFinishInflate() {
        super.onFinishInflate()
        reveal.visibility = View.GONE
    }

    fun expand() { setCollapsed(0) }

    private fun setCollapsed(value: Int): Int {
        val before = collapsed
        collapsed = value.coerceIn(0, collapseLimit)
        if (before != collapsed) requestLayout()
        return collapsed - before
    }

    private fun forceContentMeasure(view: View) {
        view.forceLayout()
        // A cached parent measurement only restores that parent's dimensions,
        // leaving descendant heights from the previous collapse amount in place.
        // Remeasure the containers, but let the grid recycle its visible items.
        if (view is ViewGroup && view !is AdapterView<*>) {
            for (i in 0 until view.childCount) forceContentMeasure(view.getChildAt(i))
        }
    }

    private fun headerHeight(grid: View, content: View): Int {
        var child = grid
        var result = 0
        while (child !== content) {
            val parent = child.parent as? ViewGroup ?: break
            result += parent.paddingTop + ((child.layoutParams as? MarginLayoutParams)?.topMargin ?: 0)
            if (parent is LinearLayout && parent.orientation == LinearLayout.VERTICAL) {
                for (i in 0 until parent.childCount) {
                    val sibling = parent.getChildAt(i)
                    if (sibling === child) break
                    if (sibling.visibility == View.GONE) continue
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
        val grid = findViewById<GridView>(R.id.libraryGrid)
        val visible = findViewById<View>(R.id.libraryPanel)?.visibility == View.VISIBLE
        val newlyShort = height != lastHeight && height < dp(440)
        if (!visible) collapsed = 0
        // Compute and remeasure here, not from onLayout: layout-time requests can
        // otherwise be discarded, leaving the header shifted over a tiny grid.
        for (pass in 0 until 3) {
            forceContentMeasure(content)
            content.measure(widthMeasureSpec,
                MeasureSpec.makeMeasureSpec(height + collapsed, MeasureSpec.EXACTLY))
            collapseLimit = if (visible && grid != null)
                (headerHeight(grid, content) - dp(8)).coerceAtLeast(0) else 0
            var next = collapsed.coerceIn(0, collapseLimit)
            if (newlyShort && visible && grid != null && grid.measuredHeight < height * 0.6f) {
                next = (collapsed + (height * 0.6f - grid.measuredHeight).toInt()).coerceIn(0, collapseLimit)
            }
            if (next == collapsed) break
            collapsed = next
        }
        if (content.measuredHeight != height + collapsed) {
            forceContentMeasure(content)
            content.measure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(height + collapsed, MeasureSpec.EXACTLY))
        }
        lastHeight = height
        measureChild(reveal, widthMeasureSpec, heightMeasureSpec)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        val content = getChildAt(0) ?: return
        content.offsetTopAndBottom(-collapsed)
        reveal.visibility = if (collapsed > 0) View.VISIBLE else View.GONE
    }

    override fun onStartNestedScroll(child: View, target: View, axes: Int): Boolean =
        target.id == R.id.libraryGrid && axes and View.SCROLL_AXIS_VERTICAL != 0

    override fun onNestedScrollAccepted(child: View, target: View, axes: Int) { nested = true }
    override fun getNestedScrollAxes() = if (nested) View.SCROLL_AXIS_VERTICAL else 0
    override fun onStopNestedScroll(target: View) { nested = false }
    override fun onNestedPreScroll(target: View, dx: Int, dy: Int, consumed: IntArray) {
        consumed[1] = setCollapsed(collapsed + dy)
    }
    override fun onNestedScroll(target: View, dxConsumed: Int, dyConsumed: Int, dxUnconsumed: Int, dyUnconsumed: Int) {
        setCollapsed(collapsed + dyUnconsumed)
    }
}
