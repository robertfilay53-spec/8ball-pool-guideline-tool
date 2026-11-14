package com.poolguide.tool.ui

import com.poolguide.tool.cv.TableRect
import com.poolguide.tool.geometry.Vec2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Offline practice-table state (v1.0.4, Cheto lead; combo/kiss modes v1.0.6; bank mode v1.0.7). */
class PracticeStateTest {

    private fun state() = PracticeState(
        TableRect(
            Vec2(40.0, 20.0), Vec2(680.0, 20.0),
            Vec2(680.0, 340.0), Vec2(40.0, 340.0)
        ),
        9.0
    )

    private fun directShot(s: PracticeState) =
        (s.solve() as PracticeState.PracticeSolution.Direct).shot

    @Test
    fun `default layout solves a shot`() {
        val s = state()
        val solution = s.solve()
        assertTrue(solution is PracticeState.PracticeSolution.Direct)
        val shot = directShot(s)
        // Cue at 25% width, target at 60%: the top-right and
        // bottom-right pockets tie at about a 35 degree cut, and the
        // first-listed pocket wins the tie.
        assertEquals(200.0, s.cue.center.x, 0.0)
        assertEquals(424.0, s.target.center.x, 0.0)
        assertTrue(shot.cutAngleDegrees > 20.0)
        assertTrue(shot.cutAngleDegrees < 40.0)
        assertEquals(680.0, shot.targetLine.end.x, 0.0)
        assertEquals(20.0, shot.targetLine.end.y, 0.0)
    }

    @Test
    fun `nudge moves the selected ball by exactly 1 px`() {
        val s = state()
        val before = s.cue.center
        s.nudge(1.0, 0.0)
        assertEquals(before.x + 1.0, s.cue.center.x, 0.0)
        assertEquals(before.y, s.cue.center.y, 0.0)
        s.nudge(0.0, -1.0)
        assertEquals(before.y - 1.0, s.cue.center.y, 0.0)
    }

    @Test
    fun `nudge moves the target when it is selected`() {
        val s = state()
        s.select(1)
        val before = s.target.center
        s.nudge(1.0, 1.0)
        assertEquals(before.x + 1.0, s.target.center.x, 0.0)
        assertEquals(before.y + 1.0, s.target.center.y, 0.0)
        // The cue did not move.
        assertEquals(200.0, s.cue.center.x, 0.0)
    }

    @Test
    fun `balls clamp inside the table`() {
        val s = state()
        s.moveBall(0, Vec2(-100.0, -100.0))
        assertEquals(49.0, s.cue.center.x, 0.0)
        assertEquals(29.0, s.cue.center.y, 0.0)
        s.moveBall(1, Vec2(9999.0, 9999.0))
        assertEquals(671.0, s.target.center.x, 0.0)
        assertEquals(331.0, s.target.center.y, 0.0)
    }

    @Test
    fun `hit test finds the nearer ball and misses empty felt`() {
        val s = state()
        assertEquals(0, s.ballAt(Vec2(200.0, 180.0))?.id)
        assertEquals(1, s.ballAt(Vec2(424.0, 180.0))?.id)
        assertNull(s.ballAt(Vec2(360.0, 100.0)))
    }

    @Test
    fun `reset restores the default layout and selection`() {
        val s = state()
        s.moveBall(0, Vec2(500.0, 300.0))
        s.select(1)
        s.reset()
        assertEquals(200.0, s.cue.center.x, 0.0)
        assertEquals(424.0, s.target.center.x, 0.0)
        assertEquals(0, s.selectedId)
    }

    @Test
    fun `overlapping balls have no shot`() {
        val s = state()
        s.moveBall(1, s.cue.center)
        assertNull(s.solve())
    }

    // ---- v1.0.7: bank mode ----

    @Test
    fun `bank default layout solves off the bottom rail to the top-right pocket`() {
        val s = state()
        s.setMode(PracticeState.PracticeMode.BANK)
        val solution = s.solve()
        assertTrue(solution is PracticeState.PracticeSolution.Bank)
        val bank = solution as PracticeState.PracticeSolution.Bank
        // Verified against the solver before the change: bottom rail,
        // top-right pocket, cut 30.8 deg, contact (502.2, 340.0).
        assertEquals("bottom", bank.railName)
        assertTrue(bank.solution.direct.cutAngleDegrees > 29.0)
        assertTrue(bank.solution.direct.cutAngleDegrees < 32.0)
        assertEquals(502.2, bank.solution.railContact.x, 0.5)
        assertEquals(340.0, bank.solution.railContact.y, 0.0)
        assertEquals(680.0, bank.solution.bankPath.last().end.x, 0.0)
        assertEquals(20.0, bank.solution.bankPath.last().end.y, 0.0)
        // The bank path is target -> rail contact -> pocket, and the
        // direct aim ends at the ghost point, not the mirrored pocket.
        assertEquals(2, bank.solution.bankPath.size)
        assertEquals(480.0, bank.solution.bankPath[0].start.x, 0.0)
        assertEquals(300.0, bank.solution.bankPath[0].start.y, 0.0)
        assertTrue(!bank.solution.direct.isThinCut)
    }

    @Test
    fun `bank mode has two balls and the middle is not selectable`() {
        val s = state()
        s.setMode(PracticeState.PracticeMode.BANK)
        assertEquals(2, s.balls().size)
        assertEquals(120.0, s.cue.center.x, 0.0)
        assertEquals(80.0, s.cue.center.y, 0.0)
        assertEquals(480.0, s.target.center.x, 0.0)
        assertEquals(300.0, s.target.center.y, 0.0)
        s.select(2)
        assertEquals(0, s.selectedId)
        assertNull(s.ballAt(Vec2(320.0, 180.0)))
    }

    @Test
    fun `bank overlapping balls have no shot`() {
        val s = state()
        s.setMode(PracticeState.PracticeMode.BANK)
        s.moveBall(1, s.cue.center)
        assertNull(s.solve())
    }

    @Test
    fun `bank reset restores the bank layout`() {
        val s = state()
        s.setMode(PracticeState.PracticeMode.BANK)
        s.moveBall(1, Vec2(500.0, 100.0))
        s.select(1)
        s.reset()
        assertEquals(120.0, s.cue.center.x, 0.0)
        assertEquals(480.0, s.target.center.x, 0.0)
        assertEquals(300.0, s.target.center.y, 0.0)
        assertEquals(0, s.selectedId)
    }

    @Test
    fun `switching to bank and back restores the direct layout`() {
        val s = state()
        s.setMode(PracticeState.PracticeMode.BANK)
        assertTrue(s.solve() is PracticeState.PracticeSolution.Bank)
        s.setMode(PracticeState.PracticeMode.DIRECT)
        assertEquals(2, s.balls().size)
        assertEquals(200.0, s.cue.center.x, 0.0)
        assertEquals(424.0, s.target.center.x, 0.0)
        assertTrue(s.solve() is PracticeState.PracticeSolution.Direct)
    }

    // ---- v1.0.6: combo mode ----

    @Test
    fun `combo default layout solves to the top-right pocket`() {
        val s = state()
        s.setMode(PracticeState.PracticeMode.COMBO)
        val solution = s.solve()
        assertTrue(solution is PracticeState.PracticeSolution.Combo)
        val combo = (solution as PracticeState.PracticeSolution.Combo).solution
        // Verified against the solver before the change: first cut
        // 6.4 deg, second cut 39.1 deg, top-right pocket.
        assertTrue(combo.firstShot.cutAngleDegrees > 5.0)
        assertTrue(combo.firstShot.cutAngleDegrees < 8.0)
        assertTrue(combo.secondShot.cutAngleDegrees > 38.0)
        assertTrue(combo.secondShot.cutAngleDegrees < 41.0)
        assertEquals(680.0, combo.secondShot.targetLine.end.x, 0.0)
        assertEquals(20.0, combo.secondShot.targetLine.end.y, 0.0)
    }

    @Test
    fun `combo mode has three balls and the middle is selectable`() {
        val s = state()
        s.setMode(PracticeState.PracticeMode.COMBO)
        assertEquals(3, s.balls().size)
        assertEquals(320.0, s.middle.center.x, 0.0)
        assertEquals(180.0, s.middle.center.y, 0.0)
        assertEquals(440.0, s.target.center.x, 0.0)
        assertEquals(2, s.ballAt(Vec2(320.0, 180.0))?.id)
        // Nudge moves the middle ball when it is selected.
        s.select(2)
        s.nudge(1.0, 0.0)
        assertEquals(321.0, s.middle.center.x, 0.0)
        assertEquals(200.0, s.cue.center.x, 0.0)
    }

    @Test
    fun `combo overlapping balls have no shot`() {
        val s = state()
        s.setMode(PracticeState.PracticeMode.COMBO)
        s.moveBall(2, s.cue.center)
        assertNull(s.solve())
        s.reset()
        s.moveBall(1, s.middle.center)
        assertNull(s.solve())
    }

    @Test
    fun `combo reset restores the combo layout`() {
        val s = state()
        s.setMode(PracticeState.PracticeMode.COMBO)
        s.moveBall(2, Vec2(500.0, 300.0))
        s.select(2)
        s.reset()
        assertEquals(320.0, s.middle.center.x, 0.0)
        assertEquals(440.0, s.target.center.x, 0.0)
        assertEquals(0, s.selectedId)
    }

    // ---- v1.0.6: kiss mode ----

    @Test
    fun `kiss default layout solves to the bottom-right pocket`() {
        val s = state()
        s.setMode(PracticeState.PracticeMode.KISS)
        val solution = s.solve()
        assertTrue(solution is PracticeState.PracticeSolution.Kiss)
        val kiss = (solution as PracticeState.PracticeSolution.Kiss).solution
        // Verified against the solver before the change: kiss cut
        // 72.2 deg, second cut 27.2 deg, bottom-right pocket.
        assertTrue(kiss.kissShot.cutAngleDegrees > 70.0)
        assertTrue(kiss.kissShot.cutAngleDegrees < 75.0)
        assertTrue(kiss.secondShot.cutAngleDegrees > 26.0)
        assertTrue(kiss.secondShot.cutAngleDegrees < 29.0)
        assertEquals(680.0, kiss.secondShot.targetLine.end.x, 0.0)
        assertEquals(340.0, kiss.secondShot.targetLine.end.y, 0.0)
    }

    @Test
    fun `kiss reset restores the kiss layout`() {
        val s = state()
        s.setMode(PracticeState.PracticeMode.KISS)
        assertEquals(320.0, s.middle.center.x, 0.0)
        assertEquals(140.0, s.middle.center.y, 0.0)
        s.moveBall(2, Vec2(100.0, 100.0))
        s.reset()
        assertEquals(320.0, s.middle.center.x, 0.0)
        assertEquals(440.0, s.target.center.x, 0.0)
    }

    // ---- v1.0.6: mode switching ----

    @Test
    fun `switching mode re-lays out and hides the middle ball in direct mode`() {
        val s = state()
        s.setMode(PracticeState.PracticeMode.COMBO)
        assertEquals(3, s.balls().size)
        s.setMode(PracticeState.PracticeMode.DIRECT)
        assertEquals(2, s.balls().size)
        assertEquals(424.0, s.target.center.x, 0.0)
        assertTrue(s.solve() is PracticeState.PracticeSolution.Direct)
        // The middle ball cannot be selected in DIRECT mode.
        s.select(2)
        assertEquals(0, s.selectedId)
        assertNull(s.ballAt(Vec2(320.0, 180.0)))
    }
}
