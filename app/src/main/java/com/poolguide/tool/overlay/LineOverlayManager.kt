package com.poolguide.tool.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.PointF
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import com.poolguide.tool.R
import com.poolguide.tool.cv.DetectionResult
import com.poolguide.tool.data.CalibrationStore
import com.poolguide.tool.data.DiagnosticsLog
import com.poolguide.tool.geometry.Segment
import com.poolguide.tool.geometry.ShotSolver
import com.poolguide.tool.geometry.Vec2
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
 *    We cannot observe the user's touches (FLAG_NOT_TOUCHABLE passes them
 *    through), so the toggle is manual — see README, "Honest limits".
 * 3. Auto-hide: when the game's own guideline is lost for 15 consecutive
 *    frames, the lines are removed — a stale shot is worse than no shot.
 * 4. Windows are reused by index across frames (updateViewLayout), not
 *    re-added, to avoid flicker.
 * 5. EDIT mode (v1.0.4, Line Guide lead): the bubble's Edit button freezes
 *    the current shot and shows two draggable anchor handles (cue +
 *    ghost). The handles are small touchable windows by design — a touch
 *    landing exactly on one sets FLAG_WINDOW_IS_OBSCURED, so EDIT mode is
 *    for arranging, not for shooting; leaving it resumes the live
 *    detection. In EDIT mode the lines do not auto-hide on guideline
 *    loss (the user is editing precisely because detection missed) and
 *    do not follow new detections.
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

    private val lineWindows = mutableListOf<LineWindow>()
    private val handleWindows = mutableListOf<HandleWindow>()
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var bubbleView: View? = null
    /** Lock-progress ring on the bubble (v1.0.5). */
    private var lockRing: LockRingView? = null
    private var hideButton: Button? = null
    private var editButton: Button? = null
    private var framesWithoutGuideline = 0
    /** EDIT-mode state (v1.0.4): the frozen shot + anchor overrides. */
    private val manualAim = ManualAim()
    /** The latest result, kept so a handle drag can re-render without
     * waiting for the next frame. */
    private var lastResult: DetectionResult? = null
    /** Jitter gate + interpolation (v1.0.3, Bloro lead): the best
     * shot's lines ease toward new detections instead of snapping. */
    private val smoother = LineSmoother()

    /** Test-only: how many line windows are currently shown. */
    @androidx.annotation.VisibleForTesting
    val lineWindowCount: Int get() = lineWindows.size

    /** Test-only: whether EDIT mode is active. */
    @androidx.annotation.VisibleForTesting
    val isEditMode: Boolean get() = manualAim.isEditMode

    /** Test-only: how many anchor-handle windows are currently shown. */
    @androidx.annotation.VisibleForTesting
    val handleWindowCount: Int get() = handleWindows.size

    /** Test-only: the corrected shot currently drawn in EDIT mode. */
    @androidx.annotation.VisibleForTesting
    val editedShot: ShotSolver.ShotSolution? get() = manualAim.currentShot()

    /** Test-only: the lock-ring progress currently shown on the bubble
     * (-1 when the bubble does not exist yet). */
    @androidx.annotation.VisibleForTesting
    val lockRingProgress: Float get() = lockRing?.progress ?: -1f

    /** Test-only: whether the lock ring is showing its frozen (EDIT
     * mode) state rather than live lock progress. */
    @androidx.annotation.VisibleForTesting
    val lockRingFrozen: Boolean get() = lockRing?.isFrozen ?: false

    /** Toggle EDIT mode (the bubble's Edit/Done button). Entering freezes
     * the current shot; leaving resumes the live detection and drops
     * any corrections. With no shot to edit, the tap does nothing. */
    fun toggleEditMode() {
        if (manualAim.isEditMode) {
            manualAim.exitEditMode()
            removeHandles()
            editButton?.text = context.getString(R.string.edit_guides)
            lockRing?.isFrozen = false
            DiagnosticsLog.record(DiagnosticsLog.Kind.EDIT_EXIT)
            return
        }
        val result = lastResult
        val shot = result?.shot
        if (shot == null) {
            Toast.makeText(context, R.string.no_shot_to_edit, Toast.LENGTH_SHORT).show()
            return
        }
        smoother.reset()
        manualAim.enterEditMode(shot, result.cueBall?.radiusPx ?: 9.0)
        editButton?.text = context.getString(R.string.done_editing)
        lockRing?.isFrozen = true
        DiagnosticsLog.record(DiagnosticsLog.Kind.EDIT_ENTER)
        result.let { updateEditMode(it) }
    }

    /** Test-only: drag an anchor by a working-coordinate delta, then
     * re-render. No-op outside EDIT mode. */
    @androidx.annotation.VisibleForTesting
    fun applyAnchorDrag(anchor: Anchor, deltaWorking: Vec2) {
        if (!manualAim.isEditMode) return
        manualAim.dragAnchor(anchor, deltaWorking, lastResult?.table)
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

    fun update(result: DetectionResult) {
        ensureBubble()
        // Lock ring (v1.0.5): fills as the table stabilises, green
        // once locked, empty while no table is detected. In EDIT mode
        // (v1.0.6) the shot is frozen, so the ring shows the neutral
        // frozen state instead of implying live lock progress.
        lockRing?.isFrozen = manualAim.isEditMode
        lockRing?.progress = result.tableLockProgress
        lastResult = result
        if (result.guideline.isEmpty()) {
            framesWithoutGuideline++
        } else {
            framesWithoutGuideline = 0
        }

        // EDIT mode (v1.0.4): the frozen shot is drawn, not the live
        // detection — see updateEditMode.
        if (manualAim.isEditMode) {
            updateEditMode(result)
            return
        }

        // Table-lock gate (v1.0.3, Bloro lead): draw nothing until the
        // table rect has been stable for 5 s. A new table, a moved
        // table, or a lost table all clear the lock in the pipeline.
        if (!result.tableLocked) {
            smoother.reset()
            removeLineWindows()
            return
        }

        val hide = store.guidesHidden ||
            result.isCaptureBlack ||
            framesWithoutGuideline > NO_GUIDELINE_HIDE_AFTER_FRAMES
        if (hide) {
            removeLineWindows()
            return
        }

        val lines = buildDrawLines(result)
        if (lines.isEmpty()) {
            removeLineWindows()
            return
        }
        applyLines(lines)
    }

    /** EDIT-mode frame: draw the frozen shot with any anchor corrections.
     * No table-lock gate and no guideline-loss auto-hide — the user is
     * editing precisely because detection missed — and no smoothing or
     * candidate shots: the drag IS the direct control. Only the explicit
     * hide toggle and a black capture still remove the lines. */
    private fun updateEditMode(result: DetectionResult) {
        if (store.guidesHidden || result.isCaptureBlack) {
            removeLineWindows()
            removeHandles()
            return
        }
        val shot = manualAim.currentShot()
        if (shot == null) {
            removeLineWindows()
            removeHandles()
            return
        }
        val lines = buildEditLines(shot, result)
        if (lines.isEmpty()) {
            removeLineWindows()
            removeHandles()
            return
        }
        applyLines(lines)
        ensureHandles(shot, result)
    }

    /** The corrected shot's lines, mapped to screen coordinates. The
     * pocket ring marks the (unchanged) target pocket. */
    private fun buildEditLines(
        shot: ShotSolver.ShotSolution,
        result: DetectionResult
    ): List<DrawLine> {
        val sx = result.scaleToScreenX
        val sy = result.scaleToScreenY
        fun map(p: Vec2) = Vec2(p.x * sx, p.y * sy)
        val pocket = shot.targetLine.end
        return listOf(
            DrawLine(
                Segment(map(shot.aimLine.start), map(shot.aimLine.end)),
                AIM_COLOR, AIM_WIDTH_PX
            ),
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
                POCKET_COLOR, RING_WIDTH_PX, isRing = true, ringRadius = 18f
            )
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

    /** Build the lines to draw, mapped from working to screen coordinates.
     * The best shot's lines go through the jitter smoother (screen
     * coordinates, Bloro's 40 px gate); candidates and the pocket ring
     * are drawn as detected. */
    private fun buildDrawLines(result: DetectionResult): List<DrawLine> {
        val sx = result.scaleToScreenX
        val sy = result.scaleToScreenY
        fun map(p: Vec2) = Vec2(p.x * sx, p.y * sy)
        val out = mutableListOf<DrawLine>()

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
            // Pocket ring at the target pocket.
            val pocket = shot.targetLine.end
            out += DrawLine(
                Segment(map(pocket), map(pocket)),
                POCKET_COLOR, RING_WIDTH_PX, isRing = true, ringRadius = 18f
            )
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
            val pocket = bank.bankPath.last().end
            out += DrawLine(
                Segment(map(pocket), map(pocket)),
                POCKET_COLOR, RING_WIDTH_PX, isRing = true, ringRadius = 18f
            )
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
            val pocket = kick.direct.targetLine.end
            out += DrawLine(
                Segment(map(pocket), map(pocket)),
                POCKET_COLOR, RING_WIDTH_PX, isRing = true, ringRadius = 18f
            )
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
            val pocket = bank2.bankPath.last().end
            out += DrawLine(
                Segment(map(pocket), map(pocket)),
                POCKET_COLOR, RING_WIDTH_PX, isRing = true, ringRadius = 18f
            )
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
            val pocket = kick2.direct.targetLine.end
            out += DrawLine(
                Segment(map(pocket), map(pocket)),
                POCKET_COLOR, RING_WIDTH_PX, isRing = true, ringRadius = 18f
            )
        }
        return out
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
        if (store.guidesHidden) removeLineWindows()
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
        manualAim.exitEditMode()
        removeLineWindows()
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
        hideButton = null
        editButton = null
        framesWithoutGuideline = 0
        lastResult = null
    }
}
