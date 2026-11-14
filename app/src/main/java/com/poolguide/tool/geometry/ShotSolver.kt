package com.poolguide.tool.geometry

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
     */
    fun solveBank(
        cue: Vec2,
        target: Vec2,
        pocket: Vec2,
        rail: Rail,
        ballRadiusPx: Double
    ): BankSolution? {
        val mirrored = mirrorPointAcrossRail(pocket, rail)
        val direct = solveShot(cue, target, mirrored, ballRadiusPx) ?: return null
        val contact = railContactPoint(target, mirrored, rail) ?: return null
        return BankSolution(
            direct = direct,
            railContact = contact,
            bankPath = listOf(Segment(target, contact), Segment(contact, pocket)),
            mirroredPocket = mirrored
        )
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
