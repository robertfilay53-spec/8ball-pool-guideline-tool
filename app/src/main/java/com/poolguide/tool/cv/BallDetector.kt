package com.poolguide.tool.cv

import com.poolguide.tool.geometry.Vec2
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import kotlin.math.PI
import kotlin.math.abs

/**
 * Ball detector.
 *
 * Fast path (every frame): HSV inRange + findContours per colour family,
 * minEnclosingCircle gives centre + radius + colour in one pass.
 * Slow path (every 3rd frame): HoughCircles (ChatGPT params) as a fallback
 * for the white cue ball / black 8-ball and for colours the HSV pass misses
 * (notably the green ball, whose HSV band overlaps the green felt — the
 * contour pass deliberately skips green; Hough finds it on the grey frame).
 *
 * Stripe vs solid: the centre pixel of a stripe ball is white (the number
 * band); a solid ball's centre pixel is its own colour.
 */
class BallDetector {

    private val mask = Mat()
    private val hierarchy = Mat()
    private val grayBlurred = Mat()
    private val circles = Mat()

    private var lastBalls = listOf<Ball>()
    private var lastSeenFrame = mutableMapOf<Int, Long>()
    private var nextId = 1

    /** Colour windows: (colour, HSV lower, HSV upper). */
    private val colorWindows = listOf(
        Triple(BallColor.RED, doubleArrayOf(0.0, 100.0, 70.0), doubleArrayOf(10.0, 255.0, 255.0)),
        Triple(BallColor.RED, doubleArrayOf(160.0, 100.0, 70.0), doubleArrayOf(179.0, 255.0, 255.0)),
        Triple(BallColor.ORANGE, doubleArrayOf(10.0, 100.0, 70.0), doubleArrayOf(20.0, 255.0, 255.0)),
        Triple(BallColor.YELLOW, doubleArrayOf(20.0, 100.0, 70.0), doubleArrayOf(35.0, 255.0, 255.0)),
        Triple(BallColor.BLUE, doubleArrayOf(90.0, 100.0, 70.0), doubleArrayOf(130.0, 255.0, 255.0)),
        Triple(BallColor.PURPLE, doubleArrayOf(130.0, 80.0, 50.0), doubleArrayOf(160.0, 255.0, 255.0)),
        Triple(BallColor.WHITE, doubleArrayOf(0.0, 0.0, 200.0), doubleArrayOf(180.0, 40.0, 255.0)),
        Triple(BallColor.BLACK, doubleArrayOf(0.0, 0.0, 0.0), doubleArrayOf(180.0, 255.0, 60.0))
        // GREEN is deliberately absent: its HSV band is the felt itself.
    )

    /**
     * Detect balls in [hsv] and [gray] (working resolution). [table] bounds
     * the search when known. [pockets] are excluded: the pockets are black
     * circles that would otherwise be detected as black balls. [runHough]
     * enables the slower HoughCircles fallback for this frame.
     */
    fun detect(
        hsv: Mat, gray: Mat, table: TableRect?, pockets: List<Vec2> = emptyList(),
        runHough: Boolean, frameIndex: Long
    ): List<Ball> {
        val found = mutableListOf<Ball>()

        for ((color, lower, upper) in colorWindows) {
            Core.inRange(hsv, Scalar(lower), Scalar(upper), mask)
            val contours = mutableListOf<MatOfPoint>()
            Imgproc.findContours(
                mask, contours, hierarchy,
                Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE
            )
            for (contour in contours) {
                val center = Point()
                val radius = FloatArray(1)
                Imgproc.minEnclosingCircle(MatOfPoint2f(*contour.toArray()), center, radius)
                val r = radius[0].toDouble()
                if (r < CvConfig.BALL_MIN_RADIUS_PX || r > CvConfig.BALL_MAX_RADIUS_PX) continue
                val area = Imgproc.contourArea(contour)
                if (area / (PI * r * r) < CvConfig.BALL_MIN_CIRCULARITY) continue
                val c = Vec2(center.x, center.y)
                if (table != null && !insideTable(c, table)) continue
                if (isPocket(c, r, pockets)) continue
                if (found.any { it.center.distanceTo(c) < CvConfig.BALL_DEDUP_DIST_PX }) continue
                found += Ball(0, c, r, color, isStripeAt(hsv, c, color))
            }
        }

        if (runHough) {
            Imgproc.medianBlur(gray, grayBlurred, CvConfig.MEDIAN_BLUR_KSIZE)
            Imgproc.HoughCircles(
                grayBlurred, circles, Imgproc.HOUGH_GRADIENT,
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
                val c = Vec2(v[0], v[1])
                val r = v[2]
                if (r < CvConfig.BALL_MIN_RADIUS_PX || r > CvConfig.BALL_MAX_RADIUS_PX) continue
                if (table != null && !insideTable(c, table)) continue
                if (isPocket(c, r, pockets)) continue
                if (found.any { it.center.distanceTo(c) < CvConfig.BALL_DEDUP_DIST_PX }) continue
                val color = colorAt(hsv, c)
                found += Ball(0, c, r, color, isStripeAt(hsv, c, color))
            }
        }

        return track(found, frameIndex)
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

    /** True when [p] is a pocket, not a ball (pockets are black circles). */
    private fun isPocket(p: Vec2, radius: Double, pockets: List<Vec2>): Boolean {
        for (pocket in pockets) {
            if (p.distanceTo(pocket) < radius + 12.0) return true
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

    /** Colour class of the HSV pixel at [p] (used for Hough-found circles). */
    private fun colorAt(hsv: Mat, p: Vec2): BallColor {
        val px = hsv.get(p.y.toInt().coerceIn(0, hsv.rows() - 1), p.x.toInt().coerceIn(0, hsv.cols() - 1))
            ?: return BallColor.UNKNOWN
        val h = px[0]
        val s = px[1]
        val v = px[2]
        return when {
            v < 60 -> BallColor.BLACK
            s < 40 && v > 200 -> BallColor.WHITE
            h < 10 || h >= 160 -> BallColor.RED
            h < 20 -> BallColor.ORANGE
            h < 35 -> BallColor.YELLOW
            h < 85 -> BallColor.GREEN
            h < 130 -> BallColor.BLUE
            h < 160 -> BallColor.PURPLE
            else -> BallColor.UNKNOWN
        }
    }

    /** True when the centre pixel is white-ish: a stripe ball's number band. */
    private fun isStripeAt(hsv: Mat, p: Vec2, color: BallColor): Boolean {
        if (color == BallColor.WHITE || color == BallColor.BLACK) return false
        val px = hsv.get(p.y.toInt().coerceIn(0, hsv.rows() - 1), p.x.toInt().coerceIn(0, hsv.cols() - 1))
            ?: return false
        return px[1] < 60 && px[2] > 180
    }
}
