package com.poolguide.tool

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.poolguide.tool.cv.CvPipeline
import com.poolguide.tool.cv.DetectionResult
import com.poolguide.tool.cv.OpenCvInit
import com.poolguide.tool.geometry.BallGroup
import com.poolguide.tool.geometry.Vec2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    /** Undirected angle of the longest detected guideline segment, in
     * [-90, 90] degrees. The demo tables draw the guideline as the
     * true aim ray (v1.0.28), so the fitted angle must match the
     * solver's aim angle for the depicted shot (pinned by a kotlinc
     * probe on ShotSolver.ghostPoint before the test was written:
     * green 2.7048 deg, blue -29.0394 deg, bank 63.3387 deg). A
     * Hough segment has no direction, so the angle is normalised
     * to [-90, 90]; the 1.5 deg tolerance is 5x the measured fit
     * error (<= 0.3 deg) and still catches the old chord, which
     * sat 3.01 deg (green) / 2.05 deg (blue) off the aim. */
    private fun guidelineAngleDeg(result: DetectionResult): Double {
        val seg = result.guideline.maxByOrNull { it.segment.length() }?.segment
            ?: return Double.NaN
        var angle = Math.toDegrees(
            kotlin.math.atan2(seg.end.y - seg.start.y, seg.end.x - seg.start.x)
        )
        angle %= 180.0
        if (angle > 90.0) angle -= 180.0
        if (angle < -90.0) angle += 180.0
        return angle
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
        // v1.0.28: the demo guideline is the true aim ray, so the
        // fitted angle must be the solver's aim angle (2.7048 deg).
        val greenAngle = guidelineAngleDeg(result)
        assertTrue(
            "guideline angle must be the true aim ~2.70 deg, got $greenAngle",
            kotlin.math.abs(greenAngle - 2.7048) < 1.5
        )
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
    fun greenRerankTable_showsFirstClearPocket() {
        // v1.0.21: same layout as the green table, but the orange
        // 13-ball is moved off the bottom-right target line, so the
        // best pocket (top-right, cut ~31 deg) stays blocked by the
        // yellow 1-ball while the bottom-right pocket (cut ~31 deg)
        // is clear. The pipeline must show the bottom-right shot,
        // with no blocked flag — the blocked best stays a faint
        // candidate, it does not hide the makeable pocket.
        val mat = loadDemoImage("demo_table_green_rerank.png")
        val pipeline = CvPipeline(1280, 720)
        var result = pipeline.process(mat)
        repeat(5) { result = pipeline.process(mat) }

        assertFalse("rerank table must not be black", result.isCaptureBlack)
        assertNotNull("table not found", result.table)
        assertEquals("6 pockets expected", 6, result.pockets.size)
        assertNotNull("cue ball not found", result.cueBall)
        assertTrue("expected >= 8 balls, got ${result.balls.size}", result.balls.size >= 8)
        assertTrue("guideline not found", result.guideline.isNotEmpty())
        assertNotNull("no shot solved: ${result.status}", result.shot)
        val shot = result.shot!!
        // The solved target is still the red ball near (394, 202).
        val targetEnd = shot.targetLine.start
        assertTrue(
            "target is not the red ball: $targetEnd",
            targetEnd.x in 360.0..430.0 && targetEnd.y in 170.0..235.0
        )
        // The pocket is the BOTTOM-right corner near (676, 355),
        // not the blocked top-right one.
        val pocket = shot.targetLine.end
        assertTrue(
            "pocket is not the bottom-right corner: $pocket",
            pocket.x in 620.0..720.0 && pocket.y in 320.0..390.0
        )
        assertTrue("cut angle out of range: ${shot.cutAngleDegrees}", shot.cutAngleDegrees in 20.0..45.0)
        assertFalse("shot must not be a thin cut", shot.isThinCut)
        // The shown shot is allShots.first(), and the blocked
        // top-right shot is still among the candidates.
        assertTrue("allShots must not be empty", result.allShots.isNotEmpty())
        assertEquals("best shot must be allShots.first()", shot, result.allShots.first())
        assertTrue("expected >1 candidate shot, got ${result.allShots.size}", result.allShots.size > 1)
        assertTrue(
            "blocked top-right shot must stay a candidate: ${result.allShots.map { it.targetLine.end }}",
            result.allShots.any {
                it.targetLine.end.x in 620.0..720.0 && it.targetLine.end.y in 20.0..90.0
            }
        )
        // The shown (bottom-right) path is clear: no flag, and the
        // status line says nothing about a blocked path.
        assertNull("shown shot must not be flagged blocked", result.blockedBy)
        assertFalse("status must not say the path is blocked", result.status.contains("path blocked"))
    }

    @Test
    fun greenBankRerankTable_showsFirstClearBank() {
        // v1.0.22: only three balls, and the red 3-ball is frozen
        // near the top rail, so no direct shot solves. The best
        // bank (cut ~6 deg, off the top rail into the top-left
        // pocket) is blocked by the yellow 1-ball on its second
        // leg, so the pipeline must show the next clear bank
        // (cut ~67 deg, into the bottom-middle pocket), with no
        // blocked flag. The blocked best stays a candidate, it
        // does not hide the makeable bank.
        val mat = loadDemoImage("demo_table_green_bank_rerank.png")
        val pipeline = CvPipeline(1280, 720)
        var result = pipeline.process(mat)
        repeat(5) { result = pipeline.process(mat) }

        assertFalse("bank rerank table must not be black", result.isCaptureBlack)
        assertNotNull("table not found", result.table)
        assertEquals("6 pockets expected", 6, result.pockets.size)
        assertNotNull("cue ball not found", result.cueBall)
        assertTrue("expected >= 3 balls, got ${result.balls.size}", result.balls.size >= 3)
        // All three placed balls must be present: the cue near
        // (64, 98), the red target near (64, 60), and the yellow
        // blocker near (59, 126).
        for (expected in listOf(Vec2(64.5, 97.6), Vec2(64.5, 60.0), Vec2(58.7, 126.3))) {
            assertTrue(
                "expected a ball near $expected, got ${result.balls.map { it.center }}",
                result.balls.any { it.center.distanceTo(expected) < 30.0 }
            )
        }
        assertTrue("guideline not found", result.guideline.isNotEmpty())
        // v1.0.28: the demo guideline is the true aim ray, so the
        // fitted angle must be the solver's aim angle (63.3387 deg,
        // the undirected form of -116.6613 deg).
        val bankAngle = guidelineAngleDeg(result)
        assertTrue(
            "guideline angle must be the true aim ~63.34 deg, got $bankAngle",
            kotlin.math.abs(bankAngle - 63.3387) < 1.5
        )
        assertNull("no direct shot must solve: ${result.status}", result.shot)
        assertNull("no kick must solve: ${result.status}", result.kick)
        assertNotNull("no bank solved: ${result.status}", result.bank)
        val bank = result.bank!!
        // The solved target is the red ball near (64, 60).
        val targetEnd = bank.direct.targetLine.start
        assertTrue(
            "target is not the red ball: $targetEnd",
            targetEnd.x in 40.0..90.0 && targetEnd.y in 40.0..85.0
        )
        // The shown bank is the CLEAR one (cut ~67 deg), not the
        // blocked best (~6 deg).
        assertTrue(
            "cut angle out of range: ${bank.direct.cutAngleDegrees}",
            bank.direct.cutAngleDegrees in 60.0..80.0
        )
        // The rail contact is on the top rail, near (64, 51).
        assertTrue(
            "rail contact is not on the top rail: ${bank.railContact}",
            bank.railContact.x in 60.0..90.0 && bank.railContact.y in 40.0..65.0
        )
        // The ghost is where the cue aims, near (52, 73).
        val ghost = bank.direct.ghostPoint
        assertTrue(
            "ghost is not where the cue aims: $ghost",
            ghost.x in 40.0..65.0 && ghost.y in 60.0..85.0
        )
        // The second leg ends at the REAL pocket (bottom-middle,
        // near (360, 355)), not the mirrored one.
        assertEquals("bank path must have 2 legs", 2, bank.bankPath.size)
        val pocketEnd = bank.bankPath[1].end
        assertTrue(
            "second leg does not end at the bottom-middle pocket: $pocketEnd",
            pocketEnd.x in 340.0..380.0 && pocketEnd.y in 340.0..370.0
        )
        // The shown bank's path is clear: no flag, and the status
        // line names the bank shot without saying it is blocked.
        assertNull("shown bank must not be flagged blocked", result.blockedBy)
        assertTrue("status must say bank shot: ${result.status}", result.status.contains("Bank shot (one rail)"))
        assertFalse("status must not say the path is blocked", result.status.contains("path blocked"))
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
        // v1.0.20: the cue is the REAL cue ball, not the red 11-ball's
        // white number band. The band detects as a WHITE ball at the
        // stripe ball's centre ~(506,253) but its disc samples STRIPE,
        // while the real cue at ~(168,281) samples CUE — the
        // group-first pick (CuePicker) takes the real cue.
        val cue = result.cueBall!!
        assertTrue(
            "cue must be the real cue ball, not the stripe band: ${cue.center}",
            cue.center.distanceTo(Vec2(168.5, 281.5)) < 10.0
        )
        assertEquals(BallGroup.CUE, cue.group)
        // The band phantom is still detected (it IS the 11-ball, just
        // mis-coloured) but it is tagged STRIPE, never picked as cue.
        val phantom = result.balls.firstOrNull {
            it.center.distanceTo(Vec2(506.5, 253.5)) < 10.0
        }
        assertNotNull("stripe band phantom must still be detected", phantom)
        assertEquals(BallGroup.STRIPE, phantom!!.group)
        // The depicted shot is now real-cue -> red solid -> top-right
        // pocket, cut ~16.8 deg (harness replica, probe20) — AND it
        // is flagged blocked: the green ball at (357.2, 149.6) sits
        // 12.6 px off the aim line, inside the one-diameter threshold
        // 2r = 17.1 px, so it is genuinely on the path. (v1.0.28: the
        // ball moved from (281.0, 196.6) along the solved aim line so
        // the red ball still wins the target pick now that the
        // guideline is the true aim ray; it sits exactly as far off
        // the solved aim line as before. An earlier version of this
        // test asserted the path clear from a probe that read a key
        // the harness replica never computes (the replica has no
        // blocked-path check) and CI proved it wrong: blockedBy was
        // the green ball. The flag, not a clear path, is the correct
        // expectation, and the blue table now proves the flag on the
        // real depicted shot, not just the green table's 1-px-off case.)
        // v1.0.28: the demo guideline is the true aim ray, so the
        // fitted angle must be the solver's aim angle (-29.0394 deg).
        val blueAngle = guidelineAngleDeg(result)
        assertTrue(
            "guideline angle must be the true aim ~-29.04 deg, got $blueAngle",
            kotlin.math.abs(blueAngle - (-29.0394)) < 1.5
        )
        val shot = result.shot!!
        assertTrue(
            "blue cut angle must be ~16.8 deg, got ${shot.cutAngleDegrees}",
            kotlin.math.abs(shot.cutAngleDegrees - 16.75) < 2.0
        )
        assertNotNull("blue shot must be flagged blocked", result.blockedBy)
        val blocker = result.blockedBy!!
        assertTrue(
            "blocker is not the green ball: $blocker",
            blocker.distanceTo(Vec2(357.2, 149.6)) < 10.0
        )
        assertTrue("status must say the path is blocked", result.status.contains("path blocked"))
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
