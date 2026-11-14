package com.poolguide.tool.geometry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class Vec2Test {

    /** Component-wise comparison: exact Vec2 equality is too strict for
     * floating point (e.g. -0.0 vs 0.0, 0.6000000000000001 vs 0.6). */
    private fun assertVecEquals(expected: Vec2, actual: Vec2, delta: Double = 1e-9) {
        assertEquals(expected.x, actual.x, delta)
        assertEquals(expected.y, actual.y, delta)
    }

    @Test
    fun `plus minus times dot norm`() {
        val a = Vec2(3.0, 4.0)
        assertVecEquals(Vec2(5.0, 6.0), a + Vec2(2.0, 2.0))
        assertVecEquals(Vec2(1.0, 2.0), a - Vec2(2.0, 2.0))
        assertVecEquals(Vec2(6.0, 8.0), a * 2.0)
        assertEquals(25.0, a.dot(a), 1e-9)
        assertEquals(5.0, a.norm(), 1e-9)
        assertVecEquals(Vec2(0.6, 0.8), a.normalized())
    }

    @Test
    fun `normalized zero vector returns zero not NaN`() {
        val n = Vec2(0.0, 0.0).normalized()
        assertEquals(0.0, n.x, 0.0)
        assertEquals(0.0, n.y, 0.0)
        assertTrue(!n.x.isNaN() && !n.y.isNaN())
    }

    @Test
    fun `perp rotates 90 degrees counter-clockwise`() {
        assertVecEquals(Vec2(0.0, 1.0), Vec2(1.0, 0.0).perp())
        assertVecEquals(Vec2(-1.0, 0.0), Vec2(0.0, 1.0).perp())
    }
}

class ShotSolverTest {

    private val r = 10.0

    @Test
    fun `ghost point is one diameter from the target toward the pocket`() {
        val g = ShotSolver.ghostPoint(Vec2(100.0, 100.0), Vec2(200.0, 100.0), r)
        assertEquals(Vec2(80.0, 100.0), g)
        assertEquals(2 * r, g.distanceTo(Vec2(100.0, 100.0)), 1e-9)
    }

    @Test
    fun `straight shot has cut angle zero and zero deflection`() {
        val shot = ShotSolver.solveShot(
            Vec2(0.0, 100.0), Vec2(100.0, 100.0), Vec2(200.0, 100.0), r
        )
        assertNotNull(shot)
        shot!!
        assertEquals(Vec2(80.0, 100.0), shot.ghostPoint)
        assertEquals(0.0, shot.cutAngleDegrees, 1e-6)
        assertTrue(!shot.isThinCut)
        assertEquals(0.0, shot.deflectionLine.length(), 1e-6)
    }

    @Test
    fun `cut shot geometry matches hand calculation`() {
        // Cue (0,-50), target (100,0), pocket (100,100): ghost (100,-20),
        // aim (0.958, 0.287), cut angle ~73.3 deg, deflection +x.
        val shot = ShotSolver.solveShot(
            Vec2(0.0, -50.0), Vec2(100.0, 0.0), Vec2(100.0, 100.0), r
        )
        assertNotNull(shot)
        shot!!
        assertEquals(100.0, shot.ghostPoint.x, 1e-6)
        assertEquals(-20.0, shot.ghostPoint.y, 1e-6)
        assertEquals(73.3, shot.cutAngleDegrees, 0.1)
        assertTrue(!shot.isThinCut)
        val tangent = shot.deflectionLine.direction()
        assertEquals(1.0, tangent.x, 1e-6)
        assertEquals(0.0, tangent.y, 1e-6)
    }

    @Test
    fun `thin cut is flagged above 80 degrees`() {
        // Cue (0,-30), target (100,0), pocket (100,100): ghost (100,-20),
        // aim (0.995, 0.0995), cut angle ~84.3 deg — a thin cut.
        val shot = ShotSolver.solveShot(
            Vec2(0.0, -30.0), Vec2(100.0, 0.0), Vec2(100.0, 100.0), r
        )
        assertNotNull(shot)
        shot!!
        assertEquals(84.3, shot.cutAngleDegrees, 0.2)
        assertTrue(shot.isThinCut)
    }

    @Test
    fun `shot from the wrong side is impossible`() {
        // Cue approaches from above while the pocket is above the target:
        // the cue would have to push the target down to send it up.
        val shot = ShotSolver.solveShot(
            Vec2(0.0, 0.0), Vec2(100.0, 0.0), Vec2(100.0, 100.0), r
        )
        assertNull(shot)
    }

    @Test
    fun `overlapping balls return null`() {
        assertNull(
            ShotSolver.solveShot(
                Vec2(100.0, 100.0), Vec2(105.0, 100.0), Vec2(200.0, 100.0), r
            )
        )
    }

    @Test
    fun `mirror pocket across a horizontal rail`() {
        val rail = Rail(Vec2(0.0, 0.0), Vec2(0.0, 1.0))
        val mirrored = ShotSolver.mirrorPointAcrossRail(Vec2(100.0, 50.0), rail)
        assertEquals(Vec2(100.0, -50.0), mirrored)
    }

    @Test
    fun `reflection flips the normal component`() {
        val rail = Rail(Vec2(0.0, 0.0), Vec2(0.0, -1.0)) // bottom rail, normal up
        val out = ShotSolver.reflectDirection(Vec2(1.0, 1.0), rail)
        assertEquals(0.7071, out.x, 1e-3)
        assertEquals(-0.7071, out.y, 1e-3)
    }

    @Test
    fun `bank shot contact point is on the rail`() {
        val rail = Rail(Vec2(0.0, 0.0), Vec2(0.0, 1.0)) // top rail y=0
        val bank = ShotSolver.solveBank(
            Vec2(0.0, 200.0), Vec2(100.0, 100.0), Vec2(100.0, 50.0), rail, r
        )
        assertNotNull(bank)
        bank!!
        // Mirrored pocket is (100,-50); the path T->P' crosses y=0 at (100,0).
        assertEquals(100.0, bank.railContact.x, 1e-6)
        assertEquals(0.0, bank.railContact.y, 1e-6)
        assertEquals(2, bank.bankPath.size)
    }

    @Test
    fun `cut angle helper agrees with the solver`() {
        val angle = ShotSolver.cutAngleDegrees(Vec2(1.0, 0.0), Vec2(0.0, 1.0))
        assertEquals(90.0, angle, 1e-6)
        assertEquals(0.0, ShotSolver.cutAngleDegrees(Vec2(1.0, 0.0), Vec2(1.0, 0.0)), 1e-6)
    }

    @Test
    fun `pocket tie within one degree goes to the first-listed pocket`() {
        // Symmetric layout (the synthetic green table): cue (197,202),
        // target (394,202), top-right and bottom-right pockets tie at
        // ~30 degrees. The first-listed pocket must win so detection
        // noise cannot flip the pick.
        val cue = Vec2(197.0, 202.0)
        val target = Vec2(394.0, 202.0)
        val topRight = Vec2(666.5, 60.5)
        val bottomRight = Vec2(666.5, 344.5)
        val best = ShotSolver.bestShot(cue, target, listOf(topRight, bottomRight), 9.0)
        assertNotNull(best)
        best!!
        assertEquals(topRight, best.targetLine.end)
        assertEquals(30.2, best.cutAngleDegrees, 0.5)
    }

    @Test
    fun `a clearly better pocket wins over list order`() {
        // First-listed pocket is a ~60 degree cut; the second is ~30.
        // A 30-degree gap is far outside the 1-degree tie margin.
        val cue = Vec2(197.0, 202.0)
        val target = Vec2(394.0, 202.0)
        val bad = Vec2(500.0, 50.0)
        val good = Vec2(666.5, 344.5)
        val best = ShotSolver.bestShot(cue, target, listOf(bad, good), 9.0)
        assertNotNull(best)
        best!!
        assertEquals(good, best.targetLine.end)
        assertEquals(30.2, best.cutAngleDegrees, 0.5)
    }

    @Test
    fun `no reachable pocket returns null`() {
        val cue = Vec2(197.0, 202.0)
        val target = Vec2(394.0, 202.0)
        // Both pockets sit behind the target relative to the cue.
        val behind = listOf(Vec2(100.0, 50.0), Vec2(100.0, 350.0))
        assertNull(ShotSolver.bestShot(cue, target, behind, 9.0))
    }

    @Test
    fun `allShots returns every reachable shot best first`() {
        val cue = Vec2(197.0, 202.0)
        val target = Vec2(394.0, 202.0)
        val bad = Vec2(500.0, 50.0)
        val good = Vec2(666.5, 344.5)
        val all = ShotSolver.allShots(cue, target, listOf(bad, good), 9.0)
        assertEquals(2, all.size)
        assertEquals(good, all[0].targetLine.end)
        assertEquals(bad, all[1].targetLine.end)
        assertTrue(all[0].cutAngleDegrees < all[1].cutAngleDegrees)
    }

    @Test
    fun `allShots keeps pocket order on a symmetric tie`() {
        val cue = Vec2(197.0, 202.0)
        val target = Vec2(394.0, 202.0)
        // Exactly symmetric about y=202: both cuts are identical, so
        // the 1-degree tie margin keeps the pocket list order.
        val topRight = Vec2(666.5, 60.5)
        val bottomRight = Vec2(666.5, 343.5)
        val all = ShotSolver.allShots(cue, target, listOf(topRight, bottomRight), 9.0)
        assertEquals(2, all.size)
        assertEquals(topRight, all[0].targetLine.end)
        assertEquals(bottomRight, all[1].targetLine.end)
        assertEquals(all[0].cutAngleDegrees, all[1].cutAngleDegrees, 1e-6)
    }

    @Test
    fun `allShots is empty when nothing is reachable`() {
        val behind = listOf(Vec2(100.0, 50.0), Vec2(100.0, 350.0))
        assertTrue(ShotSolver.allShots(Vec2(197.0, 202.0), Vec2(394.0, 202.0), behind, 9.0).isEmpty())
    }
}

class ComboKissTest {

    private val r = 10.0

    @Test
    fun `straight combo chains two ghost points`() {
        // Cue, first, second, pocket all on one line: G2 = (180,100),
        // G1 = (80,100), both cuts 0 degrees.
        val combo = ShotSolver.solveCombo(
            Vec2(0.0, 100.0), Vec2(100.0, 100.0),
            Vec2(200.0, 100.0), Vec2(300.0, 100.0), r
        )
        assertNotNull(combo)
        combo!!
        assertEquals(Vec2(180.0, 100.0), combo.intermediateGhost)
        assertEquals(Vec2(80.0, 100.0), combo.firstShot.ghostPoint)
        assertEquals(0.0, combo.firstShot.cutAngleDegrees, 1e-6)
        assertEquals(Vec2(180.0, 100.0), combo.secondShot.ghostPoint)
        assertEquals(0.0, combo.secondShot.cutAngleDegrees, 1e-6)
    }

    @Test
    fun `combo with a cut on the first ball`() {
        // First ball cut 51.3 degrees toward G2 (aim (80,100) from
        // (0,0)); second leg straight.
        val combo = ShotSolver.solveCombo(
            Vec2(0.0, 0.0), Vec2(100.0, 100.0),
            Vec2(200.0, 100.0), Vec2(300.0, 100.0), r
        )
        assertNotNull(combo)
        combo!!
        assertEquals(Vec2(180.0, 100.0), combo.intermediateGhost)
        assertEquals(51.3, combo.firstShot.cutAngleDegrees, 0.5)
        assertEquals(Vec2(180.0, 100.0), combo.secondShot.ghostPoint)
        assertEquals(0.0, combo.secondShot.cutAngleDegrees, 1e-6)
    }

    @Test
    fun `combo is impossible when the first ball cannot reach the second ghost point`() {
        // First ball sits behind the second relative to the cue: the
        // first leg needs a 180-degree cut.
        assertNull(
            ShotSolver.solveCombo(
                Vec2(0.0, 100.0), Vec2(200.0, 100.0),
                Vec2(100.0, 100.0), Vec2(300.0, 100.0), r
            )
        )
    }

    @Test
    fun `combo is impossible when the balls overlap`() {
        assertNull(
            ShotSolver.solveCombo(
                Vec2(0.0, 100.0), Vec2(100.0, 100.0),
                Vec2(105.0, 100.0), Vec2(300.0, 100.0), r
            )
        )
    }

    @Test
    fun `kiss deflects the cue into the target ghost point`() {
        // Cue left of the kissed ball; the e=0.95 bisection refines the
        // contact normal so the deflected ray passes exactly through the
        // red ball's ghost point (180,100) for the top-right pocket.
        // Refined ghost (104.17, 119.56), contact (102.08, 109.78).
        val kiss = ShotSolver.solveKiss(
            Vec2(50.0, 250.0), Vec2(100.0, 100.0),
            Vec2(200.0, 100.0), Vec2(300.0, 100.0), r
        )
        assertNotNull(kiss)
        kiss!!
        assertEquals(104.2, kiss.ghostPoint.x, 1.0)
        assertEquals(119.6, kiss.ghostPoint.y, 1.0)
        assertEquals(102.1, kiss.contactPoint.x, 1.0)
        assertEquals(109.8, kiss.contactPoint.y, 1.0)
        // The first leg's ghost point is the same point, recomputed
        // inside solveShot via a normalized() call — compare with a
        // tolerance, not exact equality: the normalize rounds the
        // last floating-point bit (exact assertEquals failed in CI).
        assertEquals(kiss.ghostPoint.x, kiss.kissShot.ghostPoint.x, 1e-9)
        assertEquals(kiss.ghostPoint.y, kiss.kissShot.ghostPoint.y, 1e-9)
        assertEquals(Vec2(180.0, 100.0), kiss.secondShot.ghostPoint)
        // The refined deflection ray from the ghost point must point at
        // the target's ghost point (the bisection root).
        val toGhost = (Vec2(180.0, 100.0) - kiss.ghostPoint).normalized()
        val defl = kiss.kissShot.deflectionLine.direction()
        assertEquals(1.0, toGhost.dot(defl), 0.002)
    }

    @Test
    fun `kiss without a bracketing root still solves within one ball radius`() {
        // Cue nearly straight below the kissed ball: the residual does
        // not change sign across the +/-30 degree bracket, so no
        // refinement runs and the e=0.95 deflection is ~7 degrees off
        // the ideal ray — but the ray still strikes the target (16.8 px
        // off its centre, inside one diameter), so the kiss solves.
        val kiss = ShotSolver.solveKiss(
            Vec2(105.0, 200.0), Vec2(100.0, 100.0),
            Vec2(200.0, 100.0), Vec2(300.0, 100.0), r
        )
        assertNotNull(kiss)
        kiss!!
        assertEquals(105.0, kiss.ghostPoint.x, 1.0)
        assertEquals(119.4, kiss.ghostPoint.y, 1.0)
        assertEquals(Vec2(180.0, 100.0), kiss.secondShot.ghostPoint)
        val toGhost = (Vec2(180.0, 100.0) - kiss.ghostPoint).normalized()
        val defl = kiss.kissShot.deflectionLine.direction()
        assertTrue(toGhost.dot(defl) > 0.99)
    }

    @Test
    fun `kiss is impossible when the deflection points away from the target`() {
        // Cue right of the kissed ball, approaching from the pocket
        // side: the only reachable contact normal deflects the cue away
        // from the target's ghost point (anti-parallel ray).
        assertNull(
            ShotSolver.solveKiss(
                Vec2(250.0, 200.0), Vec2(100.0, 100.0),
                Vec2(200.0, 100.0), Vec2(300.0, 100.0), r
            )
        )
    }

    @Test
    fun `kiss is impossible when DEST sits inside the kissed ball`() {
        // Target (115,100) -> pocket (300,100): ghost (95,100) is 5 px
        // from the kissed ball's centre, inside one diameter.
        assertNull(
            ShotSolver.solveKiss(
                Vec2(105.0, 200.0), Vec2(100.0, 100.0),
                Vec2(115.0, 100.0), Vec2(300.0, 100.0), r
            )
        )
    }

    @Test
    fun `kiss is impossible from directly behind the kissed ball`() {
        // Cue on the pocket side of the kissed ball: neither mirror
        // contact point is on the cue's approach side.
        assertNull(
            ShotSolver.solveKiss(
                Vec2(0.0, 100.0), Vec2(100.0, 100.0),
                Vec2(200.0, 100.0), Vec2(300.0, 100.0), r
            )
        )
    }
}

class TableGeometryTest {

    @Test
    fun `six pockets at the corners and side midpoints`() {
        val table = com.poolguide.tool.cv.TableRect(
            Vec2(0.0, 0.0), Vec2(200.0, 0.0),
            Vec2(200.0, 100.0), Vec2(0.0, 100.0)
        )
        val pockets = table.pockets()
        assertEquals(6, pockets.size)
        // v1.0.3: the inset is fitted from ground truth — the pockets
        // sit exactly on the corners (inset 0), not 1.5% inside.
        val inset = 200.0 * com.poolguide.tool.cv.CvConfig.POCKET_INSET_FRACTION
        assertEquals(Vec2(inset, inset), pockets[0])
        assertEquals(Vec2(200.0 - inset, inset), pockets[1])
        assertEquals(Vec2(200.0 - inset, 100.0 - inset), pockets[2])
        assertEquals(Vec2(inset, 100.0 - inset), pockets[3])
        assertEquals(Vec2(100.0, inset), pockets[4])
        assertEquals(Vec2(100.0, 100.0 - inset), pockets[5])
    }
}
