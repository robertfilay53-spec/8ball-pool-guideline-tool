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
        // (225, 30), second cut 79.3 degrees). The kick must still
        // win; a combo never displaces it. The second ball sits
        // clear of the best kick's path: at (135, 30) it blocked
        // the best kick's target line, and since v1.0.22 the
        // fallback shows the first CLEAR kick, so that layout
        // now falls through to the bank instead.
        val kickCue = Vec2(200.0, 100.0)
        val kickMiddle = Vec2(200.0, 30.0)
        val second = Vec2(225.0, 30.0)
        val combo = ShotSolver.bestCombo(
            kickCue, kickMiddle, listOf(second), pockets, r
        )
        assertNotNull(combo)
        assertEquals(79.30, combo!!.secondShot.cutAngleDegrees, 0.1)
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

    // ---- one-rail re-ranking (v1.0.22) ----
    //
    // Layouts verified against the real solver with kotlinc before
    // the tests were written (see probe22/): the target sits frozen
    // near the top rail with the cue below it, so no direct shot
    // solves and the one-rail families are the only option.

    @Test
    fun `blocked best bank gives way to the next clear bank`() {
        // Cue (60,70) -> target (60,30): no direct shot; banks
        // 6.30 / 66.68 / 88.71, no kicks at all. A ball at
        // (54.5,100) sits on the best bank's second leg, so the
        // fallback shows the 66.68 bank into the bottom-middle
        // pocket, unflagged, not the blocked 6.30 one.
        val bankCue = Vec2(60.0, 70.0)
        val bankTarget = Vec2(60.0, 30.0)
        val blocker = Vec2(54.5, 100.0)
        assertTrue(ShotSolver.allShots(bankCue, bankTarget, pockets, r).isEmpty())
        val banks = ShotSolver.allBanks(
            bankCue, bankTarget, pockets, railsOf(table), r
        )
        assertEquals(6.30, banks.first().direct.cutAngleDegrees, 0.1)
        val result = solveFallback(
            bankCue, bankTarget, listOf(blocker), pockets, table, r
        )
        assertTrue(result is FallbackShot.Bank)
        val bank = (result as FallbackShot.Bank).solution
        assertEquals(66.68, bank.direct.cutAngleDegrees, 0.1)
        assertVec(Vec2(69.1, 20.0), bank.railContact)
        assertVec(Vec2(47.9, 43.3), bank.direct.ghostPoint)
        assertNull(
            com.poolguide.tool.geometry.PathBlock.blockedBy(
                bank, listOf(blocker), r
            )
        )
    }

    @Test
    fun `blocked best kick gives way to the next clear kick`() {
        // Cue (120,60) -> target (100,60): no direct shot; kicks
        // 2.48 / 4.53 / 10.03. A ball at (114.8,40) sits on the
        // best kick's path, so the fallback shows the 4.53 kick
        // off the left rail into the top-right pocket, unflagged.
        val kickCue = Vec2(120.0, 60.0)
        val kickTarget = Vec2(100.0, 60.0)
        val blocker = Vec2(114.8, 40.0)
        assertTrue(ShotSolver.allShots(kickCue, kickTarget, pockets, r).isEmpty())
        val kicks = ShotSolver.allKicks(
            kickCue, kickTarget, pockets, railsOf(table), r
        ) { contact, index, pocket ->
            bankContactIsValid(contact, index, table, pocket, r)
        }
        assertEquals(2.48, kicks.first().direct.cutAngleDegrees, 0.1)
        val result = solveFallback(
            kickCue, kickTarget, listOf(blocker), pockets, table, r
        )
        assertTrue(result is FallbackShot.Kick)
        val kick = (result as FallbackShot.Kick).solution
        assertEquals(4.53, kick.direct.cutAngleDegrees, 0.1)
        assertVec(Vec2(40.0, 60.8), kick.railContact)
        assertVec(Vec2(82.0, 61.2), kick.direct.ghostPoint)
        assertNull(
            com.poolguide.tool.geometry.PathBlock.blockedBy(
                kick, listOf(blocker), r
            )
        )
    }

    @Test
    fun `family choice compares first-clear cuts, not best cuts`() {
        // Cue (60,65) -> target (60,40): no direct shot. The best
        // bank cuts 11.92 and the best kick 44.43, so the OLD
        // comparison (best cuts) would have picked the bank, but
        // a ball at (54.1,100) blocks the best bank, and its next
        // clear bank cuts 87.42, worse than the clear 44.43 kick.
        // The fallback must show the kick, unflagged.
        val cue2 = Vec2(60.0, 65.0)
        val target2 = Vec2(60.0, 40.0)
        val blocker = Vec2(54.1, 100.0)
        assertTrue(ShotSolver.allShots(cue2, target2, pockets, r).isEmpty())
        val banks = ShotSolver.allBanks(
            cue2, target2, pockets, railsOf(table), r
        )
        val kicks = ShotSolver.allKicks(
            cue2, target2, pockets, railsOf(table), r
        ) { contact, index, pocket ->
            bankContactIsValid(contact, index, table, pocket, r)
        }
        assertEquals(11.92, banks.first().direct.cutAngleDegrees, 0.1)
        assertEquals(44.43, kicks.first().direct.cutAngleDegrees, 0.1)
        val result = solveFallback(
            cue2, target2, listOf(blocker), pockets, table, r
        )
        assertTrue(result is FallbackShot.Kick)
        val kick = (result as FallbackShot.Kick).solution
        assertEquals(44.43, kick.direct.cutAngleDegrees, 0.1)
        assertVec(Vec2(680.0, 58.8), kick.railContact)
        assertVec(Vec2(72.7, 52.7), kick.direct.ghostPoint)
        assertNull(
            com.poolguide.tool.geometry.PathBlock.blockedBy(
                kick, listOf(blocker), r
            )
        )
    }

    @Test
    fun `all-blocked one-rail shots keep the best and flag it`() {
        // Same bank layout as the re-rank test, but a ball on the
        // second leg of EVERY bank (midpoints (49.7,180),
        // (214.5,180), (379.4,180)): no bank is clear and no kick
        // solves, so the fallback keeps the best bank (6.30) and
        // the pipeline flags it via the blocker on its path.
        val bankCue = Vec2(60.0, 70.0)
        val bankTarget = Vec2(60.0, 30.0)
        val blockers = listOf(
            Vec2(49.7, 180.0), Vec2(214.5, 180.0), Vec2(379.4, 180.0)
        )
        assertTrue(ShotSolver.allShots(bankCue, bankTarget, pockets, r).isEmpty())
        val result = solveFallback(
            bankCue, bankTarget, blockers, pockets, table, r
        )
        assertTrue(result is FallbackShot.Bank)
        val bank = (result as FallbackShot.Bank).solution
        assertEquals(6.30, bank.direct.cutAngleDegrees, 0.1)
        assertEquals(
            Vec2(49.7, 180.0),
            com.poolguide.tool.geometry.PathBlock.blockedBy(bank, blockers, r)
        )
    }

    // ---- two-rail re-ranking (v1.0.23) ----
    //
    // Layouts verified against the real solver with kotlinc before
    // the tests were written (see probe23/probe23g.kt and
    // probe23j.kt). The fallback only reaches the two-rail search
    // when no direct shot and no one-rail bank or kick solves, and
    // a 90,006-layout scan found exactly four such layouts, all
    // with the cue 19 px from the target: the one-rail bank sits
    // at ~90 degrees cut, so the family flips within 0.1 px of
    // cue movement (probe23i). The layout below is one of them:
    // target (50,50) near the top-left corner, cue 19 px away at
    // 300 degrees, so no direct shot and no one-rail bank or kick
    // solves, and two two-rail banks solve: 27.93 into the
    // top-middle pocket (contacts left (40,68.5) + bottom
    // (186.9,340)) and 88.05 into the top-right pocket (contacts
    // (40,59.4) + (339,340)). The tests pin exact doubles and
    // assert the empty earlier families first, so a solver change
    // that flips the family fails loudly here instead of silently
    // showing a different shot. No demo image proves this end to
    // end (see the PR body): the cue sits 19 px from the target
    // and the best bank's ghost is ~1 px from the cue, so no
    // guideline can be drawn for the pipeline to pick the target.

    private val twoRailTarget = Vec2(50.0, 50.0)
    private val twoRailCue = run {
        val angle = Math.toRadians(300.0)
        Vec2(
            50.0 + 19.0 * kotlin.math.cos(angle),
            50.0 + 19.0 * kotlin.math.sin(angle)
        )
    }

    private fun allTwoRailBanksGuarded(cue: Vec2, target: Vec2) =
        ShotSolver.allTwoRailBanks(
            cue, target, pockets, railsOf(table), r
        ) { contact, index, pocket ->
            bankContactIsValid(contact, index, table, pocket, r)
        }

    @Test
    fun `blocked best two-rail bank gives way to the next clear two-rail bank`() {
        assertTrue(ShotSolver.allShots(twoRailCue, twoRailTarget, pockets, r).isEmpty())
        assertTrue(
            ShotSolver.allBanks(twoRailCue, twoRailTarget, pockets, railsOf(table), r).isEmpty()
        )
        assertTrue(
            ShotSolver.allKicks(twoRailCue, twoRailTarget, pockets, railsOf(table), r) { contact, index, pocket ->
                bankContactIsValid(contact, index, table, pocket, r)
            }.isEmpty()
        )
        val banks = allTwoRailBanksGuarded(twoRailCue, twoRailTarget)
        assertEquals(27.93, banks.first().direct.cutAngleDegrees, 0.1)
        // Blocker on the best bank's second-leg midpoint (113.4,204.2).
        val mid = (banks.first().bankPath[1].start + banks.first().bankPath[1].end) * 0.5
        val result = solveFallback(
            twoRailCue, twoRailTarget, listOf(mid), pockets, table, r
        )
        assertTrue(result is FallbackShot.Bank2)
        val bank = (result as FallbackShot.Bank2).solution
        assertEquals(88.05, bank.direct.cutAngleDegrees, 0.1)
        assertVec(Vec2(63.1, 37.7), bank.direct.ghostPoint)
        assertVec(Vec2(40.0, 59.4), bank.railContacts[0])
        assertVec(Vec2(339.0, 340.0), bank.railContacts[1])
        assertVec(Vec2(680.0, 20.0), bank.bankPath[2].end)
        assertNull(
            com.poolguide.tool.geometry.PathBlock.blockedBy(
                bank, listOf(mid), r
            )
        )
    }

    @Test
    fun `all-blocked two-rail banks keep the best and flag it`() {
        val banks = allTwoRailBanksGuarded(twoRailCue, twoRailTarget)
        // One blocker on each bank's second-leg midpoint:
        // (113.4,204.2) on the best bank, (189.5,199.7) on the
        // second. No bank is clear, so the fallback keeps the
        // best bank (27.93) and the pipeline flags it via the
        // first blocker on its path.
        val blockers = banks.map {
            (it.bankPath[1].start + it.bankPath[1].end) * 0.5
        }
        val result = solveFallback(
            twoRailCue, twoRailTarget, blockers, pockets, table, r
        )
        assertTrue(result is FallbackShot.Bank2)
        val bank = (result as FallbackShot.Bank2).solution
        assertEquals(27.93, bank.direct.cutAngleDegrees, 0.1)
        // The flag names the first blocker on the kept best bank's
        // path: the best bank's own leg-2 midpoint (blockers[0]).
        assertEquals(
            blockers[0],
            com.poolguide.tool.geometry.PathBlock.blockedBy(bank, blockers, r)
        )
    }

    // ---- combo / kiss blocked-path flag (v1.0.24) ----
    //
    // The helpers take the pipeline's blocker list (every ball
    // except the cue and the first/kissed ball) and remove the
    // second ball, which sits at the start of its own target
    // line and would flag its own path. Values pinned by the
    // kotlinc probe (probe24/Probe24.kt).

    @Test
    fun `comboBlockedBy ignores the second ball and returns a real blocker`() {
        val combo = ShotSolver.bestCombo(
            cue, middle, listOf(Vec2(80.0, 35.0)), pockets, r
        )!!
        // The second ball is IN the pipeline's blocker list, and
        // the chain is clear without it: no flag.
        assertNull(comboBlockedBy(combo, listOf(Vec2(80.0, 35.0)), r))
        // A blocker on the first ball's leg midpoint (61.0,38.0)
        // is returned even with the second ball in the list.
        val mid = (combo.firstShot.targetLine.start +
            combo.firstShot.targetLine.end) * 0.5
        assertVec(Vec2(61.0, 38.0), mid)
        assertVec(
            Vec2(61.0, 38.0),
            comboBlockedBy(combo, listOf(Vec2(80.0, 35.0), mid), r)!!
        )
    }

    @Test
    fun `kissBlockedBy ignores the second ball and returns a real blocker`() {
        val kiss = ShotSolver.bestKiss(
            cue, middle, listOf(Vec2(100.0, 60.0)), pockets, r
        )!!
        // The second ball is IN the pipeline's blocker list, and
        // the chain is clear without it: no flag.
        assertNull(kissBlockedBy(kiss, listOf(Vec2(100.0, 60.0)), r))
        // A blocker on the deflected leg's midpoint (69.8,59.5)
        // is returned even with the second ball in the list.
        val mid = (kiss.secondShot.aimLine.start +
            kiss.secondShot.aimLine.end) * 0.5
        assertVec(Vec2(69.8, 59.5), mid)
        assertVec(
            Vec2(69.8, 59.5),
            kissBlockedBy(kiss, listOf(Vec2(100.0, 60.0), mid), r)!!
        )
    }
}
