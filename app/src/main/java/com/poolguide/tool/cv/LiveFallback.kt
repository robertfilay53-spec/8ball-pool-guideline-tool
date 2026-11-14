package com.poolguide.tool.cv

import com.poolguide.tool.geometry.PathBlock
import com.poolguide.tool.geometry.Rail
import com.poolguide.tool.geometry.ShotSolver
import com.poolguide.tool.geometry.Vec2
import kotlin.math.abs

/**
 * The live pipeline's fallback chain (v1.0.13, pure geometry — no
 * OpenCV, no Android — so it is fully unit-testable on the JVM).
 *
 * [CvPipeline.solveFor] solves direct shots first (every pocket,
 * best cut angle wins). When no direct shot exists it calls
 * [solveFallback], which runs the chain in this order, best first:
 * direct > one-rail bank > one-rail kick > two-rail bank >
 * two-rail kick > combo > kiss.
 *
 * A bank and a kick are both one-rail shots ranked by the same
 * number — the direct cut angle of the underlying solve, the
 * codebase's established difficulty proxy — so the smaller cut
 * wins, and a tie goes to the bank, the established fallback. A
 * kick never displaces a direct shot. Since v1.0.22 each
 * one-rail family is a ranked list ([ShotSolver.allBanks],
 * [ShotSolver.allKicks]) and the shown shot is the first CLEAR
 * one in cut order, so the bank-vs-kick comparison is between
 * the two first-clear shots: the easiest makeable one-rail shot
 * wins, and when every bank or kick is blocked the best stays
 * and is flagged, as before. The two-rail fallback
 * applies the v1.0.7 corner guards to BOTH contacts (the one-rail
 * bank fallback does not apply them, unchanged from v1.0.3); the
 * kick fallback applies them to its one contact, for the same
 * reason — a contact inside a pocket's jaws or on the pocket is
 * not a clean rail shot, and a fallback shot is already the least
 * reliable shot the tool shows. The two-rail kick fallback
 * (v1.0.11) applies the same guards to BOTH of its contacts, for
 * the same reason — and it is only tried after the two-rail bank
 * search comes back empty, because a two-rail kick compounds
 * real-table error twice (two cushion contacts, each fitted to
 * synthetic data, real-table behaviour unmeasured).
 *
 * The combo fallback (v1.0.13) is only tried after the two-rail
 * kick search comes back empty: the picked target is the FIRST
 * ball (the ball the game's guideline points at — the ball the
 * cue strikes), and every other ball is a candidate second ball
 * (the ball that pots), ranked by the second shot's cut angle.
 * A combo never displaces any other shot: two balls and two cuts
 * compound real-table error more than any single-ball shot. The
 * kiss fallback is only tried after the combo fails too: a kiss
 * is the last resort of all, and it never displaces a combo.
 */
sealed interface FallbackShot {
    data class Bank(val solution: ShotSolver.BankSolution) : FallbackShot
    data class Kick(val solution: ShotSolver.KickSolution) : FallbackShot
    data class Bank2(val solution: ShotSolver.TwoRailBankSolution) : FallbackShot
    data class Kick2(val solution: ShotSolver.TwoRailKickSolution) : FallbackShot
    data class Combo(val solution: ShotSolver.ComboSolution) : FallbackShot
    data class Kiss(val solution: ShotSolver.KissSolution) : FallbackShot
    object None : FallbackShot
}

/**
 * Run the fallback chain for a frame with no direct shot. [cue] is
 * the cue ball's centre, [target] the picked target ball's centre
 * (the ball the guideline points at — for a combo or kiss, the
 * FIRST ball, the one the cue strikes), [others] every other ball
 * on the table (candidate second balls for a combo or kiss),
 * [pockets] the table's pockets, [table] the table rect, and
 * [ballRadiusPx] the ball radius. Returns the best fallback shot,
 * or [FallbackShot.None] when nothing solves.
 */
fun solveFallback(
    cue: Vec2,
    target: Vec2,
    others: List<Vec2>,
    pockets: List<Vec2>,
    table: TableRect,
    ballRadiusPx: Double
): FallbackShot {
    val rails = railsOf(table)

    // No direct shot: every one-rail bank and every one-rail
    // kick, each ranked by the direct cut angle (easiest pot
    // first), across all pockets and all four rails. The kick
    // contact must pass the corner guards (same numbers as
    // PracticeState): inside the rail span, four ball radii clear
    // of the corners, and not on the pocket. The shown shot of
    // each family is its first CLEAR one in cut order (v1.0.22
    // re-ranking): a blocked easiest bank or kick no longer hides
    // a makeable one, and when every bank or kick is blocked the
    // best stays and is flagged, as before. [others] is the
    // blocker list here: every ball except the cue and the target,
    // which travel the path.
    val allBanks = ShotSolver.allBanks(cue, target, pockets, rails, ballRadiusPx)
    val shownBank = PathBlock.firstClearBank(allBanks, others, ballRadiusPx)
        ?: allBanks.firstOrNull()
    val allKicks = ShotSolver.allKicks(
        cue, target, pockets, rails, ballRadiusPx
    ) { contact, index, pocket ->
        bankContactIsValid(contact, index, table, pocket, ballRadiusPx)
    }
    val shownKick = PathBlock.firstClearKick(allKicks, others, ballRadiusPx)
        ?: allKicks.firstOrNull()
    // Smaller cut wins; a tie goes to the bank, the established
    // fallback (the cut angles are equal, so either shot is as
    // makeable as the other). The comparison is between the two
    // first-clear shots, not the two best: the easiest MAKEABLE
    // one-rail shot wins, so a blocked best bank can no longer
    // beat a clear kick, and a blocked best kick can no longer
    // beat a clear bank. Neither solving falls through to
    // the two-rail search below.
    when (ShotSolver.pickOneRailFallback(
        shownBank?.direct?.cutAngleDegrees,
        shownKick?.direct?.cutAngleDegrees
    )) {
        ShotSolver.OneRailFallback.KICK -> return FallbackShot.Kick(shownKick!!)
        ShotSolver.OneRailFallback.BANK -> return FallbackShot.Bank(shownBank!!)
        ShotSolver.OneRailFallback.NONE -> Unit
    }

    // No one-rail bank or kick either: try two-rail banks, ranked by the
    // direct cut angle (easiest pot first). Both contacts must
    // pass the corner guards (same numbers as PracticeState):
    // inside the rail span, four ball radii clear of the corners,
    // and not on the pocket.
    var bestTwoRail: ShotSolver.TwoRailBankSolution? = null
    var bestTwoRailCut = Double.MAX_VALUE
    for (pocket in pockets) {
        for ((index1, rail1) in rails.withIndex()) {
            for ((index2, rail2) in rails.withIndex()) {
                if (index1 == index2) continue
                if (abs((pocket - rail1.point).dot(rail1.normal)) < 1.0) continue
                if (abs((pocket - rail2.point).dot(rail2.normal)) < 1.0) continue
                val bank2 = ShotSolver.solveTwoRailBank(
                    cue, target, pocket, rail1, rail2, ballRadiusPx
                ) ?: continue
                if (!bankContactIsValid(bank2.railContacts[0], index1, table, pocket, ballRadiusPx)) continue
                if (!bankContactIsValid(bank2.railContacts[1], index2, table, pocket, ballRadiusPx)) continue
                if (bank2.direct.cutAngleDegrees < bestTwoRailCut) {
                    bestTwoRail = bank2
                    bestTwoRailCut = bank2.direct.cutAngleDegrees
                }
            }
        }
    }
    if (bestTwoRail != null) return FallbackShot.Bank2(bestTwoRail)

    // No two-rail bank either: try two-rail kicks, ranked by the
    // direct cut angle (easiest pot first). Both contacts must
    // pass the corner guards (same numbers as PracticeState):
    // inside the rail span, four ball radii clear of the corners,
    // and not on the pocket. This is the last resort before the
    // combo/kiss search: a two-rail kick is only shown when it is
    // the only single-ball shot left.
    var bestTwoRailKick: ShotSolver.TwoRailKickSolution? = null
    var bestTwoRailKickCut = Double.MAX_VALUE
    for (pocket in pockets) {
        for ((index1, rail1) in rails.withIndex()) {
            for ((index2, rail2) in rails.withIndex()) {
                if (index1 == index2) continue
                // No pocket-on-rail skip here (unlike the bank
                // searches): a kick mirrors the ghost, never the
                // pocket, so a pocket on a rail is not
                // degenerate — the solver itself returns null
                // when the ghost sits on either rail.
                val kick2 = ShotSolver.solveTwoRailKick(
                    cue, target, pocket, rail1, rail2, ballRadiusPx
                ) ?: continue
                if (!bankContactIsValid(kick2.railContacts[0], index1, table, pocket, ballRadiusPx)) continue
                if (!bankContactIsValid(kick2.railContacts[1], index2, table, pocket, ballRadiusPx)) continue
                if (kick2.direct.cutAngleDegrees < bestTwoRailKickCut) {
                    bestTwoRailKick = kick2
                    bestTwoRailKickCut = kick2.direct.cutAngleDegrees
                }
            }
        }
    }
    if (bestTwoRailKick != null) return FallbackShot.Kick2(bestTwoRailKick)

    // No two-rail kick either: try combos, ranked by the second
    // shot's cut angle (easiest pot first). The picked target is
    // the first ball; every other ball is a candidate second ball.
    // A combo is only shown when no single-ball shot solves — two
    // balls and two cuts compound real-table error more than any
    // one-ball shot.
    val combo = ShotSolver.bestCombo(cue, target, others, pockets, ballRadiusPx)
    if (combo != null) return FallbackShot.Combo(combo)

    // No combo either: try kisses, ranked by the second shot's cut
    // angle (easiest pot first). A kiss is the last resort of all:
    // it is only shown when it is the only shot left, and it never
    // displaces a combo.
    val kiss = ShotSolver.bestKiss(cue, target, others, pockets, ballRadiusPx)
    if (kiss != null) return FallbackShot.Kiss(kiss)

    return FallbackShot.None
}

/** The v1.0.7 corner guard, shared with PracticeState's bank
 * solve (same numbers, same comment): the contact must lie inside
 * its rail span, four ball radii clear of the corners, and not on
 * the pocket. [railIndex] follows railsOf() order: 0 top, 1 bottom,
 * 2 left, 3 right. */
fun bankContactIsValid(
    contact: Vec2,
    railIndex: Int,
    tableRect: TableRect,
    pocket: Vec2,
    ballRadiusPx: Double
): Boolean {
    val along = if (railIndex < 2) contact.x else contact.y
    val lo = if (railIndex < 2) tableRect.topLeft.x + 4 * ballRadiusPx
        else tableRect.topLeft.y + 4 * ballRadiusPx
    val hi = if (railIndex < 2) tableRect.topRight.x - 4 * ballRadiusPx
        else tableRect.bottomLeft.y - 4 * ballRadiusPx
    if (along < lo || along > hi) return false
    return contact.distanceTo(pocket) >= 2 * ballRadiusPx
}

/** The four rails of the table rect, normals pointing into the table. */
fun railsOf(t: TableRect): List<Rail> = listOf(
    Rail(t.topLeft, Vec2(0.0, 1.0)),   // top rail, normal points down
    Rail(t.bottomLeft, Vec2(0.0, -1.0)), // bottom rail, normal points up
    Rail(t.topLeft, Vec2(1.0, 0.0)),   // left rail, normal points right
    Rail(t.topRight, Vec2(-1.0, 0.0))  // right rail, normal points left
)
