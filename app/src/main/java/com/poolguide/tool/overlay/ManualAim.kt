package com.poolguide.tool.overlay

import com.poolguide.tool.cv.TableRect
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
 */
class ManualAim {

    var mode: OverlayMode = OverlayMode.PLAY
        private set

    private var baseShot: ShotSolver.ShotSolution? = null
    private var ballRadiusPx: Double = 9.0
    private var cueOverride: Vec2? = null
    private var ghostOverride: Vec2? = null

    val isEditMode: Boolean get() = mode == OverlayMode.EDIT
    val hasShot: Boolean get() = baseShot != null
    val hasOverrides: Boolean get() = cueOverride != null || ghostOverride != null

    /** Freeze [shot] as the editable base and enter EDIT mode. */
    fun enterEditMode(shot: ShotSolver.ShotSolution, radiusPx: Double) {
        baseShot = shot
        ballRadiusPx = radiusPx
        cueOverride = null
        ghostOverride = null
        mode = OverlayMode.EDIT
    }

    /** Leave EDIT mode; the frozen shot and any overrides are dropped. */
    fun exitEditMode() {
        mode = OverlayMode.PLAY
        baseShot = null
        cueOverride = null
        ghostOverride = null
    }

    /** The shot to draw in EDIT mode: the frozen base with any overrides
     * applied. Null when no shot was frozen. */
    fun currentShot(): ShotSolver.ShotSolution? {
        val base = baseShot ?: return null
        return applyOverrides(base)
    }

    /** Drag [anchor] by [delta] (working coordinates), clamped inside
     * [table] (inset by the ball radius) when a table is known. */
    fun dragAnchor(anchor: Anchor, delta: Vec2, table: TableRect?) {
        val base = baseShot ?: return
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
}
