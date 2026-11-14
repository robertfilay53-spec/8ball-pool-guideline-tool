package com.poolguide.tool

import com.poolguide.tool.cv.Ball
import com.poolguide.tool.cv.BallColor
import com.poolguide.tool.cv.CuePicker
import com.poolguide.tool.geometry.BallGroup
import com.poolguide.tool.geometry.Vec2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Cue-ball pick (v1.0.20). The blue demo table's stripe-band phantom
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
}
