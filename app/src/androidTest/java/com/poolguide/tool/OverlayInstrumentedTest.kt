package com.poolguide.tool

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.poolguide.tool.cv.Ball
import com.poolguide.tool.cv.BallColor
import com.poolguide.tool.cv.DetectionResult
import com.poolguide.tool.cv.TableRect
import com.poolguide.tool.data.CalibrationStore
import com.poolguide.tool.data.ShotHistory
import com.poolguide.tool.geometry.BallGroup
import com.poolguide.tool.geometry.CutAngleLabel
import com.poolguide.tool.geometry.Rail
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

    /** The same shot as [shotResult], but with three classified balls
     * (v1.0.14): the cue ball (C), the target ball (S), and an
     * unknown-group ball off the shot lines (?). The shot itself is
     * unchanged — the balls only feed the group tags. */
    private fun shotResultWithBalls(): DetectionResult {
        val cue = Ball(0, Vec2(100.0, 200.0), 9.0, BallColor.WHITE, false, BallGroup.CUE)
        val target = Ball(1, Vec2(300.0, 200.0), 9.0, BallColor.RED, false, BallGroup.SOLID)
        val unknown = Ball(2, Vec2(500.0, 100.0), 9.0, BallColor.UNKNOWN, false, BallGroup.UNKNOWN)
        return shotResult().copy(
            balls = listOf(cue, target, unknown),
            cueBall = cue
        )
    }

    @Test
    fun groupTags_showWithLines_hideWithLines() {
        val ctx = appContext()
        assertTrue(
            "overlay permission must be granted for this test",
            Settings.canDrawOverlays(ctx)
        )
        val store = CalibrationStore(ctx).apply { guidesHidden = false }
        val manager = LineOverlayManager(ctx, store)
        val instrumentation = InstrumentationRegistry.getInstrumentation()

        // One tag window per detected ball, in ball order — and the
        // pinned line/label counts do not move (5 lines, 2 labels:
        // angle + pocket distance — for this straight best shot).
        store.showAllShots = false
        instrumentation.runOnMainSync { manager.update(shotResultWithBalls()) }
        assertEquals("expected 5 line windows", 5, manager.lineWindowCount)
        assertEquals("expected 2 label windows", 2, manager.labelWindowCount)
        assertEquals("expected 3 tag windows", 3, manager.tagWindowCount)
        assertEquals(listOf("C", "S", "?"), manager.tagTexts)

        // Hide toggle removes the tags with the lines.
        store.guidesHidden = true
        instrumentation.runOnMainSync { manager.update(shotResultWithBalls()) }
        assertEquals("hide must remove all tag windows", 0, manager.tagWindowCount)
        assertEquals("hide must remove all line windows", 0, manager.lineWindowCount)

        // Black frame removes the tags too.
        store.guidesHidden = false
        instrumentation.runOnMainSync { manager.update(shotResultWithBalls()) }
        assertEquals(3, manager.tagWindowCount)
        val black = shotResultWithBalls().copy(
            isCaptureBlack = true, shot = null, guideline = emptyList()
        )
        instrumentation.runOnMainSync { manager.update(black) }
        assertEquals("black frame must remove all tag windows", 0, manager.tagWindowCount)

        instrumentation.runOnMainSync { manager.removeAll() }
        assertEquals(0, manager.tagWindowCount)
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

        // A shot draws 5 windows: aim, target, deflection, pocket ring, ghost ring.
        // With "Show all shots" on, the 2 extra candidates add 2 faint
        // windows each (aim + target): 5 + 4 = 9.
        store.showAllShots = true
        instrumentation.runOnMainSync { manager.update(shotResult()) }
        assertEquals("expected 9 line windows with all shots", 9, manager.lineWindowCount)

        // Toggle off: only the best shot's 5 windows.
        store.showAllShots = false
        instrumentation.runOnMainSync { manager.update(shotResult()) }
        assertEquals("expected 5 line windows with all shots off", 5, manager.lineWindowCount)
        store.showAllShots = true

        // Hide toggle removes them all.
        store.guidesHidden = true
        instrumentation.runOnMainSync { manager.update(shotResult()) }
        assertEquals("hide must remove all line windows", 0, manager.lineWindowCount)

        // Black frame removes them too.
        store.guidesHidden = false
        instrumentation.runOnMainSync { manager.update(shotResult()) }
        assertEquals(9, manager.lineWindowCount)
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

        // Locked table: the same result draws all 9 windows.
        instrumentation.runOnMainSync { manager.update(shotResult()) }
        assertEquals("locked table must draw lines", 9, manager.lineWindowCount)

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

        // Live mode: best shot + 2 faint candidates = 9 windows.
        instrumentation.runOnMainSync { manager.update(shotResult()) }
        assertEquals(9, manager.lineWindowCount)
        assertEquals(0, manager.handleWindowCount)
        assertFalse(manager.isEditMode)

        // Enter edit mode: best shot only (5 windows) + 2 handles.
        instrumentation.runOnMainSync { manager.toggleEditMode() }
        assertTrue(manager.isEditMode)
        assertEquals(5, manager.lineWindowCount)
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
        assertEquals(5, manager.lineWindowCount)

        // Edit mode keeps drawing even with no guideline (the user is
        // editing precisely because detection missed): 20 frames with
        // no guideline must not hide the lines.
        val noGuideline = shotResult().copy(guideline = emptyList())
        repeat(20) { instrumentation.runOnMainSync { manager.update(noGuideline) } }
        assertEquals(5, manager.lineWindowCount)
        assertEquals(2, manager.handleWindowCount)

        // Leaving edit mode resumes live detection (9 windows again).
        instrumentation.runOnMainSync { manager.toggleEditMode() }
        assertFalse(manager.isEditMode)
        assertEquals(0, manager.handleWindowCount)
        instrumentation.runOnMainSync { manager.update(shotResult()) }
        assertEquals(9, manager.lineWindowCount)

        instrumentation.runOnMainSync { manager.removeAll() }
    }

    @Test
    fun editMode_bank_freezesBankShowsLegsAndHandles() {
        val ctx = appContext()
        assertTrue(
            "overlay permission must be granted for this test",
            Settings.canDrawOverlays(ctx)
        )
        val store = CalibrationStore(ctx).apply {
            guidesHidden = false
            bankLinesEnabled = true
        }
        val manager = LineOverlayManager(ctx, store)
        val instrumentation = InstrumentationRegistry.getInstrumentation()

        // Live mode: the bank draws 6 windows (aim + 2 bank legs +
        // pocket ring + ghost ring + contact marker) and 2 labels
        // (angle + pocket distance).
        instrumentation.runOnMainSync { manager.update(bankResult()) }
        assertEquals(6, manager.lineWindowCount)
        assertEquals(2, manager.labelWindowCount)
        assertEquals(0, manager.handleWindowCount)
        assertFalse(manager.isEditMode)

        // Enter edit mode: the frozen bank draws the same 6 windows
        // (not a collapsed direct shot) + 2 handles.
        instrumentation.runOnMainSync { manager.toggleEditMode() }
        assertTrue(manager.isEditMode)
        assertEquals(6, manager.lineWindowCount)
        assertEquals(2, manager.handleWindowCount)
        val frozen = manager.editedBank!!
        assertEquals(313.5, frozen.direct.ghostPoint.x, 0.0)
        assertEquals(73.9, frozen.railContact.x, 0.0)

        // Drag the ghost anchor 2 px right (working coords): the aim
        // line follows, the contact moves along the rail, the pocket
        // stays the same.
        instrumentation.runOnMainSync {
            manager.applyAnchorDrag(Anchor.GHOST, Vec2(2.0, 0.0))
        }
        val edited = manager.editedBank!!
        assertEquals(315.5, edited.direct.ghostPoint.x, 0.0)
        assertEquals(40.4, edited.railContact.x, 0.0)
        assertEquals(40.0, edited.bankPath[1].end.x, 0.0)
        assertEquals(30.0, edited.bankPath[1].end.y, 0.0)
        assertEquals(6, manager.lineWindowCount)

        // Drag the ghost 10 px further right: the contact would fall
        // off the table, so the last good bank is kept.
        instrumentation.runOnMainSync {
            manager.applyAnchorDrag(Anchor.GHOST, Vec2(10.0, 0.0))
        }
        assertEquals(315.5, manager.editedBank!!.direct.ghostPoint.x, 0.0)
        assertEquals(40.4, manager.editedBank!!.railContact.x, 0.0)

        // Leaving edit mode resumes live detection (6 windows again).
        instrumentation.runOnMainSync { manager.toggleEditMode() }
        assertFalse(manager.isEditMode)
        assertEquals(0, manager.handleWindowCount)
        instrumentation.runOnMainSync { manager.update(bankResult()) }
        assertEquals(6, manager.lineWindowCount)

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

    /** One-rail bank result (v1.0.29): cue (450,320) -> target (300,200)
     * -> top rail -> pocket (40,30). Same numbers as ManualAimTest's
     * baseBank: ghost (313.5,211.9), cut 3.13 deg, contact (73.9,0.0).
     * No direct shot exists, so the shown shot is the bank. */
    private fun bankResult(): DetectionResult {
        val bank = ShotSolver.solveBank(
            Vec2(450.0, 320.0), Vec2(300.0, 200.0), Vec2(40.0, 30.0),
            Rail(Vec2(0.0, 0.0), Vec2(0.0, 1.0)), 9.0
        )!!
        return DetectionResult(
            frameIndex = 1,
            workingWidth = 720,
            workingHeight = 405,
            scaleToScreenX = 1.0,
            scaleToScreenY = 1.0,
            isCaptureBlack = false,
            table = TableRect(
                Vec2(0.0, 0.0), Vec2(720.0, 0.0),
                Vec2(720.0, 405.0), Vec2(0.0, 405.0)
            ),
            pockets = emptyList(),
            balls = emptyList(),
            cueBall = null,
            guideline = listOf(
                com.poolguide.tool.cv.GuidelineSegment(
                    Segment(Vec2(450.0, 320.0), Vec2(300.0, 200.0)), 40
                )
            ),
            shot = null,
            bank = bank,
            allShots = emptyList(),
            tableLocked = true,
            status = "Bank shot (one rail) to pocket"
        )
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

        // A direct shot draws 5 line windows + 2 labels (the angle
        // label and the pocket-distance label, v1.0.15 — the shot is
        // not thin, so no warning). The angle text is the shot's own
        // cut angle, one decimal; the distance is the total path in
        // ball diameters, pinned against the solver (dist15-probe):
        // cue (100, 200) -> target (300, 200) -> pocket (650, 200)
        // travels 29.6 ball diameters.
        val result = shotResult()
        store.showAllShots = false
        instrumentation.runOnMainSync { manager.update(result) }
        assertEquals(5, manager.lineWindowCount)
        assertEquals(2, manager.labelWindowCount)
        assertEquals(
            listOf(CutAngleLabel.format(result.shot!!.cutAngleDegrees), "29.6 bd"),
            manager.labelTexts
        )

        // Hide toggle removes the labels with the lines.
        store.guidesHidden = true
        instrumentation.runOnMainSync { manager.update(result) }
        assertEquals(0, manager.lineWindowCount)
        assertEquals("hide must remove the labels too", 0, manager.labelWindowCount)

        // Unhide brings both back.
        store.guidesHidden = false
        instrumentation.runOnMainSync { manager.update(result) }
        assertEquals(5, manager.lineWindowCount)
        assertEquals(2, manager.labelWindowCount)

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

        // A thin shot draws the angle label AND the warning label,
        // plus the pocket-distance label last (v1.0.15): cue (620,
        // 260) -> target (620, 300) -> bottom-right pocket travels
        // 5.9 ball diameters (dist15-probe).
        val result = thinShotResult()
        assertTrue(result.shot!!.isThinCut)
        instrumentation.runOnMainSync { manager.update(result) }
        assertEquals(5, manager.lineWindowCount)
        assertEquals("thin shot needs angle + warning + distance labels", 3, manager.labelWindowCount)
        assertEquals(
            listOf("82.8°", "Thin cut 82.8°", "5.9 bd"),
            manager.labelTexts
        )

        instrumentation.runOnMainSync { manager.removeAll() }
        assertEquals(0, manager.labelWindowCount)
    }

    @Test
    fun pocketDistanceLabel_showsBesideRingAndFollowsEditMode() {
        val ctx = appContext()
        assertTrue(
            "overlay permission must be granted for this test",
            Settings.canDrawOverlays(ctx)
        )
        val store = CalibrationStore(ctx).apply { guidesHidden = false }
        val manager = LineOverlayManager(ctx, store)
        val instrumentation = InstrumentationRegistry.getInstrumentation()

        // The best shot is straight into the middle-right pocket
        // (cut 0.0 deg): angle label first, distance label last,
        // "29.6 bd" pinned against the solver (dist15-probe).
        val result = shotResult()
        store.showAllShots = false
        instrumentation.runOnMainSync { manager.update(result) }
        assertEquals(2, manager.labelWindowCount)
        assertEquals(
            listOf("0.0°", "29.6 bd"),
            manager.labelTexts
        )

        // EDIT mode freezes the same direct shot: the distance label
        // follows the frozen shot, beside the (unchanged) pocket ring.
        instrumentation.runOnMainSync { manager.toggleEditMode() }
        assertTrue(manager.isEditMode)
        assertEquals(2, manager.labelWindowCount)
        assertEquals(
            listOf("0.0°", "29.6 bd"),
            manager.labelTexts
        )

        instrumentation.runOnMainSync { manager.removeAll() }
        assertEquals(0, manager.labelWindowCount)
    }

    /** Rail-contact markers (v1.0.16). All layouts verified against
     * the solver with kotlinc before the tests were written
     * (contact16-probe/Probe.kt + ProbeDist.kt). */
    private fun bankResult(): DetectionResult {
        // Bank-default layout, bottom rail to the top-right pocket:
        // cut 30.77 deg, contact (502.2, 340), distance 45.5 bd.
        val bank = ShotSolver.solveBank(
            Vec2(120.0, 80.0), Vec2(480.0, 300.0),
            Vec2(680.0, 20.0),
            Rail(Vec2(40.0, 340.0), Vec2(0.0, -1.0)), 9.0
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
                    Segment(Vec2(120.0, 80.0), Vec2(480.0, 300.0)), 40
                )
            ),
            shot = null,
            bank = bank,
            allShots = emptyList(),
            tableLocked = true,
            status = "Bank shot to pocket"
        )
    }

    private fun kickResult(): DetectionResult {
        // KickTest layout: cue (120, 80), target (480, 300), right
        // rail to the bottom-middle pocket: cut 2.34 deg, contact
        // (680, 241.5), distance 50.0 bd.
        val kick = ShotSolver.solveKick(
            Vec2(120.0, 80.0), Vec2(480.0, 300.0),
            Vec2(360.0, 340.0),
            Rail(Vec2(680.0, 20.0), Vec2(-1.0, 0.0)), 9.0
        )!!
        return bankResult().copy(
            bank = null,
            kick = kick,
            status = "Kick shot to pocket"
        )
    }

    private fun bank2Result(): DetectionResult {
        // TwoRailBankTest layout: cue (580, 300), target (600, 320),
        // bottom then right rails to the top-middle pocket: cut 12.64
        // deg, contacts (623.5, 340) and (680, 292), distance 29.7 bd.
        val bank2 = ShotSolver.solveTwoRailBank(
            Vec2(580.0, 300.0), Vec2(600.0, 320.0),
            Vec2(360.0, 20.0),
            Rail(Vec2(40.0, 340.0), Vec2(0.0, -1.0)),
            Rail(Vec2(680.0, 20.0), Vec2(-1.0, 0.0)), 9.0
        )!!
        return bankResult().copy(
            bank = null,
            bank2 = bank2,
            status = "Two-rail bank shot to pocket"
        )
    }

    private fun kick2Result(): DetectionResult {
        // TwoRailKickTest layout: cue (140, 120), target (60, 120),
        // top then left rails to the bottom-middle pocket: cut 24.63
        // deg, contacts (84.3, 20) and (40, 99.5), distance 32.7 bd.
        val kick2 = ShotSolver.solveTwoRailKick(
            Vec2(140.0, 120.0), Vec2(60.0, 120.0),
            Vec2(360.0, 340.0),
            Rail(Vec2(40.0, 20.0), Vec2(0.0, 1.0)),
            Rail(Vec2(40.0, 20.0), Vec2(1.0, 0.0)), 9.0
        )!!
        return bankResult().copy(
            bank = null,
            kick2 = kick2,
            status = "Two-rail kick shot to pocket"
        )
    }

    @Test
    fun railContactMarkers_bankDrawsOneMarker() {
        val ctx = appContext()
        assertTrue(
            "overlay permission must be granted for this test",
            Settings.canDrawOverlays(ctx)
        )
        val store = CalibrationStore(ctx).apply { guidesHidden = false }
        val manager = LineOverlayManager(ctx, store)
        val instrumentation = InstrumentationRegistry.getInstrumentation()

        // A one-rail bank draws 6 line windows: aim, 2 bank
        // segments, pocket ring, ghost ring, and 1 rail-contact marker. Labels
        // are the angle (30.8 deg) and the distance (45.5 bd).
        val result = bankResult()
        instrumentation.runOnMainSync { manager.update(result) }
        assertEquals("bank needs aim + 2 path + 2 rings + 1 marker", 6, manager.lineWindowCount)
        assertEquals(2, manager.labelWindowCount)
        assertEquals(
            listOf("30.8°", "45.5 bd"),
            manager.labelTexts
        )

        // Hide toggle removes the marker with the lines.
        store.guidesHidden = true
        instrumentation.runOnMainSync { manager.update(result) }
        assertEquals("hide must remove the marker too", 0, manager.lineWindowCount)
        assertEquals(0, manager.labelWindowCount)

        // Unhide brings the marker back.
        store.guidesHidden = false
        instrumentation.runOnMainSync { manager.update(result) }
        assertEquals(6, manager.lineWindowCount)

        instrumentation.runOnMainSync { manager.removeAll() }
        assertEquals(0, manager.lineWindowCount)
    }

    @Test
    fun railContactMarkers_kickDrawsOneMarker() {
        val ctx = appContext()
        assertTrue(
            "overlay permission must be granted for this test",
            Settings.canDrawOverlays(ctx)
        )
        val store = CalibrationStore(ctx).apply { guidesHidden = false }
        val manager = LineOverlayManager(ctx, store)
        val instrumentation = InstrumentationRegistry.getInstrumentation()

        // A one-rail kick draws 7 line windows: aim, kicked path,
        // target, deflection, pocket ring, ghost ring, and 1 rail-contact marker.
        val result = kickResult()
        instrumentation.runOnMainSync { manager.update(result) }
        assertEquals("kick needs 4 lines + 2 rings + 1 marker", 7, manager.lineWindowCount)
        assertEquals(2, manager.labelWindowCount)
        assertEquals(
            listOf("2.3°", "50.0 bd"),
            manager.labelTexts
        )

        instrumentation.runOnMainSync { manager.removeAll() }
        assertEquals(0, manager.lineWindowCount)
    }

    @Test
    fun railContactMarkers_twoRailBankDrawsTwoMarkers() {
        val ctx = appContext()
        assertTrue(
            "overlay permission must be granted for this test",
            Settings.canDrawOverlays(ctx)
        )
        val store = CalibrationStore(ctx).apply { guidesHidden = false }
        val manager = LineOverlayManager(ctx, store)
        val instrumentation = InstrumentationRegistry.getInstrumentation()

        // A two-rail bank draws 8 line windows: aim, 3 bank
        // segments, pocket ring, ghost ring, and 2 rail-contact markers.
        val result = bank2Result()
        instrumentation.runOnMainSync { manager.update(result) }
        assertEquals("bank2 needs 4 lines + 2 rings + 2 markers", 8, manager.lineWindowCount)
        assertEquals(2, manager.labelWindowCount)
        assertEquals(
            listOf("12.6°", "29.7 bd"),
            manager.labelTexts
        )

        instrumentation.runOnMainSync { manager.removeAll() }
        assertEquals(0, manager.lineWindowCount)
    }

    @Test
    fun railContactMarkers_twoRailKickDrawsTwoMarkers() {
        val ctx = appContext()
        assertTrue(
            "overlay permission must be granted for this test",
            Settings.canDrawOverlays(ctx)
        )
        val store = CalibrationStore(ctx).apply { guidesHidden = false }
        val manager = LineOverlayManager(ctx, store)
        val instrumentation = InstrumentationRegistry.getInstrumentation()

        // A two-rail kick draws 9 line windows: aim, 2 kicked
        // segments, target, deflection, pocket ring, ghost ring, and 2
        // rail-contact markers.
        val result = kick2Result()
        instrumentation.runOnMainSync { manager.update(result) }
        assertEquals("kick2 needs 5 lines + 2 rings + 2 markers", 9, manager.lineWindowCount)
        assertEquals(2, manager.labelWindowCount)
        assertEquals(
            listOf("24.6°", "32.7 bd"),
            manager.labelTexts
        )

        instrumentation.runOnMainSync { manager.removeAll() }
        assertEquals(0, manager.lineWindowCount)
    }

    @Test
    fun railContactMarkers_hiddenWhenBankLinesDisabled() {
        val ctx = appContext()
        assertTrue(
            "overlay permission must be granted for this test",
            Settings.canDrawOverlays(ctx)
        )
        val store = CalibrationStore(ctx).apply { guidesHidden = false }
        val manager = LineOverlayManager(ctx, store)
        val instrumentation = InstrumentationRegistry.getInstrumentation()

        // Bank lines off: a bank result draws nothing at all — no
        // lines, no marker, no labels.
        val result = bankResult()
        store.bankLinesEnabled = false
        instrumentation.runOnMainSync { manager.update(result) }
        assertEquals("bank lines off must hide the marker too", 0, manager.lineWindowCount)
        assertEquals(0, manager.labelWindowCount)

        store.bankLinesEnabled = true
        instrumentation.runOnMainSync { manager.removeAll() }
    }

    private fun comboResult(): DetectionResult {
        // The verified combo layout (v1.0.13): cue (55, 58),
        // middle (60, 40), second (80, 35) — first leg cuts
        // 84.8 degrees (thin), second leg 60.3 degrees into the
        // top-middle pocket.
        val combo = ShotSolver.bestCombo(
            Vec2(55.0, 58.0), Vec2(60.0, 40.0),
            listOf(Vec2(80.0, 35.0)),
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
                    Segment(Vec2(55.0, 58.0), Vec2(60.0, 40.0)), 40
                )
            ),
            shot = null,
            bank = null,
            allShots = emptyList(),
            tableLocked = true,
            status = "Combo shot to pocket",
            combo = combo
        )
    }

    private fun kissResult(): DetectionResult {
        // The verified kiss layout (v1.0.13): cue (55, 58),
        // middle (60, 40), second (100, 60) — kiss cut 78.2
        // degrees, second leg 11.9 degrees into the top-right
        // pocket. No combo exists here, so the kiss is the last
        // resort.
        val kiss = ShotSolver.bestKiss(
            Vec2(55.0, 58.0), Vec2(60.0, 40.0),
            listOf(Vec2(100.0, 60.0)),
            listOf(
                Vec2(40.0, 20.0), Vec2(680.0, 20.0), Vec2(680.0, 340.0),
                Vec2(40.0, 340.0), Vec2(360.0, 20.0), Vec2(360.0, 340.0)
            ),
            9.0
        )!!
        return comboResult().copy(
            kiss = kiss,
            combo = null,
            status = "Kiss shot to pocket"
        )
    }

    @Test
    fun comboResult_drawsBothLegsAndLabelsPerLeg() {
        val ctx = appContext()
        assertTrue(
            "overlay permission must be granted for this test",
            Settings.canDrawOverlays(ctx)
        )
        val store = CalibrationStore(ctx).apply { guidesHidden = false }
        val manager = LineOverlayManager(ctx, store)
        val instrumentation = InstrumentationRegistry.getInstrumentation()

        // A combo draws 6 leg segments (aim/target/deflection
        // per leg) + 1 pocket ring + 2 ghost rings (first leg's
        // ghost and, since v1.0.27, the second leg's ghost), and
        // one angle label per leg.
        // the thin first leg adds its warning label — plus one
        // pocket-distance label last (v1.0.15): the whole chain
        // travels 16.0 ball diameters (dist15-probe).
        val result = comboResult()
        assertTrue(result.combo!!.firstShot.isThinCut)
        instrumentation.runOnMainSync { manager.update(result) }
        assertEquals(9, manager.lineWindowCount)
        assertEquals(4, manager.labelWindowCount)
        assertEquals(
            listOf("84.8°", "Thin cut 84.8°", "60.3°", "16.0 bd"),
            manager.labelTexts
        )

        // Hide toggle removes the lines and the labels together.
        store.guidesHidden = true
        instrumentation.runOnMainSync { manager.update(result) }
        assertEquals(0, manager.lineWindowCount)
        assertEquals(
            "hide must remove the combo labels too",
            0, manager.labelWindowCount
        )

        instrumentation.runOnMainSync { manager.removeAll() }
        assertEquals(0, manager.labelWindowCount)
    }

    @Test
    fun kissResult_drawsBothLegsAndLabelsPerLeg() {
        val ctx = appContext()
        assertTrue(
            "overlay permission must be granted for this test",
            Settings.canDrawOverlays(ctx)
        )
        val store = CalibrationStore(ctx).apply { guidesHidden = false }
        val manager = LineOverlayManager(ctx, store)
        val instrumentation = InstrumentationRegistry.getInstrumentation()

        // A kiss draws the same 6 + 3 windows (6 leg segments, 1
        // pocket ring, 2 ghost rings since v1.0.27), with one angle
        // label per leg and no warning (neither leg is thin), plus
        // one pocket-distance label last (v1.0.15): the whole chain
        // travels 33.8 ball diameters (dist15-probe).
        val result = kissResult()
        assertTrue(!result.kiss!!.kissShot.isThinCut)
        assertTrue(!result.kiss!!.secondShot.isThinCut)
        instrumentation.runOnMainSync { manager.update(result) }
        assertEquals(9, manager.lineWindowCount)
        assertEquals(3, manager.labelWindowCount)
        assertEquals(
            listOf("78.2°", "11.9°", "33.8 bd"),
            manager.labelTexts
        )

        instrumentation.runOnMainSync { manager.removeAll() }
        assertEquals(0, manager.labelWindowCount)
    }

    @Test
    fun comboResult_blockedPath_showsPathBlockedLabel() {
        val ctx = appContext()
        assertTrue(
            "overlay permission must be granted for this test",
            Settings.canDrawOverlays(ctx)
        )
        val store = CalibrationStore(ctx).apply { guidesHidden = false }
        val manager = LineOverlayManager(ctx, store)
        val instrumentation = InstrumentationRegistry.getInstrumentation()

        // v1.0.24: a blocked combo gains the "Path blocked" label
        // in the warning amber, between the per-leg angle labels
        // and the pocket-distance label, the same position as
        // the single-ball families. The blocker sits on the
        // first leg's aim-line midpoint (53.5,57.0).
        val result = comboResult().copy(blockedBy = Vec2(53.5, 57.0))
        instrumentation.runOnMainSync { manager.update(result) }
        assertEquals(9, manager.lineWindowCount)
        assertEquals(5, manager.labelWindowCount)
        assertEquals(
            listOf("84.8°", "Thin cut 84.8°", "60.3°", "Path blocked", "16.0 bd"),
            manager.labelTexts
        )

        instrumentation.runOnMainSync { manager.removeAll() }
        assertEquals(0, manager.labelWindowCount)
    }

    @Test
    fun kissResult_blockedPath_showsPathBlockedLabel() {
        val ctx = appContext()
        assertTrue(
            "overlay permission must be granted for this test",
            Settings.canDrawOverlays(ctx)
        )
        val store = CalibrationStore(ctx).apply { guidesHidden = false }
        val manager = LineOverlayManager(ctx, store)
        val instrumentation = InstrumentationRegistry.getInstrumentation()

        // v1.0.24: a blocked kiss gains the same label, in the
        // same position. The blocker sits on the kiss leg's
        // aim-line midpoint (56.3,57.9).
        val result = kissResult().copy(blockedBy = Vec2(56.3, 57.9))
        instrumentation.runOnMainSync { manager.update(result) }
        assertEquals(9, manager.lineWindowCount)
        assertEquals(4, manager.labelWindowCount)
        assertEquals(
            listOf("78.2°", "11.9°", "Path blocked", "33.8 bd"),
            manager.labelTexts
        )

        instrumentation.runOnMainSync { manager.removeAll() }
        assertEquals(0, manager.labelWindowCount)
    }

    @Test
    fun comboResult_hiddenWhenBankLinesDisabled() {
        val ctx = appContext()
        assertTrue(
            "overlay permission must be granted for this test",
            Settings.canDrawOverlays(ctx)
        )
        val store = CalibrationStore(ctx).apply {
            guidesHidden = false
            bankLinesEnabled = false
        }
        val manager = LineOverlayManager(ctx, store)
        val instrumentation = InstrumentationRegistry.getInstrumentation()

        // Combo and kiss are fallback lines, so the bank-lines
        // switch gates them like the other fallback families.
        val result = comboResult()
        instrumentation.runOnMainSync { manager.update(result) }
        assertEquals(0, manager.lineWindowCount)
        assertEquals(0, manager.labelWindowCount)

        // Re-enabling the switch brings the lines back.
        store.bankLinesEnabled = true
        instrumentation.runOnMainSync { manager.update(result) }
        assertEquals(9, manager.lineWindowCount)
        assertEquals(4, manager.labelWindowCount)

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

    /** Shot-history strip (v1.0.17): the overlay records each shot
     * it draws, once per change — a steady aim records once, a
     * black frame records nothing, and a different family opens a
     * new entry. The fabricated result has no table and no pockets,
     * so the pocket name is "?". */
    @Test
    fun shotHistory_recordsDrawnShotOncePerChange() {
        val ctx = appContext()
        assertTrue(
            "overlay permission must be granted for this test",
            Settings.canDrawOverlays(ctx)
        )
        val store = CalibrationStore(ctx).apply { guidesHidden = false }
        val manager = LineOverlayManager(ctx, store)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        ShotHistory.clear()
        try {
            store.showAllShots = false
            instrumentation.runOnMainSync { manager.update(shotResult()) }
            assertEquals(5, manager.lineWindowCount)
            // The fabricated result's three pockets are (600,50),
            // (600,350), (650,200): the best shot is straight to the
            // mid-right pocket, cut 0.0 deg, 29.6 bd.
            val snap = ShotHistory.snapshot()
            assertEquals("one entry after the first drawn shot", 1, snap.size)
            assertEquals("DIRECT", snap[0].family)
            assertEquals("?", snap[0].pocketName)
            assertEquals(0.0, snap[0].cutAngleDegrees, 0.0)
            assertEquals(29.56, snap[0].distanceBd, 0.05)

            // Same shot again: deduped, still one entry.
            instrumentation.runOnMainSync { manager.update(shotResult()) }
            assertEquals(1, ShotHistory.snapshot().size)

            // Black frame draws nothing and records nothing.
            val black = shotResult().copy(
                isCaptureBlack = true, shot = null, guideline = emptyList()
            )
            instrumentation.runOnMainSync { manager.update(black) }
            assertEquals(1, ShotHistory.snapshot().size)

            // A different family opens a second entry (the
            // fabricated result has no table and no pockets, so the
            // pocket is "?" here too).
            instrumentation.runOnMainSync { manager.update(bank2Result()) }
            val snap2 = ShotHistory.snapshot()
            assertEquals(2, snap2.size)
            assertEquals("BANK2", snap2[1].family)
            assertEquals("?", snap2[1].pocketName)
            assertEquals(12.64, snap2[1].cutAngleDegrees, 0.05)
            assertEquals(29.75, snap2[1].distanceBd, 0.05)
        } finally {
            ShotHistory.clear()
            instrumentation.runOnMainSync { manager.removeAll() }
        }
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
