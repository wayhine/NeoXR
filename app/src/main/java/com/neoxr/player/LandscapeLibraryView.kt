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
                var lastFirst = -1; var lastTop = Int.MIN_VALUE; var lastCols = 0; var visible = false
                bindings += {
                    if (b.adapter !== a.adapter) {
                        b.adapter = a.adapter
                        lastFirst = -1
                    }
                    val showing = a.isShown
                    if (showing && !visible) { b.invalidateViews(); lastFirst = -1 }
                    visible = showing
                    if (showing && a.childCount > 0) {
                        val first = a.firstVisiblePosition
                        val top = a.getChildAt(0).top - a.paddingTop
                        val cols = (b as? GridView)?.numColumns?.coerceAtLeast(1) ?: 1
                        if (first != lastFirst || top != lastTop || cols != lastCols) {
                            b.setSelectionFromTop(first / cols * cols, top)
                            lastFirst = first; lastTop = top; lastCols = cols
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
