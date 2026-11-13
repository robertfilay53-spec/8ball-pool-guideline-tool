package com.poolguide.tool.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import android.view.View

/**
 * Draws exactly ONE line (or one ring) inside a small window whose bounds
 * are just that line's bounding box. This is the ChatGPT design change:
 * obscuring is computed by WINDOW BOUNDS, not by pixel alpha, so a
 * full-screen MATCH_PARENT overlay would mark every touch as obscured.
 * One small window per line means the game only sees the obscured flag when
 * the user taps exactly on a line.
 */
class LineView(context: Context) : View(context) {

    enum class Mode { LINE, RING }

    private var mode = Mode.LINE
    private var start = PointF(0f, 0f)
    private var end = PointF(0f, 0f)
    private var ringRadius = 20f

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    /** Set the line to draw, in this view's LOCAL coordinates. */
    fun setLine(start: PointF, end: PointF, color: Int, widthPx: Float) {
        mode = Mode.LINE
        this.start = start
        this.end = end
        paint.color = color
        paint.strokeWidth = widthPx
        invalidate()
    }

    /** Set a ring (pocket highlight) centred in this view. */
    fun setRing(center: PointF, radius: Float, color: Int, widthPx: Float) {
        mode = Mode.RING
        this.start = center
        this.ringRadius = radius
        paint.color = color
        paint.strokeWidth = widthPx
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        when (mode) {
            Mode.LINE -> {
                canvas.drawLine(start.x, start.y, end.x, end.y, paint)
                // Dot at the far end (e.g. the ghost-ball point).
                val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = paint.color
                    style = Paint.Style.FILL
                }
                canvas.drawCircle(end.x, end.y, paint.strokeWidth * 0.9f, dotPaint)
            }
            Mode.RING -> {
                canvas.drawCircle(start.x, start.y, ringRadius, paint)
            }
        }
    }
}
