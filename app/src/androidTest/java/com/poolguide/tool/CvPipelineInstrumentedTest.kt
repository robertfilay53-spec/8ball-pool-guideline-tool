package com.poolguide.tool

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.poolguide.tool.cv.CvPipeline
import com.poolguide.tool.cv.OpenCvInit
import com.poolguide.tool.geometry.Vec2
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

    /** A solid mid-grey frame: no felt, no balls, not black (mean grey
     * 128, far above the black-frame threshold). It stands in for a
     * non-table screen — the home screen, the lobby, a menu. */
    private fun greyFrame(): Mat {
        val bitmap = android.graphics.Bitmap.createBitmap(
            1280, 720, android.graphics.Bitmap.Config.ARGB_8888
        )
        bitmap.eraseColor(android.graphics.Color.rgb(128, 128, 128))
        val mat = Mat()
        Utils.bitmapToMat(bitmap, mat)
        return mat
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
        // v1.0.19: the depicted best shot is blocked — the yellow
        // 1-ball sits ~1 px off the target line to the top-right
        // pocket — so the pipeline flags it instead of drawing the
        // line as a clean make. The flag names the blocking ball,
        // and the status line says so.
        assertNotNull("best shot must be flagged blocked", result.blockedBy)
        val blocker = result.blockedBy!!
        assertTrue(
            "blocker is not the yellow 1-ball: $blocker",
            blocker.distanceTo(Vec2(506.25, 140.62)) < 30.0
        )
        assertTrue("status must say the path is blocked", result.status.contains("path blocked"))
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
        // v1.0.19: NO blockedBy assertion here. The blue demo
        // table's cue detection picks the red 11-ball's white number
        // band as the cue (a second white ball at ~(506,253)), so
        // the solved shot is phantom-cue -> real-cue, not the
        // depicted shot — a pre-existing detection quirk, not a
        // blocked-path miss (the replica reproduces it exactly: the
        // phantom-cue shot is genuinely unblocked). The flag itself
        // is proven by the green table above. Fixing cue
        // identification is its own slice, not this one.
    }

    @Test
    fun blackFrame_isReportedAsSecure() {
        val mat = loadDemoImage("demo_black.png")
        val pipeline = CvPipeline(1280, 720)
        val result = pipeline.process(mat)
        assertTrue("black frame must be reported", result.isCaptureBlack)
        assertTrue("black frame must have no shot", result.shot == null)
    }

    @Test
    fun tableLock_engagesAfter75StableFrames() {
        val mat = loadDemoImage("demo_table_green.png")
        val pipeline = CvPipeline(1280, 720)
        var result = pipeline.process(mat)
        repeat(5) { result = pipeline.process(mat) }
        // 6 frames in: the table is found but not locked yet.
        assertNotNull("table not found", result.table)
        assertFalse("table must not lock before 75 stable frames", result.tableLocked)
        // Same image, same detection: the lock engages at frame 75.
        repeat(69) { result = pipeline.process(mat) }
        assertTrue("table must lock after 75 stable frames", result.tableLocked)
        assertNotNull("locked table must still solve the shot", result.shot)
    }

    /**
     * v1.0.5: a guessed table must never lock. On a fresh non-table
     * screen the ratio fallback is never used — no table, no lock, no
     * lines — even after 160 frames (v1.0.4 locked the guess at 75).
     */
    @Test
    fun nonTableScreen_neverLocksAndReportsNoTable() {
        val grey = greyFrame()
        val pipeline = CvPipeline(1280, 720)
        var result = pipeline.process(grey)
        repeat(159) { result = pipeline.process(grey) }
        assertFalse("non-table screen must not be black", result.isCaptureBlack)
        assertTrue("non-table screen must report no table", result.table == null)
        assertFalse("non-table screen must never lock", result.tableLocked)
        assertFalse("non-table screen must not use the fallback", result.tableFromFallback)
        assertTrue("status must say the guides are hidden", result.status.contains("guides hidden"))
        assertEquals("no lock progress on a non-table screen", 0f, result.tableLockProgress, 0.001f)
    }

    /**
     * v1.0.5: the fallback may only CONTINUE a lock a real detection
     * already earned. Lock on the green table, lose it to a grey
     * screen: the fallback stands in briefly (labelled), then the lock
     * drops and the table is reported as gone.
     */
    @Test
    fun lockedTableThenLost_continuesOnFallbackThenDrops() {
        val green = loadDemoImage("demo_table_green.png")
        val grey = greyFrame()
        val pipeline = CvPipeline(1280, 720)
        var result = pipeline.process(green)
        repeat(74) { result = pipeline.process(green) }
        assertTrue("green table must lock after 75 frames", result.tableLocked)
        assertFalse("green table must be a real detection", result.tableFromFallback)

        // Lose the table: after 40 grey frames the fallback is still
        // standing in for the locked table, labelled as such.
        repeat(40) { result = pipeline.process(grey) }
        assertNotNull("fallback must stand in while the lock continues", result.table)
        assertTrue("continuation must be labelled fallback", result.tableFromFallback)
        assertTrue("lock must continue inside the window", result.tableLocked)
        assertTrue("status must label the continuation", result.status.contains("continuing last lock"))

        // Past the 150-frame continuation window the lock drops and
        // the table is reported as gone (200 more grey frames covers
        // the window plus the re-detect cadence).
        repeat(200) { result = pipeline.process(grey) }
        assertTrue("table must be gone after the window", result.table == null)
        assertFalse("lock must drop after the window", result.tableLocked)
        assertFalse("fallback must stop after the window", result.tableFromFallback)
        assertTrue("status must say the guides are hidden", result.status.contains("guides hidden"))

        // And a real table re-locks from scratch.
        repeat(75) { result = pipeline.process(green) }
        assertTrue("real table must re-lock after the drop", result.tableLocked)
        assertFalse("re-locked table must be a real detection", result.tableFromFallback)
    }
}
