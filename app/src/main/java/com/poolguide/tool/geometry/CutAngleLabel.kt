package com.poolguide.tool.geometry

import java.util.Locale

/**
 * Cut-angle readout (v1.0.12). Pure Kotlin, no Android dependency —
 * fully unit-testable on the JVM. The solver already computes the cut
 * angle for every shot ([ShotSolver.ShotSolution.cutAngleDegrees]) and
 * already flags thin cuts ([ShotSolver.THIN_CUT_DEGREES]); this object
 * is the single place that turns those numbers into what the player
 * sees: the angle label beside the aim line and the thin-cut warning
 * above the cue ball, in the live overlay and in practice mode.
 *
 * Formatting is Locale.US on purpose: a degree readout must always
 * read "12.6°", never "12,6°" on a device whose locale uses a comma
 * decimal separator.
 */
object CutAngleLabel {

    /** One decimal, degree sign: 12.64 -> "12.6°", 34.62 -> "34.6°". */
    fun format(cutAngleDegrees: Double): String =
        String.format(Locale.US, "%.1f°", cutAngleDegrees)

    /** Strictly above the thin-cut threshold: 80.0 is NOT thin,
     * 80.1 IS. Same rule as [ShotSolver.ShotSolution.isThinCut]. */
    fun isThinCut(cutAngleDegrees: Double): Boolean =
        cutAngleDegrees > ShotSolver.THIN_CUT_DEGREES

    /** Warning label for a thin cut, null otherwise:
     * 82.83 -> "Thin cut 82.8°". */
    fun warningText(cutAngleDegrees: Double): String? =
        if (isThinCut(cutAngleDegrees)) "Thin cut ${format(cutAngleDegrees)}" else null

    /** Where the angle label sits: the aim line's midpoint, pushed off
     * the line by 2.5 ball radii perpendicular to the aim direction,
     * so the text never sits on the line it describes. The offset is
     * in the same coordinate space as [aimLine] and [ballRadiusPx]
     * (working coordinates in the overlay, view coordinates in
     * practice mode). */
    fun labelAnchor(aimLine: Segment, ballRadiusPx: Double): Vec2 {
        val mid = (aimLine.start + aimLine.end) * 0.5
        val perp = aimLine.direction().perp()
        return mid + perp * (2.5 * ballRadiusPx)
    }

    /** Where the thin-cut warning sits: above the cue ball by 3 ball
     * radii, in the same coordinate space as [cueBall]. */
    fun warningAnchor(cueBall: Vec2, ballRadiusPx: Double): Vec2 =
        Vec2(cueBall.x, cueBall.y - 3.0 * ballRadiusPx)
}
