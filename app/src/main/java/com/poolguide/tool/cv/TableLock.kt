package com.poolguide.tool.cv

/**
 * Table-lock stability gate (Bloro lead, v1.0.3).
 *
 * Draw nothing until the detected table rectangle has stayed within
 * [CvConfig.TABLE_LOCK_TOLERANCE_PX] on all four corners for
 * [CvConfig.TABLE_LOCK_FRAMES] consecutive frames (75 at the 15 FPS
 * cap = 5 s). This is the defence against flicker and false lines
 * while the table is animating, mid-transition, or the camera is still
 * settling — it costs nothing and needs no new detection.
 *
 * A null table, or a rect that moved beyond tolerance, resets the
 * count. The gate lives in the pipeline ([CvPipeline] reports
 * [DetectionResult.tableLocked]) and the overlay hides the lines
 * until it is set.
 *
 * Pure Kotlin, no Android dependency — fully unit-testable on the JVM.
 * Validated against the harness replica: locks at frame 75 on a
 * stable table, clears when the table is re-detected 10 px away,
 * never locks on black frames.
 */
class TableLock {

    private var candidate: TableRect? = null
    private var stableFrames = 0

    /** True once the current table rect has been stable long enough. */
    var locked = false
        private set

    /** Feed the table rect detected for this frame (or null). Returns
     * whether the table is locked after this frame. */
    fun update(table: TableRect?): Boolean {
        if (table == null) {
            candidate = null
            stableFrames = 0
            locked = false
            return false
        }
        val current = candidate
        if (current != null && sameRect(current, table)) {
            stableFrames++
        } else {
            candidate = table
            stableFrames = 1
        }
        locked = stableFrames >= CvConfig.TABLE_LOCK_FRAMES
        return locked
    }

    fun reset() {
        candidate = null
        stableFrames = 0
        locked = false
    }

    /** True when every corner of [a] is within tolerance of [b]. */
    private fun sameRect(a: TableRect, b: TableRect): Boolean {
        val tolerance = CvConfig.TABLE_LOCK_TOLERANCE_PX
        return a.corners.zip(b.corners).all { (p, q) ->
            kotlin.math.abs(p.x - q.x) <= tolerance &&
                kotlin.math.abs(p.y - q.y) <= tolerance
        }
    }
}
