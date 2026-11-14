package com.poolguide.tool

import com.poolguide.tool.geometry.PocketDistanceLabel
import com.poolguide.tool.geometry.Rail
import com.poolguide.tool.geometry.Segment
import com.poolguide.tool.geometry.ShotSolver
import com.poolguide.tool.geometry.Vec2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pocket-distance readout (v1.0.15). All numbers pinned against the
 * real solver first (kotlinc probe, dist15-probe/Probe15.kt): the
 * instrumented-test layouts solve to 29.6 bd (direct best shot),
 * 5.9 bd (thin), 16.0 bd (combo), 33.8 bd (kiss), 42.1 bd (bank),
 * 42.3 bd (kick), 72.3 bd (two-rail bank), 56.2 bd (two-rail kick).
 */
class PocketDistanceLabelTest {

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

    @Test
    fun format_oneDecimalWithBdUnit() {
        assertEquals("12.4 bd", PocketDistanceLabel.format(12.44))
        assertEquals("0.5 bd", PocketDistanceLabel.format(0.5))
        assertEquals("0.0 bd", PocketDistanceLabel.format(0.0))
        assertEquals("29.6 bd", PocketDistanceLabel.format(29.555555555555557))
    }

    @Test
    fun totalBallDiameters_sumsSegmentsDividedByDiameter() {
        val segs = listOf(
            Segment(Vec2(0.0, 0.0), Vec2(18.0, 0.0)),
            Segment(Vec2(18.0, 0.0), Vec2(18.0, 18.0))
        )
        assertEquals(2.0, PocketDistanceLabel.totalBallDiameters(segs, 9.0), 0.0)
        assertEquals(0.0, PocketDistanceLabel.totalBallDiameters(emptyList(), 9.0), 0.0)
        // A 720-wide table is 40 ball diameters long.
        assertEquals(
            40.0,
            PocketDistanceLabel.totalBallDiameters(
                listOf(Segment(Vec2(0.0, 0.0), Vec2(720.0, 0.0))), 9.0
            ),
            0.0
        )
    }

    @Test
    fun totalBallDiameters_rejectsNonPositiveRadius() {
        try {
            PocketDistanceLabel.totalBallDiameters(emptyList(), 0.0)
            assertTrue("radius 0 must throw", false)
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }

    @Test
    fun labelAnchor_sitsPastTheRingOnTheArrivalDirection() {
        // Pocket (100, 100), ball arriving left-to-right, r=9, ring=18:
        // the label centre sits 36 px past the pocket, on the line.
        assertEquals(
            Vec2(136.0, 100.0),
            PocketDistanceLabel.labelAnchor(Vec2(100.0, 100.0), Vec2(3.0, 0.0), 9.0, 18.0)
        )
        // A diagonal arrival keeps the label on the arrival line.
        val diagonal = PocketDistanceLabel.labelAnchor(
            Vec2(100.0, 100.0), Vec2(1.0, 1.0), 9.0, 18.0
        )
        assertEquals(100.0 + 36.0 / Math.sqrt(2.0), diagonal.x, 1e-9)
        assertEquals(100.0 + 36.0 / Math.sqrt(2.0), diagonal.y, 1e-9)
    }

    @Test
    fun labelAnchor_degenerateDirectionFallsBackAboveThePocket() {
        assertEquals(
            Vec2(100.0, 64.0),
            PocketDistanceLabel.labelAnchor(Vec2(100.0, 100.0), Vec2(0.0, 0.0), 9.0, 18.0)
        )
    }

    @Test
    fun pathSegments_direct_isAimPlusTarget() {
        val best = ShotSolver.allShots(
            Vec2(100.0, 200.0), Vec2(300.0, 200.0),
            listOf(Vec2(600.0, 50.0), Vec2(600.0, 350.0), Vec2(650.0, 200.0)), 9.0
        ).first()
        val segs = PocketDistanceLabel.pathSegments(best)
        assertEquals(2, segs.size)
        assertEquals(best.aimLine, segs[0])
        assertEquals(best.targetLine, segs[1])
        assertEquals(
            "29.6 bd",
            PocketDistanceLabel.format(PocketDistanceLabel.totalBallDiameters(segs, 9.0))
        )
    }

    @Test
    fun pathSegments_thinShot() {
        val thin = ShotSolver.bestShot(
            Vec2(620.0, 260.0), Vec2(620.0, 300.0), pockets, 9.0
        )!!
        assertTrue(thin.isThinCut)
        assertEquals(
            "5.9 bd",
            PocketDistanceLabel.format(
                PocketDistanceLabel.totalBallDiameters(
                    PocketDistanceLabel.pathSegments(thin), 9.0
                )
            )
        )
    }

    @Test
    fun pathSegments_bank_isAimPlusBankPath() {
        val bank = rails.firstNotNullOfOrNull {
            ShotSolver.solveBank(
                Vec2(100.0, 200.0), Vec2(300.0, 100.0), Vec2(680.0, 340.0), it, 9.0
            )
        }!!
        val segs = PocketDistanceLabel.pathSegments(bank)
        assertEquals(3, segs.size)
        assertEquals(bank.direct.aimLine, segs[0])
        assertEquals(bank.bankPath[0], segs[1])
        assertEquals(bank.bankPath[1], segs[2])
        assertEquals(
            "42.1 bd",
            PocketDistanceLabel.format(PocketDistanceLabel.totalBallDiameters(segs, 9.0))
        )
    }

    @Test
    fun pathSegments_kick_isKickPathPlusTarget() {
        val kick = rails.firstNotNullOfOrNull {
            ShotSolver.solveKick(
                Vec2(100.0, 200.0), Vec2(300.0, 100.0), Vec2(680.0, 340.0), it, 9.0
            )
        }!!
        val segs = PocketDistanceLabel.pathSegments(kick)
        assertEquals(3, segs.size)
        // The kick's aim line IS the first path leg by design (cue ->
        // rail contact); the straight cue -> ghost chord is not in
        // the path at all.
        assertEquals(kick.direct.aimLine, segs[0])
        assertEquals(kick.kickPath[0], segs[0])
        assertEquals(kick.kickPath[1], segs[1])
        assertEquals(kick.direct.targetLine, segs[2])
        assertFalse(
            segs.contains(
                Segment(
                    Vec2(100.0, 200.0),
                    ShotSolver.ghostPoint(Vec2(300.0, 100.0), Vec2(680.0, 340.0), 9.0)
                )
            )
        )
        assertEquals(
            "42.3 bd",
            PocketDistanceLabel.format(PocketDistanceLabel.totalBallDiameters(segs, 9.0))
        )
    }

    @Test
    fun pathSegments_twoRailBankAndKick() {
        var bank2: ShotSolver.TwoRailBankSolution? = null
        var kick2: ShotSolver.TwoRailKickSolution? = null
        outer@ for (pocket in listOf(
            Vec2(680.0, 340.0), Vec2(40.0, 340.0),
            Vec2(680.0, 20.0), Vec2(40.0, 20.0)
        )) {
            for (a in rails.indices) for (b in rails.indices) {
                if (a == b) continue
                if (bank2 == null) bank2 = ShotSolver.solveTwoRailBank(
                    Vec2(100.0, 200.0), Vec2(300.0, 100.0), pocket, rails[a], rails[b], 9.0
                )
                if (kick2 == null) kick2 = ShotSolver.solveTwoRailKick(
                    Vec2(100.0, 200.0), Vec2(300.0, 100.0), pocket, rails[a], rails[b], 9.0
                )
                if (bank2 != null && kick2 != null) break@outer
            }
        }
        val b2 = bank2!!
        val k2 = kick2!!
        val bankSegs = PocketDistanceLabel.pathSegments(b2)
        assertEquals(4, bankSegs.size)
        assertEquals(b2.direct.aimLine, bankSegs[0])
        assertEquals(
            "72.3 bd",
            PocketDistanceLabel.format(PocketDistanceLabel.totalBallDiameters(bankSegs, 9.0))
        )
        val kickSegs = PocketDistanceLabel.pathSegments(k2)
        assertEquals(4, kickSegs.size)
        assertEquals(k2.direct.targetLine, kickSegs[3])
        assertEquals(
            "56.2 bd",
            PocketDistanceLabel.format(PocketDistanceLabel.totalBallDiameters(kickSegs, 9.0))
        )
    }

    @Test
    fun pathSegments_combo_countsFirstBallTravelOnce() {
        val combo = ShotSolver.bestCombo(
            Vec2(55.0, 58.0), Vec2(60.0, 40.0),
            listOf(Vec2(80.0, 35.0)), pockets, 9.0
        )!!
        val segs = PocketDistanceLabel.pathSegments(combo)
        assertEquals(3, segs.size)
        assertEquals(combo.firstShot.aimLine, segs[0])
        assertEquals(combo.firstShot.targetLine, segs[1])
        assertEquals(combo.secondShot.targetLine, segs[2])
        // The second leg's aim line measures the first ball's travel
        // from the ghost point — the same leg the first target line
        // already counts — so it must not appear.
        assertFalse(segs.contains(combo.secondShot.aimLine))
        assertEquals(
            "16.0 bd",
            PocketDistanceLabel.format(PocketDistanceLabel.totalBallDiameters(segs, 9.0))
        )
    }

    @Test
    fun pathSegments_kiss_countsCueTravelNotKissedDeparture() {
        val kiss = ShotSolver.bestKiss(
            Vec2(55.0, 58.0), Vec2(60.0, 40.0),
            listOf(Vec2(100.0, 60.0)), pockets, 9.0
        )!!
        val segs = PocketDistanceLabel.pathSegments(kiss)
        assertEquals(3, segs.size)
        assertEquals(kiss.kissShot.aimLine, segs[0])
        assertEquals(kiss.secondShot.aimLine, segs[1])
        assertEquals(kiss.secondShot.targetLine, segs[2])
        // The kissed ball's departure pots nothing: energy lost, not
        // on the potting chain. The deflection stub is a drawing aid.
        assertFalse(segs.contains(kiss.kissShot.targetLine))
        assertFalse(segs.contains(kiss.kissShot.deflectionLine))
        assertEquals(
            "33.8 bd",
            PocketDistanceLabel.format(PocketDistanceLabel.totalBallDiameters(segs, 9.0))
        )
    }
}
