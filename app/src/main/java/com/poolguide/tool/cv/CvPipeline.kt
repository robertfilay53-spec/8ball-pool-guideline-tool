package com.poolguide.tool.cv

import com.poolguide.tool.data.GameProfile
import com.poolguide.tool.geometry.PathBlock
import com.poolguide.tool.geometry.Segment
import com.poolguide.tool.geometry.ShotSolver
import com.poolguide.tool.geometry.Vec2
import org.opencv.android.OpenCVLoader
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/** Loads OpenCV once. Tries the AAR's initLocal() first, then a direct
 * System.loadLibrary("opencv_java4") fallback. */
object OpenCvInit {
    @Volatile
    private var loaded = false

    @Synchronized
    fun ensureLoaded(): Boolean {
        if (loaded) return true
        loaded = try {
            OpenCVLoader.initLocal()
        } catch (e: Throwable) {
            false
        } || try {
            System.loadLibrary("opencv_java4")
            true
        } catch (e: Throwable) {
            false
        }
        return loaded
    }
}

/**
 * The detection pipeline. One frame in, one [DetectionResult] out.
 *
 * Order per frame: downscale to the working resolution -> BGR/HSV/grey ->
 * black-frame check -> table (every 30th frame or when lost) -> balls
 * (HSV contours every frame, HoughCircles every 3rd) -> guideline (every
 * 2nd frame, table ROI only) -> target + pocket pick -> ghost-ball solve
 * (+ one-rail bank fallback). All Mats are reused; frames arriving while
 * a frame is being processed are dropped by the caller.
 */
class CvPipeline(
    /** Screen size in pixels, for the working->screen scale factors.
     * Updated via [setScreenSize] when the display rotates (the game plays
     * in landscape while the launcher is portrait) or in demo mode. */
    screenWidth: Int,
    screenHeight: Int
) {

    private val tableDetector = TableDetector()
    private val ballDetector = BallDetector()
    private val guidelineDetector = GuidelineDetector()
    private val tableLock = TableLock()

    private val small = Mat()
    private val bgr = Mat()
    private val hsv = Mat()
    private val gray = Mat()
    private val grayRoi = Mat()

    private var workingWidth = CvConfig.WORK_WIDTH
    private var workingHeight = 405
    private var frameIndex = 0L
    private var table: TableRect? = null
    /** True when [table] is the ratio fallback, not a detection. */
    private var tableIsFallback = false
    private var framesSinceTableCheck = Int.MAX_VALUE

    /** Manual table corners from calibration (normalised 0-1), or null. */
    var tableOverrideNormalized: List<Vec2>? = null

    /** Per-game-version calibration profile (aimcall lead): pocket
     * fractions + ball radius for the detected game version. Set by the
     * service from the CalibrationStore; defaults match the old
     * hardcoded fractions. */
    var gameProfile: GameProfile = GameProfile.DEFAULT

    private var screenWidth = screenWidth
    private var screenHeight = screenHeight

    /** Working -> screen scale factors (set after the first frame). */
    val scaleToScreenX: Double get() = screenWidth.toDouble() / workingWidth
    val scaleToScreenY: Double get() = screenHeight.toDouble() / workingHeight

    /** Called when the physical display size changes (rotation) or in demo
     * mode, where the "screen" is the demo frame itself. */
    fun setScreenSize(width: Int, height: Int) {
        screenWidth = width
        screenHeight = height
    }

    fun reset() {
        table = null
        tableIsFallback = false
        ballDetector.reset()
        guidelineDetector.reset()
        tableLock.reset()
        framesSinceTableCheck = Int.MAX_VALUE
    }

    /** Process one RGBA frame [Mat] (capture resolution). */
    fun process(rgbaFrame: Mat): DetectionResult {
        frameIndex++
        // Working resolution: 720 wide, height follows the capture aspect.
        workingHeight = (CvConfig.WORK_WIDTH.toDouble() * rgbaFrame.rows() / rgbaFrame.cols())
            .toInt().coerceAtLeast(200)
        Imgproc.resize(rgbaFrame, small, Size(CvConfig.WORK_WIDTH.toDouble(), workingHeight.toDouble()))
        Imgproc.cvtColor(small, bgr, Imgproc.COLOR_RGBA2BGR)
        Imgproc.cvtColor(bgr, hsv, Imgproc.COLOR_BGR2HSV)
        Imgproc.cvtColor(bgr, gray, Imgproc.COLOR_BGR2GRAY)

        val blackResult = blackCheck()
        if (blackResult != null) return blackResult

        // Table: calibration override wins; else auto-detect on schedule.
        // Ratio fallback (v1.0.4, Bloro lead; gated in v1.0.5): when
        // detection finds no table, the ratio guess may ONLY continue a
        // lock a real detection already earned, inside the
        // FALLBACK_CONTINUATION_FRAMES window. On a fresh screen with
        // no real lock it is never used — the pipeline reports no table
        // and the overlay draws nothing. A guessed rect can never lock
        // the table: the lock counts real detections only.
        val detected: TableRect?
        val tableFromFallback: Boolean
        // Local copy: the property is a mutable var, so a null check in
        // the when condition does not smart-cast it inside the branch.
        val override = tableOverrideNormalized
        when {
            override != null -> {
                detected = normalizedToTable(override)
                tableFromFallback = false
            }
            table == null || framesSinceTableCheck >= CvConfig.TABLE_REDETECT_EVERY_N_FRAMES -> {
                framesSinceTableCheck = 0
                val found = tableDetector.detect(hsv, gameProfile)
                if (found != null) {
                    detected = found
                    tableFromFallback = false
                } else if (tableLock.canContinueFallback) {
                    detected = RatioTableGeometry.fallbackTable(workingWidth, workingHeight)
                    tableFromFallback = true
                } else {
                    detected = null
                    tableFromFallback = false
                }
            }
            else -> {
                framesSinceTableCheck++
                detected = table
                tableFromFallback = tableIsFallback
            }
        }
        table = detected
        tableIsFallback = tableFromFallback

        val runHough = frameIndex % CvConfig.HOUGH_CIRCLES_EVERY_N_FRAMES == 0L
        val pocketRadiusPx = table?.let { it.width * gameProfile.pocketRadiusFraction } ?: 12.0
        // v1.0.3: no-table gate — with no table the pockets have no
        // exclusion list and the 6 pockets come back as balls (7 false
        // positives on the old harness tables). Nothing is a ball until
        // a table exists.
        val balls = if (table == null) {
            emptyList()
        } else {
            ballDetector.detect(
                hsv, gray, table, table?.pockets(gameProfile) ?: emptyList(), runHough, frameIndex,
                tableDetector.feltHsvMean, pocketRadiusPx
            )
        }
        // v1.0.20: cue by group, not by colour — see CuePicker.
        val cue = CuePicker.pickCue(balls)

        // Guideline: detect every 2nd frame inside the table ROI; between
        // detection frames the last tracked segments are reused.
        val guideline: List<GuidelineSegment> =
            if (frameIndex % CvConfig.GUIDELINE_EVERY_N_FRAMES == 0L && table != null) {
                detectGuidelineInTableRoi().also { lastGuideline = it }
            } else {
                lastGuideline
            }

        val pockets = table?.pockets(gameProfile) ?: emptyList()
        val outcome = solveFor(cue, balls, pockets, guideline)

        // Table-lock gate (v1.0.3): the overlay draws nothing until the
        // table rect has been stable for 5 s. A calibration override
        // counts as locked immediately — the user placed the corners.
        // Fallback frames never count toward the lock (v1.0.5): they
        // only ride out a real lock inside the continuation window.
        val locked = if (tableOverrideNormalized != null && table != null) {
            true
        } else {
            tableLock.update(table, tableFromFallback)
        }

        return DetectionResult(
            frameIndex = frameIndex,
            workingWidth = workingWidth,
            workingHeight = workingHeight,
            scaleToScreenX = scaleToScreenX,
            scaleToScreenY = scaleToScreenY,
            isCaptureBlack = false,
            table = table,
            pockets = pockets,
            balls = balls,
            cueBall = cue,
            guideline = guideline,
            shot = outcome.shot,
            bank = outcome.bank,
            bank2 = outcome.bank2,
            kick = outcome.kick,
            kick2 = outcome.kick2,
            combo = outcome.combo,
            kiss = outcome.kiss,
            allShots = outcome.allShots,
            blockedBy = outcome.blockedBy,
            tableLocked = locked,
            tableFromFallback = tableFromFallback,
            // The ring on the control bubble shows this: 1.0 once
            // locked (an override counts as locked immediately), the
            // stability fraction while the table is settling.
            tableLockProgress = if (locked) 1f else tableLock.lockProgress,
            status = if (tableFromFallback) outcome.status + " (table: ratio fallback — continuing last lock)"
            else outcome.status
        )
    }

    private var lastGuideline: List<GuidelineSegment> = emptyList()

    private fun blackCheck(): DetectionResult? {
        val mean = Core.mean(gray).`val`[0]
        return if (mean < CvConfig.BLACK_FRAME_MEAN) {
            DetectionResult.blackFrame(
                frameIndex, workingWidth, workingHeight,
                scaleToScreenX, scaleToScreenY
            )
        } else null
    }

    private fun normalizedToTable(norm: List<Vec2>): TableRect? {
        if (norm.size != 4) return null
        val pts = norm.map { Vec2(it.x * workingWidth, it.y * workingHeight) }
        return TableDetector().orderCorners(pts)
    }

    private fun detectGuidelineInTableRoi(): List<GuidelineSegment> {
        val t = table ?: return emptyList()
        val xs = t.corners.map { it.x }
        val ys = t.corners.map { it.y }
        val x0 = xs.min().toInt().coerceIn(0, workingWidth - 1)
        val y0 = ys.min().toInt().coerceIn(0, workingHeight - 1)
        val x1 = xs.max().toInt().coerceIn(1, workingWidth)
        val y1 = ys.max().toInt().coerceIn(1, workingHeight)
        if (x1 - x0 < 50 || y1 - y0 < 50) return emptyList()
        val roi = gray.submat(Rect(x0, y0, x1 - x0, y1 - y0))
        val found = guidelineDetector.detect(roi)
        roi.release()
        // Translate ROI-relative coords to working coords.
        return found.map {
            val s = it.segment
            GuidelineSegment(
                Segment(
                    Vec2(s.start.x + x0, s.start.y + y0),
                    Vec2(s.end.x + x0, s.end.y + y0)
                ),
                it.alpha
            )
        }
    }

    /** One frame's solve: best direct shot, every candidate shot, and
     * the fallback chain (one-rail bank, one-rail kick, two-rail
     * bank, two-rail kick, combo, kiss — see [solveFallback]) plus
     * the status line. */
    private data class SolveOutcome(
        val shot: ShotSolver.ShotSolution?,
        val bank: ShotSolver.BankSolution?,
        val allShots: List<ShotSolver.ShotSolution>,
        val status: String,
        /** Best two-rail bank (v1.0.9), set only when no direct shot
         * and no one-rail bank solves: the last-resort best shot. */
        val bank2: ShotSolver.TwoRailBankSolution? = null,
        /** Best one-rail kick (v1.0.10), set only when no direct
         * shot solves and its cut beats the best one-rail bank's. */
        val kick: ShotSolver.KickSolution? = null,
        /** Best two-rail kick (v1.0.11), set only when no direct
         * shot, no one-rail bank, no one-rail kick, and no two-rail
         * bank solves: the last resort. */
        val kick2: ShotSolver.TwoRailKickSolution? = null,
        /** Best combo (v1.0.13), set only when no direct shot, no
         * one-rail bank, no one-rail kick, no two-rail bank, and no
         * two-rail kick solves: the last resort before a kiss. A
         * combo never displaces any other shot in the live overlay. */
        val combo: ShotSolver.ComboSolution? = null,
        /** Best kiss (v1.0.13), set only when no combo solves
         * either: the last resort of all. A kiss never displaces
         * a combo. */
        val kiss: ShotSolver.KissSolution? = null,
        /** Blocked-path flag (v1.0.19, combo/kiss since v1.0.24):
         * the centre of the ball sitting on the shown shot's
         * path, or null when the path is clear. The overlay draws
         * a "Path blocked" label for it and the status line says
         * so. For a combo or kiss the flag covers the whole
         * potting chain (see [PathBlock.blockedBy]); the second
         * ball is never a blocker of its own shot. */
        val blockedBy: Vec2? = null
    )

    /**
     * Pick the target ball (nearest ball along the guideline ray from the cue
     * ball; nearest ball overall when no guideline is visible) and the first
     * clear pocket in cut order, then solve. Falls back through the
     * chain in [solveFallback] when no direct shot is possible: one-rail
     * bank or kick, two-rail bank, two-rail kick, combo, then kiss.
     *
     * Ranking (best shot stays honest): direct first, then the better of
     * the one-rail bank and the one-rail kick, then two-rail banks as a
     * last resort, then two-rail kicks as the last resort after that,
     * then combos, then kisses as the last resort of all. Within the
     * direct family the shown shot is the first CLEAR pocket in cut
     * order (v1.0.21 re-ranking): a blocked easiest pocket never
     * hides a makeable one, and when every direct shot is blocked
     * the best stays and is flagged. A bank and
     * a kick are both one-rail shots ranked by the same number — the
     * direct cut angle of the underlying solve, the codebase's
     * established difficulty proxy — so the smaller cut wins, and a
     * tie goes to the bank, the established fallback. A kick never
     * displaces a direct shot. The one-rail families are re-ranked
     * the same way (v1.0.22): the bank-vs-kick comparison is
     * between the two first-clear shots, so the easiest makeable
     * one-rail shot wins, and when every bank or kick is blocked
     * the best stays and is flagged.
     */
    private fun solveFor(
        cue: Ball?,
        balls: List<Ball>,
        pockets: List<Vec2>,
        guideline: List<GuidelineSegment>
    ): SolveOutcome {
        // Table first: with the v1.0.3 no-table gate no balls are
        // reported when there is no table, so a missing table must say
        // so — checking the cue first would report "Cue ball not found"
        // for a frame with no table at all.
        if (pockets.isEmpty()) return SolveOutcome(null, null, emptyList(), "Table not found — guides hidden")
        if (cue == null) return SolveOutcome(null, null, emptyList(), "Cue ball not found")
        val target = pickTarget(cue, balls, guideline)
            ?: return SolveOutcome(null, null, emptyList(), "No target ball in line")
        val radius = (cue.radiusPx + target.radiusPx) / 2.0
        // Every other ball is a potential blocker (v1.0.19): the cue
        // and the target travel the path, so they are never blockers
        // of their own shot. The same list feeds the fallback chain
        // as its candidate second balls.
        val others = balls.filter { it.id != cue.id && it.id != target.id }
            .map { it.center }
        // Status suffix when the shown shot's path is blocked.
        fun blockedSuffix(blocked: Vec2?): String =
            if (blocked != null) " — path blocked by another ball" else ""

        // Every direct shot, best first: smallest cut angle, first-listed
        // pocket wins ties within the tie margin (a symmetric layout must
        // not flip on sub-degree detection noise). The shown shot is the
        // first CLEAR one in that order (v1.0.21 re-ranking): a blocked
        // easiest pocket never hides a makeable one. When every direct
        // shot is blocked the best stays and is flagged, as before.
        // The result list is reordered so the shown shot is first (the
        // overlay draws allShots.drop(1) faint, and [shot] is always
        // allShots.firstOrNull()).
        val all = ShotSolver.allShots(cue.center, target.center, pockets, radius)
        val shown = PathBlock.firstClearShot(all, others, radius) ?: all.firstOrNull()
        val ranked = if (shown != null) listOf(shown) + all.filter { it !== shown } else all
        if (shown != null) {
            val blocked = PathBlock.blockedBy(shown, others, radius)
            val warn = if (shown.isThinCut) " — thin cut, very low margin" else ""
            return SolveOutcome(
                shown, null, ranked, "Shot to pocket$warn${blockedSuffix(blocked)}",
                blockedBy = blocked
            )
        }

        // No direct shot: run the fallback chain (one-rail bank or
        // kick, two-rail bank, two-rail kick, combo, kiss — best
        // first, see solveFallback in LiveFallback.kt). The picked
        // target is the first ball for a combo or kiss; every other
        // ball is a candidate second ball (the ball that pots).
        val tableRect = table ?: return SolveOutcome(null, null, emptyList(), "No shot found")
        return when (val fallback = solveFallback(
            cue.center, target.center, others, pockets, tableRect, radius
        )) {
            is FallbackShot.Bank -> {
                val blocked = PathBlock.blockedBy(fallback.solution, others, radius)
                SolveOutcome(
                    null, fallback.solution, emptyList(),
                    "Bank shot (one rail) to pocket${blockedSuffix(blocked)}",
                    blockedBy = blocked
                )
            }
            is FallbackShot.Kick -> {
                val blocked = PathBlock.blockedBy(fallback.solution, others, radius)
                SolveOutcome(
                    null, null, emptyList(),
                    "Kick shot (one rail) to pocket${blockedSuffix(blocked)}",
                    kick = fallback.solution, blockedBy = blocked
                )
            }
            is FallbackShot.Bank2 -> {
                val blocked = PathBlock.blockedBy(fallback.solution, others, radius)
                SolveOutcome(
                    null, null, emptyList(),
                    "Bank shot (two rails) to pocket${blockedSuffix(blocked)}",
                    bank2 = fallback.solution, blockedBy = blocked
                )
            }
            is FallbackShot.Kick2 -> {
                val blocked = PathBlock.blockedBy(fallback.solution, others, radius)
                SolveOutcome(
                    null, null, emptyList(),
                    "Kick shot (two rails) to pocket${blockedSuffix(blocked)}",
                    kick2 = fallback.solution, blockedBy = blocked
                )
            }
            is FallbackShot.Combo -> {
                val blocked = comboBlockedBy(fallback.solution, others, radius)
                SolveOutcome(
                    null, null, emptyList(),
                    "Combo shot to pocket${blockedSuffix(blocked)}",
                    combo = fallback.solution, blockedBy = blocked
                )
            }
            is FallbackShot.Kiss -> {
                val blocked = kissBlockedBy(fallback.solution, others, radius)
                SolveOutcome(
                    null, null, emptyList(),
                    "Kiss shot to pocket${blockedSuffix(blocked)}",
                    kiss = fallback.solution, blockedBy = blocked
                )
            }
            FallbackShot.None ->
                SolveOutcome(null, null, emptyList(), "No shot found for this ball")
        }
    }

    private fun pickTarget(
        cue: Ball,
        balls: List<Ball>,
        guideline: List<GuidelineSegment>
    ): Ball? {
        val others = balls.filter { it.id != cue.id }
        if (others.isEmpty()) return null
        val seg = guideline.maxByOrNull { it.segment.length() }?.segment
        if (seg == null) {
            // No guideline visible: fall back to the nearest ball.
            return others.minByOrNull { it.center.distanceTo(cue.center) }
        }
        // Guideline ray: from the endpoint nearer the cue ball, through the
        // far endpoint. The target is the ball closest to that ray, in front
        // of the cue ball.
        val near = if (seg.start.distanceTo(cue.center) < seg.end.distanceTo(cue.center)) seg.start else seg.end
        val far = if (near == seg.start) seg.end else seg.start
        val dir = (far - near).normalized()
        var best: Ball? = null
        var bestScore = Double.MAX_VALUE
        for (ball in others) {
            val toBall = ball.center - near
            val along = toBall.dot(dir)
            if (along <= 0) continue // behind the cue ball
            val perp = (toBall - dir * along).norm()
            if (perp > 80.0) continue // too far off the aim line
            val score = perp + along * 0.05
            if (score < bestScore) {
                bestScore = score
                best = ball
            }
        }
        return best ?: others.minByOrNull { it.center.distanceTo(cue.center) }
    }
}
