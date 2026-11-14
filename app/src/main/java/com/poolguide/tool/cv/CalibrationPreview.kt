package com.poolguide.tool.cv

import com.poolguide.tool.data.GameProfile
import kotlin.math.roundToInt

/**
 * Pure-Kotlin slider <-> value mappings and preview-status decision for
 * the calibration screen (v1.0.8). No Android / OpenCV dependency, so it
 * is unit-testable; [com.poolguide.tool.ui.CalibrationActivity] calls it
 * and holds all Activity state.
 *
 * Sliders:
 * - brown H: 0-179, S/V: 0-255 (direct, one slider step = one HSV unit).
 * - pocket inset / pocket radius: 0-0.1 as a slider 0-1000 (value =
 *   slider / 10000, so one step = 0.0001).
 * - ball radius at 720 px: 1-40 (direct, one step = 1 px).
 */
object CalibrationPreview {

    const val BROWN_H_MAX = 179
    const val BROWN_SV_MAX = 255
    const val FRACTION_SLIDER_MAX = 1000
    const val BALL_RADIUS_MIN = 1
    const val BALL_RADIUS_MAX = 40

    /** Clamp an int slider position into [min, max]. */
    fun clampSlider(value: Int, min: Int, max: Int): Int = value.coerceIn(min, max)

    /** Brown H/S/V slider position -> HSV value (direct mapping). */
    fun brownSliderToValue(slider: Int, max: Int): Int = clampSlider(slider, 0, max)

    /** HSV value -> brown slider position (direct mapping, clamped). */
    fun brownValueToSlider(value: Double, max: Int): Int =
        clampSlider(value.roundToInt(), 0, max)

    /** Pocket fraction slider (0-1000) -> fraction (0-0.1). */
    fun fractionSliderToValue(slider: Int): Double =
        clampSlider(slider, 0, FRACTION_SLIDER_MAX) / 10000.0

    /** Pocket fraction (0-0.1) -> slider position (0-1000, clamped). */
    fun fractionValueToSlider(value: Double): Int =
        clampSlider((value * 10000.0).roundToInt(), 0, FRACTION_SLIDER_MAX)

    /** Ball-radius slider (1-40) -> radius in px at 720 wide. */
    fun ballRadiusSliderToValue(slider: Int): Double =
        clampSlider(slider, BALL_RADIUS_MIN, BALL_RADIUS_MAX).toDouble()

    /** Ball radius in px -> slider position (1-40, clamped). */
    fun ballRadiusValueToSlider(value: Double): Int =
        clampSlider(value.roundToInt(), BALL_RADIUS_MIN, BALL_RADIUS_MAX)

    /** Mask coverage as a fraction of the frame: non-zero mask pixels /
     * total frame pixels. Returns 0.0 for a degenerate frame. */
    fun maskCoverage(nonZeroPixels: Long, totalPixels: Long): Double {
        if (totalPixels <= 0 || nonZeroPixels <= 0) return 0.0
        return (nonZeroPixels.toDouble() / totalPixels.toDouble()).coerceIn(0.0, 1.0)
    }

    /** Coverage fraction (0-1) formatted as a percent with one decimal,
     * e.g. 0.123 -> "12.3". */
    fun formatCoveragePercent(coverage: Double): String =
        "%.1f".format(coverage.coerceIn(0.0, 1.0) * 100.0)

    /** The preview-status decision. [tableFound] is null when there is no
     * frame to check (or the profile from the sliders is invalid). */
    enum class PreviewStatus { FOUND, NOT_FOUND, NO_FRAME }

    fun decideStatus(tableFound: Boolean?, maskCoverage: Double?): PreviewStatus {
        if (tableFound == null) return PreviewStatus.NO_FRAME
        return if (tableFound) PreviewStatus.FOUND else PreviewStatus.NOT_FOUND
    }

    /** The [GameProfile] the current slider positions describe, or null
     * when the positions are invalid (e.g. a low above its high — the
     * constructor rejects it, and the caller must never persist it). */
    fun profileFromSliders(
        version: String,
        brownHLo: Int, brownHHi: Int,
        brownSLo: Int, brownSHi: Int,
        brownVLo: Int, brownVHi: Int,
        pocketInsetSlider: Int, pocketRadiusSlider: Int,
        ballRadiusSlider: Int
    ): GameProfile? {
        return try {
            GameProfile(
                version = version,
                pocketInsetFraction = fractionSliderToValue(pocketInsetSlider),
                pocketRadiusFraction = fractionSliderToValue(pocketRadiusSlider),
                ballRadiusPxAt720 = ballRadiusSliderToValue(ballRadiusSlider),
                brownHLo = brownSliderToValue(brownHLo, BROWN_H_MAX).toDouble(),
                brownHHi = brownSliderToValue(brownHHi, BROWN_H_MAX).toDouble(),
                brownSLo = brownSliderToValue(brownSLo, BROWN_SV_MAX).toDouble(),
                brownSHi = brownSliderToValue(brownSHi, BROWN_SV_MAX).toDouble(),
                brownVLo = brownSliderToValue(brownVLo, BROWN_SV_MAX).toDouble(),
                brownVHi = brownSliderToValue(brownVHi, BROWN_SV_MAX).toDouble()
            )
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /** Slider positions for [GameProfile.DEFAULT] (the reset target). */
    fun defaultSliders(): Map<String, Int> {
        val d = GameProfile.DEFAULT
        return mapOf(
            "brownHLo" to brownValueToSlider(d.brownHLo, BROWN_H_MAX),
            "brownHHi" to brownValueToSlider(d.brownHHi, BROWN_H_MAX),
            "brownSLo" to brownValueToSlider(d.brownSLo, BROWN_SV_MAX),
            "brownSHi" to brownValueToSlider(d.brownSHi, BROWN_SV_MAX),
            "brownVLo" to brownValueToSlider(d.brownVLo, BROWN_SV_MAX),
            "brownVHi" to brownValueToSlider(d.brownVHi, BROWN_SV_MAX),
            "pocketInset" to fractionValueToSlider(d.pocketInsetFraction),
            "pocketRadius" to fractionValueToSlider(d.pocketRadiusFraction),
            "ballRadius" to ballRadiusValueToSlider(d.ballRadiusPxAt720)
        )
    }
}
