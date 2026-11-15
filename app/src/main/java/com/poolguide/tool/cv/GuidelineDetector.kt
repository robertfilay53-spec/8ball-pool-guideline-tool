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
 * (vision_core.py + verifier.py + line_tracker.py) to OpenCV on Android,
 * with the detector parameters from workua94-afk/8ball-aim-master (Nov 13
 * GitHub search).
 *
 * Pipeline: Gaussian blur 5x5 -> Canny -> HoughLinesP -> verifier
 * (edge-point support) -> robust refit -> LineTracker (alpha fade).
 *
 * v1.0.1 changes (instrumented tests, Nov 14): the kaigiii Canny (500, 350)
 * kept almost no edges for the ~2 px guideline after the 720-wide
 * downscale (0 verified segments on the green table); Canny is now 50/150
 * and the Hough is the aim-master single pass (threshold 30, minLen 15,
 * maxGap 8) on the table-ROI crop, with the kaigiii short-line Hough as a
 * fallback only when nothing verifies. Verified segments get one guarded
 * Tukey-weighted least-squares refit over their inlier edge points
 * (yasin IRLS/Tukey lead); the original segment is kept when the refit
 * is degenerate, so the refit can only straighten, never break, a segment.
 *
 * Input: a grey [Mat] of the table ROI. Output: up to 3 verified segments
 * with a fade alpha. All Mats are reused across frames (no per-frame
 * allocation in the hot path).
 */
class GuidelineDetector {

    private val blurred = Mat()
    private val edges = Mat()
    private val lines = Mat()
    private val edgePoints = Mat()

    /** Tracked segments with their fade alpha. */
    private var tracked = mutableListOf<GuidelineSegment>()

    /** Detect the guideline in [grayRoi]. Returns the tracked segments. */
    fun detect(grayRoi: Mat): List<GuidelineSegment> {
        if (grayRoi.empty()) return tracked

        // v1.0.32: GaussianBlur 5x5 replaces the bilateral filter
        // (d=20, sigmaColor 200, sigmaSpace 1). The bilateral was 92%
        // of the guideline stage (50.6 ms/run on the harness replica);
        // the blur is 7.6x faster per run (7.9 ms) for +0.02 deg mean
        // angle error, verified on the 15-table harness + 7 adversarial
        // tables, with zero change to shots, cut error, or ball recall.
        Imgproc.GaussianBlur(
            grayRoi, blurred,
            Size(
                CvConfig.GAUSSIAN_BLUR_KSIZE.toDouble(),
                CvConfig.GAUSSIAN_BLUR_KSIZE.toDouble()
            ),
            0.0
        )
        Imgproc.Canny(blurred, edges, CvConfig.CANNY_LOW, CvConfig.CANNY_HIGH)

        // Main pass (aim-master params). The short-line Hough only runs
        // when nothing verifies — it produces ~150 junk candidates a frame.
        var verified = verifyLines(collectLines(houghMain()), edges)
        if (verified.isEmpty()) {
            verified = verifyLines(collectLines(houghShort()), edges)
        }

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

    private fun houghMain(): Mat {
        Imgproc.HoughLinesP(
            edges, lines, 1.0, Math.PI / 180.0,
            CvConfig.HOUGH_GUIDELINE_THRESHOLD.toInt(),
            CvConfig.HOUGH_GUIDELINE_MIN_LENGTH, CvConfig.HOUGH_GUIDELINE_MAX_GAP
        )
        return lines
    }

    private fun houghShort(): Mat {
        Imgproc.HoughLinesP(
            edges, lines, 1.0, Math.PI / 180.0,
            CvConfig.HOUGH_SHORT_THRESHOLD.toInt(),
            CvConfig.HOUGH_SHORT_MIN_LENGTH, CvConfig.HOUGH_SHORT_MAX_GAP
        )
        return lines
    }

    private fun collectLines(lines: Mat): List<Segment> {
        val out = mutableListOf<Segment>()
        for (i in 0 until lines.rows()) {
            val v = lines.get(i, 0) ?: continue
            if (v.size < 4) continue
            out += Segment(Vec2(v[0], v[1]), Vec2(v[2], v[3]))
        }
        return out
    }

    /**
     * Verifier (kaigiii verifier.py): for each candidate, collect the Canny
     * edge points within 1.5 px of the segment (projection clamped to
     * [0, 1]); keep segments with more than 20 inliers, best first, and
     * refit each kept segment over its inliers (guarded Tukey least
     * squares, below).
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
        // Dedup by exact coordinates (the two Hough passes can agree).
        val unique = candidates.distinctBy {
            "${it.start.x.toInt()},${it.start.y.toInt()},${it.end.x.toInt()},${it.end.y.toInt()}"
        }
        return unique
            .map { seg -> seg to inlierPoints(seg, points) }
            .filter { (_, inliers) -> inliers.size > CvConfig.VERIFY_MIN_INLIERS }
            .sortedByDescending { (_, inliers) -> inliers.size }
            .take(CvConfig.VERIFY_KEEP_TOP)
            .map { (seg, inliers) -> robustRefit(seg, inliers) }
    }

    /** Edge points within VERIFY_MAX_DIST_PX of [seg] (projection clamped). */
    private fun inlierPoints(seg: Segment, points: List<Point>): List<Point> {
        val ax = seg.start.x
        val ay = seg.start.y
        val bx = seg.end.x
        val by = seg.end.y
        val dx = bx - ax
        val dy = by - ay
        val lenSq = dx * dx + dy * dy
        if (lenSq < 1e-9) return emptyList()
        val maxDist = CvConfig.VERIFY_MAX_DIST_PX
        val out = ArrayList<Point>()
        for (p in points) {
            var t = ((p.x - ax) * dx + (p.y - ay) * dy) / lenSq
            t = t.coerceIn(0.0, 1.0)
            val px = ax + t * dx
            val py = ay + t * dy
            val dist = sqrt((p.x - px) * (p.x - px) + (p.y - py) * (p.y - py))
            if (dist <= maxDist) out += p
        }
        return out
    }

    /**
     * One guarded Tukey-weighted least-squares refit of [seg] over its
     * inlier edge points (total least squares via the weighted covariance
     * principal axis; yasin IRLS/Tukey lead, single pass). Returns the
     * original segment when the refit is degenerate: the fitted line
     * turns more than REFIT_MAX_ANGLE_DEG, or it shrinks below
     * REFIT_MIN_LENGTH_FRACTION of the original length. The refit
     * straightens a Hough segment onto its edge support; it can never
     * move it somewhere else.
     */
    private fun robustRefit(seg: Segment, inliers: List<Point>): Segment {
        if (inliers.size < CvConfig.VERIFY_MIN_INLIERS) return seg
        val c = CvConfig.VERIFY_MAX_DIST_PX
        // Tukey weights from the perpendicular distance to the segment.
        val weights = DoubleArray(inliers.size) { idx ->
            val u = distanceToSegment(inliers[idx], seg) / c
            if (u >= 1.0) 0.0 else (1.0 - u * u) * (1.0 - u * u)
        }
        var wSum = 0.0
        var mx = 0.0
        var my = 0.0
        for (i in inliers.indices) {
            wSum += weights[i]
            mx += weights[i] * inliers[i].x
            my += weights[i] * inliers[i].y
        }
        if (wSum < 1e-9) return seg
        mx /= wSum
        my /= wSum
        var sxx = 0.0
        var sxy = 0.0
        var syy = 0.0
        for (i in inliers.indices) {
            val dx = inliers[i].x - mx
            val dy = inliers[i].y - my
            sxx += weights[i] * dx * dx
            sxy += weights[i] * dx * dy
            syy += weights[i] * dy * dy
        }
        // Principal axis of the weighted covariance.
        val theta = 0.5 * atan2(2.0 * sxy, sxx - syy)
        var dirX = cos(theta)
        var dirY = sin(theta)
        // Keep the fitted direction pointing the same way as the segment.
        val segDir = seg.direction()
        if (dirX * segDir.x + dirY * segDir.y < 0.0) {
            dirX = -dirX
            dirY = -dirY
        }
        // Project the inliers onto the fitted line; the refitted segment
        // spans their extent.
        var tMin = Double.MAX_VALUE
        var tMax = -Double.MAX_VALUE
        for (p in inliers) {
            val t = (p.x - mx) * dirX + (p.y - my) * dirY
            if (t < tMin) tMin = t
            if (t > tMax) tMax = t
        }
        val refitted = Segment(
            Vec2(mx + dirX * tMin, my + dirY * tMin),
            Vec2(mx + dirX * tMax, my + dirY * tMax)
        )
        // Guards: angle change, length collapse.
        val angleChange = Math.toDegrees(
            kotlin.math.acos(
                (segDir.x * dirX + segDir.y * dirY).coerceIn(-1.0, 1.0)
            )
        )
        if (angleChange > CvConfig.REFIT_MAX_ANGLE_DEG) return seg
        if (refitted.length() < seg.length() * CvConfig.REFIT_MIN_LENGTH_FRACTION) return seg
        return refitted
    }

    private fun distanceToSegment(p: Point, seg: Segment): Double {
        val ax = seg.start.x
        val ay = seg.start.y
        val dx = seg.end.x - ax
        val dy = seg.end.y - ay
        val lenSq = dx * dx + dy * dy
        if (lenSq < 1e-9) return sqrt((p.x - ax) * (p.x - ax) + (p.y - ay) * (p.y - ay))
        var t = ((p.x - ax) * dx + (p.y - ay) * dy) / lenSq
        t = t.coerceIn(0.0, 1.0)
        val px = ax + t * dx
        val py = ay + t * dy
        return sqrt((p.x - px) * (p.x - px) + (p.y - py) * (p.y - py))
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
