package com.neoxr.player

import android.app.Activity
import android.app.Presentation
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.view.Gravity
import android.widget.FrameLayout
import kotlin.math.roundToInt

internal object LibraryMirror {
    fun attach(activity: Activity, content: SbsFrameLayout, layoutRes: Int? = null) {
        activity.fullscreenOverCutout()
        val display = Glasses.find(activity)
        activity.requestedOrientation = if (display == null) ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        val prefs = activity.getSharedPreferences("neoxr", Context.MODE_PRIVATE)
        content.sbs = display == null && prefs.getBoolean("menuSbs", false)
        content.setBackgroundColor(activity.getColor(R.color.backdrop))
        val watcher = GlassesWatcher(activity, display) { activity.recreate() }
        if (display == null) {
            activity.setContentView(content)
            content.onLongPressToggle = {
                content.sbs = !content.sbs
                prefs.edit().putBoolean("menuSbs", content.sbs).apply()
            }
            return
        }
        content.keepScreenOn = true
        val mode = display.mode
        val modeKey = "menuSbs:${display.name}:${mode.physicalWidth}x${mode.physicalHeight}"
        val window = Presentation(activity, display)
        window.setCancelable(false)
        window.window?.apply {
            addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_FULLSCREEN)
            @Suppress("DEPRECATION")
            decorView.systemUiVisibility = activity.window.decorView.systemUiVisibility
        }
        val aspect = VideoGeometry.glassesEyeAspect(mode.physicalWidth, mode.physicalHeight)
        val initialStereo = prefs.getBoolean(modeKey, VideoGeometry.isFullSbs(mode.physicalWidth, mode.physicalHeight))
        val mirror: LibraryOutput
        if (layoutRes != null) {
            activity.setContentView(content)
            val page = LandscapeLibraryView(activity, content, layoutRes, aspect, initialStereo)
            window.setContentView(page)
            mirror = page
        } else {
            // Arbitrary web pages cannot share a DOM between two WebViews. Keep the one
            // interactive browser in a landscape preview on the upright phone.
            val preview = object : FrameLayout(activity) {
                override fun onMeasure(w: Int, h: Int) {
                    val width = MeasureSpec.getSize(w); val height = MeasureSpec.getSize(h)
                    val fitW = minOf(width, (height * aspect).roundToInt())
                    content.layoutParams.apply { this.width = fitW; this.height = (fitW / aspect).roundToInt() }
                    super.onMeasure(w, h)
                }
            }
            preview.setBackgroundColor(activity.getColor(R.color.backdrop))
            preview.addView(content, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
            activity.setContentView(preview)
            val web = LibraryMirrorView(window.context, activity, content, aspect, initialStereo)
            window.setContentView(web)
            mirror = web
        }
        content.onLongPressToggle = {
            mirror.stereo = !mirror.stereo
            prefs.edit().putBoolean(modeKey, mirror.stereo).apply()
            content.flash(if (mirror.stereo) "眼镜菜单：左右双目" else "眼镜菜单：单画面")
        }
        GlassesPresentation(activity, window, watcher::presentationDismissed) { error ->
            android.util.Log.w("NeoXR", "Library mirror unavailable", error)
            content.onLongPressToggle = null
            content.flash("眼镜输出暂不可用，可继续在手机上选片")
        }
    }
}

internal interface LibraryOutput { var stereo: Boolean }

internal class LibraryMirrorView(
    context: Context, private val activity: Activity, private val source: View,
    private val eyeAspect: Float, stereo: Boolean
) : View(context), LibraryOutput {
    override var stereo = stereo
        set(value) { field = value; invalidate() }
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val destination = RectF()
    private val handler = Handler(Looper.getMainLooper())
    private val position = IntArray(2)
    private val bounds = Rect()
    private var shown: Bitmap? = null
    private var spare: Bitmap? = null
    private var generation = 0
    private var copying = false
    private var dirty = false
    private var queued = false
    private var retries = 0
    private var observer: ViewTreeObserver? = null
    private val drawListener = ViewTreeObserver.OnDrawListener { retries = 0; schedule() }
    private val capture = Runnable {
        queued = false
        if (!isAttachedToWindow || !source.isAttachedToWindow || source.width <= 0 || source.height <= 0) return@Runnable
        source.getLocationInWindow(position)
        bounds.set(position[0], position[1], position[0] + source.width, position[1] + source.height)
        val scale = minOf(1f, 1920f / source.width, 1080f / source.height)
        val width = (source.width * scale).roundToInt().coerceAtLeast(1)
        val height = (source.height * scale).roundToInt().coerceAtLeast(1)
        val back = spare?.takeIf { it.width == width && it.height == height }
            ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        spare = null
        val ticket = generation
        dirty = false
        copying = true
        try {
            PixelCopy.request(activity.window, bounds, back, { result ->
                if (ticket != generation) return@request
                copying = false
                if (result == PixelCopy.SUCCESS) {
                    spare = shown
                    shown = back
                    invalidate()
                } else {
                    spare = back
                    if (++retries <= 3) dirty = true
                }
                if (dirty) schedule()
            }, handler)
        } catch (_: IllegalArgumentException) {
            copying = false
            spare = back
            if (++retries <= 3) schedule()
        }
    }

    private fun schedule() {
        dirty = true
        if (queued || copying || !isAttachedToWindow) return
        queued = true
        postOnAnimationDelayed(capture, 16)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        observer = source.viewTreeObserver.also { it.addOnDrawListener(drawListener) }
        schedule()
    }

    override fun onDetachedFromWindow() {
        observer?.takeIf { it.isAlive }?.removeOnDrawListener(drawListener)
        observer = null
        removeCallbacks(capture)
        generation++
        copying = false
        queued = false
        shown = null
        spare = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        val frame = shown ?: return
        val eyes = if (stereo) 2 else 1
        val panelWidth = width.toFloat() / eyes
        val panelAspect = if (stereo) eyeAspect else width.toFloat() / height
        val (widthFraction, heightFraction) = VideoGeometry.fit(frame.width.toFloat() / frame.height, panelAspect)
        val insetX = panelWidth * (1 - widthFraction) / 2
        val insetY = height * (1 - heightFraction) / 2
        for (eye in 0 until eyes) {
            destination.set(eye * panelWidth + insetX, insetY, (eye + 1) * panelWidth - insetX, height - insetY)
            canvas.drawBitmap(frame, null, destination, paint)
        }
    }
}
