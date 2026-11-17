package com.neoxr.player

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.SystemClock
import android.view.Display
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView

/**
 * Fullscreen over the entire physical panel, including the camera cutout area.
 * Mandatory for every screen: the glasses' SBS merge splits the *physical* screen
 * in half, so any horizontal inset (cutout letterbox, nav bar) shifts our split
 * off the physical center and the eyes see misaligned halves.
 */
@Suppress("DEPRECATION")
fun Activity.fullscreenOverCutout() {
    if (Build.VERSION.SDK_INT >= 28) {
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode =
                if (Build.VERSION.SDK_INT >= 30) WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }
    window.decorView.systemUiVisibility =
        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
}

/**
 * Lays out one eye's content and draws it into both screen halves, so regular
 * Android UI stays readable while the glasses are in native 3D-SBS mode. Touches on
 * either half are mapped back to full-layout coordinates.
 *
 * ponytail: dialogs, spinner popups, toasts and the IME are separate windows and
 * stay non-SBS; render in-layout panels if that ever matters.
 */
class SbsFrameLayout(context: Context) : FrameLayout(context) {

    // Both copies fill their entire physical half. Full SBS keeps square pixels,
    // while Half SBS compresses horizontally for the glasses to expand optically.
    var sbs = false
        set(value) {
            field = value
            requestLayout()
            invalidate()
        }

    /** Physical aspect of one eye, supplied by the current external display mode. */
    var eyeAspect: Float? = null
        set(value) { field = value?.takeIf { it.isFinite() && it > 0f }; requestLayout() }

    val contentWidth: Int
        get() = if (sbs && eyeAspect != null) (height * eyeAspect!!).toInt().coerceAtLeast(1) else width

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (!sbs || eyeAspect == null) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        val physicalWidth = MeasureSpec.getSize(widthMeasureSpec)
        val logicalWidth = (MeasureSpec.getSize(heightMeasureSpec) * eyeAspect!!).toInt().coerceAtLeast(1)
        super.onMeasure(MeasureSpec.makeMeasureSpec(logicalWidth, MeasureSpec.EXACTLY), heightMeasureSpec)
        setMeasuredDimension(physicalWidth, measuredHeight)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        if (!sbs || eyeAspect == null) {
            super.onLayout(changed, left, top, right, bottom)
            return
        }
        // FrameLayout positions gravity-aligned children against its physical width.
        // In SBS they all belong to the logical, single-eye layout instead.
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            val lp = child.layoutParams as LayoutParams
            val gravity = if (lp.gravity == -1) Gravity.TOP or Gravity.START else lp.gravity
            val absolute = Gravity.getAbsoluteGravity(gravity, layoutDirection)
            val x = when (absolute and Gravity.HORIZONTAL_GRAVITY_MASK) {
                Gravity.RIGHT -> contentWidth - paddingRight - lp.rightMargin - child.measuredWidth
                Gravity.CENTER_HORIZONTAL -> paddingLeft + (contentWidth - paddingLeft - paddingRight - child.measuredWidth) / 2 + lp.leftMargin - lp.rightMargin
                else -> paddingLeft + lp.leftMargin
            }
            val y = when (gravity and Gravity.VERTICAL_GRAVITY_MASK) {
                Gravity.BOTTOM -> height - paddingBottom - lp.bottomMargin - child.measuredHeight
                Gravity.CENTER_VERTICAL -> paddingTop + (height - paddingTop - paddingBottom - child.measuredHeight) / 2 + lp.topMargin - lp.bottomMargin
                else -> paddingTop + lp.topMargin
            }
            child.layout(x, y, x + child.measuredWidth, y + child.measuredHeight)
        }
    }

    /** When set, a long press anywhere (except editable text) toggles SBS. */
    var onLongPressToggle: (() -> Unit)? = null

    /** Zones (layout coords) where the long-press toggle must not fire, e.g. a touchpad. */
    var longPressExclude: ((x: Float, y: Float) -> Boolean)? = null

    // In-layout toast replacement: system Toasts are separate windows, so the
    // glasses' SBS merge never shows them — anything that must be readable in
    // stereo goes through flash() instead.
    private var flashView: TextView? = null
    private val hideFlash = Runnable { flashView?.visibility = GONE }

    fun flash(msg: String) {
        val tv = flashView ?: TextView(context).apply {
            textSize = 16f
            setTextColor(context.getColor(R.color.on_surface))
            setBackgroundResource(R.drawable.bg_input)
            val p = (14 * resources.displayMetrics.density).toInt()
            setPadding(p, p, p, p)
            layoutParams = LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
            ).apply { bottomMargin = (120 * resources.displayMetrics.density).toInt() }
            flashView = this
            addView(this)
        }
        tv.text = msg
        tv.visibility = VISIBLE
        tv.bringToFront()
        tv.removeCallbacks(hideFlash)
        tv.postDelayed(hideFlash, 2500)
    }

    private val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onLongPress(e: MotionEvent) {
            val toggle = onLongPressToggle ?: return
            if (isEditTextAt(this@SbsFrameLayout, mapX(e.x), e.y)) return // keep long-press paste
            if (longPressExclude?.invoke(mapX(e.x), e.y) == true) return
            cancelChildren() // swallow the press so the item under the finger doesn't also fire
            toggle()
        }
    })

    override fun dispatchDraw(canvas: Canvas) {
        if (!sbs) {
            super.dispatchDraw(canvas)
            return
        }
        for (eye in 0..1) {
            canvas.save()
            canvas.clipRect(eye * width / 2f, 0f, (eye + 1) * width / 2f, height.toFloat())
            canvas.translate(eye * width / 2f, 0f)
            canvas.scale(width / (2f * contentWidth), 1f)
            super.dispatchDraw(canvas)
            canvas.restore()
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (onLongPressToggle != null) detector.onTouchEvent(ev)
        if (!sbs || width == 0) return super.dispatchTouchEvent(ev)
        val copy = MotionEvent.obtain(ev)
        val eyeStart = if (ev.x >= width / 2f) width / 2f else 0f
        copy.transform(Matrix().apply {
            setValues(floatArrayOf(contentWidth * 2f / width, 0f, -eyeStart * contentWidth * 2f / width,
                0f, 1f, 0f, 0f, 0f, 1f))
        })
        val handled = super.dispatchTouchEvent(copy)
        copy.recycle()
        return handled
    }

    private fun mapX(x: Float): Float =
        if (sbs && width > 0) (x % (width / 2f)) * contentWidth * 2f / width else x

    /** Dispatches a synthesized event in layout coords, bypassing the SBS touch remap. */
    fun inject(ev: MotionEvent): Boolean = super.dispatchTouchEvent(ev)

    private fun cancelChildren() {
        val now = SystemClock.uptimeMillis()
        val cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
        super.dispatchTouchEvent(cancel)
        cancel.recycle()
    }

    private fun isEditTextAt(v: View, x: Float, y: Float): Boolean {
        if (v === this) {
            for (i in 0 until childCount) if (isEditTextAt(getChildAt(i), x, y)) return true
            return false
        }
        if (x < v.left || x > v.right || y < v.top || y > v.bottom) return false
        if (v is EditText) return true
        if (v !is ViewGroup) return false
        val lx = x - v.left + v.scrollX
        val ly = y - v.top + v.scrollY
        for (i in 0 until v.childCount) if (isEditTextAt(v.getChildAt(i), lx, ly)) return true
        return false
    }

    companion object {
        /**
         * Sets [layoutRes] as the activity content wrapped in an [SbsFrameLayout].
         * Without glasses it goes to the phone screen (long press toggles menu SBS).
         * With glasses plugged in (USB-C external display) the content goes to THEM
         * at native resolution and the phone shows only a touchpad controller.
         * Callers must findViewById through the returned wrap, not the activity.
         * [controllerExtras] are appended to the phone controller strip in glasses
         * mode (ignored otherwise).
         */
        fun attach(
            activity: Activity, layoutRes: Int, controllerExtras: List<View> = emptyList()
        ): SbsFrameLayout {
            activity.fullscreenOverCutout()
            val prefs = activity.getSharedPreferences("neoxr", Context.MODE_PRIVATE)
            val wrap = SbsFrameLayout(activity)
            activity.layoutInflater.inflate(layoutRes, wrap, true)
            wrap.sbs = prefs.getBoolean("menuSbs", false)
            val glasses = Glasses.find(activity)
            fun showOnPhone(failedDisplay: Display? = null) {
                // no glasses, or the display refused the presentation (a stale
                // display entry, or one the app may not draw on): keep everything
                // on the phone rather than crashing
                wrap.onLongPressToggle = {
                    wrap.sbs = !wrap.sbs
                    prefs.edit().putBoolean("menuSbs", wrap.sbs).apply()
                }
                activity.setContentView(wrap)
                watchForGlasses(activity, failedDisplay)
            }
            val out = glasses?.let { display -> GlassesOut(activity, display, wrap) { showOnPhone(display) } }
            if (out != null) activity.setContentView(GlassesOut.controller(activity, out, controllerExtras))
            else showOnPhone()
            return wrap
        }

        /** Recreates the activity when glasses get plugged in, moving content to them. */
        private fun watchForGlasses(activity: Activity, initial: Display? = null) {
            // A failed presentation falls back after onResume, so its watcher is already active.
            val watcher = GlassesWatcher(activity, initial, initiallyResumed = initial != null) { activity.recreate() }
            activity.window.decorView.addOnAttachStateChangeListener(
                object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(v: View) {}
                    override fun onViewDetachedFromWindow(v: View) =
                        watcher.close()
                })
        }
    }
}
