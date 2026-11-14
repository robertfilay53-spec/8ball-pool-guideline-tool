package com.poolguide.tool.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.poolguide.tool.cv.TableRect
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
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFF5252.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 4f
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
        // Solved shot lines.
        val shot = st.solve()
        if (shot != null) {
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
            val ghost = toView(shot.ghostPoint)
            canvas.drawCircle(
                ghost.x.toFloat(), ghost.y.toFloat(),
                (BALL_RADIUS_PX * scale).toFloat(), aimPaint
            )
            canvas.drawCircle(
                tgtE.x.toFloat(), tgtE.y.toFloat(),
                (BALL_RADIUS_PX * 1.8 * scale).toFloat(), ringPaint
            )
        }
        // Balls.
        val ballR = (BALL_RADIUS_PX * scale).toFloat()
        for (ball in listOf(st.cue, st.target)) {
            val v = toView(ball.center)
            canvas.drawCircle(
                v.x.toFloat(), v.y.toFloat(), ballR,
                if (ball.isCue) cuePaint else targetPaint
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
