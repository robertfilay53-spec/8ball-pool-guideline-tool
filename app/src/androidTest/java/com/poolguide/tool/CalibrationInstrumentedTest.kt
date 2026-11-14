package com.poolguide.tool

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.poolguide.tool.cv.CalibrationPreview
import com.poolguide.tool.cv.OpenCvInit
import com.poolguide.tool.cv.TableDetector
import com.poolguide.tool.data.CalibrationStore
import com.poolguide.tool.data.GameProfile
import com.poolguide.tool.geometry.Vec2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc

/**
 * Calibration persistence + preview detection state (v1.0.8).
 *
 * Note on the "impossible window" test: TableDetector.detect tries
 * green, then blue, then the brown window, so a bad brown window alone
 * cannot hide a green demo table from detect(). The honest check for
 * the brown window itself is the brown mask: an impossible window
 * matches ~nothing on the demo frame, so its mask coverage is ~0 and
 * no brown contour is big enough to be a table. The default-profile
 * test below uses detect() (table found on the demo frame).
 */
@RunWith(AndroidJUnit4::class)
class CalibrationInstrumentedTest {

    @Before
    fun loadOpenCv() {
        assertTrue("OpenCV failed to load", OpenCvInit.ensureLoaded())
    }

    private fun loadDemoHsv(): Mat {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        targetContext.assets.open("demo/demo_table_green.png").use { stream ->
            val bitmap = BitmapFactory.decodeStream(stream)
            assertNotNull("demo image failed to decode", bitmap)
            val rgba = Mat()
            Utils.bitmapToMat(bitmap, rgba)
            val bgr = Mat()
            val hsv = Mat()
            Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
            Imgproc.cvtColor(bgr, hsv, Imgproc.COLOR_BGR2HSV)
            return hsv
        }
    }

    /** Brown-only mask coverage + largest brown contour area for a window. */
    private fun brownMaskStats(hsv: Mat, lower: DoubleArray, upper: DoubleArray): Pair<Double, Double> {
        val mask = Mat()
        Core.inRange(hsv, Scalar(lower), Scalar(upper), mask)
        val coverage = CalibrationPreview.maskCoverage(
            Core.countNonZero(mask).toLong(),
            (mask.rows() * mask.cols()).toLong()
        )
        val contours = mutableListOf<MatOfPoint>()
        Imgproc.findContours(mask, contours, Mat(), Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
        val biggest = contours.maxOfOrNull { Imgproc.contourArea(it) } ?: 0.0
        return coverage to biggest
    }

    @Test
    fun calibration_persistsBrownWindowProfileAndCorners() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = CalibrationStore(context)
        val version = store.gameVersion
        // Start clean for this version.
        store.clearBrownFeltWindow(version)
        store.clearGameProfile(version)
        store.clearTableCorners(version)

        val lower = doubleArrayOf(10.0, 120.0, 90.0)
        val upper = doubleArrayOf(25.0, 255.0, 255.0)
        store.setBrownFeltWindow(lower, upper, version)
        val profile = GameProfile(version, 0.01, 0.03, 12.0,
            brownHLo = 10.0, brownHHi = 25.0, brownSLo = 120.0,
            brownSHi = 255.0, brownVLo = 90.0, brownVHi = 255.0)
        store.setGameProfile(profile)
        val corners = listOf(
            Vec2(0.1, 0.2), Vec2(0.9, 0.2), Vec2(0.9, 0.8), Vec2(0.1, 0.8)
        )
        store.setTableCorners(corners, version)

        val readBack = store.getGameProfile(version)
        assertEquals(0.01, readBack.pocketInsetFraction, 0.0)
        assertEquals(0.03, readBack.pocketRadiusFraction, 0.0)
        assertEquals(12.0, readBack.ballRadiusPxAt720, 0.0)
        assertEquals(10.0, readBack.brownHLo, 0.0)
        assertEquals(25.0, readBack.brownHHi, 0.0)
        assertEquals(120.0, readBack.brownSLo, 0.0)
        assertEquals(255.0, readBack.brownSHi, 0.0)
        assertEquals(90.0, readBack.brownVLo, 0.0)
        assertEquals(255.0, readBack.brownVHi, 0.0)
        val readCorners = store.getTableCorners(version)
        assertNotNull(readCorners)
        assertEquals(4, readCorners!!.size)
        for (i in 0 until 4) {
            assertEquals(corners[i].x, readCorners[i].x, 0.001)
            assertEquals(corners[i].y, readCorners[i].y, 0.001)
        }

        // Reset clears all three; the profile falls back to the defaults.
        store.clearBrownFeltWindow(version)
        store.clearGameProfile(version)
        store.clearTableCorners(version)
        assertNull(store.getTableCorners(version))
        val reset = store.getGameProfile(version)
        assertEquals(GameProfile.DEFAULT.pocketInsetFraction, reset.pocketInsetFraction, 0.0)
        assertEquals(GameProfile.DEFAULT.pocketRadiusFraction, reset.pocketRadiusFraction, 0.0)
        assertEquals(GameProfile.DEFAULT.ballRadiusPxAt720, reset.ballRadiusPxAt720, 0.0)
        assertEquals(GameProfile.DEFAULT.brownHLo, reset.brownHLo, 0.0)
        assertEquals(GameProfile.DEFAULT.brownHHi, reset.brownHHi, 0.0)
        assertEquals(GameProfile.DEFAULT.brownSLo, reset.brownSLo, 0.0)
        assertEquals(GameProfile.DEFAULT.brownSHi, reset.brownSHi, 0.0)
        assertEquals(GameProfile.DEFAULT.brownVLo, reset.brownVLo, 0.0)
        assertEquals(GameProfile.DEFAULT.brownVHi, reset.brownVHi, 0.0)
    }

    @Test
    fun defaultProfile_detectsDemoTable() {
        val hsv = loadDemoHsv()
        val table = TableDetector().detect(hsv, GameProfile.DEFAULT)
        assertNotNull("default profile must find the demo table", table)
    }

    @Test
    fun impossibleBrownWindow_matchesNothingOnDemoFrame() {
        val hsv = loadDemoHsv()
        val (coverage, biggest) = brownMaskStats(
            hsv,
            doubleArrayOf(170.0, 250.0, 250.0),
            doubleArrayOf(179.0, 255.0, 255.0)
        )
        assertEquals("impossible window must match ~nothing", 0.0, coverage, 0.001)
        assertTrue("no brown contour can be a table: $biggest", biggest < 5_000)
        assertEquals(
            CalibrationPreview.PreviewStatus.NOT_FOUND,
            CalibrationPreview.decideStatus(false, coverage)
        )
    }
}
