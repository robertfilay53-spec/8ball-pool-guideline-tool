package com.poolguide.tool.cv

import com.poolguide.tool.geometry.ShotSolver
import com.poolguide.tool.geometry.Vec2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The live pipeline's fallback chain (v1.0.13). Pure geometry — no
 * OpenCV — so the whole chain is unit-testable here, including the
 * order: a combo is only tried after direct, one-rail bank,
 * one-rail kick, two-rail bank, and two-rail kick all fail, and a
 * kiss only after the combo fails too. */
class LiveFallbackTest {

    private val table = TableRect(
        Vec2(40.0, 20.0), Vec2(680.0, 20.0),
        Vec2(680.0, 340.0), Vec2(40.0, 340.0)
    )
    private val r = 9.0
    private val pockets get() = table.pockets()

    // Layouts verified against the real solver with kotlinc before
    // the tests were written (see combo13-probe/): the middle ball
    // sits near the top-left corner pocket with the cue nearly
    // touching it — the only configuration on the practice table
    // where all five earlier families fail. The cue is 18.7 px
    // from the middle ball, so every mirrored pocket direction is
    // more than ~19 degrees off the cue-to-middle line and every
    // bank/kick cut is over 90 degrees.
    private val cue = Vec2(55.0, 58.0)
    private val middle = Vec2(60.0, 40.0)

    private fun assertVec(expected: Vec2, actual: Vec2, delta: Double = 0.5) {
        assertEquals(expected.x, actual.x, delta)
        assertEquals(expected.y, actual.y, delta)
    }

    @Test
    fun `combo is only tried after all five earlier families fail`() {
        // No second ball: no direct shot, and every bank/kick
        // family fails — the fallback is None, not a combo.
        assertEquals(0, ShotSolver.allShots(cue, middle, pockets, r).size)
        assertTrue(
            solveFallback(cue, middle, emptyList(), pockets, table, r)
                is FallbackShot.None
        )

        // With a second ball at (80, 35), the combo solves: the
        // first leg cuts 84.8 degrees (thin), the second leg cuts
        // 60.3 degrees into the top-middle pocket.
        val result = solveFallback(
            cue, middle, listOf(Vec2(80.0, 35.0)), pockets, table, r
        )
        assertTrue(result is FallbackShot.Combo)
        val combo = (result as FallbackShot.Combo).solution
        assertEquals(84.76, combo.firstShot.cutAngleDegrees, 0.1)
        assertTrue(combo.firstShot.isThinCut)
        assertEquals(60.29, combo.secondShot.cutAngleDegrees, 0.1)
        assertTrue(!combo.secondShot.isThinCut)
        assertVec(Vec2(51.9, 56.1), combo.firstShot.ghostPoint)
        assertVec(Vec2(62.0, 36.0), combo.secondShot.ghostPoint)
        assertVec(Vec2(62.0, 36.0), combo.intermediateGhost)
        assertVec(Vec2(360.0, 20.0), combo.secondShot.targetLine.end)
    }

    @Test
    fun `kiss is only tried after the combo fails`() {
        // With a second ball at (100, 60), no combo exists at all
        // (the first ball cannot reach any second ghost point), so
        // the kiss is the last resort: kiss cut 78.2 degrees, then
        // the second leg cuts 11.9 degrees into the top-right
        // pocket.
        assertNull(
            ShotSolver.bestCombo(
                cue, middle, listOf(Vec2(100.0, 60.0)), pockets, r
            )
        )
        val result = solveFallback(
            cue, middle, listOf(Vec2(100.0, 60.0)), pockets, table, r
        )
        assertTrue(result is FallbackShot.Kiss)
        val kiss = (result as FallbackShot.Kiss).solution
        assertEquals(78.19, kiss.kissShot.cutAngleDegrees, 0.1)
        assertTrue(!kiss.kissShot.isThinCut)
        assertEquals(11.86, kiss.secondShot.cutAngleDegrees, 0.1)
        assertVec(Vec2(57.5, 57.8), kiss.ghostPoint)
        assertVec(Vec2(58.8, 48.9), kiss.contactPoint)
        assertVec(Vec2(82.0, 61.2), kiss.secondShot.ghostPoint)
        assertVec(Vec2(680.0, 20.0), kiss.secondShot.targetLine.end)
    }

    @Test
    fun `a bank still wins when a combo also solves`() {
        // Cue below a middle ball frozen on the top rail: the bank
        // cuts 38.9 degrees, and a combo exists too (second ball
        // at (220, 35), second cut 78.3 degrees) — the bank must
        // still win; a combo never displaces it.
        val bankCue = Vec2(200.0, 80.0)
        val bankMiddle = Vec2(200.0, 30.0)
        val second = Vec2(220.0, 35.0)
        val combo = ShotSolver.bestCombo(
            bankCue, bankMiddle, listOf(second), pockets, r
        )
        assertNotNull(combo)
        assertEquals(78.25, combo!!.secondShot.cutAngleDegrees, 0.1)
        val result = solveFallback(
            bankCue, bankMiddle, listOf(second), pockets, table, r
        )
        assertTrue(result is FallbackShot.Bank)
        assertEquals(
            38.94,
            (result as FallbackShot.Bank).solution.direct.cutAngleDegrees,
            0.1
        )
    }

    @Test
    fun `a kick still wins when a combo also solves`() {
        // Cue further below the frozen middle ball: the kick cuts
        // 0.6 degrees, and a combo exists too (second ball at
        // (135, 30), second cut 89.0 degrees) — the kick must still
        // win; a combo never displaces it.
        val kickCue = Vec2(200.0, 100.0)
        val kickMiddle = Vec2(200.0, 30.0)
        val second = Vec2(135.0, 30.0)
        val combo = ShotSolver.bestCombo(
            kickCue, kickMiddle, listOf(second), pockets, r
        )
        assertNotNull(combo)
        assertEquals(89.04, combo!!.secondShot.cutAngleDegrees, 0.1)
        val result = solveFallback(
            kickCue, kickMiddle, listOf(second), pockets, table, r
        )
        assertTrue(result is FallbackShot.Kick)
        assertEquals(
            0.61,
            (result as FallbackShot.Kick).solution.direct.cutAngleDegrees,
            0.1
        )
    }

    @Test
    fun `no fallback when no second ball pots`() {
        // A second ball far from every pocket line leaves nothing
        // to fall back to: None, not a combo or a kiss.
        assertTrue(
            solveFallback(
                cue, middle, listOf(Vec2(300.0, 200.0)),
                pockets, table, r
            ) is FallbackShot.None
        )
        assertNull(
            ShotSolver.bestCombo(
                cue, middle, listOf(Vec2(300.0, 200.0)), pockets, r
            )
        )
        assertNull(
            ShotSolver.bestKiss(
                cue, middle, listOf(Vec2(300.0, 200.0)), pockets, r
            )
        )
    }
}
