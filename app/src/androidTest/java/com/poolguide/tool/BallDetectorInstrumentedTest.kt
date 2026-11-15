package com.poolguide.tool

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.poolguide.tool.cv.BallDetector
import com.poolguide.tool.cv.CvConfig
import com.poolguide.tool.cv.OpenCvInit
import com.poolguide.tool.cv.TableDetector
import com.poolguide.tool.geometry.Vec2
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * Direct BallDetector tests on the bundled synthetic table images
 * (v1.0.36, power-team CV item 3): the detector runs its expensive
 * passes on the table ROI only, so these tests pin that the crop
 * does not lose balls — including a ball frozen near the top rail —
 * and that a missing table still falls back to the full frame.
 *
 * Working resolution is 720 wide, as in CvPipeline: the demo images
 * are 1280x720, so working coords are the full-res coords * 0.5625.
 * Green table: cue ~(197,202), red 3-ball ~(394,202). Bank rerank
 * table: red target ~(64,60) near the top rail, yellow blocker
 * ~(59,126).
 */
@RunWith(AndroidJUnit4::class)
class BallDetectorInstrumentedTest {

    @Before
    fun loadOpenCv() {
        assertTrue("OpenCV failed to load", OpenCvInit.ensureLoaded())
    }

    private fun loadDemoImage(name: String): Mat {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        targetContext.assets.open("demo/$name").use { stream ->
            val bitmap = BitmapFactory.decodeStream(stream)
            assertNotNull("demo image $name failed to decode", bitmap)
            val mat = Mat()
            Utils.bitmapToMat(bitmap, mat)
            return mat
        }
    }

    /** HSV + grey Mats at the 720-wide working resolution, exactly as
     * CvPipeline builds them (resize -> BGR -> HSV/grey). */
    private fun workingMats(name: String): Triple<Mat, Mat, Mat> {
        val rgba = loadDemoImage(name)
        val small = Mat()
        Imgproc.resize(rgba, small, Size(CvConfig.WORK_WIDTH.toDouble(), 405.0))
        val bgr = Mat()
        Imgproc.cvtColor(small, bgr, Imgproc.COLOR_RGBA2BGR)
        val hsv = Mat()
        Imgproc.cvtColor(bgr, hsv, Imgproc.COLOR_BGR2HSV)
        val gray = Mat()
        Imgproc.cvtColor(bgr, gray, Imgproc.COLOR_BGR2GRAY)
        return Triple(hsv, gray, small)
    }

    private fun near(balls: List<com.poolguide.tool.cv.Ball>, expected: Vec2, gate: Double): Boolean =
        balls.any { it.center.distanceTo(expected) < gate }

    @Test
    fun greenTable_roiFindsCueAndTarget() {
        val (hsv, gray, _) = workingMats("demo_table_green.png")
        val tableDetector = TableDetector()
        val table = tableDetector.detect(hsv)
        assertNotNull("table not found", table)
        val pockets = table!!.pockets()
        val pocketRadiusPx = table.width * 0.02

        val balls = BallDetector().detect(
            hsv, gray, table, pockets, runHough = true, frameIndex = 1,
            tableDetector.feltHsvMean, pocketRadiusPx
        )
        assertTrue("expected >= 8 balls, got ${balls.size}", balls.size >= 8)
        assertTrue(
            "cue ball not found near (197,202): ${balls.map { it.center }}",
            near(balls, Vec2(197.0, 202.0), 15.0)
        )
        assertTrue(
            "red target not found near (394,202): ${balls.map { it.center }}",
            near(balls, Vec2(394.0, 202.0), 15.0)
        )
    }

    @Test
    fun bankTable_roiKeepsRailBall() {
        // The red target sits ~9 px inside the top rail (centre
        // (64,60), table top edge ~51): the ROI pad is 5 px, so a
        // crop that cut the rail would lose this ball first.
        val (hsv, gray, _) = workingMats("demo_table_green_bank_rerank.png")
        val tableDetector = TableDetector()
        val table = tableDetector.detect(hsv)
        assertNotNull("table not found", table)
        val pockets = table!!.pockets()
        val pocketRadiusPx = table.width * 0.02

        val balls = BallDetector().detect(
            hsv, gray, table, pockets, runHough = true, frameIndex = 1,
            tableDetector.feltHsvMean, pocketRadiusPx
        )
        assertTrue("expected >= 3 balls, got ${balls.size}", balls.size >= 3)
        assertTrue(
            "rail ball not found near (64,60): ${balls.map { it.center }}",
            near(balls, Vec2(64.5, 60.0), 10.0)
        )
        assertTrue(
            "yellow blocker not found near (59,126): ${balls.map { it.center }}",
            near(balls, Vec2(58.7, 126.3), 10.0)
        )
    }

    @Test
    fun greenTable_noTableFallsBackToFullFrame() {
        // Degenerate input (no table): the detector must fall back
        // to the full frame and still find the cue ball, exactly as
        // before the ROI change.
        val (hsv, gray, _) = workingMats("demo_table_green.png")
        val tableDetector = TableDetector()
        val table = tableDetector.detect(hsv)
        assertNotNull("table not found", table)
        val pockets = table!!.pockets()
        val pocketRadiusPx = table.width * 0.02

        val balls = BallDetector().detect(
            hsv, gray, null, pockets, runHough = true, frameIndex = 1,
            tableDetector.feltHsvMean, pocketRadiusPx
        )
        assertTrue(
            "cue ball not found without a table: ${balls.map { it.center }}",
            near(balls, Vec2(197.0, 202.0), 15.0)
        )
    }
}
