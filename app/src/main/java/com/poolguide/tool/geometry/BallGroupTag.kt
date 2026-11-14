package com.poolguide.tool.geometry

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Group-tag placement (v1.0.14). Pure Kotlin, no Android dependency —
 * fully unit-testable on the JVM. One rule, in both the live overlay
 * and practice mode: the tag sits 2.2 ball radii off the nearest shot
 * segment that passes through the ball, perpendicular to that
 * segment, so the tag never sits on the line it sits next to; when
 * no shot line passes through the ball it sits straight above it.
 *
 * The tag is centred on the anchor. A 14 sp tag is ~19 px tall, so
 * its near edge sits ~2.2r − 10 px from the line centre — at the
 * working radius (9 px) that is ~10 px clear of a 5 px aim line.
 * [MIN_CLEARANCE_RADII] is what the tests pin: the anchor itself
 * must sit at least 2.0 radii from the line.
 */
object BallGroupTag {

    /** Tag offset from the ball centre, in ball radii. */
    const val OFFSET_RADII = 2.2

    /** Minimum anchor-to-line distance the tests pin, in ball radii. */
    const val MIN_CLEARANCE_RADII = 2.0

    /** Where the group tag sits for a ball of [ballRadiusPx] at
     * [ballCenter], given the shot [segments] in the same coordinate
     * space. A segment passes through the ball when the centre is
     * within one radius of it; the nearest such segment sets the
     * perpendicular direction. */
    fun anchor(
        ballCenter: Vec2,
        ballRadiusPx: Double,
        segments: List<Segment>
    ): Vec2 {
        val through = segments
            .map { it to distanceToSegment(ballCenter, it) }
            .filter { it.second < ballRadiusPx }
            .minByOrNull { it.second }
        if (through != null) {
            val dir = through.first.direction()
            return ballCenter + dir.perp() * (OFFSET_RADII * ballRadiusPx)
        }
        return ballCenter + Vec2(0.0, -OFFSET_RADII * ballRadiusPx)
    }

    /** Point-to-segment distance (the perpendicular distance when the
     * projection falls on the segment, else the distance to the nearer
     * endpoint). */
    fun distanceToSegment(p: Vec2, seg: Segment): Double {
        val dx = seg.end.x - seg.start.x
        val dy = seg.end.y - seg.start.y
        val lenSq = dx * dx + dy * dy
        if (lenSq < 1e-9) return p.distanceTo(seg.start)
        val t = max(
            0.0,
            min(
                1.0,
                ((p.x - seg.start.x) * dx + (p.y - seg.start.y) * dy) / lenSq
            )
        )
        return p.distanceTo(Vec2(seg.start.x + t * dx, seg.start.y + t * dy))
    }
}
