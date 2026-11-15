package com.poolguide.tool.overlay

import com.poolguide.tool.geometry.Vec2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Shot-in-progress detector (v1.0.37, REA hardening R1). Pure Kotlin,
 * JVM-tested. The thresholds are judgement calls (detection jitter is
 * sub-degree / sub-pixel, a real drag swings several degrees per frame,
 * a struck cue ball moves several pixels per frame) — the tests pin
 * the behaviour at the thresholds, not the thresholds themselves.
 */
class AimActivityTrackerTest {

    private val cue = Vec2(100.0, 200.0)

    @Test
    fun `steady aim never marks aiming`() {
        val tracker = AimActivityTracker()
        repeat(10) {
            assertFalse(tracker.observe(10.0, cue))
        }
        assertFalse(tracker.isAiming)
    }

    @Test
    fun `guideline swing marks aiming, then settles after five quiet frames`() {
        val tracker = AimActivityTracker()
        assertFalse(tracker.observe(10.0, cue))
        assertTrue("a 30-degree swing is a drag, not jitter", tracker.observe(40.0, cue))
        assertTrue(tracker.isAiming)
        // Four more quiet frames: still settling.
        repeat(4) { assertTrue(tracker.observe(40.0, cue)) }
        // The fifth quiet frame settles the aim.
        assertFalse(tracker.observe(40.0, cue))
        assertFalse(tracker.isAiming)
    }

    @Test
    fun `cue ball travelling marks aiming, then settles`() {
        val tracker = AimActivityTracker()
        assertFalse(tracker.observe(10.0, cue))
        assertTrue(
            "a 5 px cue jump is a shot in flight",
            tracker.observe(10.0, Vec2(105.0, 200.0))
        )
        repeat(4) { assertTrue(tracker.observe(10.0, Vec2(105.0, 200.0))) }
        assertFalse(tracker.observe(10.0, Vec2(105.0, 200.0)))
    }

    @Test
    fun `sub-threshold movement is jitter, not aiming`() {
        val tracker = AimActivityTracker()
        assertFalse(tracker.observe(10.0, cue))
        // 1 degree and 1 px are inside both thresholds.
        repeat(10) {
            assertFalse(tracker.observe(11.0, Vec2(101.0, 200.0)))
        }
    }

    @Test
    fun `angle wrap is a small delta, not a swing`() {
        val tracker = AimActivityTracker()
        assertFalse(tracker.observe(179.0, cue))
        // 179 to -179 is a 2-degree swing across the wrap, at the
        // threshold: not over it, so still calm.
        assertFalse(tracker.observe(-179.0, cue))
    }

    @Test
    fun `missing signals never mark aiming and never crash`() {
        val tracker = AimActivityTracker()
        repeat(10) {
            assertFalse(tracker.observe(null, null))
        }
        // One signal alone, steady, is calm too.
        assertFalse(tracker.observe(10.0, null))
        assertFalse(tracker.observe(null, cue))
    }

    @Test
    fun `reset clears aiming immediately`() {
        val tracker = AimActivityTracker()
        assertFalse(tracker.observe(10.0, cue))
        assertTrue(tracker.observe(40.0, cue))
        tracker.reset()
        assertFalse(tracker.isAiming)
        // The next frame re-baselines instead of comparing against
        // the pre-reset aim.
        assertFalse(tracker.observe(40.0, cue))
    }

    @Test
    fun `direction angle helper points where the segment points`() {
        assertEquals(0.0, AimActivityTracker.directionAngleDeg(Vec2(1.0, 0.0)), 1e-9)
        assertEquals(90.0, AimActivityTracker.directionAngleDeg(Vec2(0.0, 1.0)), 1e-9)
        assertEquals(-90.0, AimActivityTracker.directionAngleDeg(Vec2(0.0, -1.0)), 1e-9)
        assertEquals(180.0, AimActivityTracker.directionAngleDeg(Vec2(-1.0, 0.0)), 1e-9)
    }
}
