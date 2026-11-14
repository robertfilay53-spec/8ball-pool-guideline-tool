package com.poolguide.tool.cv

import com.poolguide.tool.geometry.Vec2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Table-lock gate tests (v1.0.3, Bloro lead). The lock needs 75
 * consecutive frames (5 s at the 15 FPS cap) with the table rect
 * stable within 2 px on all four corners.
 */
class TableLockTest {

    private fun rect(x: Double = 45.0, y: Double = 50.0, w: Double = 630.0, h: Double = 304.0) =
        TableRect(
            Vec2(x, y),
            Vec2(x + w, y),
            Vec2(x + w, y + h),
            Vec2(x, y + h)
        )

    @Test
    fun `locks at frame 75, not at frame 74`() {
        val lock = TableLock()
        val table = rect()
        repeat(74) { assertFalse(lock.update(table)) }
        assertTrue(lock.update(table))
        assertTrue(lock.locked)
    }

    @Test
    fun `stays locked while the table is stable`() {
        val lock = TableLock()
        val table = rect()
        repeat(75) { lock.update(table) }
        assertTrue(lock.locked)
        repeat(25) { assertTrue(lock.update(table)) }
    }

    @Test
    fun `null table clears the lock`() {
        val lock = TableLock()
        val table = rect()
        repeat(75) { lock.update(table) }
        assertTrue(lock.locked)
        assertFalse(lock.update(null))
        assertFalse(lock.locked)
        // Re-locking needs another 75 stable frames.
        repeat(74) { assertFalse(lock.update(table)) }
        assertTrue(lock.update(table))
    }

    @Test
    fun `table moving beyond tolerance resets the count`() {
        val lock = TableLock()
        val table = rect()
        repeat(75) { lock.update(table) }
        assertTrue(lock.locked)
        // 10 px move: the count restarts from 1.
        assertFalse(lock.update(rect(x = 55.0)))
        assertFalse(lock.locked)
        repeat(73) { assertFalse(lock.update(rect(x = 55.0))) }
        assertTrue(lock.update(rect(x = 55.0)))
    }

    @Test
    fun `movement within tolerance keeps counting`() {
        val lock = TableLock()
        val table = rect()
        repeat(40) { lock.update(table) }
        // 1 px jitter (inside the 2 px tolerance) must not reset.
        repeat(35) { lock.update(rect(x = 46.0)) }
        assertTrue(lock.locked)
    }

    @Test
    fun `reset clears the lock`() {
        val lock = TableLock()
        repeat(75) { lock.update(rect()) }
        assertTrue(lock.locked)
        lock.reset()
        assertFalse(lock.locked)
        assertFalse(lock.update(rect()))
    }

    // ---- v1.0.5: a guessed (fallback) rect can never lock the table. ----

    @Test
    fun `fallback frames never lock on their own`() {
        val lock = TableLock()
        val guess = RatioTableGeometry.fallbackTable(720, 405)
        repeat(200) { assertFalse(lock.update(guess, fromFallback = true)) }
        assertFalse(lock.locked)
        assertFalse(lock.canContinueFallback)
        assertEquals(0f, lock.lockProgress, 0.001f)
    }

    @Test
    fun `fallback continues a real lock inside the window then drops it`() {
        val lock = TableLock()
        val table = rect()
        val guess = RatioTableGeometry.fallbackTable(720, 405)
        repeat(75) { lock.update(table) }
        assertTrue(lock.locked)
        assertTrue(lock.canContinueFallback)
        // 150 fallback frames (10 s at the 15 FPS cap): still locked.
        repeat(150) { assertTrue(lock.update(guess, fromFallback = true)) }
        assertTrue(lock.locked)
        assertFalse(lock.canContinueFallback)
        // The 151st fallback frame drops the lock.
        assertFalse(lock.update(guess, fromFallback = true))
        assertFalse(lock.locked)
    }

    @Test
    fun `real detection after fallback expiry re-locks from scratch`() {
        val lock = TableLock()
        val table = rect()
        val guess = RatioTableGeometry.fallbackTable(720, 405)
        repeat(75) { lock.update(table) }
        repeat(151) { lock.update(guess, fromFallback = true) }
        assertFalse(lock.locked)
        // A fresh real table needs another 75 stable frames.
        repeat(74) { assertFalse(lock.update(table)) }
        assertTrue(lock.update(table))
        assertTrue(lock.locked)
    }

    @Test
    fun `lockProgress tracks real frames only`() {
        val lock = TableLock()
        val table = rect()
        val guess = RatioTableGeometry.fallbackTable(720, 405)
        repeat(37) { lock.update(table) }
        assertEquals(37f / 75f, lock.lockProgress, 0.001f)
        // Fallback frames do not advance the progress.
        repeat(50) { lock.update(guess, fromFallback = true) }
        assertEquals(37f / 75f, lock.lockProgress, 0.001f)
        repeat(38) { lock.update(table) }
        assertTrue(lock.locked)
        assertEquals(1f, lock.lockProgress, 0.001f)
    }

    @Test
    fun `null table during fallback continuation clears everything`() {
        val lock = TableLock()
        val table = rect()
        val guess = RatioTableGeometry.fallbackTable(720, 405)
        repeat(75) { lock.update(table) }
        repeat(10) { lock.update(guess, fromFallback = true) }
        assertTrue(lock.locked)
        assertFalse(lock.update(null))
        assertFalse(lock.locked)
        assertFalse(lock.canContinueFallback)
        assertEquals(0f, lock.lockProgress, 0.001f)
    }
}
