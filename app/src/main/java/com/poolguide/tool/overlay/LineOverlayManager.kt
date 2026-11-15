package com.poolguide.tool.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.PointF
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.poolguide.tool.R
import com.poolguide.tool.cv.Ball
import com.poolguide.tool.cv.BallColor
import com.poolguide.tool.cv.DetectionResult
import com.poolguide.tool.data.CalibrationStore
import com.poolguide.tool.data.DiagnosticsLog
import com.poolguide.tool.data.ShotHistory
import com.poolguide.tool.geometry.BallGroupTag
import com.poolguide.tool.geometry.BlockedPathLabel
import com.poolguide.tool.geometry.CutAngleLabel
import com.poolguide.tool.geometry.GhostBallRing
import com.poolguide.tool.geometry.PocketDistanceLabel
import com.poolguide.tool.geometry.RailContactMarker
import com.poolguide.tool.geometry.Segment
import com.poolguide.tool.geometry.ShotSolver
import com.poolguide.tool.geometry.Vec2
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min

/**
 * Overlay manager — the ChatGPT design, applied:
 *
 * 1. NO full-screen MATCH_PARENT overlay. Each aim line lives in its OWN
 *    small window sized to just that line's bounding box, so
 *    FLAG_WINDOW_IS_OBSCURED is only set when the user taps exactly on a
 *    line. A full-screen overlay would flag EVERY touch as obscured.
 * 2. "Hide guide while shooting": the control bubble has a Hide/Show toggle
 *    that removes all line windows (removeView) while the user shoots.
 *    Since v1.0.37 the lines also auto-hide while the aim is moving
 *    (AimActivityTracker: the game's guideline angle swinging, or the
 *    cue ball travelling after a shot) and come back once the aim has
 *    been quiet for 5 frames — the game reports FLAG_WINDOW_IS_OBSCURED
 *    for every touch landing on a line window, so no line may exist
 *    over the table at the moment a touch lands. The manual toggle
 *    stays as the fallback. We cannot observe the user's touches
 *    (FLAG_NOT_TOUCHABLE passes them through), so the auto-hide is
 *    driven by the aim signal in the detection, not by touch events —
 *    see README, "Honest limits".
 * 3. Auto-hide: when the game's own guideline is lost for 15 consecutive
 *    frames, the lines are removed — a stale shot is worse than no shot.
 * 4. Black capture (v1.0.37, REA hardening R2): a black run that
 *    persists past 30 frames shows "Capture blocked (FLAG_SECURE?)"
 *    on the bubble, so the user is told the game is hiding the table
 *    instead of seeing no lines and no explanation; the run is also
 *    recorded in DiagnosticsLog with its context (see CaptureService).
 * 5. Windows are reused by index across frames (updateViewLayout), not
 *    re-added, to avoid flicker.
 * 6. EDIT mode (v1.0.4, Line Guide lead): the bubble's Edit button freezes
 *    the current shot and shows two draggable anchor handles (cue +
 *    ghost). The handles are small touchable windows by design — a touch
 *    landing exactly on one sets FLAG_WINDOW_IS_OBSCURED, so EDIT mode is
 *    for arranging, not for shooting; leaving it resumes the live
 *    detection. In EDIT mode the lines do not auto-hide on guideline
 *    loss (the user is editing precisely because detection missed) and
 *    do not follow new detections. EDIT mode exits on its own after
 *    20 s without a handle drag (v1.0.41, REA hardening R4), so a
 *    forgotten session cannot leave the handles over the table.
 * 7. Session pot rate (v1.0.42, REA hardening R6): the bubble shows
 *    "Pots 7/10 (70%)" once any shot was taken, and the diagnostics
 *    export carries the same line. The stat comes from
 *    ShotOutcomeTracker, which counts a shot when the cue ball
 *    jumps after an aim and a pot when a ball is gone once the table
 *    settles; at ten shots and 80 percent or more the label turns
 *    amber and tells the user to vary their play, because perfect
 *    play is itself a signal to the server.
 */
class LineOverlayManager(
    private val context: Context,
    private val store: CalibrationStore,
    private val onStopRequested: () -> Unit = {}
) {

    companion object {
        private const val PADDING_PX = 10
        private const val AIM_COLOR = 0xFF00E676.toInt() // green
        private const val TARGET_COLOR = 0xFFFFD600.toInt() // yellow
        private const val DEFLECT_COLOR = Color.WHITE
        private const val BANK_COLOR = 0xFF00E5FF.toInt() // cyan
        /** Kick lines (v1.0.10): magenta, distinct from the cyan bank
         * lines — a kick is the cue ball off a rail, a bank is the
         * target ball off a rail, and the two must not be confusable
         * at a glance. Aim stays green, target yellow, deflection
         * white. */
        private const val KICK_COLOR = 0xFFFF4081.toInt() // magenta
        private const val POCKET_COLOR = 0xFFFF5252.toInt() // red ring
        /** Pocket-ring radius (v1.0.15): the distance label anchors
         * just past this ring, so the two share one constant. */
        private const val RING_RADIUS_PX = 18f
        /** Rail-contact marker radius (v1.0.16): smaller than the
         * pocket ring (a spot on a rail, not a pocket), same stroke
         * width, in the shot family's colour. */
        private const val CONTACT_MARKER_RADIUS_PX = 10f
        /** Thin-cut warning (v1.0.12): amber, distinct from every line
         * family (green aim, yellow target, white deflection, cyan
         * bank, magenta kick, red ring) so the warning is never
         * mistaken for a line. */
        private const val WARNING_COLOR = 0xFFFFAB00.toInt() // amber
        /** Cut-angle label text size. */
        private const val LABEL_TEXT_SIZE_SP = 14f
        /** Non-best candidate shots (PoolAimAssist UX): thin, ~35% alpha
         * grey. The best shot keeps the full-colour lines — its yellow
         * target line is the highlight that marks the pick. */
        private const val CANDIDATE_COLOR = 0x59BDBDBD.toInt() // ~35% grey
        private const val CANDIDATE_WIDTH_PX = 2f
        private const val AIM_WIDTH_PX = 5f
        private const val LINE_WIDTH_PX = 4f
        private const val RING_WIDTH_PX = 4f
        private const val NO_GUIDELINE_HIDE_AFTER_FRAMES = 15
        /** EDIT-mode anchor handle size (Line Guide's controllers are
         * 150 px; ours are smaller — a 48 dp touch target is enough to
         * grab, and a smaller window flags fewer touches as obscured). */
        private const val HANDLE_SIZE_DP = 48f
        /** Bubble drag threshold (Bloro lead, v1.0.5): a touch must
         * move this far before it counts as a drag; shorter is a tap. */
        private const val BUBBLE_DRAG_THRESHOLD_DP = 10f
        /** Bubble edge-snap margin (Bloro lead, v1.0.5). */
        private const val BUBBLE_EDGE_MARGIN_DP = 16f
        /** Lock-progress ring size on the bubble (v1.0.5). */
        private const val BUBBLE_RING_SIZE_DP = 28f
        /** Bubble default top offset. */
        private const val BUBBLE_DEFAULT_Y_DP = 120f
    }

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private data class LineWindow(
        val view: LineView,
        val params: WindowManager.LayoutParams
    )

    private data class HandleWindow(
        val anchor: Anchor,
        val view: LineView,
        val params: WindowManager.LayoutParams
    )

    private data class LabelWindow(
        val view: TextView,
        val params: WindowManager.LayoutParams
    )

    private val lineWindows = mutableListOf<LineWindow>()
    private val handleWindows = mutableListOf<HandleWindow>()
    /** Cut-angle label windows (v1.0.12) + pocket-distance label
     * (v1.0.15): one small TextView window per label, managed exactly
     * like the line windows (reuse by index, removed with the lines)
     * so a label can never outlive its shot. Kept separate from
     * [lineWindows] on purpose: the existing line-window counts (5
     * for a direct shot, 9 with all shots: the ghost-ball ring,
     * v1.0.26, is a line window too) are pinned by instrumented
     * tests and must not change. The distance label IS a label, so
     * it shares these windows: the pinned label counts move by +1
     * per shot (2 for a direct shot, 3 for a thin one); see the
     * instrumented tests. */
    private val labelWindows = mutableListOf<LabelWindow>()
    /** Group-tag windows (v1.0.14): one small TextView window per
     * detected ball ("C" cue, "8" eight, "S" solid, "ST" stripe,
     * "?" unknown), managed exactly like the label windows (reuse by
     * index, removed with the lines). Kept separate from
     * [labelWindows] on purpose: the pinned label counts (1 for a
     * direct shot, 2 for a thin one) must not change when tags are
     * added. */
    private val tagWindows = mutableListOf<LabelWindow>()
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var bubbleView: View? = null
    /** Lock-progress ring on the bubble (v1.0.5). */
    private var lockRing: LockRingView? = null
    /** "Capture blocked" status on the bubble (v1.0.37, REA hardening
     * R2): visible only while a black run persists past the threshold. */
    private var blockedStatusView: TextView? = null
    /** Session pot-rate stat on the bubble (v1.0.42, REA hardening
     * R6): "Pots 7/10 (70%)" once any shot was taken, GONE before
     * that, so the bubble keeps its normal size. Amber with a second
     * line when the rate is abnormally high. */
    private var potRateView: TextView? = null
    /** Last pot-rate text set on the bubble, so update() does not
     * re-set the same string every frame. */
    private var lastPotRateText: String? = null
    private var hideButton: Button? = null
    private var editButton: Button? = null
    private var framesWithoutGuideline = 0
    /** Shot-in-progress state (v1.0.37, REA hardening R1): hides the
     * lines while the aim is moving or a shot is in flight. */
    private val aimTracker = AimActivityTracker()
    /** EDIT-mode state (v1.0.4): the frozen shot + anchor overrides. */
    private val manualAim = ManualAim()
    /** EDIT-mode inactivity timer (v1.0.41, REA hardening R4): exits
     * EDIT mode after 20 s without a handle drag, so a forgotten
     * session cannot leave the touchable handles over the table. */
    private val editInactivity = EditInactivityTracker()
    /** The latest result, kept so a handle drag can re-render without
     * waiting for the next frame. */
    private var lastResult: DetectionResult? = null
    /** Jitter gate + interpolation (v1.0.3, Bloro lead): the best
     * shot's lines ease toward new detections instead of snapping. */
    private val smoother = LineSmoother()

    /** Test-only: how many line windows are currently shown. */
    @androidx.annotation.VisibleForTesting
    val lineWindowCount: Int get() = lineWindows.size

    /** Test-only: how many cut-angle label windows are currently shown. */
    @androidx.annotation.VisibleForTesting
    val labelWindowCount: Int get() = labelWindows.size

    /** Test-only: the texts of the currently shown label windows, in
     * window order (angle label first, thin-cut warning second,
     * pocket-distance label last). */
    @androidx.annotation.VisibleForTesting
    val labelTexts: List<String> get() = labelWindows.map { it.view.text.toString() }

    /** Test-only: how many group-tag windows are currently shown. */
    @androidx.annotation.VisibleForTesting
    val tagWindowCount: Int get() = tagWindows.size

    /** Test-only: the texts of the currently shown tag windows, in
     * ball order (the order of [DetectionResult.balls]). */
    @androidx.annotation.VisibleForTesting
    val tagTexts: List<String> get() = tagWindows.map { it.view.text.toString() }

    /** Test-only: whether EDIT mode is active. */
    @androidx.annotation.VisibleForTesting
    val isEditMode: Boolean get() = manualAim.isEditMode

    /** Test-only: how many anchor-handle windows are currently shown. */
    @androidx.annotation.VisibleForTesting
    val handleWindowCount: Int get() = handleWindows.size

    /** Test-only: the corrected shot currently drawn in EDIT mode. */
    @androidx.annotation.VisibleForTesting
    val editedShot: ShotSolver.ShotSolution? get() = manualAim.currentShot()

    /** Test-only: the corrected bank currently drawn in EDIT mode
     * (v1.0.29). Null unless a bank is frozen. */
    @androidx.annotation.VisibleForTesting
    val editedBank: ShotSolver.BankSolution? get() = manualAim.currentBank()

    /** Test-only: the corrected kick currently drawn in EDIT mode
     * (v1.0.30). Null unless a kick is frozen. */
    @androidx.annotation.VisibleForTesting
    val editedKick: ShotSolver.KickSolution? get() = manualAim.currentKick()

    /** Test-only: the corrected two-rail bank currently drawn in
     * EDIT mode (v1.0.33). Null unless a two-rail bank is frozen. */
    @androidx.annotation.VisibleForTesting
    val editedBank2: ShotSolver.TwoRailBankSolution? get() = manualAim.currentTwoRailBank()

    /** Test-only: whether any anchor override is registered in EDIT
     * mode (v1.0.29 diagnostic: distinguishes a drag that never
     * registered from one the bank re-aim ignores). */
    @androidx.annotation.VisibleForTesting
    val editHasOverrides: Boolean get() = manualAim.hasOverrides

    /** Test-only: the lock-ring progress currently shown on the bubble
     * (-1 when the bubble does not exist yet). */
    @androidx.annotation.VisibleForTesting
    val lockRingProgress: Float get() = lockRing?.progress ?: -1f

    /** Test-only: whether the lock ring is showing its frozen (EDIT
     * mode) state rather than live lock progress. */
    @androidx.annotation.VisibleForTesting
    val lockRingFrozen: Boolean get() = lockRing?.isFrozen ?: false

    /** Test-only: whether the bubble is currently showing the
     * "capture blocked" state (v1.0.37). */
    @androidx.annotation.VisibleForTesting
    val isCaptureBlockedShown: Boolean
        get() = blockedStatusView?.visibility == View.VISIBLE

    /** Toggle EDIT mode (the bubble's Edit/Done button). Entering freezes
     * the current shot; leaving resumes the live detection and drops
     * any corrections. With no shot to edit, the tap does nothing.
     * One-rail bank shots (v1.0.29) freeze the same way: when no direct
     * shot exists but a bank is shown, EDIT freezes the bank instead.
     * One-rail kick shots (v1.0.30) freeze the same way again: when
     * neither a direct shot nor a bank exists but a kick is shown,
     * EDIT freezes the kick instead. Two-rail bank shots (v1.0.33)
     * freeze the same way again: when no direct shot, bank, or kick
     * exists but a two-rail bank is shown, EDIT freezes the two-rail
     * bank instead. Two-rail kick and combo/kiss shots stay out:
     * with none of the four, the tap still does nothing. */
    fun toggleEditMode() {
        if (manualAim.isEditMode) {
            exitEditMode(DiagnosticsLog.Kind.EDIT_EXIT)
            return
        }
        val result = lastResult
        val shot = result?.shot
        if (shot != null) {
            smoother.reset()
            manualAim.enterEditMode(shot, result.cueBall?.radiusPx ?: 9.0)
            editInactivity.enter(SystemClock.uptimeMillis())
            editButton?.text = context.getString(R.string.done_editing)
            lockRing?.isFrozen = true
            DiagnosticsLog.record(DiagnosticsLog.Kind.EDIT_ENTER)
            result.let { updateEditMode(it) }
            return
        }
        val bank = result?.bank
        if (bank != null && store.bankLinesEnabled) {
            smoother.reset()
            manualAim.enterEditMode(bank, result.cueBall?.radiusPx ?: 9.0)
            editInactivity.enter(SystemClock.uptimeMillis())
            editButton?.text = context.getString(R.string.done_editing)
            lockRing?.isFrozen = true
            DiagnosticsLog.record(DiagnosticsLog.Kind.EDIT_ENTER)
            result.let { updateEditMode(it) }
            return
        }
        val kick = result?.kick
        if (kick != null && store.bankLinesEnabled) {
            smoother.reset()
            manualAim.enterEditMode(kick, result.cueBall?.radiusPx ?: 9.0)
            editInactivity.enter(SystemClock.uptimeMillis())
            editButton?.text = context.getString(R.string.done_editing)
            lockRing?.isFrozen = true
            DiagnosticsLog.record(DiagnosticsLog.Kind.EDIT_ENTER)
            result.let { updateEditMode(it) }
            return
        }
        val bank2 = result?.bank2
        if (bank2 != null && store.bankLinesEnabled) {
            smoother.reset()
            manualAim.enterEditMode(bank2, result.cueBall?.radiusPx ?: 9.0)
            editInactivity.enter(SystemClock.uptimeMillis())
            editButton?.text = context.getString(R.string.done_editing)
            lockRing?.isFrozen = true
            DiagnosticsLog.record(DiagnosticsLog.Kind.EDIT_ENTER)
            result.let { updateEditMode(it) }
            return
        }
        Toast.makeText(context, R.string.no_shot_to_edit, Toast.LENGTH_SHORT).show()
    }

    /** Shared EDIT-mode exit path (v1.0.41, REA hardening R4): the
     * Done button exits with [DiagnosticsLog.Kind.EDIT_EXIT]; the
     * inactivity timer exits with EDIT_AUTO_EXIT so the diagnostics
     * export can tell a forgotten session from a deliberate Done.
     * Either way the frozen shot is dropped, the handles are removed,
     * and live detection resumes on the next frame. */
    private fun exitEditMode(kind: DiagnosticsLog.Kind) {
        manualAim.exitEditMode()
        removeHandles()
        editButton?.text = context.getString(R.string.edit_guides)
        lockRing?.isFrozen = false
        editInactivity.exit()
        DiagnosticsLog.record(kind)
    }

    /** Test-only: drag an anchor by a working-coordinate delta, then
     * re-render. No-op outside EDIT mode. */
    @androidx.annotation.VisibleForTesting
    fun applyAnchorDrag(anchor: Anchor, deltaWorking: Vec2) {
        if (!manualAim.isEditMode) return
        manualAim.dragAnchor(anchor, deltaWorking, lastResult?.table)
        editInactivity.noteInteraction(SystemClock.uptimeMillis())
        lastResult?.let { updateEditMode(it) }
    }

    /** One line to draw, in screen coordinates. */
    private data class DrawLine(
        val segment: Segment,
        val color: Int,
        val widthPx: Float,
        val isRing: Boolean = false,
        val ringRadius: Float = 0f
    )

    /** One text label to draw (v1.0.12), in screen coordinates. The
     * window is centred on ([centerX], [centerY]); the angle label
     * sits beside the aim line and the thin-cut warning above the cue
     * ball, both computed from the same smoothed segments as the
     * lines, so a label moves with its line and hides with it. */
    private data class DrawLabel(
        val text: String,
        val color: Int,
        val centerX: Float,
        val centerY: Float
    )

    /** The lines, labels, and group tags for one frame. */
    private data class DrawnOverlay(
        val lines: List<DrawLine>,
        val labels: List<DrawLabel>,
        /** Group tags (v1.0.14): one per detected ball, in ball order. */
        val tags: List<DrawLabel> = emptyList()
    )

    fun update(result: DetectionResult) {
        ensureBubble()
        // Lock ring (v1.0.5): fills as the table stabilises, green
        // once locked, empty while no table is detected. In EDIT mode
        // (v1.0.6) the shot is frozen, so the ring shows the neutral
        // frozen state instead of implying live lock progress.
        lockRing?.isFrozen = manualAim.isEditMode
        lockRing?.progress = result.tableLockProgress
        // Capture blocked (v1.0.37, REA hardening R2): a black run
        // that persists past the threshold shows on the bubble, so the
        // user is told the game is hiding the table instead of seeing
        // no lines and no explanation. Set before the EDIT branch so
        // it shows in EDIT mode too.
        setCaptureBlocked(DetectionResult.isPersistentBlackRun(result.blackFrameRunFrames))
        lastResult = result
        if (result.guideline.isEmpty()) {
            framesWithoutGuideline++
        } else {
            framesWithoutGuideline = 0
        }

        // EDIT mode (v1.0.4): the frozen shot is drawn, not the live
        // detection — see updateEditMode. A session with no handle
        // drag for 20 s exits on its own (v1.0.41, REA hardening
        // R4), so the touchable handles cannot sit over the table
        // through a whole turn; live lines resume on the next frame.
        if (manualAim.isEditMode) {
            if (editInactivity.shouldExit(SystemClock.uptimeMillis())) {
                exitEditMode(DiagnosticsLog.Kind.EDIT_AUTO_EXIT)
                return
            }
            updateEditMode(result)
            return
        }

        // Session pot rate (v1.0.42, REA hardening R6): feed every
        // live frame, before the lock gate and the hide branch both
        // return early: the tracker needs the scatter frames too,
        // when the lines are hidden because the balls are moving.
        // EDIT mode returns before this point, so a frozen shot
        // never feeds it; black and unlocked frames abandon a
        // pending shot inside the tracker.
        DiagnosticsLog.outcomes.observe(result)
        updatePotRateLabel()

        // Table-lock gate (v1.0.3, Bloro lead): draw nothing until the
        // table rect has been stable for 5 s. A new table, a moved
        // table, or a lost table all clear the lock in the pipeline.
        if (!result.tableLocked) {
            smoother.reset()
            aimTracker.reset()
            removeLineWindows()
            removeLabelWindows()
            removeTagWindows()
            return
        }

        // Auto-hide while aiming/shooting (v1.0.37, REA hardening R1):
        // the aim signal is the game's guideline angle and the cue
        // ball's position between frames. While either is moving, no
        // line window may exist over the table at the moment a touch
        // lands; once the aim has been quiet for 5 frames the lines
        // come back.
        val aiming = aimTracker.observe(
            guidelineAngleDeg(result), result.cueBall?.center
        )
        val hide = store.guidesHidden ||
            result.isCaptureBlack ||
            framesWithoutGuideline > NO_GUIDELINE_HIDE_AFTER_FRAMES ||
            aiming
        if (hide) {
            removeLineWindows()
            removeLabelWindows()
            removeTagWindows()
            return
        }

        val drawn = buildDrawLines(result)
        if (drawn.lines.isEmpty()) {
            removeLineWindows()
            removeLabelWindows()
            removeTagWindows()
            return
        }
        // Shot history (v1.0.17): record the shot the player just
        // saw, once per change — a steady aim records once, not once
        // per frame. EDIT mode returns before this point, so a frozen
        // shot is never recorded as a live solve.
        ShotHistory.entryFor(result)?.let { ShotHistory.recordIfChanged(it) }
        applyLines(drawn.lines)
        applyLabels(drawn.labels)
        applyTags(drawn.tags)
    }

    /** The game's guideline angle in degrees (v1.0.37, REA hardening
     * R1): the longest tracked segment's direction, or null when there
     * is no guideline this frame. The longest segment is the most
     * stable read on where the player is aiming. */
    private fun guidelineAngleDeg(result: DetectionResult): Double? {
        val longest = result.guideline.maxByOrNull { it.segment.length() } ?: return null
        return AimActivityTracker.directionAngleDeg(longest.segment.direction())
    }

    /** Show or hide the "capture blocked" state on the bubble
     * (v1.0.37, REA hardening R2). The view is GONE, not INVISIBLE,
     * so the bubble shrinks back to its normal size when the capture
     * recovers. */
    private fun setCaptureBlocked(blocked: Boolean) {
        blockedStatusView?.visibility = if (blocked) View.VISIBLE else View.GONE
    }

    /** Session pot-rate stat on the bubble (v1.0.42, REA hardening
     * R6): GONE until the first shot is taken, then "Pots 7/10
     * (70%)". At ten shots and 80 percent or more the label turns
     * amber and gains a second line telling the user to vary their
     * play. The text is set only when it changes. */
    private fun updatePotRateLabel() {
        val view = potRateView ?: return
        val outcomes = DiagnosticsLog.outcomes
        if (outcomes.shotsTaken == 0) {
            if (lastPotRateText != null) {
                lastPotRateText = null
                view.visibility = View.GONE
            }
            return
        }
        val text = if (outcomes.shouldRemind()) {
            outcomes.format() + "\nPot rate very high, vary your play"
        } else {
            outcomes.format()
        }
        if (text == lastPotRateText) return
        lastPotRateText = text
        view.text = text
        view.setTextColor(if (outcomes.shouldRemind()) WARNING_COLOR else Color.WHITE)
        view.visibility = View.VISIBLE
    }

    /** EDIT-mode frame: draw the frozen shot with any anchor corrections.
     * No table-lock gate and no guideline-loss auto-hide — the user is
     * editing precisely because detection missed — and no smoothing or
     * candidate shots: the drag IS the direct control. Only the explicit
     * hide toggle and a black capture still remove the lines. */
    private fun updateEditMode(result: DetectionResult) {
        if (store.guidesHidden || result.isCaptureBlack) {
            removeLineWindows()
            removeLabelWindows()
            removeTagWindows()
            removeHandles()
            return
        }
        // One-rail bank EDIT (v1.0.29): the frozen bank with its legs.
        if (manualAim.isBankEdit) {
            val bank = manualAim.currentBank()
            if (bank == null) {
                removeLineWindows()
                removeLabelWindows()
                removeTagWindows()
                removeHandles()
                return
            }
            val drawn = buildEditBankLines(bank, result)
            if (drawn.lines.isEmpty()) {
                removeLineWindows()
                removeLabelWindows()
                removeTagWindows()
                removeHandles()
                return
            }
            applyLines(drawn.lines)
            applyLabels(drawn.labels)
            applyTags(drawn.tags)
            ensureHandles(bank.direct, result)
            return
        }
        // One-rail kick EDIT (v1.0.30): the frozen kick with its legs.
        if (manualAim.isKickEdit) {
            val kick = manualAim.currentKick()
            if (kick == null) {
                removeLineWindows()
                removeLabelWindows()
                removeTagWindows()
                removeHandles()
                return
            }
            val drawn = buildEditKickLines(kick, result)
            if (drawn.lines.isEmpty()) {
                removeLineWindows()
                removeLabelWindows()
                removeTagWindows()
                removeHandles()
                return
            }
            applyLines(drawn.lines)
            applyLabels(drawn.labels)
            applyTags(drawn.tags)
            ensureHandles(kick.direct, result)
            return
        }
        // Two-rail bank EDIT (v1.0.33): the frozen two-rail bank
        // with its three legs.
        if (manualAim.isTwoRailBankEdit) {
            val bank2 = manualAim.currentTwoRailBank()
            if (bank2 == null) {
                removeLineWindows()
                removeLabelWindows()
                removeTagWindows()
                removeHandles()
                return
            }
            val drawn = buildEditTwoRailBankLines(bank2, result)
            if (drawn.lines.isEmpty()) {
                removeLineWindows()
                removeLabelWindows()
                removeTagWindows()
                removeHandles()
                return
            }
            applyLines(drawn.lines)
            applyLabels(drawn.labels)
            applyTags(drawn.tags)
            ensureHandles(bank2.direct, result)
            return
        }
        val shot = manualAim.currentShot()
        if (shot == null) {
            removeLineWindows()
            removeLabelWindows()
            removeTagWindows()
            removeHandles()
            return
        }
        val drawn = buildEditLines(shot, result)
        if (drawn.lines.isEmpty()) {
            removeLineWindows()
            removeLabelWindows()
            removeTagWindows()
            removeHandles()
            return
        }
        applyLines(drawn.lines)
        applyLabels(drawn.labels)
        applyTags(drawn.tags)
        ensureHandles(shot, result)
    }

    /** The corrected shot's lines, mapped to screen coordinates. The
     * pocket ring marks the (unchanged) target pocket. The ghost-ball
     * ring (v1.0.26) marks the cue ball's arrival point, in the aim
     * green, like the live overlay. The cut-angle label and thin-cut
     * warning (v1.0.12) follow the frozen shot's aim line and cue
     * ball, in the aim green. EDIT mode draws a direct shot. The
     * pocket-distance label (v1.0.15) sits beside the pocket ring,
     * in the aim green. */
    private fun buildEditLines(
        shot: ShotSolver.ShotSolution,
        result: DetectionResult
    ): DrawnOverlay {
        val sx = result.scaleToScreenX
        val sy = result.scaleToScreenY
        fun map(p: Vec2) = Vec2(p.x * sx, p.y * sy)
        val pocket = shot.targetLine.end
        val aim = Segment(map(shot.aimLine.start), map(shot.aimLine.end))
        val lines = listOf(
            DrawLine(aim, AIM_COLOR, AIM_WIDTH_PX),
            DrawLine(
                Segment(map(shot.targetLine.start), map(shot.targetLine.end)),
                TARGET_COLOR, LINE_WIDTH_PX
            ),
            DrawLine(
                Segment(map(shot.deflectionLine.start), map(shot.deflectionLine.end)),
                DEFLECT_COLOR, LINE_WIDTH_PX
            ),
            DrawLine(
                Segment(map(pocket), map(pocket)),
                POCKET_COLOR, RING_WIDTH_PX, isRing = true, ringRadius = RING_RADIUS_PX
            ),
            ghostRing(shot, result, ::map)
        )
        return DrawnOverlay(
            lines = lines,
            labels = labelsFor(shot, aim, result, AIM_COLOR) +
                distanceLabel(
                    PocketDistanceLabel.pathSegments(shot),
                    shot.targetLine.end, shot.targetLine, result, AIM_COLOR
                ),
            tags = groupTags(result, lines.filter { !it.isRing }.map { it.segment })
        )
    }

    /** The corrected bank's lines, mapped to screen coordinates
     * (v1.0.29). The aim line stays aim green; the two bank legs, the
     * cut-angle label and the pocket-distance label are bank cyan, like
     * the live bank overlay. The pocket ring marks the (unchanged)
     * target pocket, the ghost-ball ring (v1.0.26) marks the cue ball's
     * arrival point, and the rail-contact marker (v1.0.16) marks the
     * cushion spot, all three in the live overlay's positions, so
     * EDIT draws the bank the live overlay drew, not a direct shot. */
    private fun buildEditBankLines(
        bank: ShotSolver.BankSolution,
        result: DetectionResult
    ): DrawnOverlay {
        val sx = result.scaleToScreenX
        val sy = result.scaleToScreenY
        fun map(p: Vec2) = Vec2(p.x * sx, p.y * sy)
        val pocket = bank.bankPath.last().end
        val aim = Segment(map(bank.direct.aimLine.start), map(bank.direct.aimLine.end))
        val lines = listOf(
            DrawLine(aim, AIM_COLOR, AIM_WIDTH_PX),
            DrawLine(
                Segment(map(bank.bankPath[0].start), map(bank.bankPath[0].end)),
                BANK_COLOR, LINE_WIDTH_PX
            ),
            DrawLine(
                Segment(map(bank.bankPath[1].start), map(bank.bankPath[1].end)),
                BANK_COLOR, LINE_WIDTH_PX
            ),
            DrawLine(
                Segment(map(pocket), map(pocket)),
                POCKET_COLOR, RING_WIDTH_PX, isRing = true, ringRadius = RING_RADIUS_PX
            ),
            ghostRing(bank.direct, result, ::map)
        ) + contactMarkers(bank.bankPath, BANK_COLOR, ::map)
        return DrawnOverlay(
            lines = lines,
            labels = labelsFor(bank.direct, aim, result, BANK_COLOR) +
                distanceLabel(
                    PocketDistanceLabel.pathSegments(bank),
                    bank.bankPath.last().end, bank.bankPath.last(), result, BANK_COLOR
                ),
            tags = groupTags(result, lines.filter { !it.isRing }.map { it.segment })
        )
    }

    /** The corrected kick's lines, mapped to screen coordinates
     * (v1.0.30). The aim line (cue to rail contact) stays aim green;
     * the kicked leg (contact to ghost), the cut-angle label and the
     * pocket-distance label are kick magenta, like the live kick
     * overlay, and the target and deflection lines are drawn as for
     * a direct shot. The pocket ring marks the (unchanged) target
     * pocket, the ghost-ball ring (v1.0.26) marks the cue ball's
     * arrival point, and the rail-contact marker (v1.0.16) marks the
     * cushion spot, all three in the live overlay's positions, so
     * EDIT draws the kick the live overlay drew, not a direct shot. */
    private fun buildEditKickLines(
        kick: ShotSolver.KickSolution,
        result: DetectionResult
    ): DrawnOverlay {
        val sx = result.scaleToScreenX
        val sy = result.scaleToScreenY
        fun map(p: Vec2) = Vec2(p.x * sx, p.y * sy)
        val pocket = kick.direct.targetLine.end
        val aim = Segment(map(kick.direct.aimLine.start), map(kick.direct.aimLine.end))
        val lines = listOf(
            DrawLine(aim, AIM_COLOR, AIM_WIDTH_PX),
            DrawLine(
                Segment(map(kick.kickPath[1].start), map(kick.kickPath[1].end)),
                KICK_COLOR, LINE_WIDTH_PX
            ),
            DrawLine(
                Segment(map(kick.direct.targetLine.start), map(kick.direct.targetLine.end)),
                TARGET_COLOR, LINE_WIDTH_PX
            ),
            DrawLine(
                Segment(map(kick.direct.deflectionLine.start), map(kick.direct.deflectionLine.end)),
                DEFLECT_COLOR, LINE_WIDTH_PX
            ),
            DrawLine(
                Segment(map(pocket), map(pocket)),
                POCKET_COLOR, RING_WIDTH_PX, isRing = true, ringRadius = RING_RADIUS_PX
            ),
            ghostRing(kick.direct, result, ::map)
        ) + contactMarkers(kick.kickPath, KICK_COLOR, ::map)
        return DrawnOverlay(
            lines = lines,
            labels = labelsFor(kick.direct, aim, result, KICK_COLOR) +
                distanceLabel(
                    PocketDistanceLabel.pathSegments(kick),
                    kick.direct.targetLine.end, kick.direct.targetLine, result, KICK_COLOR
                ),
            tags = groupTags(result, lines.filter { !it.isRing }.map { it.segment })
        )
    }

    /** The corrected two-rail bank's lines, mapped to screen
     * coordinates (v1.0.33). The aim line stays aim green; the three
     * bank legs, the cut-angle label and the pocket-distance label
     * are bank cyan, like the live two-rail bank overlay. The pocket
     * ring marks the (unchanged) target pocket, the ghost-ball ring
     * (v1.0.26) marks the cue ball's arrival point, and the two
     * rail-contact markers (v1.0.16, one per interior joint of the
     * 3-segment path) mark the cushion spots, all in the live
     * overlay's positions, so EDIT draws the two-rail bank the live
     * overlay drew, not a direct shot. */
    private fun buildEditTwoRailBankLines(
        bank2: ShotSolver.TwoRailBankSolution,
        result: DetectionResult
    ): DrawnOverlay {
        val sx = result.scaleToScreenX
        val sy = result.scaleToScreenY
        fun map(p: Vec2) = Vec2(p.x * sx, p.y * sy)
        val pocket = bank2.bankPath.last().end
        val aim = Segment(map(bank2.direct.aimLine.start), map(bank2.direct.aimLine.end))
        val lines = listOf(
            DrawLine(aim, AIM_COLOR, AIM_WIDTH_PX),
            DrawLine(
                Segment(map(bank2.bankPath[0].start), map(bank2.bankPath[0].end)),
                BANK_COLOR, LINE_WIDTH_PX
            ),
            DrawLine(
                Segment(map(bank2.bankPath[1].start), map(bank2.bankPath[1].end)),
                BANK_COLOR, LINE_WIDTH_PX
            ),
            DrawLine(
                Segment(map(bank2.bankPath[2].start), map(bank2.bankPath[2].end)),
                BANK_COLOR, LINE_WIDTH_PX
            ),
            DrawLine(
                Segment(map(pocket), map(pocket)),
                POCKET_COLOR, RING_WIDTH_PX, isRing = true, ringRadius = RING_RADIUS_PX
            ),
            ghostRing(bank2.direct, result, ::map)
        ) + contactMarkers(bank2.bankPath, BANK_COLOR, ::map)
        return DrawnOverlay(
            lines = lines,
            labels = labelsFor(bank2.direct, aim, result, BANK_COLOR) +
                distanceLabel(
                    PocketDistanceLabel.pathSegments(bank2),
                    bank2.bankPath.last().end, bank2.bankPath.last(), result, BANK_COLOR
                ),
            tags = groupTags(result, lines.filter { !it.isRing }.map { it.segment })
        )
    }

    /** Group tags (v1.0.14): one small tag per detected ball, in the
     * ball's own colour family (white for the cue and the 8-ball —
     * black text would be unreadable on the dark tag backing — grey
     * for an unknown group). The anchor sits 2.2 radii off the
     * nearest drawn shot segment through the ball (or straight above
     * it when no line passes through it), so a tag never sits on the
     * line it sits next to. [segments] are screen coordinates, the
     * same space as the drawn lines. */
    private fun groupTags(result: DetectionResult, segments: List<Segment>): List<DrawLabel> {
        val sx = result.scaleToScreenX
        val sy = result.scaleToScreenY
        return result.balls.map { ball ->
            val center = Vec2(ball.center.x * sx, ball.center.y * sy)
            val radiusScreen = ball.radiusPx * sx
            val anchor = BallGroupTag.anchor(center, radiusScreen, segments)
            DrawLabel(
                ball.group.tagText(),
                tagColor(ball),
                anchor.x.toFloat(),
                anchor.y.toFloat()
            )
        }
    }

    /** Tag text colour for one ball, from its detected colour family. */
    private fun tagColor(ball: Ball): Int = when (ball.color) {
        BallColor.WHITE -> Color.WHITE
        BallColor.BLACK -> Color.WHITE
        BallColor.RED -> 0xFFFF5252.toInt()
        BallColor.YELLOW -> 0xFFFFD600.toInt()
        BallColor.BLUE -> 0xFF448AFF.toInt()
        BallColor.ORANGE -> 0xFFFFAB40.toInt()
        BallColor.GREEN -> 0xFF00E676.toInt()
        BallColor.PURPLE -> 0xFFE040FB.toInt()
        BallColor.UNKNOWN -> 0xFFBDBDBD.toInt()
    }

    /** The pocket-distance label (v1.0.15) for one shot: the total
     * travelled path in ball diameters, beside the pocket ring, in
     * the shot family's colour. [pathSegments] are working
     * coordinates, the same space as [pocket] and [arrival]; the
     * anchor is computed in screen coordinates from the mapped
     * pocket and the mapped arrival direction, so the text sits just
     * past the ring and never on the target line. Appended AFTER the
     * cut-angle labels, so the distance label is always last in
     * window order. */
    private fun distanceLabel(
        pathSegments: List<Segment>,
        pocket: Vec2,
        arrival: Segment,
        result: DetectionResult,
        color: Int
    ): DrawLabel {
        val sx = result.scaleToScreenX
        val sy = result.scaleToScreenY
        val ballRadiusWorking = result.cueBall?.radiusPx ?: 9.0
        val text = PocketDistanceLabel.format(
            PocketDistanceLabel.totalBallDiameters(pathSegments, ballRadiusWorking)
        )
        val pocketScreen = Vec2(pocket.x * sx, pocket.y * sy)
        val arrivalScreen = Segment(
            Vec2(arrival.start.x * sx, arrival.start.y * sy),
            Vec2(arrival.end.x * sx, arrival.end.y * sy)
        )
        val ballRadiusScreen = ballRadiusWorking * sx
        val anchor = PocketDistanceLabel.labelAnchor(
            pocketScreen,
            arrivalScreen.end - arrivalScreen.start,
            ballRadiusScreen,
            RING_RADIUS_PX.toDouble()
        )
        return DrawLabel(text, color, anchor.x.toFloat(), anchor.y.toFloat())
    }

    /** The cut-angle label (+ thin-cut warning) for one shot, from a
     * screen-coordinate aim segment. The label colour is the shot's
     * line family: green for a direct shot, cyan for a bank, magenta
     * for a kick. [ballRadiusScreenPx] is the ball radius in screen
     * pixels; the cue-ball position is the aim segment's start. */
    private fun labelsFor(
        shot: ShotSolver.ShotSolution,
        aimScreen: Segment,
        result: DetectionResult,
        color: Int
    ): List<DrawLabel> {
        val ballRadiusScreenPx = (result.cueBall?.radiusPx ?: 9.0) * result.scaleToScreenX
        val anchor = CutAngleLabel.labelAnchor(aimScreen, ballRadiusScreenPx)
        val out = mutableListOf(
            DrawLabel(
                CutAngleLabel.format(shot.cutAngleDegrees),
                color,
                anchor.x.toFloat(),
                anchor.y.toFloat()
            )
        )
        val warning = CutAngleLabel.warningText(shot.cutAngleDegrees)
        if (warning != null) {
            val warnAnchor = CutAngleLabel.warningAnchor(
                aimScreen.start, ballRadiusScreenPx
            )
            out += DrawLabel(
                warning,
                WARNING_COLOR,
                warnAnchor.x.toFloat(),
                warnAnchor.y.toFloat()
            )
        }
        return out
    }

    /** The blocked-path label (v1.0.19) for one shot: "Path blocked"
     * in the warning amber, at the aim line's midpoint on the
     * opposite side from the cut-angle label. Null when the shown
     * shot's path is clear ([DetectionResult.blockedBy] is null).
     * [aimScreen] is the smoothed first segment (aim line) in
     * screen coordinates, the same segment the cut-angle label is
     * built from. Since v1.0.24 this is also called from the
     * combo and kiss branches: their potting chains are flagged
     * the same way as the single-ball families, with the label
     * on the first leg's aim line. */
    private fun blockedLabel(
        aimScreen: Segment,
        result: DetectionResult
    ): DrawLabel? {
        if (result.blockedBy == null) return null
        val ballRadiusScreenPx = (result.cueBall?.radiusPx ?: 9.0) * result.scaleToScreenX
        val anchor = BlockedPathLabel.labelAnchor(aimScreen, ballRadiusScreenPx)
        return DrawLabel(
            BlockedPathLabel.TEXT,
            WARNING_COLOR,
            anchor.x.toFloat(),
            anchor.y.toFloat()
        )
    }

    /** Show the two anchor handles at the corrected cue + ghost points
     * (screen coordinates), reusing the windows across frames. The
     * handles are small touchable windows by design (Line Guide's
     * ControllerPointA/B): a touch landing exactly on one sets
     * FLAG_WINDOW_IS_OBSCURED, so EDIT mode is for arranging, not for
     * shooting. */
    private fun ensureHandles(shot: ShotSolver.ShotSolution, result: DetectionResult) {
        val sizePx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, HANDLE_SIZE_DP,
            context.resources.displayMetrics
        ).toInt()
        val positions = listOf(
            Anchor.CUE to shot.aimLine.start,
            Anchor.GHOST to shot.ghostPoint
        )
        for ((index, pair) in positions.withIndex()) {
            val (anchor, point) = pair
            val cx = (point.x * result.scaleToScreenX).toInt()
            val cy = (point.y * result.scaleToScreenY).toInt()
            val x = cx - sizePx / 2
            val y = cy - sizePx / 2
            val color = if (anchor == Anchor.CUE) AIM_COLOR else TARGET_COLOR
            if (index < handleWindows.size) {
                val win = handleWindows[index]
                win.params.x = x
                win.params.y = y
                win.view.setDot(
                    PointF(sizePx / 2f, sizePx / 2f), sizePx * 0.22f, color
                )
                windowManager.updateViewLayout(win.view, win.params)
            } else {
                val view = LineView(context)
                view.setDot(PointF(sizePx / 2f, sizePx / 2f), sizePx * 0.22f, color)
                val params = WindowManager.LayoutParams(
                    sizePx, sizePx,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    this.x = x
                    this.y = y
                }
                makeHandleDraggable(view, anchor)
                windowManager.addView(view, params)
                handleWindows += HandleWindow(anchor, view, params)
            }
        }
        while (handleWindows.size > positions.size) {
            val win = handleWindows.removeAt(handleWindows.lastIndex)
            try {
                windowManager.removeView(win.view)
            } catch (_: Exception) {
            }
        }
    }

    /** Drag a handle: screen deltas become working-coordinate deltas for
     * the anchor, then the frozen shot is re-rendered immediately. */
    private fun makeHandleDraggable(view: LineView, anchor: Anchor) {
        var lastRawX = 0f
        var lastRawY = 0f
        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    lastRawX = event.rawX
                    lastRawY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val result = lastResult ?: return@setOnTouchListener false
                    val dxScreen = event.rawX - lastRawX
                    val dyScreen = event.rawY - lastRawY
                    lastRawX = event.rawX
                    lastRawY = event.rawY
                    val delta = Vec2(
                        dxScreen / result.scaleToScreenX,
                        dyScreen / result.scaleToScreenY
                    )
                    manualAim.dragAnchor(anchor, delta, result.table)
                    editInactivity.noteInteraction(SystemClock.uptimeMillis())
                    updateEditMode(result)
                    true
                }
                else -> false
            }
        }
    }

    private fun removeHandles() {
        for (win in handleWindows) {
            try {
                windowManager.removeView(win.view)
            } catch (_: Exception) {
            }
        }
        handleWindows.clear()
    }

    /** Ghost-ball ring (v1.0.26) for one solved shot: a ball-radius
     * ring in the aim green, centred on the shot's ghost point,
     * mapped from working to screen coordinates by [map]. Practice
     * mode already draws this circle (PracticeView.drawGhost); the
     * live overlay did not. The centre is the solution's ghostPoint
     * via [GhostBallRing], never the aim line's end, which for a
     * kick is the rail contact. Like the pocket ring, the ring uses
     * the solution's own geometry unsmoothed, so it never drifts
     * from the point it marks. It hides with the lines (it is a
     * line window) and is excluded from the group-tag anchors by
     * the isRing filter, like every other ring. */
    private fun ghostRing(
        shot: ShotSolver.ShotSolution,
        result: DetectionResult,
        map: (Vec2) -> Vec2
    ): DrawLine {
        val radiusScreen =
            GhostBallRing.radius(result.cueBall?.radiusPx ?: 9.0) * result.scaleToScreenX
        val ghost = GhostBallRing.center(shot)
        return DrawLine(
            Segment(map(ghost), map(ghost)),
            AIM_COLOR, RING_WIDTH_PX, isRing = true, ringRadius = radiusScreen.toFloat()
        )
    }

    /** Rail-contact markers (v1.0.16) for one bank/kick path: one ring
     * per interior vertex of the path, in the shot family's colour.
     * The contacts come from the solution's own path (unsmoothed, like
     * the pocket ring), so a marker never drifts from the bend it marks
     * the way a smoothed segment joint could. A marker is a ring, so
     * groupTags excludes it from the tag-anchor lines and it hides with
     * the lines. A member since v1.0.29, so the bank EDIT builder can
     * use it too (it was a local fun of [buildDrawLines] before). */
    private fun contactMarkers(
        path: List<Segment>,
        color: Int,
        map: (Vec2) -> Vec2
    ): List<DrawLine> =
        RailContactMarker.contacts(path).map { contact ->
            DrawLine(
                Segment(map(contact), map(contact)),
                color, RING_WIDTH_PX, isRing = true,
                ringRadius = CONTACT_MARKER_RADIUS_PX
            )
        }

    /** Build the lines to draw, mapped from working to screen coordinates.
     * The best shot's lines go through the jitter smoother (screen
     * coordinates, Bloro's 40 px gate); candidates and the pocket ring
     * are drawn as detected. The cut-angle label (+ thin-cut warning,
     * v1.0.12) is built from the same smoothed aim segment as the
     * lines, so it moves with them; only the best shot gets one. The
     * pocket-distance label (v1.0.15) sits beside the pocket ring,
     * last in window order, in the shot family's colour. The
     * blocked-path label (v1.0.19) sits at the aim line's midpoint
     * on the opposite side from the cut-angle label, only when
     * another ball sits on the shown shot's path — never in EDIT
     * mode, where the drawn shot is the frozen manual aim, not the
     * live detection the flag belongs to. The ghost-ball ring
     * (v1.0.26) sits on the shown shot's ghost point, in the aim
     * green, and a combo or kiss draws a second ring on the second
     * leg's ghost (v1.0.27). The best shot only, never a candidate. */
    private fun buildDrawLines(result: DetectionResult): DrawnOverlay {
        val sx = result.scaleToScreenX
        val sy = result.scaleToScreenY
        fun map(p: Vec2) = Vec2(p.x * sx, p.y * sy)
        val out = mutableListOf<DrawLine>()
        var labels: List<DrawLabel> = emptyList()

        val shot = result.shot
        val bank = result.bank
        if (shot != null) {
            // Non-best candidates first (drawn underneath): aim + target
            // lines only, faint grey — no deflection, no ring, so the
            // best shot stays unambiguous. PoolAimAssist UX: every
            // candidate visible, best highlighted (yellow target line).
            if (store.showAllShots) {
                for (candidate in result.allShots.drop(1)) {
                    out += DrawLine(
                        Segment(map(candidate.aimLine.start), map(candidate.aimLine.end)),
                        CANDIDATE_COLOR, CANDIDATE_WIDTH_PX
                    )
                    out += DrawLine(
                        Segment(map(candidate.targetLine.start), map(candidate.targetLine.end)),
                        CANDIDATE_COLOR, CANDIDATE_WIDTH_PX
                    )
                }
            }
            // Jitter gate + interpolation: the best shot's lines ease
            // toward new detections instead of snapping each frame.
            val (aim, target, deflection) = smoother.smooth(
                Segment(map(shot.aimLine.start), map(shot.aimLine.end)),
                Segment(map(shot.targetLine.start), map(shot.targetLine.end)),
                Segment(map(shot.deflectionLine.start), map(shot.deflectionLine.end)),
                result.status
            )
            out += DrawLine(aim, AIM_COLOR, AIM_WIDTH_PX)
            out += DrawLine(target, TARGET_COLOR, LINE_WIDTH_PX)
            out += DrawLine(deflection, DEFLECT_COLOR, LINE_WIDTH_PX)
            // Cut-angle label (v1.0.12): the best shot's angle, in the
            // aim green, beside the smoothed aim line. Pocket-distance
            // label (v1.0.15): the total path in ball diameters,
            // beside the pocket ring, last in window order. Blocked-path
            // label (v1.0.19): "Path blocked" when another ball sits
            // on the shot's path, opposite the cut-angle label.
            labels = labelsFor(shot, aim, result, AIM_COLOR) +
                listOfNotNull(blockedLabel(aim, result)) +
                distanceLabel(
                    PocketDistanceLabel.pathSegments(shot),
                    shot.targetLine.end, shot.targetLine, result, AIM_COLOR
                )
            // Pocket ring at the target pocket.
            val pocket = shot.targetLine.end
            out += DrawLine(
                Segment(map(pocket), map(pocket)),
                POCKET_COLOR, RING_WIDTH_PX, isRing = true, ringRadius = RING_RADIUS_PX
            )
            // Ghost-ball ring (v1.0.26): the cue ball's arrival point.
            out += ghostRing(shot, result, ::map)
        } else if (bank != null && store.bankLinesEnabled) {
            val smoothed = smoother.smoothList(
                listOf(
                    Segment(map(bank.direct.aimLine.start), map(bank.direct.aimLine.end)),
                    Segment(map(bank.bankPath[0].start), map(bank.bankPath[0].end)),
                    Segment(map(bank.bankPath[1].start), map(bank.bankPath[1].end))
                ),
                result.status
            )
            out += DrawLine(smoothed[0], AIM_COLOR, AIM_WIDTH_PX)
            out += DrawLine(smoothed[1], BANK_COLOR, LINE_WIDTH_PX)
            out += DrawLine(smoothed[2], BANK_COLOR, LINE_WIDTH_PX)
            labels = labelsFor(bank.direct, smoothed[0], result, BANK_COLOR) +
                listOfNotNull(blockedLabel(smoothed[0], result)) +
                distanceLabel(
                    PocketDistanceLabel.pathSegments(bank),
                    bank.bankPath.last().end, bank.bankPath.last(), result, BANK_COLOR
                )
            val pocket = bank.bankPath.last().end
            out += DrawLine(
                Segment(map(pocket), map(pocket)),
                POCKET_COLOR, RING_WIDTH_PX, isRing = true, ringRadius = RING_RADIUS_PX
            )
            // Ghost-ball ring (v1.0.26): the cue ball's arrival point.
            out += ghostRing(bank.direct, result, ::map)
            // Rail-contact marker (v1.0.16): the cushion spot the
            // target ball must hit, in the bank cyan.
            out += contactMarkers(bank.bankPath, BANK_COLOR, ::map)
        } else if (result.kick != null && store.bankLinesEnabled) {
            // One-rail kick (v1.0.10): the cue ball goes off the rail
            // first, so the cue path has two segments — cue -> rail
            // contact in green (the aim direction, same as every other
            // shot) and contact -> ghost in magenta (the kicked cue
            // ball, distinct from the cyan bank lines). The target
            // line and deflection are drawn as for a direct shot.
            // Four smoothed segments, like a two-rail bank, so a kick
            // never lerps into a bank or a direct shot.
            val kick = result.kick
            val smoothed = smoother.smoothList(
                listOf(
                    Segment(map(kick.direct.aimLine.start), map(kick.direct.aimLine.end)),
                    Segment(map(kick.kickPath[1].start), map(kick.kickPath[1].end)),
                    Segment(map(kick.direct.targetLine.start), map(kick.direct.targetLine.end)),
                    Segment(map(kick.direct.deflectionLine.start), map(kick.direct.deflectionLine.end))
                ),
                result.status
            )
            out += DrawLine(smoothed[0], AIM_COLOR, AIM_WIDTH_PX)
            out += DrawLine(smoothed[1], KICK_COLOR, LINE_WIDTH_PX)
            out += DrawLine(smoothed[2], TARGET_COLOR, LINE_WIDTH_PX)
            out += DrawLine(smoothed[3], DEFLECT_COLOR, LINE_WIDTH_PX)
            labels = labelsFor(kick.direct, smoothed[0], result, KICK_COLOR) +
                listOfNotNull(blockedLabel(smoothed[0], result)) +
                distanceLabel(
                    PocketDistanceLabel.pathSegments(kick),
                    kick.direct.targetLine.end, kick.direct.targetLine, result, KICK_COLOR
                )
            val pocket = kick.direct.targetLine.end
            out += DrawLine(
                Segment(map(pocket), map(pocket)),
                POCKET_COLOR, RING_WIDTH_PX, isRing = true, ringRadius = RING_RADIUS_PX
            )
            // Ghost-ball ring (v1.0.26): the cue ball's arrival point.
            out += ghostRing(kick.direct, result, ::map)
            // Rail-contact marker (v1.0.16): the cushion spot the cue
            // ball must hit, in the kick magenta.
            out += contactMarkers(kick.kickPath, KICK_COLOR, ::map)
        } else if (result.bank2 != null && store.bankLinesEnabled) {
            // Two-rail bank (v1.0.9): same cyan rendering as a
            // one-rail bank, with all three path segments so both
            // rail contacts are visible.
            val bank2 = result.bank2
            val smoothed = smoother.smoothList(
                listOf(
                    Segment(map(bank2.direct.aimLine.start), map(bank2.direct.aimLine.end)),
                    Segment(map(bank2.bankPath[0].start), map(bank2.bankPath[0].end)),
                    Segment(map(bank2.bankPath[1].start), map(bank2.bankPath[1].end)),
                    Segment(map(bank2.bankPath[2].start), map(bank2.bankPath[2].end))
                ),
                result.status
            )
            out += DrawLine(smoothed[0], AIM_COLOR, AIM_WIDTH_PX)
            out += DrawLine(smoothed[1], BANK_COLOR, LINE_WIDTH_PX)
            out += DrawLine(smoothed[2], BANK_COLOR, LINE_WIDTH_PX)
            out += DrawLine(smoothed[3], BANK_COLOR, LINE_WIDTH_PX)
            labels = labelsFor(bank2.direct, smoothed[0], result, BANK_COLOR) +
                listOfNotNull(blockedLabel(smoothed[0], result)) +
                distanceLabel(
                    PocketDistanceLabel.pathSegments(bank2),
                    bank2.bankPath.last().end, bank2.bankPath.last(), result, BANK_COLOR
                )
            val pocket = bank2.bankPath.last().end
            out += DrawLine(
                Segment(map(pocket), map(pocket)),
                POCKET_COLOR, RING_WIDTH_PX, isRing = true, ringRadius = RING_RADIUS_PX
            )
            // Ghost-ball ring (v1.0.26): the cue ball's arrival point.
            out += ghostRing(bank2.direct, result, ::map)
            // Rail-contact markers (v1.0.16): both cushion spots, in
            // the bank cyan, in path order.
            out += contactMarkers(bank2.bankPath, BANK_COLOR, ::map)
        } else if (result.kick2 != null && store.bankLinesEnabled) {
            // Two-rail kick (v1.0.11): the cue ball goes off two rails
            // first, so the cue path has three segments — cue -> rail
            // 1 contact in green (the aim direction, same as every
            // other shot) and contact 1 -> contact 2 -> ghost in
            // magenta (the kicked cue ball, distinct from the cyan
            // bank lines). The target line and deflection are drawn
            // as for a direct shot. Five smoothed segments, so a
            // two-rail kick never lerps into any other shot. This
            // branch sits last: a two-rail kick is only shown when
            // it is the only shot left.
            val kick2 = result.kick2
            val smoothed = smoother.smoothList(
                listOf(
                    Segment(map(kick2.direct.aimLine.start), map(kick2.direct.aimLine.end)),
                    Segment(map(kick2.kickPath[1].start), map(kick2.kickPath[1].end)),
                    Segment(map(kick2.kickPath[2].start), map(kick2.kickPath[2].end)),
                    Segment(map(kick2.direct.targetLine.start), map(kick2.direct.targetLine.end)),
                    Segment(map(kick2.direct.deflectionLine.start), map(kick2.direct.deflectionLine.end))
                ),
                result.status
            )
            out += DrawLine(smoothed[0], AIM_COLOR, AIM_WIDTH_PX)
            out += DrawLine(smoothed[1], KICK_COLOR, LINE_WIDTH_PX)
            out += DrawLine(smoothed[2], KICK_COLOR, LINE_WIDTH_PX)
            out += DrawLine(smoothed[3], TARGET_COLOR, LINE_WIDTH_PX)
            out += DrawLine(smoothed[4], DEFLECT_COLOR, LINE_WIDTH_PX)
            labels = labelsFor(kick2.direct, smoothed[0], result, KICK_COLOR) +
                listOfNotNull(blockedLabel(smoothed[0], result)) +
                distanceLabel(
                    PocketDistanceLabel.pathSegments(kick2),
                    kick2.direct.targetLine.end, kick2.direct.targetLine, result, KICK_COLOR
                )
            val pocket = kick2.direct.targetLine.end
            out += DrawLine(
                Segment(map(pocket), map(pocket)),
                POCKET_COLOR, RING_WIDTH_PX, isRing = true, ringRadius = RING_RADIUS_PX
            )
            // Ghost-ball ring (v1.0.26): the cue ball's arrival point.
            out += ghostRing(kick2.direct, result, ::map)
            // Rail-contact markers (v1.0.16): both cushion spots, in
            // the kick magenta, in path order.
            out += contactMarkers(kick2.kickPath, KICK_COLOR, ::map)
        } else if (result.combo != null && store.bankLinesEnabled) {
            // Combo (v1.0.13): cue -> first ball -> second ball ->
            // pocket, drawn exactly the way practice mode draws it:
            // each leg is a direct shot in the standard colours
            // (green aim, yellow target, white deflection) — there
            // is no combo colour, in practice or here. The first
            // leg's target line ends at the intermediate ghost
            // point, not a pocket, so the pocket ring sits only on
            // the second leg's pocket. One cut-angle label per leg,
            // in the aim green (practice mode's colour for combo
            // legs), each with its own thin-cut warning. Six
            // smoothed segments, so a combo never lerps into any
            // other shot. This branch sits after the two-rail kick:
            // a combo is only shown when it is the only shot left
            // before a kiss.
            val combo = result.combo
            val smoothed = smoother.smoothList(
                listOf(
                    Segment(map(combo.firstShot.aimLine.start), map(combo.firstShot.aimLine.end)),
                    Segment(map(combo.firstShot.targetLine.start), map(combo.firstShot.targetLine.end)),
                    Segment(map(combo.firstShot.deflectionLine.start), map(combo.firstShot.deflectionLine.end)),
                    Segment(map(combo.secondShot.aimLine.start), map(combo.secondShot.aimLine.end)),
                    Segment(map(combo.secondShot.targetLine.start), map(combo.secondShot.targetLine.end)),
                    Segment(map(combo.secondShot.deflectionLine.start), map(combo.secondShot.deflectionLine.end))
                ),
                result.status
            )
            out += DrawLine(smoothed[0], AIM_COLOR, AIM_WIDTH_PX)
            out += DrawLine(smoothed[1], TARGET_COLOR, LINE_WIDTH_PX)
            out += DrawLine(smoothed[2], DEFLECT_COLOR, LINE_WIDTH_PX)
            out += DrawLine(smoothed[3], AIM_COLOR, AIM_WIDTH_PX)
            out += DrawLine(smoothed[4], TARGET_COLOR, LINE_WIDTH_PX)
            out += DrawLine(smoothed[5], DEFLECT_COLOR, LINE_WIDTH_PX)
            labels = labelsFor(combo.firstShot, smoothed[0], result, AIM_COLOR) +
                labelsFor(combo.secondShot, smoothed[3], result, AIM_COLOR) +
                listOfNotNull(blockedLabel(smoothed[0], result)) +
                distanceLabel(
                    PocketDistanceLabel.pathSegments(combo),
                    combo.secondShot.targetLine.end, combo.secondShot.targetLine,
                    result, AIM_COLOR
                )
            val pocket = combo.secondShot.targetLine.end
            out += DrawLine(
                Segment(map(pocket), map(pocket)),
                POCKET_COLOR, RING_WIDTH_PX, isRing = true, ringRadius = RING_RADIUS_PX
            )
            // Ghost-ball ring (v1.0.26): the cue ball's arrival point
            // (first leg's ghost only).
            out += ghostRing(combo.firstShot, result, ::map)
            // Second-leg ghost ring (v1.0.27): the first ball's
            // arrival point (the transfer point between the two
            // balls), the same circle practice draws for the
            // second leg. Same aim green, same radius.
            out += ghostRing(combo.secondShot, result, ::map)
        } else if (result.kiss != null && store.bankLinesEnabled) {
            // Kiss (v1.0.13): cue kisses the first ball thin and its
            // deflection carries it into the second ball's ghost
            // point, potting the second ball — drawn exactly the way
            // practice mode draws it: each leg is a direct shot in
            // the standard colours (green aim, yellow target, white
            // deflection). The kiss leg's target line is the kissed
            // ball's departure line (it pots nothing); its
            // deflection is the refined cue direction toward the
            // second ghost. The pocket ring sits only on the second
            // leg's pocket. One cut-angle label per leg, in the aim
            // green, each with its own thin-cut warning. Six
            // smoothed segments, so a kiss never lerps into any
            // other shot. This branch sits last: a kiss is only
            // shown when it is the only shot left, after the combo.
            val kiss = result.kiss
            val smoothed = smoother.smoothList(
                listOf(
                    Segment(map(kiss.kissShot.aimLine.start), map(kiss.kissShot.aimLine.end)),
                    Segment(map(kiss.kissShot.targetLine.start), map(kiss.kissShot.targetLine.end)),
                    Segment(map(kiss.kissShot.deflectionLine.start), map(kiss.kissShot.deflectionLine.end)),
                    Segment(map(kiss.secondShot.aimLine.start), map(kiss.secondShot.aimLine.end)),
                    Segment(map(kiss.secondShot.targetLine.start), map(kiss.secondShot.targetLine.end)),
                    Segment(map(kiss.secondShot.deflectionLine.start), map(kiss.secondShot.deflectionLine.end))
                ),
                result.status
            )
            out += DrawLine(smoothed[0], AIM_COLOR, AIM_WIDTH_PX)
            out += DrawLine(smoothed[1], TARGET_COLOR, LINE_WIDTH_PX)
            out += DrawLine(smoothed[2], DEFLECT_COLOR, LINE_WIDTH_PX)
            out += DrawLine(smoothed[3], AIM_COLOR, AIM_WIDTH_PX)
            out += DrawLine(smoothed[4], TARGET_COLOR, LINE_WIDTH_PX)
            out += DrawLine(smoothed[5], DEFLECT_COLOR, LINE_WIDTH_PX)
            labels = labelsFor(kiss.kissShot, smoothed[0], result, AIM_COLOR) +
                labelsFor(kiss.secondShot, smoothed[3], result, AIM_COLOR) +
                listOfNotNull(blockedLabel(smoothed[0], result)) +
                distanceLabel(
                    PocketDistanceLabel.pathSegments(kiss),
                    kiss.secondShot.targetLine.end, kiss.secondShot.targetLine,
                    result, AIM_COLOR
                )
            val pocket = kiss.secondShot.targetLine.end
            out += DrawLine(
                Segment(map(pocket), map(pocket)),
                POCKET_COLOR, RING_WIDTH_PX, isRing = true, ringRadius = RING_RADIUS_PX
            )
            // Ghost-ball ring (v1.0.26): the cue ball's arrival point
            // (kiss leg's ghost only).
            out += ghostRing(kiss.kissShot, result, ::map)
            // Second-leg ghost ring (v1.0.27): the point the cue
            // ball must reach after the kiss to pot the second
            // ball, the same circle practice draws for the second
            // leg. Same aim green, same radius.
            out += ghostRing(kiss.secondShot, result, ::map)
        }
        return DrawnOverlay(
            out,
            labels,
            groupTags(result, out.filter { !it.isRing }.map { it.segment })
        )
    }

    /** Reuse windows by index; add or remove as the line count changes. */
    private fun applyLines(lines: List<DrawLine>) {
        for (i in lines.indices) {
            val line = lines[i]
            val (x, y, w, h, localStart, localEnd) = windowFor(line)
            if (i < lineWindows.size) {
                val win = lineWindows[i]
                win.params.x = x
                win.params.y = y
                win.params.width = w
                win.params.height = h
                setLineOnView(win.view, line, localStart, localEnd)
                windowManager.updateViewLayout(win.view, win.params)
            } else {
                val view = LineView(context)
                val params = WindowManager.LayoutParams(
                    w, h,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    this.x = x
                    this.y = y
                }
                setLineOnView(view, line, localStart, localEnd)
                windowManager.addView(view, params)
                lineWindows += LineWindow(view, params)
            }
        }
        // Remove surplus windows.
        while (lineWindows.size > lines.size) {
            val win = lineWindows.removeAt(lineWindows.lastIndex)
            try {
                windowManager.removeView(win.view)
            } catch (_: Exception) {
            }
        }
    }

    /** Reuse label windows by index; add or remove as the label count
     * changes. Same pattern as [applyLines]: the windows are small
     * (WRAP_CONTENT around the text, centred on the label position),
     * NOT_TOUCHABLE, and dark-backed like the control bubble so the
     * text stays readable on bright felt. A label window is exactly
     * as wide as its text, so it flags no more touches as obscured
     * than the text itself covers. */
    private fun applyLabels(labels: List<DrawLabel>) {
        for (i in labels.indices) {
            val label = labels[i]
            if (i < labelWindows.size) {
                val win = labelWindows[i]
                win.view.text = label.text
                win.view.setTextColor(label.color)
                val (w, h) = measureLabel(win.view)
                win.params.x = (label.centerX - w / 2).toInt()
                win.params.y = (label.centerY - h / 2).toInt()
                win.params.width = w
                win.params.height = h
                windowManager.updateViewLayout(win.view, win.params)
            } else {
                val view = TextView(context).apply {
                    text = label.text
                    setTextColor(label.color)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, LABEL_TEXT_SIZE_SP)
                    setBackgroundColor(Color.argb(200, 0, 0, 0))
                    setPadding(8, 2, 8, 2)
                }
                val (w, h) = measureLabel(view)
                val params = WindowManager.LayoutParams(
                    w, h,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    x = (label.centerX - w / 2).toInt()
                    y = (label.centerY - h / 2).toInt()
                }
                windowManager.addView(view, params)
                labelWindows += LabelWindow(view, params)
            }
        }
        // Remove surplus windows.
        while (labelWindows.size > labels.size) {
            val win = labelWindows.removeAt(labelWindows.lastIndex)
            try {
                windowManager.removeView(win.view)
            } catch (_: Exception) {
            }
        }
    }

    /** Group tags (v1.0.14): same window mechanics as [applyLabels],
     * but on the separate [tagWindows] list so the pinned label
     * counts never change. A tag is a small NOT_TOUCHABLE TextView in
     * the ball's own colour family on the same dark backing as the
     * cut-angle labels. */
    private fun applyTags(tags: List<DrawLabel>) {
        for (i in tags.indices) {
            val tag = tags[i]
            if (i < tagWindows.size) {
                val win = tagWindows[i]
                win.view.text = tag.text
                win.view.setTextColor(tag.color)
                val (w, h) = measureLabel(win.view)
                win.params.x = (tag.centerX - w / 2).toInt()
                win.params.y = (tag.centerY - h / 2).toInt()
                win.params.width = w
                win.params.height = h
                windowManager.updateViewLayout(win.view, win.params)
            } else {
                val view = TextView(context).apply {
                    text = tag.text
                    setTextColor(tag.color)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, LABEL_TEXT_SIZE_SP)
                    setBackgroundColor(Color.argb(200, 0, 0, 0))
                    setPadding(8, 2, 8, 2)
                }
                val (w, h) = measureLabel(view)
                val params = WindowManager.LayoutParams(
                    w, h,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    x = (tag.centerX - w / 2).toInt()
                    y = (tag.centerY - h / 2).toInt()
                }
                windowManager.addView(view, params)
                tagWindows += LabelWindow(view, params)
            }
        }
        // Remove surplus windows.
        while (tagWindows.size > tags.size) {
            val win = tagWindows.removeAt(tagWindows.lastIndex)
            try {
                windowManager.removeView(win.view)
            } catch (_: Exception) {
            }
        }
    }

    /** Measure a label view at its current text (UNSPECIFIED both
     * ways, so the window wraps the text). */
    private fun measureLabel(view: TextView): Pair<Int, Int> {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        return view.measuredWidth to view.measuredHeight
    }

    private fun removeLabelWindows() {
        for (win in labelWindows) {
            try {
                windowManager.removeView(win.view)
            } catch (_: Exception) {
            }
        }
        labelWindows.clear()
    }

    private fun removeTagWindows() {
        for (win in tagWindows) {
            try {
                windowManager.removeView(win.view)
            } catch (_: Exception) {
            }
        }
        tagWindows.clear()
    }

    private data class WindowBox(
        val x: Int, val y: Int, val w: Int, val h: Int,
        val localStart: PointF, val localEnd: PointF
    )

    private fun windowFor(line: DrawLine): WindowBox {
        return if (line.isRing) {
            val r = line.ringRadius + PADDING_PX
            val cx = line.segment.start.x
            val cy = line.segment.start.y
            WindowBox(
                (cx - r).toInt(), (cy - r).toInt(),
                (2 * r).toInt(), (2 * r).toInt(),
                PointF(r, r), PointF(r, r)
            )
        } else {
            val x0 = min(line.segment.start.x, line.segment.end.x) - PADDING_PX
            val y0 = min(line.segment.start.y, line.segment.end.y) - PADDING_PX
            val x1 = max(line.segment.start.x, line.segment.end.x) + PADDING_PX
            val y1 = max(line.segment.start.y, line.segment.end.y) + PADDING_PX
            WindowBox(
                x0.toInt(), y0.toInt(),
                (x1 - x0).toInt(), (y1 - y0).toInt(),
                PointF((line.segment.start.x - x0).toFloat(), (line.segment.start.y - y0).toFloat()),
                PointF((line.segment.end.x - x0).toFloat(), (line.segment.end.y - y0).toFloat())
            )
        }
    }

    private fun setLineOnView(view: LineView, line: DrawLine, localStart: PointF, localEnd: PointF) {
        if (line.isRing) {
            view.setRing(localStart, line.ringRadius, line.color, line.widthPx)
        } else {
            view.setLine(localStart, localEnd, line.color, line.widthPx)
        }
    }

    private fun removeLineWindows() {
        for (win in lineWindows) {
            try {
                windowManager.removeView(win.view)
            } catch (_: Exception) {
            }
        }
        lineWindows.clear()
    }

    /** Hide/Show toggle, shared by the bubble's Hide button and a tap
     * on the bubble background (v1.0.5, Bloro lead). */
    private fun toggleGuidesHidden() {
        store.guidesHidden = !store.guidesHidden
        hideButton?.text = if (store.guidesHidden) context.getString(R.string.show_guides)
        else context.getString(R.string.hide_guides)
        DiagnosticsLog.record(
            if (store.guidesHidden) DiagnosticsLog.Kind.GUIDES_HIDDEN
            else DiagnosticsLog.Kind.GUIDES_SHOWN
        )
        if (store.guidesHidden) {
            removeLineWindows()
            removeLabelWindows()
            removeTagWindows()
        }
    }

    // ---- Control bubble (draggable, small footprint) ----
    // Bubble behaviour (v1.0.5, Bloro lead): drag after 10 dp of
    // movement; a tap (short, no drag) on the bubble background toggles
    // the guides; on release after a drag the bubble edge-snaps and its
    // position is saved. The ring at the left of the bubble shows the
    // table-lock progress (Bloro's ring showed remaining paid minutes;
    // ours has no credits, so the ring shows the lock instead).
    private fun ensureBubble() {
        if (bubbleView != null) return
        val metrics = context.resources.displayMetrics
        val edgeMarginPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, BUBBLE_EDGE_MARGIN_DP, metrics
        ).toInt()
        val dragThresholdPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, BUBBLE_DRAG_THRESHOLD_DP, metrics
        )
        val ringSizePx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, BUBBLE_RING_SIZE_DP, metrics
        ).toInt()
        val defaultYPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, BUBBLE_DEFAULT_Y_DP, metrics
        ).toInt()

        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.argb(200, 0, 0, 0))
            setPadding(8, 8, 8, 8)
        }
        val ring = LockRingView(context).apply {
            layoutParams = LinearLayout.LayoutParams(ringSizePx, ringSizePx).apply {
                gravity = Gravity.CENTER_VERTICAL
                marginEnd = 8
            }
        }
        lockRing = ring
        layout.addView(ring)
        // "Capture blocked" status (v1.0.37, REA hardening R2): a small
        // amber label that appears only while a black run persists past
        // the threshold, so the user knows the game is hiding the
        // table. GONE the rest of the time, so the bubble keeps its
        // normal size.
        val blocked = TextView(context).apply {
            text = context.getString(R.string.bubble_capture_blocked)
            setTextColor(WARNING_COLOR)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_VERTICAL
                marginEnd = 8
            }
            visibility = View.GONE
        }
        blockedStatusView = blocked
        layout.addView(blocked)
        // Session pot-rate stat (v1.0.42, REA hardening R6): a small
        // label that appears once the first shot of the session is
        // taken, GONE before that, so the bubble keeps its normal
        // size. Updated from update(), not from here.
        val potRate = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_VERTICAL
                marginEnd = 8
            }
            visibility = View.GONE
        }
        potRateView = potRate
        layout.addView(potRate)
        val hide = Button(context).apply {
            text = if (store.guidesHidden) context.getString(R.string.show_guides)
            else context.getString(R.string.hide_guides)
            setOnClickListener { toggleGuidesHidden() }
        }
        hideButton = hide
        val edit = Button(context).apply {
            text = context.getString(R.string.edit_guides)
            setOnClickListener { toggleEditMode() }
        }
        editButton = edit
        val stop = Button(context).apply {
            text = context.getString(R.string.stop)
            setOnClickListener { onStopRequested() }
        }
        layout.addView(hide)
        layout.addView(edit)
        layout.addView(stop)

        // TOP|START so edge-snapping is plain left/right math. The
        // saved position wins; otherwise the bubble starts top-right.
        val screenWidth = metrics.widthPixels
        val savedX = store.bubbleX
        val savedY = store.bubbleY
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = savedX ?: run {
                layout.measure(
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                )
                (screenWidth - layout.measuredWidth - edgeMarginPx)
                    .coerceAtLeast(edgeMarginPx)
            }
            y = savedY ?: defaultYPx
        }
        // Drag the bubble by touching anywhere on it except the buttons.
        // Buttons still get their clicks: a touch landing on a button is
        // dispatched to the button first, never to this listener.
        val drag = BubbleDrag(dragThresholdPx)
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        layout.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    drag.onDown(event.rawX, event.rawY, event.eventTime)
                    downX = event.rawX
                    downY = event.rawY
                    startX = params.x
                    startY = params.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (drag.onMove(event.rawX, event.rawY)) {
                        params.x = startX + (event.rawX - downX).toInt()
                        params.y = startY + (event.rawY - downY).toInt()
                        try {
                            windowManager.updateViewLayout(layout, params)
                        } catch (_: Exception) {
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    when (drag.onUp(event.eventTime)) {
                        BubbleDrag.Outcome.Tap -> toggleGuidesHidden()
                        is BubbleDrag.Outcome.Drag -> {
                            val width = if (layout.width > 0) layout.width
                            else layout.measuredWidth
                            params.x = BubbleDrag.snapX(
                                params.x, width, screenWidth, edgeMarginPx
                            )
                            try {
                                windowManager.updateViewLayout(layout, params)
                            } catch (_: Exception) {
                            }
                            store.bubbleX = params.x
                            store.bubbleY = params.y
                        }
                        BubbleDrag.Outcome.Hold -> Unit
                    }
                    true
                }
                else -> false
            }
        }
        windowManager.addView(layout, params)
        bubbleView = layout
        bubbleParams = params
    }

    fun removeAll() {
        smoother.reset()
        aimTracker.reset()
        manualAim.exitEditMode()
        removeLineWindows()
        removeLabelWindows()
        removeTagWindows()
        removeHandles()
        bubbleView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {
            }
        }
        bubbleView = null
        bubbleParams = null
        lockRing = null
        blockedStatusView = null
        potRateView = null
        lastPotRateText = null
        hideButton = null
        editButton = null
        framesWithoutGuideline = 0
        lastResult = null
    }
}
