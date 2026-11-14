package com.poolguide.tool.geometry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Cushion-correction tests (v1.0.3, Bloro form, OUR fit).
 *
 * The harness fit is zero (no systematic angle error left after the
 * pocket-model fix), so the fitted correction must be the identity.
 * The mechanism itself is tested with synthetic coefficients: a
 * non-zero correction moves the bank contact along the rail while
 * the corrected path still reaches the pocket.
 */
class CushionCorrectionTest {

    private val r = 10.0

    @Test
    fun `fitted correction is the identity`() {
        val fitted = CushionCorrection.FITTED
        assertEquals(0.0, fitted.a0, 0.0)
        assertEquals(0.0, fitted.a1, 0.0)
        assertEquals(0.0, fitted.a2, 0.0)
        for (angle in listOf(0.0, 10.0, 30.0, 45.0, 78.0, 90.0)) {
            assertEquals("correction must be 0 at $angle deg", 0.0, fitted.correct(angle), 0.0)
        }
    }

    @Test
    fun `polynomial evaluates a0 plus a1 x plus a2 x squared`() {
        val c = CushionCorrection(0.5, 0.02, 0.0)
        assertEquals(0.5, c.correct(0.0), 1e-9)
        assertEquals(1.5, c.correct(50.0), 1e-9)
        val q = CushionCorrection(0.0, 0.0, 0.0001)
        assertEquals(0.81, q.correct(90.0), 1e-9)
    }

    @Test
    fun `correction is clamped to plus minus 2 degrees`() {
        val big = CushionCorrection(10.0, 0.0, 0.0)
        assertEquals(2.0, big.correct(45.0), 1e-9)
        val negative = CushionCorrection(-10.0, 0.0, 0.0)
        assertEquals(-2.0, negative.correct(45.0), 1e-9)
        val wild = CushionCorrection(0.0, 0.0, 0.001)
        assertEquals(2.0, wild.correct(90.0), 1e-9)
    }

    @Test
    fun `bank with the fitted correction equals the ideal bank`() {
        val rail = Rail(Vec2(0.0, 0.0), Vec2(0.0, 1.0)) // top rail y=0
        val ideal = ShotSolver.solveBank(
            Vec2(0.0, 200.0), Vec2(100.0, 100.0), Vec2(100.0, 50.0), rail, r
        )
        assertNotNull(ideal)
        ideal!!
        assertEquals(100.0, ideal.railContact.x, 1e-6)
        assertEquals(0.0, ideal.railContact.y, 1e-6)
    }

    @Test
    fun `non-zero correction moves the contact but still pots the ball`() {
        val rail = Rail(Vec2(0.0, 0.0), Vec2(0.0, 1.0)) // top rail y=0
        val cue = Vec2(0.0, 200.0)
        val target = Vec2(100.0, 100.0)
        val pocket = Vec2(100.0, 50.0)
        // +1 deg constant correction: the outgoing ray tilts, so the
        // contact must move right (~50 * tan(1 deg) = 0.87 px) for the
        // corrected ray to still reach the pocket.
        val corrected = ShotSolver.solveBank(
            cue, target, pocket, rail, r, CushionCorrection(1.0, 0.0, 0.0)
        )
        assertNotNull("corrected bank must exist", corrected)
        corrected!!
        assertEquals("contact stays on the rail", 0.0, corrected.railContact.y, 1e-6)
        assertTrue(
            "contact must move right, got ${corrected.railContact.x}",
            corrected.railContact.x > 100.5 && corrected.railContact.x < 101.5
        )
        // The bank path still ends exactly at the pocket.
        assertEquals(pocket, corrected.bankPath.last().end)
        assertEquals(target, corrected.bankPath.first().start)
        assertEquals(corrected.railContact, corrected.bankPath.first().end)
        // The aim must point at the corrected contact, not the ideal one.
        val aimEnd = corrected.direct.aimLine.end
        val ghostForCorrected = ShotSolver.ghostPoint(target, corrected.railContact, r)
        assertEquals(ghostForCorrected.x, aimEnd.x, 1e-6)
        assertEquals(ghostForCorrected.y, aimEnd.y, 1e-6)
        // And the corrected bank differs from the ideal one.
        val ideal = ShotSolver.solveBank(cue, target, pocket, rail, r)!!
        assertTrue(
            abs(corrected.railContact.x - ideal.railContact.x) > 0.5
        )
    }

    @Test
    fun `negative correction moves the contact the other way`() {
        val rail = Rail(Vec2(0.0, 0.0), Vec2(0.0, 1.0))
        val corrected = ShotSolver.solveBank(
            Vec2(0.0, 200.0), Vec2(100.0, 100.0), Vec2(100.0, 50.0),
            rail, r, CushionCorrection(-1.0, 0.0, 0.0)
        )
        assertNotNull(corrected)
        corrected!!
        assertTrue(
            "contact must move left, got ${corrected.railContact.x}",
            corrected.railContact.x < 99.5 && corrected.railContact.x > 98.5
        )
        assertEquals(Vec2(100.0, 50.0), corrected.bankPath.last().end)
    }
}
