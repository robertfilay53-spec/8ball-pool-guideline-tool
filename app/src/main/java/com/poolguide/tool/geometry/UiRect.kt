package com.poolguide.tool.geometry

/**
 * A screen-space rectangle of the tool's own UI (the floating
 * control bubble, its menu), in any coordinate system the caller
 * uses — screen pixels or working pixels — as long as the detector
 * is given rects in the same coordinates it works in. The detector
 * must ignore whatever the overlay itself draws, or it will detect
 * its own white buttons as balls and their bright edges as
 * guideline segments (the real-phone failure: the control bar's
 * buttons were detected as white balls and one was picked as the
 * cue ball).
 *
 * A ball is excluded when its CENTRE is inside a rect. A guideline
 * segment is excluded only when BOTH its endpoints are inside ONE
 * rect — a real guideline merely crossing a rect (the bubble is
 * small and a line can pass behind it) is kept, because the line
 * is still the game's line.
 *
 * Pure Kotlin (no Android dependency) so it is JVM-testable.
 */
data class UiRect(
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double
) {
    fun contains(point: Vec2): Boolean =
        point.x >= left && point.x <= right &&
            point.y >= top && point.y <= bottom

    fun containsSegment(start: Vec2, end: Vec2): Boolean =
        contains(start) && contains(end)
}
