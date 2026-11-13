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
        val inset = 200.0 * 0.015
        assertEquals(Vec2(inset, inset), pockets[0])
        assertEquals(Vec2(200.0 - inset, inset), pockets[1])
        assertEquals(Vec2(200.0 - inset, 100.0 - inset), pockets[2])
        assertEquals(Vec2(inset, 100.0 - inset), pockets[3])
        assertEquals(Vec2(100.0, inset), pockets[4])
        assertEquals(Vec2(100.0, 100.0 - inset), pockets[5])
    }
}
