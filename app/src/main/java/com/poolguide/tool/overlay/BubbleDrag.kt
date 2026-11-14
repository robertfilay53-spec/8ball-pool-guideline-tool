package com.poolguide.tool.overlay

import kotlin.math.hypot

/**
 * Control-bubble drag state (Bloro lead, v1.0.5). Pure Kotlin, no
 * Android dependency — fully unit-testable on the JVM.
 *
 * Bloro's float icon: drag after 10 dp of movement; tap under 400 ms
 * with no drag opens the status dialog; on release the icon edge-snaps
 * (when enabled) and its position is saved. Ours adapts the pattern
 * to what our bubble owns: a tap toggles the guides (the quick action
 * — the Hide/Show button does the same thing), a drag moves the bubble
 * and edge-snaps on release, and the position is persisted. There is
 * no long-press action: a hold with no movement does nothing.
 */
class BubbleDrag(
    /** Movement in px that turns a touch into a drag (10 dp, passed in
     * already converted to px by the caller). */
    private val dragThresholdPx: Float
) {

    /** Tap deadline: a touch released within this long without dragging
     * is a tap (Bloro: 400 ms). */
    private val tapMaxDurationMs = 400L

    sealed interface Outcome {
        /** A tap: no drag, released within the tap deadline. */
        data object Tap : Outcome
        /** A drag: total movement in px from the down point. */
        data class Drag(val dx: Float, val dy: Float) : Outcome
        /** Held without moving past the tap deadline: no action. */
        data object Hold : Outcome
    }

    private var downX = 0f
    private var downY = 0f
    private var downTimeMs = 0L
    private var dragging = false
    private var lastX = 0f
    private var lastY = 0f

    /** True while a drag is in progress (after the threshold). */
    val isDragging: Boolean get() = dragging

    fun onDown(x: Float, y: Float, timeMs: Long) {
        downX = x
        downY = y
        lastX = x
        lastY = y
        downTimeMs = timeMs
        dragging = false
    }

    /** Feed a move event. Returns true once the touch has become a drag
     * (movement past the threshold); stays true for later moves. */
    fun onMove(x: Float, y: Float): Boolean {
        if (!dragging && hypot(x - downX, y - downY) >= dragThresholdPx) {
            dragging = true
        }
        lastX = x
        lastY = y
        return dragging
    }

    /** Release: tap when nothing dragged and the touch was short, a
     * drag with the total delta when the threshold was passed, a hold
     * when the touch sat still past the tap deadline. */
    fun onUp(timeMs: Long): Outcome {
        if (dragging) return Outcome.Drag(lastX - downX, lastY - downY)
        return if (timeMs - downTimeMs < tapMaxDurationMs) Outcome.Tap else Outcome.Hold
    }

    companion object {
        /** Snap a bubble's left edge to the nearest screen edge on
         * release: the left margin when its centre is left of
         * mid-screen, the right edge otherwise. */
        fun snapX(x: Int, bubbleWidth: Int, screenWidth: Int, marginPx: Int): Int {
            val centre = x + bubbleWidth / 2
            return if (centre < screenWidth / 2) marginPx
            else (screenWidth - bubbleWidth - marginPx).coerceAtLeast(marginPx)
        }
    }
}
