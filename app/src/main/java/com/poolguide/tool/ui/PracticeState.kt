package com.poolguide.tool.ui

import com.poolguide.tool.cv.TableRect
import com.poolguide.tool.geometry.ShotSolver
import com.poolguide.tool.geometry.Vec2

/**
 * Offline practice-table state (Cheto lead: `TrickActivity$Trickshot`,
 * implemented from scratch with our own solver). A self-contained table
 * with a draggable cue ball and target ball; the shot is solved live with
 * [ShotSolver.bestShot] across the table's pockets. No capture, no
 * overlay, no game interaction — zero ban risk by construction.
 *
 * Pure Kotlin, no Android dependency. Coordinates are the practice
 * view's logical coordinates (the view scales them to the screen).
 */
class PracticeState(
    val table: TableRect,
    val ballRadiusPx: Double
) {
    data class PracticeBall(
        val id: Int,
        val center: Vec2,
        val radiusPx: Double,
        val isCue: Boolean
    )

    private fun defaultCue() = PracticeBall(
        0,
        Vec2(table.topLeft.x + table.width * 0.25, table.center.y),
        ballRadiusPx, true
    )

    private fun defaultTarget() = PracticeBall(
        1,
        Vec2(table.topLeft.x + table.width * 0.60, table.center.y),
        ballRadiusPx, false
    )

    var cue: PracticeBall = defaultCue()
        private set
    var target: PracticeBall = defaultTarget()
        private set

    /** The ball the nudge buttons move: the cue by default. */
    var selectedId: Int = 0
        private set

    val selected: PracticeBall get() = if (selectedId == 0) cue else target

    fun select(id: Int) {
        if (id == 0 || id == 1) selectedId = id
    }

    /** Move a ball, clamped inside the table (inset by the ball radius). */
    fun moveBall(id: Int, to: Vec2) {
        val clamped = clampInside(to)
        if (id == 0) cue = cue.copy(center = clamped)
        else target = target.copy(center = clamped)
    }

    /** 1-px nudge (Cheto's control cluster): moves the selected ball. */
    fun nudge(dx: Double, dy: Double) = moveBall(selectedId, selected.center + Vec2(dx, dy))

    fun reset() {
        cue = defaultCue()
        target = defaultTarget()
        selectedId = 0
    }

    /** The best direct shot for the current layout, or null. */
    fun solve(): ShotSolver.ShotSolution? =
        ShotSolver.bestShot(cue.center, target.center, table.pockets(), ballRadiusPx)

    /** Hit-test: the ball whose centre is within [radiusPx + slop] of [p];
     * the nearer ball wins when both are in range. */
    fun ballAt(p: Vec2, slop: Double = 12.0): PracticeBall? {
        val cueDist = p.distanceTo(cue.center)
        val targetDist = p.distanceTo(target.center)
        return when {
            cueDist <= ballRadiusPx + slop && cueDist <= targetDist -> cue
            targetDist <= ballRadiusPx + slop -> target
            else -> null
        }
    }

    private fun clampInside(p: Vec2): Vec2 = Vec2(
        p.x.coerceIn(table.topLeft.x + ballRadiusPx, table.topRight.x - ballRadiusPx),
        p.y.coerceIn(table.topLeft.y + ballRadiusPx, table.bottomLeft.y - ballRadiusPx)
    )
}
