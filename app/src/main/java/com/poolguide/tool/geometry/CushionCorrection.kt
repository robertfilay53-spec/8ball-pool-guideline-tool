package com.poolguide.tool.geometry

/**
 * Cushion-response correction for bank shots (Bloro lead, v1.0.3).
 *
 * The form is Bloro's: a per-stage polynomial correction of the
 * reflected angle, chained per rail — a pragmatic way to absorb the
 * systematic error between ideal mirror reflection and the game's
 * actual cushion rendering/physics. The constants are NOT Bloro's
 * (fitted to their native detection output and the game version they
 * tested against); they are re-fitted to OUR harness data (see
 * [FITTED]).
 *
 * A correction is a quadratic in the incident angle (degrees, the
 * angle between the ideal outgoing direction and the rail):
 *   correction = a0 + a1*x + a2*x^2
 * applied to the reflected angle in [ShotSolver.solveBank]. The
 * correction is clamped to [MAX_CORRECTION_DEGREES]: a cushion
 * correction is a small empirical adjustment, never a large one, and
 * an unbounded polynomial would go wild outside the fitted range.
 *
 * Pure Kotlin, no Android dependency — fully unit-testable on the JVM.
 */
data class CushionCorrection(
    val a0: Double,
    val a1: Double,
    val a2: Double
) {
    /** Correct an incident angle (degrees). Returns the correction to
     * ADD to the reflected angle, clamped to +/- [MAX_CORRECTION_DEGREES]. */
    fun correct(incidentAngleDegrees: Double): Double {
        val x = incidentAngleDegrees
        val raw = a0 + a1 * x + a2 * x * x
        return raw.coerceIn(-MAX_CORRECTION_DEGREES, MAX_CORRECTION_DEGREES)
    }

    companion object {
        /** A correction must never move an angle by more than this. */
        const val MAX_CORRECTION_DEGREES = 2.0

        /**
         * The harness fit (v1.0.3, 15 synthetic tables, ground truth):
         * after the pocket-model fix (inset 1.5% -> 0, which removed
         * the dominant systematic error: t06 4.24 deg, t08 2.02 deg),
         * the residual cut-angle error is detection noise, not a
         * systematic function of angle:
         *   - deltas (GT - app) range -0.26..+0.47 deg, mean +0.22 deg
         *   - linear fit slope -0.004 deg/deg (flat: no angle dependence)
         *   - quadratic fit overfits (only 4 distinct cut angles in
         *     the data: 16/23/31/78 deg; rms 0.15 vs 0.26 for a constant)
         * So the fitted correction is ZERO: the form is in place and
         * live (applied in solveBank, unit-tested with synthetic
         * coefficients), but the data says there is nothing systematic
         * left to correct. When real-table measurements exist, re-fit
         * these three numbers from them — do not copy Bloro's.
         */
        val FITTED = CushionCorrection(0.0, 0.0, 0.0)
    }
}

/** Rotate [v] by [angleDegrees] counter-clockwise. */
internal fun rotate(v: Vec2, angleDegrees: Double): Vec2 {
    val radians = Math.toRadians(angleDegrees)
    val cos = kotlin.math.cos(radians)
    val sin = kotlin.math.sin(radians)
    return Vec2(v.x * cos - v.y * sin, v.x * sin + v.y * cos)
}

/** Angle (degrees, 0-90) between two directions. */
internal fun angleBetween(a: Vec2, b: Vec2): Double {
    val dot = a.normalized().dot(b.normalized()).coerceIn(-1.0, 1.0)
    return Math.toDegrees(kotlin.math.acos(dot))
}
