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
 * Fallback rects (v1.0.5): a ratio-fallback rect is a GUESS, never a
 * detection, so it can never lock the table on its own — feeding
 * guessed frames to the stability count is exactly how v1.0.4 drew
 * lines on a guessed table on a fresh non-table screen (the guess is
 * deterministic from the frame size, so it is trivially "stable").
 * The fallback may only CONTINUE a lock a real detection already
 * earned, for at most [CvConfig.FALLBACK_CONTINUATION_FRAMES] frames
 * (10 s at the 15 FPS cap): long enough to ride out a brief detection
 * dropout, short enough that leaving the game still hides the guides.
 * After the window the lock drops and the pipeline must report no
 * table until a real detection re-locks from scratch.
 *
 * Pure Kotlin, no Android dependency — fully unit-testable on the JVM.
 * Validated against the harness replica: locks at frame 75 on a
 * stable table, clears when the table is re-detected 10 px away,
 * never locks on black frames, never locks on fallback rects alone.
 */
class TableLock {

    private var candidate: TableRect? = null
    private var stableFrames = 0
    /** Fallback-continuation frames consumed since the last real
     * detection. Resets on every real frame. */
    private var fallbackFrames = 0

    /** True once the current table rect has been stable long enough. */
    var locked = false
        private set

    /** Fraction of the way to a lock (0.0-1.0): stable real frames so
     * far over [CvConfig.TABLE_LOCK_FRAMES]. 1.0 once locked. The
     * bubble's lock ring shows this; fallback frames never advance it. */
    val lockProgress: Float
        get() = if (locked) 1f
        else (stableFrames.toFloat() / CvConfig.TABLE_LOCK_FRAMES).coerceIn(0f, 1f)

    /** True when a fallback rect may still continue the current lock:
     * a real lock exists and the continuation window is not spent. */
    val canContinueFallback: Boolean
        get() = locked && fallbackFrames < CvConfig.FALLBACK_CONTINUATION_FRAMES

    /** Feed the table rect for this frame (or null). [fromFallback]
     * must be true when the rect is the ratio fallback, not a
     * detection. Returns whether the table is locked after this frame. */
    fun update(table: TableRect?, fromFallback: Boolean = false): Boolean {
        if (table == null) {
            candidate = null
            stableFrames = 0
            fallbackFrames = 0
            locked = false
            return false
        }
        if (fromFallback) {
            // A guess never counts toward the lock. It may only ride
            // out a real lock for the continuation window; past it the
            // lock drops and the pipeline must stop using the guess.
            if (!locked) return false
            fallbackFrames++
            if (fallbackFrames > CvConfig.FALLBACK_CONTINUATION_FRAMES) {
                candidate = null
                stableFrames = 0
                fallbackFrames = 0
                locked = false
            }
            return locked
        }
        fallbackFrames = 0
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
        fallbackFrames = 0
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
