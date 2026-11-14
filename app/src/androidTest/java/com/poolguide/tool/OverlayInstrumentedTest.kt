package com.poolguide.tool

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.poolguide.tool.cv.DetectionResult
import com.poolguide.tool.data.CalibrationStore
import com.poolguide.tool.geometry.Segment
import com.poolguide.tool.geometry.ShotSolver
import com.poolguide.tool.geometry.Vec2
import com.poolguide.tool.overlay.LineOverlayManager
import com.poolguide.tool.overlay.OverlayService
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
        // Three candidate shots to three pockets (same target ball),
        // ranked by ShotSolver.allShots — best first.
        val all = ShotSolver.allShots(
            Vec2(100.0, 200.0), Vec2(300.0, 200.0),
            listOf(Vec2(600.0, 50.0), Vec2(600.0, 350.0), Vec2(650.0, 200.0)),
            9.0
        )
        val shot = all.first()
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
            allShots = all,
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
        // With "Show all shots" on, the 2 extra candidates add 2 faint
        // windows each (aim + target): 4 + 4 = 8.
        store.showAllShots = true
        instrumentation.runOnMainSync { manager.update(shotResult()) }
        assertEquals("expected 8 line windows with all shots", 8, manager.lineWindowCount)

        // Toggle off: only the best shot's 4 windows.
        store.showAllShots = false
        instrumentation.runOnMainSync { manager.update(shotResult()) }
        assertEquals("expected 4 line windows with all shots off", 4, manager.lineWindowCount)
        store.showAllShots = true

        // Hide toggle removes them all.
        store.guidesHidden = true
        instrumentation.runOnMainSync { manager.update(shotResult()) }
        assertEquals("hide must remove all line windows", 0, manager.lineWindowCount)

        // Black frame removes them too.
        store.guidesHidden = false
        instrumentation.runOnMainSync { manager.update(shotResult()) }
        assertEquals(8, manager.lineWindowCount)
        val black = shotResult().copy(isCaptureBlack = true, shot = null, guideline = emptyList())
        instrumentation.runOnMainSync { manager.update(black) }
        assertEquals("black frame must remove all line windows", 0, manager.lineWindowCount)

        instrumentation.runOnMainSync { manager.removeAll() }
        assertEquals(0, manager.lineWindowCount)
    }

    @Test
    fun demoService_startsAndStaysRunning() {
        val ctx = appContext()
        // Demo runs in the OverlayService alone (FGS split, v1.0.2):
        // specialUse, no MediaProjection token, no capture service.
        val start = Intent(ctx, OverlayService::class.java).apply {
            action = OverlayService.ACTION_DEMO
        }
        ctx.startForegroundService(start)
        // Let the demo loop run a few frames.
        Thread.sleep(3000)
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        @Suppress("DEPRECATION")
        val running = am.getRunningServices(Int.MAX_VALUE)
            .any { it.service.className == OverlayService::class.java.name }
        assertTrue("OverlayService should still be running in demo mode", running)

        val stop = Intent(ctx, OverlayService::class.java).apply {
            action = OverlayService.ACTION_STOP
        }
        ctx.startService(stop)
        Thread.sleep(1000)
    }
}
