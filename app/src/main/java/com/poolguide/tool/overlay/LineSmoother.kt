package com.poolguide.tool.overlay

import com.poolguide.tool.geometry.Segment
import com.poolguide.tool.geometry.Vec2

/**
 * Line smoother: the jitter gate + interpolation (Bloro lead, v1.0.3).
 *
 * The overlay redraws from each CV frame, so detection noise makes the
 * lines jitter. If the cue point moved [JITTER_GATE_PX] or less since
 * the last drawn frame and the shot status is unchanged, do NOT snap:
 * interpolate (lerp) the line endpoints toward the new values. A jump
 * over the gate, or a status change, snaps immediately — a new shot
 * must never ease in from the old one.
 *
 * The gate is in screen pixels (Bloro's 40 px is a screen distance);
 * [LineOverlayManager] smooths AFTER mapping working -> screen
 * coordinates. The lerp factor is 0.5 per frame at the 15 FPS cap:
 * the line converges in ~2 frames, smooth but responsive.
 *
 * Pure Kotlin, no Android dependency — fully unit-testable on the JVM.
 */
class LineSmoother {

    companion object {
        /** Cue-point movement at or below this interpolates; above it snaps. */
        const val JITTER_GATE_PX = 40.0
        /** Per-frame interpolation factor (0 = frozen, 1 = snap). */
        const val LERP_FACTOR = 0.5
    }

    private data class Smoothed(
        val aim: Segment,
        val target: Segment,
        val deflection: Segment,
        val status: String
    )

    private var last: Smoothed? = null

    /** Smooth the best shot's three lines for this frame. Returns the
     * lines to draw (screen coordinates). */
    fun smooth(
        aim: Segment,
        target: Segment,
        deflection: Segment,
        status: String
    ): Triple<Segment, Segment, Segment> {
        val previous = last
        val cueMoved = previous?.aim?.start?.distanceTo(aim.start) ?: Double.MAX_VALUE
        val snap = previous == null ||
            previous.status != status ||
            cueMoved > JITTER_GATE_PX
        val smoothed = if (snap) {
            Smoothed(aim, target, deflection, status)
        } else {
            Smoothed(
                aim = lerpSegment(previous.aim, aim),
                target = lerpSegment(previous.target, target),
                deflection = lerpSegment(previous.deflection, deflection),
                status = status
            )
        }
        last = smoothed
        return Triple(smoothed.aim, smoothed.target, smoothed.deflection)
    }

    fun reset() {
        last = null
    }

    private fun lerpSegment(from: Segment, to: Segment): Segment =
        Segment(lerpPoint(from.start, to.start), lerpPoint(from.end, to.end))

    private fun lerpPoint(from: Vec2, to: Vec2): Vec2 =
        from + (to - from) * LERP_FACTOR
}
