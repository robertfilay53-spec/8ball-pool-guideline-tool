package com.poolguide.tool.cv

import com.poolguide.tool.geometry.BallGroup
import kotlin.math.abs

/**
 * Cue-ball pick (v1.0.20, ray fallback v1.0.40). Pure Kotlin — unit-tested on the JVM.
 *
 * The old pick was the first ball whose HSV colour is WHITE. A stripe
 * ball's white number band detects as a WHITE ball at the stripe
 * ball's centre, and on the blue demo table that phantom came first
 * in the list, so the pipeline solved phantom-cue -> real-cue instead
 * of the depicted shot (found while testing v1.0.19).
 *
 * The group classifier (v1.0.14) already tells them apart: sampling
 * the phantom's disc samples the whole stripe ball, white fraction
 * 0.65-0.79, so it classifies STRIPE, while the real cue ball
 * measures white fraction 1.000 and classifies CUE. So the pick is
 * group-first: the ball the classifier calls CUE wins.
 *
 * The colour fallback stays for the case the real cue's group reads
 * UNKNOWN (occluded, or cut off by the frame edge) — never lose the
 * old behaviour entirely. Since v1.0.40 that fallback is ray-scored,
 * not list order: when a guideline is visible, the WHITE ball nearest
 * the longest guideline segment's line wins (same ray logic
 * pickTarget uses — the guideline emanates from the cue ball, so the
 * real cue sits on its line and a stripe-band phantom 79 px off it
 * loses). Only when there is no guideline, or no white ball close
 * enough to its line, does the pick fall back to the first white
 * ball. A phantom exactly on the aim line can still win the ray
 * score if it sits nearer the segment start than the real cue; that
 * is the honest degradation, and EDIT mode exists to correct a
 * mis-detection by hand.
 */
object CuePicker {

    /**
     * A white ball counts as on the guideline ray only this close to
     * its line (px, working resolution). The real cue's centre sits
     * ~1-3 px off the detected midline (midline error ~2 px + centre
     * error ~1 px); the a09 phantom sits 79 px off it. 12 px admits
     * the real cue with a wide margin and excludes the phantom by
     * 6x (was 20 px, v1.0.69, real-phone fix: the loose gate let a
     * misdetected white blob win the ray score off a rail line the
     * verifier had wrongly returned as the guideline). Judgement
     * call, pinned by CuePickerTest.
     */
    const val RAY_PERP_GATE_PX = 12.0

    /** The cue ball in [balls], or null when no ball qualifies. */
    fun pickCue(
        balls: List<Ball>,
        guideline: List<GuidelineSegment> = emptyList()
    ): Ball? {
        balls.firstOrNull { it.group == BallGroup.CUE }?.let { return it }
        pickByRay(balls, guideline)?.let { return it }
        return balls.firstOrNull { it.isCue }
    }

    /**
     * The WHITE ball nearest the longest guideline segment's line.
     * Null when there is no segment, no white ball, or no white ball
     * within [RAY_PERP_GATE_PX] of the line.
     */
    private fun pickByRay(
        balls: List<Ball>,
        guideline: List<GuidelineSegment>
    ): Ball? {
        val seg = guideline.maxByOrNull { it.segment.length() }?.segment
            ?: return null
        val whites = balls.filter { it.isCue }
        if (whites.isEmpty()) return null
        var best: Ball? = null
        var bestScore = Double.MAX_VALUE
        for (ball in whites) {
            // Same ray shape as pickTarget: from the endpoint nearer
            // the ball, through the far endpoint. The cue sits at (or
            // just behind) the segment start — the segment starts at
            // the cue ball's edge, ~1 radius from its centre — so a
            // negative along is legitimate here, unlike pickTarget,
            // and |along| prefers the ball nearest the segment start.
            val near =
                if (seg.start.distanceTo(ball.center) < seg.end.distanceTo(ball.center))
                    seg.start else seg.end
            val far = if (near == seg.start) seg.end else seg.start
            val dir = (far - near).normalized()
            val toBall = ball.center - near
            val along = toBall.dot(dir)
            val perp = (toBall - dir * along).norm()
            if (perp > RAY_PERP_GATE_PX) continue
            val score = perp + abs(along) * 0.05
            if (score < bestScore) {
                bestScore = score
                best = ball
            }
        }
        return best
    }
}
