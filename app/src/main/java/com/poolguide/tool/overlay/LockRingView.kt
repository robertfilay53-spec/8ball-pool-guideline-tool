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
 * table is still settling. In EDIT mode the shot is frozen, so the
 * ring switches to a neutral grey full circle ([isFrozen]) instead of
 * implying live lock progress (v1.0.6 fix of a v1.0.5 honest limit).
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

    /** Frozen (EDIT mode): the shot on screen is a frozen copy, not a
     * live detection, so the ring must not be read as live lock
     * progress. When true the ring draws as a neutral grey full
     * circle — a distinct frozen state, not a progress arc. Set from
     * [LineOverlayManager] alongside [progress]. */
    var isFrozen: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    private val frozenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(255, 158, 158, 158) // neutral grey: frozen
        style = Paint.Style.STROKE
        strokeWidth = 5f
        strokeCap = Paint.Cap.ROUND
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
        if (isFrozen) {
            // Frozen: a full neutral circle. No arc, so nothing here
            // can be misread as "the table is 60% locked" while the
            // user is editing a frozen shot.
            canvas.drawArc(rect, -90f, 360f, false, frozenPaint)
            return
        }
        val p = progress
        if (p <= 0f) return
        val paint = if (p >= 1f) lockedPaint else progressPaint
        canvas.drawArc(rect, -90f, 360f * p, false, paint)
    }
}
