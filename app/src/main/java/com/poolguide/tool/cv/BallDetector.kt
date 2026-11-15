package com.poolguide.tool.cv

import com.poolguide.tool.geometry.BallGroup
import com.poolguide.tool.geometry.BallGroupClassifier
import com.poolguide.tool.geometry.Vec2
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Ball detector.
 *
 * Fast path (every frame): HSV inRange + findContours per colour family,
 * minEnclosingCircle gives centre + radius + colour in one pass.
 * Slow path (every 3rd frame): HoughCircles (param2 12, no median blur;
 * swept Nov 14, v1.0.3) as a fallback for the white cue ball / black
 * 8-ball, for stripe balls (whose white band splits the colour into
 * two caps that fail the circularity check), and for colours the HSV
 * pass misses.
 *
 * Stripe vs solid: the centre pixel of a stripe ball is white (the number
 * band); a solid ball's centre pixel is its own colour.
 *
 * v1.0.1 fixes (instrumented tests, Nov 14):
 * - RETR_LIST, not RETR_EXTERNAL: the wooden rails fall inside the red
 *   and orange windows and form a closed ring around the table; every
 *   ball inside that ring's hole was dropped by RETR_EXTERNAL. The same
 *   trap hid the black ball (the dark background is a ring in the black
 *   window) and every ball inside it.
 * - Green ball: a GREEN window whose S lower bound is learned from the
 *   felt (Scugalug adaptive cloth-colour lead). The green ball's hue IS
 *   the felt's hue, so hue cannot separate them — but the ball is more
 *   saturated than the cloth (S 218 vs 171 on the synthetic table).
 * - Blue ball on a blue table: the same felt-relative tightening for the
 *   blue window (S and V above the felt's), since the whole felt falls
 *   inside the static blue window.
 * - Pocket exclusion is now a disc-overlap test (pocket radius + ball
 *   radius): Hough fitted r~12 circles to the pocket rims whose centres
 *   sit ~25 px from the pocket centres, just outside the old radius+12
 *   rule. A real ball's disc can never overlap a pocket's disc.
 */
class BallDetector {

    private val mask = Mat()
    private val hierarchy = Mat()
    private val grayBlurred = Mat()
    private val circles = Mat()

    private var lastBalls = listOf<Ball>()
    private var lastSeenFrame = mutableMapOf<Int, Long>()
    private var nextId = 1

    /**
     * Colour windows: (colour, HSV lower, HSV upper). The green and blue
     * windows are felt-relative when [feltHsv] is known (see below);
     * the white window's V floor is felt-relative too (v1.0.34):
     * max(90, feltV + 30), so a dim cue ball stays inside the window.
     * The rest are static.
     */
    private fun colorWindows(feltHsv: DoubleArray?): List<Triple<BallColor, DoubleArray, DoubleArray>> {
        val feltH = feltHsv?.get(0) ?: -1.0
        val feltS = feltHsv?.get(1) ?: 0.0
        val feltV = feltHsv?.get(2) ?: 0.0
        // Same-hue-as-felt colours need S/V separation from the cloth:
        // the ball is more saturated and brighter than the felt it sits
        // on. Only tightened when the felt's hue is inside that colour's
        // hue range; otherwise the static window applies.
        val greenSLow = if (feltH in 55.0..85.0) {
            (feltS + 25.0).coerceIn(150.0, 235.0)
        } else {
            195.0
        }
        val blueSLow = if (feltH in 90.0..130.0) {
            (feltS + 20.0).coerceIn(120.0, 240.0)
        } else {
            100.0
        }
        val blueVLow = if (feltH in 90.0..130.0) {
            (feltV + 15.0).coerceIn(70.0, 240.0)
        } else {
            70.0
        }
        // v1.0.3: same felt-relative tightening for orange on a brown
        // felt (felt H~15 sits inside the orange window): the orange
        // ball is far more saturated (S 255) than the cloth (S 139).
        val orangeSLow = if (feltH in 10.0..25.0) {
            (feltS + 25.0).coerceIn(150.0, 235.0)
        } else {
            100.0
        }
        return listOf(
            Triple(BallColor.RED, doubleArrayOf(0.0, 100.0, 70.0), doubleArrayOf(10.0, 255.0, 255.0)),
            Triple(BallColor.RED, doubleArrayOf(160.0, 100.0, 70.0), doubleArrayOf(179.0, 255.0, 255.0)),
            Triple(BallColor.ORANGE, doubleArrayOf(10.0, orangeSLow, 70.0), doubleArrayOf(20.0, 255.0, 255.0)),
            Triple(BallColor.YELLOW, doubleArrayOf(20.0, 100.0, 70.0), doubleArrayOf(35.0, 255.0, 255.0)),
            Triple(BallColor.BLUE, doubleArrayOf(90.0, blueSLow, blueVLow), doubleArrayOf(130.0, 255.0, 255.0)),
            Triple(BallColor.PURPLE, doubleArrayOf(130.0, 80.0, 50.0), doubleArrayOf(160.0, 255.0, 255.0)),
            Triple(BallColor.GREEN, doubleArrayOf(55.0, greenSLow, 40.0), doubleArrayOf(85.0, 255.0, 255.0)),
            Triple(BallColor.WHITE, doubleArrayOf(0.0, 0.0, CvConfig.feltRelativeWhiteVFloor(feltHsv, CvConfig.WHITE_V_FLOOR)), doubleArrayOf(180.0, 40.0, 255.0)),
            Triple(BallColor.BLACK, doubleArrayOf(0.0, 0.0, 0.0), doubleArrayOf(180.0, 255.0, 60.0))
        )
    }

    /**
     * Table ROI (v1.0.36, power-team CV item 3): the axis-aligned
     * bounding box of the table corners, padded 5 px exactly like
     * [insideTable], clamped to the frame. Returns null when there
     * is no table, the frame is empty, or the box is degenerate
     * (under 10 px on a side) — the caller then uses the full
     * frame, exactly as before. Pure bounds math, no OpenCV calls,
     * so it is unit-testable (see BallDetectorRoiTest).
     */
    internal fun tableRoi(table: TableRect?, frameWidth: Int, frameHeight: Int): Rect? {
        if (table == null || frameWidth <= 0 || frameHeight <= 0) return null
        val xs = table.corners.map { it.x }
        val ys = table.corners.map { it.y }
        val x0 = floor(xs.min() - 5.0).toInt().coerceIn(0, frameWidth - 1)
        val y0 = floor(ys.min() - 5.0).toInt().coerceIn(0, frameHeight - 1)
        val x1 = ceil(xs.max() + 5.0).toInt().coerceIn(1, frameWidth)
        val y1 = ceil(ys.max() + 5.0).toInt().coerceIn(1, frameHeight)
        if (x1 - x0 < 10 || y1 - y0 < 10) return null
        return Rect(x0, y0, x1 - x0, y1 - y0)
    }

    /**
     * Detect balls in [hsv] and [gray] (working resolution). [table] bounds
     * the search when known. [pockets] are excluded: the pockets are black
     * circles that would otherwise be detected as black balls. [runHough]
     * enables the slower HoughCircles fallback for this frame. [feltHsv]
     * is the felt's mean HSV (from the table detector) for the adaptive
     * green/blue windows; [pocketRadiusPx] is the pocket radius at the
     * working resolution for the disc-overlap exclusion.
     *
     * v1.0.36: the expensive passes (inRange + findContours per colour,
     * HoughCircles) run on the table ROI only ([tableRoi]). The ROI is
     * the table bbox + the same 5 px pad [insideTable] uses, so any
     * centre the ROI can produce would have passed [insideTable]
     * anyway — the crop drops work, never balls. Centres are
     * translated back to full-frame coordinates immediately, so every
     * check and sampler below (insideTable, pockets, stripe, colour,
     * disc sampling) still runs on the full frame exactly as before.
     * No table, or a degenerate ROI, falls back to the full frame.
     */
    fun detect(
        hsv: Mat, gray: Mat, table: TableRect?, pockets: List<Vec2> = emptyList(),
        runHough: Boolean, frameIndex: Long,
        feltHsv: DoubleArray? = null, pocketRadiusPx: Double = 12.0
    ): List<Ball> {
        val roi = tableRoi(table, hsv.cols(), hsv.rows())
        val hsvRoi = if (roi != null) hsv.submat(roi) else hsv
        val grayRoi = if (roi != null) gray.submat(roi) else gray
        val ox = roi?.x?.toDouble() ?: 0.0
        val oy = roi?.y?.toDouble() ?: 0.0
        try {
            return detectCropped(hsv, hsvRoi, grayRoi, ox, oy, table, pockets, runHough, frameIndex, feltHsv, pocketRadiusPx)
        } finally {
            // Only the submat views are released here: when the ROI
            // fell back to the full frame, hsvRoi/grayRoi ARE the
            // caller's Mats and must not be touched.
            if (roi != null) {
                hsvRoi.release()
                grayRoi.release()
            }
        }
    }

    /** The detection itself, run on the (possibly cropped) [hsvRoi] /
     * [grayRoi] with centres translated back to full-frame coordinates
     * by ([ox], [oy]) the moment they are found. [hsv] is the full
     * frame, used only for single-pixel sampling (stripe, colour,
     * disc groups), which is cheap and stays in full-frame coords. */
    private fun detectCropped(
        hsv: Mat, hsvRoi: Mat, grayRoi: Mat, ox: Double, oy: Double,
        table: TableRect?, pockets: List<Vec2>,
        runHough: Boolean, frameIndex: Long,
        feltHsv: DoubleArray?, pocketRadiusPx: Double
    ): List<Ball> {
        val found = mutableListOf<Ball>()

        for ((color, lower, upper) in colorWindows(feltHsv)) {
            Core.inRange(hsvRoi, Scalar(lower), Scalar(upper), mask)
            val contours = mutableListOf<MatOfPoint>()
            Imgproc.findContours(
                mask, contours, hierarchy,
                Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE
            )
            for (contour in contours) {
                val center = Point()
                val radius = FloatArray(1)
                Imgproc.minEnclosingCircle(MatOfPoint2f(*contour.toArray()), center, radius)
                val r = radius[0].toDouble()
                if (r < CvConfig.BALL_MIN_RADIUS_PX || r > CvConfig.BALL_MAX_RADIUS_PX) continue
                val area = Imgproc.contourArea(contour)
                if (area / (PI * r * r) < CvConfig.BALL_MIN_CIRCULARITY) continue
                val c = Vec2(center.x + ox, center.y + oy)
                if (table != null && !insideTable(c, table)) continue
                if (isPocket(c, r, pockets, pocketRadiusPx)) continue
                if (found.any { it.center.distanceTo(c) < CvConfig.BALL_DEDUP_DIST_PX }) continue
                found += Ball(0, c, r, color, isStripeAt(hsv, c, color, feltHsv))
            }
        }

        if (runHough) {
            // v1.0.3: no median blur (MEDIAN_BLUR_KSIZE 0) — the blur
            // smears the stripe band and Hough loses stripe balls.
            val houghSrc = if (CvConfig.MEDIAN_BLUR_KSIZE <= 0) {
                grayRoi
            } else {
                Imgproc.medianBlur(grayRoi, grayBlurred, CvConfig.MEDIAN_BLUR_KSIZE)
                grayBlurred
            }
            Imgproc.HoughCircles(
                houghSrc, circles, Imgproc.HOUGH_GRADIENT,
                CvConfig.HOUGH_CIRCLES_DP,
                CvConfig.HOUGH_CIRCLES_MIN_DIST,
                CvConfig.HOUGH_CIRCLES_PARAM1,
                CvConfig.HOUGH_CIRCLES_PARAM2,
                CvConfig.HOUGH_CIRCLES_MIN_RADIUS,
                CvConfig.HOUGH_CIRCLES_MAX_RADIUS
            )
            for (i in 0 until circles.cols()) {
                val v = circles.get(0, i) ?: continue
                if (v.size < 3) continue
                val c = Vec2(v[0] + ox, v[1] + oy)
                val r = v[2]
                if (r < CvConfig.BALL_MIN_RADIUS_PX || r > CvConfig.BALL_MAX_RADIUS_PX) continue
                if (table != null && !insideTable(c, table)) continue
                if (isPocket(c, r, pockets, pocketRadiusPx)) continue
                if (found.any { it.center.distanceTo(c) < CvConfig.BALL_DEDUP_DIST_PX }) continue
                val color = colorAt(hsv, c, feltHsv)
                found += Ball(0, c, r, color, isStripeAt(hsv, c, color, feltHsv))
            }
        }

        return track(classifyGroups(hsv, found, feltHsv), frameIndex)
    }

    /**
     * Group identification (v1.0.14): sample each detected ball's disc
     * and classify its group (cue / eight / solid / stripe / unknown).
     * A ball whose disc overlaps another detected ball's is occluded:
     * UNKNOWN, never guessed. Runs on the full found list so the
     * occlusion check sees every ball on the table this frame; the
     * tracker then carries each ball's group across frames with its
     * id (a ball missed briefly keeps the group it was last
     * classified with).
     */
    private fun classifyGroups(hsv: Mat, found: List<Ball>, feltHsv: DoubleArray? = null): List<Ball> {
        return found.map { ball ->
            val others = found.filter { it !== ball }
                .map { it.center to it.radiusPx }
            val group = if (BallGroupClassifier.isOccluded(ball.center, ball.radiusPx, others)) {
                BallGroup.UNKNOWN
            } else {
                BallGroupClassifier.classify(
                    BallClassifier.sampleDisc(hsv, ball.center, ball.radiusPx, feltHsv)
                )
            }
            ball.copy(group = group)
        }
    }

    fun reset() {
        lastBalls = emptyList()
        lastSeenFrame = mutableMapOf()
        nextId = 1
    }

    /** Nearest-neighbour tracking: stable ids across frames. */
    private fun track(found: List<Ball>, frameIndex: Long): List<Ball> {
        val used = BooleanArray(lastBalls.size)
        val result = mutableListOf<Ball>()
        for (ball in found) {
            var bestIdx = -1
            var bestDist = CvConfig.BALL_TRACK_GATE_PX
            for (i in lastBalls.indices) {
                if (used[i]) continue
                val d = ball.center.distanceTo(lastBalls[i].center)
                if (d < bestDist) {
                    bestDist = d
                    bestIdx = i
                }
            }
            if (bestIdx >= 0) {
                used[bestIdx] = true
                val old = lastBalls[bestIdx]
                result += ball.copy(id = old.id)
                lastSeenFrame[old.id] = frameIndex
            } else {
                val id = nextId++
                result += ball.copy(id = id)
                lastSeenFrame[id] = frameIndex
            }
        }
        // Keep balls that were missed briefly (smoothing over detection gaps).
        for (i in lastBalls.indices) {
            if (!used[i]) {
                val old = lastBalls[i]
                val missed = frameIndex - (lastSeenFrame[old.id] ?: frameIndex)
                if (missed <= CvConfig.BALL_TRACK_MISS_LIMIT) result += old
            }
        }
        lastBalls = result
        return result
    }

    /**
     * True when [p] is a pocket, not a ball. Disc-overlap test: a ball's
     * disc can never overlap a pocket's disc (a ball that close would
     * have dropped in), so any candidate whose disc touches the pocket
     * disc is the pocket itself, or a circle fitted to the pocket rim
     * by Hough. The margin is [CvConfig.POCKET_EXCLUSION_MARGIN_PX]
     * (0 since v1.0.3: the old +3 px ate real balls 22.5 px from a
     * pocket once the pocket model was fixed to inset 0).
     */
    private fun isPocket(p: Vec2, radius: Double, pockets: List<Vec2>, pocketRadiusPx: Double): Boolean {
        for (pocket in pockets) {
            if (p.distanceTo(pocket) < pocketRadiusPx + radius + CvConfig.POCKET_EXCLUSION_MARGIN_PX) return true
        }
        return false
    }

    /** Rough point-in-table test via the rect's bounding box. */
    private fun insideTable(p: Vec2, table: TableRect): Boolean {
        val xs = table.corners.map { it.x }
        val ys = table.corners.map { it.y }
        val pad = 5.0
        return p.x in (xs.min() - pad)..(xs.max() + pad) &&
            p.y in (ys.min() - pad)..(ys.max() + pad)
    }

    /** Colour class of the HSV pixel at [p] (used for Hough-found circles).
     * The white rule uses the felt-relative floor (v1.0.34), like the
     * white window in [colorWindows]; without the felt it keeps the
     * fixed floor. */
    private fun colorAt(hsv: Mat, p: Vec2, feltHsv: DoubleArray? = null): BallColor {
        val px = hsv.get(p.y.toInt().coerceIn(0, hsv.rows() - 1), p.x.toInt().coerceIn(0, hsv.cols() - 1))
            ?: return BallColor.UNKNOWN
        val h = px[0]
        val s = px[1]
        val v = px[2]
        return when {
            v < 60 -> BallColor.BLACK
            s < 40 && v > CvConfig.feltRelativeWhiteVFloor(feltHsv, CvConfig.WHITE_V_FLOOR) -> BallColor.WHITE
            h < 10 || h >= 160 -> BallColor.RED
            h < 20 -> BallColor.ORANGE
            h < 35 -> BallColor.YELLOW
            h < 85 -> BallColor.GREEN
            h < 130 -> BallColor.BLUE
            h < 160 -> BallColor.PURPLE
            else -> BallColor.UNKNOWN
        }
    }

    /** True when the centre pixel is white-ish: a stripe ball's number band.
     * The V test uses the felt-relative floor (v1.0.34), like the disc
     * sampler; without the felt it keeps the fixed stripe floor. */
    private fun isStripeAt(hsv: Mat, p: Vec2, color: BallColor, feltHsv: DoubleArray? = null): Boolean {
        if (color == BallColor.WHITE || color == BallColor.BLACK) return false
        val px = hsv.get(p.y.toInt().coerceIn(0, hsv.rows() - 1), p.x.toInt().coerceIn(0, hsv.cols() - 1))
            ?: return false
        return px[1] < 60 && px[2] > CvConfig.feltRelativeWhiteVFloor(feltHsv, CvConfig.STRIPE_V_FLOOR)
    }
}
