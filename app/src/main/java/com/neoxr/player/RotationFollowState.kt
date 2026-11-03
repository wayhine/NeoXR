package com.neoxr.player

import kotlin.math.abs

/** A button override lasts until the handset moves to another stable physical direction. */
internal class RotationFollowState {
    var manualOverride = false
        private set
    private var lastStable: Int? = null
    private var manualStart: Int? = null
    private var candidate: Int? = null
    private var candidateSince = 0L

    fun holdManual() { manualOverride = true; manualStart = lastStable }
    fun reset() {
        manualOverride = false; lastStable = null; manualStart = null; candidate = null
    }

    fun onOrientation(degrees: Int, timeMs: Long): Boolean {
        if (degrees !in 0..359) return false
        val quarter = ((degrees + 45) / 90) % 4
        val distance = abs(((degrees - quarter * 90 + 540) % 360) - 180)
        if (distance > 20) { candidate = null; return false }
        if (candidate != quarter) { candidate = quarter; candidateSince = timeMs; return false }
        if (timeMs - candidateSince < 300) return false
        lastStable = quarter
        if (!manualOverride) return false
        val start = manualStart
        if (start == null) { manualStart = quarter; return false }
        if (start == quarter) return false
        manualOverride = false; manualStart = null
        return true
    }
}
