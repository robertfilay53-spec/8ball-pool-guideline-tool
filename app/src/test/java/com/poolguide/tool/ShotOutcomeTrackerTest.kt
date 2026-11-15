package com.poolguide.tool.data

import com.poolguide.tool.cv.Ball
import com.poolguide.tool.cv.BallColor
import com.poolguide.tool.cv.DetectionResult
import com.poolguide.tool.geometry.Segment
import com.poolguide.tool.geometry.ShotSolver
import com.poolguide.tool.geometry.Vec2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Session pot-rate tracker (v1.0.42, REA hardening R6). Pure Kotlin,
 * JVM-tested. The thresholds are judgement calls (detection jitter
 * is a few px, a struck cue ball moves tens of px per frame, a
 * settled ball sits within a couple of px of its last spot). The
 * tests pin the behaviour at the thresholds, not the thresholds
 * themselves. Frames are built by hand: a solved shot, a cue ball
 * at (100, 200), a red target at (300, 200), a blue ball at
 * (400, 300), all radius 9.
 */
class ShotOutcomeTrackerTest {

    private fun ball(id: Int, x: Double, y: Double, color: BallColor): Ball =
        Ball(id, Vec2(x, y), 9.0, color, false)

    private fun cue(x: Double = 100.0, y: Double = 200.0): Ball =
        ball(0, x, y, BallColor.WHITE)

    private fun target(x: Double = 300.0, y: Double = 200.0): Ball =
        ball(1, x, y, BallColor.RED)

    private fun blue(x: Double = 400.0, y: Double = 300.0): Ball =
        ball(2, x, y, BallColor.BLUE)

    private fun solvedShot(): ShotSolver.ShotSolution = ShotSolver.ShotSolution(
        ghostPoint = Vec2(282.0, 200.0),
        aimLine = Segment(Vec2(100.0, 200.0), Vec2(282.0, 200.0)),
        targetLine = Segment(Vec2(300.0, 200.0), Vec2(500.0, 200.0)),
        deflectionLine = Segment(Vec2(282.0, 200.0), Vec2(282.0, 220.0)),
        cutAngleDegrees = 10.0,
        isThinCut = false,
        distanceToImpact = 182.0
    )

    private fun frame(
        balls: List<Ball>,
        cueBall: Ball?,
        solved: Boolean = true,
        locked: Boolean = true,
        black: Boolean = false
    ): DetectionResult = DetectionResult(
        frameIndex = 0,
        workingWidth = 720,
        workingHeight = 360,
        scaleToScreenX = 1.0,
        scaleToScreenY = 1.0,
        isCaptureBlack = black,
        table = null,
        pockets = emptyList(),
        balls = balls,
        cueBall = cueBall,
        guideline = emptyList(),
        shot = if (solved) solvedShot() else null,
        bank = null,
        tableLocked = locked,
        status = "test"
    )

    /** Aim 3 frames, strike (cue jumps 50 px and stays there for
     * 2 frames), roll 2 frames, settle 5 frames with the target
     * gone: 1 shot, 1 pot. */
    private fun playPot(tracker: ShotOutcomeTracker) {
        val c = cue()
        val t = target()
        val b = blue()
        repeat(3) { tracker.observe(frame(listOf(c, t, b), c)) }
        val c2 = cue(150.0, 200.0)
        repeat(2) { tracker.observe(frame(listOf(c2, t, b), c2)) }
        val c3 = cue(170.0, 200.0)
        repeat(2) { tracker.observe(frame(listOf(c3, t, b), c3)) }
        val c4 = cue(180.0, 200.0)
        repeat(6) { tracker.observe(frame(listOf(c4, b), c4)) }
    }

    /** Same as [playPot] but the target is still there at settle:
     * 1 shot, 0 pots. */
    private fun playMiss(tracker: ShotOutcomeTracker) {
        val c = cue()
        val t = target()
        val b = blue()
        repeat(3) { tracker.observe(frame(listOf(c, t, b), c)) }
        val c2 = cue(150.0, 200.0)
        repeat(2) { tracker.observe(frame(listOf(c2, t, b), c2)) }
        val c3 = cue(170.0, 200.0)
        repeat(2) { tracker.observe(frame(listOf(c3, t, b), c3)) }
        val c4 = cue(180.0, 200.0)
        repeat(6) { tracker.observe(frame(listOf(c4, t, b), c4)) }
    }

    @Test
    fun `no shot solved never counts`() {
        val tracker = ShotOutcomeTracker()
        val c = cue()
        val t = target()
        repeat(10) { tracker.observe(frame(listOf(c, t), c, solved = false)) }
        assertEquals(0, tracker.shotsTaken)
        assertEquals(0, tracker.pots)
        assertNull(tracker.potRate())
        assertFalse(tracker.shouldRemind())
        assertEquals("No shots taken yet", tracker.format())
    }

    @Test
    fun `steady aim never counts a shot`() {
        val tracker = ShotOutcomeTracker()
        val c = cue()
        val t = target()
        val b = blue()
        repeat(10) { tracker.observe(frame(listOf(c, t, b), c)) }
        assertEquals(0, tracker.shotsTaken)
        assertEquals(0, tracker.pots)
    }

    @Test
    fun `one-frame cue jump is a glitch, not a shot`() {
        val tracker = ShotOutcomeTracker()
        val c = cue()
        val t = target()
        val b = blue()
        repeat(3) { tracker.observe(frame(listOf(c, t, b), c)) }
        val jumped = cue(150.0, 200.0)
        tracker.observe(frame(listOf(jumped, t, b), jumped))
        // Cue snaps back: no shot.
        repeat(5) { tracker.observe(frame(listOf(c, t, b), c)) }
        assertEquals(0, tracker.shotsTaken)
        assertEquals(0, tracker.pots)
    }

    @Test
    fun `shot taken and potted counts one and one`() {
        val tracker = ShotOutcomeTracker()
        playPot(tracker)
        assertEquals(1, tracker.shotsTaken)
        assertEquals(1, tracker.pots)
        assertEquals(1.0, tracker.potRate()!!, 1e-9)
        assertEquals("Pots 1/1 (100%)", tracker.format())
    }

    @Test
    fun `shot taken and missed counts the shot, not the pot`() {
        val tracker = ShotOutcomeTracker()
        playMiss(tracker)
        assertEquals(1, tracker.shotsTaken)
        assertEquals(0, tracker.pots)
        assertEquals(0.0, tracker.potRate()!!, 1e-9)
        assertEquals("Pots 0/1 (0%)", tracker.format())
    }

    @Test
    fun `scratch counts the shot, never a pot`() {
        val tracker = ShotOutcomeTracker()
        val c = cue()
        val t = target()
        val b = blue()
        repeat(3) { tracker.observe(frame(listOf(c, t, b), c)) }
        val c2 = cue(150.0, 200.0)
        repeat(2) { tracker.observe(frame(listOf(c2, t, b), c2)) }
        // Settle with no cue ball at all: scratched. The target is
        // still there, so nothing was potted.
        repeat(6) { tracker.observe(frame(listOf(t, b), null)) }
        assertEquals(1, tracker.shotsTaken)
        assertEquals(0, tracker.pots)
    }

    @Test
    fun `two shots accumulate, second pot keeps the rate honest`() {
        val tracker = ShotOutcomeTracker()
        playPot(tracker)
        playMiss(tracker)
        assertEquals(2, tracker.shotsTaken)
        assertEquals(1, tracker.pots)
        assertEquals(0.5, tracker.potRate()!!, 1e-9)
        assertEquals("Pots 1/2 (50%)", tracker.format())
        assertFalse(tracker.shouldRemind())
    }

    @Test
    fun `reminder fires at ten shots and eighty percent`() {
        val tracker = ShotOutcomeTracker()
        repeat(8) { playPot(tracker) }
        playMiss(tracker)
        // 8/9: high rate, but under ten shots, so no reminder.
        assertFalse(tracker.shouldRemind())
        playPot(tracker)
        // 9/10: ten shots, 90 percent, reminder fires.
        assertEquals(10, tracker.shotsTaken)
        assertEquals(9, tracker.pots)
        assertTrue(tracker.shouldRemind())
    }

    @Test
    fun `black capture abandons a pending shot without counting`() {
        val tracker = ShotOutcomeTracker()
        val c = cue()
        val t = target()
        val b = blue()
        repeat(3) { tracker.observe(frame(listOf(c, t, b), c)) }
        val c2 = cue(150.0, 200.0)
        repeat(2) { tracker.observe(frame(listOf(c2, t, b), c2)) }
        // Capture goes black mid-scatter: the shot was struck, but
        // its outcome is unknown, so no pot is counted.
        repeat(5) {
            tracker.observe(frame(emptyList(), null, black = true, locked = false))
        }
        assertEquals(1, tracker.shotsTaken)
        assertEquals(0, tracker.pots)
        // A fresh aim after the black run arms cleanly again.
        playPot(tracker)
        assertEquals(2, tracker.shotsTaken)
        assertEquals(1, tracker.pots)
    }

    @Test
    fun `unlocked table abandons the aim without counting`() {
        val tracker = ShotOutcomeTracker()
        val c = cue()
        val t = target()
        repeat(3) { tracker.observe(frame(listOf(c, t), c)) }
        tracker.observe(frame(listOf(c, t), c, locked = false))
        repeat(5) { tracker.observe(frame(listOf(c, t), c)) }
        assertEquals(0, tracker.shotsTaken)
    }

    @Test
    fun `empty-ball frames never read as every ball potted`() {
        val tracker = ShotOutcomeTracker()
        val c = cue()
        val t = target()
        val b = blue()
        repeat(3) { tracker.observe(frame(listOf(c, t, b), c)) }
        val c2 = cue(150.0, 200.0)
        repeat(2) { tracker.observe(frame(listOf(c2, t, b), c2)) }
        // Detector finds nothing for 5 frames, then the same table
        // comes back: the shot counts, no pot is invented.
        repeat(5) { tracker.observe(frame(emptyList(), null)) }
        repeat(6) { tracker.observe(frame(listOf(c2, t, b), c2)) }
        assertEquals(1, tracker.shotsTaken)
        assertEquals(0, tracker.pots)
    }

    @Test
    fun `reset clears counters and pending state`() {
        val tracker = ShotOutcomeTracker()
        playPot(tracker)
        assertEquals(1, tracker.shotsTaken)
        tracker.reset()
        assertEquals(0, tracker.shotsTaken)
        assertEquals(0, tracker.pots)
        assertNull(tracker.potRate())
        assertEquals("No shots taken yet", tracker.format())
    }

    @Test
    fun `ballsStable needs the same count and close positions`() {
        val a = listOf(cue(), target(), blue())
        val same = listOf(cue(101.0, 200.0), target(300.0, 201.0), blue())
        assertTrue(ballsStable(a, same, 2.0))
        val moved = listOf(cue(110.0, 200.0), target(), blue())
        assertFalse(ballsStable(a, moved, 2.0))
        val fewer = listOf(cue(), target())
        assertFalse(ballsStable(a, fewer, 2.0))
    }

    @Test
    fun `countPotted matches the cue by identity, not by old spot`() {
        val c = cue()
        val t = target()
        val b = blue()
        // Cue travelled 80 px and stopped; target gone; blue stayed.
        val settledCue = cue(180.0, 200.0)
        assertEquals(
            1,
            countPotted(listOf(c, t, b), c, listOf(settledCue, b), settledCue)
        )
        // Same, but the cue ball is missing from the settled frame
        // (scratch): the target is still there, so zero pots.
        assertEquals(
            0,
            countPotted(listOf(c, t, b), c, listOf(t, b), null)
        )
    }
}
