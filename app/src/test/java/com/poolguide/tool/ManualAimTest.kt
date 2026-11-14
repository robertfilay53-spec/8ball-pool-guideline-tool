package com.poolguide.tool.overlay

import com.poolguide.tool.cv.TableRect
import com.poolguide.tool.geometry.ShotSolver
import com.poolguide.tool.geometry.Vec2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** EDIT-mode manual aim (v1.0.4, Line Guide lead). */
class ManualAimTest {

    // Straight shot: cue (100,200) -> target (300,200) -> pocket (600,200).
    private val cue = Vec2(100.0, 200.0)
    private val target = Vec2(300.0, 200.0)
    private val pocket = Vec2(600.0, 200.0)
    private val radius = 9.0

    private fun baseShot() =
        ShotSolver.solveShot(cue, target, pocket, radius)!!

    private fun table() = TableRect(
        Vec2(0.0, 0.0), Vec2(720.0, 0.0),
        Vec2(720.0, 405.0), Vec2(0.0, 405.0)
    )

    @Test
    fun `starts in play mode with no shot`() {
        val aim = ManualAim()
        assertFalse(aim.isEditMode)
        assertFalse(aim.hasShot)
        assertNull(aim.currentShot())
    }

    @Test
    fun `enter edit freezes the shot, exit clears it`() {
        val aim = ManualAim()
        aim.enterEditMode(baseShot(), radius)
        assertTrue(aim.isEditMode)
        assertTrue(aim.hasShot)
        assertFalse(aim.hasOverrides)
        val frozen = aim.currentShot()!!
        assertEquals(100.0, frozen.aimLine.start.x, 0.0)
        assertEquals(282.0, frozen.ghostPoint.x, 0.0)
        aim.exitEditMode()
        assertFalse(aim.isEditMode)
        assertFalse(aim.hasShot)
        assertNull(aim.currentShot())
    }

    @Test
    fun `no overrides returns the frozen shot unchanged`() {
        val aim = ManualAim()
        val base = baseShot()
        aim.enterEditMode(base, radius)
        assertEquals(base, aim.currentShot())
    }

    @Test
    fun `dragging the cue anchor re-solves from the corrected cue`() {
        val aim = ManualAim()
        aim.enterEditMode(baseShot(), radius)
        // Move the cue 20 px down: the ghost point (target + pocket)
        // stays put, but the aim line and cut angle change.
        aim.dragAnchor(Anchor.CUE, Vec2(0.0, 20.0), table())
        val corrected = aim.currentShot()!!
        assertTrue(aim.hasOverrides)
        assertEquals(100.0, corrected.aimLine.start.x, 0.0)
        assertEquals(220.0, corrected.aimLine.start.y, 0.0)
        assertEquals(282.0, corrected.ghostPoint.x, 0.0)
        assertEquals(200.0, corrected.ghostPoint.y, 0.0)
        assertTrue(corrected.cutAngleDegrees > 0.0)
    }

    @Test
    fun `dragging the ghost anchor re-aims without moving the target`() {
        val aim = ManualAim()
        aim.enterEditMode(baseShot(), radius)
        // Drag the ghost 30 px down: aim line ends at the dragged point,
        // target line still ends at the pocket.
        aim.dragAnchor(Anchor.GHOST, Vec2(0.0, 30.0), table())
        val corrected = aim.currentShot()!!
        assertEquals(282.0, corrected.ghostPoint.x, 0.0)
        assertEquals(230.0, corrected.ghostPoint.y, 0.0)
        assertEquals(300.0, corrected.targetLine.start.x, 0.0)
        assertEquals(600.0, corrected.targetLine.end.x, 0.0)
        assertTrue(corrected.cutAngleDegrees > 0.0)
        // Deflection starts at the dragged ghost point.
        assertEquals(282.0, corrected.deflectionLine.start.x, 0.0)
        assertEquals(230.0, corrected.deflectionLine.start.y, 0.0)
    }

    @Test
    fun `dragging the ghost past the impossible line keeps the last good shot`() {
        val aim = ManualAim()
        aim.enterEditMode(baseShot(), radius)
        // Drag the ghost far to the left of the cue: the aim direction
        // would point away from the pocket (cut > 90 deg).
        aim.dragAnchor(Anchor.GHOST, Vec2(-300.0, 0.0), table())
        val corrected = aim.currentShot()!!
        assertEquals(282.0, corrected.ghostPoint.x, 0.0)
        assertEquals(200.0, corrected.ghostPoint.y, 0.0)
    }

    @Test
    fun `anchors clamp inside the table`() {
        val aim = ManualAim()
        aim.enterEditMode(baseShot(), radius)
        // Drag the cue far left/up: it stops at the table edge + radius.
        aim.dragAnchor(Anchor.CUE, Vec2(-500.0, -500.0), table())
        val corrected = aim.currentShot()!!
        assertEquals(9.0, corrected.aimLine.start.x, 0.0)
        assertEquals(9.0, corrected.aimLine.start.y, 0.0)
        // Drag the ghost far right/down: same clamp.
        aim.dragAnchor(Anchor.GHOST, Vec2(900.0, 900.0), table())
        val corrected2 = aim.currentShot()!!
        assertEquals(711.0, corrected2.ghostPoint.x, 0.0)
        assertEquals(396.0, corrected2.ghostPoint.y, 0.0)
    }

    @Test
    fun `drag with no table does not clamp`() {
        val aim = ManualAim()
        aim.enterEditMode(baseShot(), radius)
        aim.dragAnchor(Anchor.CUE, Vec2(-500.0, 0.0), null)
        val corrected = aim.currentShot()!!
        assertEquals(-400.0, corrected.aimLine.start.x, 0.0)
    }

    @Test
    fun `drag with no frozen shot does nothing`() {
        val aim = ManualAim()
        aim.dragAnchor(Anchor.CUE, Vec2(10.0, 10.0), table())
        assertNull(aim.currentShot())
        assertFalse(aim.hasOverrides)
    }
}
