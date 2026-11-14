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
 * shot wins. v1.0.23 re-ranks TWO-RAIL banks and kicks the
 * same way ([firstClearTwoRailBank], [firstClearTwoRailKick]):
 * when no one-rail shot solves either, the shown two-rail shot
 * is the first clear one in cut order. The two-rail bank is
 * still tried before the two-rail kick (a two-rail kick
 * compounds real-table error twice, so it stays the last resort
 * before combo, as before). The re-ranking is WITHIN each
 * family, not between them. v1.0.25 re-ranks COMBO and KISS shots
 * the same way ([firstClearCombo], [firstClearKiss]): when no
 * two-rail shot solves either, the shown combo or kiss is the
 * first clear one in cut order. The re-ranking is WITHIN each
 * family, not between them: a combo is still tried before a kiss,
 * and neither displaces any single-ball shot. Each candidate's
 * own second ball is removed from its blocker list (it sits at
 * the start of its own target line); another candidate's second
 * ball stays, because it is a real ball on the table.
 * v1.0.24 FLAGS combo and kiss shots too ([blockedBy] overloads
 * below): the potting chain has two struck balls, so the check
 * has its own definition per family: the cue's path, the
 * first/kissed ball's path, and the potted ball's path, and
 * the travelling balls are never passed as blockers, or they
 * would flag their own path (the second ball sits at the start
 * of its own target line; the first ball sits on the second
 * leg's aim line).
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

    /** First CLEAR two-rail bank in [banks] (v1.0.23): the first
     * bank whose path no other ball sits on, in the given order.
     * The caller passes the cut-ranked [ShotSolver.allTwoRailBanks]
     * list, so this is the easiest makeable two-rail bank. Returns
     * null when every bank is blocked (the caller then keeps the
     * best and flags it) or when [banks] is empty. Same blocker
     * rule as [firstClearBank]. */
    fun firstClearTwoRailBank(
        banks: List<ShotSolver.TwoRailBankSolution>,
        blockers: List<Vec2>,
        ballRadiusPx: Double
    ): ShotSolver.TwoRailBankSolution? {
        require(ballRadiusPx > 0) { "ballRadiusPx must be positive" }
        return banks.firstOrNull { blockedBy(it, blockers, ballRadiusPx) == null }
    }

    /** First CLEAR two-rail kick in [kicks] (v1.0.23): the first
     * kick whose path no other ball sits on, in the given order.
     * The caller passes the cut-ranked [ShotSolver.allTwoRailKicks]
     * list, so this is the easiest makeable two-rail kick. Returns
     * null when every kick is blocked (the caller then keeps the
     * best and flags it) or when [kicks] is empty. Same blocker
     * rule as [firstClearBank]. */
    fun firstClearTwoRailKick(
        kicks: List<ShotSolver.TwoRailKickSolution>,
        blockers: List<Vec2>,
        ballRadiusPx: Double
    ): ShotSolver.TwoRailKickSolution? {
        require(ballRadiusPx > 0) { "ballRadiusPx must be positive" }
        return kicks.firstOrNull { blockedBy(it, blockers, ballRadiusPx) == null }
    }

    /** First CLEAR combo in [combos] (v1.0.25): the first combo
     * whose potting chain no other ball sits on, in the given
     * order. The caller passes the cut-ranked
     * [ShotSolver.allCombos] list, so this is the easiest
     * makeable combo. Returns null when every combo is blocked
     * (the caller then keeps the best and flags it) or when
     * [combos] is empty. [blockers] is every ball EXCEPT the cue
     * and the first ball (the pipeline's `others` list, which
     * includes the candidate second balls): each candidate's own
     * second ball is removed here, because it sits at the start
     * of its own target line and would flag its own path (see
     * [blockedBy] for combos and comboBlockedBy in LiveFallback).
     * Another candidate's second ball is NOT removed: it is a
     * real ball on the table, and it blocks the chain exactly
     * like any other ball when it sits on it. */
    fun firstClearCombo(
        combos: List<ShotSolver.ComboSolution>,
        blockers: List<Vec2>,
        ballRadiusPx: Double
    ): ShotSolver.ComboSolution? {
        require(ballRadiusPx > 0) { "ballRadiusPx must be positive" }
        return combos.firstOrNull { combo ->
            val second = combo.secondShot.targetLine.start
            blockedBy(combo, blockers.filter { it != second }, ballRadiusPx) == null
        }
    }

    /** First CLEAR kiss in [kisses] (v1.0.25): the first kiss whose
     * potting chain no other ball sits on, in the given order. The
     * caller passes the cut-ranked [ShotSolver.allKisses] list, so
     * this is the easiest makeable kiss. Returns null when every
     * kiss is blocked (the caller then keeps the best and flags it)
     * or when [kisses] is empty. Same blocker rule as
     * [firstClearCombo]: each candidate's own second ball is
     * removed (it sits at the start of its own target line), and
     * another candidate's second ball stays, because it is a real
     * ball on the table. The kissed ball is never in [blockers]
     * (the pipeline's `others` list excludes it), and the kissed
     * ball's own departure is not on the potting chain (see
     * [blockedBy] for kisses and kissBlockedBy in LiveFallback). */
    fun firstClearKiss(
        kisses: List<ShotSolver.KissSolution>,
        blockers: List<Vec2>,
        ballRadiusPx: Double
    ): ShotSolver.KissSolution? {
        require(ballRadiusPx > 0) { "ballRadiusPx must be positive" }
        return kisses.firstOrNull { kiss ->
            val second = kiss.secondShot.targetLine.start
            blockedBy(kiss, blockers.filter { it != second }, ballRadiusPx) == null
        }
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

    /** Combo (v1.0.24): three struck balls, three travelled legs.
     * The cue travels the first leg's aim line (cue -> first
     * ghost); the first ball travels its target line (first ball
     * -> second ball's ghost point, the intermediate ghost. The
     * first leg is solved against the intermediate ghost, so its
     * target line IS the first ball's path); and the second ball
     * travels the second leg's target line (second ball ->
     * pocket). [blockers] must be every ball EXCEPT the cue, the
     * first ball, and the second ball: the second ball sits at
     * the start of its own target line and the first ball sits
     * on the second leg's aim line, so passing either as a
     * blocker would flag its own path. */
    fun blockedBy(
        combo: ShotSolver.ComboSolution,
        blockers: List<Vec2>,
        ballRadiusPx: Double
    ): Vec2? = firstBlockerOnPath(
        listOf(
            combo.firstShot.aimLine,
            combo.firstShot.targetLine,
            combo.secondShot.targetLine
        ),
        blockers, ballRadiusPx
    )

    /** Kiss (v1.0.24): the cue travels the kiss leg's aim line
     * (cue -> kiss ghost), then deflects along the second leg's
     * aim line (kiss ghost -> second ball's ghost point), and
     * the second ball travels the second leg's target line
     * (second ball -> pocket). The kissed ball's own departure
     * line is NOT checked: it pots nothing and its departure is
     * not on the potting chain. The kissed ball only matters if
     * it sits on one of the three checked legs, in which case it
     * is flagged as a blocker like any other ball. [blockers]
     * must be every ball EXCEPT the cue, the kissed ball, and
     * the second ball: all three travel the chain, and a
     * travelling ball passed as a blocker would flag its own
     * path (the second ball sits at the start of its own target
     * line). The kissed ball sits exactly one diameter from the
     * kiss ghost, on the boundary of the deflected leg. The
     * probe layout measures that distance at 18.000 px for
     * r = 9.0, so including it would make the verdict depend on
     * sub-pixel refinement noise; it is excluded, like the
     * other travelling balls. */
    fun blockedBy(
        kiss: ShotSolver.KissSolution,
        blockers: List<Vec2>,
        ballRadiusPx: Double
    ): Vec2? = firstBlockerOnPath(
        listOf(
            kiss.kissShot.aimLine,
            kiss.secondShot.aimLine,
            kiss.secondShot.targetLine
        ),
        blockers, ballRadiusPx
    )
}
