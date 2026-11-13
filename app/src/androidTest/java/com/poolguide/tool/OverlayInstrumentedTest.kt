package com.poolguide.tool

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.poolguide.tool.capture.CaptureService
import com.poolguide.tool.cv.DetectionResult
import com.poolguide.tool.data.CalibrationStore
import com.poolguide.tool.geometry.Segment
import com.poolguide.tool.geometry.ShotSolver
import com.poolguide.tool.geometry.Vec2
import com.poolguide.tool.overlay.LineOverlayManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Overlay + service lifecycle tests. Run on an emulator with the overlay
 * permission granted first:
 *   adb shell appops set --uid <package> SYSTEM_ALERT_WINDOW allow
 * (the CI workflow does this before connectedAndroidTest).
 */
@RunWith(AndroidJUnit4::class)
class OverlayInstrumentedTest {

    private fun appContext(): Context =
        InstrumentationRegistry.getInstrumentation().targetContext

    private fun shotResult(): DetectionResult {
        val shot = ShotSolver.solveShot(
            Vec2(100.0, 200.0), Vec2(300.0, 200.0), Vec2(600.0, 50.0), 9.0
        )!!
        return DetectionResult(
            frameIndex = 1,
            workingWidth = 720,
            workingHeight = 405,
            scaleToScreenX = 1.0,
            scaleToScreenY = 1.0,
            isCaptureBlack = false,
            table = null,
            pockets = emptyList(),
            balls = emptyList(),
            cueBall = null,
            guideline = listOf(
                com.poolguide.tool.cv.GuidelineSegment(
                    Segment(Vec2(100.0, 200.0), Vec2(300.0, 200.0)), 40
                )
            ),
            shot = shot,
            bank = null,
            status = "Shot to pocket"
        )
    }

    @Test
    fun overlay_showsOneSmallWindowPerLine_thenHides() {
        val ctx = appContext()
        assertTrue(
            "overlay permission must be granted for this test",
            Settings.canDrawOverlays(ctx)
        )
        val store = CalibrationStore(ctx).apply { guidesHidden = false }
        val manager = LineOverlayManager(ctx, store)
        val instrumentation = InstrumentationRegistry.getInstrumentation()

        // A shot draws 4 windows: aim, target, deflection, pocket ring.
        instrumentation.runOnMainSync { manager.update(shotResult()) }
        assertEquals("expected 4 line windows", 4, manager.lineWindowCount)

        // Hide toggle removes them all.
        store.guidesHidden = true
        instrumentation.runOnMainSync { manager.update(shotResult()) }
        assertEquals("hide must remove all line windows", 0, manager.lineWindowCount)

        // Black frame removes them too.
        store.guidesHidden = false
        instrumentation.runOnMainSync { manager.update(shotResult()) }
        assertEquals(4, manager.lineWindowCount)
        val black = shotResult().copy(isCaptureBlack = true, shot = null, guideline = emptyList())
        instrumentation.runOnMainSync { manager.update(black) }
        assertEquals("black frame must remove all line windows", 0, manager.lineWindowCount)

        instrumentation.runOnMainSync { manager.removeAll() }
        assertEquals(0, manager.lineWindowCount)
    }

    @Test
    fun demoService_startsAndStaysRunning() {
        val ctx = appContext()
        val start = Intent(ctx, CaptureService::class.java).apply {
            action = CaptureService.ACTION_DEMO
        }
        ctx.startForegroundService(start)
        // Let the demo loop run a few frames.
        Thread.sleep(3000)
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        @Suppress("DEPRECATION")
        val running = am.getRunningServices(Int.MAX_VALUE)
            .any { it.service.className == CaptureService::class.java.name }
        assertTrue("CaptureService should still be running in demo mode", running)

        val stop = Intent(ctx, CaptureService::class.java).apply {
            action = CaptureService.ACTION_STOP
        }
        ctx.startService(stop)
        Thread.sleep(1000)
    }
}
