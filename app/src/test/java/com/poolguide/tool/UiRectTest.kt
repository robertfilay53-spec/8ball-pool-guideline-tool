package com.poolguide.tool

import com.poolguide.tool.geometry.UiRect
import com.poolguide.tool.geometry.Vec2
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UI exclusion rectangles (v1.0.69, real-phone fix): the detector
 * must ignore the tool's own overlay. A ball is excluded when its
 * centre is inside a rect; a guideline segment is excluded only
 * when BOTH endpoints are inside ONE rect (a real guideline merely
 * crossing a rect is kept). Pure Kotlin, JVM-testable.
 */
class UiRectTest {

    @Test
    fun contains_centreInsideAndOutside() {
        val rect = UiRect(42.0, 103.0, 325.0, 135.0)
        assertTrue(rect.contains(Vec2(100.0, 120.0)))
        assertFalse(rect.contains(Vec2(400.0, 120.0)))
        assertFalse(rect.contains(Vec2(100.0, 200.0)))
    }

    @Test
    fun contains_boundaryIsInside() {
        val rect = UiRect(42.0, 103.0, 325.0, 135.0)
        assertTrue(rect.contains(Vec2(42.0, 103.0)))
        assertTrue(rect.contains(Vec2(325.0, 135.0)))
    }

    @Test
    fun containsSegment_bothEndpointsInsideDrops() {
        // The tool's own control bar: a button-edge line fully
        // inside the bar rect is the overlay itself.
        val rect = UiRect(42.0, 103.0, 325.0, 135.0)
        assertTrue(rect.containsSegment(Vec2(134.0, 122.0), Vec2(253.0, 122.0)))
    }

    @Test
    fun containsSegment_crossingLineIsKept() {
        // A real guideline merely crossing the bubble rect is still
        // the game's line — only a segment fully inside drops.
        val rect = UiRect(42.0, 103.0, 325.0, 135.0)
        assertFalse(rect.containsSegment(Vec2(200.0, 50.0), Vec2(200.0, 200.0)))
        assertFalse(rect.containsSegment(Vec2(200.0, 120.0), Vec2(400.0, 120.0)))
    }
}
