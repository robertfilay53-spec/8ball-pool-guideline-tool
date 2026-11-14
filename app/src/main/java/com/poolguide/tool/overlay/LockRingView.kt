package com.poolguide.tool.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * Lock-progress ring for the control bubble (Bloro lead, v1.0.5).
 *
 * Bloro's float icon carries a ring for remaining free minutes. Ours
 * has no credits and no paywall, so the ring shows the table-lock
 * progress instead: it fills as the detected table stabilises toward
 * the 5 s lock, and turns solid green once locked. Empty (just the
 * faint background circle) while no table is detected at all. The
 * colour matches the aim line green when locked; amber while the
 * table is still settling.
 */
class LockRingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /** 0.0-1.0 lock progress, from DetectionResult.tableLockProgress. */
    var progress: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(120, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }

    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(255, 255, 193, 7) // amber while stabilising
        style = Paint.Style.STROKE
        strokeWidth = 5f
        strokeCap = Paint.Cap.ROUND
    }

    private val lockedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF00E676.toInt() // green, same as the aim line
        style = Paint.Style.STROKE
        strokeWidth = 5f
        strokeCap = Paint.Cap.ROUND
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val radius = minOf(width, height) / 2f - 4f
        if (radius <= 0f) return
        val rect = RectF(
            width / 2f - radius, height / 2f - radius,
            width / 2f + radius, height / 2f + radius
        )
        canvas.drawCircle(width / 2f, height / 2f, radius, backgroundPaint)
        val p = progress
        if (p <= 0f) return
        val paint = if (p >= 1f) lockedPaint else progressPaint
        canvas.drawArc(rect, -90f, 360f * p, false, paint)
    }
}
