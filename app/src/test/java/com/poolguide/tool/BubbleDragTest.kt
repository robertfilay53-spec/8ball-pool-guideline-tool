package com.poolguide.tool.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Control-bubble drag state (Bloro lead, v1.0.5): drag after 10 dp of
 * movement; tap under 400 ms with no drag; edge snap on release.
 */
class BubbleDragTest {

    /** 10 dp at mdpi density: 10 px. */
    private val thresholdPx = 10f

    @Test
    fun `small move stays a tap`() {
        val drag = BubbleDrag(thresholdPx)
        drag.onDown(100f, 100f, 1000L)
        assertFalse(drag.onMove(105f, 103f))
        assertFalse(drag.isDragging)
        assertEquals(BubbleDrag.Outcome.Tap, drag.onUp(1200L))
    }

    @Test
    fun `move past the threshold becomes a drag with the total delta`() {
        val drag = BubbleDrag(thresholdPx)
        drag.onDown(100f, 100f, 1000L)
        assertFalse(drag.onMove(105f, 100f))
        assertTrue(drag.onMove(115f, 120f))
        assertTrue(drag.isDragging)
        // Later moves stay a drag even back under the threshold.
        assertTrue(drag.onMove(102f, 101f))
        assertEquals(BubbleDrag.Outcome.Drag(2f, 1f), drag.onUp(1100L))
    }

    @Test
    fun `fast drag under 400 ms is still a drag, not a tap`() {
        val drag = BubbleDrag(thresholdPx)
        drag.onDown(100f, 100f, 1000L)
        assertTrue(drag.onMove(200f, 100f))
        assertEquals(BubbleDrag.Outcome.Drag(100f, 0f), drag.onUp(1100L))
    }

    @Test
    fun `hold without moving past the deadline is a hold, not a tap`() {
        val drag = BubbleDrag(thresholdPx)
        drag.onDown(100f, 100f, 1000L)
        assertEquals(BubbleDrag.Outcome.Hold, drag.onUp(1500L))
    }

    @Test
    fun `tap exactly at the deadline is a hold`() {
        val drag = BubbleDrag(thresholdPx)
        drag.onDown(100f, 100f, 1000L)
        assertEquals(BubbleDrag.Outcome.Hold, drag.onUp(1400L))
        // One ms earlier is still a tap.
        val quick = BubbleDrag(thresholdPx)
        quick.onDown(100f, 100f, 1000L)
        assertEquals(BubbleDrag.Outcome.Tap, quick.onUp(1399L))
    }

    @Test
    fun `onDown resets the state for a new gesture`() {
        val drag = BubbleDrag(thresholdPx)
        drag.onDown(100f, 100f, 1000L)
        assertTrue(drag.onMove(200f, 100f))
        drag.onDown(50f, 50f, 2000L)
        assertFalse(drag.isDragging)
        assertFalse(drag.onMove(55f, 50f))
        assertEquals(BubbleDrag.Outcome.Tap, drag.onUp(2100L))
    }

    @Test
    fun `snapX picks the nearest edge`() {
        // Bubble 200 wide on a 1080 screen, 16 px margin.
        assertEquals(16, BubbleDrag.snapX(100, 200, 1080, 16))
        assertEquals(864, BubbleDrag.snapX(700, 200, 1080, 16))
        // Centre exactly mid-screen snaps right (not strictly left).
        assertEquals(864, BubbleDrag.snapX(440, 200, 1080, 16))
        // A bubble wider than the screen still gets the margin.
        assertEquals(16, BubbleDrag.snapX(0, 2000, 1080, 16))
    }
}
