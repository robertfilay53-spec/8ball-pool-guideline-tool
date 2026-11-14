package com.poolguide.tool.cv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ratio table-geometry fallback (v1.0.4, Bloro lead). */
class RatioTableGeometryTest {

    @Test
    fun `720x405 frame gives Bloro band fractions, 2 to 1, centred`() {
        val table = RatioTableGeometry.fallbackTable(720, 405)
        // Band: 0.237037*405 = 96.0 to 0.912963*405 = 369.75.
        assertEquals(96.0, table.topLeft.y, 0.01)
        assertEquals(369.75, table.bottomLeft.y, 0.01)
        // Length = 2 * band height = 547.5, centred in 720.
        assertEquals(547.5, table.width, 0.01)
        assertEquals(273.75, table.height, 0.01)
        assertEquals(86.25, table.topLeft.x, 0.01)
        assertEquals(633.75, table.topRight.x, 0.01)
    }

    @Test
    fun `ball radius is 3 percent of band height`() {
        // Band height at 405 tall = 273.75; 3% = 8.2125.
        assertEquals(8.2125, RatioTableGeometry.ballRadiusPx(405), 0.001)
        assertEquals(8.2125, RatioTableGeometry.ballRadiusPx(405), 0.001)
    }

    @Test
    fun `portrait frame clamps the length inside the frame`() {
        val table = RatioTableGeometry.fallbackTable(720, 1280)
        assertTrue(table.topLeft.x >= 0.0)
        assertTrue(table.topRight.x <= 720.0)
        // Length capped at 96% of the width.
        assertEquals(691.2, table.width, 0.01)
        assertEquals(14.4, table.topLeft.x, 0.01)
    }

    @Test
    fun `fallback pockets sit inside the rect`() {
        val table = RatioTableGeometry.fallbackTable(720, 405)
        for (pocket in table.pockets()) {
            assertTrue(pocket.x >= table.topLeft.x)
            assertTrue(pocket.x <= table.topRight.x)
            assertTrue(pocket.y >= table.topLeft.y)
            assertTrue(pocket.y <= table.bottomLeft.y)
        }
        assertEquals(6, table.pockets().size)
    }

    @Test
    fun `zero size is rejected`() {
        try {
            RatioTableGeometry.fallbackTable(0, 405)
            throw AssertionError("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
        try {
            RatioTableGeometry.ballRadiusPx(0)
            throw AssertionError("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }
}
