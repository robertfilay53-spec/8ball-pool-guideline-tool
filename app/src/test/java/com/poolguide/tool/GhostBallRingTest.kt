package com.poolguide.tool

import com.poolguide.tool.geometry.GhostBallRing
import com.poolguide.tool.geometry.Rail
import com.poolguide.tool.geometry.ShotSolver
import com.poolguide.tool.geometry.Vec2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ghost-ball ring (v1.0.26; second-leg ring v1.0.27). All solver
 * numbers pinned against the solver with kotlinc before the tests
 * were written (/tmp/probe26/Probe26.kt, Probe26b.kt; v1.0.27:
 * ~/workspace/probe27/Probe27.kt, Probe27b.kt).
 *
 * The ring is a circle of one ball radius centred on the ghost
 * point, the cue ball's centre at impact. The centre is always
 * the solution's ghostPoint, never the aim line's end: for a kick
 * the aim line is the cue-stick direction and ends at the rail
 * contact, while the ghost sits at the end of the kicked path.
 */
class GhostBallRingTest {

    private val r = 9.0
    // Practice-table rails (normals point into the table).
    private val bottom = Rail(Vec2(40.0, 340.0), Vec2(0.0, -1.0))
    private val right = Rail(Vec2(680.0, 20.0), Vec2(-1.0, 0.0))
    private val top = Rail(Vec2(0.0, 0.0), Vec2(0.0, 1.0))
    private val pockets = listOf(
        Vec2(40.0, 20.0), Vec2(360.0, 20.0), Vec2(680.0, 20.0),
        Vec2(40.0, 340.0), Vec2(360.0, 340.0), Vec2(680.0, 340.0)
    )

    @Test
    fun `direct straight shot ring sits on the ghost and touches the target`() {
        val shot = ShotSolver.solveShot(
            Vec2(100.0, 200.0), Vec2(200.0, 200.0), Vec2(400.0, 200.0), r
        )!!
        assertEquals(Vec2(182.0, 200.0), GhostBallRing.center(shot))
        assertEquals(shot.aimLine.end, GhostBallRing.center(shot))
        assertEquals(0.0, shot.cutAngleDegrees, 1e-9)
        assertEquals(9.0, GhostBallRing.radius(r), 0.0)
        assertTrue(GhostBallRing.touchesTarget(shot, Vec2(200.0, 200.0), r))
    }

    @Test
    fun `direct cut shot ring sits on the ghost and touches the target`() {
        val shot = ShotSolver.solveShot(
            Vec2(100.0, 200.0), Vec2(200.0, 200.0), Vec2(300.0, 100.0), r
        )!!
        assertEquals(187.27, GhostBallRing.center(shot).x, 0.01)
        assertEquals(212.73, GhostBallRing.center(shot).y, 0.01)
        assertEquals(53.30, shot.cutAngleDegrees, 0.01)
        assertTrue(GhostBallRing.touchesTarget(shot, Vec2(200.0, 200.0), r))
    }

    @Test
    fun `bank ring sits on the ghost which is the aim line end`() {
        val bank = ShotSolver.solveBank(
            Vec2(0.0, 200.0), Vec2(100.0, 100.0), Vec2(100.0, 50.0), top, r
        )!!
        assertEquals(Vec2(100.0, 118.0), GhostBallRing.center(bank.direct))
        assertEquals(bank.direct.aimLine.end, GhostBallRing.center(bank.direct))
        assertTrue(GhostBallRing.touchesTarget(bank.direct, Vec2(100.0, 100.0), r))
    }

    @Test
    fun `two-rail bank ring sits on the ghost which is the aim line end`() {
        val bank = ShotSolver.solveTwoRailBank(
            Vec2(580.0, 300.0), Vec2(600.0, 320.0), Vec2(360.0, 20.0), bottom, right, r
        )!!
        assertEquals(586.29, GhostBallRing.center(bank.direct).x, 0.01)
        assertEquals(308.34, GhostBallRing.center(bank.direct).y, 0.01)
        assertEquals(bank.direct.aimLine.end, GhostBallRing.center(bank.direct))
        assertEquals(12.64, bank.direct.cutAngleDegrees, 0.01)
        assertTrue(GhostBallRing.touchesTarget(bank.direct, Vec2(600.0, 320.0), r))
    }

    @Test
    fun `kick ring sits at the kicked path end, not the aim line end`() {
        // The trap this helper exists for: the kick's aim line is the
        // cue-stick direction (cue -> rail contact), so a ring drawn
        // at the aim line's end would sit on the cushion. The ghost
        // is the end of the kicked path (contact -> ghost).
        val kick = ShotSolver.solveKick(
            Vec2(120.0, 80.0), Vec2(480.0, 300.0), Vec2(360.0, 340.0), right, r
        )!!
        assertEquals(497.08, GhostBallRing.center(kick.direct).x, 0.01)
        assertEquals(294.31, GhostBallRing.center(kick.direct).y, 0.01)
        assertEquals(kick.kickPath.last().end, GhostBallRing.center(kick.direct))
        assertFalse(GhostBallRing.center(kick.direct) == kick.direct.aimLine.end)
        assertEquals(680.0, kick.direct.aimLine.end.x, 0.0)
        assertEquals(241.54, kick.direct.aimLine.end.y, 0.01)
        assertTrue(GhostBallRing.touchesTarget(kick.direct, Vec2(480.0, 300.0), r))
    }

    @Test
    fun `combo rings sit on both legs' ghosts, second at the transfer point`() {
        val combo = ShotSolver.solveCombo(
            Vec2(100.0, 300.0), Vec2(200.0, 300.0), Vec2(300.0, 300.0),
            Vec2(400.0, 300.0), r
        )!!
        assertEquals(Vec2(182.0, 300.0), GhostBallRing.center(combo.firstShot))
        assertEquals(Vec2(282.0, 300.0), combo.secondShot.ghostPoint)
        // The second leg's ghost is a different point: the first
        // ring marks the cue ball's arrival, the second ring
        // (v1.0.27) marks the first ball's arrival at the transfer
        // point between the two balls.
        assertFalse(GhostBallRing.center(combo.firstShot) == combo.secondShot.ghostPoint)
        assertTrue(GhostBallRing.touchesTarget(combo.firstShot, Vec2(200.0, 300.0), r))
        // The second ghost IS the first leg's target line end: the
        // first ball must arrive exactly where the first leg aims
        // it, and the second ring touches the second ball exactly.
        assertEquals(combo.firstShot.targetLine.end, GhostBallRing.center(combo.secondShot))
        assertTrue(GhostBallRing.touchesTarget(combo.secondShot, Vec2(300.0, 300.0), r))
    }

    @Test
    fun `kiss ring sits on the kiss ghost and touches the kissed ball`() {
        val kisses = ShotSolver.allKisses(
            Vec2(55.0, 58.0), Vec2(60.0, 40.0), listOf(Vec2(340.0, 60.0)), pockets, r
        )
        assertEquals(1, kisses.size)
        val kiss = kisses.first()
        assertEquals(58.81, GhostBallRing.center(kiss.kissShot).x, 0.01)
        assertEquals(57.96, GhostBallRing.center(kiss.kissShot).y, 0.01)
        assertEquals(kiss.kissShot.aimLine.end, GhostBallRing.center(kiss.kissShot))
        assertEquals(331.95, kiss.secondShot.ghostPoint.x, 0.01)
        assertEquals(76.10, kiss.secondShot.ghostPoint.y, 0.01)
        assertTrue(GhostBallRing.touchesTarget(kiss.kissShot, Vec2(60.0, 40.0), r))
    }

    @Test
    fun `kiss second leg ring sits on the post-kiss ghost and touches the second ball`() {
        val kisses = ShotSolver.allKisses(
            Vec2(55.0, 58.0), Vec2(60.0, 40.0), listOf(Vec2(340.0, 60.0)), pockets, r
        )
        assertEquals(1, kisses.size)
        val kiss = kisses.first()
        // The second ring (v1.0.27) marks where the cue ball must
        // arrive after the kiss to pot the second ball: a different
        // point from the kiss ghost, and the second leg's aim line
        // ends there.
        assertEquals(331.95, GhostBallRing.center(kiss.secondShot).x, 0.01)
        assertEquals(76.10, GhostBallRing.center(kiss.secondShot).y, 0.01)
        assertFalse(GhostBallRing.center(kiss.secondShot) == GhostBallRing.center(kiss.kissShot))
        assertEquals(kiss.secondShot.aimLine.end, GhostBallRing.center(kiss.secondShot))
        assertTrue(GhostBallRing.touchesTarget(kiss.secondShot, Vec2(340.0, 60.0), r))
    }

    @Test
    fun `ring radius is one ball radius and rejects a non-positive radius`() {
        assertEquals(9.0, GhostBallRing.radius(9.0), 0.0)
        assertEquals(12.5, GhostBallRing.radius(12.5), 0.0)
        assertThrows(IllegalArgumentException::class.java) { GhostBallRing.radius(0.0) }
        assertThrows(IllegalArgumentException::class.java) { GhostBallRing.radius(-3.0) }
    }

    @Test
    fun `a ring two diameters from the target does not touch it`() {
        val shot = ShotSolver.solveShot(
            Vec2(100.0, 200.0), Vec2(200.0, 200.0), Vec2(400.0, 200.0), r
        )!!
        // The ghost touches the true target; a ball one diameter
        // further away (218, 200) is a full radius clear of the ring.
        assertFalse(GhostBallRing.touchesTarget(shot, Vec2(218.0, 200.0), r))
    }
}
