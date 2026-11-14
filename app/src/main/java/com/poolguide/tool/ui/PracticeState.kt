package com.poolguide.tool.ui

import com.poolguide.tool.cv.TableRect
import com.poolguide.tool.geometry.BallGroup
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
 *   Two-rail banks (v1.0.9) are ranked after one-rail banks: a
 *   two-rail bank only wins when its cut angle is smaller than the
 *   best one-rail cut by more than [TWO_RAIL_CUT_MARGIN_DEGREES].
 *   Each extra cushion compounds real-table error (the cushion
 *   correction is fitted to synthetic data, real-table behaviour
 *   unmeasured), so a two-rail bank must be clearly easier to be
 *   the recommended bank. The margin is 30 degrees: the v1.0.7
 *   bank default has a one-rail cut of 30.8 deg and a two-rail cut
 *   of 3.4 deg, so a smaller margin would flip that layout to a
 *   two-rail bank.
 * - [PracticeMode.COMBO]: cue -> middle (first ball) -> target
 *   (second ball) -> pocket. The first ball must reach the second
 *   ball's ghost point; the solver ranks pockets by the second shot's
 *   cut angle, easiest pot first.
 * - [PracticeMode.KISS]: cue kisses the middle ball thin and deflects
 *   onto the target, which then goes to a pocket.
 * - [PracticeMode.KICK]: cue -> one rail -> target -> pocket. The
 *   kick is ranked by the direct cut angle, easiest pot first, across
 *   all pockets and all four rails. A kick to a pocket whose ghost
 *   sits on the rail itself is degenerate (the mirror is the ghost),
 *   so those are skipped; the rail contact must also lie inside the
 *   rail span, four ball radii clear of the corners, and not on the
 *   pocket — the same three guards as the bank solve. Two-rail
 *   kicks (v1.0.11) are ranked after one-rail kicks, the same way
 *   two-rail banks are ranked after one-rail banks in BANK mode: a
 *   two-rail kick only wins when its cut angle is smaller than the
 *   best one-rail cut by more than [TWO_RAIL_CUT_MARGIN_DEGREES].
 *   Each extra cushion compounds real-table error, so a two-rail
 *   kick must be clearly easier to be the recommended kick.
 *
 * Default layouts are verified against the solver (see PracticeStateTest):
 * the combo default solves to the top-right pocket (first cut 6.4 deg,
 * second cut 39.1 deg), the kiss default solves to the bottom-right
 * pocket (kiss cut 72.2 deg, second cut 27.2 deg), and the bank default
 * solves off the bottom rail to the top-right pocket (cut 30.8 deg,
 * contact (502.2, 340.0)). Switching mode re-lays out the balls to
 * that mode's default. The two-rail test layout (cue (580, 300),
 * target (600, 320)) solves off the bottom then right rails to the
 * top-middle pocket (cut 12.6 deg, contacts (623.5, 340.0) and
 * (680.0, 292.0)); the best one-rail bank there is 67.9 deg, so the
 * two-rail bank wins by the margin rule. The kick default reuses the
 * bank default's balls (cue (120, 80), target (480, 300)) and solves
 * off the right rail to the bottom-middle pocket (cut 2.3 deg, contact
 * (680.0, 241.5)) — the same two balls, a different shot. The
 * two-rail kick test layout (cue (60, 40), target (100, 60)) solves
 * off the bottom then top rails to the bottom-left pocket (cut 16.0
 * deg, contacts (80.4, 340.0) and (102.2, 20.0)); the best one-rail
 * kick there is 58.0 deg, so the two-rail kick wins by the margin
 * rule.
 */
class PracticeState(
    private val table: TableRect,
    private val ballRadiusPx: Double
) {
    enum class PracticeMode { DIRECT, BANK, COMBO, KISS, KICK }

    /** The solved shot for the current mode, or null if there is none. */
    sealed interface PracticeSolution {
        data class Direct(val shot: ShotSolver.ShotSolution) : PracticeSolution
        data class Bank(
            val solution: ShotSolver.BankSolution,
            /** Which rail the bank runs off ("top", "bottom", "left", "right"). */
            val railName: String
        ) : PracticeSolution
        /** Two-rail bank (v1.0.9): cue -> target -> rail 1 -> rail 2
         * -> pocket. */
        data class Bank2(
            val solution: ShotSolver.TwoRailBankSolution,
            /** Which rails the bank runs off, in path order. */
            val railNames: Pair<String, String>
        ) : PracticeSolution
        data class Combo(val solution: ShotSolver.ComboSolution) : PracticeSolution
        data class Kiss(val solution: ShotSolver.KissSolution) : PracticeSolution
        /** One-rail kick (v1.0.10): cue -> rail -> target -> pocket. */
        data class Kick(
            val solution: ShotSolver.KickSolution,
            /** Which rail the kick runs off ("top", "bottom", "left", "right"). */
            val railName: String
        ) : PracticeSolution
        /** Two-rail kick (v1.0.11): cue -> rail 1 -> rail 2 ->
         * target -> pocket. */
        data class Kick2(
            val solution: ShotSolver.TwoRailKickSolution,
            /** Which rails the kick runs off, in path order. */
            val railNames: Pair<String, String>
        ) : PracticeSolution
    }

    companion object {
        /** A two-rail bank must beat the best one-rail bank by more
         * than this many degrees of cut angle to be the recommended
         * bank in BANK mode (see the class KDoc). */
        const val TWO_RAIL_CUT_MARGIN_DEGREES = 30.0
    }

    /** One ball on the practice table. The group is known by
     * construction (v1.0.14): the cue ball is CUE, the other balls
     * are SOLID (the practice table has no stripe or 8-ball). */
    data class PracticeBall(
        val id: Int,
        var center: Vec2,
        val isCue: Boolean,
        val group: BallGroup = if (isCue) BallGroup.CUE else BallGroup.SOLID
    )

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
     * or selectable in DIRECT/BANK/KICK mode). */
    fun balls(): List<PracticeBall> = when (mode) {
        PracticeMode.DIRECT, PracticeMode.BANK, PracticeMode.KICK -> listOf(cue, target)
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
            PracticeMode.KICK -> {
                // Verified against the solver before the change: kick
                // off the right rail to the bottom-middle pocket, cut
                // 2.3 deg, contact (680.0, 241.5). Same balls as the
                // bank default — a different shot from the same layout.
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
        PracticeMode.BANK -> solveBestBank()
        PracticeMode.COMBO -> solveCombo()?.let { PracticeSolution.Combo(it) }
        PracticeMode.KISS -> solveKiss()?.let { PracticeSolution.Kiss(it) }
        PracticeMode.KICK -> solveBestKick()
    }

    /** KICK mode: the best kick. One-rail kicks rank first; a
     * two-rail kick only wins when its cut angle beats the best
     * one-rail cut by more than [TWO_RAIL_CUT_MARGIN_DEGREES]
     * (a two-rail kick must be clearly easier — see the class KDoc).
     * Either kind wins outright when the other does not exist. */
    private fun solveBestKick(): PracticeSolution? {
        val oneRail = solveKick()
        val twoRail = solveKick2()
        val oneCut = oneRail?.first?.direct?.cutAngleDegrees ?: Double.MAX_VALUE
        val twoCut = twoRail?.first?.direct?.cutAngleDegrees
            ?.plus(TWO_RAIL_CUT_MARGIN_DEGREES) ?: Double.MAX_VALUE
        return if (twoCut < oneCut) {
            twoRail?.let { PracticeSolution.Kick2(it.first, it.second) }
        } else {
            oneRail?.let { PracticeSolution.Kick(it.first, it.second) }
        }
    }

    /** BANK mode: the best bank. One-rail banks rank first; a
     * two-rail bank only wins when its cut angle beats the best
     * one-rail cut by more than [TWO_RAIL_CUT_MARGIN_DEGREES]
     * (a two-rail bank must be clearly easier — see the class KDoc).
     * Either kind wins outright when the other does not exist. */
    private fun solveBestBank(): PracticeSolution? {
        val oneRail = solveBank()
        val twoRail = solveBank2()
        val oneCut = oneRail?.first?.direct?.cutAngleDegrees ?: Double.MAX_VALUE
        val twoCut = twoRail?.first?.direct?.cutAngleDegrees
            ?.plus(TWO_RAIL_CUT_MARGIN_DEGREES) ?: Double.MAX_VALUE
        return if (twoCut < oneCut) {
            twoRail?.let { PracticeSolution.Bank2(it.first, it.second) }
        } else {
            oneRail?.let { PracticeSolution.Bank(it.first, it.second) }
        }
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

    /** Two-rail bank (v1.0.9): cue -> target -> rail 1 -> rail 2 ->
     * best pocket, ranked by the direct cut angle (easiest pot
     * first). Same three guards as [solveBank], applied to BOTH
     * contacts: a pocket on either rail is skipped for that pair,
     * each contact must lie inside its rail span, four ball radii
     * clear of the corners, and neither contact may sit on the
     * pocket. Returns the bank plus the two rail names in path
     * order, or null when no two-rail bank is possible. */
    fun solveBank2(): Pair<ShotSolver.TwoRailBankSolution, Pair<String, String>>? {
        if (cue.center.distanceTo(target.center) < ballRadiusPx * 2 + 0.5) return null
        val rails = railsOf()
        var best: ShotSolver.TwoRailBankSolution? = null
        var bestRails = "" to ""
        var bestCut = Double.MAX_VALUE
        for (pocket in table.pockets()) {
            for ((index1, rail1) in rails.withIndex()) {
                for ((index2, rail2) in rails.withIndex()) {
                    if (index1 == index2) continue
                    if (abs((pocket - rail1.point).dot(rail1.normal)) < 1.0) continue
                    if (abs((pocket - rail2.point).dot(rail2.normal)) < 1.0) continue
                    val bank = ShotSolver.solveTwoRailBank(
                        cue.center, target.center, pocket, rail1, rail2, ballRadiusPx
                    ) ?: continue
                    if (!bankContactIsValid(bank.railContacts[0], index1, pocket)) continue
                    if (!bankContactIsValid(bank.railContacts[1], index2, pocket)) continue
                    if (bank.direct.cutAngleDegrees < bestCut) {
                        best = bank
                        bestRails = railNames[index1] to railNames[index2]
                        bestCut = bank.direct.cutAngleDegrees
                    }
                }
            }
        }
        return best?.let { it to bestRails }
    }

    /** One-rail kick (v1.0.10): cue -> one rail -> target -> best
     * pocket, ranked by the direct cut angle (easiest pot first).
     * Same three guards as [solveBank]: a pocket whose ghost sits
     * on the rail is skipped for that rail (the solver returns null
     * there), the contact must lie inside its rail span, four ball
     * radii clear of the corners, and it must not sit on the pocket.
     * Returns the kick plus the rail it runs off, or null when no
     * kick is possible. */
    fun solveKick(): Pair<ShotSolver.KickSolution, String>? {
        if (cue.center.distanceTo(target.center) < ballRadiusPx * 2 + 0.5) return null
        val rails = railsOf()
        var best: ShotSolver.KickSolution? = null
        var bestRail = ""
        var bestCut = Double.MAX_VALUE
        for (pocket in table.pockets()) {
            for ((index, rail) in rails.withIndex()) {
                val kick = ShotSolver.solveKick(
                    cue.center, target.center, pocket, rail, ballRadiusPx
                ) ?: continue
                val contact = kick.railContact
                if (!bankContactIsValid(contact, index, pocket)) continue
                if (kick.direct.cutAngleDegrees < bestCut) {
                    best = kick
                    bestRail = railNames[index]
                    bestCut = kick.direct.cutAngleDegrees
                }
            }
        }
        return best?.let { it to bestRail }
    }

    /** Two-rail kick (v1.0.11): cue -> rail 1 -> rail 2 -> target
     * -> best pocket, ranked by the direct cut angle (easiest pot
     * first). Same three guards as [solveKick], applied to BOTH
     * contacts: a pocket whose ghost sits on either rail is skipped
     * by the solver (it returns null there), each contact must lie
     * inside its rail span, four ball radii clear of the corners,
     * and neither contact may sit on the pocket. Returns the kick
     * plus the two rail names in path order, or null when no
     * two-rail kick is possible. */
    fun solveKick2(): Pair<ShotSolver.TwoRailKickSolution, Pair<String, String>>? {
        if (cue.center.distanceTo(target.center) < ballRadiusPx * 2 + 0.5) return null
        val rails = railsOf()
        var best: ShotSolver.TwoRailKickSolution? = null
        var bestRails = "" to ""
        var bestCut = Double.MAX_VALUE
        for (pocket in table.pockets()) {
            for ((index1, rail1) in rails.withIndex()) {
                for ((index2, rail2) in rails.withIndex()) {
                    if (index1 == index2) continue
                    val kick = ShotSolver.solveTwoRailKick(
                        cue.center, target.center, pocket, rail1, rail2, ballRadiusPx
                    ) ?: continue
                    if (!bankContactIsValid(kick.railContacts[0], index1, pocket)) continue
                    if (!bankContactIsValid(kick.railContacts[1], index2, pocket)) continue
                    if (kick.direct.cutAngleDegrees < bestCut) {
                        best = kick
                        bestRails = railNames[index1] to railNames[index2]
                        bestCut = kick.direct.cutAngleDegrees
                    }
                }
            }
        }
        return best?.let { it to bestRails }
    }

    /** The v1.0.7 corner guard (same numbers as the live pipeline's
     * CvPipeline.bankContactIsValid): the contact must lie inside its
     * rail span, four ball radii clear of the corners, and not on the
     * pocket. [railIndex] follows railsOf() order: 0 top, 1 bottom,
     * 2 left, 3 right. */
    private fun bankContactIsValid(contact: Vec2, railIndex: Int, pocket: Vec2): Boolean {
        val along = if (railIndex < 2) contact.x else contact.y
        val lo = if (railIndex < 2) table.topLeft.x + 4 * ballRadiusPx
            else table.topLeft.y + 4 * ballRadiusPx
        val hi = if (railIndex < 2) table.topRight.x - 4 * ballRadiusPx
            else table.bottomLeft.y - 4 * ballRadiusPx
        if (along < lo || along > hi) return false
        return contact.distanceTo(pocket) >= 2 * ballRadiusPx
    }
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
