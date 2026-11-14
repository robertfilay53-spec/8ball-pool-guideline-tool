package com.poolguide.tool.geometry

/**
 * Blocked-path label (v1.0.19). Pure Kotlin, no Android dependency —
 * fully unit-testable on the JVM. The solver already knows every
 * ball's position and [PathBlock] already answers whether one sits
 * on the shown shot's path; this object is the single place that
 * turns that answer into what the player sees: the "Path blocked"
 * label in the live overlay, in the warning amber (the same colour
 * as the thin-cut warning — a blocked path is a warning, not a
 * second shot).
 */
object BlockedPathLabel {

    /** The label text, always this exact string. */
    const val TEXT = "Path blocked"

    /** Where the label sits: the aim line's midpoint, pushed off
     * the line by 2.5 ball radii perpendicular to the aim direction
     * on the OPPOSITE side from the cut-angle label (see
     * [CutAngleLabel.labelAnchor], which pushes the same distance
     * the other way), so the two labels never sit on each other.
     * The offset is in the same coordinate space as [aimLine] and
     * [ballRadiusPx] (working coordinates in the overlay before
     * mapping, screen coordinates after — the caller passes the
     * smoothed screen aim segment, like the cut-angle label). */
    fun labelAnchor(aimLine: Segment, ballRadiusPx: Double): Vec2 {
        val mid = (aimLine.start + aimLine.end) * 0.5
        val perp = aimLine.direction().perp()
        return mid - perp * (2.5 * ballRadiusPx)
    }
}
