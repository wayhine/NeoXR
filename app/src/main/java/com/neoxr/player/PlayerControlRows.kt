package com.neoxr.player

import android.view.View
import android.view.ViewGroup
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** Rebuild from the same view instances on every turn; no conditional parent assumptions. */
internal object PlayerControlRows {
    fun arrange(root: View, portrait: Boolean, remote: Boolean, navigationInset: Int = 0) {
        val d = root.resources.displayMetrics.density
        fun dp(value: Int) = (value * d).toInt()
        val bar = root.findViewById<LinearLayout>(R.id.controlsBar)
        (bar.layoutParams as FrameLayout.LayoutParams).apply {
            bottomMargin = dp(8) + navigationInset.coerceAtLeast(0)
        }.also { bar.layoutParams = it }
        val quick = root.findViewById<LinearLayout>(R.id.quickControls)
        val timeline = root.findViewById<LinearLayout>(R.id.rowMain)
        val tuning = root.findViewById<FrameLayout>(R.id.rowTop)
        val transport = listOf(R.id.btnRw, R.id.btnPlay, R.id.btnFf).map { root.findViewById<TextView>(it) }
        val hold = root.findViewById<TextView>(R.id.btnHoldSpeed)
        val tools = listOf(R.id.btn3d, R.id.btnPicture, R.id.btnTracks, R.id.btnQuality).map { root.findViewById<TextView>(it) }
        val times = listOf(R.id.timePos, R.id.seek, R.id.timeDur).map { root.findViewById<View>(it) }
        (transport + tools + times + hold).forEach { (it.parent as? ViewGroup)?.removeView(it) }
        quick.removeAllViews(); timeline.removeAllViews(); tuning.removeAllViews()
        val separate = portrait || remote
        if (separate) {
            (transport + hold).forEachIndexed { index, button ->
                button.gravity = Gravity.CENTER; button.maxLines = 1
                button.textSize = if (button.id == R.id.btnPlay && !remote) 20f else 11f
                button.setPadding(dp(2), 0, dp(2), 0)
                button.setBackgroundResource(R.drawable.bg_input)
                quick.addView(button, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                    if (index < 3) marginEnd = dp(4)
                })
            }
            transport[0].text = "−10秒"; transport[2].text = "+15秒"
        } else {
            transport.forEach { button ->
                button.setPadding(dp(10), dp(10), dp(10), dp(10))
                button.textSize = if (button.id == R.id.btnPlay) 20f else 15f
                timeline.addView(button, LinearLayout.LayoutParams(-2, -2))
            }
            transport[0].text = "◄◄"; transport[2].text = "►►"
            quick.addView(hold, LinearLayout.LayoutParams(0, dp(48), 1f))
        }
        quick.visibility = if (separate) View.VISIBLE else View.GONE
        times.forEach { view ->
            timeline.addView(view, if (view.id == R.id.seek) LinearLayout.LayoutParams(0, dp(40), 1f)
                else LinearLayout.LayoutParams(-2, -2))
        }
        if (portrait) {
            val line = LinearLayout(root.context).apply { orientation = LinearLayout.HORIZONTAL }
            tools.forEach { button ->
                button.gravity = Gravity.CENTER; button.maxLines = 1; button.textSize = 13f
                button.setPadding(dp(4), 0, dp(4), 0)
                line.addView(button, LinearLayout.LayoutParams(0, dp(48), 1f))
            }
            tuning.addView(line, FrameLayout.LayoutParams(-1, -2))
        } else tools.forEach { button ->
            button.setPadding(dp(10), dp(10), dp(10), dp(10))
            button.textSize = if (button.id == R.id.btnQuality) 13f else 14f
            timeline.addView(button, LinearLayout.LayoutParams(-2, -2))
        }
        tuning.visibility = if (portrait) View.VISIBLE else View.GONE
        bar.removeAllViews()
        // Portrait: timeline, transport, then tools BELOW the progress bar.
        val rows = if (portrait) listOf(timeline, quick, tuning) else listOf(quick, timeline, tuning)
        rows.forEach { bar.addView(it, LinearLayout.LayoutParams(-1, -2)) }
    }
}
