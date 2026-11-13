package com.poolguide.tool.cv

import com.poolguide.tool.geometry.Segment
import com.poolguide.tool.geometry.Vec2
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * White-guideline detector — a port of kaigiii/8-Ball-Pool-Aim-Assist-for-macOS
 * (vision_core.py + verifier.py + line_tracker.py) to OpenCV on Android.
 *
 * Pipeline: bilateral filter -> Canny -> dual HoughLinesP (long + short) ->
 * dedup -> verifier (edge-point support) -> LineTracker (alpha fade).
 *
 * Input: a grey [Mat] of the table ROI. Output: up to 3 verified segments
 * with a fade alpha. All Mats are reused across frames (no per-frame
 * allocation in the hot path).
 */
class GuidelineDetector {

    private val blurred = Mat()
    private val edges = Mat()
    private val linesLong = Mat()
    private val linesShort = Mat()
    private val edgePoints = Mat()

    /** Tracked segments with their fade alpha. */
    private var tracked = mutableListOf<GuidelineSegment>()

    /** Detect the guideline in [grayRoi]. Returns the tracked segments. */
    fun detect(grayRoi: Mat): List<GuidelineSegment> {
        if (grayRoi.empty()) return tracked

        Imgproc.bilateralFilter(
            grayRoi, blurred,
            CvConfig.BILATERAL_D,
            CvConfig.BILATERAL_SIGMA_COLOR,
            CvConfig.BILATERAL_SIGMA_SPACE
        )
        Imgproc.Canny(blurred, edges, CvConfig.CANNY_LOW, CvConfig.CANNY_HIGH)

        val candidates = mutableListOf<Segment>()
        Imgproc.HoughLinesP(
            edges, linesLong, 1.0, Math.PI / 180.0,
            CvConfig.HOUGH_LONG_THRESHOLD.toInt(),
            CvConfig.HOUGH_LONG_MIN_LENGTH, CvConfig.HOUGH_LONG_MAX_GAP
        )
        Imgproc.HoughLinesP(
            edges, linesShort, 1.0, Math.PI / 180.0,
            CvConfig.HOUGH_SHORT_THRESHOLD.toInt(),
            CvConfig.HOUGH_SHORT_MIN_LENGTH, CvConfig.HOUGH_SHORT_MAX_GAP
        )
        collectLines(linesLong, candidates)
        collectLines(linesShort, candidates)

        // Dedup by exact coordinates.
        val unique = candidates.distinctBy {
            "${it.start.x.toInt()},${it.start.y.toInt()},${it.end.x.toInt()},${it.end.y.toInt()}"
        }

        // Verifier: keep the segments with the most Canny edge support.
        val verified = verifyLines(unique, edges).take(CvConfig.VERIFY_KEEP_TOP)

        // LineTracker: match against tracked segments, fade the rest.
        val next = mutableListOf<GuidelineSegment>()
        for (seg in verified) {
            val matched = tracked.find { matches(it.segment, seg) }
            next += GuidelineSegment(seg, CvConfig.TRACKER_ALPHA_START)
            if (matched != null) tracked.remove(matched)
        }
        for (old in tracked) {
            val faded = old.alpha - CvConfig.TRACKER_ALPHA_FADE
            if (faded > 0) next += GuidelineSegment(old.segment, faded)
        }
        tracked = next
        return tracked
    }

    fun reset() {
        tracked = mutableListOf()
    }

    private fun collectLines(lines: Mat, out: MutableList<Segment>) {
        for (i in 0 until lines.rows()) {
            val v = lines.get(i, 0) ?: continue
            if (v.size < 4) continue
            out += Segment(Vec2(v[0], v[1]), Vec2(v[2], v[3]))
        }
    }

    /**
     * Verifier (kaigiii verifier.py): for each candidate, count Canny edge
     * points within 1.5 px of the segment (projection clamped to [0, 1]);
     * keep segments with more than 20 inliers, best first.
     */
    private fun verifyLines(candidates: List<Segment>, edgeMat: Mat): List<Segment> {
        if (candidates.isEmpty()) return emptyList()
        Core.findNonZero(edgeMat, edgePoints)
        val count = edgePoints.rows()
        if (count == 0) return emptyList()
        // Cap the point cloud so verification stays fast on dense edges.
        val step = if (count > 8000) count / 8000 else 1
        val points = ArrayList<Point>(count / step)
        var i = 0
        while (i < count) {
            val p = edgePoints.get(i, 0)
            if (p != null && p.size >= 2) points += Point(p[0], p[1])
            i += step
        }
        return candidates
            .map { seg -> seg to countInliers(seg, points) }
            .filter { (_, inliers) -> inliers > CvConfig.VERIFY_MIN_INLIERS }
            .sortedByDescending { (_, inliers) -> inliers }
            .map { (seg, _) -> seg }
    }

    private fun countInliers(seg: Segment, points: List<Point>): Int {
        val ax = seg.start.x
        val ay = seg.start.y
        val bx = seg.end.x
        val by = seg.end.y
        val dx = bx - ax
        val dy = by - ay
        val lenSq = dx * dx + dy * dy
        if (lenSq < 1e-9) return 0
        val maxDist = CvConfig.VERIFY_MAX_DIST_PX
        var inliers = 0
        for (p in points) {
            var t = ((p.x - ax) * dx + (p.y - ay) * dy) / lenSq
            t = t.coerceIn(0.0, 1.0)
            val px = ax + t * dx
            val py = ay + t * dy
            val dist = sqrt((p.x - px) * (p.x - px) + (p.y - py) * (p.y - py))
            if (dist <= maxDist) inliers++
        }
        return inliers
    }

    private fun matches(a: Segment, b: Segment): Boolean {
        val angleA = atan2(a.end.y - a.start.y, a.end.x - a.start.x)
        val angleB = atan2(b.end.y - b.start.y, b.end.x - b.start.x)
        var diff = abs(Math.toDegrees(angleA - angleB)) % 180.0
        if (diff > 90.0) diff = 180.0 - diff
        if (diff > CvConfig.TRACKER_MAX_ANGLE_DEG) return false
        val gate = CvConfig.TRACKER_MAX_ENDPOINT_DIST_PX
        return (a.start.distanceTo(b.start) < gate && a.end.distanceTo(b.end) < gate) ||
            (a.start.distanceTo(b.end) < gate && a.end.distanceTo(b.start) < gate)
    }

    /** Direction of the strongest tracked segment, or null when none. */
    fun strongestDirection(): Vec2? {
        val seg = tracked.maxByOrNull { it.segment.length() } ?: return null
        return seg.segment.direction()
    }
}
