package com.poolguide.tool.ui

import com.poolguide.tool.cv.TableRect
import com.poolguide.tool.geometry.ShotSolver
import com.poolguide.tool.geometry.Vec2

/**
 * Offline practice-table state (v1.0.4, Cheto lead; bank/combo/kiss
 * modes added v1.0.6). Pure Kotlin — no Android dependency — so it is
 * fully unit-testable on the JVM.
 *
 * Three modes, one solver each (the same [ShotSolver] the live overlay
 * uses, so what you practise is what the tool draws over the game):
 * - [PracticeMode.DIRECT]: cue -> target -> pocket.
 * - [PracticeMode.COMBO]: cue -> middle (first ball) -> target
 *   (second ball) -> pocket. The first ball must reach the second
 *   ball's ghost point; the solver ranks pockets by the second shot's
 *   cut angle, easiest pot first.
 * - [PracticeMode.KISS]: cue kisses the middle ball thin and deflects
 *   onto the target, which then goes to a pocket.
 *
 * Default layouts are verified against the solver (see PracticeStateTest):
 * the combo default solves to the top-right pocket (first cut 6.4 deg,
 * second cut 39.1 deg) and the kiss default solves to the bottom-right
 * pocket (kiss cut 72.2 deg, second cut 27.2 deg). Switching mode
 * re-lays out the balls to that mode's default.
 */
class PracticeState(
    private val table: TableRect,
    private val ballRadiusPx: Double
) {
    enum class PracticeMode { DIRECT, COMBO, KISS }

    /** The solved shot for the current mode, or null if there is none. */
    sealed interface PracticeSolution {
        data class Direct(val shot: ShotSolver.ShotSolution) : PracticeSolution
        data class Combo(val solution: ShotSolver.ComboSolution) : PracticeSolution
        data class Kiss(val solution: ShotSolver.KissSolution) : PracticeSolution
    }

    data class PracticeBall(val id: Int, var center: Vec2, val isCue: Boolean)

    var mode: PracticeMode = PracticeMode.DIRECT
        private set

    var cue: PracticeBall = PracticeBall(0, Vec2(200.0, 180.0), true)
        private set
    var target: PracticeBall = PracticeBall(1, Vec2(424.0, 180.0), false)
        private set
    /** First ball of a combo, or the kissed ball of a kiss shot.
     * Only present in COMBO/KISS modes. */
    var middle: PracticeBall = PracticeBall(2, Vec2(320.0, 180.0), false)
        private set

    var selectedId: Int = 0
        private set

    /** Balls on the table in this mode (the middle ball is not drawn
     * or selectable in DIRECT mode). */
    fun balls(): List<PracticeBall> = when (mode) {
        PracticeMode.DIRECT -> listOf(cue, target)
        PracticeMode.COMBO, PracticeMode.KISS -> listOf(cue, target, middle)
    }

    /** Switch mode and re-lay out the balls to that mode's default. */
    fun setMode(newMode: PracticeMode) {
        mode = newMode
        reset()
    }

    fun select(id: Int) {
        if (balls().any { it.id == id }) selectedId = id
    }

    fun selected(): PracticeBall = balls().first { it.id == selectedId }

    fun nudge(dx: Double, dy: Double) {
        val ball = selected()
        moveBall(ball.id, Vec2(ball.center.x + dx, ball.center.y + dy))
    }

    fun moveBall(id: Int, at: Vec2) {
        val ball = balls().firstOrNull { it.id == id } ?: return
        val minX = table.topLeft.x + ballRadiusPx
        val maxX = table.topRight.x - ballRadiusPx
        val minY = table.topLeft.y + ballRadiusPx
        val maxY = table.bottomLeft.y - ballRadiusPx
        ball.center = Vec2(
            at.x.coerceIn(minX, maxX),
            at.y.coerceIn(minY, maxY)
        )
    }

    /** Hit-test: the ball whose centre is within [ballRadiusPx + slop]
     * of [at]; the nearer ball wins when both are in range. */
    fun ballAt(at: Vec2, slop: Double = 12.0): PracticeBall? {
        val radius = ballRadiusPx + slop
        return balls().minByOrNull { it.center.distanceTo(at) }
            ?.takeIf { it.center.distanceTo(at) <= radius }
    }

    /** Restore this mode's default layout and selection. */
    fun reset() {
        cue = PracticeBall(0, Vec2(200.0, 180.0), true)
        when (mode) {
            PracticeMode.DIRECT -> {
                target = PracticeBall(1, Vec2(424.0, 180.0), false)
                middle = PracticeBall(2, Vec2(320.0, 180.0), false)
            }
            PracticeMode.COMBO -> {
                middle = PracticeBall(2, Vec2(320.0, 180.0), false)
                target = PracticeBall(1, Vec2(440.0, 180.0), false)
            }
            PracticeMode.KISS -> {
                middle = PracticeBall(2, Vec2(320.0, 140.0), false)
                target = PracticeBall(1, Vec2(440.0, 180.0), false)
            }
        }
        selectedId = 0
    }

    /** Solve the shot for the current mode. */
    fun solve(): PracticeSolution? = when (mode) {
        PracticeMode.DIRECT -> solveDirect()?.let { PracticeSolution.Direct(it) }
        PracticeMode.COMBO -> solveCombo()?.let { PracticeSolution.Combo(it) }
        PracticeMode.KISS -> solveKiss()?.let { PracticeSolution.Kiss(it) }
    }

    /** Direct shot: cue -> target -> best pocket (smallest cut). */
    fun solveDirect(): ShotSolver.ShotSolution? {
        if (cue.center.distanceTo(target.center) < ballRadiusPx * 2 + 0.5) return null
        return ShotSolver.bestShot(cue.center, target.center, table.pockets(), ballRadiusPx)
    }

    /** Combo: cue -> middle -> target -> best pocket, ranked by the
     * second shot's cut angle (easiest pot first). */
    fun solveCombo(): ShotSolver.ComboSolution? {
        if (cue.center.distanceTo(middle.center) < ballRadiusPx * 2 + 0.5) return null
        if (middle.center.distanceTo(target.center) < ballRadiusPx * 2 + 0.5) return null
        var best: ShotSolver.ComboSolution? = null
        var bestSecondCut = Double.MAX_VALUE
        for (pocket in table.pockets()) {
            val combo = ShotSolver.solveCombo(
                cue.center, middle.center, target.center, pocket, ballRadiusPx
            ) ?: continue
            if (combo.secondShot.cutAngleDegrees < bestSecondCut) {
                best = combo
                bestSecondCut = combo.secondShot.cutAngleDegrees
            }
        }
        return best
    }

    /** Kiss: cue kisses middle thin, deflects onto target -> best
     * pocket, ranked by the second shot's cut angle. */
    fun solveKiss(): ShotSolver.KissSolution? {
        if (cue.center.distanceTo(middle.center) < ballRadiusPx * 2 + 0.5) return null
        if (middle.center.distanceTo(target.center) < ballRadiusPx * 2 + 0.5) return null
        var best: ShotSolver.KissSolution? = null
        var bestSecondCut = Double.MAX_VALUE
        for (pocket in table.pockets()) {
            val kiss = ShotSolver.solveKiss(
                cue.center, middle.center, target.center, pocket, ballRadiusPx
            ) ?: continue
            if (kiss.secondShot.cutAngleDegrees < bestSecondCut) {
                best = kiss
                bestSecondCut = kiss.secondShot.cutAngleDegrees
            }
        }
        return best
    }
}
