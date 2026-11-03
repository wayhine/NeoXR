package com.neoxr.player

import android.app.Activity
import android.app.Application
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.view.Display

internal data class DisplayRoute(val id: Int, val width: Int, val height: Int, val rotation: Int)

/** A mode switch can remove/re-add the display. Wait before treating it as unplugged. */
internal class DisplayRecovery(private val initial: DisplayRoute?) {
    private var candidate = initial
    private var since = 0L
    private var presentationLost = false
    fun lostPresentation(now: Long) { presentationLost = true; since = now }
    fun waitMs(route: DisplayRoute?, now: Long): Long? {
        if (route != candidate) { candidate = route; since = now }
        if (route == initial && !presentationLost) return null
        val grace = if (route == null && initial != null) 4000L else 800L
        return (grace - (now - since)).coerceAtLeast(0)
    }
}

internal class GlassesWatcher(
    private val activity: Activity, initial: Display?, initiallyResumed: Boolean = false, private val rebuild: () -> Unit
) : AutoCloseable {
    private val handler = Handler(Looper.getMainLooper())
    private val manager = activity.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    private val recovery = DisplayRecovery(route(initial))
    private var closed = false
    private var resumed = initiallyResumed
    private val check = object : Runnable {
        override fun run() {
            if (closed || !resumed || activity.isDestroyed || activity.isFinishing) return
            handler.removeCallbacks(this)
            val delay = recovery.waitMs(route(Glasses.find(activity)), SystemClock.uptimeMillis()) ?: return
            if (delay > 0) handler.postDelayed(this, delay) else {
                close(); rebuild()
            }
        }
    }
    private val listener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = check.run()
        override fun onDisplayRemoved(displayId: Int) = check.run()
        override fun onDisplayChanged(displayId: Int) = check.run()
    }
    private val lifecycle = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(a: Activity, state: Bundle?) {}
        override fun onActivityStarted(a: Activity) {}
        override fun onActivityResumed(a: Activity) {
            if (a === activity) { resumed = true; check.run() }
        }
        override fun onActivityPaused(a: Activity) {
            if (a === activity) { resumed = false; handler.removeCallbacks(check) }
        }
        override fun onActivityStopped(a: Activity) {}
        override fun onActivitySaveInstanceState(a: Activity, state: Bundle) {}
        override fun onActivityDestroyed(a: Activity) { if (a === activity) close() }
    }
    init {
        manager.registerDisplayListener(listener, handler)
        activity.application.registerActivityLifecycleCallbacks(lifecycle)
    }
    fun presentationDismissed() {
        if (closed) return
        recovery.lostPresentation(SystemClock.uptimeMillis())
        check.run()
    }
    override fun close() {
        if (closed) return
        closed = true; handler.removeCallbacks(check); manager.unregisterDisplayListener(listener)
        activity.application.unregisterActivityLifecycleCallbacks(lifecycle)
    }
    private fun route(display: Display?): DisplayRoute? = display?.takeIf { it.isValid }?.let {
        val mode = it.mode
        DisplayRoute(it.displayId, mode.physicalWidth, mode.physicalHeight, it.rotation)
    }
}
