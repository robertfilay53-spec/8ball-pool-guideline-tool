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

    enum class Mode { LINE, RING, DOT }

    private var mode = Mode.LINE
    private var start = PointF(0f, 0f)
    private var end = PointF(0f, 0f)
    private var ringRadius = 20f

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    /** Filled-dot paint (EDIT-mode anchor handles, v1.0.4). */
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    /** Halo underlay (v1.0.3, Bloro lead): a wide, faint stroke of the
     * same colour under the bright core, so the line stays readable on
     * bright felt. Alpha 40/255, width 2.2x the core. */
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        alpha = HALO_ALPHA
    }

    /** Set the line to draw, in this view's LOCAL coordinates. */
    fun setLine(start: PointF, end: PointF, color: Int, widthPx: Float) {
        mode = Mode.LINE
        this.start = start
        this.end = end
        paint.color = color
        paint.strokeWidth = widthPx
        haloPaint.color = color
        haloPaint.strokeWidth = widthPx * HALO_WIDTH_FACTOR
        invalidate()
    }

    /** Set a ring (pocket highlight) centred in this view. */
    fun setRing(center: PointF, radius: Float, color: Int, widthPx: Float) {
        mode = Mode.RING
        this.start = center
        this.ringRadius = radius
        paint.color = color
        paint.strokeWidth = widthPx
        haloPaint.color = color
        haloPaint.strokeWidth = widthPx * HALO_WIDTH_FACTOR
        invalidate()
    }

    /** Set a filled dot (EDIT-mode anchor handle, v1.0.4) centred in
     * this view. The handle window is larger than the dot so the touch
     * target is comfortable; the dot itself marks the anchor point. */
    fun setDot(center: PointF, radius: Float, color: Int) {
        mode = Mode.DOT
        this.start = center
        this.ringRadius = radius
        dotPaint.color = color
        paint.color = color
        paint.strokeWidth = 3f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        when (mode) {
            Mode.LINE -> {
                // Double-pass halo rendering: wide faint underlay, then
                // the thin bright core. The window padding (10 px)
                // fits the halo: the widest core is 5 px, so the halo
                // extends 5.5 px each side.
                canvas.drawLine(start.x, start.y, end.x, end.y, haloPaint)
                canvas.drawLine(start.x, start.y, end.x, end.y, paint)
                // Dot at the far end (e.g. the ghost-ball point).
                val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = paint.color
                    style = Paint.Style.FILL
                }
                canvas.drawCircle(end.x, end.y, paint.strokeWidth * 0.9f, dotPaint)
            }
            Mode.RING -> {
                canvas.drawCircle(start.x, start.y, ringRadius, haloPaint)
                canvas.drawCircle(start.x, start.y, ringRadius, paint)
            }
            Mode.DOT -> {
                canvas.drawCircle(start.x, start.y, ringRadius, dotPaint)
                canvas.drawCircle(start.x, start.y, ringRadius, paint)
            }
        }
    }

    companion object {
        /** Halo stroke width as a multiple of the core stroke width. */
        const val HALO_WIDTH_FACTOR = 2.2f
        /** Halo alpha (0-255): Bloro's wide pass uses alpha 40. */
        const val HALO_ALPHA = 40
    }
}
