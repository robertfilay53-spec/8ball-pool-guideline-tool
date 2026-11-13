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
