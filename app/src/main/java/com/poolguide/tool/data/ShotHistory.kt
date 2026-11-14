package com.poolguide.tool.data

import com.poolguide.tool.cv.DetectionResult
import com.poolguide.tool.cv.TableRect
import com.poolguide.tool.geometry.CutAngleLabel
import com.poolguide.tool.geometry.PocketDistanceLabel
import com.poolguide.tool.geometry.ShotSolver
import com.poolguide.tool.geometry.Vec2
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Shot-history strip (v1.0.17).
 *
 * The live overlay solves a shot every frame but shows only the
 * current one: once the player moves the cue, what the pipeline
 * solved a moment ago is gone, and the only record is the
 * diagnostics export after the fact. This object keeps the last
 * [MAX_SHOTS] solved shots the overlay actually drew, newest last,
 * so MainActivity can show them under the diagnostic preview and a
 * tester can review what the pipeline saw without re-aiming.
 *
 * Recording is deliberately conservative: [recordIfChanged] stores a
 * new entry only when the solved shot changes (different family,
 * pocket, or cut angle / distance beyond one decimal), so a steady
 * aim does not flood the strip with one entry per frame — at 15 FPS
 * an unchanged shot records once. The record point is
 * LineOverlayManager.update, after buildDrawLines returns lines:
 * only shots the player actually saw are recorded (table locked,
 * not hidden, not a black capture, not EDIT mode — a frozen shot
 * is not a live solve).
 *
 * The history is in-memory only and dies with the process. The
 * diagnostics export appends it (see [DiagnosticsLog.buildReport]),
 * so it survives in the file the user chooses to share.
 *
 * Pure Kotlin + JDK only (no Android dependency), so it is fully
 * unit-testable on the JVM.
 */
object ShotHistory {

    /** How many solved shots the strip keeps. */
    const val MAX_SHOTS = 8

    /** One solved shot, as the player saw it. [timeMs] is wall-clock
     * milliseconds; [format] renders it as HH:mm:ss. [blocked] is
     * true when another ball sat on the shown shot's path (v1.0.19):
     * the shot was drawn, flagged "Path blocked", and the player
     * was warned — the strip records that, not a clean make. */
    data class ShotEntry(
        val family: String,
        val pocketName: String,
        val cutAngleDegrees: Double,
        val distanceBd: Double,
        val timeMs: Long,
        val blocked: Boolean = false
    )

    private val entries = ArrayDeque<ShotEntry>()

    /** Invoked (on the overlay's main thread) whenever a new entry
     * is recorded, so MainActivity can refresh the strip. Null by
     * default; MainActivity sets and clears it with its lifecycle. */
    @Volatile
    var listener: (() -> Unit)? = null

    /** The solved shot in [result], or null when no shot solved.
     * Mirrors the branch priority in LineOverlayManager.buildDrawLines
     * (direct > bank > kick > two-rail bank > two-rail kick > combo >
     * kiss) and the per-family path rules in [PocketDistanceLabel]:
     * the cut angle is the potting leg's cut, and the distance is
     * the total travelled path in ball diameters. The pocket is
     * named by its position on the table (TL/TR/BR/BL/TM/BM); with
     * no table and no pockets it is "?". [nowMs] is injectable so
     * tests can pin the timestamp. */
    fun entryFor(result: DetectionResult, nowMs: Long = System.currentTimeMillis()): ShotEntry? {
        val ballRadiusPx = result.cueBall?.radiusPx ?: 9.0
        val family: String
        val solution: ShotSolver.ShotSolution
        val pocket: Vec2
        val segments: List<com.poolguide.tool.geometry.Segment>
        when {
            result.shot != null -> {
                family = "DIRECT"
                solution = result.shot
                pocket = result.shot.targetLine.end
                segments = PocketDistanceLabel.pathSegments(result.shot)
            }
            result.bank != null -> {
                family = "BANK"
                solution = result.bank.direct
                pocket = result.bank.bankPath.last().end
                segments = PocketDistanceLabel.pathSegments(result.bank)
            }
            result.kick != null -> {
                family = "KICK"
                solution = result.kick.direct
                pocket = result.kick.direct.targetLine.end
                segments = PocketDistanceLabel.pathSegments(result.kick)
            }
            result.bank2 != null -> {
                family = "BANK2"
                solution = result.bank2.direct
                pocket = result.bank2.bankPath.last().end
                segments = PocketDistanceLabel.pathSegments(result.bank2)
            }
            result.kick2 != null -> {
                family = "KICK2"
                solution = result.kick2.direct
                pocket = result.kick2.direct.targetLine.end
                segments = PocketDistanceLabel.pathSegments(result.kick2)
            }
            result.combo != null -> {
                family = "COMBO"
                solution = result.combo.secondShot
                pocket = result.combo.secondShot.targetLine.end
                segments = PocketDistanceLabel.pathSegments(result.combo)
            }
            result.kiss != null -> {
                family = "KISS"
                solution = result.kiss.secondShot
                pocket = result.kiss.secondShot.targetLine.end
                segments = PocketDistanceLabel.pathSegments(result.kiss)
            }
            else -> return null
        }
        return ShotEntry(
            family = family,
            pocketName = pocketName(pocket, result.table, result.pockets),
            cutAngleDegrees = solution.cutAngleDegrees,
            distanceBd = PocketDistanceLabel.totalBallDiameters(segments, ballRadiusPx),
            timeMs = nowMs,
            // v1.0.19: the blocked flag rides along — a blocked shot
            // is a different entry from the same clean shot, so a
            // ball rolling onto the path opens a new strip line.
            blocked = result.blockedBy != null
        )
    }

    /** Name a pocket by its position on the table: TL/TR/BR/BL for
     * the corners, TM/BM for the side pockets. With a table rect the
     * names come from the rect's corners and side midpoints (the
     * pockets sit inset from those points by a uniform amount, so
     * the nearest corner/midpoint is still the right name). Without
     * a table, the [pockets] list order is used — TableGeometry
     * builds it TL, TR, BR, BL, TM, BM. With neither, "?". */
    fun pocketName(pocket: Vec2, table: TableRect?, pockets: List<Vec2>): String {
        val named: List<Pair<String, Vec2>> = if (table != null) {
            val midTop = Vec2(
                (table.topLeft.x + table.topRight.x) / 2.0,
                (table.topLeft.y + table.topRight.y) / 2.0
            )
            val midBottom = Vec2(
                (table.bottomLeft.x + table.bottomRight.x) / 2.0,
                (table.bottomLeft.y + table.bottomRight.y) / 2.0
            )
            listOf(
                "TL" to table.topLeft,
                "TR" to table.topRight,
                "BR" to table.bottomRight,
                "BL" to table.bottomLeft,
                "TM" to midTop,
                "BM" to midBottom
            )
        } else if (pockets.size >= 6) {
            listOf(
                "TL" to pockets[0],
                "TR" to pockets[1],
                "BR" to pockets[2],
                "BL" to pockets[3],
                "TM" to pockets[4],
                "BM" to pockets[5]
            )
        } else {
            return "?"
        }
        return named.minByOrNull { (_, p) -> p.distanceTo(pocket) }!!.first
    }

    /** Record [entry] unless it is the same shot as the last entry
     * (same family and pocket, cut angle and distance equal to one
     * decimal — sub-degree detection noise must not open a new
     * entry every frame). Returns true when an entry was added.
     * Oldest entries drop once the strip holds [MAX_SHOTS]. */
    @Synchronized
    fun recordIfChanged(entry: ShotEntry): Boolean {
        val last = entries.lastOrNull()
        if (last != null && last.family == entry.family &&
            last.pocketName == entry.pocketName &&
            last.blocked == entry.blocked &&
            round1(last.cutAngleDegrees) == round1(entry.cutAngleDegrees) &&
            round1(last.distanceBd) == round1(entry.distanceBd)
        ) {
            return false
        }
        if (entries.size >= MAX_SHOTS) entries.removeFirst()
        entries.addLast(entry)
        listener?.invoke()
        return true
    }

    @Synchronized
    fun snapshot(): List<ShotEntry> = entries.toList()

    @Synchronized
    fun clear() {
        entries.clear()
    }

    /** One strip line, Locale.US on purpose like [CutAngleLabel]
     * and [PocketDistanceLabel]: "14:32:05 DIRECT → TL, cut 24.6°,
     * 29.6 bd" — never "24,6°" on a comma-decimal device. A blocked
     * shot (v1.0.19) appends " — blocked". */
    fun format(entry: ShotEntry): String {
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(entry.timeMs))
        val blockedSuffix = if (entry.blocked) " — blocked" else ""
        return "$time ${entry.family} → ${entry.pocketName}, " +
            "cut ${CutAngleLabel.format(entry.cutAngleDegrees)}, " +
            PocketDistanceLabel.format(entry.distanceBd) + blockedSuffix
    }

    private fun round1(x: Double): Double = (x * 10.0).roundToInt() / 10.0
}
