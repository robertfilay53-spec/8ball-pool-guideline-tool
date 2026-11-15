package com.poolguide.tool.overlay

import com.poolguide.tool.geometry.Vec2
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2

/**
 * Shot-in-progress detector (v1.0.37, REA hardening R1).
 *
 * The game reports FLAG_WINDOW_IS_OBSCURED for every touch that lands
 * on a visible window above it — including our FLAG_NOT_TOUCHABLE line
 * windows — so the lines must not exist over the table at the moment
 * a touch lands. We cannot observe the user's touches (our windows
 * pass them through), so the signal is the aim itself, from the
 * per-frame [DetectionResult]: while the user drags to aim, the
 * game's guideline angle swings; while a shot is in flight, the cue
 * ball moves. Either one marks the shot as in progress and the
 * overlay hides the lines; when the aim has been quiet for
 * [STABLE_FRAMES] consecutive frames, the lines come back.
 *
 * Pure Kotlin, no Android dependency — fully unit-testable on the JVM.
 * The thresholds are judgement calls, pinned by AimActivityTrackerTest:
 * detection jitter on a locked table is sub-degree / sub-pixel, while
 * a real drag swings the guideline several degrees per frame at the
 * 15 FPS cap, and a struck cue ball moves several pixels per frame.
 * A threshold that is too low would flicker the lines on jitter; one
 * that is too high would leave the lines up through the first frames
 * of a drag.
 */
class AimActivityTracker {

    companion object {
        /** Guideline-angle change per frame that counts as aiming. */
        const val ANGLE_DELTA_DEGREES = 2.0
        /** Cue-ball displacement per frame that counts as a shot in
         * flight (working-resolution pixels, 720 px wide). */
        const val CUE_MOVE_PX = 2.0
        /** Quiet frames before the aim counts as settled and the
         * lines come back (5 frames is about a third of a second at
         * the 15 FPS cap). */
        const val STABLE_FRAMES = 5

        /** Angle of a direction vector in degrees, for the tracker's
         * guideline input. */
        fun directionAngleDeg(direction: Vec2): Double =
            atan2(direction.y, direction.x) * 180.0 / PI
    }

    private var lastAngleDeg: Double? = null
    private var lastCue: Vec2? = null
    private var stableFrames = STABLE_FRAMES

    /** True while the aim is moving or a shot is in flight. */
    val isAiming: Boolean get() = stableFrames < STABLE_FRAMES

    /**
     * Feed one frame's aim state. [guidelineAngleDeg] is the angle of
     * the game's guideline (longest segment), or null when there is no
     * guideline this frame; [cueCenter] is the cue ball's centre, or
     * null when it was not detected. A missing signal contributes no
     * movement — it never marks aiming by itself. Returns [isAiming].
     */
    fun observe(guidelineAngleDeg: Double?, cueCenter: Vec2?): Boolean {
        var moved = false
        val lastAngle = lastAngleDeg
        if (guidelineAngleDeg != null && lastAngle != null &&
            angleDeltaDeg(lastAngle, guidelineAngleDeg) > ANGLE_DELTA_DEGREES
        ) {
            moved = true
        }
        val lastCueBall = lastCue
        if (cueCenter != null && lastCueBall != null &&
            cueCenter.distanceTo(lastCueBall) > CUE_MOVE_PX
        ) {
            moved = true
        }
        lastAngleDeg = guidelineAngleDeg
        lastCue = cueCenter
        stableFrames = if (moved) 0 else (stableFrames + 1).coerceAtMost(STABLE_FRAMES)
        return isAiming
    }

    /** Forget the last aim state (table unlocked, overlay torn down).
     * The next frame re-baselines instead of comparing against a
     * stale aim. */
    fun reset() {
        lastAngleDeg = null
        lastCue = null
        stableFrames = STABLE_FRAMES
    }

    /** Smallest absolute difference between two angles in degrees,
     * wrapping at 180 (a swing from 179 to -179 is 2 degrees, not
     * 358). */
    internal fun angleDeltaDeg(a: Double, b: Double): Double {
        val diff = abs(a - b) % 360.0
        return if (diff > 180.0) 360.0 - diff else diff
    }
}
