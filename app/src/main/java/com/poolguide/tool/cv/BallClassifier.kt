package com.poolguide.tool.cv

import com.poolguide.tool.geometry.BallGroupClassifier
import com.poolguide.tool.geometry.DiscStats
import com.poolguide.tool.geometry.Vec2
import org.opencv.core.Mat
import kotlin.math.PI

/**
 * Ball-group sampling (v1.0.14). Samples the HSV pixels of a ball's
 * disc on a 2 px grid inside 0.85 * radius and returns the statistics
 * [BallGroupClassifier] decides on. The rim is excluded on purpose:
 * it is antialiased against the felt and would pollute the white
 * fraction with felt pixels.
 *
 * The decision itself is pure Kotlin ([BallGroupClassifier]) and is
 * unit-tested on the JVM against the measured harness stats; this
 * object only touches OpenCV, so it is covered by the instrumented
 * tests and the CV harness mirror (see cv-harness/pipeline.py).
 */
object BallClassifier {

    /** Sample step inside the disc (working-resolution px). */
    const val SAMPLE_STEP_PX = 2.0

    /** Sample inside this fraction of the radius: the rim is
     * antialiased against the felt. */
    const val SAMPLE_RADIUS_FRACTION = 0.85

    fun sampleDisc(hsv: Mat, center: Vec2, radiusPx: Double, feltHsv: DoubleArray? = null): DiscStats {
        val sampleR = SAMPLE_RADIUS_FRACTION * radiusPx
        val expected = (PI * sampleR * sampleR / (SAMPLE_STEP_PX * SAMPLE_STEP_PX)).toInt()
        var sampled = 0
        var white = 0
        var sumV = 0.0
        var sumS = 0.0
        // v1.0.34: the white test uses the felt-relative floor, so a
        // dim cue ball's disc still samples white (at 0.4x light the
        // cue is V~98 against a felt-relative floor of 90, not the
        // fixed 130); without the felt it keeps the fixed floor.
        val whiteVFloor = CvConfig.feltRelativeWhiteVFloor(feltHsv, CvConfig.STRIPE_V_FLOOR)
        var y = center.y - sampleR
        while (y <= center.y + sampleR) {
            var x = center.x - sampleR
            while (x <= center.x + sampleR) {
                val dx = x - center.x
                val dy = y - center.y
                if (dx * dx + dy * dy <= sampleR * sampleR) {
                    val px = hsv.get(
                        y.toInt().coerceIn(0, hsv.rows() - 1),
                        x.toInt().coerceIn(0, hsv.cols() - 1)
                    )
                    if (px != null && px.size >= 3) {
                        sampled++
                        val s = px[1]
                        val v = px[2]
                        sumS += s
                        sumV += v
                        if (s < 60.0 && v > whiteVFloor) white++
                    }
                }
                x += SAMPLE_STEP_PX
            }
            y += SAMPLE_STEP_PX
        }
        return DiscStats(
            whiteFraction = if (sampled > 0) white.toDouble() / sampled else 0.0,
            meanV = if (sampled > 0) sumV / sampled else 0.0,
            meanS = if (sampled > 0) sumS / sampled else 0.0,
            sampledPx = sampled,
            expectedPx = expected
        )
    }
}
