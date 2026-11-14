package com.poolguide.tool.geometry

import kotlin.math.sqrt

/**
 * Blocked-path check (v1.0.19). Pure Kotlin, no Android dependency —
 * fully unit-testable on the JVM.
 *
 * The solver's ghost-ball geometry assumes a clear path: the cue ball
 * travels cue -> ghost, the target ball travels target -> pocket, and
 * nothing sits in between. On a real table another ball often does,
 * and the tool used to draw the makeable-looking line anyway — the
 * player only found out when the potted ball smacked into the ball
 * in the way. This object is the single place that answers "is
 * another ball on this shot's path", for the shot the live overlay
 * shows (direct, one-rail bank, one-rail kick, two-rail bank,
 * two-rail kick). Combo and kiss shots are NOT checked in v1.0.19:
 * their potting chain has two struck balls and the second leg's
 * path is only defined once the first leg lands, so the check is a
 * later slice, not this one.
 *
 * The rule is the physics, not a heuristic: two balls of radius r
 * collide when their centres come within one diameter (2r) of each
 * other, so a ball whose centre is within 2r of a travelled
 * segment is on the path. Exactly 2r is a graze, not a hit, so the
 * test is strict (< 2r). Detection noise (~0.4 px on the synthetic
 * tables) can flip the verdict for a ball sitting within a pixel or
 * two of the boundary — the flag is a warning about the detected
 * positions, not a measurement of the real table.
 *
 * v1.0.19 only FLAGS a blocked shot (overlay label, status line,
 * shot history). v1.0.21 re-ranks DIRECT shots: the live pipeline
 * shows the first CLEAR direct shot in cut order ([firstClearShot]),
 * so a blocked easiest pocket no longer hides a makeable one. When
 * every direct shot is blocked the best stays and is flagged, as
 * before — the demo tables prove it: on the green and blue demo
 * tables every reachable pocket is blocked, so their shown shots
 * are unchanged. v1.0.22 re-ranks ONE-RAIL banks and kicks the
 * same way ([firstClearBank], [firstClearKick]): when no direct
 * shot solves, the shown one-rail shot is the first clear bank
 * or kick in cut order, and the bank-vs-kick choice compares
 * the two first-clear cuts, so the easiest makeable one-rail
 * shot wins. Two-rail banks/kicks, combo, and kiss shots are
 * still flag-only: their fallbacks return a single best shot,
 * not a ranked list, so there is nothing to re-rank within
 * the family.
 */
object PathBlock {

    /** Distance from [point] to the segment [from] -> [to], clamped
     * to the segment: a ball behind the cue or past the pocket does
     * not block the shot. A zero-length segment measures the
     * distance to its single point. */
    fun distancePointToSegment(point: Vec2, from: Vec2, to: Vec2): Double {
        val dx = to.x - from.x
        val dy = to.y - from.y
        val lenSq = dx * dx + dy * dy
        if (lenSq < 1e-12) return point.distanceTo(from)
        var t = ((point.x - from.x) * dx + (point.y - from.y) * dy) / lenSq
        t = t.coerceIn(0.0, 1.0)
        val nearest = Vec2(from.x + dx * t, from.y + dy * t)
        return point.distanceTo(nearest)
    }

    /** True when [blocker]'s centre is within one ball diameter
     * (2r) of the segment [from] -> [to]: the moving ball would hit
     * it. Exactly 2r is a graze and returns false. */
    fun segmentBlockedBy(
        from: Vec2,
        to: Vec2,
        blocker: Vec2,
        ballRadiusPx: Double
    ): Boolean {
        require(ballRadiusPx > 0) { "ballRadiusPx must be positive" }
        return distancePointToSegment(blocker, from, to) < 2.0 * ballRadiusPx
    }

    /** Distance along [segment] of the point on it nearest [point],
     * in pixels from the segment start (0 when the nearest point is
     * the start, the segment length when it is the end). Used to
     * order blockers along a path: the first ball the moving ball
     * meets is the one that matters. */
    fun alongDistance(point: Vec2, segment: Segment): Double {
        val dx = segment.end.x - segment.start.x
        val dy = segment.end.y - segment.start.y
        val lenSq = dx * dx + dy * dy
        if (lenSq < 1e-12) return 0.0
        val t = (((point.x - segment.start.x) * dx +
            (point.y - segment.start.y) * dy) / lenSq).coerceIn(0.0, 1.0)
        return t * sqrt(lenSq)
    }

    /** The first ball blocking any of [segments] (in path order),
     * or null when the path is clear. Within one segment the
     * blocker nearest the segment start wins; across segments the
     * earliest segment wins — the first ball the moving ball would
     * meet. [blockers] must be every ball EXCEPT the ones travelling
     * the path (the cue, the target, and for banks/kicks the balls
     * the path already accounts for); a travelling ball passed as a
     * blocker would flag its own path. */
    fun firstBlockerOnPath(
        segments: List<Segment>,
        blockers: List<Vec2>,
        ballRadiusPx: Double
    ): Vec2? {
        require(ballRadiusPx > 0) { "ballRadiusPx must be positive" }
        for (segment in segments) {
            var best: Vec2? = null
            var bestAlong = Double.MAX_VALUE
            for (blocker in blockers) {
                if (!segmentBlockedBy(segment.start, segment.end, blocker, ballRadiusPx)) continue
                val along = alongDistance(blocker, segment)
                if (along < bestAlong) {
                    bestAlong = along
                    best = blocker
                }
            }
            if (best != null) return best
        }
        return null
    }

    /** Direct shot: the cue travels the aim line (cue -> ghost),
     * the target travels the target line (target -> pocket). */
    fun blockedBy(
        shot: ShotSolver.ShotSolution,
        blockers: List<Vec2>,
        ballRadiusPx: Double
    ): Vec2? = firstBlockerOnPath(
        listOf(shot.aimLine, shot.targetLine), blockers, ballRadiusPx
    )

    /** First CLEAR direct shot in [shots] (v1.0.21): the first shot
     * whose path no other ball sits on, in the given order — the
     * caller passes the cut-ranked [ShotSolver.allShots] list, so
     * this is the easiest makeable pocket. Returns null when every
     * shot is blocked (the caller then keeps the best and flags it)
     * or when [shots] is empty. [blockers] must be every ball
     * EXCEPT the ones travelling the path (the cue and the target);
     * a travelling ball passed as a blocker would flag its own
     * path. */
    fun firstClearShot(
        shots: List<ShotSolver.ShotSolution>,
        blockers: List<Vec2>,
        ballRadiusPx: Double
    ): ShotSolver.ShotSolution? {
        require(ballRadiusPx > 0) { "ballRadiusPx must be positive" }
        return shots.firstOrNull { blockedBy(it, blockers, ballRadiusPx) == null }
    }

    /** First CLEAR one-rail bank in [banks] (v1.0.22): the first
     * bank whose path no other ball sits on, in the given order.
     * The caller passes the cut-ranked [ShotSolver.allBanks]
     * list, so this is the easiest makeable bank. Returns null
     * when every bank is blocked (the caller then keeps the best
     * and flags it) or when [banks] is empty. [blockers] must be
     * every ball EXCEPT the ones travelling the path (the cue
     * and the target); a travelling ball passed as a blocker
     * would flag its own path. */
    fun firstClearBank(
        banks: List<ShotSolver.BankSolution>,
        blockers: List<Vec2>,
        ballRadiusPx: Double
    ): ShotSolver.BankSolution? {
        require(ballRadiusPx > 0) { "ballRadiusPx must be positive" }
        return banks.firstOrNull { blockedBy(it, blockers, ballRadiusPx) == null }
    }

    /** First CLEAR one-rail kick in [kicks] (v1.0.22): the first
     * kick whose path no other ball sits on, in the given order.
     * The caller passes the cut-ranked [ShotSolver.allKicks]
     * list, so this is the easiest makeable kick. Returns null
     * when every kick is blocked (the caller then keeps the best
     * and flags it) or when [kicks] is empty. Same blocker rule
     * as [firstClearBank]. */
    fun firstClearKick(
        kicks: List<ShotSolver.KickSolution>,
        blockers: List<Vec2>,
        ballRadiusPx: Double
    ): ShotSolver.KickSolution? {
        require(ballRadiusPx > 0) { "ballRadiusPx must be positive" }
        return kicks.firstOrNull { blockedBy(it, blockers, ballRadiusPx) == null }
    }

    /** One-rail bank: the cue travels the aim line, the target
     * travels the bank path (target -> rail -> pocket). */
    fun blockedBy(
        bank: ShotSolver.BankSolution,
        blockers: List<Vec2>,
        ballRadiusPx: Double
    ): Vec2? = firstBlockerOnPath(
        listOf(bank.direct.aimLine) + bank.bankPath, blockers, ballRadiusPx
    )

    /** Two-rail bank: the cue travels the aim line, the target
     * travels the three-segment bank path. */
    fun blockedBy(
        bank: ShotSolver.TwoRailBankSolution,
        blockers: List<Vec2>,
        ballRadiusPx: Double
    ): Vec2? = firstBlockerOnPath(
        listOf(bank.direct.aimLine) + bank.bankPath, blockers, ballRadiusPx
    )

    /** One-rail kick: the cue travels the kick path (cue -> rail ->
     * ghost), the target travels the target line. The direct
     * solve's aim line is the straight cue -> ghost chord, NOT the
     * travelled path, so it is not checked. */
    fun blockedBy(
        kick: ShotSolver.KickSolution,
        blockers: List<Vec2>,
        ballRadiusPx: Double
    ): Vec2? = firstBlockerOnPath(
        kick.kickPath + listOf(kick.direct.targetLine), blockers, ballRadiusPx
    )

    /** Two-rail kick: the cue travels the three-segment kick path,
     * the target travels the target line. */
    fun blockedBy(
        kick: ShotSolver.TwoRailKickSolution,
        blockers: List<Vec2>,
        ballRadiusPx: Double
    ): Vec2? = firstBlockerOnPath(
        kick.kickPath + listOf(kick.direct.targetLine), blockers, ballRadiusPx
    )
}
