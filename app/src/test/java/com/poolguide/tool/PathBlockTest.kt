package com.poolguide.tool

import com.poolguide.tool.geometry.BlockedPathLabel
import com.poolguide.tool.geometry.CutAngleLabel
import com.poolguide.tool.geometry.PathBlock
import com.poolguide.tool.geometry.Rail
import com.poolguide.tool.geometry.ShotSolver
import com.poolguide.tool.geometry.Vec2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Blocked-path check (v1.0.19). All numbers pinned against the real
 * code first (kotlinc probe, /tmp/probe19/Probe19.kt): the straight
 * shot cue (100,200) -> target (300,200) -> pocket (680,200) has
 * ghost (282,200) and cut 0.0; the cut shot to the best pocket has
 * cut 22.2 deg, cut-angle anchor (192.3,219.4), blocked anchor
 * (190.8,174.4); best bank cut 8.6, best kick 6.1, best two-rail
 * bank 6.6, best two-rail kick 1.1 (best across all pockets/rails,
 * no corner guards — the tests place blockers on the solved paths'
 * midpoints, so they hold for whatever the best solution is).
 */
class PathBlockTest {

    private val r = 9.0
    private val cue = Vec2(100.0, 200.0)
    private val target = Vec2(300.0, 200.0)
    private val pockets = listOf(
        Vec2(40.0, 20.0), Vec2(680.0, 20.0), Vec2(680.0, 340.0),
        Vec2(40.0, 340.0), Vec2(360.0, 20.0), Vec2(360.0, 340.0)
    )
    private val rails = listOf(
        Rail(Vec2(0.0, 20.0), Vec2(0.0, 1.0)),
        Rail(Vec2(0.0, 340.0), Vec2(0.0, -1.0)),
        Rail(Vec2(40.0, 0.0), Vec2(1.0, 0.0)),
        Rail(Vec2(680.0, 0.0), Vec2(-1.0, 0.0))
    )

    /** Straight shot down y=200: ghost (282,200), aim (100,200)->
     * (282,200), target (300,200)->(680,200). */
    private val straight = ShotSolver.solveShot(
        cue, target, Vec2(680.0, 200.0), r
    )!!

    // ---- distancePointToSegment ----

    @Test
    fun distance_pointOnSegmentIsZero() {
        assertEquals(
            0.0,
            PathBlock.distancePointToSegment(
                Vec2(200.0, 200.0), Vec2(100.0, 200.0), Vec2(282.0, 200.0)
            ),
            1e-9
        )
    }

    @Test
    fun distance_clampsToEndpoints() {
        // Behind the start and past the end both measure to the
        // nearest endpoint, not to the infinite line.
        assertEquals(
            50.0,
            PathBlock.distancePointToSegment(
                Vec2(50.0, 200.0), Vec2(100.0, 200.0), Vec2(282.0, 200.0)
            ),
            1e-9
        )
        assertEquals(
            20.0,
            PathBlock.distancePointToSegment(
                Vec2(700.0, 200.0), Vec2(300.0, 200.0), Vec2(680.0, 200.0)
            ),
            1e-9
        )
    }

    @Test
    fun distance_zeroLengthSegmentMeasuresToPoint() {
        assertEquals(
            5.0,
            PathBlock.distancePointToSegment(
                Vec2(3.0, 4.0), Vec2(0.0, 0.0), Vec2(0.0, 0.0)
            ),
            1e-9
        )
    }

    // ---- direct shot ----

    @Test
    fun direct_clearPathReturnsNull() {
        assertNull(PathBlock.blockedBy(straight, emptyList(), r))
        assertNull(
            PathBlock.blockedBy(straight, listOf(Vec2(500.0, 230.0)), r)
        )
    }

    @Test
    fun direct_blockerOnAimLineIsReturned() {
        assertEquals(
            Vec2(200.0, 200.0),
            PathBlock.blockedBy(straight, listOf(Vec2(200.0, 200.0)), r)
        )
    }

    @Test
    fun direct_blockerOnTargetLineIsReturned() {
        assertEquals(
            Vec2(500.0, 200.0),
            PathBlock.blockedBy(straight, listOf(Vec2(500.0, 200.0)), r)
        )
    }

    @Test
    fun direct_grazeAtExactlyTwoRadiiIsClear() {
        // 18 px off the aim line is exactly one diameter: a graze,
        // not a hit.
        assertNull(
            PathBlock.blockedBy(straight, listOf(Vec2(200.0, 218.0)), r)
        )
    }

    @Test
    fun direct_justInsideTwoRadiiIsBlocked() {
        assertEquals(
            Vec2(200.0, 217.9),
            PathBlock.blockedBy(straight, listOf(Vec2(200.0, 217.9)), r)
        )
    }

    @Test
    fun direct_ballPastPocketOrBehindCueDoesNotBlock() {
        assertNull(
            PathBlock.blockedBy(straight, listOf(Vec2(700.0, 200.0)), r)
        )
        assertNull(
            PathBlock.blockedBy(straight, listOf(Vec2(50.0, 200.0)), r)
        )
    }

    @Test
    fun direct_firstBlockerAlongPathWins() {
        // Two blockers on the target line: the nearer the cue wins.
        assertEquals(
            Vec2(400.0, 200.0),
            PathBlock.blockedBy(
                straight,
                listOf(Vec2(500.0, 200.0), Vec2(400.0, 200.0)), r
            )
        )
        // A blocker on the aim line beats one on the target line:
        // the earliest segment wins.
        assertEquals(
            Vec2(200.0, 200.0),
            PathBlock.blockedBy(
                straight,
                listOf(Vec2(500.0, 200.0), Vec2(200.0, 200.0)), r
            )
        )
    }

    @Test
    fun segmentBlockedBy_rejectsNonPositiveRadius() {
        try {
            PathBlock.segmentBlockedBy(
                Vec2(0.0, 0.0), Vec2(1.0, 0.0), Vec2(0.5, 0.0), 0.0
            )
            assertFalse("zero radius must throw", true)
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("ballRadiusPx must be positive"))
        }
    }

    // ---- bank / kick / two-rail: blockers on solved-path midpoints ----

    private fun bestBank(): ShotSolver.BankSolution {
        var best: ShotSolver.BankSolution? = null
        var bestCut = Double.MAX_VALUE
        for (pocket in pockets) for (rail in rails) {
            val bank = ShotSolver.solveBank(cue, target, pocket, rail, r) ?: continue
            if (bank.direct.cutAngleDegrees < bestCut) {
                best = bank
                bestCut = bank.direct.cutAngleDegrees
            }
        }
        return best!!
    }

    private fun bestKick(): ShotSolver.KickSolution {
        var best: ShotSolver.KickSolution? = null
        var bestCut = Double.MAX_VALUE
        for (pocket in pockets) for (rail in rails) {
            val kick = ShotSolver.solveKick(cue, target, pocket, rail, r) ?: continue
            if (kick.direct.cutAngleDegrees < bestCut) {
                best = kick
                bestCut = kick.direct.cutAngleDegrees
            }
        }
        return best!!
    }

    private fun bestBank2(): ShotSolver.TwoRailBankSolution {
        var best: ShotSolver.TwoRailBankSolution? = null
        var bestCut = Double.MAX_VALUE
        for (pocket in pockets) {
            for ((i1, rail1) in rails.withIndex()) {
                for ((i2, rail2) in rails.withIndex()) {
                    if (i1 == i2) continue
                    val bank = ShotSolver.solveTwoRailBank(
                        cue, target, pocket, rail1, rail2, r
                    ) ?: continue
                    if (bank.direct.cutAngleDegrees < bestCut) {
                        best = bank
                        bestCut = bank.direct.cutAngleDegrees
                    }
                }
            }
        }
        return best!!
    }

    private fun bestKick2(): ShotSolver.TwoRailKickSolution {
        var best: ShotSolver.TwoRailKickSolution? = null
        var bestCut = Double.MAX_VALUE
        for (pocket in pockets) {
            for ((i1, rail1) in rails.withIndex()) {
                for ((i2, rail2) in rails.withIndex()) {
                    if (i1 == i2) continue
                    val kick = ShotSolver.solveTwoRailKick(
                        cue, target, pocket, rail1, rail2, r
                    ) ?: continue
                    if (kick.direct.cutAngleDegrees < bestCut) {
                        best = kick
                        bestCut = kick.direct.cutAngleDegrees
                    }
                }
            }
        }
        return best!!
    }

    @Test
    fun bank_blockerOnSecondLegIsReturned() {
        val bank = bestBank()
        val mid = (bank.bankPath[1].start + bank.bankPath[1].end) * 0.5
        assertEquals(mid, PathBlock.blockedBy(bank, listOf(mid), r))
        assertNull(PathBlock.blockedBy(bank, listOf(Vec2(0.0, 0.0)), r))
    }

    @Test
    fun bank_blockerOnAimLineIsReturned() {
        val bank = bestBank()
        val mid = (bank.direct.aimLine.start + bank.direct.aimLine.end) * 0.5
        assertEquals(mid, PathBlock.blockedBy(bank, listOf(mid), r))
    }

    @Test
    fun kick_blockerOnSecondLegIsReturned() {
        val kick = bestKick()
        val mid = (kick.kickPath[1].start + kick.kickPath[1].end) * 0.5
        assertEquals(mid, PathBlock.blockedBy(kick, listOf(mid), r))
        assertNull(PathBlock.blockedBy(kick, listOf(Vec2(0.0, 0.0)), r))
    }

    @Test
    fun kick_blockerOnTargetLineIsReturned() {
        val kick = bestKick()
        val mid = (kick.direct.targetLine.start + kick.direct.targetLine.end) * 0.5
        assertEquals(mid, PathBlock.blockedBy(kick, listOf(mid), r))
    }

    @Test
    fun twoRailBank_blockerOnMiddleLegIsReturned() {
        val bank2 = bestBank2()
        val mid = (bank2.bankPath[1].start + bank2.bankPath[1].end) * 0.5
        assertEquals(mid, PathBlock.blockedBy(bank2, listOf(mid), r))
        assertNull(PathBlock.blockedBy(bank2, listOf(Vec2(0.0, 0.0)), r))
    }

    @Test
    fun twoRailKick_blockerOnMiddleLegIsReturned() {
        val kick2 = bestKick2()
        val mid = (kick2.kickPath[1].start + kick2.kickPath[1].end) * 0.5
        assertEquals(mid, PathBlock.blockedBy(kick2, listOf(mid), r))
        assertNull(PathBlock.blockedBy(kick2, listOf(Vec2(0.0, 0.0)), r))
    }

    // ---- label ----

    @Test
    fun blockedLabel_textIsFixed() {
        assertEquals("Path blocked", BlockedPathLabel.TEXT)
    }

    @Test
    fun blockedLabel_anchorIsOppositeSideFromCutLabel() {
        // Best cut shot (22.2 deg): the cut label sits at
        // (192.3,219.4), the blocked label at (190.8,174.4) — the
        // aim line runs near y=200 at the midpoint, so the two
        // labels sit on opposite sides of it.
        val cut = ShotSolver.allShots(cue, target, pockets, r).first()
        val cutAnchor = CutAngleLabel.labelAnchor(cut.aimLine, r)
        val blockedAnchor = BlockedPathLabel.labelAnchor(cut.aimLine, r)
        assertEquals(192.3, cutAnchor.x, 0.15)
        assertEquals(219.4, cutAnchor.y, 0.15)
        assertEquals(190.8, blockedAnchor.x, 0.15)
        assertEquals(174.4, blockedAnchor.y, 0.15)
        // Opposite sides of the aim line: the midpoint's y is ~197,
        // the cut label is below it, the blocked label above it.
        val midY = (cut.aimLine.start.y + cut.aimLine.end.y) / 2.0
        assertTrue(cutAnchor.y > midY)
        assertTrue(blockedAnchor.y < midY)
    }

    // ---- firstClearShot (v1.0.21 re-ranking) ----
    //
    // The fixture's cut-ranked direct shots (kotlinc probe,
    // /tmp/probe21/Probe21.kt): (680,340) cut 22.17, (680,20) cut
    // 27.75, (360,340) cut 71.70, (360,20) cut 76.59. Blockers sit
    // on solved-path midpoints, so they hold for whatever the best
    // solution is.

    @Test
    fun firstClearShot_returnsBestWhenNothingIsBlocked() {
        val all = ShotSolver.allShots(cue, target, pockets, r)
        val pick = PathBlock.firstClearShot(all, emptyList(), r)
        assertEquals(Vec2(680.0, 340.0), pick!!.targetLine.end)
        assertEquals(22.17, pick.cutAngleDegrees, 0.05)
    }

    @Test
    fun firstClearShot_skipsBlockedBestForNextClearShot() {
        val all = ShotSolver.allShots(cue, target, pockets, r)
        val best = all.first()
        // Blocker on the best shot's target-line midpoint (490,270).
        val mid = (best.targetLine.start + best.targetLine.end) * 0.5
        val pick = PathBlock.firstClearShot(all, listOf(mid), r)
        assertEquals(Vec2(680.0, 20.0), pick!!.targetLine.end)
        assertEquals(27.75, pick.cutAngleDegrees, 0.05)
    }

    @Test
    fun firstClearShot_returnsNullWhenEveryShotIsBlocked() {
        val all = ShotSolver.allShots(cue, target, pockets, r)
        // One blocker on each shot's target-line midpoint.
        val mids = all.map { (it.targetLine.start + it.targetLine.end) * 0.5 }
        assertNull(PathBlock.firstClearShot(all, mids, r))
    }

    @Test
    fun firstClearShot_ballInFrontOfCueBlocksEveryShot() {
        // The four aim lines fan out from the cue, so one ball on
        // the best shot's aim-line midpoint sits within a diameter
        // of every aim line: no direct shot is clear.
        val all = ShotSolver.allShots(cue, target, pockets, r)
        val best = all.first()
        val mid = (best.aimLine.start + best.aimLine.end) * 0.5
        assertNull(PathBlock.firstClearShot(all, listOf(mid), r))
    }

    @Test
    fun firstClearShot_emptyShotListIsNull() {
        assertNull(PathBlock.firstClearShot(emptyList(), emptyList(), r))
    }

    // ---- firstClearBank / firstClearKick (v1.0.22 re-ranking) ----
    //
    // Same fixture (kotlinc probe, probe22/probe22d.kt): the
    // cut-ranked one-rail banks start 8.59 (mirrored pocket
    // (1320,340), contact (680,252.2)), 10.99 (mirrored pocket
    // (1320,20), contact (680,132.9)); the cut-ranked one-rail
    // kicks start 6.12 (pocket (360,340), contact (201.1,20)),
    // 18.03 (pocket (360,20), contact (203.5,340)). The kick
    // list uses the live corner guard (bankContactIsValid) with
    // the same table the fallback chain uses. Blockers sit on
    // solved-path midpoints, so they hold for whatever the best
    // solution is.

    private val rerankTable = com.poolguide.tool.cv.TableRect(
        Vec2(40.0, 20.0), Vec2(680.0, 20.0),
        Vec2(680.0, 340.0), Vec2(40.0, 340.0)
    )

    private fun allKicksGuarded() = ShotSolver.allKicks(
        cue, target, pockets, rails, r
    ) { contact, index, pocket ->
        com.poolguide.tool.cv.bankContactIsValid(
            contact, index, rerankTable, pocket, r
        )
    }

    private fun assertVecNear(expected: Vec2, actual: Vec2, delta: Double = 0.5) {
        assertEquals(expected.x, actual.x, delta)
        assertEquals(expected.y, actual.y, delta)
    }

    @Test
    fun firstClearBank_returnsBestWhenNothingIsBlocked() {
        val all = ShotSolver.allBanks(cue, target, pockets, rails, r)
        val pick = PathBlock.firstClearBank(all, emptyList(), r)
        assertEquals(8.59, pick!!.direct.cutAngleDegrees, 0.05)
        assertVecNear(Vec2(1320.0, 340.0), pick.direct.targetLine.end)
        assertVecNear(Vec2(680.0, 252.2), pick.railContact)
    }

    @Test
    fun firstClearBank_skipsBlockedBestForNextClearShot() {
        val all = ShotSolver.allBanks(cue, target, pockets, rails, r)
        val best = all.first()
        // Blocker on the best bank's first-leg midpoint (490,226.1).
        val mid = (best.bankPath[0].start + best.bankPath[0].end) * 0.5
        val pick = PathBlock.firstClearBank(all, listOf(mid), r)
        assertEquals(10.99, pick!!.direct.cutAngleDegrees, 0.05)
        assertVecNear(Vec2(1320.0, 20.0), pick.direct.targetLine.end)
        assertVecNear(Vec2(680.0, 132.9), pick.railContact)
    }

    @Test
    fun firstClearBank_returnsNullWhenEveryBankIsBlocked() {
        val all = ShotSolver.allBanks(cue, target, pockets, rails, r)
        // One blocker on each bank's first-leg midpoint.
        val mids = all.map { (it.bankPath[0].start + it.bankPath[0].end) * 0.5 }
        assertNull(PathBlock.firstClearBank(all, mids, r))
    }

    @Test
    fun firstClearBank_emptyBankListIsNull() {
        assertNull(PathBlock.firstClearBank(emptyList(), emptyList(), r))
    }

    @Test
    fun firstClearKick_returnsBestWhenNothingIsBlocked() {
        val all = allKicksGuarded()
        val pick = PathBlock.firstClearKick(all, emptyList(), r)
        assertEquals(6.12, pick!!.direct.cutAngleDegrees, 0.05)
        assertVecNear(Vec2(360.0, 340.0), pick.direct.targetLine.end)
        assertVecNear(Vec2(201.1, 20.0), pick.railContact)
    }

    @Test
    fun firstClearKick_skipsBlockedBestForNextClearShot() {
        val all = allKicksGuarded()
        val best = all.first()
        // Blocker on the best kick's second-leg midpoint (247,101.7).
        val mid = (best.kickPath[1].start + best.kickPath[1].end) * 0.5
        val pick = PathBlock.firstClearKick(all, listOf(mid), r)
        assertEquals(18.03, pick!!.direct.cutAngleDegrees, 0.05)
        assertVecNear(Vec2(360.0, 20.0), pick.direct.targetLine.end)
        assertVecNear(Vec2(203.5, 340.0), pick.railContact)
    }

    @Test
    fun firstClearKick_returnsNullWhenEveryKickIsBlocked() {
        val all = allKicksGuarded()
        // One blocker on each kick's first-leg midpoint.
        val mids = all.map { (it.kickPath[0].start + it.kickPath[0].end) * 0.5 }
        assertNull(PathBlock.firstClearKick(all, mids, r))
    }

    @Test
    fun firstClearKick_emptyKickListIsNull() {
        assertNull(PathBlock.firstClearKick(emptyList(), emptyList(), r))
    }

    // ---- firstClearTwoRailBank / firstClearTwoRailKick (v1.0.23) ----
    //
    // Layouts verified against the real solver with kotlinc before
    // the tests were written (see probe23/probe23k.kt and
    // probe23l.kt). The two-rail lists use the live corner guard
    // (bankContactIsValid) with the same table the fallback chain
    // uses. Blockers sit on solved-path midpoints, so they hold
    // for whatever the best solution is.
    //
    // Banks, cue (90,50) -> target (50,60): cuts 3.66 (real
    // pocket bottom-middle, contacts left (40,62.9) + right
    // (680,247.6)), 47.43 (top-right, contacts (40,69.2) +
    // (333.3,340)), 71.63 (top-middle, contacts (40,78.2) +
    // (184,340)). Kicks, cue (150,50) -> target (50,60): cuts
    // 5.16, 26.72, 50.41, all into the top-left pocket with the
    // same ghost (54.4,77.5), differing in rail pairs.

    private fun allTwoRailBanksGuarded(cue: Vec2, target: Vec2) =
        ShotSolver.allTwoRailBanks(
            cue, target, pockets, rails, r
        ) { contact, index, pocket ->
            com.poolguide.tool.cv.bankContactIsValid(
                contact, index, rerankTable, pocket, r
            )
        }

    private fun allTwoRailKicksGuarded(cue: Vec2, target: Vec2) =
        ShotSolver.allTwoRailKicks(
            cue, target, pockets, rails, r
        ) { contact, index, pocket ->
            com.poolguide.tool.cv.bankContactIsValid(
                contact, index, rerankTable, pocket, r
            )
        }

    @Test
    fun firstClearTwoRailBank_returnsBestWhenNothingIsBlocked() {
        val all = allTwoRailBanksGuarded(Vec2(90.0, 50.0), Vec2(50.0, 60.0))
        val pick = PathBlock.firstClearTwoRailBank(all, emptyList(), r)
        assertEquals(3.66, pick!!.direct.cutAngleDegrees, 0.05)
        assertVecNear(Vec2(360.0, 340.0), pick.bankPath[2].end)
        assertVecNear(Vec2(40.0, 62.9), pick.railContacts[0])
        assertVecNear(Vec2(680.0, 247.6), pick.railContacts[1])
    }

    @Test
    fun firstClearTwoRailBank_skipsBlockedBestForNextClearShot() {
        val all = allTwoRailBanksGuarded(Vec2(90.0, 50.0), Vec2(50.0, 60.0))
        val best = all.first()
        // Blocker on the best bank's second-leg midpoint (360,155.3).
        val mid = (best.bankPath[1].start + best.bankPath[1].end) * 0.5
        val pick = PathBlock.firstClearTwoRailBank(all, listOf(mid), r)
        assertEquals(47.43, pick!!.direct.cutAngleDegrees, 0.05)
        assertVecNear(Vec2(680.0, 20.0), pick.bankPath[2].end)
        assertVecNear(Vec2(40.0, 69.2), pick.railContacts[0])
        assertVecNear(Vec2(333.3, 340.0), pick.railContacts[1])
    }

    @Test
    fun firstClearTwoRailBank_returnsNullWhenEveryBankIsBlocked() {
        val all = allTwoRailBanksGuarded(Vec2(90.0, 50.0), Vec2(50.0, 60.0))
        // One blocker on each bank's second-leg midpoint.
        val mids = all.map { (it.bankPath[1].start + it.bankPath[1].end) * 0.5 }
        assertNull(PathBlock.firstClearTwoRailBank(all, mids, r))
    }

    @Test
    fun firstClearTwoRailBank_emptyBankListIsNull() {
        assertNull(PathBlock.firstClearTwoRailBank(emptyList(), emptyList(), r))
    }

    @Test
    fun firstClearTwoRailKick_returnsBestWhenNothingIsBlocked() {
        val all = allTwoRailKicksGuarded(Vec2(150.0, 50.0), Vec2(50.0, 60.0))
        val pick = PathBlock.firstClearTwoRailKick(all, emptyList(), r)
        assertEquals(5.16, pick!!.direct.cutAngleDegrees, 0.05)
        assertVecNear(Vec2(40.0, 20.0), pick.direct.targetLine.end)
        assertVecNear(Vec2(145.3, 20.0), pick.railContacts[0])
        assertVecNear(Vec2(95.4, 340.0), pick.railContacts[1])
    }

    @Test
    fun firstClearTwoRailKick_skipsBlockedBestForNextClearShot() {
        val all = allTwoRailKicksGuarded(Vec2(150.0, 50.0), Vec2(50.0, 60.0))
        // Blocker at (59,139): on the best kick's third leg, clear
        // of the second and third kicks (probe23l found 4,294 such
        // spots; the best kick's second-leg midpoint blocks the
        // second kick too, their paths cross near (120,180)).
        val pick = PathBlock.firstClearTwoRailKick(
            all, listOf(Vec2(59.0, 139.0)), r
        )
        assertEquals(26.72, pick!!.direct.cutAngleDegrees, 0.05)
        assertVecNear(Vec2(40.0, 20.0), pick.direct.targetLine.end)
        assertVecNear(Vec2(84.7, 340.0), pick.railContacts[0])
        assertVecNear(Vec2(40.0, 141.3), pick.railContacts[1])
    }

    @Test
    fun firstClearTwoRailKick_returnsNullWhenEveryKickIsBlocked() {
        val all = allTwoRailKicksGuarded(Vec2(150.0, 50.0), Vec2(50.0, 60.0))
        // One blocker on each kick's second-leg midpoint.
        val mids = all.map { (it.kickPath[1].start + it.kickPath[1].end) * 0.5 }
        assertNull(PathBlock.firstClearTwoRailKick(all, mids, r))
    }

    @Test
    fun firstClearTwoRailKick_emptyKickListIsNull() {
        assertNull(PathBlock.firstClearTwoRailKick(emptyList(), emptyList(), r))
    }

    // ---- combo / kiss blocked-path flag (v1.0.24) ----
    //
    // Layouts verified against the real solver with kotlinc before
    // the tests were written (see probe24/Probe24.kt): cue (55,58),
    // first ball (60,40), second ball (80,35): combo cuts 84.76 /
    // 60.29 into the top-middle pocket; second ball (100,60): kiss
    // cuts 78.19 / 11.86 into the top-right pocket. Blockers sit on
    // solved-path midpoints, so they hold for whatever the best
    // solution is.

    private val chainCue = Vec2(55.0, 58.0)
    private val chainFirst = Vec2(60.0, 40.0)
    private val chainComboSecond = Vec2(80.0, 35.0)
    private val chainKissSecond = Vec2(100.0, 60.0)

    private fun combo24() = ShotSolver.bestCombo(
        chainCue, chainFirst, listOf(chainComboSecond), pockets, r
    )!!

    private fun kiss24() = ShotSolver.bestKiss(
        chainCue, chainFirst, listOf(chainKissSecond), pockets, r
    )!!

    @Test
    fun combo_blockerOnEachLegIsReturned() {
        val combo = combo24()
        // Aim line mid (53.5,57.0), first-ball leg mid (61.0,38.0),
        // second-ball target-line mid (220.0,27.5).
        val legs = listOf(
            combo.firstShot.aimLine,
            combo.firstShot.targetLine,
            combo.secondShot.targetLine
        )
        for (leg in legs) {
            val mid = (leg.start + leg.end) * 0.5
            assertEquals(mid, PathBlock.blockedBy(combo, listOf(mid), r))
        }
        assertNull(PathBlock.blockedBy(combo, emptyList(), r))
    }

    @Test
    fun combo_grazeAtExactlyTwoRadiiIsClear() {
        val combo = combo24()
        // Second leg midpoint (220.0,27.5), offset perpendicular by
        // exactly one diameter (18.0 px): a graze, not a hit.
        val leg = combo.secondShot.targetLine
        val mid = (leg.start + leg.end) * 0.5
        val perp = leg.direction().perp()
        val graze = Vec2(mid.x + perp.x * 2 * r, mid.y + perp.y * 2 * r)
        assertNull(PathBlock.blockedBy(combo, listOf(graze), r))
        val inside = Vec2(
            mid.x + perp.x * (2 * r - 0.1), mid.y + perp.y * (2 * r - 0.1)
        )
        assertEquals(inside, PathBlock.blockedBy(combo, listOf(inside), r))
    }

    @Test
    fun combo_firstBlockerAlongPathWins() {
        val combo = combo24()
        // A blocker on the aim line beats one on the second leg.
        val mid0 = (combo.firstShot.aimLine.start + combo.firstShot.aimLine.end) * 0.5
        val mid2 = (combo.secondShot.targetLine.start + combo.secondShot.targetLine.end) * 0.5
        assertEquals(
            mid0, PathBlock.blockedBy(combo, listOf(mid2, mid0), r)
        )
    }

    @Test
    fun combo_travellingBallsWouldFlagTheirOwnPath() {
        // The first ball sits on the second leg's aim line and the
        // second ball sits at the start of its own target line, so
        // passing either as a blocker flags its own path. This is
        // why the pipeline never passes them (see comboBlockedBy).
        val combo = combo24()
        assertEquals(
            chainFirst, PathBlock.blockedBy(combo, listOf(chainFirst), r)
        )
        assertEquals(
            chainComboSecond,
            PathBlock.blockedBy(combo, listOf(chainComboSecond), r)
        )
    }

    @Test
    fun kiss_blockerOnEachLegIsReturned() {
        val kiss = kiss24()
        // Aim line mid (56.3,57.9), deflected-leg mid (69.8,59.5),
        // second-ball target-line mid (390.0,40.0).
        val legs = listOf(
            kiss.kissShot.aimLine,
            kiss.secondShot.aimLine,
            kiss.secondShot.targetLine
        )
        for (leg in legs) {
            val mid = (leg.start + leg.end) * 0.5
            assertEquals(mid, PathBlock.blockedBy(kiss, listOf(mid), r))
        }
        assertNull(PathBlock.blockedBy(kiss, emptyList(), r))
    }

    @Test
    fun kiss_ballOnKissedDepartureRayDoesNotBlock() {
        // A ball 40 px past the kissed ball along its departure
        // direction is not on the potting chain: the kissed ball's
        // departure is not checked.
        val kiss = kiss24()
        val dir = kiss.kissShot.targetLine.direction()
        val onRay = Vec2(
            chainFirst.x + dir.x * 40.0, chainFirst.y + dir.y * 40.0
        )
        assertNull(PathBlock.blockedBy(kiss, listOf(onRay), r))
    }

    @Test
    fun kiss_firstBlockerAlongPathWins() {
        val kiss = kiss24()
        // A blocker on the aim line beats one on the second leg.
        val mid0 = (kiss.kissShot.aimLine.start + kiss.kissShot.aimLine.end) * 0.5
        val mid2 = (kiss.secondShot.targetLine.start + kiss.secondShot.targetLine.end) * 0.5
        assertEquals(
            mid0, PathBlock.blockedBy(kiss, listOf(mid2, mid0), r)
        )
    }

    @Test
    fun kiss_secondBallWouldFlagItsOwnPath() {
        // The second ball sits at the start of its own target line,
        // so passing it as a blocker flags its own path. This is
        // why the pipeline never passes it (see kissBlockedBy).
        val kiss = kiss24()
        assertEquals(
            chainKissSecond,
            PathBlock.blockedBy(kiss, listOf(chainKissSecond), r)
        )
    }

    // ---- combo / kiss re-ranking (v1.0.25) ----
    //
    // Layouts verified against the real solver with kotlinc before
    // the tests were written (see probe25/Probe25d.kt and
    // probe25/Probe25l.kt). The combo pair (cue (100,150), first
    // ball (105,132)) pots through two second balls into different
    // pockets: (130,110) cuts 40.55 into the top-middle pocket,
    // (100,90) cuts 53.93 into the top-left pocket, and (130,110)
    // also pots 59.95 into the top-right pocket. The kiss pair
    // (cue (55,58), first ball (60,40)) pots through (300,50) at
    // 26.58 and (340,60) at 67.23, both into the top-middle
    // pocket. Blockers sit on solved second-leg midpoints, so
    // they hold for whatever the best solution is.

    private val rankComboCue = Vec2(100.0, 150.0)
    private val rankComboFirst = Vec2(105.0, 132.0)
    private val rankComboBestSecond = Vec2(130.0, 110.0)
    private val rankComboAltSecond = Vec2(100.0, 90.0)
    private val rankKissCue = Vec2(55.0, 58.0)
    private val rankKissFirst = Vec2(60.0, 40.0)
    private val rankKissBestSecond = Vec2(300.0, 50.0)
    private val rankKissAltSecond = Vec2(340.0, 60.0)

    private fun allCombos25() = ShotSolver.allCombos(
        rankComboCue, rankComboFirst,
        listOf(rankComboBestSecond, rankComboAltSecond), pockets, r
    )

    private fun allKisses25() = ShotSolver.allKisses(
        rankKissCue, rankKissFirst,
        listOf(rankKissBestSecond, rankKissAltSecond), pockets, r
    )

    @Test
    fun allCombos_ranksBySecondCutAndBestComboDelegates() {
        val combos = allCombos25()
        // Three entries: the best second ball pots into two
        // pockets, so it appears twice, once per pocket.
        assertEquals(3, combos.size)
        assertEquals(40.55, combos[0].secondShot.cutAngleDegrees, 0.1)
        assertEquals(Vec2(360.0, 20.0), combos[0].secondShot.targetLine.end)
        assertEquals(53.93, combos[1].secondShot.cutAngleDegrees, 0.1)
        assertEquals(Vec2(40.0, 20.0), combos[1].secondShot.targetLine.end)
        assertEquals(59.95, combos[2].secondShot.cutAngleDegrees, 0.1)
        assertEquals(Vec2(680.0, 20.0), combos[2].secondShot.targetLine.end)
        // bestCombo is the first entry of the ranked list.
        assertEquals(
            combos[0],
            ShotSolver.bestCombo(
                rankComboCue, rankComboFirst,
                listOf(rankComboBestSecond, rankComboAltSecond), pockets, r
            )
        )
    }

    @Test
    fun firstClearCombo_returnsNextClearComboWhenBestIsBlocked() {
        val combos = allCombos25()
        // No blocker: the best combo is shown, and the other
        // candidate's second ball does not block its chain (each
        // candidate's own second ball is removed, the other stays,
        // and it sits clear of this chain).
        assertEquals(
            combos[0],
            PathBlock.firstClearCombo(
                combos, listOf(rankComboBestSecond, rankComboAltSecond), r
            )
        )
        assertNull(PathBlock.blockedBy(combos[0], listOf(rankComboAltSecond), r))
        // Blocker (245,65) sits on the best combo's second-leg
        // midpoint: the shown combo is the next clear one, the
        // (100,90) combo at 53.93 into the top-left pocket.
        val shown = PathBlock.firstClearCombo(
            combos,
            listOf(rankComboBestSecond, rankComboAltSecond, Vec2(245.0, 65.0)),
            r
        )
        assertEquals(combos[1], shown)
        assertEquals(53.93, shown!!.secondShot.cutAngleDegrees, 0.1)
        assertEquals(Vec2(40.0, 20.0), shown.secondShot.targetLine.end)
    }

    @Test
    fun firstClearCombo_returnsNullWhenEveryComboIsBlocked() {
        val combos = allCombos25()
        // A blocker on every combo's second-leg midpoint: (245,65)
        // on the best, (70,55) on the alt, (405,65) on the third.
        val mids = combos.map { combo ->
            val leg = combo.secondShot.targetLine
            (leg.start + leg.end) * 0.5
        }
        assertEquals(
            listOf(Vec2(245.0, 65.0), Vec2(70.0, 55.0), Vec2(405.0, 65.0)),
            mids
        )
        assertNull(
            PathBlock.firstClearCombo(
                combos, listOf(rankComboBestSecond, rankComboAltSecond) + mids, r
            )
        )
        assertNull(PathBlock.firstClearCombo(emptyList(), emptyList(), r))
    }

    @Test
    fun allKisses_ranksBySecondCutAndBestKissDelegates() {
        val kisses = allKisses25()
        assertEquals(2, kisses.size)
        assertEquals(26.58, kisses[0].secondShot.cutAngleDegrees, 0.1)
        assertEquals(Vec2(360.0, 20.0), kisses[0].secondShot.targetLine.end)
        assertEquals(67.23, kisses[1].secondShot.cutAngleDegrees, 0.1)
        assertEquals(Vec2(360.0, 20.0), kisses[1].secondShot.targetLine.end)
        // bestKiss is the first entry of the ranked list.
        assertEquals(
            kisses[0],
            ShotSolver.bestKiss(
                rankKissCue, rankKissFirst,
                listOf(rankKissBestSecond, rankKissAltSecond), pockets, r
            )
        )
    }

    @Test
    fun firstClearKiss_returnsNextClearKissWhenBestIsBlocked() {
        val kisses = allKisses25()
        // No blocker: the best kiss is shown, and the other
        // candidate's second ball does not block its chain.
        assertEquals(
            kisses[0],
            PathBlock.firstClearKiss(
                kisses, listOf(rankKissBestSecond, rankKissAltSecond), r
            )
        )
        assertNull(PathBlock.blockedBy(kisses[0], listOf(rankKissAltSecond), r))
        // Blocker (330,35) sits on the best kiss's second-leg
        // midpoint: the shown kiss is the next clear one, the
        // (340,60) kiss at 67.23 into the top-middle pocket.
        val shown = PathBlock.firstClearKiss(
            kisses,
            listOf(rankKissBestSecond, rankKissAltSecond, Vec2(330.0, 35.0)),
            r
        )
        assertEquals(kisses[1], shown)
        assertEquals(67.23, shown!!.secondShot.cutAngleDegrees, 0.1)
        assertEquals(Vec2(360.0, 20.0), shown.secondShot.targetLine.end)
    }

    @Test
    fun firstClearKiss_returnsNullWhenEveryKissIsBlocked() {
        val kisses = allKisses25()
        // A blocker on every kiss's second-leg midpoint: (330,35)
        // on the best, (350,40) on the alt.
        val mids = kisses.map { kiss ->
            val leg = kiss.secondShot.targetLine
            (leg.start + leg.end) * 0.5
        }
        assertEquals(
            listOf(Vec2(330.0, 35.0), Vec2(350.0, 40.0)),
            mids
        )
        assertNull(
            PathBlock.firstClearKiss(
                kisses, listOf(rankKissBestSecond, rankKissAltSecond) + mids, r
            )
        )
        assertNull(PathBlock.firstClearKiss(emptyList(), emptyList(), r))
    }
}
