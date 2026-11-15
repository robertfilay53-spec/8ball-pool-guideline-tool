package com.poolguide.tool.cv

import com.poolguide.tool.geometry.Segment
import com.poolguide.tool.geometry.Vec2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.opencv.core.Point

/** The verifier dedup + spatial grid (v1.0.39, deep-dive item 6).
 * Pure geometry / pure logic, so it is unit-testable here:
 * [isParallelDuplicate] and [EdgePointGrid] are top-level because
 * constructing a GuidelineDetector allocates native Mats
 * (UnsatisfiedLinkError in JVM unit tests). */
class GuidelineVerifierTest {

    private fun seg(x0: Double, y0: Double, x1: Double, y1: Double) =
        Segment(Vec2(x0, y0), Vec2(x1, y1))

    // ---- isParallelDuplicate ----

    @Test
    fun duplicate_twoPxParallelEdges_isDuplicate() {
        // The two edges of a 2-3 px white guideline, 2 px apart.
        assertTrue(isParallelDuplicate(seg(100.0, 100.0, 300.0, 100.0), seg(100.0, 102.0, 300.0, 102.0)))
    }

    @Test
    fun duplicate_reversedDirection_isDuplicate() {
        // Hough can return the same edge either way round.
        assertTrue(isParallelDuplicate(seg(100.0, 100.0, 300.0, 100.0), seg(300.0, 102.0, 100.0, 102.0)))
    }

    @Test
    fun duplicate_shortCandidateInsideKept_isDuplicate() {
        assertTrue(isParallelDuplicate(seg(100.0, 100.0, 300.0, 100.0), seg(150.0, 102.0, 250.0, 102.0)))
    }

    @Test
    fun notDuplicate_fivePxApart() {
        assertFalse(isParallelDuplicate(seg(100.0, 100.0, 300.0, 100.0), seg(100.0, 105.0, 300.0, 105.0)))
    }

    @Test
    fun notDuplicate_perpendicular() {
        assertFalse(isParallelDuplicate(seg(100.0, 100.0, 300.0, 100.0), seg(200.0, 50.0, 200.0, 150.0)))
    }

    @Test
    fun notDuplicate_tenDegreeAngle() {
        // 10 deg over a 200 px run: the far endpoint is ~35 px off
        // the kept line, and the angle alone is over the 5 deg cap.
        assertFalse(isParallelDuplicate(seg(100.0, 100.0, 300.0, 100.0), seg(100.0, 100.0, 300.0, 135.3)))
    }

    @Test
    fun notDuplicate_candidateExtendsFarPastKept() {
        // A long line collinear with a short kept segment is NOT a
        // duplicate: its far endpoint is ~250 px from the kept
        // segment. This pins the no-gap-bridging rule (deep dive §5).
        assertFalse(isParallelDuplicate(seg(100.0, 100.0, 150.0, 100.0), seg(100.0, 102.0, 400.0, 102.0)))
    }

    // ---- EdgePointGrid ----

    @Test
    fun grid_nearbyReturnsOnlyNearPoints_inOriginalOrder() {
        val points = listOf(
            Point(0.0, 100.0), Point(50.0, 100.0), Point(500.0, 500.0),
            Point(100.0, 100.0), Point(100.0, 300.0), Point(200.0, 100.0)
        )
        val grid = EdgePointGrid(points)
        val near = grid.nearby(seg(0.0, 100.0, 200.0, 100.0))
        assertEquals(
            listOf(Point(0.0, 100.0), Point(50.0, 100.0), Point(100.0, 100.0), Point(200.0, 100.0)),
            near
        )
    }

    @Test
    fun grid_nearbyIncludesPointsUpToCellGranularity() {
        // A point 2 px off the segment is outside the 1.5 px inlier
        // band but inside the same 16 px cell as the segment's box,
        // so the grid returns it (superset) and the exact distance
        // filter in inlierPoints still rejects it downstream.
        val points = listOf(Point(100.0, 102.0), Point(100.0, 130.0))
        val grid = EdgePointGrid(points)
        assertEquals(listOf(Point(100.0, 102.0)), grid.nearby(seg(0.0, 100.0, 200.0, 100.0)))
    }

    @Test
    fun grid_nearbyDiagonalSegment() {
        // The grid gathers by bounding box, not by exact distance:
        // (10,110) sits inside the diagonal's square box, so it IS
        // returned (superset) and the exact distance filter in
        // inlierPoints rejects it downstream. Only a point outside
        // the box, (200,10), is excluded here.
        val points = listOf(
            Point(10.0, 10.0), Point(110.0, 110.0), Point(10.0, 110.0), Point(200.0, 10.0)
        )
        val grid = EdgePointGrid(points)
        assertEquals(
            listOf(Point(10.0, 10.0), Point(110.0, 110.0), Point(10.0, 110.0)),
            grid.nearby(seg(0.0, 0.0, 120.0, 120.0))
        )
    }

    @Test
    fun grid_nearbyNegativeCoordinates_useFloorNotTruncation() {
        // floor(-5 / 16) is -1, truncation would give 0: a segment
        // at negative coordinates must still find its own points.
        val points = listOf(Point(-5.0, -5.0), Point(100.0, 100.0))
        val grid = EdgePointGrid(points)
        assertEquals(listOf(Point(-5.0, -5.0)), grid.nearby(seg(-10.0, -10.0, 0.0, 0.0)))
    }

    @Test
    fun grid_empty_returnsEmpty() {
        assertTrue(EdgePointGrid(emptyList()).nearby(seg(0.0, 0.0, 100.0, 100.0)).isEmpty())
    }
}
