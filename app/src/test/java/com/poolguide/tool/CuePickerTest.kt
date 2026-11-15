package com.poolguide.tool

import com.poolguide.tool.cv.Ball
import com.poolguide.tool.cv.BallColor
import com.poolguide.tool.cv.CuePicker
import com.poolguide.tool.cv.GuidelineSegment
import com.poolguide.tool.geometry.BallGroup
import com.poolguide.tool.geometry.Segment
import com.poolguide.tool.geometry.Vec2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Cue-ball pick (v1.0.20, ray fallback v1.0.40). The blue demo table's stripe-band phantom
 * is the model: a WHITE ball at the stripe ball's centre whose disc
 * samples STRIPE, listed before the real cue ball (WHITE, group CUE).
 * The old first-white pick took the phantom; the group-first pick
 * takes the real cue.
 */
class CuePickerTest {

    private fun ball(
        id: Int, x: Double, y: Double,
        color: BallColor, group: BallGroup
    ) = Ball(id, Vec2(x, y), 9.0, color, isStripe = false, group = group)

    // Blue demo table, measured in the harness replica (probe20):
    // phantom white band at (506.5, 253.5) group STRIPE, real cue at
    // (168.5, 281.5) group CUE, a second band phantom at (196.5, 168.5).
    private val phantom = ball(8, 506.5, 253.5, BallColor.WHITE, BallGroup.STRIPE)
    private val phantom2 = ball(9, 196.5, 168.5, BallColor.WHITE, BallGroup.STRIPE)
    private val realCue = ball(11, 168.5, 281.5, BallColor.WHITE, BallGroup.CUE)
    private val solid = ball(1, 449.8, 112.2, BallColor.RED, BallGroup.SOLID)

    @Test
    fun phantomFirst_realCueStillPicked() {
        val picked = CuePicker.pickCue(listOf(solid, phantom, phantom2, realCue))
        assertEquals(realCue, picked)
    }

    @Test
    fun noCueGroup_fallsBackToFirstWhite() {
        // Real cue occluded: its group reads UNKNOWN, the phantom
        // reads STRIPE — the fallback is the first white ball, the
        // old behaviour, never nothing.
        val unknownCue = ball(11, 168.5, 281.5, BallColor.WHITE, BallGroup.UNKNOWN)
        val picked = CuePicker.pickCue(listOf(solid, phantom, unknownCue))
        assertEquals(phantom, picked)
    }

    @Test
    fun noWhiteBall_returnsNull() {
        val picked = CuePicker.pickCue(listOf(solid, ball(7, 393.5, 303.5, BallColor.BLACK, BallGroup.EIGHT)))
        assertNull(picked)
    }

    @Test
    fun emptyList_returnsNull() {
        assertNull(CuePicker.pickCue(emptyList()))
    }

    @Test
    fun cueGroupBeatsEarlierWhite() {
        // Order in the list must not matter when a CUE-group ball
        // exists: even a white ball listed first loses to it.
        val earlyWhite = ball(3, 100.0, 100.0, BallColor.WHITE, BallGroup.STRIPE)
        val picked = CuePicker.pickCue(listOf(earlyWhite, realCue))
        assertEquals(realCue, picked)
    }

    // ---- v1.0.40: ray fallback (deep-dive item 7) ----------------

    private fun segment(x0: Double, y0: Double, x1: Double, y1: Double) =
        GuidelineSegment(Segment(Vec2(x0, y0), Vec2(x1, y1)), alpha = 40)

    // a09_cue_in_cluster, measured in the harness replica: the real
    // cue at (281.0, 202.1) reads UNKNOWN (occluded by the cluster),
    // the stripe-band phantom at (393.5, 281.0) reads STRIPE and is
    // listed FIRST, the detected guideline runs (291.36, 203.11) ->
    // (468.02, 201.89). The phantom sits 79 px off the ray; the real
    // cue sits ~1 px off it.
    private val a09Phantom = ball(7, 393.5, 281.0, BallColor.WHITE, BallGroup.STRIPE)
    private val a09Cue = ball(6, 281.0, 202.1, BallColor.WHITE, BallGroup.UNKNOWN)
    private val a09Guideline = listOf(segment(291.36, 203.11, 468.02, 201.89))

    @Test
    fun noCueGroup_withGuideline_picksCueOnRay() {
        val picked = CuePicker.pickCue(
            listOf(solid, a09Phantom, a09Cue), a09Guideline
        )
        assertEquals(a09Cue, picked)
    }

    @Test
    fun cueGroup_withGuideline_stillWins() {
        // The ray never displaces a CUE-group ball.
        val picked = CuePicker.pickCue(
            listOf(solid, a09Phantom, realCue), a09Guideline
        )
        assertEquals(realCue, picked)
    }

    @Test
    fun noCueGroup_guidelineFarAway_fallsBackToFirstWhite() {
        // No white ball within 20 px of the ray: the ray gives no
        // confident answer, so the old first-white behaviour stands.
        // (A vertical segment at x=400: the phantom at x=506.5 is
        // 106 px off its line, the cue at x=168.5 is 231 px off.)
        val farGuideline = listOf(segment(400.0, 100.0, 400.0, 200.0))
        val unknownCue = ball(11, 168.5, 281.5, BallColor.WHITE, BallGroup.UNKNOWN)
        val picked = CuePicker.pickCue(
            listOf(solid, phantom, unknownCue), farGuideline
        )
        assertEquals(phantom, picked)
    }

    @Test
    fun noCueGroup_twoWhitesOnLine_nearestSegmentStartWins() {
        // Both white balls sit on the ray; the one nearest the segment
        // start is the cue (the segment starts at the cue ball's edge).
        val lineGuideline = listOf(segment(100.0, 100.0, 300.0, 100.0))
        val cueAtStart = ball(5, 90.0, 100.0, BallColor.WHITE, BallGroup.UNKNOWN)
        val whiteFurtherAlong = ball(6, 200.0, 100.0, BallColor.WHITE, BallGroup.UNKNOWN)
        val picked = CuePicker.pickCue(
            listOf(whiteFurtherAlong, cueAtStart), lineGuideline
        )
        assertEquals(cueAtStart, picked)
    }

    @Test
    fun rayGate_boundary() {
        // 20 px is the gate: a white ball exactly on it still counts.
        // The cue at perp 20.0 vs the phantom at perp 79 — the cue wins.
        val gateGuideline = listOf(segment(100.0, 100.0, 300.0, 100.0))
        val cueOnGate = ball(5, 110.0, 120.0, BallColor.WHITE, BallGroup.UNKNOWN)
        val picked = CuePicker.pickCue(
            listOf(a09Phantom, cueOnGate), gateGuideline
        )
        assertEquals(cueOnGate, picked)
    }
}
