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

    // Practice-table layout for the bestCombo/bestKiss ranking
    // tests (v1.0.13): the middle ball near the top-left corner
    // pocket with the cue nearly touching it — verified against
    // the real solver with kotlinc before the tests were written.
    private val tablePockets = listOf(
        Vec2(40.0, 20.0), Vec2(680.0, 20.0), Vec2(680.0, 340.0),
        Vec2(40.0, 340.0), Vec2(360.0, 20.0), Vec2(360.0, 340.0)
    )
    private val rankCue = Vec2(55.0, 58.0)
    private val rankMiddle = Vec2(60.0, 40.0)
    private val rankR = 9.0

    @Test
    fun `bestCombo ranks by the second shot's cut angle`() {
        // Two second balls both pot: (80, 30) cuts 75.8 degrees
        // on the second leg, (80, 35) cuts 60.3 — the easier
        // second leg must win, and its intermediate ghost is
        // (62, 36) into the top-middle pocket.
        val best = ShotSolver.bestCombo(
            rankCue, rankMiddle,
            listOf(Vec2(80.0, 30.0), Vec2(80.0, 35.0)),
            tablePockets, rankR
        )
        assertNotNull(best)
        best!!
        assertEquals(60.29, best.secondShot.cutAngleDegrees, 0.1)
        assertEquals(Vec2(62.0, 36.0), best.intermediateGhost)
        assertEquals(Vec2(360.0, 20.0), best.secondShot.targetLine.end)
        val onlyHard = ShotSolver.bestCombo(
            rankCue, rankMiddle, listOf(Vec2(80.0, 30.0)),
            tablePockets, rankR
        )
        assertNotNull(onlyHard)
        assertEquals(75.82, onlyHard!!.secondShot.cutAngleDegrees, 0.1)
    }

    @Test
    fun `bestCombo returns null when no second ball pots`() {
        assertNull(
            ShotSolver.bestCombo(
                rankCue, rankMiddle, listOf(Vec2(300.0, 200.0)),
                tablePockets, rankR
            )
        )
        assertNull(
            ShotSolver.bestCombo(
                rankCue, rankMiddle, emptyList(), tablePockets, rankR
            )
        )
    }

    @Test
    fun `bestKiss ranks by the second shot's cut angle`() {
        // Two second balls both pot: (100, 60) cuts 11.9 degrees
        // on the second leg, (210, 60) cuts 6.4 — the easier
        // second leg must win, into the top-right pocket.
        val best = ShotSolver.bestKiss(
            rankCue, rankMiddle,
            listOf(Vec2(100.0, 60.0), Vec2(210.0, 60.0)),
            tablePockets, rankR
        )
        assertNotNull(best)
        best!!
        assertEquals(6.39, best.secondShot.cutAngleDegrees, 0.1)
        assertEquals(Vec2(680.0, 20.0), best.secondShot.targetLine.end)
        val onlyHard = ShotSolver.bestKiss(
            rankCue, rankMiddle, listOf(Vec2(100.0, 60.0)),
            tablePockets, rankR
        )
        assertNotNull(onlyHard)
        assertEquals(11.86, onlyHard!!.secondShot.cutAngleDegrees, 0.1)
    }

    @Test
    fun `bestKiss returns null when no second ball pots`() {
        assertNull(
            ShotSolver.bestKiss(
                rankCue, rankMiddle, listOf(Vec2(300.0, 200.0)),
                tablePockets, rankR
            )
        )
        assertNull(
            ShotSolver.bestKiss(
                rankCue, rankMiddle, emptyList(), tablePockets, rankR
            )
        )
    }

    @Test
    fun `bestCombo and bestKiss return null for overlapping balls`() {
        // Second ball (65, 42) is 5.4 px from the middle ball —
        // inside one diameter — so no combo or kiss exists.
        assertNull(
            ShotSolver.bestCombo(
                rankCue, rankMiddle, listOf(Vec2(65.0, 42.0)),
                tablePockets, rankR
            )
        )
        assertNull(
            ShotSolver.bestKiss(
                rankCue, rankMiddle, listOf(Vec2(65.0, 42.0)),
                tablePockets, rankR
            )
        )
        // Cue overlapping the middle ball: nothing solves either.
        val overlappingCue = Vec2(61.0, 41.0)
        assertNull(
            ShotSolver.bestCombo(
                overlappingCue, rankMiddle, listOf(Vec2(80.0, 35.0)),
                tablePockets, rankR
            )
        )
        assertNull(
            ShotSolver.bestKiss(
                overlappingCue, rankMiddle, listOf(Vec2(100.0, 60.0)),
                tablePockets, rankR
            )
        )
    }
}

class TwoRailBankTest {

    private val r = 9.0
    // Practice-table rails (normals point into the table).
    private val bottom = Rail(Vec2(40.0, 340.0), Vec2(0.0, -1.0))
    private val right = Rail(Vec2(680.0, 20.0), Vec2(-1.0, 0.0))
    private val top = Rail(Vec2(40.0, 20.0), Vec2(0.0, 1.0))
    private val left = Rail(Vec2(40.0, 20.0), Vec2(1.0, 0.0))

    // Swept layout (verified against the solver with kotlinc before
    // the tests were written): cue (580, 300), target (600, 320),
    // bottom then right rails to the top-middle pocket (360, 20).
    private val cue = Vec2(580.0, 300.0)
    private val target = Vec2(600.0, 320.0)
    private val pocket = Vec2(360.0, 20.0)

    @Test
    fun `two-rail bank contacts sit on their rails and the path connects`() {
        val bank = ShotSolver.solveTwoRailBank(cue, target, pocket, bottom, right, r)
        assertNotNull(bank)
        bank!!
        assertEquals(12.64, bank.direct.cutAngleDegrees, 0.1)
        assertTrue(!bank.direct.isThinCut)
        // First contact on the bottom rail (y=340), second on the
        // right rail (x=680).
        assertEquals(623.5, bank.railContacts[0].x, 0.5)
        assertEquals(340.0, bank.railContacts[0].y, 0.0)
        assertEquals(680.0, bank.railContacts[1].x, 0.0)
        assertEquals(292.0, bank.railContacts[1].y, 0.5)
        // Three segments, connected: target -> C1 -> C2 -> pocket.
        assertEquals(3, bank.bankPath.size)
        assertEquals(target, bank.bankPath[0].start)
        assertEquals(bank.railContacts[0], bank.bankPath[0].end)
        assertEquals(bank.railContacts[0], bank.bankPath[1].start)
        assertEquals(bank.railContacts[1], bank.bankPath[1].end)
        assertEquals(bank.railContacts[1], bank.bankPath[2].start)
        assertEquals(pocket, bank.bankPath[2].end)
        // Doubly mirrored pocket: across the right rail, then the bottom.
        assertEquals(Vec2(1000.0, 660.0), bank.mirroredPocket)
    }

    @Test
    fun `two-rail bank obeys the reflection law at both rails`() {
        val bank = ShotSolver.solveTwoRailBank(cue, target, pocket, bottom, right, r)
        assertNotNull(bank)
        bank!!
        val c1 = bank.railContacts[0]
        val c2 = bank.railContacts[1]
        // The outgoing direction from each contact is the reflection
        // of the incoming direction across that rail.
        val out1 = ShotSolver.reflectDirection(c1 - target, bottom)
        val actual1 = (c2 - c1).normalized()
        assertEquals(0.0, ShotSolver.cutAngleDegrees(out1, actual1), 1e-6)
        val out2 = ShotSolver.reflectDirection(c2 - c1, right)
        val actual2 = (pocket - c2).normalized()
        assertEquals(0.0, ShotSolver.cutAngleDegrees(out2, actual2), 1e-6)
    }

    @Test
    fun `same rail twice returns null`() {
        assertNull(ShotSolver.solveTwoRailBank(cue, target, pocket, bottom, bottom, r))
        assertNull(ShotSolver.solveTwoRailBank(cue, target, pocket, right, right, r))
    }

    @Test
    fun `pocket on either rail returns null`() {
        // Top-middle pocket sits on the top rail (y=20).
        assertNull(ShotSolver.solveTwoRailBank(cue, target, pocket, top, right, r))
        // Top-right pocket sits on the right rail (x=680).
        assertNull(
            ShotSolver.solveTwoRailBank(
                cue, target, Vec2(680.0, 20.0), bottom, right, r
            )
        )
    }

    @Test
    fun `impossible cut returns null`() {
        // Cue behind the target relative to the doubly mirrored
        // pocket (1000, 660): the cut is over 90 degrees.
        assertNull(
            ShotSolver.solveTwoRailBank(
                Vec2(700.0, 340.0), target, pocket, bottom, right, r
            )
        )
    }

    @Test
    fun `synthetic cushion correction moves both contacts and stays on the rails`() {
        // A constant 1-degree correction at each rail: the correction
        // path runs (bisection re-solves both contacts) and the bank
        // still exists, with both contacts on their rails.
        val corrected = ShotSolver.solveTwoRailBank(
            cue, target, pocket, bottom, right, r,
            CushionCorrection(1.0, 0.0, 0.0)
        )
        assertNotNull(corrected)
        corrected!!
        assertEquals(340.0, corrected.railContacts[0].y, 0.0)
        assertEquals(680.0, corrected.railContacts[1].x, 0.0)
        // The correction actually moved the contacts (the second one
        // by 1.4 px), but only by a few px — a correction is a small
        // empirical adjustment, never a large one.
        val ideal = ShotSolver.solveTwoRailBank(cue, target, pocket, bottom, right, r)!!
        assertTrue(
            corrected.railContacts[1].distanceTo(ideal.railContacts[1]) > 0.0
        )
        assertTrue(
            corrected.railContacts[1].distanceTo(ideal.railContacts[1]) < 5.0
        )
        assertTrue(
            corrected.direct.cutAngleDegrees != ideal.direct.cutAngleDegrees
        )
    }
}

class KickTest {

    private val r = 9.0
    // Practice-table rails (normals point into the table).
    private val bottom = Rail(Vec2(40.0, 340.0), Vec2(0.0, -1.0))
    private val right = Rail(Vec2(680.0, 20.0), Vec2(-1.0, 0.0))
    private val top = Rail(Vec2(40.0, 20.0), Vec2(0.0, 1.0))

    // Kick default (verified against the solver with kotlinc before
    // the tests were written): cue (120, 80), target (480, 300),
    // right rail to the bottom-middle pocket (360, 340).
    private val cue = Vec2(120.0, 80.0)
    private val target = Vec2(480.0, 300.0)
    private val pocket = Vec2(360.0, 340.0)

    @Test
    fun `kick contact sits on the rail and the path connects`() {
        val kick = ShotSolver.solveKick(cue, target, pocket, right, r)
        assertNotNull(kick)
        kick!!
        assertEquals(2.34, kick.direct.cutAngleDegrees, 0.1)
        assertTrue(!kick.direct.isThinCut)
        // Contact on the right rail (x=680).
        assertEquals(680.0, kick.railContact.x, 0.0)
        assertEquals(241.5, kick.railContact.y, 0.5)
        // Two segments, connected: cue -> contact -> ghost.
        assertEquals(2, kick.kickPath.size)
        assertEquals(cue, kick.kickPath[0].start)
        assertEquals(kick.railContact, kick.kickPath[0].end)
        assertEquals(kick.railContact, kick.kickPath[1].start)
        assertEquals(kick.direct.ghostPoint, kick.kickPath[1].end)
        // The ghost is the direct ghost for target -> pocket, and the
        // mirrored ghost is its reflection across the right rail.
        // (Component-wise: the ghost is not an exact decimal.)
        assertEquals(497.1, kick.direct.ghostPoint.x, 0.5)
        assertEquals(294.3, kick.direct.ghostPoint.y, 0.5)
        assertEquals(862.9, kick.mirroredGhost.x, 0.5)
        assertEquals(294.3, kick.mirroredGhost.y, 0.5)
        // The aim line is the cue-stick direction (cue -> contact),
        // not cue -> ghost: a single segment cannot express the
        // reflection, so it ends at the contact.
        assertEquals(cue, kick.direct.aimLine.start)
        assertEquals(kick.railContact, kick.direct.aimLine.end)
        assertEquals(target, kick.direct.targetLine.start)
        assertEquals(pocket, kick.direct.targetLine.end)
    }

    @Test
    fun `kick obeys the reflection law at the rail`() {
        // Hand case (verified against the solver with kotlinc before
        // the test was written): cue (200, 300), target (360, 180),
        // bottom rail to the top-right pocket, cut 26.6 deg.
        val kick = ShotSolver.solveKick(
            Vec2(200.0, 300.0), Vec2(360.0, 180.0), Vec2(680.0, 20.0), bottom, r
        )
        assertNotNull(kick)
        kick!!
        assertEquals(26.58, kick.direct.cutAngleDegrees, 0.1)
        assertEquals(230.0, kick.railContact.x, 0.5)
        assertEquals(340.0, kick.railContact.y, 0.0)
        // The outgoing direction from the contact is the reflection
        // of the incoming direction across the rail, and it passes
        // through the ghost point.
        val out = ShotSolver.reflectDirection(kick.railContact - Vec2(200.0, 300.0), bottom)
        val actual = (kick.direct.ghostPoint - kick.railContact).normalized()
        assertEquals(0.0, ShotSolver.cutAngleDegrees(out, actual), 1e-6)
        // Incident and outgoing angles to the rail are equal.
        val railDir = bottom.normal.perp()
        val inDir = (kick.railContact - Vec2(200.0, 300.0)).normalized()
        assertEquals(
            ShotSolver.cutAngleDegrees(inDir, railDir),
            ShotSolver.cutAngleDegrees(out, railDir),
            1e-6
        )
    }

    @Test
    fun `ghost on the rail returns null`() {
        // Ghost point exactly on the bottom rail (y=340): the mirror
        // is the ghost itself, so the kick is degenerate. Verified
        // against the solver with kotlinc before the test was written.
        assertNull(
            ShotSolver.solveKick(
                Vec2(200.0, 100.0), Vec2(510.8, 325.6), Vec2(630.8, 165.6), bottom, r
            )
        )
    }

    @Test
    fun `impossible cut returns null`() {
        // Cue behind the target relative to the mirrored ghost: the
        // cut is over 90 degrees.
        assertNull(
            ShotSolver.solveKick(
                Vec2(700.0, 340.0), Vec2(600.0, 320.0), Vec2(360.0, 20.0), bottom, r
            )
        )
    }

    @Test
    fun `overlapping balls return null`() {
        assertNull(ShotSolver.solveKick(target, target, pocket, right, r))
    }

    @Test
    fun `thin cut is flagged above 80 degrees`() {
        // Cue (200, 100), target (360, 180), top rail to the
        // top-right pocket: cut 86.4 deg. Verified against the solver
        // with kotlinc before the test was written.
        val kick = ShotSolver.solveKick(
            Vec2(200.0, 100.0), Vec2(360.0, 180.0), Vec2(680.0, 20.0), top, r
        )
        assertNotNull(kick)
        kick!!
        assertEquals(86.45, kick.direct.cutAngleDegrees, 0.1)
        assertTrue(kick.direct.isThinCut)
        assertEquals(246.4, kick.railContact.x, 0.5)
        assertEquals(20.0, kick.railContact.y, 0.0)
    }

    @Test
    fun `synthetic cushion correction moves the contact and stays on the rail`() {
        // A constant 1-degree correction at the rail: the correction
        // path runs (bisection re-solves the contact) and the kick
        // still exists, with the contact on the rail.
        val corrected = ShotSolver.solveKick(
            cue, target, pocket, right, r,
            CushionCorrection(1.0, 0.0, 0.0)
        )
        assertNotNull(corrected)
        corrected!!
        assertEquals(680.0, corrected.railContact.x, 0.0)
        // The correction actually moved the contact (by 2.6 px), but
        // only by a few px — a correction is a small empirical
        // adjustment, never a large one.
        val ideal = ShotSolver.solveKick(cue, target, pocket, right, r)!!
        assertTrue(
            corrected.railContact.distanceTo(ideal.railContact) > 0.0
        )
        assertTrue(
            corrected.railContact.distanceTo(ideal.railContact) < 5.0
        )
        assertTrue(
            corrected.direct.cutAngleDegrees != ideal.direct.cutAngleDegrees
        )
    }

    @Test
    fun `pickOneRailFallback ranks by cut angle and a tie goes to the bank`() {
        // Smaller cut wins either way.
        assertEquals(
            ShotSolver.OneRailFallback.KICK,
            ShotSolver.pickOneRailFallback(30.0, 12.0)
        )
        assertEquals(
            ShotSolver.OneRailFallback.BANK,
            ShotSolver.pickOneRailFallback(12.0, 30.0)
        )
        // A tie goes to the bank, the established fallback.
        assertEquals(
            ShotSolver.OneRailFallback.BANK,
            ShotSolver.pickOneRailFallback(20.0, 20.0)
        )
        // One side missing: the other wins outright.
        assertEquals(
            ShotSolver.OneRailFallback.KICK,
            ShotSolver.pickOneRailFallback(null, 12.0)
        )
        assertEquals(
            ShotSolver.OneRailFallback.BANK,
            ShotSolver.pickOneRailFallback(12.0, null)
        )
        // Neither solves: no fallback.
        assertEquals(
            ShotSolver.OneRailFallback.NONE,
            ShotSolver.pickOneRailFallback(null, null)
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

class TwoRailKickTest {

    private val r = 9.0
    // Practice-table rails (normals point into the table).
    private val bottom = Rail(Vec2(40.0, 340.0), Vec2(0.0, -1.0))
    private val right = Rail(Vec2(680.0, 20.0), Vec2(-1.0, 0.0))
    private val top = Rail(Vec2(40.0, 20.0), Vec2(0.0, 1.0))
    private val left = Rail(Vec2(40.0, 20.0), Vec2(1.0, 0.0))

    // Geometry layout (verified against the solver with kotlinc before
    // the tests were written): cue (140, 120), target (60, 120),
    // top then left rails to the bottom-middle pocket (360, 340).
    private val cue = Vec2(140.0, 120.0)
    private val target = Vec2(60.0, 120.0)
    private val pocket = Vec2(360.0, 340.0)

    @Test
    fun `two-rail kick contacts sit on their rails and the path connects`() {
        val kick = ShotSolver.solveTwoRailKick(cue, target, pocket, top, left, r)
        assertNotNull(kick)
        kick!!
        assertEquals(24.63, kick.direct.cutAngleDegrees, 0.1)
        assertTrue(!kick.direct.isThinCut)
        // First contact on the top rail (y=20), second on the left
        // rail (x=40).
        assertEquals(84.3, kick.railContacts[0].x, 0.5)
        assertEquals(20.0, kick.railContacts[0].y, 0.0)
        assertEquals(40.0, kick.railContacts[1].x, 0.0)
        assertEquals(99.5, kick.railContacts[1].y, 0.5)
        // Three segments, connected: cue -> C1 -> C2 -> ghost.
        assertEquals(3, kick.kickPath.size)
        assertEquals(cue, kick.kickPath[0].start)
        assertEquals(kick.railContacts[0], kick.kickPath[0].end)
        assertEquals(kick.railContacts[0], kick.kickPath[1].start)
        assertEquals(kick.railContacts[1], kick.kickPath[1].end)
        assertEquals(kick.railContacts[1], kick.kickPath[2].start)
        assertEquals(kick.direct.ghostPoint, kick.kickPath[2].end)
        // The ghost is the direct ghost for target -> pocket, and the
        // mirrored ghost is its reflection across the left rail, then
        // the top rail. (Component-wise: the ghost is not an exact
        // decimal.)
        assertEquals(45.5, kick.direct.ghostPoint.x, 0.5)
        assertEquals(109.4, kick.direct.ghostPoint.y, 0.5)
        assertEquals(34.5, kick.mirroredGhost.x, 0.5)
        assertEquals(-69.4, kick.mirroredGhost.y, 0.5)
        // The aim line is the cue-stick direction (cue -> first
        // contact), not cue -> ghost: a single segment cannot express
        // the reflections, so it ends at the first contact.
        assertEquals(cue, kick.direct.aimLine.start)
        assertEquals(kick.railContacts[0], kick.direct.aimLine.end)
        assertEquals(target, kick.direct.targetLine.start)
        assertEquals(pocket, kick.direct.targetLine.end)
    }

    @Test
    fun `two-rail kick obeys the reflection law at both rails`() {
        val kick = ShotSolver.solveTwoRailKick(cue, target, pocket, top, left, r)
        assertNotNull(kick)
        kick!!
        val c1 = kick.railContacts[0]
        val c2 = kick.railContacts[1]
        // The outgoing direction from each contact is the reflection
        // of the incoming direction across that rail, and the second
        // one passes through the ghost point.
        val out1 = ShotSolver.reflectDirection(c1 - cue, top)
        val actual1 = (c2 - c1).normalized()
        assertEquals(0.0, ShotSolver.cutAngleDegrees(out1, actual1), 1e-6)
        val out2 = ShotSolver.reflectDirection(c2 - c1, left)
        val actual2 = (kick.direct.ghostPoint - c2).normalized()
        assertEquals(0.0, ShotSolver.cutAngleDegrees(out2, actual2), 1e-6)
        // Incident and outgoing angles to each rail are equal.
        val railDir1 = top.normal.perp()
        val in1 = (c1 - cue).normalized()
        assertEquals(
            ShotSolver.cutAngleDegrees(in1, railDir1),
            ShotSolver.cutAngleDegrees(out1, railDir1),
            1e-6
        )
        val railDir2 = left.normal.perp()
        val in2 = (c2 - c1).normalized()
        assertEquals(
            ShotSolver.cutAngleDegrees(in2, railDir2),
            ShotSolver.cutAngleDegrees(out2, railDir2),
            1e-6
        )
    }

    @Test
    fun `same rail twice returns null`() {
        assertNull(ShotSolver.solveTwoRailKick(cue, target, pocket, top, top, r))
        assertNull(ShotSolver.solveTwoRailKick(cue, target, pocket, bottom, bottom, r))
    }

    @Test
    fun `ghost on either rail returns null`() {
        // Ghost point exactly on the bottom rail (y=340): the mirror
        // is the ghost itself, so the kick is degenerate. Same
        // construction as the one-rail kick test, verified against
        // the solver with kotlinc before the test was written — it
        // returns null whether the ghost's rail is first or second.
        assertNull(
            ShotSolver.solveTwoRailKick(
                Vec2(200.0, 100.0), Vec2(510.8, 325.6), Vec2(630.8, 165.6), bottom, right, r
            )
        )
        assertNull(
            ShotSolver.solveTwoRailKick(
                Vec2(200.0, 100.0), Vec2(510.8, 325.6), Vec2(630.8, 165.6), right, bottom, r
            )
        )
    }

    @Test
    fun `impossible cut returns null`() {
        // Cue behind the target relative to the doubly mirrored
        // ghost: the cut is over 90 degrees.
        assertNull(
            ShotSolver.solveTwoRailKick(
                Vec2(700.0, 340.0), Vec2(600.0, 320.0), Vec2(360.0, 20.0), bottom, right, r
            )
        )
    }

    @Test
    fun `coincident contacts return null`() {
        // The cue sits on the line from the bottom-right corner to
        // the doubly mirrored ghost, so the unfolded path crosses
        // both rails exactly at the corner: both contacts are the
        // corner, and the kick is degenerate. Verified against the
        // solver with kotlinc before the test was written.
        assertNull(
            ShotSolver.solveTwoRailKick(
                Vec2(609.3, 269.3), Vec2(600.0, 320.0), Vec2(360.0, 20.0), bottom, right, r
            )
        )
    }

    @Test
    fun `overlapping balls return null`() {
        assertNull(ShotSolver.solveTwoRailKick(target, target, pocket, top, left, r))
    }

    @Test
    fun `thin cut is flagged above 80 degrees`() {
        // Cue (60, 40), target (60, 60), top then bottom rails to
        // the top-right pocket: cut 88.0 deg. Verified against the
        // solver with kotlinc before the test was written.
        val kick = ShotSolver.solveTwoRailKick(
            Vec2(60.0, 40.0), Vec2(60.0, 60.0), Vec2(680.0, 20.0), top, bottom, r
        )
        assertNotNull(kick)
        kick!!
        assertEquals(87.97, kick.direct.cutAngleDegrees, 0.1)
        assertTrue(kick.direct.isThinCut)
        assertEquals(59.4, kick.railContacts[0].x, 0.5)
        assertEquals(20.0, kick.railContacts[0].y, 0.0)
        assertEquals(50.1, kick.railContacts[1].x, 0.5)
        assertEquals(340.0, kick.railContacts[1].y, 0.0)
    }

    @Test
    fun `synthetic cushion correction moves both contacts and stays on the rails`() {
        // A constant 1-degree correction at each rail: the correction
        // path runs (bisection re-solves both contacts) and the kick
        // still exists, with both contacts on their rails.
        val corrected = ShotSolver.solveTwoRailKick(
            cue, target, pocket, top, left, r,
            CushionCorrection(1.0, 0.0, 0.0)
        )
        assertNotNull(corrected)
        corrected!!
        assertEquals(20.0, corrected.railContacts[0].y, 0.0)
        assertEquals(40.0, corrected.railContacts[1].x, 0.0)
        // The correction actually moved the contacts (the first one
        // by 0.9 px), but only by a few px — a correction is a small
        // empirical adjustment, never a large one.
        val ideal = ShotSolver.solveTwoRailKick(cue, target, pocket, top, left, r)!!
        assertTrue(
            corrected.railContacts[0].distanceTo(ideal.railContacts[0]) > 0.0
        )
        assertTrue(
            corrected.railContacts[0].distanceTo(ideal.railContacts[0]) < 5.0
        )
        assertTrue(
            corrected.railContacts[1].distanceTo(ideal.railContacts[1]) > 0.0
        )
        assertTrue(
            corrected.railContacts[1].distanceTo(ideal.railContacts[1]) < 5.0
        )
        assertTrue(
            corrected.direct.cutAngleDegrees != ideal.direct.cutAngleDegrees
        )
    }
}
