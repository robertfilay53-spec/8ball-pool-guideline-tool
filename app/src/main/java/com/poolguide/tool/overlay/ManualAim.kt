package com.poolguide.tool.overlay

import com.poolguide.tool.cv.TableRect
import com.poolguide.tool.geometry.Rail
import com.poolguide.tool.geometry.Segment
import com.poolguide.tool.geometry.ShotSolver
import com.poolguide.tool.geometry.Vec2

/** Play vs edit (Line Guide lead: `MenuService` + `LinesService.b(boolean)`).
 * PLAY draws the live detection; EDIT freezes the current shot and lets the
 * user drag the cue and ghost anchors to correct a mis-detection. The
 * toggle is an explicit state on top of the existing auto-hide on touch:
 * in EDIT mode the two anchor handles are touchable by design (see
 * [LineOverlayManager]), and the lines stay where the user put them
 * until EDIT mode is left. */
enum class OverlayMode { PLAY, EDIT }

/** Which anchor the user is dragging in EDIT mode (Line Guide's
 * ControllerPointA/B: one handle for the cue end, one for the ghost end). */
enum class Anchor { CUE, GHOST }

/**
 * The EDIT-mode shot state. Pure Kotlin, no Android dependency.
 *
 * On entering EDIT mode the current detected shot is frozen as the base.
 * Dragging the CUE anchor moves the cue ball: the shot is re-solved from
 * the corrected cue (ghost point recomputed by the solver). Dragging the
 * GHOST anchor moves the aim point: the aim line is redrawn to the dragged
 * point and the cut angle + deflection are recomputed from it, while the
 * target ball and pocket stay as detected. A drag that would make the shot
 * impossible (cut > 90 degrees) is ignored — the last good shot is kept.
 * Anchors are clamped inside the table rect (inset by the ball radius)
 * when a table is known.
 *
 * One-rail bank shots (v1.0.29) freeze the same way: the base is the
 * bank solution, the CUE drag re-solves the bank from the corrected cue
 * ([ShotSolver.solveBank]), and the GHOST drag re-aims the cue at the
 * dragged point and rebuilds the bank path from the departure direction
 * it implies. The pocket stays as detected. Like the direct ghost drag,
 * this is a manual aim correction, not a physics re-solve: the drag is
 * ignored only when the cut would exceed 90 degrees, when the departure
 * ray never meets the rail, or when the contact would fall off the
 * table. Kick, two-rail and combo/kiss shots stay direct-only: EDIT
 * mode does not enter for them.
 */
class ManualAim {

    var mode: OverlayMode = OverlayMode.PLAY
        private set

    private var baseShot: ShotSolver.ShotSolution? = null
    private var baseBank: ShotSolver.BankSolution? = null
    /** The rail the frozen bank plays off, recovered from the bank
     * solution (v1.0.29): the rail passes through the rail contact and
     * its normal is the pocket-to-mirrored-pocket direction. The
     * solution does not store the rail, and the mirror/reflection
     * helpers are sign-agnostic in the normal, so either sign works. */
    private var bankRail: Rail? = null
    /** The table rect from the last anchor drag (v1.0.29): the bank
     * contact must fall on the table, and the rect is only known from
     * a drag (like the anchor clamp). Null when no drag has supplied
     * one, in which case the contact check is skipped. */
    private var lastTable: TableRect? = null
    private var ballRadiusPx: Double = 9.0
    private var cueOverride: Vec2? = null
    private var ghostOverride: Vec2? = null

    val isEditMode: Boolean get() = mode == OverlayMode.EDIT
    val hasShot: Boolean get() = baseShot != null || baseBank != null
    /** True when the frozen shot is a one-rail bank (v1.0.29). */
    val isBankEdit: Boolean get() = baseBank != null
    val hasOverrides: Boolean get() = cueOverride != null || ghostOverride != null

    /** Freeze [shot] as the editable base and enter EDIT mode. */
    fun enterEditMode(shot: ShotSolver.ShotSolution, radiusPx: Double) {
        baseShot = shot
        baseBank = null
        bankRail = null
        ballRadiusPx = radiusPx
        cueOverride = null
        ghostOverride = null
        mode = OverlayMode.EDIT
    }

    /** Freeze [bank] as the editable base and enter EDIT mode (v1.0.29).
     * The rail is recovered from the solution: it passes through the
     * rail contact, normal along pocket-to-mirrored-pocket. */
    fun enterEditMode(bank: ShotSolver.BankSolution, radiusPx: Double) {
        baseShot = null
        baseBank = bank
        ballRadiusPx = radiusPx
        cueOverride = null
        ghostOverride = null
        bankRail = recoverRail(bank)
        mode = OverlayMode.EDIT
    }

    /** Recover the bank's rail from its solution (v1.0.29). */
    private fun recoverRail(bank: ShotSolver.BankSolution): Rail {
        val pocket = bank.bankPath.last().end
        val normal = (bank.mirroredPocket - pocket).normalized()
        return Rail(bank.railContact, normal)
    }

    /** Leave EDIT mode; the frozen shot and any overrides are dropped. */
    fun exitEditMode() {
        mode = OverlayMode.PLAY
        baseShot = null
        baseBank = null
        bankRail = null
        lastTable = null
        cueOverride = null
        ghostOverride = null
    }

    /** The shot to draw in EDIT mode: the frozen base with any overrides
     * applied. Null when no shot was frozen. */
    fun currentShot(): ShotSolver.ShotSolution? {
        val base = baseShot ?: return null
        return applyOverrides(base)
    }

    /** The bank to draw in EDIT mode (v1.0.29): the frozen base with any
     * overrides applied. Null when no bank was frozen. */
    fun currentBank(): ShotSolver.BankSolution? {
        val base = baseBank ?: return null
        return applyBankOverrides(base)
    }

    /** Drag [anchor] by [delta] (working coordinates), clamped inside
     * [table] (inset by the ball radius) when a table is known. */
    fun dragAnchor(anchor: Anchor, delta: Vec2, table: TableRect?) {
        if (table != null) lastTable = table
        val base = baseShot ?: baseBank?.direct ?: return
        when (anchor) {
            Anchor.CUE -> {
                val from = cueOverride ?: base.aimLine.start
                cueOverride = clamp(from + delta, table)
            }
            Anchor.GHOST -> {
                val from = ghostOverride ?: base.ghostPoint
                ghostOverride = clamp(from + delta, table)
            }
        }
    }

    private fun clamp(p: Vec2, table: TableRect?): Vec2 {
        if (table == null) return p
        val r = ballRadiusPx
        return Vec2(
            p.x.coerceIn(table.topLeft.x + r, table.topRight.x - r),
            p.y.coerceIn(table.topLeft.y + r, table.bottomLeft.y - r)
        )
    }

    private fun applyOverrides(base: ShotSolver.ShotSolution): ShotSolver.ShotSolution {
        val cue = cueOverride
        val ghost = ghostOverride
        if (cue == null && ghost == null) return base
        val target = base.targetLine.start
        val pocket = base.targetLine.end
        // Cue moved: re-solve from the corrected cue (ghost recomputed).
        // If the corrected cue cannot reach the pocket, keep the base.
        if (cue != null && ghost == null) {
            return ShotSolver.solveShot(cue, target, pocket, ballRadiusPx) ?: base
        }
        // Ghost dragged (with or without a cue override): aim at the
        // dragged point; cut angle + deflection recomputed from it,
        // target line unchanged.
        val aimStart = cue ?: base.aimLine.start
        val aimEnd = ghost ?: base.ghostPoint
        val aimDir = (aimEnd - aimStart).normalized()
        if (aimDir.norm() < 1e-9) return base
        val pocketDir = (pocket - target).normalized()
        val cut = ShotSolver.cutAngleDegrees(aimDir, pocketDir)
        if (cut > 90.0) return base // dragged past the impossible line
        val tangent = ShotSolver.tangentDirection(aimDir, pocketDir)
        return base.copy(
            ghostPoint = aimEnd,
            aimLine = Segment(aimStart, aimEnd),
            deflectionLine = Segment(aimEnd, aimEnd + tangent * (2 * ballRadiusPx)),
            cutAngleDegrees = cut,
            isThinCut = cut > ShotSolver.THIN_CUT_DEGREES,
            distanceToImpact = aimStart.distanceTo(aimEnd)
        )
    }

    /** Apply the anchor overrides to a frozen bank (v1.0.29). The pocket
     * stays as detected; the rail is the one recovered at freeze time.
     * CUE moved: re-solve the bank from the corrected cue (ghost and
     * contact recomputed by the solver); when the corrected cue cannot
     * bank to the pocket, or the contact would fall off the table,
     * keep the base. GHOST dragged: aim at the dragged point, and
     * rebuild the bank path from the departure direction it implies.
     * Like the direct ghost drag this is a manual aim correction, not
     * a physics re-solve: there is no pocket-consistency check on the
     * reflected ray (the ghost sits one ball diameter from the target,
     * so a few pixels of drag swing the departure several degrees and
     * any such check would reject nearly every drag). The drag is
     * ignored only when the cut would exceed 90 degrees, when the
     * departure ray never meets the rail, or when the contact would
     * fall off the table. The ray is built long (2000 px): the mirrored
     * pocket distance belongs to the original departure, and a dragged
     * departure meets the rail farther along than that. The cushion
     * correction is ideal reflection here, which matches
     * [ShotSolver.solveBank]: the fitted correction is zero. */
    private fun applyBankOverrides(base: ShotSolver.BankSolution): ShotSolver.BankSolution {
        val cue = cueOverride
        val ghost = ghostOverride
        if (cue == null && ghost == null) return base
        val target = base.direct.targetLine.start
        val pocket = base.bankPath.last().end
        val rail = bankRail ?: return base
        // Cue moved: re-solve the bank from the corrected cue.
        // If the corrected cue cannot bank to the pocket, or the
        // contact would fall off the table, keep the base.
        if (cue != null && ghost == null) {
            val solved = ShotSolver.solveBank(cue, target, pocket, rail, ballRadiusPx) ?: return base
            if (!contactOnTable(solved.railContact)) return base
            return solved
        }
        // Ghost dragged (with or without a cue override): aim at the
        // dragged point; cut angle + deflection recomputed from the
        // departure direction it implies, bank path rebuilt from it.
        val aimStart = cue ?: base.direct.aimLine.start
        val aimEnd = ghost ?: base.direct.ghostPoint
        val aimDir = (aimEnd - aimStart).normalized()
        if (aimDir.norm() < 1e-9) return base
        val depart = (target - aimEnd).normalized()
        if (depart.norm() < 1e-9) return base
        val cut = ShotSolver.cutAngleDegrees(aimDir, depart)
        if (cut > 90.0) return base
        // The contact is where the ray from the target along the
        // departure direction meets the rail. Reuse the production
        // segment check on a long ray: a null means the ray never
        // meets the rail at all.
        val mirroredPrime = target + depart * 2000.0
        val contact = ShotSolver.railContactPoint(target, mirroredPrime, rail) ?: return base
        if (!contactOnTable(contact)) return base
        val tangent = ShotSolver.tangentDirection(aimDir, depart)
        val direct = base.direct.copy(
            ghostPoint = aimEnd,
            aimLine = Segment(aimStart, aimEnd),
            deflectionLine = Segment(aimEnd, aimEnd + tangent * (2 * ballRadiusPx)),
            cutAngleDegrees = cut,
            isThinCut = cut > ShotSolver.THIN_CUT_DEGREES,
            distanceToImpact = aimStart.distanceTo(aimEnd)
        )
        return ShotSolver.BankSolution(
            direct = direct,
            railContact = contact,
            bankPath = listOf(Segment(target, contact), Segment(contact, pocket)),
            mirroredPocket = base.mirroredPocket
        )
    }

    /** True when [contact] falls on the table (v1.0.29): within the
     * rect from the last drag, with 1 px of slack for float noise.
     * No rect known means no check, like the anchor clamp. */
    private fun contactOnTable(contact: Vec2): Boolean {
        val table = lastTable ?: return true
        val tol = 1.0
        val minX = minOf(table.topLeft.x, table.bottomLeft.x) - tol
        val maxX = maxOf(table.topRight.x, table.bottomRight.x) + tol
        val minY = minOf(table.topLeft.y, table.topRight.y) - tol
        val maxY = maxOf(table.bottomLeft.y, table.bottomRight.y) + tol
        return contact.x in minX..maxX && contact.y in minY..maxY
    }
}
