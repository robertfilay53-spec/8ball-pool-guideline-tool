package com.poolguide.tool

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.poolguide.tool.cv.DetectionResult
import com.poolguide.tool.data.CalibrationStore
import com.poolguide.tool.geometry.CutAngleLabel
import com.poolguide.tool.geometry.Segment
import com.poolguide.tool.geometry.ShotSolver
import com.poolguide.tool.geometry.Vec2
import com.poolguide.tool.overlay.Anchor
import com.poolguide.tool.overlay.LineOverlayManager
import com.poolguide.tool.overlay.OverlayService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
            // v1.0.3: the overlay draws nothing until the table is
            // locked; the fabricated result must say it is.
            tableLocked = true,
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
    fun tableLockGate_hidesLinesUntilLocked() {
        val ctx = appContext()
        assertTrue(
            "overlay permission must be granted for this test",
            Settings.canDrawOverlays(ctx)
        )
        val store = CalibrationStore(ctx).apply { guidesHidden = false }
        val manager = LineOverlayManager(ctx, store)
        val instrumentation = InstrumentationRegistry.getInstrumentation()

        // Unlocked table: no windows at all, even with a solved shot.
        val unlocked = shotResult().copy(tableLocked = false)
        instrumentation.runOnMainSync { manager.update(unlocked) }
        assertEquals("unlocked table must draw no lines", 0, manager.lineWindowCount)

        // Locked table: the same result draws all 8 windows.
        instrumentation.runOnMainSync { manager.update(shotResult()) }
        assertEquals("locked table must draw lines", 8, manager.lineWindowCount)

        // Losing the lock hides them again (table moved / lost).
        instrumentation.runOnMainSync { manager.update(unlocked) }
        assertEquals("lost lock must hide lines again", 0, manager.lineWindowCount)

        instrumentation.runOnMainSync { manager.removeAll() }
    }

    @Test
    fun editMode_freezesShotShowsHandlesAndSurvivesGuidelineLoss() {
        val ctx = appContext()
        assertTrue(
            "overlay permission must be granted for this test",
            Settings.canDrawOverlays(ctx)
        )
        val store = CalibrationStore(ctx).apply {
            guidesHidden = false
            showAllShots = true
        }
        val manager = LineOverlayManager(ctx, store)
        val instrumentation = InstrumentationRegistry.getInstrumentation()

        // Live mode: best shot + 2 faint candidates = 8 windows.
        instrumentation.runOnMainSync { manager.update(shotResult()) }
        assertEquals(8, manager.lineWindowCount)
        assertEquals(0, manager.handleWindowCount)
        assertFalse(manager.isEditMode)

        // Enter edit mode: best shot only (4 windows) + 2 handles.
        instrumentation.runOnMainSync { manager.toggleEditMode() }
        assertTrue(manager.isEditMode)
        assertEquals(4, manager.lineWindowCount)
        assertEquals(2, manager.handleWindowCount)

        // Drag the ghost anchor 10 px right (working coords): the
        // corrected aim line follows; the pocket stays the same.
        val frozen = shotResult().shot!!
        instrumentation.runOnMainSync {
            manager.applyAnchorDrag(Anchor.GHOST, Vec2(10.0, 0.0))
        }
        val edited = manager.editedShot!!
        assertEquals(frozen.ghostPoint.x + 10.0, edited.ghostPoint.x, 0.0)
        assertEquals(frozen.targetLine.end.x, edited.targetLine.end.x, 0.0)
        assertEquals(frozen.targetLine.end.y, edited.targetLine.end.y, 0.0)
        assertEquals(4, manager.lineWindowCount)

        // Edit mode keeps drawing even with no guideline (the user is
        // editing precisely because detection missed): 20 frames with
        // no guideline must not hide the lines.
        val noGuideline = shotResult().copy(guideline = emptyList())
        repeat(20) { instrumentation.runOnMainSync { manager.update(noGuideline) } }
        assertEquals(4, manager.lineWindowCount)
        assertEquals(2, manager.handleWindowCount)

        // Leaving edit mode resumes live detection (8 windows again).
        instrumentation.runOnMainSync { manager.toggleEditMode() }
        assertFalse(manager.isEditMode)
        assertEquals(0, manager.handleWindowCount)
        instrumentation.runOnMainSync { manager.update(shotResult()) }
        assertEquals(8, manager.lineWindowCount)

        instrumentation.runOnMainSync { manager.removeAll() }
    }

    @Test
    fun lockRing_showsTableLockProgress() {
        val ctx = appContext()
        assertTrue(
            "overlay permission must be granted for this test",
            Settings.canDrawOverlays(ctx)
        )
        val store = CalibrationStore(ctx).apply { guidesHidden = false }
        val manager = LineOverlayManager(ctx, store)
        val instrumentation = InstrumentationRegistry.getInstrumentation()

        // No bubble yet: no ring to read.
        assertEquals(-1f, manager.lockRingProgress, 0.001f)

        // A settling table shows its progress (halfway to the lock).
        instrumentation.runOnMainSync {
            manager.update(shotResult().copy(tableLocked = false, tableLockProgress = 0.5f))
        }
        assertEquals(0.5f, manager.lockRingProgress, 0.001f)

        // A locked table fills the ring.
        instrumentation.runOnMainSync {
            manager.update(shotResult().copy(tableLocked = true, tableLockProgress = 1f))
        }
        assertEquals(1f, manager.lockRingProgress, 0.001f)

        // No table at all empties it again.
        instrumentation.runOnMainSync {
            manager.update(shotResult().copy(tableLocked = false, tableLockProgress = 0f))
        }
        assertEquals(0f, manager.lockRingProgress, 0.001f)

        instrumentation.runOnMainSync { manager.removeAll() }
        assertEquals(-1f, manager.lockRingProgress, 0.001f)
    }

    @Test
    fun lockRing_freezesInEditMode() {
        val ctx = appContext()
        assertTrue(
            "overlay permission must be granted for this test",
            Settings.canDrawOverlays(ctx)
        )
        val store = CalibrationStore(ctx).apply { guidesHidden = false }
        val manager = LineOverlayManager(ctx, store)
        val instrumentation = InstrumentationRegistry.getInstrumentation()

        // Live: locked table, ring full and not frozen.
        instrumentation.runOnMainSync {
            manager.update(shotResult().copy(tableLocked = true, tableLockProgress = 1f))
        }
        assertEquals(1f, manager.lockRingProgress, 0.001f)
        assertFalse(manager.lockRingFrozen)

        // Enter EDIT mode: the ring switches to its frozen state even
        // though the live lock progress is still 1.0.
        instrumentation.runOnMainSync { manager.toggleEditMode() }
        assertTrue(manager.isEditMode)
        assertTrue(manager.lockRingFrozen)

        // Later frames must not unfreeze it: a settling table (0.5)
        // still shows the frozen state, not live progress.
        instrumentation.runOnMainSync {
            manager.update(shotResult().copy(tableLocked = false, tableLockProgress = 0.5f))
        }
        assertTrue(manager.lockRingFrozen)

        // Leaving EDIT mode unfreezes the ring and live progress
        // resumes.
        instrumentation.runOnMainSync { manager.toggleEditMode() }
        assertFalse(manager.isEditMode)
        assertFalse(manager.lockRingFrozen)
        instrumentation.runOnMainSync {
            manager.update(shotResult().copy(tableLocked = false, tableLockProgress = 0.5f))
        }
        assertEquals(0.5f, manager.lockRingProgress, 0.001f)

        instrumentation.runOnMainSync { manager.removeAll() }
    }

    @Test
    fun editMode_withNoShotDoesNothing() {
        val ctx = appContext()
        assertTrue(
            "overlay permission must be granted for this test",
            Settings.canDrawOverlays(ctx)
        )
        val store = CalibrationStore(ctx).apply { guidesHidden = false }
        val manager = LineOverlayManager(ctx, store)
        val instrumentation = InstrumentationRegistry.getInstrumentation()

        // No update() call yet, so there is no shot to edit.
        instrumentation.runOnMainSync { manager.toggleEditMode() }
        assertFalse(manager.isEditMode)
        assertEquals(0, manager.handleWindowCount)
        assertEquals(0, manager.lineWindowCount)

        instrumentation.runOnMainSync { manager.removeAll() }
    }

    /** Thin-cut result (v1.0.12): cue (620, 260), target (620, 300)
     * solves to the bottom-right pocket at 82.83 deg — thin, but not
     * impossible. Verified against the solver with kotlinc before the
     * test was written (cut12-probe/Probe12.kt). */
    private fun thinShotResult(): DetectionResult {
        val shot = ShotSolver.bestShot(
            Vec2(620.0, 260.0), Vec2(620.0, 300.0),
            listOf(
                Vec2(40.0, 20.0), Vec2(680.0, 20.0), Vec2(680.0, 340.0),
                Vec2(40.0, 340.0), Vec2(360.0, 20.0), Vec2(360.0, 340.0)
            ),
            9.0
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
                    Segment(Vec2(620.0, 260.0), Vec2(620.0, 300.0)), 40
                )
            ),
            shot = shot,
            bank = null,
            allShots = listOf(shot),
            tableLocked = true,
            status = "Shot to pocket"
        )
    }

    @Test
    fun cutAngleLabel_showsWithLinesAndHidesWithThem() {
        val ctx = appContext()
        assertTrue(
            "overlay permission must be granted for this test",
            Settings.canDrawOverlays(ctx)
        )
        val store = CalibrationStore(ctx).apply { guidesHidden = false }
        val manager = LineOverlayManager(ctx, store)
        val instrumentation = InstrumentationRegistry.getInstrumentation()

        // A direct shot draws 4 line windows + 1 angle label (the
        // shot is not thin, so no warning). The label text is the
        // shot's own cut angle, one decimal.
        val result = shotResult()
        store.showAllShots = false
        instrumentation.runOnMainSync { manager.update(result) }
        assertEquals(4, manager.lineWindowCount)
        assertEquals(1, manager.labelWindowCount)
        assertEquals(
            listOf(CutAngleLabel.format(result.shot!!.cutAngleDegrees)),
            manager.labelTexts
        )

        // Hide toggle removes the label with the lines.
        store.guidesHidden = true
        instrumentation.runOnMainSync { manager.update(result) }
        assertEquals(0, manager.lineWindowCount)
        assertEquals("hide must remove the label too", 0, manager.labelWindowCount)

        // Unhide brings both back.
        store.guidesHidden = false
        instrumentation.runOnMainSync { manager.update(result) }
        assertEquals(4, manager.lineWindowCount)
        assertEquals(1, manager.labelWindowCount)

        // Black frame removes both as well.
        val black = result.copy(isCaptureBlack = true, shot = null, guideline = emptyList())
        instrumentation.runOnMainSync { manager.update(black) }
        assertEquals("black frame must remove all line windows", 0, manager.lineWindowCount)
        assertEquals("black frame must remove the label too", 0, manager.labelWindowCount)

        instrumentation.runOnMainSync { manager.removeAll() }
        assertEquals(0, manager.labelWindowCount)
    }

    @Test
    fun thinCut_showsWarningLabel() {
        val ctx = appContext()
        assertTrue(
            "overlay permission must be granted for this test",
            Settings.canDrawOverlays(ctx)
        )
        val store = CalibrationStore(ctx).apply { guidesHidden = false }
        val manager = LineOverlayManager(ctx, store)
        val instrumentation = InstrumentationRegistry.getInstrumentation()

        // A thin shot draws the angle label AND the warning label.
        val result = thinShotResult()
        assertTrue(result.shot!!.isThinCut)
        instrumentation.runOnMainSync { manager.update(result) }
        assertEquals(4, manager.lineWindowCount)
        assertEquals("thin shot needs angle + warning labels", 2, manager.labelWindowCount)
        assertEquals(
            listOf("82.8°", "Thin cut 82.8°"),
            manager.labelTexts
        )

        instrumentation.runOnMainSync { manager.removeAll() }
        assertEquals(0, manager.labelWindowCount)
    }

    @Test
    fun noShot_showsNoLabels() {
        val ctx = appContext()
        assertTrue(
            "overlay permission must be granted for this test",
            Settings.canDrawOverlays(ctx)
        )
        val store = CalibrationStore(ctx).apply { guidesHidden = false }
        val manager = LineOverlayManager(ctx, store)
        val instrumentation = InstrumentationRegistry.getInstrumentation()

        // No shot, no bank, no kick: no lines and no labels, even
        // with a guideline and a locked table.
        val noShot = shotResult().copy(
            shot = null, bank = null, kick = null, bank2 = null, kick2 = null,
            allShots = emptyList()
        )
        instrumentation.runOnMainSync { manager.update(noShot) }
        assertEquals(0, manager.lineWindowCount)
        assertEquals("no shot must draw no labels", 0, manager.labelWindowCount)

        instrumentation.runOnMainSync { manager.removeAll() }
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
