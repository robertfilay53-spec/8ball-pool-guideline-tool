package com.poolguide.tool.geometry

import java.util.Locale

/**
 * Pocket-distance readout (v1.0.15). Pure Kotlin, no Android
 * dependency — fully unit-testable on the JVM. The solver already
 * knows every ball's travelled path for a solved shot; this object
 * is the single place that turns that path into what the player
 * sees: the total shot distance beside the pocket ring, in ball
 * diameters, in the live overlay and in practice mode.
 *
 * The number is a POWER proxy, not a make guarantee: the harder the
 * player must hit, the longer the balls travel. It is the total
 * path the potting chain travels — cue ball to impact, then each
 * struck ball to the next impact or the pocket — measured in ball
 * diameters (one ball diameter is the pool-native unit: a table is
 * about 44 ball diameters long, so "12.4 bd" reads at a glance).
 *
 * Per-family path, in travelled order ([pathSegments]):
 * - direct: aim line (cue -> ghost) + target line (target -> pocket).
 * - one-rail bank: aim line + the bank path (target -> rail -> pocket).
 * - one-rail kick: the kick path (cue -> rail -> ghost) + target line.
 *   The direct solve's aim line is the straight cue -> ghost chord,
 *   NOT the travelled path, so it is not counted.
 * - two-rail bank / kick: same rule, all path segments.
 * - combo: first leg aim + first leg target (first ball -> the
 *   intermediate ghost) + second leg target (second ball -> pocket).
 *   The second leg's aim line (first ghost -> second ghost) is the
 *   SAME first-ball travel measured from the ghost point, so it is
 *   NOT counted twice.
 * - kiss: kiss aim (cue -> ghost) + second leg aim (cue's post-kiss
 *   travel, ghost -> second ghost) + second leg target. The kissed
 *   ball's departure line is energy lost, not on the potting chain,
 *   and the deflection stub is a fixed 2-radii drawing aid, so
 *   neither is counted.
 *
 * Formatting is Locale.US on purpose, like [CutAngleLabel]: a
 * distance must always read "12.4 bd", never "12,4 bd" on a device
 * whose locale uses a comma decimal separator.
 */
object PocketDistanceLabel {

    /** One decimal, "bd" (ball diameters): 12.44 -> "12.4 bd". */
    fun format(ballDiameters: Double): String =
        String.format(Locale.US, "%.1f bd", ballDiameters)

    /** Total travelled path in ball diameters: the sum of the
     * [segments] lengths divided by one ball diameter (2 radii).
     * Empty path -> 0. */
    fun totalBallDiameters(segments: List<Segment>, ballRadiusPx: Double): Double {
        require(ballRadiusPx > 0) { "ballRadiusPx must be positive" }
        var total = 0.0
        for (segment in segments) total += segment.length()
        return total / (2.0 * ballRadiusPx)
    }

    /** Path segments for a direct shot, in travelled order. */
    fun pathSegments(shot: ShotSolver.ShotSolution): List<Segment> =
        listOf(shot.aimLine, shot.targetLine)

    /** Path segments for a one-rail bank shot, in travelled order. */
    fun pathSegments(bank: ShotSolver.BankSolution): List<Segment> =
        listOf(bank.direct.aimLine) + bank.bankPath

    /** Path segments for a two-rail bank shot, in travelled order. */
    fun pathSegments(bank: ShotSolver.TwoRailBankSolution): List<Segment> =
        listOf(bank.direct.aimLine) + bank.bankPath

    /** Path segments for a one-rail kick shot, in travelled order. */
    fun pathSegments(kick: ShotSolver.KickSolution): List<Segment> =
        kick.kickPath + listOf(kick.direct.targetLine)

    /** Path segments for a two-rail kick shot, in travelled order. */
    fun pathSegments(kick: ShotSolver.TwoRailKickSolution): List<Segment> =
        kick.kickPath + listOf(kick.direct.targetLine)

    /** Path segments for a combo shot, in travelled order. The second
     * leg's aim line is deliberately left out: it measures the first
     * ball's travel from the ghost point, which [pathSegments] already
     * counts as the first leg's target line. */
    fun pathSegments(combo: ShotSolver.ComboSolution): List<Segment> =
        listOf(
            combo.firstShot.aimLine,
            combo.firstShot.targetLine,
            combo.secondShot.targetLine
        )

    /** Path segments for a kiss shot, in travelled order. The kissed
     * ball's departure line is deliberately left out: it pots
     * nothing, it is energy lost off the potting chain. */
    fun pathSegments(kiss: ShotSolver.KissSolution): List<Segment> =
        listOf(
            kiss.kissShot.aimLine,
            kiss.secondShot.aimLine,
            kiss.secondShot.targetLine
        )

    /** Where the distance label sits: just past the pocket ring, on
     * the arrival direction (the direction the ball travels INTO the
     * pocket), so the text never sits on the target line or the ring
     * it describes. [pocket] and [arrivalDirection] are in the same
     * coordinate space as [ballRadiusPx] and [ringRadiusPx] (working
     * coordinates in the overlay before mapping, view coordinates in
     * practice mode). A degenerate (zero) arrival direction falls
     * back to straight above the pocket. */
    fun labelAnchor(
        pocket: Vec2,
        arrivalDirection: Vec2,
        ballRadiusPx: Double,
        ringRadiusPx: Double
    ): Vec2 {
        val dir = arrivalDirection.normalized()
        val use = if (dir.norm() < 1e-9) Vec2(0.0, -1.0) else dir
        return pocket + use * (ringRadiusPx + 2.0 * ballRadiusPx)
    }
}
