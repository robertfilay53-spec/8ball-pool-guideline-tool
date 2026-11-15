package com.poolguide.tool.cv

import com.poolguide.tool.geometry.Vec2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The table ROI bounds (v1.0.36, power-team CV item 3). Pure bounds
 * math, so it is unit-testable here: [tableRoi] is a top-level
 * function because constructing a BallDetector allocates native Mats
 * (UnsatisfiedLinkError in JVM unit tests). The ROI is the table
 * bbox + the same 5 px pad BallDetector.insideTable uses, clamped to
 * the frame; it is null when there is no table, the frame is empty,
 * or the box is degenerate (under 10 px on a side). */
class BallDetectorRoiTest {


    private fun table(x0: Double, y0: Double, x1: Double, y1: Double) = TableRect(
        Vec2(x0, y0), Vec2(x1, y0), Vec2(x1, y1), Vec2(x0, y1)
    )

    private fun assertRect(x: Int, y: Int, w: Int, h: Int, rect: org.opencv.core.Rect?) {
        val r = rect ?: throw AssertionError("expected a Rect, got null")
        assertEquals("x", x, r.x)
        assertEquals("y", y, r.y)
        assertEquals("width", w, r.width)
        assertEquals("height", h, r.height)
    }

    @Test
    fun roi_interiorTable_isBboxPlusFivePad() {
        // Corners (100,50)-(620,350) on a 720x405 frame: the ROI is
        // the bbox grown 5 px on every side.
        assertRect(95, 45, 530, 310, tableRoi(table(100.0, 50.0, 620.0, 350.0), 720, 405))
    }

    @Test
    fun roi_fractionalCorners_floorMinAndCeilMax() {
        // floor() on the min side and ceil() on the max side, so the
        // ROI always covers the full 5 px pad (truncation would shave
        // up to 1 px off the far side).
        assertRect(95, 45, 531, 311, tableRoi(table(100.7, 50.2, 620.3, 350.8), 720, 405))
    }

    @Test
    fun roi_fullFrameTable_isTheWholeFrame() {
        assertRect(0, 0, 720, 405, tableRoi(table(0.0, 0.0, 720.0, 405.0), 720, 405))
    }

    @Test
    fun roi_tablePastFrameEdge_isClampedToFrame() {
        // A table that runs off the left/top of the frame: the ROI
        // starts at the frame edge and covers the rest.
        assertRect(0, 0, 705, 405, tableRoi(table(-50.0, -20.0, 700.0, 400.0), 720, 405))
    }

    @Test
    fun roi_nullTable_isNull() {
        assertNull(tableRoi(null, 720, 405))
    }

    @Test
    fun roi_emptyFrame_isNull() {
        assertNull(tableRoi(table(100.0, 50.0, 620.0, 350.0), 0, 405))
        assertNull(tableRoi(table(100.0, 50.0, 620.0, 350.0), 720, 0))
    }

    @Test
    fun roi_degenerateTinyTable_isNull() {
        // A 2x2 px table at the frame corner clamps to a 7x7 box,
        // under the 10 px minimum: the caller falls back to the
        // full frame instead of cropping to a sliver.
        assertNull(tableRoi(table(0.0, 0.0, 2.0, 2.0), 720, 405))
    }
}
