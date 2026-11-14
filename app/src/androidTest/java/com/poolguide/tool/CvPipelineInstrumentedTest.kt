package com.poolguide.tool

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.poolguide.tool.cv.CvPipeline
import com.poolguide.tool.cv.OpenCvInit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.Utils
import org.opencv.core.Mat

/**
 * Runs the real CV pipeline on the bundled synthetic table images.
 * These are the same images Demo mode uses, so a pass here means the
 * demo (and, by construction, the live path) detects the table, the
 * balls, the guideline, and solves the depicted shot.
 *
 * Green table: cue (350,360) -> red 3-ball (700,360) -> top-right pocket.
 * At the 720-wide working resolution that is cue ~(197,202), target
 * ~(394,202), pocket ~(675,51), cut angle ~31 degrees.
 */
@RunWith(AndroidJUnit4::class)
class CvPipelineInstrumentedTest {

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

    @Test
    fun greenTable_detectsTableBallsGuidelineAndShot() {
        val mat = loadDemoImage("demo_table_green.png")
        val pipeline = CvPipeline(1280, 720)
        // Run several frames: Hough runs every 3rd, guideline every 2nd.
        var result = pipeline.process(mat)
        repeat(5) { result = pipeline.process(mat) }

        assertFalse("green table must not be black", result.isCaptureBlack)
        assertNotNull("table not found", result.table)
        assertEquals("6 pockets expected", 6, result.pockets.size)
        assertNotNull("cue ball not found", result.cueBall)
        assertTrue("expected >= 8 balls, got ${result.balls.size}", result.balls.size >= 8)
        assertTrue("guideline not found", result.guideline.isNotEmpty())
        assertNotNull("no shot solved: ${result.status}", result.shot)
        val shot = result.shot!!
        // The solved target should be the red ball near (394, 202).
        val targetEnd = shot.targetLine.start
        assertTrue(
            "target is not the red ball: $targetEnd",
            targetEnd.x in 360.0..430.0 && targetEnd.y in 170.0..235.0
        )
        // The pocket should be the top-right corner pocket near (675, 51).
        val pocket = shot.targetLine.end
        assertTrue(
            "pocket is not the top-right corner: $pocket",
            pocket.x in 620.0..720.0 && pocket.y in 20.0..90.0
        )
        assertTrue("cut angle out of range: ${shot.cutAngleDegrees}", shot.cutAngleDegrees in 20.0..45.0)
        assertFalse("shot must not be a thin cut", shot.isThinCut)
        // All-shots ranking: the best shot is allShots.first(), and the
        // green table has more than one reachable pocket.
        assertTrue("allShots must not be empty", result.allShots.isNotEmpty())
        assertEquals("best shot must be allShots.first()", shot, result.allShots.first())
        assertTrue("expected >1 candidate shot, got ${result.allShots.size}", result.allShots.size > 1)
    }

    @Test
    fun blueTable_detectsTableAndShot() {
        val mat = loadDemoImage("demo_table_blue.png")
        val pipeline = CvPipeline(1280, 720)
        var result = pipeline.process(mat)
        repeat(5) { result = pipeline.process(mat) }

        assertFalse("blue table must not be black", result.isCaptureBlack)
        assertNotNull("blue table not found", result.table)
        assertNotNull("blue cue ball not found", result.cueBall)
        assertNotNull("no shot on blue table: ${result.status}", result.shot)
    }

    @Test
    fun blackFrame_isReportedAsSecure() {
        val mat = loadDemoImage("demo_black.png")
        val pipeline = CvPipeline(1280, 720)
        val result = pipeline.process(mat)
        assertTrue("black frame must be reported", result.isCaptureBlack)
        assertTrue("black frame must have no shot", result.shot == null)
    }
}
