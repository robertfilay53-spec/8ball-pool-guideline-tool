package com.poolguide.tool.data

import com.poolguide.tool.cv.CvConfig

/**
 * Per-game-version calibration profile (aimcall lead: each game version
 * keeps its own table calibration).
 *
 * Why per-version: the game updates move the table (felt colour, pocket
 * positions, ball sizes in the render). A calibration tuned for 56.35.0
 * can be silently wrong on the next version — the exact failure mode
 * Jawad flagged (hardcoded geometry from an old version). The table
 * corners already live per version in [CalibrationStore]; this profile
 * carries the three numbers that depend on the same version:
 * pocket inset + pocket radius (fractions of the table width) and the
 * ball radius at the 720-wide working resolution.
 *
 * Pure Kotlin (no Android dependency) so it is unit-testable.
 * Unknown versions fall back to [DEFAULT] via [forVersion] — a profile
 * is a calibration hint, never a hard failure.
 */
data class GameProfile(
    val version: String,
    /** Pocket inset as a fraction of the table width. */
    val pocketInsetFraction: Double,
    /** Pocket radius as a fraction of the table width (working res). */
    val pocketRadiusFraction: Double,
    /** Detected ball radius in px at the 720-wide working resolution. */
    val ballRadiusPxAt720: Double
) {
    init {
        require(pocketInsetFraction in 0.0..0.1) {
            "pocketInsetFraction out of range: $pocketInsetFraction"
        }
        require(pocketRadiusFraction in 0.0..0.1) {
            "pocketRadiusFraction out of range: $pocketRadiusFraction"
        }
        require(ballRadiusPxAt720 in 1.0..40.0) {
            "ballRadiusPxAt720 out of range: $ballRadiusPxAt720"
        }
    }

    companion object {
        /** Defaults, matching the values the pipeline used before
         * profiles existed (CvConfig). Version 56.35.0 is the game
         * version the tool was built and tested against. */
        val DEFAULT = GameProfile(
            version = CalibrationStore.DEFAULT_GAME_VERSION,
            pocketInsetFraction = CvConfig.POCKET_INSET_FRACTION,
            pocketRadiusFraction = CvConfig.POCKET_RADIUS_FRACTION,
            ballRadiusPxAt720 = CvConfig.BALL_RADIUS_PX_AT_720
        )

        /** Profile for [version]: the stored one when it exists, else the
         * defaults stamped with [version]. */
        fun forVersion(version: String): GameProfile = DEFAULT.copy(version = version)
    }
}
