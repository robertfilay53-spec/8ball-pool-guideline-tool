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
}
