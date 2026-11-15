package com.poolguide.tool.overlay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * EDIT-mode inactivity timer (v1.0.41, REA hardening R4). Pure Kotlin,
 * JVM-tested. The 20 s timeout is a judgement call — long enough to
 * study a frozen shot without being rushed, short enough that a
 * forgotten session cannot sit through a whole turn — so the tests
 * pin the behaviour at the boundary, not the timeout itself.
 */
class EditInactivityTrackerTest {

    @Test
    fun `fresh tracker never expires`() {
        val tracker = EditInactivityTracker()
        assertFalse(tracker.shouldExit(0L))
        assertFalse(tracker.shouldExit(Long.MAX_VALUE))
    }

    @Test
    fun `entering starts the timer, still active just before the timeout`() {
        val tracker = EditInactivityTracker()
        tracker.enter(1_000L)
        assertFalse(tracker.shouldExit(1_000L))
        assertFalse(tracker.shouldExit(1_000L + EditInactivityTracker.EDIT_INACTIVITY_TIMEOUT_MS - 1))
    }

    @Test
    fun `expires exactly at the timeout`() {
        val tracker = EditInactivityTracker()
        tracker.enter(1_000L)
        assertTrue(
            tracker.shouldExit(1_000L + EditInactivityTracker.EDIT_INACTIVITY_TIMEOUT_MS)
        )
    }

    @Test
    fun `expires after the timeout`() {
        val tracker = EditInactivityTracker()
        tracker.enter(0L)
        assertTrue(tracker.shouldExit(60_000L))
    }

    @Test
    fun `a drag re-baselines the timer`() {
        val tracker = EditInactivityTracker()
        tracker.enter(0L)
        // A drag at 19 s: the old deadline passes, the new one does not.
        tracker.noteInteraction(19_000L)
        assertFalse(tracker.shouldExit(20_000L))
        assertFalse(tracker.shouldExit(19_000L + EditInactivityTracker.EDIT_INACTIVITY_TIMEOUT_MS - 1))
        assertTrue(tracker.shouldExit(19_000L + EditInactivityTracker.EDIT_INACTIVITY_TIMEOUT_MS))
    }

    @Test
    fun `re-entering re-baselines the timer`() {
        val tracker = EditInactivityTracker()
        tracker.enter(0L)
        tracker.enter(25_000L)
        assertFalse(tracker.shouldExit(25_000L + EditInactivityTracker.EDIT_INACTIVITY_TIMEOUT_MS - 1))
        assertTrue(tracker.shouldExit(25_000L + EditInactivityTracker.EDIT_INACTIVITY_TIMEOUT_MS))
    }

    @Test
    fun `exiting clears the timer`() {
        val tracker = EditInactivityTracker()
        tracker.enter(0L)
        tracker.exit()
        assertFalse(tracker.shouldExit(Long.MAX_VALUE))
    }

    @Test
    fun `a clock running backwards never expires`() {
        val tracker = EditInactivityTracker()
        tracker.enter(50_000L)
        assertFalse("an earlier reading is a clock jump, not inactivity", tracker.shouldExit(1_000L))
        // And the original deadline still applies from the real baseline.
        assertTrue(tracker.shouldExit(50_000L + EditInactivityTracker.EDIT_INACTIVITY_TIMEOUT_MS))
    }
}
