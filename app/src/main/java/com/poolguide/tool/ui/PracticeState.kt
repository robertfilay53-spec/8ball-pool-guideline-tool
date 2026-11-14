package com.poolguide.tool.ui

import com.poolguide.tool.cv.TableRect
import com.poolguide.tool.geometry.Rail
import com.poolguide.tool.geometry.ShotSolver
import com.poolguide.tool.geometry.Vec2
import kotlin.math.abs

/**
 * Offline practice-table state (v1.0.4, Cheto lead; bank/combo/kiss
 * modes added v1.0.6; bank mode added v1.0.7). Pure Kotlin — no Android
 * dependency — so it is fully unit-testable on the JVM.
 *
 * Four modes, one solver each (the same [ShotSolver] the live overlay
 * uses, so what you practise is what the tool draws over the game):
 * - [PracticeMode.DIRECT]: cue -> target -> pocket.
 * - [PracticeMode.BANK]: cue -> target -> one rail -> pocket. The
 *   bank is ranked by the direct cut angle, easiest pot first, across
 *   all pockets and all four rails. A bank to a pocket on the rail
 *   itself is degenerate (the mirror is the pocket), so those are
 *   skipped; the rail contact must also lie inside the rail span,
 *   four ball radii clear of the corners, and not on the pocket.
 * - [PracticeMode.COMBO]: cue -> middle (first ball) -> target
 *   (second ball) -> pocket. The first ball must reach the second
 *   ball's ghost point; the solver ranks pockets by the second shot's
 *   cut angle, easiest pot first.
 * - [PracticeMode.KISS]: cue kisses the middle ball thin and deflects
 *   onto the target, which then goes to a pocket.
 *
 * Default layouts are verified against the solver (see PracticeStateTest):
 * the combo default solves to the top-right pocket (first cut 6.4 deg,
 * second cut 39.1 deg), the kiss default solves to the bottom-right
 * pocket (kiss cut 72.2 deg, second cut 27.2 deg), and the bank default
 * solves off the bottom rail to the top-right pocket (cut 30.8 deg,
 * contact (502.2, 340.0)). Switching mode re-lays out the balls to
 * that mode's default.
 */
class PracticeState(
    private val table: TableRect,
    private val ballRadiusPx: Double
) {
    enum class PracticeMode { DIRECT, BANK, COMBO, KISS }

    /** The solved shot for the current mode, or null if there is none. */
    sealed interface PracticeSolution {
        data class Direct(val shot: ShotSolver.ShotSolution) : PracticeSolution
        data class Bank(
            val solution: ShotSolver.BankSolution,
            /** Which rail the bank runs off ("top", "bottom", "left", "right"). */
            val railName: String
        ) : PracticeSolution
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
     * or selectable in DIRECT/BANK mode). */
    fun balls(): List<PracticeBall> = when (mode) {
        PracticeMode.DIRECT, PracticeMode.BANK -> listOf(cue, target)
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
            PracticeMode.BANK -> {
                // Verified against the solver before the change: bank
                // off the bottom rail to the top-right pocket, cut
                // 30.8 deg, contact (502.2, 340.0).
                cue = PracticeBall(0, Vec2(120.0, 80.0), true)
                target = PracticeBall(1, Vec2(480.0, 300.0), false)
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
        PracticeMode.BANK -> solveBank()?.let { PracticeSolution.Bank(it.first, it.second) }
        PracticeMode.COMBO -> solveCombo()?.let { PracticeSolution.Combo(it) }
        PracticeMode.KISS -> solveKiss()?.let { PracticeSolution.Kiss(it) }
    }

    /** Direct shot: cue -> target -> best pocket (smallest cut). */
    fun solveDirect(): ShotSolver.ShotSolution? {
        if (cue.center.distanceTo(target.center) < ballRadiusPx * 2 + 0.5) return null
        return ShotSolver.bestShot(cue.center, target.center, table.pockets(), ballRadiusPx)
    }

    /** The four cushions of this table, normals pointing into the table
     * (same construction as the live pipeline's railsOf). */
    private fun railsOf(): List<Rail> = listOf(
        Rail(table.topLeft, Vec2(0.0, 1.0)),   // top rail, normal points down
        Rail(table.bottomLeft, Vec2(0.0, -1.0)), // bottom rail, normal points up
        Rail(table.topLeft, Vec2(1.0, 0.0)),   // left rail, normal points right
        Rail(table.topRight, Vec2(-1.0, 0.0))  // right rail, normal points left
    )

    private val railNames = listOf("top", "bottom", "left", "right")

    /** Bank: cue -> target -> one rail -> best pocket, ranked by the
     * direct cut angle (easiest pot first). Returns the bank plus the
     * rail it runs off, or null when no bank is possible.
     *
     * Three guards keep degenerate banks out (the live solver's
     * solveBank does not apply them, because the live pipeline only
     * tries banks when no direct shot exists — in practice mode the
     * user asks for a bank explicitly, so a direct shot may exist):
     * a pocket on the rail itself mirrors to itself, so that rail is
     * skipped for that pocket; the contact must lie inside the rail
     * span, one ball radius clear of the corners; and it must not sit
     * on the pocket. */
    fun solveBank(): Pair<ShotSolver.BankSolution, String>? {
        if (cue.center.distanceTo(target.center) < ballRadiusPx * 2 + 0.5) return null
        val rails = railsOf()
        var best: ShotSolver.BankSolution? = null
        var bestRail = ""
        var bestCut = Double.MAX_VALUE
        for (pocket in table.pockets()) {
            for ((index, rail) in rails.withIndex()) {
                if (abs((pocket - rail.point).dot(rail.normal)) < 1.0) continue
                val bank = ShotSolver.solveBank(
                    cue.center, target.center, pocket, rail, ballRadiusPx
                ) ?: continue
                val contact = bank.railContact
                // The contact must lie inside the rail span, four ball
                // radii (two ball diameters) clear of the corners:
                // inside a pocket's jaws a bank is not a clean bank,
                // and the pocket ring would sit on top of the contact.
                val along = if (index < 2) contact.x else contact.y
                val lo = if (index < 2) table.topLeft.x + 4 * ballRadiusPx
                    else table.topLeft.y + 4 * ballRadiusPx
                val hi = if (index < 2) table.topRight.x - 4 * ballRadiusPx
                    else table.bottomLeft.y - 4 * ballRadiusPx
                if (along < lo || along > hi) continue
                if (contact.distanceTo(pocket) < 2 * ballRadiusPx) continue
                if (bank.direct.cutAngleDegrees < bestCut) {
                    best = bank
                    bestRail = railNames[index]
                    bestCut = bank.direct.cutAngleDegrees
                }
            }
        }
        return best?.let { it to bestRail }
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
