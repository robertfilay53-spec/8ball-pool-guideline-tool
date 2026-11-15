package com.poolguide.tool.data

import com.poolguide.tool.cv.Ball
import com.poolguide.tool.cv.BallColor
import com.poolguide.tool.cv.DetectionResult
import com.poolguide.tool.geometry.Vec2
import kotlin.math.roundToInt

/**
 * Session pot-rate tracker (v1.0.42, REA hardening R6).
 *
 * The deep dive asks the tool to show the user their session pot
 * rate, so play that looks inhuman on the server side (signal S13)
 * is visible to the user while they play instead of only after a
 * sanction. The data does NOT already exist in ShotHistory: that
 * strip records solved shots the player SAW, not shots taken or
 * balls potted. This tracker derives outcomes from the frame
 * stream itself, as a small state machine over DetectionResult
 * frames (same pattern as AimActivityTracker and
 * EditInactivityTracker): an aimed shot arms it, a struck cue ball
 * counts a shot taken, and a ball that is gone once the table
 * settles counts as potted.
 *
 * States: IDLE (no shot armed) -> AIMING (a shot is solved and the
 * table is locked; the snapshot refreshes every frame while the
 * player aims) -> SCATTER (the cue ball jumped, so a shot was
 * struck; waits for the balls to settle) -> IDLE (settled: missing
 * object balls are counted as pots).
 *
 * A one-frame cue jump does not count a shot: the jump must persist
 * for [SHOT_CONFIRM_FRAMES] frames, so a single mis-detected cue
 * centre cannot open a false shot. A shot whose balls never settle
 * within [SETTLE_TIMEOUT_FRAMES] keeps its shot count but gets no
 * pot count (outcome unknown, not a miss). A missing cue ball at
 * settle time is a scratch, never a pot. Balls that appear only in
 * the settled frame (detection flicker adding one) are ignored:
 * only balls missing from the pre-shot snapshot count.
 *
 * Pure Kotlin + JDK only (no Android dependency), so it is fully
 * unit-testable on the JVM. Every threshold is a judgement call
 * pinned by ShotOutcomeTrackerTest, not device data: detection
 * jitter is a few px, a struck cue ball moves tens of px per frame,
 * and a settled ball sits within a couple of px of its last spot.
 *
 * Honest limits: the tracker counts shots taken while a shot is
 * solved and locked during the capture session. It cannot tell
 * whose turn it is, so a shot taken while the user leaves capture
 * running through an opponent's turn counts too. The reminder is
 * a nudge to vary play, not a ban-risk meter: no tool can
 * guarantee "no ban".
 */
class ShotOutcomeTracker {

    companion object {
        /** Cue-ball displacement, in ball diameters, that counts as
         * a struck shot. Aiming never moves the cue ball; jitter is
         * a few px, a ball diameter is about 18 px at radius 9. */
        const val SHOT_MOVE_BALL_DIAMETERS = 1.5

        /** Consecutive frames the cue must stay displaced before a
         * shot counts, so one mis-detected frame cannot fake one. */
        const val SHOT_CONFIRM_FRAMES = 2

        /** Consecutive stable frames that count as settled (about
         * a third of a second at the 15 FPS cap). */
        const val SETTLE_STABLE_FRAMES = 5

        /** Largest per-ball frame-to-frame movement, in px, that
         * still counts as settled. */
        const val STABLE_PX = 2.0

        /** Longest a scatter may run before the outcome is given
         * up (about 10 s at 15 FPS). The shot stays counted. */
        const val SETTLE_TIMEOUT_FRAMES = 150

        /** Shots needed before the pot rate means anything. */
        const val MIN_SHOTS_FOR_REMINDER = 10

        /** Pot rate at or above this, over at least
         * [MIN_SHOTS_FOR_REMINDER] shots, shows the reminder. */
        const val REMIND_POT_RATE = 0.80
    }

    private enum class State { IDLE, AIMING, SCATTER }

    /** Shots taken this session (cue ball struck after an aim). */
    var shotsTaken = 0
        private set

    /** Balls potted this session (gone once the table settled). */
    var pots = 0
        private set

    private var state = State.IDLE
    private var snapshotBalls: List<Ball> = emptyList()
    private var snapshotCue: Ball? = null
    private var prevBalls: List<Ball> = emptyList()
    private var pendingShotFrames = 0
    private var stableFrames = 0
    private var scatterFrames = 0

    /** Feed one frame. Callers pass every live frame, including
     * black and unlocked ones: those abandon a pending shot. */
    fun observe(result: DetectionResult) {
        if (result.isCaptureBlack || !result.tableLocked) {
            resetPending()
            return
        }
        val solved = result.shot != null || result.bank != null ||
            result.kick != null || result.bank2 != null ||
            result.kick2 != null || result.combo != null ||
            result.kiss != null
        when (state) {
            State.IDLE -> {
                if (solved && result.cueBall != null) {
                    snapshotBalls = result.balls
                    snapshotCue = result.cueBall
                    state = State.AIMING
                }
            }
            State.AIMING -> {
                val cue = result.cueBall
                if (!solved || cue == null) {
                    resetPending()
                    return
                }
                val snapCue = snapshotCue
                if (snapCue != null &&
                    cue.center.distanceTo(snapCue.center) >
                    SHOT_MOVE_BALL_DIAMETERS * 2.0 * snapCue.radiusPx
                ) {
                    pendingShotFrames++
                    if (pendingShotFrames >= SHOT_CONFIRM_FRAMES) {
                        shotsTaken++
                        prevBalls = result.balls
                        stableFrames = 0
                        scatterFrames = 0
                        state = State.SCATTER
                    }
                    return
                }
                pendingShotFrames = 0
                snapshotBalls = result.balls
                snapshotCue = cue
            }
            State.SCATTER -> {
                scatterFrames++
                if (scatterFrames > SETTLE_TIMEOUT_FRAMES) {
                    resetPending()
                    return
                }
                if (result.balls.isEmpty()) {
                    // No balls detected this frame: cannot judge a
                    // settle, and an empty frame must never read as
                    // "every ball potted". Wait for a real frame.
                    stableFrames = 0
                    prevBalls = result.balls
                    return
                }
                if (ballsStable(prevBalls, result.balls, STABLE_PX)) {
                    stableFrames++
                } else {
                    stableFrames = 0
                }
                prevBalls = result.balls
                if (stableFrames >= SETTLE_STABLE_FRAMES) {
                    pots += countPotted(
                        snapshotBalls, snapshotCue, result.balls, result.cueBall
                    )
                    resetPending()
                }
            }
        }
    }

    /** Pot rate as a fraction (0.0-1.0), or null before any shot. */
    fun potRate(): Double? =
        if (shotsTaken == 0) null else pots.toDouble() / shotsTaken

    /** True once enough shots are in and the rate is abnormally
     * high: the user should vary their play. */
    fun shouldRemind(): Boolean {
        val rate = potRate() ?: return false
        return shotsTaken >= MIN_SHOTS_FOR_REMINDER && rate >= REMIND_POT_RATE
    }

    /** One-line session summary for the bubble and the diagnostics
     * export: "Pots 7/10 (70%)", or "No shots taken yet". */
    fun format(): String {
        val rate = potRate() ?: return "No shots taken yet"
        return "Pots $pots/$shotsTaken (${(rate * 100.0).roundToInt()}%)"
    }

    /** Drop a pending shot (aim abandoned, table lost, black
     * capture, settle timeout). Counters are kept. */
    fun resetPending() {
        state = State.IDLE
        snapshotBalls = emptyList()
        snapshotCue = null
        prevBalls = emptyList()
        pendingShotFrames = 0
        stableFrames = 0
        scatterFrames = 0
    }

    /** Start a new session: pending shot and counters cleared. */
    fun reset() {
        resetPending()
        shotsTaken = 0
        pots = 0
    }
}

/** True when the two frames show the same settled table: same ball
 * count, and every ball in [prev] has a counterpart in [curr]
 * within [stablePx]. A ball appearing, disappearing, or jumping
 * further than that is still moving (or flickering), not settled. */
internal fun ballsStable(prev: List<Ball>, curr: List<Ball>, stablePx: Double): Boolean {
    if (prev.size != curr.size) return false
    val remaining = curr.toMutableList()
    for (ball in prev) {
        val match = nearestWithin(remaining, ball.center, stablePx) ?: return false
        remaining.remove(match)
    }
    return true
}

/** How many snapshot balls were potted: present before the shot,
 * gone once the table settled. The cue ball is matched first and
 * never counts (a missing cue ball is a scratch): it is the settled
 * frame's cue ball by identity when known, else the white ball by
 * colour. Each remaining snapshot ball needs a settled ball within
 * one ball diameter of its old spot; anything else is a pot. */
internal fun countPotted(
    snapshot: List<Ball>,
    snapshotCue: Ball?,
    settled: List<Ball>,
    settledCue: Ball?
): Int {
    val remaining = settled.toMutableList()
    val cueInSettled = settledCue?.takeIf { it in remaining }
        ?: remaining.firstOrNull { it.color == BallColor.WHITE }
    if (cueInSettled != null) remaining.remove(cueInSettled)
    var missing = 0
    for (ball in snapshot) {
        if (ball == snapshotCue) continue
        if (ball.color == BallColor.WHITE) continue
        val match = nearestWithin(remaining, ball.center, 2.0 * ball.radiusPx)
        if (match == null) {
            missing++
        } else {
            remaining.remove(match)
        }
    }
    return missing
}

/** Nearest ball in [balls] to [point] within [radiusPx], or null.
 * The ball is returned, not just found, so callers can consume it
 * from a greedy match pool. */
internal fun nearestWithin(balls: List<Ball>, point: Vec2, radiusPx: Double): Ball? {
    var best: Ball? = null
    var bestDist = radiusPx
    for (ball in balls) {
        val d = ball.center.distanceTo(point)
        if (d <= bestDist) {
            bestDist = d
            best = ball
        }
    }
    return best
}
