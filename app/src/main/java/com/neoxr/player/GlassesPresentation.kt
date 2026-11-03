package com.neoxr.player

import android.app.Activity
import android.app.Application
import android.app.Presentation
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager

/** Only the resumed page owns a window on the glasses. */
internal class GlassesPresentation(
    private val activity: Activity,
    private val presentation: Presentation,
    private val onDismissed: () -> Unit,
    private val onFailure: (Exception) -> Unit
) : AutoCloseable {
    private val handler = Handler(Looper.getMainLooper())
    private var resumed = false
    private var closed = false
    private var attempts = 0
    private val show = object : Runnable {
        override fun run() {
            if (closed || !resumed || activity.isFinishing || activity.isDestroyed) return
            try {
                presentation.setOnDismissListener { if (!closed && resumed) onDismissed() }
                presentation.show()
                android.util.Log.i("NeoXR", "presentation shown on display ${presentation.display.displayId}")
            } catch (e: Exception) {
                dismiss()
                attempts++
                android.util.Log.w("NeoXR", "presentation attempt $attempts failed on display ${presentation.display.displayId}", e)
                // A display can be listed before its window is ready during a page handoff.
                val transient = e is WindowManager.InvalidDisplayException || e is WindowManager.BadTokenException
                if (transient && attempts < 3) handler.postDelayed(this, 400L * attempts)
                else { close(); onFailure(e) }
            }
        }
    }
    private val lifecycle = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(a: Activity, state: Bundle?) {}
        override fun onActivityStarted(a: Activity) {}
        override fun onActivityResumed(a: Activity) {
            if (a !== activity || closed) return
            resumed = true
            attempts = 0
            handler.removeCallbacks(show)
            // Let the activity transition finish before requesting its presentation window.
            handler.post(show)
        }
        override fun onActivityPaused(a: Activity) {
            if (a !== activity) return
            resumed = false
            handler.removeCallbacks(show)
            dismiss()
        }
        override fun onActivityStopped(a: Activity) {}
        override fun onActivitySaveInstanceState(a: Activity, state: Bundle) {}
        override fun onActivityDestroyed(a: Activity) { if (a === activity) close() }
    }

    init { activity.application.registerActivityLifecycleCallbacks(lifecycle) }

    private fun dismiss() {
        // An intentional page handoff must not trigger display-loss recovery.
        presentation.setOnDismissListener(null)
        presentation.dismiss()
    }

    override fun close() {
        if (closed) return
        closed = true
        handler.removeCallbacks(show)
        activity.application.unregisterActivityLifecycleCallbacks(lifecycle)
        dismiss()
    }
}
