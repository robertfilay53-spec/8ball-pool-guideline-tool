package com.poolguide.tool

import com.poolguide.tool.geometry.CutAngleLabel
import com.poolguide.tool.geometry.Segment
import com.poolguide.tool.geometry.ShotSolver
import com.poolguide.tool.geometry.Vec2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cut-angle readout (v1.0.12). All numbers pinned against the real
 * solver first (kotlinc probe, cut12-probe/Probe12.kt + ProbeFormat.kt):
 * the practice defaults solve to 34.62 / 30.77 / 2.34 / 6.39 / 39.12 /
 * 72.20 / 27.22 degrees, and the thin practice layout (cue (620, 260),
 * target (620, 300)) solves to 82.83 degrees.
 */
class CutAngleLabelTest {

    @Test
    fun format_oneDecimalWithDegreeSign() {
        assertEquals("12.6°", CutAngleLabel.format(12.64))
        assertEquals("0.0°", CutAngleLabel.format(0.0))
        assertEquals("90.0°", CutAngleLabel.format(90.0))
        assertEquals("80.0°", CutAngleLabel.format(80.0))
        assertEquals("80.1°", CutAngleLabel.format(80.1))
    }

    @Test
    fun format_practiceDefaultCutAngles() {
        // Pinned against the real solver (probe): the label a player
        // sees for each practice default layout.
        assertEquals("34.6°", CutAngleLabel.format(34.62)) // DIRECT default
        assertEquals("30.8°", CutAngleLabel.format(30.77)) // BANK default
        assertEquals("2.3°", CutAngleLabel.format(2.34)) // KICK default
        assertEquals("6.4°", CutAngleLabel.format(6.39)) // COMBO first leg
        assertEquals("39.1°", CutAngleLabel.format(39.12)) // COMBO second leg
        assertEquals("72.2°", CutAngleLabel.format(72.20)) // KISS kiss leg
        assertEquals("27.2°", CutAngleLabel.format(27.22)) // KISS second leg
        assertEquals("82.8°", CutAngleLabel.format(82.83)) // thin layout
    }

    @Test
    fun isThinCut_boundaryIsStrictlyAboveEighty() {
        assertFalse(CutAngleLabel.isThinCut(79.9))
        assertFalse(CutAngleLabel.isThinCut(80.0))
        assertTrue(CutAngleLabel.isThinCut(80.1))
        assertTrue(CutAngleLabel.isThinCut(82.83))
        assertTrue(CutAngleLabel.isThinCut(90.0))
        assertFalse(CutAngleLabel.isThinCut(0.0))
    }

    @Test
    fun isThinCut_matchesSolverFlag() {
        // Same rule as ShotSolution.isThinCut, on real solves.
        val pockets = listOf(
            Vec2(40.0, 20.0), Vec2(680.0, 20.0), Vec2(680.0, 340.0),
            Vec2(40.0, 340.0), Vec2(360.0, 20.0), Vec2(360.0, 340.0)
        )
        val direct = ShotSolver.bestShot(
            Vec2(200.0, 180.0), Vec2(424.0, 180.0), pockets, 9.0
        )!!
        assertFalse(direct.isThinCut)
        assertFalse(CutAngleLabel.isThinCut(direct.cutAngleDegrees))
        val thin = ShotSolver.bestShot(
            Vec2(620.0, 260.0), Vec2(620.0, 300.0), pockets, 9.0
        )!!
        assertTrue(thin.isThinCut)
        assertTrue(CutAngleLabel.isThinCut(thin.cutAngleDegrees))
    }

    @Test
    fun warningText_onlyForThinCuts() {
        assertNull(CutAngleLabel.warningText(80.0))
        assertNull(CutAngleLabel.warningText(34.62))
        assertEquals("Thin cut 82.8°", CutAngleLabel.warningText(82.83))
        assertEquals("Thin cut 80.1°", CutAngleLabel.warningText(80.1))
    }

    @Test
    fun labelAnchor_midpointOffsetPerpendicularByTwoAndAHalfRadii() {
        // Horizontal aim line: midpoint (50, 0), perp (-dy, dx) = (0, 1),
        // offset 2.5 * 9 = 22.5 -> (50, 22.5).
        val anchor = CutAngleLabel.labelAnchor(
            Segment(Vec2(0.0, 0.0), Vec2(100.0, 0.0)), 9.0
        )
        assertEquals(50.0, anchor.x, 1e-9)
        assertEquals(22.5, anchor.y, 1e-9)
        // Vertical aim line: midpoint (0, 50), perp (-1, 0) -> (-22.5, 50).
        val vertical = CutAngleLabel.labelAnchor(
            Segment(Vec2(0.0, 0.0), Vec2(0.0, 100.0)), 9.0
        )
        assertEquals(-22.5, vertical.x, 1e-9)
        assertEquals(50.0, vertical.y, 1e-9)
        // The anchor sits exactly 2.5 radii from the midpoint, on the
        // perpendicular — the text never sits on its own line.
        val diagonal = CutAngleLabel.labelAnchor(
            Segment(Vec2(10.0, 20.0), Vec2(110.0, 120.0)), 9.0
        )
        val mid = Vec2(60.0, 70.0)
        assertEquals(22.5, diagonal.distanceTo(mid), 1e-9)
        val aimDir = Vec2(100.0, 100.0).normalized()
        assertEquals(0.0, (diagonal - mid).dot(aimDir), 1e-9)
    }

    @Test
    fun warningAnchor_aboveCueBallByThreeRadii() {
        val anchor = CutAngleLabel.warningAnchor(Vec2(100.0, 100.0), 9.0)
        assertEquals(100.0, anchor.x, 1e-9)
        assertEquals(73.0, anchor.y, 1e-9)
    }
}
