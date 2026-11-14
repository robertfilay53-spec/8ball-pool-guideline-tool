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
    val ballRadiusPxAt720: Double,
    /** Brown felt HSV window (v1.0.4). The tight v1.0.3 fit is the
     * default; a real brown table whose rail/felt separation differs
     * can be recalibrated per version (see [CalibrationStore] and the
     * Calibrate screen). Green/blue tables never use this window. */
    val brownHLo: Double = CvConfig.FELT_BROWN_LOWER[0],
    val brownSLo: Double = CvConfig.FELT_BROWN_LOWER[1],
    val brownVLo: Double = CvConfig.FELT_BROWN_LOWER[2],
    val brownHHi: Double = CvConfig.FELT_BROWN_UPPER[0],
    val brownSHi: Double = CvConfig.FELT_BROWN_UPPER[1],
    val brownVHi: Double = CvConfig.FELT_BROWN_UPPER[2]
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
        require(brownHLo in 0.0..179.0 && brownHHi in 0.0..179.0 && brownHLo <= brownHHi) {
            "brown H window out of range: $brownHLo..$brownHHi"
        }
        require(brownSLo in 0.0..255.0 && brownSHi in 0.0..255.0 && brownSLo <= brownSHi) {
            "brown S window out of range: $brownSLo..$brownSHi"
        }
        require(brownVLo in 0.0..255.0 && brownVHi in 0.0..255.0 && brownVLo <= brownVHi) {
            "brown V window out of range: $brownVLo..$brownVHi"
        }
    }

    /** Brown felt HSV lower bound, as OpenCV wants it. */
    val brownFeltLower: DoubleArray get() = doubleArrayOf(brownHLo, brownSLo, brownVLo)

    /** Brown felt HSV upper bound, as OpenCV wants it. */
    val brownFeltUpper: DoubleArray get() = doubleArrayOf(brownHHi, brownSHi, brownVHi)

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

        /** Parse a stored "hLo,sLo,vLo,hHi,sHi,vHi" brown window, or null
         * when the string is missing or malformed. */
        fun parseBrownWindow(raw: String?): DoubleArray? {
            if (raw.isNullOrBlank()) return null
            val parts = raw.split(",").mapNotNull { it.toDoubleOrNull() }
            if (parts.size != 6) return null
            return parts.toDoubleArray()
        }

        /** Serialise a brown window for storage. */
        fun serializeBrownWindow(lower: DoubleArray, upper: DoubleArray): String {
            require(lower.size == 3 && upper.size == 3) {
                "brown window bounds must have 3 channels each"
            }
            return listOf(lower[0], lower[1], lower[2], upper[0], upper[1], upper[2])
                .joinToString(",")
        }
    }
}
