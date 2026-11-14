package com.poolguide.tool.geometry

import kotlin.math.abs
import kotlin.math.acos

/**
 * Shot solver — standard ghost-ball geometry (no spin, no friction throw,
 * no cushion compression; equal-mass elastic collision).
 *
 * Formulas (ChatGPT-verified, standard pool physics):
 *   u  = (P - T) / |P - T|          pocket direction from the target ball
 *   G  = T - 2r * u                 ghost point (cue-ball centre at impact)
 *   a  = (G - C) / |G - C|          aim direction
 *   theta = acos(a . u)             cut angle
 *   v  = a - (a . u) * u            cue-ball deflection (tangent), normalised
 *   P' = P - 2 * ((P - Q) . n) * n  mirror pocket across a rail
 *   d_out = d_in - 2 * (d_in . n) * n   reflection
 *
 * Pure Kotlin, no Android dependency — fully unit-testable on the JVM.
 */
object ShotSolver {

    /** Cut angle above this is a thin cut with very low margin. */
    const val THIN_CUT_DEGREES = 80.0

    /** Cut angles within this of each other count as a tie: the first-listed
     * pocket wins, so sub-degree detection noise cannot flip a symmetric
     * layout (e.g. the synthetic green table, where the top-right and
     * bottom-right pockets tie at ~30 degrees — the depicted shot is the
     * top-right one, listed first). */
    const val TIE_MARGIN_DEGREES = 1.0

    /** Best direct shot across [pockets]: the smallest cut angle, with the
     * first-listed pocket winning ties within [TIE_MARGIN_DEGREES].
     * Returns null when no pocket is reachable. */
    fun bestShot(
        cue: Vec2,
        target: Vec2,
        pockets: List<Vec2>,
        ballRadiusPx: Double
    ): ShotSolution? = allShots(cue, target, pockets, ballRadiusPx).firstOrNull()

    /** Every reachable direct shot across [pockets], best (smallest cut
     * angle) first. Pockets tied within [TIE_MARGIN_DEGREES] keep their
     * list order, so the first-listed pocket still wins a symmetric tie
     * (PoolAimAssist UX: draw all candidates faint, highlight the best).
     * The sort is stable, so equal keys keep pocket order. */
    fun allShots(
        cue: Vec2,
        target: Vec2,
        pockets: List<Vec2>,
        ballRadiusPx: Double
    ): List<ShotSolution> {
        val solved = pockets.mapNotNull { solveShot(cue, target, it, ballRadiusPx) }
        return solved.sortedWith(Comparator { a, b ->
            when {
                a.cutAngleDegrees < b.cutAngleDegrees - TIE_MARGIN_DEGREES -> -1
                b.cutAngleDegrees < a.cutAngleDegrees - TIE_MARGIN_DEGREES -> 1
                else -> 0
            }
        })
    }

    /** Compare two cut angles with the tie margin (shared by the
     * ranked-list functions): within [TIE_MARGIN_DEGREES] the two
     * shots tie and list order decides, so sub-degree detection
     * noise cannot flip a symmetric layout. */
    private fun cutOrder(a: Double, b: Double): Int = when {
        a < b - TIE_MARGIN_DEGREES -> -1
        b < a - TIE_MARGIN_DEGREES -> 1
        else -> 0
    }

    /** Every reachable one-rail bank across [pockets] and [rails],
     * best (smallest direct cut angle) first. The same ranking
     * as [allShots], so the live pipeline can re-rank banks the
     * way v1.0.21 re-ranked direct shots. The one-rail bank
     * fallback applies no corner guards (unchanged from v1.0.3).
     * The sort is stable, so equal keys keep pocket/rail order. */
    fun allBanks(
        cue: Vec2,
        target: Vec2,
        pockets: List<Vec2>,
        rails: List<Rail>,
        ballRadiusPx: Double
    ): List<BankSolution> {
        val solved = pockets.flatMap { pocket ->
            rails.mapNotNull { solveBank(cue, target, pocket, it, ballRadiusPx) }
        }
        return solved.sortedWith(Comparator { a, b ->
            cutOrder(a.direct.cutAngleDegrees, b.direct.cutAngleDegrees)
        })
    }

    /** Every reachable one-rail kick across [pockets] and [rails],
     * best (smallest direct cut angle) first. The same ranking
     * as [allShots]. [isValidContact] is the corner guard the
     * live fallback applies to a kick's rail contact (inside the
     * rail span, four ball radii clear of the corners, not on the
     * pocket): the geometry module takes it as a predicate so the
     * table rect stays out of the solver. The sort is stable, so
     * equal keys keep pocket/rail order. */
    fun allKicks(
        cue: Vec2,
        target: Vec2,
        pockets: List<Vec2>,
        rails: List<Rail>,
        ballRadiusPx: Double,
        isValidContact: (contact: Vec2, railIndex: Int, pocket: Vec2) -> Boolean
    ): List<KickSolution> {
        val solved = pockets.flatMap { pocket ->
            rails.mapIndexedNotNull { index, rail ->
                val kick = solveKick(cue, target, pocket, rail, ballRadiusPx)
                    ?: return@mapIndexedNotNull null
                if (!isValidContact(kick.railContact, index, pocket)) null else kick
            }
        }
        return solved.sortedWith(Comparator { a, b ->
            cutOrder(a.direct.cutAngleDegrees, b.direct.cutAngleDegrees)
        })
    }

    /** Every reachable two-rail bank across [pockets] and every
     * ordered pair of distinct [rails], best (smallest direct cut
     * angle) first. The same ranking as [allShots], so the live
     * pipeline can re-rank two-rail banks the way v1.0.21
     * re-ranked direct shots and v1.0.22 re-ranked one-rail banks.
     * [isValidContact] is the corner guard the live fallback
     * applies to each contact (inside the rail span, four ball
     * radii clear of the corners, not on the pocket): the geometry
     * module takes it as a predicate so the table rect stays out
     * of the solver. A pocket on either rail is skipped by the
     * solver itself (it returns null there), and same-rail pairs
     * are skipped here. The sort is stable, so equal keys keep
     * pocket/rail order. */
    fun allTwoRailBanks(
        cue: Vec2,
        target: Vec2,
        pockets: List<Vec2>,
        rails: List<Rail>,
        ballRadiusPx: Double,
        isValidContact: (contact: Vec2, railIndex: Int, pocket: Vec2) -> Boolean
    ): List<TwoRailBankSolution> {
        val solved = pockets.flatMap { pocket ->
            rails.flatMapIndexed { index1, rail1 ->
                rails.mapIndexedNotNull { index2, rail2 ->
                    if (index1 == index2) return@mapIndexedNotNull null
                    val bank = solveTwoRailBank(
                        cue, target, pocket, rail1, rail2, ballRadiusPx
                    ) ?: return@mapIndexedNotNull null
                    if (!isValidContact(bank.railContacts[0], index1, pocket)) null
                    else if (!isValidContact(bank.railContacts[1], index2, pocket)) null
                    else bank
                }
            }
        }
        return solved.sortedWith(Comparator { a, b ->
            cutOrder(a.direct.cutAngleDegrees, b.direct.cutAngleDegrees)
        })
    }

    /** Every reachable two-rail kick across [pockets] and every
     * ordered pair of distinct [rails], best (smallest direct cut
     * angle) first. The same ranking as [allKicks]. [isValidContact]
     * is the corner guard the live fallback applies to each
     * contact (inside the rail span, four ball radii clear of the
     * corners, not on the pocket). A ghost on either rail is
     * skipped by the solver itself (it returns null there), and
     * same-rail pairs are skipped here. The sort is stable, so
     * equal keys keep pocket/rail order. */
    fun allTwoRailKicks(
        cue: Vec2,
        target: Vec2,
        pockets: List<Vec2>,
        rails: List<Rail>,
        ballRadiusPx: Double,
        isValidContact: (contact: Vec2, railIndex: Int, pocket: Vec2) -> Boolean
    ): List<TwoRailKickSolution> {
        val solved = pockets.flatMap { pocket ->
            rails.flatMapIndexed { index1, rail1 ->
                rails.mapIndexedNotNull { index2, rail2 ->
                    if (index1 == index2) return@mapIndexedNotNull null
                    val kick = solveTwoRailKick(
                        cue, target, pocket, rail1, rail2, ballRadiusPx
                    ) ?: return@mapIndexedNotNull null
                    if (!isValidContact(kick.railContacts[0], index1, pocket)) null
                    else if (!isValidContact(kick.railContacts[1], index2, pocket)) null
                    else kick
                }
            }
        }
        return solved.sortedWith(Comparator { a, b ->
            cutOrder(a.direct.cutAngleDegrees, b.direct.cutAngleDegrees)
        })
    }

    /** Result of solving a combo shot: the cue strikes [firstShot]'s
     * target, which strikes [secondShot]'s target into the pocket. */
    data class ComboSolution(
        /** Cue -> first ball; the first ball departs toward [intermediateGhost]. */
        val firstShot: ShotSolution,
        /** First ball (at [firstShot]'s ghost point) -> second ball -> pocket. */
        val secondShot: ShotSolution,
        /** Where the first ball must be at impact with the second ball:
         * the second ball's ghost point for the pocket. */
        val intermediateGhost: Vec2
    )

    /**
     * Every reachable combo across every second ball and every pocket:
     * cue -> [first] -> [second] -> pocket, ranked by the SECOND
     * shot's cut angle (easiest pot first), the same ranking
     * [bestCombo] uses. The first leg's cut is not ranked: it is
     * the setup, the pot is the second leg. The sort is stable, so
     * equal keys keep second-ball/pocket order. The live pipeline
     * re-ranks combos the way v1.0.21 re-ranked direct shots (see
     * [PathBlock.firstClearCombo]).
     */
    fun allCombos(
        cue: Vec2,
        first: Vec2,
        seconds: List<Vec2>,
        pockets: List<Vec2>,
        ballRadiusPx: Double
    ): List<ComboSolution> {
        val solved = seconds.flatMap { second ->
            pockets.mapNotNull { solveCombo(cue, first, second, it, ballRadiusPx) }
        }
        return solved.sortedWith(Comparator { a, b ->
            cutOrder(
                a.secondShot.cutAngleDegrees, b.secondShot.cutAngleDegrees
            )
        })
    }

    /**
     * Best combo across every second ball and every pocket: cue ->
     * [first] -> [second] -> pocket, ranked by the SECOND shot's cut
     * angle (easiest pot first), the same ranking practice mode
     * uses (PracticeState.solveCombo). The first leg's cut is not
     * ranked: it is the setup, the pot is the second leg. Returns
     * null when no second ball pots into any pocket.
     *
     * Live-overlay ranking (v1.0.13): a combo is the last resort
     * before a kiss — the pipeline only tries it after direct,
     * one-rail bank, one-rail kick, two-rail bank, and two-rail
     * kick all fail (see CvPipeline.solveFor), so it never
     * displaces any other shot. The full order, best first, is:
     * direct > one-rail bank > one-rail kick > two-rail bank >
     * two-rail kick > combo > kiss.
     */
    fun bestCombo(
        cue: Vec2,
        first: Vec2,
        seconds: List<Vec2>,
        pockets: List<Vec2>,
        ballRadiusPx: Double
    ): ComboSolution? = allCombos(cue, first, seconds, pockets, ballRadiusPx)
        .firstOrNull()

    /**
     * Every reachable kiss across every second ball and every pocket:
     * cue kisses [kissed] thin and deflects onto [second] -> pocket,
     * ranked by the SECOND shot's cut angle (easiest pot first),
     * the same ranking [bestKiss] uses. The sort is stable, so equal
     * keys keep second-ball/pocket order. The live pipeline re-ranks
     * kisses the way v1.0.21 re-ranked direct shots (see
     * [PathBlock.firstClearKiss]).
     */
    fun allKisses(
        cue: Vec2,
        kissed: Vec2,
        seconds: List<Vec2>,
        pockets: List<Vec2>,
        ballRadiusPx: Double
    ): List<KissSolution> {
        val solved = seconds.flatMap { second ->
            pockets.mapNotNull { solveKiss(cue, kissed, second, it, ballRadiusPx) }
        }
        return solved.sortedWith(Comparator { a, b ->
            cutOrder(
                a.secondShot.cutAngleDegrees, b.secondShot.cutAngleDegrees
            )
        })
    }

    /**
     * Best kiss across every second ball and every pocket: cue kisses
     * [kissed] thin and deflects onto [second] -> pocket, ranked by
     * the SECOND shot's cut angle (easiest pot first), the same
     * ranking practice mode uses (PracticeState.solveKiss).
     *
     * Live-overlay ranking (v1.0.13): a kiss is the last resort of
     * all — the pipeline only tries it after a combo fails too
     * (see CvPipeline.solveFor), so it never displaces a combo or
     * any other shot. The full order, best first, is: direct >
     * one-rail bank > one-rail kick > two-rail bank > two-rail
     * kick > combo > kiss.
     */
    fun bestKiss(
        cue: Vec2,
        kissed: Vec2,
        seconds: List<Vec2>,
        pockets: List<Vec2>,
        ballRadiusPx: Double
    ): KissSolution? = allKisses(cue, kissed, seconds, pockets, ballRadiusPx)
        .firstOrNull()

    /** Result of solving a kiss shot: the cue kisses off the first ball
     * (thin cut) and its deflection carries it into the second ball's
     * ghost point, potting the second ball. */
    data class KissSolution(
        /** Cue -> kissed ball; [ShotSolution.targetLine] is the kissed
         * ball's departure line (it pots nothing — it just moves away),
         * and [ShotSolution.deflectionLine] is the cue's refined
         * post-kiss direction toward [secondShot]'s ghost point. */
        val kissShot: ShotSolution,
        /** Cue (at the kiss ghost point) -> target ball -> pocket. */
        val secondShot: ShotSolution,
        /** Where the cue must be at impact with the kissed ball. */
        val ghostPoint: Vec2,
        /** The contact point on the kissed ball. */
        val contactPoint: Vec2
    )

    /**
     * Solve a combo shot: cue -> [first] -> [second] -> pocket.
     *
     * Physics (yasin/Aim ComboShot lead, closed form): a struck ball at
     * rest always departs along the line from its ghost-ball centre
     * through its own centre. So the second ball's ghost point for the
     * pocket, [intermediateGhost], is exactly where the first ball must
     * be at impact with it — and the first ball departs toward that
     * point when the cue strikes it with the contact normal pointing
     * from [intermediateGhost] through the first ball. That is just
     * [solveShot] with the pocket replaced by [intermediateGhost].
     * The second leg is then [solveShot] from the first ball's ghost
     * point; its aim direction is the first ball's departure direction
     * by construction (the ghost point sits one diameter behind the
     * first ball on the same line), so no extra check is needed.
     * Returns null when either leg is impossible.
     */
    fun solveCombo(
        cue: Vec2,
        first: Vec2,
        second: Vec2,
        pocket: Vec2,
        ballRadiusPx: Double
    ): ComboSolution? {
        require(ballRadiusPx > 0) { "ballRadiusPx must be positive" }
        if (cue.distanceTo(first) < 2 * ballRadiusPx + 0.5) return null
        if (first.distanceTo(second) < 2 * ballRadiusPx + 0.5) return null
        val intermediate = ghostPoint(second, pocket, ballRadiusPx)
        val firstShot = solveShot(cue, first, intermediate, ballRadiusPx) ?: return null
        val secondShot = solveShot(firstShot.ghostPoint, second, pocket, ballRadiusPx)
            ?: return null
        return ComboSolution(firstShot, secondShot, intermediate)
    }

    /** Kiss side selection: auto (pick the reachable side), or lock one. */
    const val KISS_SIDE_AUTO = 0
    const val KISS_SIDE_LEFT = 1
    const val KISS_SIDE_RIGHT = 2

    /** Ball elasticity used by the kiss refinement (yasin lead: the game
     * engine's chipmunk solver uses 0.95 on every ball). */
    private const val KISS_ELASTICITY = 0.95

    /** Bisection bracket (degrees) and steps for the kiss refinement. */
    private const val KISS_REFINE_BRACKET_DEG = 30.0
    private const val KISS_REFINE_STEPS = 24

    /**
     * Solve a kiss shot: the cue kisses off [kissed] (thin cut) and its
     * deflection carries it into [target]'s ghost point for [pocket],
     * potting [target].
     *
     * Geometry (yasin/Aim KissShot lead, re-derived here): the ghost
     * point for the cue must sit one diameter from [kissed] along a
     * contact normal n, where n makes angle theta = acos(2r / d) with
     * the kissed -> DEST direction (d = distance kissed -> DEST, DEST =
     * [target]'s ghost point). Two mirror solutions exist (alpha +/-);
     * a side is reachable only when the cue approaches from the -n side.
     * At elasticity 1 the deflection is exactly perpendicular to n; at
     * the real 0.95 there is a small residual along n, so the closed
     * form is refined by bisection on the residual (the deflected ray
     * must pass through DEST). No throw model: the solver has no spin
     * input, so the throw correction is 0.
     * Returns null when DEST sits inside the kissed ball, when no
     * contact point is on the cue's approach side, or when either leg
     * is impossible.
     */
    fun solveKiss(
        cue: Vec2,
        kissed: Vec2,
        target: Vec2,
        pocket: Vec2,
        ballRadiusPx: Double,
        sideLock: Int = KISS_SIDE_AUTO
    ): KissSolution? {
        require(ballRadiusPx > 0) { "ballRadiusPx must be positive" }
        val r = ballRadiusPx
        val dest = ghostPoint(target, pocket, r)
        val dx = dest.x - kissed.x
        val dy = dest.y - kissed.y
        val d = kotlin.math.sqrt(dx * dx + dy * dy)
        if (d < 2 * r) return null // DEST inside the kissed ball's footprint

        val theta = acos((2 * r / d).coerceIn(-1.0, 1.0))
        val phi = kotlin.math.atan2(dy, dx)
        val alphaA = phi + theta
        val alphaB = phi - theta

        val approach = kissed - cue
        val approachLen = approach.norm()
        val haveApproach = approachLen > 1.0
        val v1 = if (haveApproach) approach * (1.0 / approachLen) else Vec2(0.0, 0.0)
        fun reachable(a: Double): Boolean {
            if (!haveApproach) return true
            return v1.x * kotlin.math.cos(a) + v1.y * kotlin.math.sin(a) < 0.0
        }
        // Pick a side: locked, the only reachable one, or (both reachable)
        // the side on the same side of the kissed->DEST line as the cue.
        var alpha: Double
        when (sideLock) {
            KISS_SIDE_LEFT -> alpha = alphaA
            KISS_SIDE_RIGHT -> alpha = alphaB
            else -> {
                val okA = reachable(alphaA)
                val okB = reachable(alphaB)
                when {
                    okA && !okB -> alpha = alphaA
                    okB && !okA -> alpha = alphaB
                    !okA && !okB -> return null // impossible from this approach
                    else -> {
                        val sideRef = dx * (cue.y - kissed.y) - dy * (cue.x - kissed.x)
                        val crossA = dx * kotlin.math.sin(alphaA) - dy * kotlin.math.cos(alphaA)
                        alpha = if ((crossA >= 0.0) == (sideRef >= 0.0)) alphaA else alphaB
                    }
                }
            }
        }

        // Refine against the real elasticity (e = 0.95): the deflected
        // ray from the ghost point must pass through DEST. Bisection on
        // the residual, only when the bracket straddles a root.
        if (haveApproach) {
            val k = (1.0 + KISS_ELASTICITY) / 2.0
            fun residual(a: Double): Double {
                val nx = kotlin.math.cos(a)
                val ny = kotlin.math.sin(a)
                val dot = v1.x * nx + v1.y * ny
                val outX = v1.x - nx * dot * k
                val outY = v1.y - ny * dot * k
                val gx = kissed.x + nx * 2 * r
                val gy = kissed.y + ny * 2 * r
                return outX * (dest.y - gy) - outY * (dest.x - gx)
            }
            val bracket = Math.toRadians(KISS_REFINE_BRACKET_DEG)
            var lo = alpha - bracket
            var hi = alpha + bracket
            var fLo = residual(lo)
            val fHi = residual(hi)
            if (fLo * fHi < 0.0) {
                repeat(KISS_REFINE_STEPS) {
                    val mid = (lo + hi) / 2.0
                    val fMid = residual(mid)
                    if ((fMid >= 0.0) == (fLo >= 0.0)) {
                        lo = mid
                        fLo = fMid
                    } else {
                        hi = mid
                    }
                }
                alpha = (lo + hi) / 2.0
            }
        }

        val nx = kotlin.math.cos(alpha)
        val ny = kotlin.math.sin(alpha)
        val ghost = Vec2(kissed.x + nx * 2 * r, kissed.y + ny * 2 * r)
        val contact = Vec2(kissed.x + nx * r, kissed.y + ny * r)

        // Refined deflection direction (e = 0.95) from the ghost point.
        // Guards: a near-head-on kiss leaves the cue almost stopped
        // (tiny out-vector — no usable deflection), and the bisection
        // root can be anti-parallel (the ray passes through DEST but
        // points away from it) — both are impossible kisses.
        val dot = v1.x * nx + v1.y * ny
        val k = (1.0 + KISS_ELASTICITY) / 2.0
        val outVec = if (haveApproach) {
            Vec2(v1.x - nx * dot * k, v1.y - ny * dot * k)
        } else {
            Vec2(0.0, 0.0)
        }
        if (haveApproach && outVec.norm() < 1e-6) return null // cue stops dead
        val outDir = if (haveApproach) outVec.normalized() else Vec2(0.0, 0.0)
        if (haveApproach) {
            // The deflected ray must head toward DEST, not away from it.
            val toDest = (dest - ghost).normalized()
            if (outDir.dot(toDest) <= 0.0) return null
            // ... and it must actually strike the target: the ray from
            // the ghost point must pass within one diameter of the
            // target centre, with the target in front of the ghost.
            val toTarget = target - ghost
            if (toTarget.dot(outDir) < 0.0) return null
            val rayDist = kotlin.math.abs(toTarget.x * outDir.y - toTarget.y * outDir.x)
            if (rayDist >= 2 * r) return null
        }

        // First leg: cue -> kissed ball, ghost point = [ghost]. Passing
        // (kissed - n) as the "pocket" makes solveShot's ghost land
        // exactly on [ghost].
        val kissShot = solveShot(cue, kissed, Vec2(kissed.x - nx, kissed.y - ny), r)
            ?: return null
        val kissShotRefined = if (haveApproach) {
            kissShot.copy(
                deflectionLine = Segment(ghost, ghost + outDir * (2 * r))
            )
        } else {
            kissShot
        }
        // Second leg: cue (at the kiss ghost point) -> target -> pocket.
        val secondShot = solveShot(ghost, target, pocket, r) ?: return null
        return KissSolution(kissShotRefined, secondShot, ghost, contact)
    }

    /** Which one-rail fallback wins the live overlay when no direct
     * shot solves. */
    enum class OneRailFallback { BANK, KICK, NONE }

    /** Pick the one-rail fallback from the two candidates' direct cut
     * angles (null = that shot does not solve). Both are one-rail
     * shots ranked by the same number — the direct cut angle, the
     * codebase's established difficulty proxy — so the smaller cut
     * wins; a tie goes to the bank, the established fallback. A kick
     * never displaces a direct shot: the pipeline only calls this
     * after allShots comes back empty. */
    fun pickOneRailFallback(bankCut: Double?, kickCut: Double?): OneRailFallback {
        return when {
            bankCut == null && kickCut == null -> OneRailFallback.NONE
            kickCut != null && (bankCut == null || kickCut < bankCut) -> OneRailFallback.KICK
            else -> OneRailFallback.BANK
        }
    }

    /** Result of solving one direct (no-bank) shot. */
    data class ShotSolution(
        /** Ghost-ball point: where the cue-ball centre must be at impact. */
        val ghostPoint: Vec2,
        /** Aim line: cue ball -> ghost point (the direction the cue points). */
        val aimLine: Segment,
        /** Target line: target ball -> pocket. */
        val targetLine: Segment,
        /** Cue-ball deflection after impact, from the ghost point. */
        val deflectionLine: Segment,
        /** Cut angle in degrees (0 = straight, 90 = maximum cut). */
        val cutAngleDegrees: Double,
        /** True when the cut is thinner than [THIN_CUT_DEGREES]. */
        val isThinCut: Boolean,
        /** Distance from cue ball to the ghost point (pixels). */
        val distanceToImpact: Double
    )

    /** Result of solving a one-rail bank shot. */
    data class BankSolution(
        val direct: ShotSolution,
        /** Where the target ball meets the cushion. */
        val railContact: Vec2,
        /** Target ball path: target -> rail contact -> pocket. */
        val bankPath: List<Segment>,
        /** The mirrored pocket used for the geometry. */
        val mirroredPocket: Vec2
    )

    /** Result of solving a two-rail bank shot. */
    data class TwoRailBankSolution(
        val direct: ShotSolution,
        /** Where the target ball meets the cushions, in path order:
         * [0] is the first rail hit, [1] the second. */
        val railContacts: List<Vec2>,
        /** Target ball path: target -> rail 1 contact -> rail 2 contact
         * -> pocket (three segments). */
        val bankPath: List<Segment>,
        /** The doubly mirrored pocket used for the geometry. */
        val mirroredPocket: Vec2
    )

    /** Result of solving a two-rail kick shot: the cue ball goes off
     * two rails BEFORE hitting the target (the mirror of a two-rail
     * bank, where the target ball banks instead). */
    data class TwoRailKickSolution(
        val direct: ShotSolution,
        /** Where the cue ball meets the cushions, in path order:
         * [0] is the first rail hit, [1] the second. */
        val railContacts: List<Vec2>,
        /** Cue-ball path: cue -> rail 1 contact -> rail 2 contact
         * -> ghost point (three segments). */
        val kickPath: List<Segment>,
        /** The doubly mirrored ghost point used for the geometry. */
        val mirroredGhost: Vec2
    )

    /** Result of solving a one-rail kick shot: the cue ball goes off
     * a rail BEFORE hitting the target (the mirror of a one-rail
     * bank, where the target ball banks instead). */
    data class KickSolution(
        val direct: ShotSolution,
        /** Where the cue ball meets the cushion. */
        val railContact: Vec2,
        /** Cue-ball path: cue -> rail contact -> ghost point
         * (two segments). */
        val kickPath: List<Segment>,
        /** The mirrored ghost point used for the geometry. */
        val mirroredGhost: Vec2
    )

    /**
     * Solve a direct shot. Returns null when the shot is impossible:
     * cut angle > 90 degrees (ghost point behind the target relative to the
     * cue), or the balls are already overlapping / at impact distance.
     */
    fun solveShot(cue: Vec2, target: Vec2, pocket: Vec2, ballRadiusPx: Double): ShotSolution? {
        require(ballRadiusPx > 0) { "ballRadiusPx must be positive" }
        if (cue.distanceTo(target) < 2 * ballRadiusPx + 0.5) return null // already at impact

        val u = (pocket - target).normalized()
        if (u.norm() < 1e-9) return null // target sits on the pocket
        val ghost = ghostPoint(target, pocket, ballRadiusPx)
        val aimDir = (ghost - cue).normalized()
        if (aimDir.norm() < 1e-9) return null

        val cosTheta = (aimDir.dot(u)).coerceIn(-1.0, 1.0)
        val cutAngle = Math.toDegrees(acos(cosTheta))
        if (cutAngle > 90.0) return null // impossible: would have to hit from the wrong side

        val tangent = tangentDirection(aimDir, u)
        val deflectLen = 2 * ballRadiusPx // draw 1-2 ball diameters
        return ShotSolution(
            ghostPoint = ghost,
            aimLine = Segment(cue, ghost),
            targetLine = Segment(target, pocket),
            deflectionLine = Segment(ghost, ghost + tangent * deflectLen),
            cutAngleDegrees = cutAngle,
            isThinCut = cutAngle > THIN_CUT_DEGREES,
            distanceToImpact = cue.distanceTo(ghost)
        )
    }

    /** Ghost point: the cue-ball centre position at the moment of impact. */
    fun ghostPoint(target: Vec2, pocket: Vec2, ballRadiusPx: Double): Vec2 {
        val u = (pocket - target).normalized()
        return target - u * (2 * ballRadiusPx)
    }

    /** Cut angle in degrees between the aim direction and the pocket direction. */
    fun cutAngleDegrees(aimDir: Vec2, pocketDir: Vec2): Double {
        val cosTheta = (aimDir.normalized().dot(pocketDir.normalized())).coerceIn(-1.0, 1.0)
        return Math.toDegrees(acos(cosTheta))
    }

    /**
     * Cue-ball deflection direction after a stun impact:
     * v = a - (a . u) * u, normalised. Zero vector (straight shot) is
     * returned as (0, 0) — the cue ball stops.
     */
    fun tangentDirection(aimDir: Vec2, pocketDir: Vec2): Vec2 {
        val a = aimDir.normalized()
        val u = pocketDir.normalized()
        val dot = a.dot(u)
        return (a - u * dot).normalized()
    }

    /** Mirror a point across a rail line: P' = P - 2 * ((P - Q) . n) * n. */
    fun mirrorPointAcrossRail(point: Vec2, rail: Rail): Vec2 {
        val d = (point - rail.point).dot(rail.normal)
        return point - rail.normal * (2 * d)
    }

    /** Reflect a direction across a rail: d_out = d_in - 2 * (d_in . n) * n. */
    fun reflectDirection(direction: Vec2, rail: Rail): Vec2 {
        val d = direction.normalized()
        return (d - rail.normal * (2 * d.dot(rail.normal))).normalized()
    }

    /**
     * Solve a one-rail bank: mirror the pocket across the rail, then solve a
     * direct shot against the mirrored pocket. Returns null when the bank
     * path does not actually cross the rail segment (checked by the caller
     * against the table bounds) or the direct solve fails.
     *
     * Cushion correction (v1.0.3, Bloro form): the ideal mirror assumes
     * a perfect reflection; the real cushion does not reflect perfectly.
     * [correction] is a polynomial in the incident angle (degrees,
     * between the ideal outgoing direction and the rail) that adjusts
     * the reflected angle. With the fitted coefficients the correction
     * is zero and the ideal bank is returned unchanged. With a non-zero
     * correction the contact point is re-solved by bisection along the
     * rail so the corrected outgoing ray still reaches the pocket, and
     * the aim is re-solved against the corrected contact. When the
     * correction cannot be satisfied (no sign change in the bracket,
     * or the corrected aim is impossible), the ideal bank is returned:
     * a slightly-off bank line is better than no bank line.
     */
    fun solveBank(
        cue: Vec2,
        target: Vec2,
        pocket: Vec2,
        rail: Rail,
        ballRadiusPx: Double,
        correction: CushionCorrection = CushionCorrection.FITTED
    ): BankSolution? {
        val mirrored = mirrorPointAcrossRail(pocket, rail)
        val direct = solveShot(cue, target, mirrored, ballRadiusPx) ?: return null
        val contact = railContactPoint(target, mirrored, rail) ?: return null
        val ideal = BankSolution(
            direct = direct,
            railContact = contact,
            bankPath = listOf(Segment(target, contact), Segment(contact, pocket)),
            mirroredPocket = mirrored
        )

        // Incident angle: between the ideal outgoing direction and the rail.
        val railDir = rail.normal.perp()
        val outgoingIdeal = reflectDirection(contact - target, rail)
        val incidentAngle = angleBetween(outgoingIdeal, railDir)
        val adjust = correction.correct(incidentAngle)
        if (kotlin.math.abs(adjust) < 1e-9) return ideal

        // Re-solve the contact: find s along the rail where the corrected
        // outgoing ray (ideal reflection rotated by the correction)
        // passes through the pocket. Bisection on the signed cross
        // product, same 24-step pattern as the kiss refinement.
        val s0 = (contact - rail.point).dot(railDir)
        fun residual(s: Double): Double {
            val c = rail.point + railDir * s
            val incoming = (c - target).normalized()
            if (incoming.norm() < 1e-9) return Double.NaN
            val outIdeal = reflectDirection(incoming, rail)
            val outCorrected = rotate(outIdeal, adjust)
            val toPocket = pocket - c
            // Signed perpendicular distance of the pocket from the ray.
            return toPocket.x * outCorrected.y - toPocket.y * outCorrected.x
        }
        val bracket = 150.0
        var lo = s0 - bracket
        var hi = s0 + bracket
        var fLo = residual(lo)
        val fHi = residual(hi)
        if (fLo.isNaN() || fHi.isNaN() || fLo * fHi > 0.0) return ideal
        repeat(KISS_REFINE_STEPS) {
            val mid = (lo + hi) / 2.0
            val fMid = residual(mid)
            if (fMid.isNaN()) return ideal
            if ((fMid >= 0.0) == (fLo >= 0.0)) {
                lo = mid
                fLo = fMid
            } else {
                hi = mid
            }
        }
        val correctedContact = rail.point + railDir * ((lo + hi) / 2.0)
        // The corrected ray must head toward the pocket, not away.
        val outCheck = run {
            val incoming = (correctedContact - target).normalized()
            rotate(reflectDirection(incoming, rail), adjust)
        }
        if ((pocket - correctedContact).dot(outCheck) <= 0.0) return ideal
        val correctedDirect = solveShot(cue, target, correctedContact, ballRadiusPx)
            ?: return ideal
        return BankSolution(
            direct = correctedDirect,
            railContact = correctedContact,
            bankPath = listOf(
                Segment(target, correctedContact),
                Segment(correctedContact, pocket)
            ),
            mirroredPocket = mirrored
        )
    }

    /**
     * Solve a two-rail bank: mirror the pocket across the LAST rail
     * first ([rail2]), then across the FIRST rail ([rail1]), and solve
     * a direct shot against the doubly mirrored pocket. The straight
     * line target -> doubly mirrored pocket unfolds the two-reflection
     * path: it crosses [rail1] at the first contact, and the segment
     * from that contact to the once-mirrored pocket crosses [rail2]
     * at the second contact. Returns null when the rails are the same,
     * when the pocket sits on either rail (it would mirror to itself),
     * when either contact is not on its segment, when the contacts
     * coincide (both at the same corner), or when the direct solve
     * fails.
     *
     * Cushion correction (same form as the one-rail bank, Bloro lead):
     * the correction is applied at EACH rail contact, the second
     * contact re-solved first (its target is the pocket), then the
     * first (its target is the corrected second contact). With the
     * fitted coefficients the correction is zero and the ideal bank
     * is returned unchanged. When a correction cannot be satisfied,
     * the ideal bank is returned: a slightly-off bank line is better
     * than no bank line.
     */
    fun solveTwoRailBank(
        cue: Vec2,
        target: Vec2,
        pocket: Vec2,
        rail1: Rail,
        rail2: Rail,
        ballRadiusPx: Double,
        correction: CushionCorrection = CushionCorrection.FITTED
    ): TwoRailBankSolution? {
        if (rail1 == rail2) return null
        // A pocket on either rail mirrors to itself: degenerate.
        if (abs((pocket - rail1.point).dot(rail1.normal)) < 1.0) return null
        if (abs((pocket - rail2.point).dot(rail2.normal)) < 1.0) return null
        val mirroredOnce = mirrorPointAcrossRail(pocket, rail2)
        val mirrored = mirrorPointAcrossRail(mirroredOnce, rail1)
        val direct = solveShot(cue, target, mirrored, ballRadiusPx) ?: return null
        val contact1 = railContactPoint(target, mirrored, rail1) ?: return null
        val contact2 = railContactPoint(contact1, mirroredOnce, rail2) ?: return null
        if (contact1.distanceTo(contact2) < 1e-6) return null
        val ideal = TwoRailBankSolution(
            direct = direct,
            railContacts = listOf(contact1, contact2),
            bankPath = listOf(
                Segment(target, contact1),
                Segment(contact1, contact2),
                Segment(contact2, pocket)
            ),
            mirroredPocket = mirrored
        )

        // Incident angles: between each ideal outgoing direction and
        // its rail, same definition as the one-rail bank.
        val rail1Dir = rail1.normal.perp()
        val rail2Dir = rail2.normal.perp()
        val out1 = reflectDirection(contact1 - target, rail1)
        val out2 = reflectDirection(contact2 - contact1, rail2)
        val adjust1 = correction.correct(angleBetween(out1, rail1Dir))
        val adjust2 = correction.correct(angleBetween(out2, rail2Dir))
        if (abs(adjust1) < 1e-9 && abs(adjust2) < 1e-9) return ideal

        val corrected2 = correctedContact(rail2, contact1, pocket, contact2, adjust2)
            ?: return ideal
        val corrected1 = correctedContact(rail1, target, corrected2, contact1, adjust1)
            ?: return ideal
        val correctedDirect = solveShot(cue, target, corrected1, ballRadiusPx)
            ?: return ideal
        return TwoRailBankSolution(
            direct = correctedDirect,
            railContacts = listOf(corrected1, corrected2),
            bankPath = listOf(
                Segment(target, corrected1),
                Segment(corrected1, corrected2),
                Segment(corrected2, pocket)
            ),
            mirroredPocket = mirrored
        )
    }

    /**
     * Solve a two-rail kick: mirror the TARGET ball's ghost point
     * across the LAST rail first ([rail2]), then across the FIRST
     * rail ([rail1]), and aim the cue at the doubly mirrored ghost.
     * The straight line cue -> doubly mirrored ghost unfolds the
     * two-reflection path: it crosses [rail1] at the first contact,
     * and the segment from that contact to the once-mirrored ghost
     * crosses [rail2] at the second contact. This is the mirror of
     * a two-rail bank: there the pocket is mirrored twice and the
     * target banks; here the ghost is mirrored twice and the cue
     * banks. Returns null when the rails are the same, when the
     * ghost sits on either rail (it would mirror to itself), when
     * either contact is not on its segment, when the contacts
     * coincide (both at the same corner), or when the cut is
     * impossible (> 90 deg).
     *
     * Cushion correction (same form as the two-rail bank, Bloro
     * lead): the correction is applied at EACH rail contact, the
     * second contact re-solved first (its target is the ghost),
     * then the first (its target is the corrected second contact).
     * With the fitted coefficients the correction is zero and the
     * ideal kick is returned unchanged. When a correction cannot
     * be satisfied, the ideal kick is returned: a slightly-off kick
     * line is better than no kick line.
     *
     * Ranking note: a two-rail kick compounds real-table error
     * twice (two cushion contacts, each fitted to synthetic data),
     * so it is the last resort: the live pipeline only tries it
     * after direct, one-rail bank, one-rail kick, and two-rail bank
     * all fail (see CvPipeline.solveFor), and practice KICK mode
     * only recommends it when its cut beats the best one-rail kick
     * by more than [PracticeState.TWO_RAIL_CUT_MARGIN_DEGREES]
     * (see PracticeState.solveBestKick).
     */
    fun solveTwoRailKick(
        cue: Vec2,
        target: Vec2,
        pocket: Vec2,
        rail1: Rail,
        rail2: Rail,
        ballRadiusPx: Double,
        correction: CushionCorrection = CushionCorrection.FITTED
    ): TwoRailKickSolution? {
        require(ballRadiusPx > 0) { "ballRadiusPx must be positive" }
        if (cue.distanceTo(target) < 2 * ballRadiusPx + 0.5) return null // already at impact
        if (rail1 == rail2) return null
        val ghost = ghostPoint(target, pocket, ballRadiusPx)
        // Ghost on either rail: the mirror is the ghost itself, the
        // kick is degenerate (the cue would meet the target at the
        // cushion).
        if (abs((ghost - rail1.point).dot(rail1.normal)) < 1.0) return null
        if (abs((ghost - rail2.point).dot(rail2.normal)) < 1.0) return null
        val mirroredOnce = mirrorPointAcrossRail(ghost, rail2)
        val mirrored = mirrorPointAcrossRail(mirroredOnce, rail1)
        val contact1 = railContactPoint(cue, mirrored, rail1) ?: return null
        val contact2 = railContactPoint(contact1, mirroredOnce, rail2) ?: return null
        if (contact1.distanceTo(contact2) < 1e-6) return null
        val ideal = buildTwoRailKick(
            cue, target, pocket, rail1, rail2, contact1, contact2, ballRadiusPx, mirrored
        ) ?: return null

        // Incident angles: between each ideal outgoing direction and
        // its rail, same definition as the one-rail kick.
        val rail1Dir = rail1.normal.perp()
        val rail2Dir = rail2.normal.perp()
        val out1 = reflectDirection(contact1 - cue, rail1)
        val out2 = reflectDirection(contact2 - contact1, rail2)
        val adjust1 = correction.correct(angleBetween(out1, rail1Dir))
        val adjust2 = correction.correct(angleBetween(out2, rail2Dir))
        if (abs(adjust1) < 1e-9 && abs(adjust2) < 1e-9) return ideal

        val corrected2 = correctedContact(rail2, contact1, ghost, contact2, adjust2)
            ?: return ideal
        val corrected1 = correctedContact(rail1, cue, corrected2, contact1, adjust1)
            ?: return ideal
        return buildTwoRailKick(
            cue, target, pocket, rail1, rail2, corrected1, corrected2, ballRadiusPx, mirrored
        ) ?: ideal
    }

    /**
     * Build a two-rail kick solution from given rail contacts: the
     * cue arrives at the ghost point travelling the twice-reflected
     * direction, so the cut angle is between the second reflected
     * direction and the pocket direction. Returns null when either
     * reflected ray does not head toward its next point or the cut
     * is impossible (> 90 deg).
     */
    private fun buildTwoRailKick(
        cue: Vec2,
        target: Vec2,
        pocket: Vec2,
        rail1: Rail,
        rail2: Rail,
        contact1: Vec2,
        contact2: Vec2,
        ballRadiusPx: Double,
        mirroredGhost: Vec2
    ): TwoRailKickSolution? {
        val ghost = ghostPoint(target, pocket, ballRadiusPx)
        val outDir1 = reflectDirection(contact1 - cue, rail1)
        if ((contact2 - contact1).dot(outDir1) <= 0.0) return null
        val outDir2 = reflectDirection(contact2 - contact1, rail2)
        if ((ghost - contact2).dot(outDir2) <= 0.0) return null
        val u = (pocket - target).normalized()
        if (u.norm() < 1e-9) return null // target sits on the pocket
        val cutAngle = cutAngleDegrees(outDir2, u)
        if (cutAngle > 90.0) return null // impossible: would have to hit from the wrong side
        val tangent = tangentDirection(outDir2, u)
        val deflectLen = 2 * ballRadiusPx // draw 1-2 ball diameters
        val direct = ShotSolution(
            ghostPoint = ghost,
            // The aim line is the cue-stick direction: cue -> first
            // rail contact. The full cue-ball path is kickPath (cue ->
            // contact 1 -> contact 2 -> ghost); a single segment cannot
            // express the reflections, so aimLine ends at contact 1,
            // not the ghost.
            aimLine = Segment(cue, contact1),
            targetLine = Segment(target, pocket),
            deflectionLine = Segment(ghost, ghost + tangent * deflectLen),
            cutAngleDegrees = cutAngle,
            isThinCut = cutAngle > THIN_CUT_DEGREES,
            distanceToImpact = cue.distanceTo(contact1) +
                contact1.distanceTo(contact2) + contact2.distanceTo(ghost)
        )
        return TwoRailKickSolution(
            direct = direct,
            railContacts = listOf(contact1, contact2),
            kickPath = listOf(
                Segment(cue, contact1),
                Segment(contact1, contact2),
                Segment(contact2, ghost)
            ),
            mirroredGhost = mirroredGhost
        )
    }

    /**
     * Solve a one-rail kick: mirror the TARGET ball's ghost point
     * across the rail, then aim the cue at the mirrored ghost. The
     * straight line cue -> mirrored ghost unfolds the reflected path:
     * it crosses the rail at the contact, and reflecting the cue's
     * direction there sends it through the real ghost point, so the
     * cue strikes the target exactly as a direct shot would — the
     * cut angle is between the reflected direction and the pocket
     * direction. This is the mirror of a one-rail bank: there the
     * pocket is mirrored and the target banks; here the ghost is
     * mirrored and the cue banks. Returns null when the balls
     * overlap, when the ghost sits on the rail (degenerate: the
     * mirror is the ghost itself), when the cue -> mirrored-ghost
     * line does not cross the rail segment, when the reflected ray
     * does not head toward the ghost, or when the cut is impossible
     * (> 90 deg).
     *
     * Cushion correction (same form as the one-rail bank, Bloro
     * lead): the correction is applied at the rail contact, the
     * contact re-solved by bisection so the corrected outgoing ray
     * still reaches the ghost point, and the cut re-solved from the
     * corrected contact. With the fitted coefficients the correction
     * is zero and the ideal kick is returned unchanged. When a
     * correction cannot be satisfied, the ideal kick is returned: a
     * slightly-off kick line is better than no kick line.
     *
     * Ranking note: a kick is compared with a one-rail bank by the
     * same number — the direct cut angle of the underlying solve.
     * Both are one-rail shots and the cut angle is the codebase's
     * established difficulty proxy (allShots, banks, and practice
     * mode all rank by it), so the smaller cut wins; see
     * CvPipeline.solveFor. A kick never displaces a direct shot.
     */
    fun solveKick(
        cue: Vec2,
        target: Vec2,
        pocket: Vec2,
        rail: Rail,
        ballRadiusPx: Double,
        correction: CushionCorrection = CushionCorrection.FITTED
    ): KickSolution? {
        require(ballRadiusPx > 0) { "ballRadiusPx must be positive" }
        if (cue.distanceTo(target) < 2 * ballRadiusPx + 0.5) return null // already at impact
        val ghost = ghostPoint(target, pocket, ballRadiusPx)
        // Ghost on the rail: the mirror is the ghost itself, the kick
        // is degenerate (the cue would meet the target at the cushion).
        if (abs((ghost - rail.point).dot(rail.normal)) < 1.0) return null
        val mirrored = mirrorPointAcrossRail(ghost, rail)
        val contact = railContactPoint(cue, mirrored, rail) ?: return null
        val ideal = buildKick(cue, target, pocket, rail, contact, ballRadiusPx, mirrored)
            ?: return null

        // Incident angle: between the ideal outgoing direction and the rail.
        val railDir = rail.normal.perp()
        val outgoingIdeal = reflectDirection(contact - cue, rail)
        val incidentAngle = angleBetween(outgoingIdeal, railDir)
        val adjust = correction.correct(incidentAngle)
        if (abs(adjust) < 1e-9) return ideal

        // Re-solve the contact: find s along the rail where the corrected
        // outgoing ray (ideal reflection rotated by the correction)
        // passes through the ghost point.
        val correctedContact = correctedContact(rail, cue, ghost, contact, adjust)
            ?: return ideal
        return buildKick(cue, target, pocket, rail, correctedContact, ballRadiusPx, mirrored)
            ?: ideal
    }

    /**
     * Build a kick solution from a given rail contact: the cue arrives
     * at the ghost point travelling the reflected direction, so the cut
     * angle is between the reflected direction and the pocket
     * direction. Returns null when the reflected ray does not head
     * toward the ghost or the cut is impossible (> 90 deg).
     */
    private fun buildKick(
        cue: Vec2,
        target: Vec2,
        pocket: Vec2,
        rail: Rail,
        contact: Vec2,
        ballRadiusPx: Double,
        mirroredGhost: Vec2
    ): KickSolution? {
        val ghost = ghostPoint(target, pocket, ballRadiusPx)
        val outDir = reflectDirection(contact - cue, rail)
        if ((ghost - contact).dot(outDir) <= 0.0) return null
        val u = (pocket - target).normalized()
        if (u.norm() < 1e-9) return null // target sits on the pocket
        val cutAngle = cutAngleDegrees(outDir, u)
        if (cutAngle > 90.0) return null // impossible: would have to hit from the wrong side
        val tangent = tangentDirection(outDir, u)
        val deflectLen = 2 * ballRadiusPx // draw 1-2 ball diameters
        val direct = ShotSolution(
            ghostPoint = ghost,
            // The aim line is the cue-stick direction: cue -> rail
            // contact. The full cue-ball path is kickPath (cue ->
            // contact -> ghost); a single segment cannot express the
            // reflection, so aimLine ends at the contact, not the ghost.
            aimLine = Segment(cue, contact),
            targetLine = Segment(target, pocket),
            deflectionLine = Segment(ghost, ghost + tangent * deflectLen),
            cutAngleDegrees = cutAngle,
            isThinCut = cutAngle > THIN_CUT_DEGREES,
            distanceToImpact = cue.distanceTo(contact) + contact.distanceTo(ghost)
        )
        return KickSolution(
            direct = direct,
            railContact = contact,
            kickPath = listOf(Segment(cue, contact), Segment(contact, ghost)),
            mirroredGhost = mirroredGhost
        )
    }

    /**
     * Re-solve one rail contact under a cushion correction: find s
     * along the rail where the corrected outgoing ray (ideal reflection
     * rotated by [adjust] degrees) passes through [to]. Returns null
     * when the correction cannot be satisfied (no sign change in the
     * bracket, or the corrected ray heads away from [to]). Same
     * 24-step bisection pattern as the kiss refinement and the
     * one-rail bank correction.
     */
    private fun correctedContact(
        rail: Rail,
        from: Vec2,
        to: Vec2,
        idealContact: Vec2,
        adjust: Double
    ): Vec2? {
        val railDir = rail.normal.perp()
        fun residual(s: Double): Double {
            val c = rail.point + railDir * s
            val incoming = (c - from).normalized()
            if (incoming.norm() < 1e-9) return Double.NaN
            val outCorrected = rotate(reflectDirection(incoming, rail), adjust)
            val toTarget = to - c
            return toTarget.x * outCorrected.y - toTarget.y * outCorrected.x
        }
        val s0 = (idealContact - rail.point).dot(railDir)
        val bracket = 150.0
        var lo = s0 - bracket
        var hi = s0 + bracket
        var fLo = residual(lo)
        val fHi = residual(hi)
        if (fLo.isNaN() || fHi.isNaN() || fLo * fHi > 0.0) return null
        repeat(KISS_REFINE_STEPS) {
            val mid = (lo + hi) / 2.0
            val fMid = residual(mid)
            if (fMid.isNaN()) return null
            if ((fMid >= 0.0) == (fLo >= 0.0)) {
                lo = mid
                fLo = fMid
            } else {
                hi = mid
            }
        }
        val corrected = rail.point + railDir * ((lo + hi) / 2.0)
        // The corrected ray must head toward the target, not away.
        val incoming = (corrected - from).normalized()
        val out = rotate(reflectDirection(incoming, rail), adjust)
        if ((to - corrected).dot(out) <= 0.0) return null
        return corrected
    }

    /**
     * Intersection of the segment target -> mirroredPocket with the rail line.
     * Returns null when the segment is parallel to the rail or the
     * intersection lies behind the target.
     */
    fun railContactPoint(target: Vec2, mirroredPocket: Vec2, rail: Rail): Vec2? {
        // Parametrize T + s * (P' - T); find s where the point is on the rail:
        // (point - Q) . n = 0.
        val dir = mirroredPocket - target
        val denom = dir.dot(rail.normal)
        if (kotlin.math.abs(denom) < 1e-9) return null // parallel to the rail
        val s = ((rail.point - target).dot(rail.normal)) / denom
        if (s < 0.0 || s > 1.0) return null // contact is not on the segment
        return target + dir * s
    }
}
