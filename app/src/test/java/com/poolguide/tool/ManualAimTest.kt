package com.poolguide.tool.overlay

import com.poolguide.tool.cv.TableRect
import com.poolguide.tool.geometry.Rail
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

    // One-rail bank (v1.0.29): cue (450,320) -> target (300,200) ->
    // top rail -> pocket (40,30). Numbers pinned by kotlinc probe
    // (~/workspace/probe29, Probe29b full precision; Vec2.toString
    // rounds to 1 decimal, so pin raw doubles, never printed coords):
    // ghost (313.48193812482685,211.92632987965453), cut 3.12979 deg,
    // rail contact (73.91304347826087,0.0), mirrored pocket (40,-30).
    private val bankCue = Vec2(450.0, 320.0)
    private val bankPocket = Vec2(40.0, 30.0)
    private val topRail = Rail(Vec2(0.0, 0.0), Vec2(0.0, 1.0))

    private fun baseBank() =
        ShotSolver.solveBank(bankCue, target, bankPocket, topRail, radius)!!

    // One-rail kick (v1.0.30): cue (100,320) -> target (300,200) ->
    // pocket (680,200), off the bottom rail. Numbers pinned by kotlinc
    // probe (~/workspace/probe30, Probe30a/30b full precision):
    // ghost (282.0,200.0), cut 57.88818512576571 deg, rail contact
    // (153.3448275862069,405.0), mirrored ghost (282.0,610.0).
    // No off-table test: the contact is the crossing of cue to
    // mirrored ghost, so its rail coordinate always lies between the
    // cue's and the ghost's, both clamped inside the table, and no
    // clamped drag can push it off the rail (see applyKickOverrides).
    private val kickCue = Vec2(100.0, 320.0)
    private val kickPocket = Vec2(680.0, 200.0)
    private val bottomRail = Rail(Vec2(0.0, 405.0), Vec2(0.0, -1.0))

    private fun baseKick() =
        ShotSolver.solveKick(kickCue, target, kickPocket, bottomRail, radius)!!

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
    fun `rejected ghost drag after an accepted one keeps the last good shot`() {
        val aim = ManualAim()
        aim.enterEditMode(baseShot(), radius)
        // First drag accepted: ghost moves down 30 px.
        aim.dragAnchor(Anchor.GHOST, Vec2(0.0, 30.0), table())
        val edited = aim.currentShot()!!
        assertEquals(282.0, edited.ghostPoint.x, 0.0)
        assertEquals(230.0, edited.ghostPoint.y, 0.0)
        // Second drag rejected (cut > 90 deg): the drag is not
        // committed, so the last good shot stays instead of snapping
        // back to the frozen base.
        aim.dragAnchor(Anchor.GHOST, Vec2(-300.0, 0.0), table())
        val kept = aim.currentShot()!!
        assertEquals(282.0, kept.ghostPoint.x, 0.0)
        assertEquals(230.0, kept.ghostPoint.y, 0.0)
        assertTrue(aim.hasOverrides)
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
        assertNull(aim.currentBank())
        assertFalse(aim.hasOverrides)
    }

    @Test
    fun `bank edit freezes the bank, exit clears it`() {
        val aim = ManualAim()
        aim.enterEditMode(baseBank(), radius)
        assertTrue(aim.isEditMode)
        assertTrue(aim.isBankEdit)
        assertTrue(aim.hasShot)
        assertFalse(aim.hasOverrides)
        val frozen = aim.currentBank()!!
        assertNull(aim.currentShot())
        assertEquals(313.48193812482685, frozen.direct.ghostPoint.x, 1e-9)
        assertEquals(211.92632987965453, frozen.direct.ghostPoint.y, 1e-9)
        assertEquals(3.1297902183293265, frozen.direct.cutAngleDegrees, 1e-9)
        assertEquals(73.91304347826087, frozen.railContact.x, 1e-9)
        assertEquals(0.0, frozen.railContact.y, 0.0)
        assertEquals(2, frozen.bankPath.size)
        aim.exitEditMode()
        assertFalse(aim.isEditMode)
        assertFalse(aim.isBankEdit)
        assertFalse(aim.hasShot)
        assertNull(aim.currentBank())
    }

    @Test
    fun `bank cue drag re-solves from the corrected cue`() {
        val aim = ManualAim()
        aim.enterEditMode(baseBank(), radius)
        // Move the cue 20 px down: the ghost point and rail contact
        // stay put (same bank), but the cut angle changes, and the
        // re-solve matches a fresh solveBank from the corrected cue.
        aim.dragAnchor(Anchor.CUE, Vec2(0.0, 20.0), table())
        val corrected = aim.currentBank()!!
        assertTrue(aim.hasOverrides)
        assertEquals(450.0, corrected.direct.aimLine.start.x, 0.0)
        assertEquals(340.0, corrected.direct.aimLine.start.y, 0.0)
        assertEquals(313.48193812482685, corrected.direct.ghostPoint.x, 1e-9)
        assertEquals(211.92632987965453, corrected.direct.ghostPoint.y, 1e-9)
        assertEquals(73.91304347826087, corrected.railContact.x, 1e-9)
        assertEquals(1.6755688569763976, corrected.direct.cutAngleDegrees, 1e-9)
        val fresh = ShotSolver.solveBank(
            Vec2(450.0, 340.0), target, bankPocket, topRail, radius
        )!!
        assertEquals(fresh, corrected)
    }

    @Test
    fun `bank ghost drag re-aims and rebuilds the path`() {
        val aim = ManualAim()
        aim.enterEditMode(baseBank(), radius)
        // Drag the ghost 2 px right: aim line ends at the dragged
        // point, the contact moves along the rail, the pocket stays.
        aim.dragAnchor(Anchor.GHOST, Vec2(2.0, 0.0), table())
        val corrected = aim.currentBank()!!
        assertEquals(315.48193812482685, corrected.direct.ghostPoint.x, 1e-9)
        assertEquals(211.92632987965453, corrected.direct.ghostPoint.y, 1e-9)
        assertEquals(40.37380684500539, corrected.railContact.x, 1e-9)
        assertEquals(0.0, corrected.railContact.y, 0.0)
        assertEquals(1.1704390055855385, corrected.direct.cutAngleDegrees, 1e-9)
        assertEquals(40.0, corrected.bankPath[1].end.x, 0.0)
        assertEquals(30.0, corrected.bankPath[1].end.y, 0.0)
        assertEquals(2, corrected.bankPath.size)
        // Deflection starts at the dragged ghost point.
        assertEquals(315.48193812482685, corrected.direct.deflectionLine.start.x, 1e-9)
        assertEquals(211.92632987965453, corrected.direct.deflectionLine.start.y, 1e-9)
    }

    @Test
    fun `bank ghost drag down the rail moves the contact`() {
        val aim = ManualAim()
        aim.enterEditMode(baseBank(), radius)
        // Drag the ghost 100 px down: the departure turns toward the
        // rail and the contact slides far along it.
        aim.dragAnchor(Anchor.GHOST, Vec2(0.0, 100.0), table())
        val corrected = aim.currentBank()!!
        assertEquals(313.48193812482685, corrected.direct.ghostPoint.x, 1e-9)
        assertEquals(311.92632987965453, corrected.direct.ghostPoint.y, 1e-9)
        assertEquals(275.9092643539319, corrected.railContact.x, 1e-9)
        assertEquals(79.74707625370303, corrected.direct.cutAngleDegrees, 1e-9)
    }

    @Test
    fun `bank ghost drag off the table keeps the last good bank`() {
        val aim = ManualAim()
        val base = baseBank()
        aim.enterEditMode(base, radius)
        // Drag the ghost 10 px right: the contact would fall off the
        // left edge of the table (x < 0).
        aim.dragAnchor(Anchor.GHOST, Vec2(10.0, 0.0), table())
        assertEquals(base, aim.currentBank())
    }

    @Test
    fun `bank rejected ghost drag after an accepted one keeps the last good bank`() {
        val aim = ManualAim()
        aim.enterEditMode(baseBank(), radius)
        // First drag accepted: ghost moves 2 px right, contact slides
        // along the rail.
        aim.dragAnchor(Anchor.GHOST, Vec2(2.0, 0.0), table())
        val edited = aim.currentBank()!!
        assertEquals(315.48193812482685, edited.direct.ghostPoint.x, 1e-9)
        assertEquals(40.37380684500539, edited.railContact.x, 1e-9)
        // Second drag rejected (contact would fall off the table):
        // the drag is not committed, so the last good bank stays
        // instead of snapping back to the frozen base.
        aim.dragAnchor(Anchor.GHOST, Vec2(10.0, 0.0), table())
        val kept = aim.currentBank()!!
        assertEquals(315.48193812482685, kept.direct.ghostPoint.x, 1e-9)
        assertEquals(40.37380684500539, kept.railContact.x, 1e-9)
        assertTrue(aim.hasOverrides)
    }

    @Test
    fun `bank ghost drag past the impossible line keeps the last good bank`() {
        val aim = ManualAim()
        val base = baseBank()
        aim.enterEditMode(base, radius)
        // Drag the ghost far to the left of the cue: the aim direction
        // would point away from the pocket (cut > 90 deg).
        aim.dragAnchor(Anchor.GHOST, Vec2(-300.0, 0.0), table())
        assertEquals(base, aim.currentBank())
    }

    @Test
    fun `bank ghost drag away from the rail keeps the last good bank`() {
        val aim = ManualAim()
        val base = baseBank()
        aim.enterEditMode(base, radius)
        // Drag the ghost 100 px up: the departure points away from the
        // top rail, so the ray never meets it.
        aim.dragAnchor(Anchor.GHOST, Vec2(0.0, -100.0), table())
        assertEquals(base, aim.currentBank())
    }

    @Test
    fun `bank cue drag that cannot bank keeps the last good bank`() {
        val aim = ManualAim()
        val base = baseBank()
        aim.enterEditMode(base, radius)
        // Drag the cue far left: the corrected cue cannot bank to the
        // pocket (cut > 90 deg from there).
        aim.dragAnchor(Anchor.CUE, Vec2(-400.0, 0.0), table())
        assertEquals(base, aim.currentBank())
    }

    @Test
    fun `kick edit freezes the kick, exit clears it`() {
        val aim = ManualAim()
        aim.enterEditMode(baseKick(), radius)
        assertTrue(aim.isEditMode)
        assertTrue(aim.isKickEdit)
        assertTrue(aim.hasShot)
        assertFalse(aim.hasOverrides)
        val frozen = aim.currentKick()!!
        assertNull(aim.currentShot())
        assertNull(aim.currentBank())
        assertEquals(282.0, frozen.direct.ghostPoint.x, 0.0)
        assertEquals(200.0, frozen.direct.ghostPoint.y, 0.0)
        assertEquals(57.88818512576571, frozen.direct.cutAngleDegrees, 1e-9)
        assertEquals(153.3448275862069, frozen.railContact.x, 1e-9)
        assertEquals(405.0, frozen.railContact.y, 0.0)
        assertEquals(2, frozen.kickPath.size)
        aim.exitEditMode()
        assertFalse(aim.isEditMode)
        assertFalse(aim.isKickEdit)
        assertFalse(aim.hasShot)
        assertNull(aim.currentKick())
    }

    @Test
    fun `kick cue drag re-solves from the corrected cue`() {
        val aim = ManualAim()
        aim.enterEditMode(baseKick(), radius)
        // Move the cue 20 px down: the ghost point stays put (same
        // kick), but the contact and cut angle change, and the
        // re-solve matches a fresh solveKick from the corrected cue.
        aim.dragAnchor(Anchor.CUE, Vec2(0.0, 20.0), table())
        val corrected = aim.currentKick()!!
        assertTrue(aim.hasOverrides)
        assertEquals(100.0, corrected.direct.aimLine.start.x, 0.0)
        assertEquals(340.0, corrected.direct.aimLine.start.y, 0.0)
        assertEquals(282.0, corrected.direct.ghostPoint.x, 0.0)
        assertEquals(200.0, corrected.direct.ghostPoint.y, 0.0)
        assertEquals(143.8148148148148, corrected.railContact.x, 1e-9)
        assertEquals(56.017111618789215, corrected.direct.cutAngleDegrees, 1e-9)
        val fresh = ShotSolver.solveKick(
            Vec2(100.0, 340.0), target, kickPocket, bottomRail, radius
        )!!
        assertEquals(fresh, corrected)
    }

    @Test
    fun `kick ghost drag re-aims and rebuilds the path`() {
        val aim = ManualAim()
        aim.enterEditMode(baseKick(), radius)
        // Drag the ghost 2 px right: aim line ends at the dragged
        // point, the contact moves along the rail, the pocket stays.
        aim.dragAnchor(Anchor.GHOST, Vec2(2.0, 0.0), table())
        val corrected = aim.currentKick()!!
        assertEquals(284.0, corrected.direct.ghostPoint.x, 0.0)
        assertEquals(200.0, corrected.direct.ghostPoint.y, 0.0)
        assertEquals(153.9310344827586, corrected.railContact.x, 1e-9)
        assertEquals(405.0, corrected.railContact.y, 0.0)
        assertEquals(57.605577354384614, corrected.direct.cutAngleDegrees, 1e-9)
        assertEquals(284.0, corrected.kickPath[1].end.x, 0.0)
        assertEquals(200.0, corrected.kickPath[1].end.y, 0.0)
        assertEquals(680.0, corrected.direct.targetLine.end.x, 0.0)
        assertEquals(200.0, corrected.direct.targetLine.end.y, 0.0)
        assertEquals(2, corrected.kickPath.size)
        // Deflection starts at the dragged ghost point.
        assertEquals(284.0, corrected.direct.deflectionLine.start.x, 0.0)
        assertEquals(200.0, corrected.direct.deflectionLine.start.y, 0.0)
    }

    @Test
    fun `kick ghost drag up moves the contact`() {
        val aim = ManualAim()
        aim.enterEditMode(baseKick(), radius)
        // Drag the ghost 100 px up: the mirrored ghost drops, the
        // cue-to-mirror line meets the rail further left.
        aim.dragAnchor(Anchor.GHOST, Vec2(0.0, -100.0), table())
        val corrected = aim.currentKick()!!
        assertEquals(282.0, corrected.direct.ghostPoint.x, 0.0)
        assertEquals(100.0, corrected.direct.ghostPoint.y, 0.0)
        assertEquals(139.66666666666666, corrected.railContact.x, 1e-9)
        assertEquals(64.98310652189998, corrected.direct.cutAngleDegrees, 1e-9)
    }

    @Test
    fun `kick rejected ghost drag after an accepted one keeps the last good kick`() {
        val aim = ManualAim()
        aim.enterEditMode(baseKick(), radius)
        // First drag accepted: ghost moves 2 px right, contact slides
        // along the rail.
        aim.dragAnchor(Anchor.GHOST, Vec2(2.0, 0.0), table())
        val edited = aim.currentKick()!!
        assertEquals(284.0, edited.direct.ghostPoint.x, 0.0)
        assertEquals(153.9310344827586, edited.railContact.x, 1e-9)
        // Second drag rejected (cut past 90 deg): the drag is not
        // committed, so the last good kick stays instead of snapping
        // back to the frozen base.
        aim.dragAnchor(Anchor.GHOST, Vec2(-273.0, 0.0), table())
        val kept = aim.currentKick()!!
        assertEquals(284.0, kept.direct.ghostPoint.x, 0.0)
        assertEquals(153.9310344827586, kept.railContact.x, 1e-9)
        assertTrue(aim.hasOverrides)
    }

    @Test
    fun `kick ghost drag past the impossible line keeps the last good kick`() {
        val aim = ManualAim()
        val base = baseKick()
        aim.enterEditMode(base, radius)
        // Drag the ghost far to the left: the reflected ray would point
        // away from the pocket (cut > 90 deg).
        aim.dragAnchor(Anchor.GHOST, Vec2(-273.0, 0.0), table())
        assertEquals(base, aim.currentKick())
    }

    @Test
    fun `kick cue drag that cannot kick keeps the last good kick`() {
        val aim = ManualAim()
        val base = baseKick()
        aim.enterEditMode(base, radius)
        // Drag the cue to the bottom-right corner: from there the
        // reflected ray points away from the pocket, so no kick exists.
        aim.dragAnchor(Anchor.CUE, Vec2(611.0, 76.0), table())
        assertEquals(base, aim.currentKick())
    }

    // Two-rail bank (v1.0.33): cue (580,300) -> target (600,320) ->
    // bottom then right rails -> pocket (360,20). Numbers pinned by
    // kotlinc probe (~/workspace/probe33, Probe33 full precision):
    // ghost (586.2850922803298,308.3423284382803), cut
    // 12.64117220907829 deg, contacts (623.5294117647059,340.0) and
    // (680.0,292.0), mirrored pocket (1000.0,660.0).
    private val bank2Cue = Vec2(580.0, 300.0)
    private val bank2Target = Vec2(600.0, 320.0)
    private val bank2Pocket = Vec2(360.0, 20.0)
    private val bottomRail2 = Rail(Vec2(40.0, 340.0), Vec2(0.0, -1.0))
    private val rightRail2 = Rail(Vec2(680.0, 20.0), Vec2(-1.0, 0.0))

    private fun baseBank2() =
        ShotSolver.solveTwoRailBank(
            bank2Cue, bank2Target, bank2Pocket, bottomRail2, rightRail2, radius
        )!!

    @Test
    fun `twoRailBank_freezeShowsLegsAndContacts`() {
        val aim = ManualAim()
        aim.enterEditMode(baseBank2(), radius)
        assertTrue(aim.isEditMode)
        assertTrue(aim.isTwoRailBankEdit)
        assertTrue(aim.hasShot)
        assertFalse(aim.hasOverrides)
        val frozen = aim.currentTwoRailBank()!!
        assertNull(aim.currentShot())
        assertNull(aim.currentBank())
        assertNull(aim.currentKick())
        assertEquals(586.2850922803298, frozen.direct.ghostPoint.x, 1e-9)
        assertEquals(308.3423284382803, frozen.direct.ghostPoint.y, 1e-9)
        assertEquals(12.64117220907829, frozen.direct.cutAngleDegrees, 1e-9)
        assertEquals(623.5294117647059, frozen.railContacts[0].x, 1e-9)
        assertEquals(340.0, frozen.railContacts[0].y, 0.0)
        assertEquals(680.0, frozen.railContacts[1].x, 0.0)
        assertEquals(292.0, frozen.railContacts[1].y, 1e-9)
        assertEquals(1000.0, frozen.mirroredPocket.x, 0.0)
        assertEquals(660.0, frozen.mirroredPocket.y, 0.0)
        assertEquals(3, frozen.bankPath.size)
        assertEquals(360.0, frozen.bankPath[2].end.x, 0.0)
        assertEquals(20.0, frozen.bankPath[2].end.y, 0.0)
        aim.exitEditMode()
        assertFalse(aim.isEditMode)
        assertFalse(aim.isTwoRailBankEdit)
        assertFalse(aim.hasShot)
        assertNull(aim.currentTwoRailBank())
    }

    @Test
    fun `twoRailBank_cueDragChangesCutOnly`() {
        val aim = ManualAim()
        aim.enterEditMode(baseBank2(), radius)
        // Move the cue 20 px left: the ghost and both contacts stay
        // put (same bank: they depend only on target, pocket and
        // rails, never on the cue), but the cut angle changes.
        // The cut is pinned to the spec's probe-B value with the
        // usual 1e-9 tolerance: the frozen rails are recovered by
        // bisection, so the re-solve's mirrored pocket differs from
        // a true-rails solve by ~1e-13 and the cut by 3.2e-14 deg
        // (probe33b), far inside the tolerance. No exact-equality
        // assert against a fresh true-rails solve: it would fail
        // on that float noise.
        aim.dragAnchor(Anchor.CUE, Vec2(-20.0, 0.0), table())
        val corrected = aim.currentTwoRailBank()!!
        assertTrue(aim.hasOverrides)
        assertEquals(560.0, corrected.direct.aimLine.start.x, 0.0)
        assertEquals(300.0, corrected.direct.aimLine.start.y, 0.0)
        assertEquals(586.2850922803298, corrected.direct.ghostPoint.x, 1e-9)
        assertEquals(308.3423284382803, corrected.direct.ghostPoint.y, 1e-9)
        assertEquals(623.5294117647059, corrected.railContacts[0].x, 1e-9)
        assertEquals(340.0, corrected.railContacts[0].y, 0.0)
        assertEquals(680.0, corrected.railContacts[1].x, 0.0)
        assertEquals(292.0, corrected.railContacts[1].y, 1e-9)
        assertEquals(22.756206986766507, corrected.direct.cutAngleDegrees, 1e-9)
    }

    @Test
    fun `twoRailBank_cueDragPastImpossibleLineRejects`() {
        val aim = ManualAim()
        val base = baseBank2()
        aim.enterEditMode(base, radius)
        // Drag the cue 20 px down: the solver returns null, because
        // the cut would be 102.07 deg (over 90).
        aim.dragAnchor(Anchor.CUE, Vec2(0.0, 20.0), table())
        assertEquals(base, aim.currentTwoRailBank())
    }

    @Test
    fun `twoRailBank_ghostDragMovesBothContacts`() {
        val aim = ManualAim()
        aim.enterEditMode(baseBank2(), radius)
        // Drag the ghost 2 px down: aim line ends at the dragged
        // point, BOTH contacts move along their rails, the pocket
        // stays.
        aim.dragAnchor(Anchor.GHOST, Vec2(0.0, 2.0), table())
        val corrected = aim.currentTwoRailBank()!!
        assertEquals(586.2850922803298, corrected.direct.ghostPoint.x, 1e-9)
        assertEquals(310.3423284382803, corrected.direct.ghostPoint.y, 1e-9)
        assertEquals(23.560526876418276, corrected.direct.cutAngleDegrees, 1e-9)
        assertEquals(628.402100096326, corrected.railContacts[0].x, 1e-9)
        assertEquals(340.0, corrected.railContacts[0].y, 0.0)
        assertEquals(680.0, corrected.railContacts[1].x, 0.0)
        assertEquals(303.66613755413914, corrected.railContacts[1].y, 1e-9)
        assertEquals(360.0, corrected.bankPath[2].end.x, 0.0)
        assertEquals(20.0, corrected.bankPath[2].end.y, 0.0)
        assertEquals(3, corrected.bankPath.size)
        // Deflection starts at the dragged ghost point.
        assertEquals(586.2850922803298, corrected.direct.deflectionLine.start.x, 1e-9)
        assertEquals(310.3423284382803, corrected.direct.deflectionLine.start.y, 1e-9)
    }

    @Test
    fun `twoRailBank_chainedCueThenGhostDrag`() {
        val aim = ManualAim()
        aim.enterEditMode(baseBank2(), radius)
        // Cue drag first, then ghost drag: the contacts are the SAME
        // as the ghost-only drag (they depend only on the ghost and
        // target, never on the cue), but the cut is DIFFERENT: the
        // aim starts at the corrected cue, so the cut is 13.67 deg,
        // NOT the 23.56 deg a ghost-only drag gives. A test that
        // pins 23.56 here is wrong (v1.0.30 kick lesson).
        aim.dragAnchor(Anchor.CUE, Vec2(-20.0, 0.0), table())
        aim.dragAnchor(Anchor.GHOST, Vec2(0.0, 2.0), table())
        val corrected = aim.currentTwoRailBank()!!
        assertEquals(586.2850922803298, corrected.direct.ghostPoint.x, 1e-9)
        assertEquals(310.3423284382803, corrected.direct.ghostPoint.y, 1e-9)
        assertEquals(628.402100096326, corrected.railContacts[0].x, 1e-9)
        assertEquals(340.0, corrected.railContacts[0].y, 0.0)
        assertEquals(680.0, corrected.railContacts[1].x, 0.0)
        assertEquals(303.66613755413914, corrected.railContacts[1].y, 1e-9)
        assertEquals(13.674160027265291, corrected.direct.cutAngleDegrees, 1e-9)
    }

    @Test
    fun `twoRailBank_rejectAfterAcceptKeepsAccepted`() {
        val aim = ManualAim()
        aim.enterEditMode(baseBank2(), radius)
        // First drag accepted: cue moves 20 px left, cut becomes 22.76.
        aim.dragAnchor(Anchor.CUE, Vec2(-20.0, 0.0), table())
        val edited = aim.currentTwoRailBank()!!
        assertEquals(22.756206986766507, edited.direct.cutAngleDegrees, 1e-9)
        // Second drag rejected (ghost far left, cut 176.24 deg):
        // the drag is not committed, so the last good bank stays
        // instead of snapping back to the frozen base.
        aim.dragAnchor(Anchor.GHOST, Vec2(-300.0, 0.0), table())
        val kept = aim.currentTwoRailBank()!!
        assertEquals(586.2850922803298, kept.direct.ghostPoint.x, 1e-9)
        assertEquals(22.756206986766507, kept.direct.cutAngleDegrees, 1e-9)
        assertTrue(aim.hasOverrides)
    }

    @Test
    fun `twoRailBank_exitClearsFrozen`() {
        val aim = ManualAim()
        aim.enterEditMode(baseBank2(), radius)
        aim.exitEditMode()
        assertFalse(aim.isEditMode)
        assertFalse(aim.isTwoRailBankEdit)
        assertFalse(aim.hasShot)
        assertNull(aim.currentTwoRailBank())
    }

    @Test
    fun `twoRailBank_ghostDragRayMissesSecondRailRejects`() {
        val aim = ManualAim()
        val base = baseBank2()
        aim.enterEditMode(base, radius)
        // Drag the ghost right until it sits directly above the
        // target (ghost.x == target.x == 600): the departure runs
        // straight down to the bottom rail, so the reflected ray
        // runs straight up, parallel to the right rail, and never
        // meets it (contact2 is null). Pinned by kotlinc probe
        // (~/workspace/probe33, T8): the cut would be 67.36 deg,
        // contact1 (600, 340), reflected direction (0, -1).
        // The drag is rejected and the frozen base is kept.
        aim.dragAnchor(Anchor.GHOST, Vec2(13.714907719670236, 0.0), table())
        assertEquals(base, aim.currentTwoRailBank())
        assertEquals(586.2850922803298, aim.currentTwoRailBank()!!.direct.ghostPoint.x, 1e-9)
        assertFalse(aim.hasOverrides)
    }
}
