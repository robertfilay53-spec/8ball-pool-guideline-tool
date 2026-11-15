package com.poolguide.tool.overlay

/**
 * EDIT-mode inactivity timer (v1.0.41, REA hardening R4).
 *
 * EDIT mode leaves two 48 dp touchable anchor handles sitting on the
 * table, exactly where the user taps to shoot. A touch landing on
 * one sets FLAG_WINDOW_IS_OBSCURED for the game, so a forgotten EDIT
 * session is the one way a touchable window can eat a shot (signal
 * S1 in the REA deep dive). If no handle drag has happened for
 * [EDIT_INACTIVITY_TIMEOUT_MS], EDIT mode exits on its own, the
 * handles are removed, and live detection resumes — the user never
 * has to remember to tap Done.
 *
 * Pure Kotlin, no Android dependency — fully unit-testable on the JVM.
 * The timeout is a judgement call, pinned by EditInactivityTrackerTest:
 * 20 seconds is long enough to study a frozen shot and nudge a handle
 * without being rushed, and short enough that a forgotten session
 * cannot sit through a whole turn. Entering EDIT mode and every
 * handle drag both re-baseline the timer; the Done button exits
 * through the normal path and clears it.
 *
 * The clock is [SystemClock.uptimeMillis] at the call sites (the same
 * monotonic source FramePacer uses), passed in here as a plain Long
 * so the tests can drive the timer exactly. A clock that runs
 * backwards (a negative elapsed time) never expires — the timer just
 * waits for a later reading.
 */
class EditInactivityTracker {

    companion object {
        /** No handle drag for this long exits EDIT mode (20 s). */
        const val EDIT_INACTIVITY_TIMEOUT_MS = 20_000L
    }

    /** Uptime of the last EDIT entry or handle drag, or null when
     * EDIT mode is not active. */
    private var lastInteractionMs: Long? = null

    /** EDIT mode was entered (or re-entered): the timer starts now. */
    fun enter(nowMs: Long) {
        lastInteractionMs = nowMs
    }

    /** A handle was dragged: the timer re-baselines to now. */
    fun noteInteraction(nowMs: Long) {
        lastInteractionMs = nowMs
    }

    /** EDIT mode was left (Done button or auto-exit): no timer runs. */
    fun exit() {
        lastInteractionMs = null
    }

    /** True when EDIT mode is active and the last interaction is at
     * least [EDIT_INACTIVITY_TIMEOUT_MS] ago. False when EDIT mode
     * is not active, and false when the clock has run backwards
     * since the last interaction. */
    fun shouldExit(nowMs: Long): Boolean {
        val last = lastInteractionMs ?: return false
        return nowMs - last >= EDIT_INACTIVITY_TIMEOUT_MS
    }
}
