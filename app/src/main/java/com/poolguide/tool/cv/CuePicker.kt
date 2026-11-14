package com.poolguide.tool.cv

import com.poolguide.tool.geometry.BallGroup

/**
 * Cue-ball pick (v1.0.20). Pure Kotlin — unit-tested on the JVM.
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
 * group-first: the ball the classifier calls CUE wins. The colour
 * fallback stays for the case the real cue's group reads UNKNOWN
 * (occluded, or cut off by the frame edge) — never lose the old
 * behaviour entirely. A phantom can still win that fallback if it
 * precedes the real cue in the list; that is the honest degradation,
 * and EDIT mode exists to correct a mis-detection by hand.
 */
object CuePicker {

    /** The cue ball in [balls], or null when no ball qualifies. */
    fun pickCue(balls: List<Ball>): Ball? =
        balls.firstOrNull { it.group == BallGroup.CUE }
            ?: balls.firstOrNull { it.isCue }
}
