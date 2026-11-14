package com.poolguide.tool.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.poolguide.tool.cv.TableRect
import com.poolguide.tool.geometry.BallGroupTag
import com.poolguide.tool.geometry.CutAngleLabel
import com.poolguide.tool.geometry.Segment
import com.poolguide.tool.geometry.ShotSolver
import com.poolguide.tool.geometry.Vec2

/**
 * The practice table view (Cheto lead, drawn from scratch with our own
 * solver). Draws the table, pockets, balls and the solved shot in a
 * fixed logical space (720x360, table 640x320) scaled to fit the view.
 * Dragging a ball moves it (clamped inside the table); the lines update
 * live from [PracticeState.solve]. No capture, no overlay, no game.
 */
class PracticeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    companion object {
        /** Logical space the state lives in (720-wide working coords). */
        const val LOGICAL_WIDTH = 720.0
        const val LOGICAL_HEIGHT = 360.0

        /** The logical table: 640x320, centred with a 40 px margin. */
        val LOGICAL_TABLE = TableRect(
            Vec2(40.0, 20.0), Vec2(680.0, 20.0),
            Vec2(680.0, 340.0), Vec2(40.0, 340.0)
        )
        const val BALL_RADIUS_PX = 9.0
    }

    var state: PracticeState? = null

    private var scale = 1.0
    private var offsetX = 0.0
    private var offsetY = 0.0
    private var draggingId = -1

    private val feltPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0x1B, 0x7A, 0x3D)
        style = Paint.Style.FILL
    }
    private val railPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0x5D, 0x3A, 0x1A)
        style = Paint.Style.STROKE
        strokeWidth = 10f
    }
    private val pocketPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.FILL
    }
    private val cuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val cueDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.LTGRAY
        style = Paint.Style.FILL
    }
    private val targetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0xD3, 0x2F, 0x2F)
        style = Paint.Style.FILL
    }
    /** Middle ball (combo first ball / kiss ball): blue, distinct from
     * the white cue and the red target. */
    private val middlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0x1E, 0x88, 0xE5)
        style = Paint.Style.FILL
    }
    private val selectPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.YELLOW
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val aimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF00E676.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 5f
        strokeCap = Paint.Cap.ROUND
    }
    private val targetLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFD600.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 4f
        strokeCap = Paint.Cap.ROUND
    }
    private val deflectPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 4f
        strokeCap = Paint.Cap.ROUND
    }
    /** Bank path (target -> rail contact -> pocket): cyan, the same
     * colour the live overlay uses for bank lines. */
    private val bankPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF00E5FF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 4f
        strokeCap = Paint.Cap.ROUND
    }
    /** Kick path (cue -> rail contact -> ghost): magenta, the same
     * colour the live overlay uses for kick lines (distinct from the
     * cyan bank lines). */
    private val kickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFF4081.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 4f
        strokeCap = Paint.Cap.ROUND
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFF5252.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    /** Cut-angle readout text (v1.0.12): centred on its anchor, colour
     * set per shot family at draw time (green direct, cyan bank,
     * magenta kick). Text size is set from the view scale at draw
     * time — the paints are shared, so the size is not fixed here. */
    private val angleTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF00E676.toInt()
        style = Paint.Style.FILL
        textAlign = Paint.Align.CENTER
    }
    /** Thin-cut warning text (v1.0.12): amber, the same contrasting
     * colour the live overlay uses, so a low-percentage shot reads
     * the same in practice as over the game. */
    private val warningTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFAB00.toInt()
        style = Paint.Style.FILL
        textAlign = Paint.Align.CENTER
    }
    /** Group-tag text (v1.0.14): centred on its anchor, colour set
     * per ball at draw time (white cue, blue middle, red target —
     * the ball's own paint colour). Text size is set from the view
     * scale at draw time, like the cut-angle label. */
    private val groupTagPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        textAlign = Paint.Align.CENTER
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        scale = minOf(w / LOGICAL_WIDTH, h / LOGICAL_HEIGHT)
        offsetX = (w - LOGICAL_WIDTH * scale) / 2.0
        offsetY = (h - LOGICAL_HEIGHT * scale) / 2.0
    }

    private fun toView(p: Vec2): Vec2 =
        Vec2(offsetX + p.x * scale, offsetY + p.y * scale)

    private fun toLogical(x: Float, y: Float): Vec2 =
        Vec2((x - offsetX) / scale, (y - offsetY) / scale)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val st = state ?: return
        val t = LOGICAL_TABLE
        val tl = toView(t.topLeft)
        val br = toView(t.bottomRight)
        // Table + rail.
        canvas.drawRect(
            tl.x.toFloat(), tl.y.toFloat(), br.x.toFloat(), br.y.toFloat(), feltPaint
        )
        canvas.drawRect(
            tl.x.toFloat(), tl.y.toFloat(), br.x.toFloat(), br.y.toFloat(), railPaint
        )
        // Pockets.
        val pocketR = (BALL_RADIUS_PX * 1.6 * scale).toFloat()
        for (pocket in t.pockets()) {
            val v = toView(pocket)
            canvas.drawCircle(v.x.toFloat(), v.y.toFloat(), pocketR, pocketPaint)
        }
        // Solved shot lines (v1.0.6: direct, combo, and kiss modes;
        // v1.0.7: bank mode).
        val solution = st.solve()
        when (solution) {
            is PracticeState.PracticeSolution.Direct -> drawShot(canvas, solution.shot)
            is PracticeState.PracticeSolution.Bank -> drawBank(canvas, solution.solution.direct, solution.solution.bankPath)
            is PracticeState.PracticeSolution.Bank2 -> drawBank(canvas, solution.solution.direct, solution.solution.bankPath)
            is PracticeState.PracticeSolution.Kick -> drawKick(canvas, solution.solution.direct, solution.solution.kickPath)
            is PracticeState.PracticeSolution.Kick2 -> drawKick(canvas, solution.solution.direct, solution.solution.kickPath)
            is PracticeState.PracticeSolution.Combo -> {
                val combo = solution.solution
                // First leg's target line ends at the intermediate ghost
                // point, not a pocket, so no pocket ring on it.
                drawShot(canvas, combo.firstShot, pocket = false)
                drawShot(canvas, combo.secondShot)
            }
            is PracticeState.PracticeSolution.Kiss -> {
                val kiss = solution.solution
                // The kiss leg's target line ends at the cue's ghost
                // point, not a pocket, so no pocket ring on it.
                drawShot(canvas, kiss.kissShot, pocket = false)
                drawShot(canvas, kiss.secondShot)
            }
            null -> {
                // No shot in this layout; the status line says so.
            }
        }
        // Balls.
        val ballR = (BALL_RADIUS_PX * scale).toFloat()
        for (ball in st.balls()) {
            val v = toView(ball.center)
            canvas.drawCircle(
                v.x.toFloat(), v.y.toFloat(), ballR,
                when {
                    ball.isCue -> cuePaint
                    ball.id == 2 -> middlePaint
                    else -> targetPaint
                }
            )
            if (ball.isCue) {
                canvas.drawCircle(
                    v.x.toFloat(), v.y.toFloat(), ballR * 0.3f, cueDotPaint
                )
            }
            if (ball.id == st.selectedId) {
                canvas.drawCircle(
                    v.x.toFloat(), v.y.toFloat(), ballR + 6f, selectPaint
                )
            }
        }
        // Group tags (v1.0.14): the same tags as the live overlay —
        // the ball's group, in the ball's own paint colour, anchored
        // 2.2 radii off the nearest shot segment through the ball (or
        // straight above it when no line passes through it), so a tag
        // never sits on the line it sits next to. Tags draw only when
        // a shot solves, the same as the lines.
        if (solution != null) {
            val segments = segmentsFor(solution)
            for (ball in st.balls()) {
                val anchor = BallGroupTag.anchor(ball.center, BALL_RADIUS_PX, segments)
                groupTagPaint.color = when {
                    ball.isCue -> Color.WHITE
                    ball.id == 2 -> Color.BLUE
                    else -> Color.RED
                }
                groupTagPaint.textSize = (16.0 * scale).toFloat()
                val ap = toView(anchor)
                canvas.drawText(
                    ball.group.tagText(),
                    ap.x.toFloat(), ap.y.toFloat(), groupTagPaint
                )
            }
        }
    }

    /** The shot segments of a solution, in logical coordinates — the
     * lines the group tags anchor off (v1.0.14). Mirrors the draw
     * calls in onDraw so the anchor rule sees exactly the lines that
     * are on screen. */
    private fun segmentsFor(
        solution: PracticeState.PracticeSolution
    ): List<Segment> {
        return when (solution) {
            is PracticeState.PracticeSolution.Direct -> listOf(
                solution.shot.aimLine,
                solution.shot.targetLine,
                solution.shot.deflectionLine
            )
            is PracticeState.PracticeSolution.Bank ->
                listOf(solution.solution.direct.aimLine) + solution.solution.bankPath
            is PracticeState.PracticeSolution.Bank2 ->
                listOf(solution.solution.direct.aimLine) + solution.solution.bankPath
            is PracticeState.PracticeSolution.Kick ->
                listOf(solution.solution.direct.aimLine) +
                    solution.solution.kickPath.drop(1) +
                    listOf(
                        solution.solution.direct.targetLine,
                        solution.solution.direct.deflectionLine
                    )
            is PracticeState.PracticeSolution.Kick2 ->
                listOf(solution.solution.direct.aimLine) +
                    solution.solution.kickPath.drop(1) +
                    listOf(
                        solution.solution.direct.targetLine,
                        solution.solution.direct.deflectionLine
                    )
            is PracticeState.PracticeSolution.Combo -> listOf(
                solution.solution.firstShot.aimLine,
                solution.solution.firstShot.targetLine,
                solution.solution.firstShot.deflectionLine,
                solution.solution.secondShot.aimLine,
                solution.solution.secondShot.targetLine,
                solution.solution.secondShot.deflectionLine
            )
            is PracticeState.PracticeSolution.Kiss -> listOf(
                solution.solution.kissShot.aimLine,
                solution.solution.kissShot.targetLine,
                solution.solution.kissShot.deflectionLine,
                solution.solution.secondShot.aimLine,
                solution.solution.secondShot.targetLine,
                solution.solution.secondShot.deflectionLine
            )
        }
    }

    /** Draw one bank shot the way the live overlay does: the green aim
     * line from the bank's direct solve, the cyan bank-path segments
     * (target -> rail contact(s) -> pocket; two segments for a
     * one-rail bank, three for a two-rail bank), the ghost circle at
     * impact, and the pocket ring at the pocket. The direct solve's
     * own target line is NOT drawn — it points at the mirrored pocket,
     * which sits off the table. */
    private fun drawBank(
        canvas: Canvas,
        direct: ShotSolver.ShotSolution,
        bankPath: List<Segment>
    ) {
        val aimS = toView(direct.aimLine.start)
        val aimE = toView(direct.aimLine.end)
        canvas.drawLine(
            aimS.x.toFloat(), aimS.y.toFloat(),
            aimE.x.toFloat(), aimE.y.toFloat(), aimPaint
        )
        for (segment in bankPath) {
            val s = toView(segment.start)
            val e = toView(segment.end)
            canvas.drawLine(
                s.x.toFloat(), s.y.toFloat(),
                e.x.toFloat(), e.y.toFloat(), bankPaint
            )
        }
        drawGhost(canvas, direct.ghostPoint)
        val pocket = toView(bankPath.last().end)
        canvas.drawCircle(
            pocket.x.toFloat(), pocket.y.toFloat(),
            (BALL_RADIUS_PX * 1.8 * scale).toFloat(), ringPaint
        )
        drawCutAngleLabel(canvas, direct, bankPaint.color)
    }

    /** Draw one kick shot the way the live overlay does: the green
     * aim line (cue -> first rail contact, the cue-stick direction),
     * the magenta kicked cue-ball path (every [kickPath] segment
     * after the first: contact -> ghost for a one-rail kick,
     * contact 1 -> contact 2 -> ghost for a two-rail kick), the
     * yellow target line (target -> pocket), the white deflection,
     * the ghost circle at impact, and the pocket ring at the pocket.
     * Unlike a bank, the target line IS drawn: the target goes
     * straight to the pocket, it is the cue ball that banks. */
    private fun drawKick(
        canvas: Canvas,
        direct: ShotSolver.ShotSolution,
        kickPath: List<Segment>
    ) {
        val aimS = toView(direct.aimLine.start)
        val aimE = toView(direct.aimLine.end)
        canvas.drawLine(
            aimS.x.toFloat(), aimS.y.toFloat(),
            aimE.x.toFloat(), aimE.y.toFloat(), aimPaint
        )
        for (segment in kickPath.drop(1)) {
            val s = toView(segment.start)
            val e = toView(segment.end)
            canvas.drawLine(
                s.x.toFloat(), s.y.toFloat(),
                e.x.toFloat(), e.y.toFloat(), kickPaint
            )
        }
        val tgtS = toView(direct.targetLine.start)
        val tgtE = toView(direct.targetLine.end)
        canvas.drawLine(
            tgtS.x.toFloat(), tgtS.y.toFloat(),
            tgtE.x.toFloat(), tgtE.y.toFloat(), targetLinePaint
        )
        val defS = toView(direct.deflectionLine.start)
        val defE = toView(direct.deflectionLine.end)
        canvas.drawLine(
            defS.x.toFloat(), defS.y.toFloat(),
            defE.x.toFloat(), defE.y.toFloat(), deflectPaint
        )
        drawGhost(canvas, direct.ghostPoint)
        val pocket = toView(direct.targetLine.end)
        canvas.drawCircle(
            pocket.x.toFloat(), pocket.y.toFloat(),
            (BALL_RADIUS_PX * 1.8 * scale).toFloat(), ringPaint
        )
        drawCutAngleLabel(canvas, direct, kickPaint.color)
    }

    /** Draw one shot's aim, target, and deflection lines plus the
     * ghost circle and (when [pocket] is true) the pocket ring at the
     * target line's end. Combo first legs and kiss legs end at a
     * ghost point, not a pocket, so they pass pocket = false. */
    private fun drawShot(
        canvas: Canvas,
        shot: com.poolguide.tool.geometry.ShotSolver.ShotSolution,
        pocket: Boolean = true
    ) {
        val aimS = toView(shot.aimLine.start)
        val aimE = toView(shot.aimLine.end)
        canvas.drawLine(
            aimS.x.toFloat(), aimS.y.toFloat(),
            aimE.x.toFloat(), aimE.y.toFloat(), aimPaint
        )
        val tgtS = toView(shot.targetLine.start)
        val tgtE = toView(shot.targetLine.end)
        canvas.drawLine(
            tgtS.x.toFloat(), tgtS.y.toFloat(),
            tgtE.x.toFloat(), tgtE.y.toFloat(), targetLinePaint
        )
        val defS = toView(shot.deflectionLine.start)
        val defE = toView(shot.deflectionLine.end)
        canvas.drawLine(
            defS.x.toFloat(), defS.y.toFloat(),
            defE.x.toFloat(), defE.y.toFloat(), deflectPaint
        )
        drawGhost(canvas, shot.ghostPoint)
        if (pocket) {
            canvas.drawCircle(
                tgtE.x.toFloat(), tgtE.y.toFloat(),
                (BALL_RADIUS_PX * 1.8 * scale).toFloat(), ringPaint
            )
        }
        drawCutAngleLabel(canvas, shot, aimPaint.color)
    }

    /** Cut-angle readout + thin-cut warning for one solved shot
     * (v1.0.12), drawn in view coordinates. The angle sits beside the
     * aim line (midpoint + 2.5 ball radii perpendicular, via
     * [CutAngleLabel.labelAnchor]); the warning sits above the cue
     * ball ([CutAngleLabel.warningAnchor]) and only appears for a
     * thin cut (> 80 deg — the solver returns null past 90, so an
     * impossible shot draws no label at all). [labelColor] is the
     * shot family's colour (green direct, cyan bank, magenta kick);
     * the warning is always amber. Combo and kiss legs each get their
     * own label, because each leg has its own cut angle. */
    private fun drawCutAngleLabel(
        canvas: Canvas,
        shot: ShotSolver.ShotSolution,
        labelColor: Int
    ) {
        val aimS = toView(shot.aimLine.start)
        val aimE = toView(shot.aimLine.end)
        val radiusViewPx = BALL_RADIUS_PX * scale
        val anchor = CutAngleLabel.labelAnchor(
            Segment(aimS, aimE), radiusViewPx
        )
        angleTextPaint.color = labelColor
        angleTextPaint.textSize = (16.0 * scale).toFloat()
        canvas.drawText(
            CutAngleLabel.format(shot.cutAngleDegrees),
            anchor.x.toFloat(), anchor.y.toFloat(), angleTextPaint
        )
        val warning = CutAngleLabel.warningText(shot.cutAngleDegrees) ?: return
        val warnAnchor = CutAngleLabel.warningAnchor(aimS, radiusViewPx)
        warningTextPaint.textSize = (16.0 * scale).toFloat()
        canvas.drawText(
            warning,
            warnAnchor.x.toFloat(), warnAnchor.y.toFloat(), warningTextPaint
        )
    }

    private fun drawGhost(canvas: Canvas, ghost: Vec2) {
        val v = toView(ghost)
        canvas.drawCircle(
            v.x.toFloat(), v.y.toFloat(),
            (BALL_RADIUS_PX * scale).toFloat(), aimPaint
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val st = state ?: return false
        val logical = toLogical(event.x, event.y)
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                val ball = st.ballAt(logical)
                if (ball == null) return false
                st.select(ball.id)
                draggingId = ball.id
                st.moveBall(ball.id, logical)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (draggingId < 0) return false
                st.moveBall(draggingId, logical)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                draggingId = -1
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
