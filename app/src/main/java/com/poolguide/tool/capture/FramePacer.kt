package com.poolguide.tool.capture

/**
 * Frame pacer (Scugalug measured targets: 15 FPS cap). The camera /
 * MediaProjection delivers frames faster than the pipeline can use
 * them; processing every one only burns battery and heats the phone
 * for a stale overlay. The pacer admits at most one frame per
 * [minIntervalMs] and drops the rest — the caller still closes the
 * dropped Image (or skips the copy) so the producer is never blocked.
 *
 * Pure Kotlin (no Android dependency) so it is unit-testable. The
 * clock is injected as milliseconds so tests can drive it exactly;
 * callers pass SystemClock.uptimeMillis().
 */
class FramePacer(private val minIntervalMs: Long) {

    private var lastAdmittedMs: Long? = null

    /** True when this frame may be processed; admits it (advances the
     * clock). False when it arrives inside the minimum interval. */
    fun shouldProcess(nowMs: Long): Boolean {
        val last = lastAdmittedMs
        if (last == null || nowMs - last >= minIntervalMs) {
            lastAdmittedMs = nowMs
            return true
        }
        return false
    }

    fun reset() {
        lastAdmittedMs = null
    }
}
