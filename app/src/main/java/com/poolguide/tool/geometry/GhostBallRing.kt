package com.poolguide.tool.geometry

import kotlin.math.abs

/**
 * Ghost-ball ring (v1.0.26; second-leg ring v1.0.27). Pure Kotlin, no Android dependency,
 * fully unit-testable on the JVM.
 *
 * Until now the live overlay drew the aim line ending at an invisible
 * point: the player saw the green line stop and had to imagine where
 * the cue ball's centre must arrive. Practice mode already draws the
 * ghost circle at impact (PracticeView.drawGhost); the live overlay
 * did not, so practice and live disagreed. The ring is a circle of
 * one ball radius centred on the ghost point, the cue ball's centre
 * at impact, in the aim green, the same colour practice uses. The
 * ring touches the target ball exactly (the ghost point sits 2r from
 * the target centre, and both balls have radius r), so the player
 * aims the cue ball at a visible circle that kisses the target, not
 * at empty felt.
 *
 * One ring per shown shot, on the FIRST leg's ghost: the point
 * the CUE ball must reach. Since v1.0.27 a combo or kiss draws a
 * second ring, in the same aim green, on the SECOND leg's ghost:
 * for a combo that is the first ball's arrival point (it is also
 * the first leg's target line end, the transfer point between the
 * two balls), and for a kiss it is the point the cue ball must
 * reach after the kiss to pot the second ball. Practice mode
 * already draws a ghost circle for each leg (PracticeView.drawShot
 * per leg); the live overlay now agrees with it. Like the pocket
 * ring and the rail-contact markers, a ring uses the solution's
 * own ghost point unsmoothed, so it never drifts from the point
 * it marks the way a smoothed line end could.
 *
 * The centre is always the solution's ghostPoint, never the aim
 * line's end. For a direct shot or a bank the two are the same
 * (the aim line is cue -> ghost), but for a kick the aim line is
 * the cue-stick direction and ends at the rail contact, while the
 * ghost sits at the end of the kicked path. A ring drawn at the aim
 * line's end would sit on the cushion for every kick.
 */
object GhostBallRing {

    /** Centre of the ghost ring for one solved shot: its ghost point. */
    fun center(shot: ShotSolver.ShotSolution): Vec2 = shot.ghostPoint

    /** Ring radius: one ball radius, the size of the cue ball itself. */
    fun radius(ballRadiusPx: Double): Double {
        require(ballRadiusPx > 0) { "ballRadiusPx must be positive" }
        return ballRadiusPx
    }

    /** True when the ring touches the target ball exactly: the ghost
     * point sits 2r from the target centre, so the two radius-r
     * circles meet at exactly one point. */
    fun touchesTarget(shot: ShotSolver.ShotSolution, target: Vec2, ballRadiusPx: Double): Boolean =
        abs(shot.ghostPoint.distanceTo(target) - 2 * ballRadiusPx) < 1e-6
}
