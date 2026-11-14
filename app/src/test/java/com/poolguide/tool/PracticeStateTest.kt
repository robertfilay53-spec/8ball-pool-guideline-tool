package com.poolguide.tool.ui

import com.poolguide.tool.cv.TableRect
import com.poolguide.tool.geometry.CutAngleLabel
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

    // ---- v1.0.9: two-rail banks ----

    @Test
    fun `two-rail layout solves as Bank2 off the bottom then right rails`() {
        // Swept layout, verified against the solver with kotlinc
        // before the test was written: cue (580, 300), target
        // (600, 320). The two-rail bank runs bottom then right to
        // the top-middle pocket at cut 12.6 deg; the best one-rail
        // bank there is 67.9 deg, so the two-rail bank wins by the
        // 30-degree margin rule.
        val s = state()
        s.setMode(PracticeState.PracticeMode.BANK)
        s.moveBall(0, Vec2(580.0, 300.0))
        s.moveBall(1, Vec2(600.0, 320.0))
        val solution = s.solve()
        assertTrue(solution is PracticeState.PracticeSolution.Bank2)
        val bank2 = solution as PracticeState.PracticeSolution.Bank2
        assertEquals("bottom" to "right", bank2.railNames)
        assertTrue(bank2.solution.direct.cutAngleDegrees > 11.0)
        assertTrue(bank2.solution.direct.cutAngleDegrees < 14.0)
        assertEquals(623.5, bank2.solution.railContacts[0].x, 0.5)
        assertEquals(340.0, bank2.solution.railContacts[0].y, 0.0)
        assertEquals(680.0, bank2.solution.railContacts[1].x, 0.0)
        assertEquals(292.0, bank2.solution.railContacts[1].y, 0.5)
        assertEquals(3, bank2.solution.bankPath.size)
        assertEquals(360.0, bank2.solution.bankPath.last().end.x, 0.0)
        assertEquals(20.0, bank2.solution.bankPath.last().end.y, 0.0)
        assertTrue(!bank2.solution.direct.isThinCut)
    }

    @Test
    fun `bank default still solves as Bank, not Bank2`() {
        // The v1.0.7 default has a one-rail cut of 30.8 deg and a
        // two-rail cut of 3.4 deg: the 30-degree margin keeps the
        // one-rail bank as the recommended bank.
        val s = state()
        s.setMode(PracticeState.PracticeMode.BANK)
        val solution = s.solve()
        assertTrue(solution is PracticeState.PracticeSolution.Bank)
        val bank = solution as PracticeState.PracticeSolution.Bank
        assertEquals("bottom", bank.railName)
        assertTrue(bank.solution.direct.cutAngleDegrees > 29.0)
        assertTrue(bank.solution.direct.cutAngleDegrees < 32.0)
    }

    @Test
    fun `two-rail bank with both contacts in the pocket jaws is rejected`() {
        // Target tucked in the top-left corner: every two-rail bank
        // has a contact inside a pocket's jaws (within four ball
        // radii of a corner), so the corner guards reject them all.
        // Verified against the solver with kotlinc before the test
        // was written: no guard-passing two-rail bank exists here.
        val s = state()
        s.setMode(PracticeState.PracticeMode.BANK)
        s.moveBall(0, Vec2(100.0, 80.0))
        s.moveBall(1, Vec2(60.0, 40.0))
        assertNull(s.solveBank2())
        assertNull(s.solve())
    }

    @Test
    fun `two-rail overlapping balls have no shot`() {
        val s = state()
        s.setMode(PracticeState.PracticeMode.BANK)
        s.moveBall(0, Vec2(580.0, 300.0))
        s.moveBall(1, Vec2(580.0, 300.0))
        assertNull(s.solveBank2())
    }

    // ---- v1.0.10: kick mode ----

    @Test
    fun `kick default layout solves off the right rail to the bottom-middle pocket`() {
        val s = state()
        s.setMode(PracticeState.PracticeMode.KICK)
        val solution = s.solve()
        assertTrue(solution is PracticeState.PracticeSolution.Kick)
        val kick = solution as PracticeState.PracticeSolution.Kick
        // Verified against the solver before the change: same balls
        // as the bank default, right rail, bottom-middle pocket, cut
        // 2.3 deg, contact (680.0, 241.5).
        assertEquals("right", kick.railName)
        assertTrue(kick.solution.direct.cutAngleDegrees > 1.0)
        assertTrue(kick.solution.direct.cutAngleDegrees < 4.0)
        assertEquals(680.0, kick.solution.railContact.x, 0.0)
        assertEquals(241.5, kick.solution.railContact.y, 0.5)
        assertEquals(2, kick.solution.kickPath.size)
        // The kick path ends at the ghost point, not the pocket; the
        // target line ends at the pocket.
        assertEquals(497.1, kick.solution.kickPath.last().end.x, 0.5)
        assertEquals(294.3, kick.solution.kickPath.last().end.y, 0.5)
        assertEquals(360.0, kick.solution.direct.targetLine.end.x, 0.0)
        assertEquals(340.0, kick.solution.direct.targetLine.end.y, 0.0)
        assertTrue(!kick.solution.direct.isThinCut)
    }

    @Test
    fun `kick mode has two balls and the middle is not selectable`() {
        val s = state()
        s.setMode(PracticeState.PracticeMode.KICK)
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
    fun `kick overlapping balls have no shot`() {
        val s = state()
        s.setMode(PracticeState.PracticeMode.KICK)
        s.moveBall(1, s.cue.center)
        assertNull(s.solve())
        assertNull(s.solveKick())
    }

    @Test
    fun `kick reset restores the kick layout`() {
        val s = state()
        s.setMode(PracticeState.PracticeMode.KICK)
        s.moveBall(1, Vec2(500.0, 100.0))
        s.select(1)
        s.reset()
        assertEquals(120.0, s.cue.center.x, 0.0)
        assertEquals(480.0, s.target.center.x, 0.0)
        assertEquals(300.0, s.target.center.y, 0.0)
        assertEquals(0, s.selectedId)
    }

    @Test
    fun `switching to kick and back restores the direct layout`() {
        val s = state()
        s.setMode(PracticeState.PracticeMode.KICK)
        assertTrue(s.solve() is PracticeState.PracticeSolution.Kick)
        s.setMode(PracticeState.PracticeMode.DIRECT)
        assertEquals(2, s.balls().size)
        assertEquals(200.0, s.cue.center.x, 0.0)
        assertEquals(424.0, s.target.center.x, 0.0)
        assertTrue(s.solve() is PracticeState.PracticeSolution.Direct)
    }

    @Test
    fun `kick with every contact in the pocket jaws is rejected`() {
        // Target tucked tight in the top-left corner: every kick has
        // a contact inside a pocket's jaws (within four ball radii
        // of a corner), so the corner guards reject them all.
        // Verified against the solver with kotlinc before the test
        // was written: kicks solve here, but none passes the guards.
        val s = state()
        s.setMode(PracticeState.PracticeMode.KICK)
        s.moveBall(0, Vec2(80.0, 60.0))
        s.moveBall(1, Vec2(50.0, 30.0))
        assertNull(s.solveKick())
        assertNull(s.solve())
    }

    // ---- v1.0.11: two-rail kick mode ----

    @Test
    fun `two-rail kick layout wins by the margin rule`() {
        // Verified against the solver with kotlinc before the test
        // was written: cue (60, 40), target (100, 60), bottom then
        // top rails to the bottom-left pocket, cut 16.0 deg, contacts
        // (80.4, 340.0) and (102.2, 20.0). The best one-rail kick
        // there is 58.0 deg, so the two-rail kick wins by the margin
        // rule (16.0 + 30 < 58.0).
        val s = state()
        s.setMode(PracticeState.PracticeMode.KICK)
        s.moveBall(0, Vec2(60.0, 40.0))
        s.moveBall(1, Vec2(100.0, 60.0))
        val solution = s.solve()
        assertTrue(solution is PracticeState.PracticeSolution.Kick2)
        val kick = solution as PracticeState.PracticeSolution.Kick2
        assertEquals("bottom" to "top", kick.railNames)
        assertTrue(kick.solution.direct.cutAngleDegrees > 14.0)
        assertTrue(kick.solution.direct.cutAngleDegrees < 18.0)
        assertEquals(80.4, kick.solution.railContacts[0].x, 0.5)
        assertEquals(340.0, kick.solution.railContacts[0].y, 0.0)
        assertEquals(102.2, kick.solution.railContacts[1].x, 0.5)
        assertEquals(20.0, kick.solution.railContacts[1].y, 0.0)
        assertEquals(3, kick.solution.kickPath.size)
        // The kick path ends at the ghost point, not the pocket; the
        // target line ends at the pocket.
        assertEquals(103.8, kick.solution.kickPath.last().end.x, 0.5)
        assertEquals(42.4, kick.solution.kickPath.last().end.y, 0.5)
        assertEquals(40.0, kick.solution.direct.targetLine.end.x, 0.0)
        assertEquals(340.0, kick.solution.direct.targetLine.end.y, 0.0)
        assertTrue(!kick.solution.direct.isThinCut)
    }

    @Test
    fun `kick default still solves one rail, two-rail loses on the margin`() {
        // At the kick default the best two-rail kick (3.7 deg) is
        // barely worse than the best one-rail kick (2.3 deg), so the
        // margin rule keeps the one-rail kick: 3.7 + 30 is not less
        // than 2.3. Verified against the solver with kotlinc before
        // the test was written.
        val s = state()
        s.setMode(PracticeState.PracticeMode.KICK)
        assertTrue(s.solveKick2() != null)
        val solution = s.solve()
        assertTrue(solution is PracticeState.PracticeSolution.Kick)
    }

    @Test
    fun `two-rail kick that is only slightly better loses to the one-rail kick`() {
        // Cue (140, 120), target (60, 120): the best two-rail kick
        // is 4.3 deg and the best one-rail kick is 1.0 deg — the
        // two-rail kick is worse outright, so the one-rail kick wins
        // without the margin even mattering. Verified against the
        // solver with kotlinc before the test was written.
        val s = state()
        s.setMode(PracticeState.PracticeMode.KICK)
        s.moveBall(0, Vec2(140.0, 120.0))
        s.moveBall(1, Vec2(60.0, 120.0))
        assertTrue(s.solveKick2() != null)
        val solution = s.solve()
        assertTrue(solution is PracticeState.PracticeSolution.Kick)
    }

    @Test
    fun `two-rail kick with every contact in the pocket jaws is rejected`() {
        // Same corner layout as the one-rail guard test: two-rail
        // kicks solve here, but none passes the corner guards on
        // both contacts. Verified against the solver with kotlinc
        // before the test was written.
        val s = state()
        s.setMode(PracticeState.PracticeMode.KICK)
        s.moveBall(0, Vec2(80.0, 60.0))
        s.moveBall(1, Vec2(50.0, 30.0))
        assertNull(s.solveKick2())
        assertNull(s.solve())
    }

    @Test
    fun `two-rail kick overlapping balls have no shot`() {
        val s = state()
        s.setMode(PracticeState.PracticeMode.KICK)
        s.moveBall(1, s.cue.center)
        assertNull(s.solveKick2())
        assertNull(s.solve())
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

    // ---- v1.0.12: cut-angle readout + thin-cut warning ----

    @Test
    fun `default layouts label with their solver cut angles`() {
        // Pinned against the solver with kotlinc before the test was
        // written (cut12-probe/Probe12.kt): DIRECT 34.62, BANK 30.77,
        // KICK 2.34, COMBO first 6.39 / second 39.12, KISS kiss 72.20 /
        // second 27.22. None of them is thin, so no warning text.
        val s = state()
        val direct = directShot(s)
        assertEquals("34.6°", CutAngleLabel.format(direct.cutAngleDegrees))
        assertNull(CutAngleLabel.warningText(direct.cutAngleDegrees))

        s.setMode(PracticeState.PracticeMode.BANK)
        val bank = (s.solve() as PracticeState.PracticeSolution.Bank).solution
        assertEquals("30.8°", CutAngleLabel.format(bank.direct.cutAngleDegrees))
        assertNull(CutAngleLabel.warningText(bank.direct.cutAngleDegrees))

        s.setMode(PracticeState.PracticeMode.KICK)
        val kick = (s.solve() as PracticeState.PracticeSolution.Kick).solution
        assertEquals("2.3°", CutAngleLabel.format(kick.direct.cutAngleDegrees))
        assertNull(CutAngleLabel.warningText(kick.direct.cutAngleDegrees))

        s.setMode(PracticeState.PracticeMode.COMBO)
        val combo = (s.solve() as PracticeState.PracticeSolution.Combo).solution
        assertEquals("6.4°", CutAngleLabel.format(combo.firstShot.cutAngleDegrees))
        assertEquals("39.1°", CutAngleLabel.format(combo.secondShot.cutAngleDegrees))
        assertNull(CutAngleLabel.warningText(combo.firstShot.cutAngleDegrees))
        assertNull(CutAngleLabel.warningText(combo.secondShot.cutAngleDegrees))

        s.setMode(PracticeState.PracticeMode.KISS)
        val kiss = (s.solve() as PracticeState.PracticeSolution.Kiss).solution
        assertEquals("72.2°", CutAngleLabel.format(kiss.kissShot.cutAngleDegrees))
        assertEquals("27.2°", CutAngleLabel.format(kiss.secondShot.cutAngleDegrees))
        assertNull(CutAngleLabel.warningText(kiss.kissShot.cutAngleDegrees))
        assertNull(CutAngleLabel.warningText(kiss.secondShot.cutAngleDegrees))
    }

    @Test
    fun `thin layout labels with a warning`() {
        // Verified against the solver with kotlinc before the test
        // was written: cue (620, 260), target (620, 300) solves to
        // the bottom-right pocket at 82.83 deg — thin, but not
        // impossible (the solver returns null past 90).
        val s = state()
        s.moveBall(0, Vec2(620.0, 260.0))
        s.moveBall(1, Vec2(620.0, 300.0))
        val shot = directShot(s)
        assertTrue(shot.isThinCut)
        assertEquals("82.8°", CutAngleLabel.format(shot.cutAngleDegrees))
        assertEquals("Thin cut 82.8°", CutAngleLabel.warningText(shot.cutAngleDegrees))
    }
}
