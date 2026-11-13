package com.poolguide.tool.cv

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
 */
class TableDetector {

    private val mask = Mat()
    private val hierarchy = Mat()
    private val approx = MatOfPoint2f()

    /** Detect the table in an HSV [Mat]. Returns null when no felt found. */
    fun detect(hsv: Mat): TableRect? {
        return detectWith(hsv, CvConfig.FELT_GREEN_LOWER, CvConfig.FELT_GREEN_UPPER)
            ?: detectWith(hsv, CvConfig.FELT_BLUE_LOWER, CvConfig.FELT_BLUE_UPPER)
    }

    private fun detectWith(hsv: Mat, lower: DoubleArray, upper: DoubleArray): TableRect? {
        Core.inRange(hsv, Scalar(lower), Scalar(upper), mask)
        val contours = mutableListOf<MatOfPoint>()
        Imgproc.findContours(
            mask, contours, hierarchy,
            Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE
        )
        if (contours.isEmpty()) return null
        val biggest = contours.maxByOrNull { Imgproc.contourArea(it) } ?: return null
        if (Imgproc.contourArea(biggest) < 5_000) return null // too small to be a table

        val curve = MatOfPoint2f(*biggest.toArray())
        val peri = Imgproc.arcLength(curve, true)
        Imgproc.approxPolyDP(curve, approx, 0.02 * peri, true)
        val pts = approx.toArray()
        if (pts.size == 4) {
            return orderCorners(pts.map { Vec2(it.x, it.y) })
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
