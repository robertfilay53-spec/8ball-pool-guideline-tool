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
 * table. One-rail kick shots (v1.0.30) freeze the same way again: the
 * base is the kick solution, the CUE drag re-solves the kick from the
 * corrected cue ([ShotSolver.solveKick]), and the GHOST drag mirrors
 * the dragged ghost across the rail, aims the cue at the mirror, and
 * rebuilds the kick path from the contact it implies. Two-rail bank
 * shots (v1.0.33) freeze the same way again: the base is the two-rail
 * bank solution, the CUE drag re-solves the bank from the corrected
 * cue ([ShotSolver.solveTwoRailBank]), and the GHOST drag re-aims the
 * cue at the dragged point and rebuilds the two-leg bank path from
 * the departure direction it implies, reflecting once more off the
 * second rail. Two-rail kick and combo/kiss shots stay out: EDIT
 * mode does not enter for them.
 */
class ManualAim {

    var mode: OverlayMode = OverlayMode.PLAY
        private set

    private var baseShot: ShotSolver.ShotSolution? = null
    private var baseBank: ShotSolver.BankSolution? = null
    private var baseKick: ShotSolver.KickSolution? = null
    private var baseTwoRailBank: ShotSolver.TwoRailBankSolution? = null
    /** The rail the frozen kick plays off, recovered from the kick
     * solution (v1.0.30): the rail passes through the rail contact and
     * its normal is the ghost-to-mirrored-ghost direction. The
     * solution does not store the rail, and the mirror/reflection
     * helpers are sign-agnostic in the normal, so either sign works. */
    private var kickRail: Rail? = null
    /** The rail the frozen bank plays off, recovered from the bank
     * solution (v1.0.29): the rail passes through the rail contact and
     * its normal is the pocket-to-mirrored-pocket direction. The
     * solution does not store the rail, and the mirror/reflection
     * helpers are sign-agnostic in the normal, so either sign works. */
    private var bankRail: Rail? = null
    /** The two rails the frozen two-rail bank plays off, recovered
     * from the bank solution (v1.0.33): each rail passes through its
     * rail contact, its tangent the bisector of the incoming and
     * outgoing legs at that contact. The solution does not store
     * the rails, and the mirror/reflection helpers are
     * sign-agnostic in the normal, so either sign works. */
    private var bankRail1: Rail? = null
    private var bankRail2: Rail? = null
    /** The table rect from the last anchor drag (v1.0.29): the bank
     * contact must fall on the table, and the rect is only known from
     * a drag (like the anchor clamp). Null when no drag has supplied
     * one, in which case the contact check is skipped. */
    private var lastTable: TableRect? = null
    private var ballRadiusPx: Double = 9.0
    private var cueOverride: Vec2? = null
    private var ghostOverride: Vec2? = null

    val isEditMode: Boolean get() = mode == OverlayMode.EDIT
    val hasShot: Boolean get() = baseShot != null || baseBank != null || baseKick != null || baseTwoRailBank != null
    /** True when the frozen shot is a one-rail bank (v1.0.29). */
    val isBankEdit: Boolean get() = baseBank != null
    /** True when the frozen shot is a one-rail kick (v1.0.30). */
    val isKickEdit: Boolean get() = baseKick != null
    /** True when the frozen shot is a two-rail bank (v1.0.33). */
    val isTwoRailBankEdit: Boolean get() = baseTwoRailBank != null
    val hasOverrides: Boolean get() = cueOverride != null || ghostOverride != null

    /** Freeze [shot] as the editable base and enter EDIT mode. */
    fun enterEditMode(shot: ShotSolver.ShotSolution, radiusPx: Double) {
        baseShot = shot
        baseBank = null
        baseKick = null
        baseTwoRailBank = null
        bankRail = null
        kickRail = null
        bankRail1 = null
        bankRail2 = null
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
        baseKick = null
        baseTwoRailBank = null
        ballRadiusPx = radiusPx
        cueOverride = null
        ghostOverride = null
        bankRail = recoverRail(bank)
        kickRail = null
        bankRail1 = null
        bankRail2 = null
        mode = OverlayMode.EDIT
    }

    /** Freeze [kick] as the editable base and enter EDIT mode (v1.0.30).
     * The rail is recovered from the solution: it passes through the
     * rail contact, normal along ghost-to-mirrored-ghost. */
    fun enterEditMode(kick: ShotSolver.KickSolution, radiusPx: Double) {
        baseShot = null
        baseBank = null
        baseKick = kick
        baseTwoRailBank = null
        ballRadiusPx = radiusPx
        cueOverride = null
        ghostOverride = null
        bankRail = null
        kickRail = recoverKickRail(kick)
        bankRail1 = null
        bankRail2 = null
        mode = OverlayMode.EDIT
    }

    /** Freeze [bank2] as the editable base and enter EDIT mode
     * (v1.0.33). Both rails are recovered from the solution: each
     * passes through its rail contact, its tangent the bisector of
     * the incoming and outgoing legs at that contact (see
     * [recoverTwoRailBankRails]). */
    fun enterEditMode(bank2: ShotSolver.TwoRailBankSolution, radiusPx: Double) {
        baseShot = null
        baseBank = null
        baseKick = null
        baseTwoRailBank = bank2
        ballRadiusPx = radiusPx
        cueOverride = null
        ghostOverride = null
        bankRail = null
        kickRail = null
        val (rail1, rail2) = recoverTwoRailBankRails(bank2)
        bankRail1 = rail1
        bankRail2 = rail2
        mode = OverlayMode.EDIT
    }

    /** Recover the bank's rail from its solution (v1.0.29). */
    private fun recoverRail(bank: ShotSolver.BankSolution): Rail {
        val pocket = bank.bankPath.last().end
        val normal = (bank.mirroredPocket - pocket).normalized()
        return Rail(bank.railContact, normal)
    }

    /** Recover the kick's rail from its solution (v1.0.30). */
    private fun recoverKickRail(kick: ShotSolver.KickSolution): Rail {
        val ghost = kick.direct.ghostPoint
        val normal = (kick.mirroredGhost - ghost).normalized()
        return Rail(kick.railContact, normal)
    }

    /** Recover the two-rail bank's rails from its solution (v1.0.33):
     * at each contact the rail tangent is the bisector of the
     * incoming and outgoing legs (their normalized directions
     * summed and normalized), and the normal is the tangent's
     * perpendicular. The recovered normal may point OUT of the table
     * (opposite sign to the true rail normal); that is fine because
     * [ShotSolver.railContactPoint] and
     * [ShotSolver.reflectDirection] are sign-insensitive. A
     * degenerate leg pair (a 180-degree turn, which a bank path
     * never has) falls back to the incoming leg's perpendicular.
     * Probe-verified on the v1.0.33 fixture: rail1 tangent (1, 0),
     * normal (0, 1); rail2 tangent (0, -1), normal (1, 0). */
    private fun recoverTwoRailBankRails(
        bank2: ShotSolver.TwoRailBankSolution
    ): Pair<Rail, Rail> {
        val path = bank2.bankPath
        fun railAt(index: Int): Rail {
            val contact = path[index].end
            val incoming = (path[index].end - path[index].start).normalized()
            val outgoing = (path[index + 1].end - path[index + 1].start).normalized()
            val sum = incoming + outgoing
            val tangent = if (sum.norm() < 1e-9) incoming else sum.normalized()
            return Rail(contact, tangent.perp())
        }
        return railAt(0) to railAt(1)
    }

    /** Leave EDIT mode; the frozen shot and any overrides are dropped. */
    fun exitEditMode() {
        mode = OverlayMode.PLAY
        baseShot = null
        baseBank = null
        baseKick = null
        baseTwoRailBank = null
        bankRail = null
        kickRail = null
        bankRail1 = null
        bankRail2 = null
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

    /** The kick to draw in EDIT mode (v1.0.30): the frozen base with any
     * overrides applied. Null when no kick was frozen. */
    fun currentKick(): ShotSolver.KickSolution? {
        val base = baseKick ?: return null
        return applyKickOverrides(base)
    }

    /** The two-rail bank to draw in EDIT mode (v1.0.33): the frozen
     * base with any overrides applied. Null when no two-rail bank
     * was frozen. */
    fun currentTwoRailBank(): ShotSolver.TwoRailBankSolution? {
        val base = baseTwoRailBank ?: return null
        return applyTwoRailBankOverrides(base)
    }

    /** Drag [anchor] by [delta] (working coordinates), clamped inside
     * [table] (inset by the ball radius) when a table is known. A drag
     * the correction rejects (cut over 90, contact off the table, no
     * re-solve) is not committed: the override reverts to the last
     * good value, so an earlier accepted drag is never wiped out by
     * a later rejected one. Without the revert the rejected drag
     * would leave its override set and every later read would fall
     * back to the frozen base, losing the accepted correction. */
    fun dragAnchor(anchor: Anchor, delta: Vec2, table: TableRect?) {
        if (table != null) lastTable = table
        val base = baseShot ?: baseBank?.direct ?: baseKick?.direct ?: baseTwoRailBank?.direct ?: return
        when (anchor) {
            Anchor.CUE -> {
                val prev = cueOverride
                val from = prev ?: base.aimLine.start
                cueOverride = clamp(from + delta, table)
                if (isRejected()) cueOverride = prev
            }
            Anchor.GHOST -> {
                val prev = ghostOverride
                val from = prev ?: base.ghostPoint
                ghostOverride = clamp(from + delta, table)
                if (isRejected()) ghostOverride = prev
            }
        }
    }

    /** True when the current overrides are rejected: the correction
     * collapses to the frozen base. An accepted correction always
     * builds a new shot/bank object; a rejected one returns the base
     * itself, so reference identity is the exact signal. */
    private fun isRejected(): Boolean {
        if (baseShot != null) return currentShot() === baseShot
        if (baseBank != null) return currentBank() === baseBank
        if (baseKick != null) return currentKick() === baseKick
        if (baseTwoRailBank != null) return currentTwoRailBank() === baseTwoRailBank
        return false
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

    /** Apply the anchor overrides to a frozen kick (v1.0.30). The pocket
     * stays as detected; the rail is the one recovered at freeze time.
     * CUE moved: re-solve the kick from the corrected cue (ghost and
     * contact recomputed by the solver); when the corrected cue cannot
     * kick to the pocket, or the contact would fall off the table,
     * keep the base. GHOST dragged: mirror the dragged ghost across
     * the rail and aim the cue at the mirror; the contact is where
     * that line meets the rail, and the reflected ray from it must
     * head toward the dragged ghost. Mirroring plus aiming is exactly
     * how [ShotSolver.solveKick] builds the path, so the contact, cut
     * and deflection all follow from the dragged point, like the
     * direct ghost drag. The drag is ignored only when the cue-to-
     * mirror line never meets the rail, when the reflected ray does
     * not head toward the dragged ghost, when the cut would exceed
     * 90 degrees, or when the contact would fall off the table.
     * Note the contact can never fall off the rail from a clamped
     * drag: it is the crossing of cue to mirrored ghost, so its
     * rail coordinate always lies between the cue's and the ghost's,
     * both inside the table. The check is still kept, as defence,
     * for the unclamped (no-table) path. */
    private fun applyKickOverrides(base: ShotSolver.KickSolution): ShotSolver.KickSolution {
        val cue = cueOverride
        val ghost = ghostOverride
        if (cue == null && ghost == null) return base
        val target = base.direct.targetLine.start
        val pocket = base.direct.targetLine.end
        val rail = kickRail ?: return base
        // Cue moved: re-solve the kick from the corrected cue.
        // If the corrected cue cannot kick to the pocket, or the
        // contact would fall off the table, keep the base.
        if (cue != null && ghost == null) {
            val solved = ShotSolver.solveKick(cue, target, pocket, rail, ballRadiusPx) ?: return base
            if (!contactOnTable(solved.railContact)) return base
            return solved
        }
        // Ghost dragged (with or without a cue override): mirror the
        // dragged ghost across the rail, aim the cue at the mirror,
        // and rebuild the kick path from the contact it implies.
        val aimStart = cue ?: base.direct.aimLine.start
        val aimEnd = ghost ?: base.direct.ghostPoint
        val mirrored = ShotSolver.mirrorPointAcrossRail(aimEnd, rail)
        val contact = ShotSolver.railContactPoint(aimStart, mirrored, rail) ?: return base
        if (!contactOnTable(contact)) return base
        val outDir = ShotSolver.reflectDirection(contact - aimStart, rail)
        if ((aimEnd - contact).dot(outDir) <= 0.0) return base
        val pocketDir = (pocket - target).normalized()
        val cut = ShotSolver.cutAngleDegrees(outDir, pocketDir)
        if (cut > 90.0) return base
        val tangent = ShotSolver.tangentDirection(outDir, pocketDir)
        val direct = base.direct.copy(
            ghostPoint = aimEnd,
            aimLine = Segment(aimStart, contact),
            deflectionLine = Segment(aimEnd, aimEnd + tangent * (2 * ballRadiusPx)),
            cutAngleDegrees = cut,
            isThinCut = cut > ShotSolver.THIN_CUT_DEGREES,
            distanceToImpact = aimStart.distanceTo(contact) + contact.distanceTo(aimEnd)
        )
        return ShotSolver.KickSolution(
            direct = direct,
            railContact = contact,
            kickPath = listOf(Segment(aimStart, contact), Segment(contact, aimEnd)),
            mirroredGhost = mirrored
        )
    }

    /** Apply the anchor overrides to a frozen two-rail bank (v1.0.33).
     * The pocket stays as detected; the rails are the ones recovered
     * at freeze time. CUE moved: re-solve the bank from the corrected
     * cue ([ShotSolver.solveTwoRailBank]); when the corrected cue
     * cannot bank to the pocket, or either contact would fall off
     * the table, keep the base. The ghost and contacts depend only
     * on the target, pocket and rails, never on the cue, so a cue
     * drag moves the cut only. GHOST dragged: aim at the dragged
     * point, and rebuild the bank path from the departure direction
     * it implies, reflecting once more off the second rail. Like the
     * one-rail bank ghost drag this is a manual aim correction, not
     * a physics re-solve: there is no pocket-consistency check on
     * the reflected ray (the ghost sits one ball diameter from the
     * target, so a few pixels of drag swing the departure several
     * degrees and any such check would reject nearly every drag).
     * The drag is ignored only when the cut would exceed 90 degrees,
     * when either departure ray never meets its rail, or when either
     * contact would fall off the table. The rays are built long
     * (2000 px): the mirrored pocket distance belongs to the
     * original departure, and a dragged departure meets the rails
     * farther along than that. The cushion correction is ideal
     * reflection here, which matches
     * [ShotSolver.solveTwoRailBank]: the fitted correction is zero.
     * Note the contact can never fall off the rail from a clamped
     * drag: contact1 is the crossing of the target-to-departure ray
     * with rail1, so its rail coordinate lies between the target
     * and the departure point. The check is still kept, as defence,
     * for the unclamped (no-table) path. */
    private fun applyTwoRailBankOverrides(
        base: ShotSolver.TwoRailBankSolution
    ): ShotSolver.TwoRailBankSolution {
        val cue = cueOverride
        val ghost = ghostOverride
        if (cue == null && ghost == null) return base
        val target = base.direct.targetLine.start
        val pocket = base.bankPath.last().end
        val rail1 = bankRail1 ?: return base
        val rail2 = bankRail2 ?: return base
        // Cue moved: re-solve the bank from the corrected cue.
        // If the corrected cue cannot bank to the pocket, or either
        // contact would fall off the table, keep the base.
        if (cue != null && ghost == null) {
            val solved = ShotSolver.solveTwoRailBank(
                cue, target, pocket, rail1, rail2, ballRadiusPx
            ) ?: return base
            if (!contactOnTable(solved.railContacts[0])) return base
            if (!contactOnTable(solved.railContacts[1])) return base
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
        // The first contact is where the ray from the target along
        // the departure direction meets the first rail. Reuse the
        // production segment check on a long ray: a null means the
        // ray never meets the rail at all.
        val contact1 = ShotSolver.railContactPoint(
            target, target + depart * 2000.0, rail1
        ) ?: return base
        if (!contactOnTable(contact1)) return base
        // Reflect off the first rail and meet the second the same way.
        val dir2 = ShotSolver.reflectDirection(depart, rail1)
        val contact2 = ShotSolver.railContactPoint(
            contact1, contact1 + dir2 * 2000.0, rail2
        ) ?: return base
        if (!contactOnTable(contact2)) return base
        val tangent = ShotSolver.tangentDirection(aimDir, depart)
        val direct = base.direct.copy(
            ghostPoint = aimEnd,
            aimLine = Segment(aimStart, aimEnd),
            deflectionLine = Segment(aimEnd, aimEnd + tangent * (2 * ballRadiusPx)),
            cutAngleDegrees = cut,
            isThinCut = cut > ShotSolver.THIN_CUT_DEGREES,
            distanceToImpact = aimStart.distanceTo(aimEnd)
        )
        return ShotSolver.TwoRailBankSolution(
            direct = direct,
            railContacts = listOf(contact1, contact2),
            bankPath = listOf(
                Segment(target, contact1),
                Segment(contact1, contact2),
                Segment(contact2, pocket)
            ),
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
