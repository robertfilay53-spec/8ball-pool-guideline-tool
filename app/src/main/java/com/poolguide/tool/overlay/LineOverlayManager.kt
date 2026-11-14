package com.poolguide.tool.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.PointF
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import com.poolguide.tool.R
import com.poolguide.tool.cv.DetectionResult
import com.poolguide.tool.data.CalibrationStore
import com.poolguide.tool.geometry.Segment
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
    }

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private data class LineWindow(
        val view: LineView,
        val params: WindowManager.LayoutParams
    )

    private val lineWindows = mutableListOf<LineWindow>()
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var bubbleView: View? = null
    private var hideButton: Button? = null
    private var framesWithoutGuideline = 0
    /** Jitter gate + interpolation (v1.0.3, Bloro lead): the best
     * shot's lines ease toward new detections instead of snapping. */
    private val smoother = LineSmoother()

    /** Test-only: how many line windows are currently shown. */
    @androidx.annotation.VisibleForTesting
    val lineWindowCount: Int get() = lineWindows.size

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
        if (result.guideline.isEmpty()) {
            framesWithoutGuideline++
        } else {
            framesWithoutGuideline = 0
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
            val (aim, seg1, seg2) = smoother.smooth(
                Segment(map(bank.direct.aimLine.start), map(bank.direct.aimLine.end)),
                Segment(map(bank.bankPath[0].start), map(bank.bankPath[0].end)),
                Segment(map(bank.bankPath[1].start), map(bank.bankPath[1].end)),
                result.status
            )
            out += DrawLine(aim, AIM_COLOR, AIM_WIDTH_PX)
            out += DrawLine(seg1, BANK_COLOR, LINE_WIDTH_PX)
            out += DrawLine(seg2, BANK_COLOR, LINE_WIDTH_PX)
            val pocket = bank.bankPath.last().end
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

    // ---- Control bubble (draggable, small footprint) ----
    private fun ensureBubble() {
        if (bubbleView != null) return
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.argb(200, 0, 0, 0))
            setPadding(8, 8, 8, 8)
        }
        val hide = Button(context).apply {
            text = if (store.guidesHidden) context.getString(R.string.show_guides)
            else context.getString(R.string.hide_guides)
            setOnClickListener {
                store.guidesHidden = !store.guidesHidden
                text = if (store.guidesHidden) context.getString(R.string.show_guides)
                else context.getString(R.string.hide_guides)
                if (store.guidesHidden) removeLineWindows()
            }
        }
        hideButton = hide
        val stop = Button(context).apply {
            text = context.getString(R.string.stop)
            setOnClickListener { onStopRequested() }
        }
        layout.addView(hide)
        layout.addView(stop)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 16
            y = 120
        }
        // Drag the bubble by touching anywhere on it except the buttons.
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        layout.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = params.x
                    startY = params.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (downX - event.rawX).toInt()
                    params.y = startY + (event.rawY - downY).toInt()
                    try {
                        windowManager.updateViewLayout(layout, params)
                    } catch (_: Exception) {
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
        removeLineWindows()
        bubbleView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {
            }
        }
        bubbleView = null
        bubbleParams = null
        hideButton = null
        framesWithoutGuideline = 0
    }
}
