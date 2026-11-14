package com.poolguide.tool.cv

import com.poolguide.tool.data.GameProfile
import com.poolguide.tool.geometry.Vec2
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc

/**
 * Table detector: HSV felt mask -> largest contour -> 4 corners.
 * Tries green felt first, then blue (different table skins).
 * All Mats are reused across frames.
 *
 * Two v1.0.1 fixes:
 * - RETR_LIST, not RETR_EXTERNAL: the largest contour is what matters,
 *   and RETR_EXTERNAL also dropped contours nested inside another
 *   contour's hole — the same trap that hid balls in BallDetector.
 * - Axis-aligned snap: when the fitted quad is close to horizontal /
 *   vertical, the bounding rect gives symmetric corners. approxPolyDP
 *   cuts the felt's rounded corners asymmetrically (and the side-pocket
 *   notches skew the fit), which flipped a tied pocket pick on the
 *   synthetic green table. A rotated table keeps the approx quad.
 * - The felt's mean HSV is exposed ([feltHsvMean]) so BallDetector can
 *   separate same-hue balls from the cloth (Scugalug adaptive lead).
 */
class TableDetector {

    private val mask = Mat()
    private val hierarchy = Mat()
    private val approx = MatOfPoint2f()

    /** Mean HSV (H, S, V) of the felt mask on the last successful detect. */
    var feltHsvMean: DoubleArray? = null
        private set

    /** Detect the table in an HSV [Mat]. Returns null when no felt found.
     * The brown window comes from [profile] (v1.0.4: recalibratable per
     * game version); green/blue use the fixed windows. */
    fun detect(hsv: Mat, profile: GameProfile = GameProfile.DEFAULT): TableRect? {
        return detectWith(hsv, CvConfig.FELT_GREEN_LOWER, CvConfig.FELT_GREEN_UPPER)
            ?: detectWith(hsv, CvConfig.FELT_BLUE_LOWER, CvConfig.FELT_BLUE_UPPER)
            ?: detectWith(hsv, profile.brownFeltLower, profile.brownFeltUpper)
    }

    private fun detectWith(hsv: Mat, lower: DoubleArray, upper: DoubleArray): TableRect? {
        Core.inRange(hsv, Scalar(lower), Scalar(upper), mask)
        val contours = mutableListOf<MatOfPoint>()
        Imgproc.findContours(
            mask, contours, hierarchy,
            Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE
        )
        if (contours.isEmpty()) return null
        val biggest = contours.maxByOrNull { Imgproc.contourArea(it) } ?: return null
        if (Imgproc.contourArea(biggest) < 5_000) return null // too small to be a table
        feltHsvMean = Core.mean(hsv, mask).`val`.copyOf(3)

        val curve = MatOfPoint2f(*biggest.toArray())
        val peri = Imgproc.arcLength(curve, true)
        Imgproc.approxPolyDP(curve, approx, 0.02 * peri, true)
        val pts = approx.toArray()
        if (pts.size == 4) {
            val quad = orderCorners(pts.map { Vec2(it.x, it.y) })
            return snapToBoundingRectIfAxisAligned(quad, biggest)
        }
        // Fallback: bounding rect corners.
        val rect = Imgproc.boundingRect(biggest)
        return TableRect(
            topLeft = Vec2(rect.x.toDouble(), rect.y.toDouble()),
            topRight = Vec2((rect.x + rect.width).toDouble(), rect.y.toDouble()),
            bottomRight = Vec2((rect.x + rect.width).toDouble(), (rect.y + rect.height).toDouble()),
            bottomLeft = Vec2(rect.x.toDouble(), (rect.y + rect.height).toDouble())
        )
    }

    /**
     * When the fitted quad is axis-aligned (the table is rendered aligned
     * with the screen, the normal case), the bounding rect of the felt
     * contour is the symmetric corner set. Otherwise the approx quad
     * (a rotated table) is kept.
     */
    private fun snapToBoundingRectIfAxisAligned(
        quad: TableRect,
        contour: MatOfPoint
    ): TableRect {
        val maxAngle = CvConfig.AXIS_ALIGNED_MAX_ANGLE_DEG
        val topAngle = angleFromHorizontal(quad.topRight - quad.topLeft)
        val bottomAngle = angleFromHorizontal(quad.bottomRight - quad.bottomLeft)
        val leftAngle = angleFromVertical(quad.bottomLeft - quad.topLeft)
        val rightAngle = angleFromVertical(quad.bottomRight - quad.topRight)
        if (topAngle > maxAngle || bottomAngle > maxAngle ||
            leftAngle > maxAngle || rightAngle > maxAngle
        ) {
            return quad
        }
        val rect = Imgproc.boundingRect(contour)
        return TableRect(
            topLeft = Vec2(rect.x.toDouble(), rect.y.toDouble()),
            topRight = Vec2((rect.x + rect.width).toDouble(), rect.y.toDouble()),
            bottomRight = Vec2((rect.x + rect.width).toDouble(), (rect.y + rect.height).toDouble()),
            bottomLeft = Vec2(rect.x.toDouble(), (rect.y + rect.height).toDouble())
        )
    }

    /** Absolute angle of [v] from the horizontal axis, in degrees (0-90). */
    private fun angleFromHorizontal(v: Vec2): Double {
        var deg = Math.toDegrees(kotlin.math.atan2(kotlin.math.abs(v.y), kotlin.math.abs(v.x)))
        if (deg > 90.0) deg = 180.0 - deg
        return deg
    }

    /** Absolute angle of [v] from the vertical axis, in degrees (0-90). */
    private fun angleFromVertical(v: Vec2): Double {
        var deg = Math.toDegrees(kotlin.math.atan2(kotlin.math.abs(v.x), kotlin.math.abs(v.y)))
        if (deg > 90.0) deg = 180.0 - deg
        return deg
    }

    /** Order 4 points as top-left, top-right, bottom-right, bottom-left. */
    fun orderCorners(pts: List<Vec2>): TableRect {
        require(pts.size == 4)
        // Top-left = smallest (x + y); bottom-right = largest (x + y).
        // Top-right = smallest (y - x); bottom-left = largest (y - x).
        val sorted = pts.sortedBy { it.x + it.y }
        val tl = sorted[0]
        val br = sorted[3]
        val remaining = sorted.subList(1, 3)
        val tr = remaining.minByOrNull { it.y - it.x }!!
        val bl = remaining.maxByOrNull { it.y - it.x }!!
        return TableRect(tl, tr, br, bl)
    }
}
