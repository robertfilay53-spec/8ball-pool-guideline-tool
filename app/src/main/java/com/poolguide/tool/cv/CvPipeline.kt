package com.poolguide.tool.cv

import com.poolguide.tool.data.GameProfile
import com.poolguide.tool.geometry.Rail
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
import kotlin.math.abs

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
        // Ratio fallback (v1.0.4, Bloro lead): when detection finds no
        // table at all, guess the rect from the frame size instead of
        // reporting nothing. The guess is labelled in the result and
        // the detector retries on schedule, so a real detection replaces
        // it as soon as one succeeds.
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
                detected = found
                    ?: RatioTableGeometry.fallbackTable(workingWidth, workingHeight)
                tableFromFallback = found == null
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
        val cue = balls.firstOrNull { it.isCue }

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
        val locked = if (tableOverrideNormalized != null && table != null) {
            true
        } else {
            tableLock.update(table)
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
            allShots = outcome.allShots,
            tableLocked = locked,
            tableFromFallback = tableFromFallback,
            status = if (tableFromFallback) outcome.status + " (table: ratio fallback)"
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

    /** One frame's solve: best direct shot, every candidate shot, a
     * one-rail bank fallback, and the status line. */
    private data class SolveOutcome(
        val shot: ShotSolver.ShotSolution?,
        val bank: ShotSolver.BankSolution?,
        val allShots: List<ShotSolver.ShotSolution>,
        val status: String
    )

    /**
     * Pick the target ball (nearest ball along the guideline ray from the cue
     * ball; nearest ball overall when no guideline is visible) and the pocket
     * with the smallest cut angle, then solve. Falls back to a one-rail bank
     * when no direct shot is possible.
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
        if (pockets.isEmpty()) return SolveOutcome(null, null, emptyList(), "Table not found")
        if (cue == null) return SolveOutcome(null, null, emptyList(), "Cue ball not found")
        val target = pickTarget(cue, balls, guideline)
            ?: return SolveOutcome(null, null, emptyList(), "No target ball in line")
        val radius = (cue.radiusPx + target.radiusPx) / 2.0

        // Every direct shot, best first: smallest cut angle, first-listed
        // pocket wins ties within the tie margin (a symmetric layout must
        // not flip on sub-degree detection noise). The best is
        // allShots.firstOrNull(); the overlay draws the rest faint.
        val all = ShotSolver.allShots(cue.center, target.center, pockets, radius)
        val best = all.firstOrNull()
        if (best != null) {
            val warn = if (best.isThinCut) " — thin cut, very low margin" else ""
            return SolveOutcome(best, null, all, "Shot to pocket$warn")
        }

        // No direct shot: try one-rail banks.
        val tableRect = table ?: return SolveOutcome(null, null, emptyList(), "No shot found")
        val rails = railsOf(tableRect)
        for (pocket in pockets) {
            for (rail in rails) {
                val bank = ShotSolver.solveBank(cue.center, target.center, pocket, rail, radius)
                    ?: continue
                return SolveOutcome(null, bank, emptyList(), "Bank shot (one rail) to pocket")
            }
        }
        return SolveOutcome(null, null, emptyList(), "No shot found for this ball")
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

    /** The four rails of the table rect, normals pointing into the table. */
    private fun railsOf(t: TableRect): List<Rail> = listOf(
        Rail(t.topLeft, Vec2(0.0, 1.0)),   // top rail, normal points down
        Rail(t.bottomLeft, Vec2(0.0, -1.0)), // bottom rail, normal points up
        Rail(t.topLeft, Vec2(1.0, 0.0)),   // left rail, normal points right
        Rail(t.topRight, Vec2(-1.0, 0.0))  // right rail, normal points left
    )
}
